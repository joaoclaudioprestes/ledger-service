-- materialized balance, updated under row lock in the same tx as the entries
ALTER TABLE accounts ADD COLUMN balance_cents BIGINT  NOT NULL DEFAULT 0;
ALTER TABLE accounts ADD COLUMN allow_negative BOOLEAN NOT NULL DEFAULT false;

UPDATE accounts a SET balance_cents = COALESCE((
    SELECT SUM(CASE direction WHEN 'CREDIT' THEN amount_cents ELSE -amount_cents END)
    FROM entries e WHERE e.account_id = a.id), 0);

-- source of funds for deposits; the only account allowed below zero
INSERT INTO accounts (id, name, allow_negative) VALUES ('00000000-0000-0000-0000-000000000001', 'SYSTEM', true);

ALTER TABLE accounts ADD CONSTRAINT accounts_no_overdraft CHECK (balance_cents >= 0 OR allow_negative);
