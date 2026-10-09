-- one transaction_id groups the debit + credit legs of a transfer
CREATE TABLE entries (
    id             UUID PRIMARY KEY,
    transaction_id UUID        NOT NULL,
    account_id     UUID        NOT NULL REFERENCES accounts (id),
    direction      VARCHAR(6)  NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount_cents   BIGINT      NOT NULL CHECK (amount_cents > 0),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX entries_account_idx ON entries (account_id, created_at DESC, id);
CREATE INDEX entries_transaction_idx ON entries (transaction_id);
