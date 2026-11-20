#!/usr/bin/env python3
"""
Database-level concurrency verification.

Replays, statement by statement, the SQL that TransferTransactions.java executes for a transfer:

    1. INSERT idempotency key ... ON CONFLICT DO NOTHING          (claim the key)
    2. SELECT both accounts ORDER BY id FOR NO KEY UPDATE         (lock in a global order)
    3. check balance, INSERT transaction, INSERT 2 ledger entries, UPDATE 2 balances
    4. UPDATE idempotency key SET transaction_id                  (remember the result)
    5. COMMIT

from many parallel PostgreSQL connections, and checks that money is never created, lost or moved twice.
This validates the locking / idempotency DESIGN against a real PostgreSQL 16. It is NOT a replacement for the
Java integration tests (src/test/java), which exercise the actual Spring code.

Usage:  python3 verification/concurrency_check.py [--dsn "host=127.0.0.1 port=5544 dbname=bankverify user=bank password=bank"]
"""
import argparse
import hashlib
import statistics
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor

import psycopg2

CLEARING = "00000000-0000-0000-0000-000000000001"


class Insufficient(Exception):
    pass


class KeyConflict(Exception):
    pass


def connect(dsn):
    conn = psycopg2.connect(dsn)
    conn.autocommit = False
    return conn


def _post(cur, tx_type, debit_id, credit_id, amount, desc, who):
    """Steps 2-3. Returns the new transaction id. Caller commits."""
    cur.execute("select id, balance, allow_negative from accounts where id in (%s,%s) order by id for no key update",
                (debit_id, credit_id))
    rows = {str(r[0]): (r[1], r[2]) for r in cur.fetchall()}
    d_bal, d_neg = rows[debit_id]
    c_bal, _ = rows[credit_id]
    if not d_neg and d_bal < amount:
        raise Insufficient(f"balance {d_bal} < {amount}")
    cur.execute("""insert into transactions (type, source_account_id, destination_account_id, amount, currency, description, initiated_by)
                   values (%s,%s,%s,%s,'NPR',%s,%s) returning id""", (tx_type, debit_id, credit_id, amount, desc, who))
    tx_id = cur.fetchone()[0]
    cur.execute("insert into ledger_entries (transaction_id, account_id, amount, balance_after) values (%s,%s,%s,%s)",
                (tx_id, debit_id, -amount, d_bal - amount))
    cur.execute("insert into ledger_entries (transaction_id, account_id, amount, balance_after) values (%s,%s,%s,%s)",
                (tx_id, credit_id, amount, c_bal + amount))
    cur.execute("update accounts set balance = balance + %s where id = %s", (-amount, debit_id))
    cur.execute("update accounts set balance = balance + %s where id = %s", (amount, credit_id))
    return tx_id


def transfer(dsn, caller, key, src, dst, amount, desc="t", tx_type="TRANSFER"):
    """Returns ('fresh'|'replay', tx_id). Raises Insufficient / KeyConflict."""
    conn = connect(dsn)
    try:
        cur = conn.cursor()
        h = hashlib.sha256(f"{tx_type}|{src}|{dst}|{amount}|{desc}".encode()).hexdigest()
        cur.execute("insert into idempotency_keys (caller, idem_key, request_hash) values (%s,%s,%s) "
                    "on conflict (caller, idem_key) do nothing", (caller, key, h))
        if cur.rowcount == 0:
            cur.execute("select request_hash, transaction_id from idempotency_keys where caller=%s and idem_key=%s",
                        (caller, key))
            rh, txid = cur.fetchone()
            conn.rollback()
            if rh.strip() != h:
                raise KeyConflict("same key, different body")
            if txid is None:
                raise KeyConflict("still processing")
            return ("replay", txid)
        tx_id = _post(cur, tx_type, src, dst, amount, desc, caller)
        cur.execute("update idempotency_keys set transaction_id=%s where caller=%s and idem_key=%s",
                    (tx_id, caller, key))
        conn.commit()
        return ("fresh", tx_id)
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def run_parallel(n, fn):
    """Run fn(i) in n threads released at the same instant. Returns list of (result|None, error|None)."""
    gate = threading.Barrier(n)
    out = [None] * n

    def worker(i):
        gate.wait()
        try:
            out[i] = (fn(i), None)
        except Exception as e:  # noqa: BLE001
            out[i] = (None, e)

    with ThreadPoolExecutor(max_workers=n) as pool:
        list(pool.map(worker, range(n)))
    return out


