-- =====================================================================
-- V3: transactional outbox (Kafka), notifications (Kafka consumer), statements
-- =====================================================================

-- Transactional outbox: an event row is written in the same DB transaction as the
-- money movement. A background publisher sends it to Kafka afterwards, so we never
-- publish an event for a rolled-back transfer and never lose an event for a committed one.
CREATE TABLE outbox_events (
    id           BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id     UUID         NOT NULL UNIQUE,
    topic        VARCHAR(100) NOT NULL,
    event_key    VARCHAR(100) NOT NULL,             -- Kafka message key (keeps per-account ordering)
    event_type   VARCHAR(60)  NOT NULL,
    payload      JSONB        NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    published_at TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unpublished ON outbox_events (id) WHERE published_at IS NULL;

-- Written by the Kafka consumer. UNIQUE (event_id, customer_id) makes the consumer idempotent,
-- because Kafka delivery is at-least-once.
CREATE TABLE notifications (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id UUID         NOT NULL REFERENCES customers (id),
    event_id    UUID         NOT NULL,
    kind        VARCHAR(30)  NOT NULL,              -- DEBIT | CREDIT | KYC_APPROVED | KYC_REJECTED | KYC_SUBMITTED
    message     VARCHAR(500) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_notification_event_customer UNIQUE (event_id, customer_id)
);
CREATE INDEX idx_notifications_customer ON notifications (customer_id, id DESC);

-- Snapshot of a closed month, written by the monthly statement job.
-- Statement LINES are always read from the ledger; only the summary is stored.
CREATE TABLE account_statements (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id      UUID          NOT NULL REFERENCES accounts (id),
    period          CHAR(7)       NOT NULL,         -- e.g. 2026-10
    opening_balance NUMERIC(19,2) NOT NULL,
    closing_balance NUMERIC(19,2) NOT NULL,
    total_credits   NUMERIC(19,2) NOT NULL,
    total_debits    NUMERIC(19,2) NOT NULL,
    entry_count     INTEGER       NOT NULL,
    generated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_statement_account_period UNIQUE (account_id, period)
);
