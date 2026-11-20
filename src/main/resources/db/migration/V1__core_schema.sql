-- =====================================================================
-- V1: core banking schema
--   customers, accounts, transactions, ledger_entries, idempotency_keys
--
-- Double-entry rule: every transaction writes >= 2 ledger entries whose
-- signed amounts SUM TO ZERO.  Debit = negative amount, credit = positive.
-- A deferred constraint trigger enforces this at COMMIT time, and a second
-- trigger makes the ledger append-only.
-- =====================================================================

CREATE TABLE customers (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    keycloak_user_id VARCHAR(64)  NOT NULL UNIQUE,          -- JWT "sub" claim
    full_name        VARCHAR(200) NOT NULL,
    email            VARCHAR(254) NOT NULL UNIQUE,
    kyc_status       VARCHAR(20)  NOT NULL DEFAULT 'NOT_SUBMITTED'
                     CHECK (kyc_status IN ('NOT_SUBMITTED', 'PENDING', 'APPROVED', 'REJECTED')),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE SEQUENCE account_number_seq START WITH 1000000001;

CREATE TABLE accounts (
    id             UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id    UUID          REFERENCES customers (id),   -- NULL only for bank-owned SYSTEM accounts
    account_number VARCHAR(20)   NOT NULL UNIQUE,
    type           VARCHAR(20)   NOT NULL CHECK (type IN ('CHECKING', 'SAVINGS', 'SYSTEM')),
    currency       CHAR(3)       NOT NULL,
    balance        NUMERIC(19,2) NOT NULL DEFAULT 0,          -- cached; the ledger is the source of truth
    allow_negative BOOLEAN       NOT NULL DEFAULT FALSE,
    status         VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT balance_not_negative CHECK (allow_negative OR balance >= 0),
    CONSTRAINT customer_account_owner CHECK (
        (type = 'SYSTEM' AND customer_id IS NULL) OR (type <> 'SYSTEM' AND customer_id IS NOT NULL))
);
CREATE INDEX idx_accounts_customer ON accounts (customer_id);

CREATE TABLE transactions (
    id                     UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    type                   VARCHAR(20)   NOT NULL CHECK (type IN ('TRANSFER', 'DEPOSIT', 'WITHDRAWAL')),
    status                 VARCHAR(20)   NOT NULL DEFAULT 'COMPLETED' CHECK (status IN ('COMPLETED', 'REVERSED')),
    source_account_id      UUID          NOT NULL REFERENCES accounts (id),   -- the debited account
    destination_account_id UUID          NOT NULL REFERENCES accounts (id),   -- the credited account
    amount                 NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency               CHAR(3)       NOT NULL,
    description            VARCHAR(255),
    initiated_by           VARCHAR(64)   NOT NULL,                            -- JWT "sub" of the caller
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT different_accounts CHECK (source_account_id <> destination_account_id)
);
CREATE INDEX idx_tx_source_created ON transactions (source_account_id, created_at DESC);
CREATE INDEX idx_tx_dest_created   ON transactions (destination_account_id, created_at DESC);

CREATE TABLE ledger_entries (
    id             BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id UUID          NOT NULL REFERENCES transactions (id),
    account_id     UUID          NOT NULL REFERENCES accounts (id),
    amount         NUMERIC(19,2) NOT NULL CHECK (amount <> 0),     -- negative = debit, positive = credit
    balance_after  NUMERIC(19,2) NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_ledger_account ON ledger_entries (account_id, id);
CREATE INDEX idx_ledger_account_time ON ledger_entries (account_id, created_at);
CREATE INDEX idx_ledger_tx ON ledger_entries (transaction_id);

-- ---------------------------------------------------------------------
-- Invariant 1: entries of one transaction must sum to zero (checked at COMMIT)
-- ---------------------------------------------------------------------
CREATE FUNCTION check_ledger_balanced() RETURNS trigger AS $$
DECLARE
    total NUMERIC;
BEGIN
    SELECT COALESCE(SUM(amount), 0) INTO total FROM ledger_entries WHERE transaction_id = NEW.transaction_id;
    IF total <> 0 THEN
        RAISE EXCEPTION 'Unbalanced ledger: transaction % sums to % (must be 0)', NEW.transaction_id, total
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_ledger_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_ledger_balanced();

-- ---------------------------------------------------------------------
-- Invariant 2: the ledger is append-only (no UPDATE, no DELETE)
-- ---------------------------------------------------------------------
CREATE FUNCTION forbid_ledger_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries is append-only (% not allowed)', TG_OP USING ERRCODE = '23000';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_mutation();

-- ---------------------------------------------------------------------
-- Idempotency: one row per (caller, Idempotency-Key). The row is inserted in the
-- SAME database transaction as the money movement, so it exists if and only if
-- the transfer committed.
-- ---------------------------------------------------------------------
CREATE TABLE idempotency_keys (
    caller         VARCHAR(64)  NOT NULL,
    idem_key       VARCHAR(128) NOT NULL,
    request_hash   CHAR(64)     NOT NULL,           -- SHA-256 of the request body fields
    transaction_id UUID         REFERENCES transactions (id),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (caller, idem_key)
);
