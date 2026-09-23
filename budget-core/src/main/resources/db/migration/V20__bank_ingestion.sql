-- Connected banks, the transactions read from them, and the streams detected in those transactions.
--
-- No foreign key points outside this module. `user_id` is the Supabase account id and there is
-- deliberately no reference to any profile table: each module owns its own tables, and a constraint
-- across that boundary is what turns a modular monolith back into one schema nobody can split.
--
-- Money is NUMERIC(14,2). Never a floating-point type: a cent that cannot be represented exactly is
-- the same class of bug the Money type exists to prevent in Java, and a column is where it would be
-- reintroduced.

CREATE TABLE bank_connection (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id                 TEXT        NOT NULL,
    -- The provider's own identifier. Notifications arrive naming this and nothing else, so it is the
    -- only route from a webhook back to a user; unique because one connection belongs to one user.
    provider_item_id        TEXT        NOT NULL UNIQUE,
    -- Encrypted, so that a copy of this database is not a copy of everyone's bank feed. The column
    -- name says ciphertext so nobody ever writes a plaintext token into it "temporarily".
    access_token_ciphertext TEXT        NOT NULL,
    -- Where the last completed sync finished. NULL means never synced, which asks for a full import.
    sync_cursor             TEXT,
    connected_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_synced_at          TIMESTAMPTZ
);

CREATE INDEX bank_connection_by_user ON bank_connection (user_id);

CREATE TABLE bank_transaction (
    connection_id      BIGINT       NOT NULL REFERENCES bank_connection (id) ON DELETE CASCADE,
    -- The provider's transaction id. Together with the connection it is the identity that makes
    -- re-importing the same page idempotent rather than duplicating a month of spending.
    external_id        TEXT         NOT NULL,
    account_id         TEXT         NOT NULL,
    -- When the user spent it, not when the bank posted it.
    txn_date           DATE         NOT NULL,
    -- Positive means money LEFT the account. The provider's convention, kept rather than flipped, so
    -- that stored data and the rules written against it can never disagree about which way is out.
    amount             NUMERIC(14,2) NOT NULL,
    merchant_name      TEXT,
    -- Stored from the first import precisely so that observed merchant prices need no backfill
    -- later. It is stable across spellings of the merchant's name, which is what makes grouping by
    -- it exact rather than a string match.
    merchant_entity_id TEXT,
    latitude           DOUBLE PRECISION,
    longitude          DOUBLE PRECISION,
    kind               TEXT         NOT NULL,
    category           TEXT,
    pending            BOOLEAN      NOT NULL,
    PRIMARY KEY (connection_id, external_id),
    -- The two axes, enforced where they are stored: a category is meaningful only for real spending,
    -- and a transfer carrying one would be a transfer that had quietly become spending.
    CONSTRAINT bank_transaction_category_only_for_spend
        CHECK ((kind = 'SPEND') = (category IS NOT NULL))
);

CREATE INDEX bank_transaction_by_date ON bank_transaction (connection_id, txn_date);
CREATE INDEX bank_transaction_by_merchant ON bank_transaction (merchant_entity_id)
    WHERE merchant_entity_id IS NOT NULL;

CREATE TABLE recurring_stream (
    connection_id BIGINT        NOT NULL REFERENCES bank_connection (id) ON DELETE CASCADE,
    stream_id     TEXT          NOT NULL,
    account_id    TEXT          NOT NULL,
    direction     TEXT          NOT NULL CHECK (direction IN ('MONEY_IN', 'MONEY_OUT')),
    -- What to call it in front of a person: the merchant, so advice can name the subscription.
    label         TEXT          NOT NULL,
    frequency     TEXT          NOT NULL,
    -- The latest amount rather than the average: a subscription that went up in price costs the new
    -- price next month, and the average is already out of date the moment it changes.
    last_amount   NUMERIC(14,2) NOT NULL,
    last_date     DATE,
    next_expected DATE,
    active        BOOLEAN       NOT NULL,
    PRIMARY KEY (connection_id, stream_id)
);

-- Which transactions make up a stream. This is what lets a payroll deposit tagged as a restaurant be
-- recognised as pay, and a monthly entertainment charge be recognised as a subscription.
CREATE TABLE recurring_stream_member (
    connection_id BIGINT NOT NULL,
    stream_id     TEXT   NOT NULL,
    external_id   TEXT   NOT NULL,
    PRIMARY KEY (connection_id, stream_id, external_id),
    FOREIGN KEY (connection_id, stream_id) REFERENCES recurring_stream (connection_id, stream_id)
        ON DELETE CASCADE
);
