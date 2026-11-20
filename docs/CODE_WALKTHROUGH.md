# Code Walkthrough: what every part of the program does

Read this once before your presentation. It explains the program in plain language, from the idea down to the individual classes, and ends with the questions your reviewers are likely to ask.

---

## 1. The big picture

A bank, at its core, must do four things correctly:

1. **Know who you are** (identity, KYC, login).
2. **Hold accounts** that have balances.
3. **Move money** so that no cent is ever created, lost, or charged twice, even if the network fails, the user double-clicks, or 50 requests arrive at the same millisecond.
4. **Prove it** with a ledger that always balances and can be audited.

This project is a backend (a REST API) that does exactly that:

```
 client (Swagger / curl / mobile app)
        │  1. logs in at Keycloak, gets a signed token (JWT)
        │  2. calls our API with  Authorization: Bearer <token>
        ▼
 ┌─────────────── Spring Boot application ────────────────┐
 │  web/       controllers: receive HTTP, check roles      │
 │  service/   business rules: KYC, fraud, transfers      │
 │  repository/ explicit SQL against PostgreSQL            │
 │  events/    outbox → Kafka, Kafka → notifications       │
 └───────────────────────┬───────────────────────────────┘
                         ▼
 PostgreSQL (the source of truth: accounts, ledger, ...)    Kafka (events)    Keycloak (identity)
```

Three layers, each with one job: **controllers** talk HTTP and check *who may call*; **services** hold the *rules*; **repositories** run *SQL*. The database itself is the last line of defence: it refuses wrong data even if the Java code had a bug.

---

## 2. The database (Flyway migrations)

Flyway is a tool that runs numbered SQL files (`V1__...sql`, `V2__...sql`) in order, exactly once, and remembers which ran. This is how database changes are version-controlled like code. On startup the app asks Flyway to bring the database up to date.

| Migration | Creates | Why |
|---|---|---|
| `V1__core_schema.sql` | `customers`, `accounts`, `transactions`, `ledger_entries`, `idempotency_keys`, plus two **triggers** | the heart of the bank |
| `V2__kyc_and_fraud.sql` | `kyc_submissions`, `fraud_alerts` | identity workflow and blocked-transfer log |
| `V3__outbox_notifications_statements.sql` | `outbox_events`, `notifications`, `account_statements` | Kafka events, notifications, month-end snapshots |
| `V4__seed_system_accounts.sql` | one bank-owned account `SYS-CASH-NPR` | the counter-party for cash deposits/withdrawals |

Important design choices in the tables:

- **Money is `NUMERIC(19,2)`**, never `float`. Floating point cannot represent 0.10 exactly; a bank cannot tolerate that.
- `accounts.balance` is a **cached total**. The ledger is the truth; the balance is kept in step inside the same transaction (and checked by reconciliation).
- `CHECK (allow_negative OR balance >= 0)`: an ordinary account can **never** go below zero, even if every line of Java was wrong. Only the `SYSTEM` cash account may go negative (§3).
- `customers.keycloak_user_id` links a Keycloak login to a bank customer (the `sub` claim of the token).
- Only **one pending KYC submission per customer** (a partial unique index).

---

## 3. Double-entry bookkeeping (milestone 1)

**The rule:** money is never "added" or "subtracted" on its own. Every movement is written as **two entries that cancel out**: a *debit* (money leaves) and a *credit* (money arrives). Together they sum to **zero**. We store a debit as a negative number and a credit as a positive number.

**Example.** Alice sends 2,500 to Bob. One `transactions` row and two `ledger_entries`:

| entry | account | amount | balance after |
|---|---|---|---|
| debit | Alice | **−2,500.00** | 7,500.00 |
| credit | Bob | **+2,500.00** | 2,500.00 |
| | **sum** | **0.00** | |

**Cash deposits** also balance, using the bank's own account as the other side. Tina deposits 10,000 cash for Alice:

| entry | account | amount |
|---|---|---|
| debit | `SYS-CASH-NPR` (bank cash) | −10,000.00 |
| credit | Alice | +10,000.00 |
| | **sum** | **0.00** |

So **the sum of every ledger entry in the whole bank is always exactly 0**. The cash account shows how much cash the bank has taken in (as a negative, "the bank owes this to customers"). This single fact is checkable at any moment and is what auditors want.

**Where it is enforced:**

1. `LedgerService.post(...)`: the only code that writes ledger lines. It checks amount > 0, both accounts ACTIVE, same currency, enough money, then writes `-amount` and `+amount`, and updates both balances. Its transaction mode is `MANDATORY`: it *refuses to run* outside an existing database transaction, so ledger lines can never be half-written.
2. **A database trigger** (`trg_ledger_balanced`, deferred): at COMMIT time the database sums the entries of the transaction and rejects it if they are not zero. *We tested this against real PostgreSQL.*
3. **An append-only trigger** (`trg_ledger_append_only`): `UPDATE` and `DELETE` on `ledger_entries` always fail. Mistakes are corrected by adding new entries, never by editing history.

