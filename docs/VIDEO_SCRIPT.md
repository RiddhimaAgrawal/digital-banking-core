# Video guide: the 10-minute explainer

The brief asks for a **working demo (deployed or screen-recorded)** and a **10-minute final presentation**. Plan the recording as two parts that add up to about 10 minutes:

| Part | Length | What | Who makes it |
|---|---|---|---|
| A. Slides with narration | about 7 min | the 12 slides in `presentation/Digital_Banking_Core.pptx` | you read the script below; a captioned version is in `video/Digital_Banking_Core_explainer.mp4` |
| B. Live demo | about 3 min | `scripts/demo.sh` and Swagger UI on your laptop | **you record this**, because it needs the stack running on your machine |

> **About the ready-made MP4.** `video/Digital_Banking_Core_explainer.mp4` (6.9 min) is the 12 slides with the narration below shown as timed captions. **It has no voice**, because the sandbox I worked in had no speech engine. Use it as a reading aid, or as a backing video: play it, and record your own voice reading the same script. A recording in your own voice is also better for the "individual" parts of the evaluation. Edit the wording so it sounds like you.

## How to record

1. **Part A.** Open the `.pptx` in PowerPoint, choose *Slide Show* → *Record Slide Show* (or *Insert → Screen Recording* on Windows), and read the script. PowerPoint can then *Export → Create a Video* (MP4). Alternatively record the screen with OBS Studio (free, all platforms), macOS QuickTime (*File → New Screen Recording*) or Windows *Win + G*.
2. **Part B.** Start everything (see `docs/SETUP_GUIDE.md`), set the terminal font large (18pt or more), then record it with the same tool.
3. Join the parts with any editor (Clipchamp on Windows, iMovie on macOS, or `ffmpeg -f concat`).
4. Upload to YouTube as *Unlisted* or to Google Drive, and put the link in your README and submission.

## Part A: narration script (about 7 minutes)

Read it naturally. Each block matches one slide.

### Slide 1: Introduction
Hello. This is my Digital Banking Core Platform, the engine of a bank. It has accounts, money transfers, a ledger that always balances, and identity checks. It is built with Spring Boot, PostgreSQL, Flyway, Kafka and Keycloak. In the next ten minutes I will cover the problem, the design, three hard challenges, the results, and what I would do next.

### Slide 2: The problem
A bank core has to get four things right. First, it must never lose or invent money. Every transfer is a debit and a credit that cancel out. Second, it must never charge twice. If a network failure makes the app retry, the money must not move again. Third, it must stay correct under load, even when many requests hit one account at the same moment. And fourth, it must know who is who: login, roles, and identity checks before anyone can move money.

### Slide 3: Double-entry bookkeeping
The foundation is double-entry bookkeeping. Money is never just added or subtracted. Every movement is two entries that cancel out. A debit is stored as a negative number and a credit as a positive number. Here Alice sends two thousand five hundred to Bob. Minus 2,500 for Alice, plus 2,500 for Bob, and the sum is zero. This is enforced three ways. One service is the only code that writes entries. A database trigger rejects any unbalanced transaction at commit. And the ledger is append-only, so history can never be edited. So the sum of every entry in the whole bank is always exactly zero.

### Slide 4: Architecture
Here is the architecture. Clients log in at Keycloak and receive a signed token. They call the Spring Boot application with that token. A security filter verifies it and the controllers check the role. The services hold the business rules, and the repositories run explicit SQL against PostgreSQL, which is the source of truth. Kafka is reached only through an outbox, which I will explain later, so Kafka being down never blocks a transfer.

### Slide 5: Data model
The data model is managed by Flyway migrations, applied in order, exactly once each. Customers own accounts. A transaction produces ledger entries, one per side. The idempotency keys table points back to the transaction it produced. Money is stored as NUMERIC with two decimals, never floating point. The account balance is a cached total. The ledger is the truth. And a database check stops an ordinary account from ever going below zero.

### Slide 6: Life of one transfer
This is the life of one transfer, and all of it is one database transaction. I reserve the idempotency key. I check the caller owns the source account and that their KYC is approved. I lock both accounts in a fixed order, run the fraud rules, check funds, and write the transaction with its debit and credit. I update both balances, write the event to the outbox table, store the result on the key, and commit. If anything fails, everything rolls back, so there is never a half-finished transfer.

### Slide 7: Challenge 1: concurrency
The first challenge is many requests at once. A lost update happens when two transfers both read a balance of 1,000 and each spend 600. Without a lock both succeed. I lock both account rows before reading. A deadlock happens when a transfer from A to B and one from B to A each hold one lock and wait for the other. I always lock in ascending account id, so one simply waits. In my database-level check, 30 parallel transfers of 100 from a balance of 1,000 gave exactly 10 successes and 20 insufficient funds. And 40 simultaneous opposite transfers produced zero deadlocks.

