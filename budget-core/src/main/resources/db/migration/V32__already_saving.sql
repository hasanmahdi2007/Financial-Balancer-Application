-- What the user already moves into savings each month.
--
-- It lives beside income and balance because it is the same kind of answer: something the user types
-- about their own money, not something derived. It is reported beside the plan and never subtracted
-- from it - putting money away is not spending it, and charging the user for it would understate
-- what they have left by exactly the amount they are already doing right.
--
-- Measuring it instead would need the kind of account each transfer went to: money moving into
-- savings and money paying off a credit card are both internal transfers, and only the destination
-- account tells them apart. `bank_transaction` does not keep that, so until it does, asking is the
-- honest source. NOT NULL DEFAULT 0 because "we have not asked yet" and "nothing" lead to the same
-- plan, and a nullable column would invite a null check in the arithmetic.
ALTER TABLE planning_money
    ADD COLUMN already_saving NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (already_saving >= 0);