---

## 4. The life of one transfer (milestone 3)

`POST /api/v1/transfers` with header `Idempotency-Key` and body `{source, destination, amount}`.

```
TransferController          checks role CUSTOMER, validates the JSON (amount >= 0.01, 2 decimals)
  └─ TransferService        (not transactional; catches a fraud block after the rollback)
       └─ TransferTransactions.transfer(...)      ← ONE database transaction starts here
            1. requireKey, normalise amount (exactly 2 decimals), source != destination
            2. IDEMPOTENCY: try to insert (caller, key) into idempotency_keys
                  - inserted  → first time, continue
                  - existed   → same request? return the ORIGINAL result (HTTP 200, "Idempotent-Replay: true")
                              → different request? 422 IDEMPOTENCY_KEY_REUSED
            3. AUTHORISE: does the caller own the source account? is the destination a real customer
                  account? is the caller's KYC APPROVED?
            4. LOCK both account rows (SELECT ... ORDER BY id FOR NO KEY UPDATE)
            5. FRAUD rules, evaluated while the lock is held
            6. LedgerService.post → transaction row + debit entry + credit entry + both balances
            7. EventPublisher → one row in outbox_events (the "transfer completed" event)
            8. store the resulting transaction id on the idempotency key
       ← COMMIT: all of 2–8 become visible together, or (on any exception) none of them do
```

Any failure (insufficient funds, fraud block, database error) throws an exception → Spring **rolls the whole transaction back** → no money moved, no event queued, and the idempotency key is also rolled back, so the client may safely **retry with the same key**.

HTTP result: `201 Created` for a new transfer; `200 OK` + `Idempotent-Replay: true` for a replay.

**Why `TransferService` and `TransferTransactions` are two classes:** the fraud alert must be saved even though the transfer is rolled back. `TransferService` calls the transactional class, catches the fraud exception *after* the rollback has completed, and then saves the alert in its own short transaction. (Spring only applies `@Transactional` across a call between two different beans, hence the split.)

---

## 5. Idempotency: "retries never double-charge"

**The problem:** you tap "Pay", the response is lost, the app retries. Without protection the money moves twice.

**The solution:** the client generates a unique `Idempotency-Key` (e.g. a UUID) per *logical* payment and sends the same key on every retry. The table `idempotency_keys` has the primary key `(caller, idem_key)`.

- The key row is inserted **in the same database transaction** as the money movement, so *the key exists if and only if the transfer committed.*
- A SHA-256 **hash of the request** (accounts, amount, description) is stored with it. Same key + same hash → return the original transaction. Same key + different hash → error 422 (the client has a bug).
- **Concurrent duplicates** (20 identical requests at the same instant): the insert uses `ON CONFLICT DO NOTHING`; PostgreSQL makes the 19 losers *wait* until the winner commits, then they find the key and return the winner's result. Verified: 20 requests → 1 transfer, 19 replays, debited once.

---

## 6. Concurrency: locks, no lost updates, no deadlocks

**Lost update:** two transfers each read balance 1,000, each subtract 600, each write 400. Result: 800 was spent from 1,000 but the balance shows 400. The system allowed a 1,200 spend.

**Our defence:** before reading balances, the transaction runs `SELECT ... FOR NO KEY UPDATE` on both account rows. A second transaction touching the same row **waits** until the first commits, then reads the fresh balance. Verified: 30 parallel transfers of 100 from a 1,000 balance → exactly 10 succeed, 20 are rejected for insufficient funds, balance ends at 0.

**Deadlock:** A→B takes lock on A then waits for B; at the same time B→A takes lock on B and waits for A; both wait forever. **Fix:** every transaction locks accounts **in ascending id order** (`ORDER BY id`). Whoever gets the lower id first wins; the other waits. Verified: 40 simultaneous opposite transfers, zero deadlocks.

*Why `NO KEY UPDATE` rather than `UPDATE`?* It excludes every other writer of the balance, but does not block the foreign-key checks that other inserts perform. It is the weaker lock that is still strong enough.

---

## 7. Fraud rules (milestone 6)

`FraudService.check` runs **while the source row is locked**, so concurrent requests are evaluated one after another and cannot jointly slip under a limit:

| Rule | Default (application.yml) | Result |
|---|---|---|
| `MAX_TRANSFER_AMOUNT` | one transfer > NPR 500,000 | 422 `FRAUD_MAX_TRANSFER_AMOUNT` |
| `DAILY_LIMIT` | outgoing transfers today (UTC) + this one > NPR 1,000,000 | 422 `FRAUD_DAILY_LIMIT` |
| `VELOCITY` | already 5 transfers in the last 60 seconds | 429 `FRAUD_VELOCITY` |

