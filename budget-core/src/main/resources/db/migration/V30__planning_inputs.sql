-- What a plan is built from, per user. Every table is keyed by user_id first: there is no row here
-- that can be found by its own id alone, which is what makes "user A cannot read user B's goal" a
-- property of the schema and not only of the queries.
--
-- Enums are stored by constant name, never by ordinal, so reordering one cannot silently change what
-- a stored row means. No foreign key reaches another module's tables (country, metro_area): each
-- module owns its tables, which is what keeps extracting a service later a build decision rather than
-- a rewrite. The country and city are validated by the application when saved.

CREATE TABLE planning_profile (
    user_id                  TEXT        PRIMARY KEY,
    country_code             CHAR(2)     NOT NULL,
    -- Exactly one of these: a listed city's slug, or the name of a city we do not list. The name is
    -- display text and is never used to look anything up.
    city_slug                TEXT,
    city_not_listed          TEXT,
    lifestyle                TEXT        CHECK (lifestyle IN ('HOMEBODY', 'OCCASIONAL', 'REGULAR', 'FREQUENT')),
    income_arrives_taxed     BOOLEAN     NOT NULL,
    least_for_enjoying_life  NUMERIC(12, 2) CHECK (least_for_enjoying_life >= 0),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((city_slug IS NULL) <> (city_not_listed IS NULL))
);

-- The money typed on the manual path. The balance is a stock and the income a rate; they share a row
-- because they are asked together, not because they are the same kind of number.
CREATE TABLE planning_money (
    user_id         TEXT           PRIMARY KEY,
    monthly_income  NUMERIC(12, 2) NOT NULL CHECK (monthly_income >= 0),
    balance         NUMERIC(14, 2) NOT NULL CHECK (balance >= 0),
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- What the user says they spend. A category with no row is assumed at its local figure.
CREATE TABLE planning_spending (
    user_id   TEXT           NOT NULL,
    category  TEXT           NOT NULL CHECK (category <> 'TAX_RESERVE'),
    amount    NUMERIC(12, 2) NOT NULL CHECK (amount >= 0),
    PRIMARY KEY (user_id, category)
);

-- Commitments the user named, so advice can say "your gym" rather than "Subscriptions".
CREATE TABLE planning_line_item (
    user_id    TEXT           NOT NULL,
    id         TEXT           NOT NULL,
    label      TEXT           NOT NULL,
    category   TEXT           NOT NULL CHECK (category <> 'TAX_RESERVE'),
    amount     NUMERIC(12, 2) NOT NULL CHECK (amount >= 0),
    rigidity   TEXT           NOT NULL CHECK (rigidity IN ('DISPOSABLE', 'FLEXIBLE', 'ESSENTIAL', 'LOCKED')),
    scope      TEXT           NOT NULL CHECK (scope IN ('ALREADY_COUNTED', 'ON_TOP')),
    PRIMARY KEY (user_id, id)
);

-- Goals. There is deliberately no saved or already_saved column: what a goal holds is worked out from
-- the balance in planning_money at plan time, so the same dollars cannot be credited to it twice -
-- once typed here and once earmarked from the balance.
CREATE TABLE planning_goal (
    user_id     TEXT           NOT NULL,
    id          TEXT           NOT NULL,
    name        TEXT           NOT NULL,
    target      NUMERIC(14, 2) NOT NULL CHECK (target > 0),
    deadline    DATE           NOT NULL,
    priority    TEXT           NOT NULL CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, id)
);

-- The one goal a user nominated to take the balance ahead of priority order. The composite foreign
-- key means a nomination can only ever point at the same user's goal, and removing the goal removes
-- the nomination with it.
CREATE TABLE planning_finish_first (
    user_id  TEXT PRIMARY KEY,
    goal_id  TEXT NOT NULL,
    FOREIGN KEY (user_id, goal_id) REFERENCES planning_goal (user_id, id) ON DELETE CASCADE
);
