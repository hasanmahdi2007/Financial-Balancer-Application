-- Where the money sits, as of the last sync.
--
-- The considered-balance question - "how much of what you have should the plan work with" - has
-- nothing to answer without this, so the account balances the provider already sends alongside the
-- transactions are kept rather than discarded.
--
-- A stock, not a rate. Monthly income and a balance are different quantities and must never be
-- added; the planning side keeps them in separate concepts for that reason, and so does this table.
CREATE TABLE bank_account (
    connection_id  BIGINT        NOT NULL REFERENCES bank_connection (id) ON DELETE CASCADE,
    account_id     TEXT          NOT NULL,
    -- The bank's own name, shown to the user. Never a lookup key.
    label          TEXT          NOT NULL,
    -- CASH, CARD or LOAN. Only CASH holds spendable money: a card or loan balance is what is owed,
    -- and adding it to somebody's funds would invent money that is not there.
    role           TEXT          NOT NULL,
    current_balance NUMERIC(14,2) NOT NULL,
    -- What the bank says is actually usable. Null where it does not report one, which is normal for
    -- credit and loan accounts - and null rather than zero, because zero is a balance and this is
    -- the absence of one.
    available_balance NUMERIC(14,2),
    observed_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (connection_id, account_id)
);