Every block is written to `fraud_alerts` (visible to compliance at `GET /api/v1/admin/fraud-alerts`). The limits are configuration, not code. Teller cash deposits are not subject to these customer-transfer rules.

---

## 8. KYC workflow (milestone 6)

```
NOT_SUBMITTED ──submit──▶ PENDING ──approve──▶ APPROVED
                              └──reject (reason required)──▶ REJECTED ──submit again──▶ PENDING
```

- `KycService.submit`: customer sends document type/number, date of birth, address. Under-18 is refused. Duplicate pending submissions are refused (409).
- `approve` / `reject`: only roles `compliance` or `admin`. The submission row is **locked** so two reviewers cannot decide the same case. **Four-eyes rule:** nobody can review their *own* KYC.
- Until `APPROVED`, a customer **cannot open an account or transfer money** (403 `KYC_REQUIRED`).
- Each change also writes a Kafka event, which the consumer turns into a notification ("Your KYC was approved").
- Only document *metadata* is stored, not images (a stated limitation).

---

## 9. Security with Keycloak (milestone 4)

Keycloak is a separate identity server. **Our app never sees a password.**

1. The user logs in at Keycloak → receives a **JWT access token**: a signed JSON blob containing `sub` (user id), name, email, expiry, and `realm_access.roles`.
2. The client sends it as `Authorization: Bearer <token>`.
3. Spring Security (`SecurityConfig`, configured as an *OAuth2 resource server*) downloads Keycloak's public keys, **verifies the signature, expiry and issuer** of every token. A forged or expired token → `401`.
4. `KeycloakRealmRoleConverter` turns roles into authorities: `customer` → `ROLE_CUSTOMER` etc.
5. Each controller method has `@PreAuthorize("hasRole('...')")`. Wrong role → `403`.
6. `Caller` is a small object extracted from the token (subject, name, email, roles) that the services use.

**Roles:** `customer`, `teller`, `compliance`, `admin` (defined in `keycloak/bank-realm.json`, with five demo users, imported automatically at container start).

**Object-level security:** having the role is not enough. A customer can read only **their own** accounts and transactions; for someone else's account the API answers *404 Not Found* (not 403), so it does not even reveal which ids exist.

---

## 10. Kafka events (milestone 5): the transactional outbox

Goal: when a transfer completes, tell other systems (notifications, statements) **without** coupling them to the money path.

**The trap:** "commit to the database, then send to Kafka" can lose the event if the app crashes in between; "send to Kafka, then commit" can announce a transfer that then rolls back. The two systems cannot share one transaction.

**The pattern we use, the *transactional outbox*:**

1. In step 7 of the transfer, `EventPublisher` inserts an event row into `outbox_events`, **in the same database transaction** (it requires one: `Propagation.MANDATORY`). So the event exists iff the transfer committed.
2. `OutboxPublisher`, a background job every 500 ms, claims unpublished rows (`FOR UPDATE SKIP LOCKED`, so several app instances never send the same row), sends each to Kafka, waits for the broker's acknowledgement (`acks=all`, idempotent producer), then marks it published. If Kafka is down it leaves them and retries later: **transfers keep working while Kafka is down**.
3. Delivery is therefore **at-least-once** (a crash between send and mark sends it twice). Consumers must de-duplicate.
4. `NotificationConsumer` (`@KafkaListener`) reads `bank.transactions.completed` and `bank.kyc.status-changed` and inserts notifications; the unique key `(event_id, customer_id)` makes a duplicate delivery a no-op. Customers read them at `GET /api/v1/notifications`.

