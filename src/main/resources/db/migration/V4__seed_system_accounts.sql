-- =====================================================================
-- V4: bank-owned system account used as the counter-party of cash deposits/withdrawals.
--
-- When a customer deposits 1,000 in cash, the ledger records:
--     SYS-CASH-NPR  -1,000   (debit)   <- allowed to go negative; it mirrors cash taken in
--     customer acct +1,000   (credit)
-- so every transaction still sums to zero, and the sum of ALL ledger entries is always 0.
-- =====================================================================

INSERT INTO accounts (id, customer_id, account_number, type, currency, balance, allow_negative)
VALUES ('00000000-0000-0000-0000-000000000001', NULL, 'SYS-CASH-NPR', 'SYSTEM', 'NPR', 0, TRUE);
