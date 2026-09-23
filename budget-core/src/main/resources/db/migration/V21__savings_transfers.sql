-- Which transfers add to what the user has put by.
--
-- A separate column rather than another value of `kind`, because it is a second, independent fact:
-- moving money to savings and paying a credit-card bill are both transfers between the user's own
-- accounts, and both are correctly excluded from spending. What distinguishes them is only whether
-- the money is still the user's afterwards. Reporting the two added together would tell somebody
-- they had saved the amount they just cleared off their card.
--
-- Signed rather than a magnitude: money coming back out of savings carries the same flag with a
-- negative amount, so a month of paying in and taking out nets off instead of counting twice.
--
-- A new column rather than a rewrite of V20, because V20 has already been applied. Existing rows
-- default to false, which is correct for every kind of movement except the four transfer categories
-- the mapping table flags - and a re-sync reclassifies those from the cursor anyway.
ALTER TABLE bank_transaction
    ADD COLUMN towards_savings BOOLEAN NOT NULL DEFAULT false;

-- Saving is a property of a transfer. Spending is never saving, and neither is income or a fee.
ALTER TABLE bank_transaction
    ADD CONSTRAINT bank_transaction_only_transfers_are_savings
        CHECK (NOT towards_savings OR kind = 'TRANSFER_INTERNAL');