### Slide 8: Challenge 2: idempotency
The second challenge is that retries must never double-charge. The client sends the same Idempotency-Key on every retry. I insert that key in the same transaction as the money movement, so the key exists if and only if the transfer committed. A retry finds the key and returns the original result. The same key with a different amount is refused. A failed request leaves no key, so it can be retried. In my check, 20 simultaneous identical requests produced one transfer and 19 replays, and the account was debited once.

### Slide 9: Challenge 3: events
The third challenge is telling Kafka without losing events. A database and Kafka cannot share one transaction. So I write the event to an outbox table inside the money transaction. A background publisher sends the rows to Kafka and waits for the acknowledgement. A consumer turns them into notifications and ignores duplicates by event id. So there is never an event for a rolled-back transfer, never a lost event for a committed one, and transfers keep working even if Kafka is down.

### Slide 10: Identity and safety
Now identity and safety. Keycloak handles login. My application never sees a password. It verifies the signed token on every call, and customers can only see their own accounts. The KYC workflow goes from submit to review to approve or reject. Nobody can open an account or transfer money until approved, and nobody can review their own KYC. Fraud rules block a very large single transfer, a daily total over the limit, and too many transfers in a minute. Every block is logged for compliance.

### Slide 11: Results
Now the results, and I want to be precise about what they show. My database-level concurrency script passed all nine checks on PostgreSQL 16, ending with a reconciliation where the ledger sums to zero, with no unbalanced transaction and no balance mismatch. I also wrote 32 Java integration tests that run against a real PostgreSQL in Docker. The throughput of 228 transfers per second came from a replay of the transfer SQL on a small sandbox. It shows the database design behaves. It is not a benchmark of the Spring application.

### Slide 12: Next steps
Finally, the limits and next steps. All cash operations lock one cash account, so I would shard it per branch. It supports one currency, so foreign exchange needs a conversion account. There are no reversals yet, only the schema support. Fraud rules are static thresholds, so I would train a scoring model. The Keycloak setup is demo-grade, so production needs authorization code with PKCE and TLS. And I would upgrade to Spring Boot 4. Thank you. I am happy to take questions.

## Part B: live demo script (about 3 minutes)

Before you press record: `docker compose up -d`, wait until Keycloak answers, run `mvn spring-boot:run`, open `http://localhost:8080/swagger-ui.html` in a browser tab, and open a second terminal in the project folder. Run `bash scripts/demo.sh` once beforehand to check it works, then **reset with `docker compose down -v && docker compose up -d`** and restart the app so the recording starts from a clean bank. Wait a full minute before recording so the velocity rule starts clean.

| Time | Show | Say |
|---|---|---|
| 0:00 | Swagger UI page, scroll the endpoint groups | "This is the API documentation generated from the code with OpenAPI. Accounts, transfers, KYC, statements, admin." |
| 0:20 | Terminal: `bash scripts/demo.sh` starts; steps 1 to 3 scroll by | "A new customer cannot open an account yet: 403 KYC required. Alice and Bob submit their documents." |
| 0:50 | Steps 4 to 6 | "Carl from compliance approves them. Now they open accounts, and Tina the teller deposits cash." |
| 1:10 | Step 7 then **step 8** (pause here) | "Alice sends 2,500 to Bob: 201 Created. Now the same request again, as if the network failed: 200 with Idempotent-Replay true. Alice's balance shows she was charged once." |
| 1:40 | Steps 9 to 11 | "Same key, different amount: refused. Overdraft: refused. Above the single-transfer limit: blocked by the fraud rule." |
| 2:00 | Step 12 | "Seven quick transfers: the first four pass, then the velocity rule answers 429." |
| 2:15 | Steps 13 to 15 | "Compliance sees the blocked attempts. Bob's ledger shows each side of every entry. The monthly statement, as JSON and CSV." |
| 2:35 | Steps 16 and 17 | "Notifications created from Kafka events. And the reconciliation: healthy true, the ledger sums to zero." |
| 2:50 | Step 18 | "A customer calling an admin endpoint gets 403. Thank you." |

Optional extras if you have time to spare (they make a strong 30 seconds): run `docker exec -it bank-postgres psql -U bank -d bank -c "select sum(amount) from ledger_entries"` and show `0.00`; or try `update ledger_entries set amount = 5` and show the database refusing it.

## Checklist before you submit

- [ ] Voice is clear; terminal text is readable at 1080p
- [ ] You show a retry that does not double-charge (step 8) and the reconciliation (step 17)
- [ ] Total length is 10 minutes or less
- [ ] The link works when you are logged out
- [ ] The link is in `README.md`
