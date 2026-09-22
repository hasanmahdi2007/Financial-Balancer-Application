-- Plans exactly as they were shown, kept forever.
--
-- Append-only, and enforced here rather than only by convention: an UPDATE, DELETE or TRUNCATE on
-- this table raises. Adding a goal must never rewrite what the plan said before, because "adding the
-- emergency fund pushed the car behind" is only true if both halves are what the user actually saw.
-- Taking money back out of a plan is a new snapshot, never an edit to an old one.
--
-- The body is the plan as the user read it, words and all. Storing inputs and recomputing on read
-- would show today's arithmetic over yesterday's inputs, which is not what the plan said.

CREATE TABLE plan_snapshot (
    id        TEXT        PRIMARY KEY,
    user_id   TEXT        NOT NULL,
    taken_at  TIMESTAMPTZ NOT NULL,
    reason    TEXT        NOT NULL,
    body      JSONB       NOT NULL
);

CREATE INDEX plan_snapshot_by_user_newest_first ON plan_snapshot (user_id, taken_at DESC, id DESC);

CREATE FUNCTION plan_snapshot_is_append_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'plan snapshots are append-only: % is not allowed', TG_OP;
END;
$$;

CREATE TRIGGER plan_snapshot_no_update_or_delete
    BEFORE UPDATE OR DELETE ON plan_snapshot
    FOR EACH ROW EXECUTE FUNCTION plan_snapshot_is_append_only();

CREATE TRIGGER plan_snapshot_no_truncate
    BEFORE TRUNCATE ON plan_snapshot
    FOR EACH STATEMENT EXECUTE FUNCTION plan_snapshot_is_append_only();
