-- =====================================================================
-- V2: KYC workflow (submit -> review -> approve/reject) and fraud alerts
-- =====================================================================

CREATE TABLE kyc_submissions (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id     UUID         NOT NULL REFERENCES customers (id),
    document_type   VARCHAR(30)  NOT NULL
                    CHECK (document_type IN ('CITIZENSHIP', 'PASSPORT', 'NATIONAL_ID', 'DRIVING_LICENSE')),
    document_number VARCHAR(50)  NOT NULL,
    date_of_birth   DATE         NOT NULL,
    address         VARCHAR(300) NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    reviewer        VARCHAR(64),                    -- JWT "sub" of the compliance officer
    review_note     VARCHAR(500),
    submitted_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    reviewed_at     TIMESTAMPTZ
);
CREATE INDEX idx_kyc_status ON kyc_submissions (status, submitted_at);
-- A customer can have at most one submission waiting for review
CREATE UNIQUE INDEX uq_kyc_one_pending_per_customer ON kyc_submissions (customer_id) WHERE status = 'PENDING';

CREATE TABLE fraud_alerts (
    id          BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id UUID          REFERENCES customers (id),
    account_id  UUID          REFERENCES accounts (id),
    rule        VARCHAR(40)   NOT NULL,             -- MAX_TRANSFER_AMOUNT | DAILY_LIMIT | VELOCITY
    detail      VARCHAR(500)  NOT NULL,
    amount      NUMERIC(19,2),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_fraud_created ON fraud_alerts (created_at DESC);
