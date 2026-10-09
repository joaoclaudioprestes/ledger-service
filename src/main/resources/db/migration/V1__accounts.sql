CREATE TABLE accounts (
    id         UUID PRIMARY KEY,
    name       VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
