-- What the user says they already move into savings each month.
--
-- It lives beside income and balance because it is the same kind of answer: something the user types
-- about their own money. It is reported beside the plan and never subtracted from it - putting money
-- away is not spending it, and charging the user for it would understate what they have left by
-- exactly the amount they are already doing right.
--
-- Nullable on purpose. NULL means "not answered", and that is different from 0: a connected bank can
-- measure savings transfers (V21), and it should fill in the figure only for someone who has not
-- stated one. A user who types 0 is telling us they save nothing, and that outranks the bank the same
-- way their stated spending does.
ALTER TABLE planning_money
    ADD COLUMN already_saving NUMERIC(12, 2) CHECK (already_saving >= 0);
