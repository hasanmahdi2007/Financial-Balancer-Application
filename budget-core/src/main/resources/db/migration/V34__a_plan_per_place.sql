-- A plan belongs to a place (P11).
--
-- Until now every planning table was keyed by the user alone: one location, one set of spending, one
-- set of goals and one history per person. Moving city re-priced the same plan with the new city's
-- figures, so Beirut rent and Beirut spending turned up in a New York plan, and the history mixed the
-- two. From here a user holds any number of plans, one of them active, and a move either resumes a
-- plan they already had in that country or starts a new one.
--
-- A plan IS a planning_profile row. The profile was already "where you live and how you live there",
-- which is exactly what distinguishes one plan from another, so it gains an id rather than gaining a
-- parent table that would duplicate its key.
--
-- What stays the person's, not the plan's: the balance and what they already move to savings
-- (planning_money), and everything read off their bank. Money in an account moves with its owner, and
-- restoring a two-year-old plan must never restore a two-year-old balance. Income moves the other
-- way: it belongs to the plan, because a move usually changes what arrives each month.
--
-- Every key below still starts with user_id, so no query can reach another user's plan by its id.

-- ---- the plan itself --------------------------------------------------------------------------------

ALTER TABLE planning_profile
    ADD COLUMN plan_id        TEXT,
    ADD COLUMN monthly_income NUMERIC(12, 2) CHECK (monthly_income >= 0),
    ADD COLUMN created_at     TIMESTAMPTZ,
    ADD COLUMN last_used_at   TIMESTAMPTZ;

-- Every existing user becomes the owner of exactly one plan: the one they have been using.
UPDATE planning_profile
SET plan_id      = gen_random_uuid()::text,
    created_at   = updated_at,
    last_used_at = updated_at;

UPDATE planning_profile p
SET monthly_income = m.monthly_income
FROM planning_money m
WHERE m.user_id = p.user_id;

ALTER TABLE planning_profile
    ALTER COLUMN plan_id SET NOT NULL,
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN created_at SET DEFAULT now(),
    ALTER COLUMN last_used_at SET NOT NULL,
    ALTER COLUMN last_used_at SET DEFAULT now(),
    DROP CONSTRAINT planning_profile_pkey,
    ADD PRIMARY KEY (user_id, plan_id);

-- The chooser lists a user's plans in one country, newest use first.
CREATE INDEX planning_profile_by_country ON planning_profile (user_id, country_code, last_used_at DESC);

-- Exactly one active plan per user, and only ever one of their own.
CREATE TABLE planning_active_plan (
    user_id TEXT PRIMARY KEY,
    plan_id TEXT NOT NULL,
    FOREIGN KEY (user_id, plan_id) REFERENCES planning_profile (user_id, plan_id)
);

INSERT INTO planning_active_plan (user_id, plan_id)
SELECT user_id, plan_id FROM planning_profile;

-- Income has moved to the plan. A user who typed money before choosing a place loses only the income
-- figure, and is asked for it again: the app asks where someone lives before anything else, so in
-- practice there are none.
ALTER TABLE planning_money
    DROP COLUMN monthly_income;

-- ---- inputs that belong to a plan -------------------------------------------------------------------
--
-- Rows with no profile behind them cannot be filed under a plan, and could never have reached one:
-- a plan cannot be made without a place. They are removed rather than guessed at.

ALTER TABLE planning_spending ADD COLUMN plan_id TEXT;
UPDATE planning_spending s SET plan_id = p.plan_id FROM planning_profile p WHERE p.user_id = s.user_id;
DELETE FROM planning_spending WHERE plan_id IS NULL;
ALTER TABLE planning_spending
    ALTER COLUMN plan_id SET NOT NULL,
    DROP CONSTRAINT planning_spending_pkey,
    ADD PRIMARY KEY (user_id, plan_id, category),
    ADD FOREIGN KEY (user_id, plan_id) REFERENCES planning_profile (user_id, plan_id);

-- An item's id is a name the user chose ("my-gym"), so the same name may exist in two plans.
ALTER TABLE planning_line_item ADD COLUMN plan_id TEXT;
UPDATE planning_line_item i SET plan_id = p.plan_id FROM planning_profile p WHERE p.user_id = i.user_id;
DELETE FROM planning_line_item WHERE plan_id IS NULL;
ALTER TABLE planning_line_item
    ALTER COLUMN plan_id SET NOT NULL,
    DROP CONSTRAINT planning_line_item_pkey,
    ADD PRIMARY KEY (user_id, plan_id, id),
    ADD FOREIGN KEY (user_id, plan_id) REFERENCES planning_profile (user_id, plan_id);

-- The finish-first nomination points at a goal, so its key changes with the goal's.
ALTER TABLE planning_finish_first
    DROP CONSTRAINT planning_finish_first_user_id_goal_id_fkey;

ALTER TABLE planning_goal ADD COLUMN plan_id TEXT;
UPDATE planning_goal g SET plan_id = p.plan_id FROM planning_profile p WHERE p.user_id = g.user_id;
DELETE FROM planning_finish_first f
WHERE NOT EXISTS (SELECT 1 FROM planning_profile p WHERE p.user_id = f.user_id);
DELETE FROM planning_goal WHERE plan_id IS NULL;
ALTER TABLE planning_goal
    ALTER COLUMN plan_id SET NOT NULL,
    DROP CONSTRAINT planning_goal_pkey,
    ADD PRIMARY KEY (user_id, plan_id, id),
    ADD FOREIGN KEY (user_id, plan_id) REFERENCES planning_profile (user_id, plan_id);

ALTER TABLE planning_finish_first ADD COLUMN plan_id TEXT;
UPDATE planning_finish_first f SET plan_id = p.plan_id FROM planning_profile p WHERE p.user_id = f.user_id;
ALTER TABLE planning_finish_first
    ALTER COLUMN plan_id SET NOT NULL,
    DROP CONSTRAINT planning_finish_first_pkey,
    ADD PRIMARY KEY (user_id, plan_id),
    ADD FOREIGN KEY (user_id, plan_id, goal_id)
        REFERENCES planning_goal (user_id, plan_id, id) ON DELETE CASCADE;

-- ---- snapshots ---------------------------------------------------------------------------------------
--
-- plan_snapshot refuses every UPDATE by trigger, and this is the one write it is ever allowed: filing
-- each existing snapshot under the plan it was made for. `body` - what the user was told - is not
-- touched. The triggers are switched off for the statement and back on before this migration ends,
-- inside the migration's own transaction, so there is no moment at which another writer could use the
-- gap.
--
-- Every snapshot has a profile behind it, because a plan cannot be made without one and profiles are
-- never deleted. If that were ever untrue, SET NOT NULL below fails and the migration stops rather than
-- leaving history unfiled.

ALTER TABLE plan_snapshot ADD COLUMN plan_id TEXT;

ALTER TABLE plan_snapshot DISABLE TRIGGER plan_snapshot_no_update_or_delete;
UPDATE plan_snapshot s SET plan_id = p.plan_id FROM planning_profile p WHERE p.user_id = s.user_id;
ALTER TABLE plan_snapshot ENABLE TRIGGER plan_snapshot_no_update_or_delete;

ALTER TABLE plan_snapshot
    ALTER COLUMN plan_id SET NOT NULL,
    ADD FOREIGN KEY (user_id, plan_id) REFERENCES planning_profile (user_id, plan_id);

CREATE INDEX plan_snapshot_by_plan_newest_first ON plan_snapshot (user_id, plan_id, seq DESC);
