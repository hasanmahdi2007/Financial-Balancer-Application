-- Tax resolves through the same ordered chain as a cost-of-living baseline: the user's own figure
-- first, then the seeded country estimate. Two tables, because the second must never learn anything
-- from the first - a typed rate is private to that account, and a bug that promoted one user's
-- answer into the shared row would be invisible on one machine and serious on a shared one.
--
-- Stored as an EFFECTIVE rate and labelled ESTIMATED. Real systems are progressive, with brackets,
-- allowances and deductions; a single percentage approximates one person's position in that system
-- and is not a statutory truth. Presenting it as one would be exactly the false precision the
-- confidence model exists to prevent.
--
-- Whole basis points, and no foreign key to `country` or `data_source` - see V10 for why.

CREATE TABLE country_tax_rate (
    country_code      CHAR(2) PRIMARY KEY,
    effective_rate_bp INTEGER NOT NULL CHECK (effective_rate_bp BETWEEN 0 AND 9999),
    source_name       TEXT    NOT NULL,
    as_of             DATE    NOT NULL
);

-- Researched estimates, committed rather than fetched: no migration, test or demo may depend on a
-- network that drops. Both are mid-schedule figures for a self-employed filer, which is the only
-- user a tax reserve is ever funded for.
INSERT INTO country_tax_rate (country_code, effective_rate_bp, source_name, as_of) VALUES
    ('LB', 1500, 'Lebanon Law 144/2019 non-salaried schedule (4%-25%), mid-band estimate', DATE '2025-01-01'),
    ('US', 2500, 'US federal bracket plus self-employment tax, net of the standard deduction', DATE '2025-01-01');

-- The user's own figure. Private to one account, permanently, and it beats the seeded rate.
CREATE TABLE user_tax_override (
    user_id           TEXT    PRIMARY KEY,
    effective_rate_bp INTEGER NOT NULL CHECK (effective_rate_bp BETWEEN 0 AND 9999),
    set_at            DATE    NOT NULL
);