def new_account(dsn, balance):
    """Create customer + account and fund it through the clearing account (a real, balanced deposit)."""
    conn = connect(dsn)
    cur = conn.cursor()
    sub = f"v-{uuid.uuid4()}"
    cur.execute("insert into customers (keycloak_user_id, full_name, email, kyc_status) values (%s,%s,%s,'APPROVED') returning id",
                (sub, sub, sub + "@t.local"))
    cid = cur.fetchone()[0]
    cur.execute("insert into accounts (customer_id, account_number, type, currency) values (%s, cast(nextval('account_number_seq') as varchar), 'CHECKING','NPR') returning id",
                (cid,))
    acc = str(cur.fetchone()[0])
    if balance > 0:
        _post(cur, "DEPOSIT", CLEARING, acc, balance, "seed", "teller")
    conn.commit()
    conn.close()
    return acc


def balance(dsn, acc):
    conn = connect(dsn)
    cur = conn.cursor()
    cur.execute("select balance from accounts where id=%s", (acc,))
    b = cur.fetchone()[0]
    conn.close()
    return b


def reconcile(dsn):
    conn = connect(dsn)
    cur = conn.cursor()
    cur.execute("select coalesce(sum(amount),0) from ledger_entries")
    total = cur.fetchone()[0]
    cur.execute("select count(*) from (select transaction_id from ledger_entries group by transaction_id having sum(amount)<>0) x")
    unbalanced = cur.fetchone()[0]
    cur.execute("""select count(*) from (select a.id from accounts a left join ledger_entries l on l.account_id=a.id
                   group by a.id, a.balance having a.balance <> coalesce(sum(l.amount),0)) x""")
    mismatches = cur.fetchone()[0]
    conn.close()
    return total, unbalanced, mismatches


RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, ok, detail))
    print(f"[{'PASS' if ok else 'FAIL'}] {name}" + (f"  ({detail})" if detail else ""))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dsn", default="host=127.0.0.1 port=5544 dbname=bankverify user=bank password=bank")
    ap.add_argument("--load", type=int, default=3000, help="number of transfers in the throughput run")
    args = ap.parse_args()
    dsn = args.dsn

    # 1. parallel transfers cannot overdraw ------------------------------------------------------------------
    a, b = new_account(dsn, 1000), new_account(dsn, 0)
    res = run_parallel(30, lambda i: transfer(dsn, "alice", str(uuid.uuid4()), a, b, 100, f"race{i}"))
    ok_n = sum(1 for r, e in res if e is None)
    ins_n = sum(1 for r, e in res if isinstance(e, Insufficient))
    check("30 parallel transfers of 100 from a 1000 balance: exactly 10 succeed, 20 insufficient funds",
          ok_n == 10 and ins_n == 20 and balance(dsn, a) == 0 and balance(dsn, b) == 1000,
          f"succeeded={ok_n}, insufficient={ins_n}, sender={balance(dsn, a)}, receiver={balance(dsn, b)}")

    # 2. same idempotency key in parallel --------------------------------------------------------------------
    a, b = new_account(dsn, 500), new_account(dsn, 0)
    key = "storm-" + str(uuid.uuid4())
    res = run_parallel(20, lambda i: transfer(dsn, "alice", key, a, b, 200, "rent"))
    fresh = sum(1 for r, e in res if e is None and r[0] == "fresh")
    replay = sum(1 for r, e in res if e is None and r[0] == "replay")
    txids = {r[1] for r, e in res if e is None}
    check("20 parallel requests with the SAME idempotency key: 1 fresh, 19 replays, one transaction, debited once",
          fresh == 1 and replay == 19 and len(txids) == 1 and balance(dsn, a) == 300 and balance(dsn, b) == 200,
          f"fresh={fresh}, replays={replay}, distinct_tx={len(txids)}, sender={balance(dsn, a)}")

    # 3. same key, different body ---------------------------------------------------------------------------
    a, b = new_account(dsn, 500), new_account(dsn, 0)
    k = str(uuid.uuid4())
    transfer(dsn, "alice", k, a, b, 50)
    try:
        transfer(dsn, "alice", k, a, b, 75)
        conflict = False
    except KeyConflict:
        conflict = True
    check("same idempotency key with a different amount is rejected", conflict and balance(dsn, a) == 450)

    # 4. failed transfer leaves no key behind ---------------------------------------------------------------
    a, b = new_account(dsn, 10), new_account(dsn, 0)
    k = str(uuid.uuid4())
    try:
        transfer(dsn, "alice", k, a, b, 999)
    except Insufficient:
        pass
    conn = connect(dsn)
    cur = conn.cursor()
    cur.execute("select count(*) from idempotency_keys where idem_key=%s", (k,))
    left = cur.fetchone()[0]
    conn.close()
    retry = transfer(dsn, "alice", k, a, b, 5)
    check("a rolled-back transfer leaves no idempotency key, so the same key can be retried",
          left == 0 and retry[0] == "fresh", f"key_rows_after_failure={left}")

    # 5. opposite transfers, no deadlock ---------------------------------------------------------------------
    a, b = new_account(dsn, 1000), new_account(dsn, 1000)

    def pingpong(i):
        s, d = (a, b) if i % 2 == 0 else (b, a)
        return transfer(dsn, "x", str(uuid.uuid4()), s, d, 10, f"pp{i}")

    res = run_parallel(40, pingpong)
    errs = [e for r, e in res if e is not None]
    check("40 simultaneous opposite transfers A<->B: no deadlock, no errors, money conserved",
          not errs and balance(dsn, a) + balance(dsn, b) == 2000 and balance(dsn, a) == 1000,
          f"errors={[type(e).__name__ + ': ' + str(e)[:60] for e in errs]}")

    # 6. fan-in -----------------------------------------------------------------------------------------------
    r = new_account(dsn, 0)
    senders = [new_account(dsn, 100) for _ in range(25)]
    res = run_parallel(25, lambda i: transfer(dsn, f"s{i}", str(uuid.uuid4()), senders[i], r, 40, "fan"))
    check("25 senders pay one receiver at once: receiver has exactly 1000",
          all(e is None for _, e in res) and balance(dsn, r) == 1000, f"receiver={balance(dsn, r)}")

    # 7. database triggers -----------------------------------------------------------------------------------
    conn = connect(dsn)
    cur = conn.cursor()
    a = new_account(dsn, 0)
    try:
        tx = str(uuid.uuid4())
        cur.execute("insert into transactions (id,type,source_account_id,destination_account_id,amount,currency,initiated_by) values (%s,'DEPOSIT',%s,%s,100,'NPR','t')", (tx, CLEARING, a))
        cur.execute("insert into ledger_entries (transaction_id,account_id,amount,balance_after) values (%s,%s,100,100)", (tx, a))
        conn.commit()
        blocked = False
    except psycopg2.Error as e:
        conn.rollback()
        blocked = "Unbalanced ledger" in str(e)
    check("database refuses an unbalanced ledger at COMMIT (trigger)", blocked)
    try:
        cur.execute("update ledger_entries set amount = amount + 1")
        conn.commit()
        append_only = False
    except psycopg2.Error as e:
        conn.rollback()
        append_only = "append-only" in str(e)
    check("database refuses UPDATE on the ledger (append-only trigger)", append_only)
    conn.close()

    # 8. throughput / latency -----------------------------------------------------------------------------
    pairs = [(new_account(dsn, 1_000_000), new_account(dsn, 0)) for _ in range(40)]
    lat = []
    lock = threading.Lock()

    def one(i):
        s, d = pairs[i % len(pairs)]
        t0 = time.perf_counter()
        transfer(dsn, "load", str(uuid.uuid4()), s, d, 1)
        dt = (time.perf_counter() - t0) * 1000
        with lock:
            lat.append(dt)

    t0 = time.perf_counter()
    with ThreadPoolExecutor(max_workers=16) as pool:
        list(pool.map(one, range(args.load)))
    elapsed = time.perf_counter() - t0
    lat.sort()
    p50, p95, p99 = lat[int(len(lat) * .50)], lat[int(len(lat) * .95)], lat[int(len(lat) * .99)]
    print(f"\nTHROUGHPUT: {args.load} transfers, 16 threads, 40 account pairs -> {args.load / elapsed:,.0f} transfers/s "
          f"(latency incl. connect: p50={p50:.1f} ms, p95={p95:.1f} ms, p99={p99:.1f} ms)")

    # 9. final reconciliation ---------------------------------------------------------------------------------
    total, unbalanced, mismatches = reconcile(dsn)
    check("FINAL RECONCILIATION: sum of every ledger entry is 0, no unbalanced transaction, no balance mismatch",
          total == 0 and unbalanced == 0 and mismatches == 0,
          f"ledger_sum={total}, unbalanced_tx={unbalanced}, balance_mismatches={mismatches}")

    failed = [n for n, ok, _ in RESULTS if not ok]
    print(f"\n{len(RESULTS) - len(failed)}/{len(RESULTS)} checks passed")
    raise SystemExit(1 if failed else 0)


if __name__ == "__main__":
    main()
