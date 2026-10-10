-- stored response of a transfer, keyed by the client's Idempotency-Key
CREATE TABLE idempotency_keys (
    key            VARCHAR(255) PRIMARY KEY,
    request_hash   VARCHAR(255) NOT NULL,
    transaction_id UUID         NOT NULL,
    expires_at     TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idempotency_keys_expires_idx ON idempotency_keys (expires_at);
