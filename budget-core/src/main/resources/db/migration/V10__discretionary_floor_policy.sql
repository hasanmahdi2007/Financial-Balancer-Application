-- The discretionary floor is the minimum spending on life the engine may not touch. Without one it
-- defaults to zero, and a short goal then produces "cut Going out and fun to $0, cut Eating out to
-- $0" - arithmetically correct, and advice nobody follows.
--
-- Every number it depends on lives here rather than in code, because this is policy: it will be
-- argued about and tuned against real users, and each of those should be a row, not a redeploy.
--
-- Rates are whole basis points (1% = 100bp). Integers rather than NUMERIC so that a rate cannot
-- acquire a scale on one side of the wire and lose it on the other, which is the same class of bug
-- that Money exists to prevent for currency.
--
-- No foreign keys to `country` or `data_source`: those tables are created by another packet's
-- migration range, and a constraint on a table that does not exist yet would make this migration
-- unrunnable on its own. Adding the references once both ranges have landed is one short migration.

-- How much of a category's LOCAL baseline is protected, per lifestyle tier. A fraction rather than
-- the whole figure, because a floor equal to typical spending would make no cut possible anywhere.
-- Expressed against the local baseline rather than in dollars so one row works for Beirut and San
-- Francisco alike, and adding a country needs no new code.
CREATE TABLE lifestyle_floor (
    tier              TEXT    NOT NULL,
    category          TEXT    NOT NULL,
    pct_of_baseline_bp INTEGER NOT NULL CHECK (pct_of_baseline_bp BETWEEN 0 AND 10000),
    rationale         TEXT,
    PRIMARY KEY (tier, category)
);

INSERT INTO lifestyle_floor (tier, category, pct_of_baseline_bp, rationale) VALUES
    ('HOMEBODY',   'ENTERTAINMENT', 2500, 'Most life happens at home; little to protect'),
    ('HOMEBODY',   'DINING_OUT',    2500, 'Most life happens at home; little to protect'),
    ('HOMEBODY',   'CLOTHING',      2500, 'Most life happens at home; little to protect'),
    ('OCCASIONAL', 'ENTERTAINMENT', 3500, NULL),
    ('OCCASIONAL', 'DINING_OUT',    3500, NULL),
    ('OCCASIONAL', 'CLOTHING',      3500, NULL),
    ('REGULAR',    'ENTERTAINMENT', 4500, NULL),
    ('REGULAR',    'DINING_OUT',    4500, NULL),
    ('REGULAR',    'CLOTHING',      4500, NULL),
    ('FREQUENT',   'ENTERTAINMENT', 5500, 'Going out IS their life; cutting it is the change they will refuse'),
    ('FREQUENT',   'DINING_OUT',    5500, 'Going out IS their life; cutting it is the change they will refuse'),
    ('FREQUENT',   'CLOTHING',      5500, 'Going out IS their life; cutting it is the change they will refuse');

-- Someone with little left after rent and loans cannot also protect a large lifestyle budget. Rows
-- rather than a chain of conditions, so changing the policy is editing data.
--
-- The last band has no upper bound on purpose: a ladder with a gap at the top would leave the most
-- stretched users with no multiplier at all, which is exactly the group the floor matters most for.
CREATE TABLE floor_obligation_band (
    sort_rank      INTEGER PRIMARY KEY,
    upper_ratio_bp INTEGER     CHECK (upper_ratio_bp > 0),
    multiplier_bp  INTEGER NOT NULL CHECK (multiplier_bp > 0),
    label          TEXT    NOT NULL
);

INSERT INTO floor_obligation_band (sort_rank, upper_ratio_bp, multiplier_bp, label) VALUES
    (1, 4000, 11500, 'comfortable'),
    (2, 5500, 10000, 'normal'),
    (3, 7000,  8500, 'loaded'),
    (4, NULL,  7000, 'stretched');

-- Fallback used where the user has no city figures yet, so the very first screen shows a credible
-- number rather than a blank. BLS Consumer Expenditure Survey 2024, total annual expenditure
-- $78,535: entertainment 4.6%, food away from home $3,945 (5.0%), apparel and services 2.5%.
--
-- The approximation, stated rather than hidden: these are shares of EXPENDITURE, applied here to
-- NET INCOME. Households spend close to their net income, which makes the substitution reasonable
-- and keeps the fallback in the same units as everything else the user has told us.
CREATE TABLE discretionary_national_share (
    category              TEXT    PRIMARY KEY,
    pct_of_net_income_bp  INTEGER NOT NULL CHECK (pct_of_net_income_bp BETWEEN 0 AND 10000),
    source_note           TEXT    NOT NULL
);

INSERT INTO discretionary_national_share (category, pct_of_net_income_bp, source_note) VALUES
    ('ENTERTAINMENT', 460, 'BLS CEX 2024: entertainment, 4.6% of $78,535 annual expenditure'),
    ('DINING_OUT',    500, 'BLS CEX 2024: food away from home, $3,945 of $78,535 (5.0%)'),
    ('CLOTHING',      250, 'BLS CEX 2024: apparel and services, 2.5% of $78,535 annual expenditure');

-- Both ends of the claim the floor makes. The lower bound stops a heavily indebted user being told
-- to live on nothing; the upper stops the floor swallowing the whole surplus and making every goal
-- look infeasible. One row, because there is one policy.
CREATE TABLE discretionary_floor_clamp (
    singleton  BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    at_least_bp INTEGER NOT NULL CHECK (at_least_bp >= 0),
    at_most_bp  INTEGER NOT NULL CHECK (at_most_bp  >= 0),
    CHECK (at_least_bp <= at_most_bp)
);

INSERT INTO discretionary_floor_clamp (singleton, at_least_bp, at_most_bp) VALUES (TRUE, 300, 1200);