Topics: `bank.transactions.completed` (3 partitions, key = source account id, so one account's events stay in order) and `bank.kyc.status-changed`.

---

## 11. Monthly statements (milestone 7)

`GET /api/v1/accounts/{id}/statements/2026-10` (and `/csv`):

- **Opening balance** = sum of all ledger entries before the month starts.
- **Lines** = that month's ledger entries (joined with their transaction description).
- **Credits / debits / closing balance** computed from the lines. `closing = opening + credits − debits`.
- For a **closed** month, a stored snapshot in `account_statements` is used if present; the scheduler writes snapshots at 01:00 UTC on the 1st of each month (`StatementScheduler`; admins can trigger it with `POST /api/v1/admin/statements/generate?period=2026-10`).
- The CSV export neutralises spreadsheet formula injection (a description starting with `=`, `+`, `-`, `@` is prefixed with `'`).

---

## 12. Reconciliation: proving the books balance

`GET /api/v1/admin/reconciliation` (admin) runs three checks and returns `healthy: true/false`:

1. The sum of **all** ledger entries is 0.
2. **Every** transaction's entries sum to 0.
3. Every account's cached `balance` equals the sum of its ledger entries.

Run it after any load test. It is the final "show me" answer to *does the ledger always balance?*.

---

## 13. Errors

`GlobalExceptionHandler` converts every exception into one JSON shape (RFC 7807): `{ "title", "status", "detail", "code" }`, with a stable `code`:

`INSUFFICIENT_FUNDS` (422) · `IDEMPOTENCY_KEY_REUSED` (422) · `KYC_REQUIRED` (403) · `FRAUD_MAX_TRANSFER_AMOUNT` / `FRAUD_DAILY_LIMIT` (422) · `FRAUD_VELOCITY` (429) · `VALIDATION_FAILED` (400, with a per-field list) · `NOT_FOUND` (404) · `FORBIDDEN` (403) · `CONFLICT` (409) · `CURRENCY_MISMATCH`, `ACCOUNT_NOT_ACTIVE`, `UNDERAGE` (422).

---

## 14. The tests (milestone 7)

All integration tests run against a **real PostgreSQL 16** in Docker (Testcontainers), not an in-memory fake, because the behaviour under test (locks, triggers, `ON CONFLICT`) *is* PostgreSQL's behaviour.

| Class | Highlights |
|---|---|
| `TransferConcurrencyTest` | parallel debits cannot overdraw; same key ×20 charges once; opposite transfers don't deadlock; many senders → one receiver exact |
| `LedgerInvariantTest` | unbalanced insert rejected at commit; ledger update/delete rejected; overdraft rejected even via raw SQL; reconciliation healthy |
| `FraudRulesTest` | each rule triggers; alert is stored although the transfer rolled back; deposits not throttled |
| `KycWorkflowTest` | full lifecycle, resubmission, four-eyes, underage, KYC gate on accounts and transfers |
| `StatementAndOutboxTest` | statement arithmetic, privacy between customers, outbox row only when the transfer commits |
| `ApiSecurityTest` | 401 without token, 403 wrong role, 404 for others' accounts, JSON error format, replay over HTTP |

Separately, `verification/concurrency_check.py` was **actually executed** during development against a live PostgreSQL 16 and its output is saved; it reproduces the same SQL sequence from parallel connections (9 of 9 checks passed).

---

## 15. File map

| Path | Role |
|---|---|
| `BankCoreApplication` | entry point; turns on scheduling |
| `config/SecurityConfig` | JWT validation, role mapping, which URLs are public |
| `config/OpenApiConfig` | Swagger metadata and the "Authorize" button |
| `config/BankProperties` | typed access to `bank.*` settings (limits, outbox) |
| `config/KafkaTopicConfig` | declares the two topics |
| `domain/*` | immutable records (`Account`, `BankTransaction`, `LedgerEntry`, ...) |
| `repository/*` | all SQL; `AccountRepository.lockBoth` is the lock; `TransactionRepository` holds ledger + idempotency queries |
| `service/TransferTransactions` | the atomic money transaction (§4) |
| `service/TransferService` | wrapper that saves fraud alerts after rollback |
| `service/LedgerService` | writes the double-entry lines (§3) |
| `service/FraudService`, `FraudAlertRecorder` | rules and alert storage |
| `service/KycService`, `AccountService`, `CustomerService` | workflow, account opening/visibility, JIT customer creation |
| `service/StatementService`, `StatementScheduler` | statements and month-end job |
| `service/ReconciliationService` | the three balance checks |
| `events/*` | outbox writer, outbox→Kafka publisher, Kafka consumer, event records |
| `web/*` | controllers, request DTOs, error handler |
| `security/Caller` | the authenticated user, extracted from the JWT |
| `exception/*` | business errors with HTTP status + stable code |

---

## 16. Questions reviewers will ask (and short answers)

- **Why not just `UPDATE accounts SET balance = balance - x`?** No audit trail, no proof, easy to corrupt silently. The ledger gives history; the trigger gives proof.
- **Why JDBC and not JPA/Hibernate?** On the money path I wanted every SQL statement and lock visible and reviewable; JPA hides them.
- **What if the app crashes mid-transfer?** The database transaction never committed → nothing happened → the client retries with the same key.
- **What if Kafka is down?** Transfers still succeed; events wait in the outbox and are delivered later.
- **What if a duplicate Kafka message arrives?** The consumer de-duplicates on `(event_id, customer_id)`.
- **Can an admin edit the ledger?** No. Updates and deletes are rejected by a trigger; corrections are new entries.
- **Weaknesses?** Single hot cash account; one currency; no reversals yet; demo-grade Keycloak setup; static fraud thresholds (see README "Known limitations").
