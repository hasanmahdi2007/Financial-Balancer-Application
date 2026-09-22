package com.hasan.budget.ingestion.domain;

import com.hasan.budget.shared.TransactionKind;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Collapses the two halves of one internal transfer into a single record.
 *
 * <p>With both accounts connected, moving $250 into savings arrives twice: once leaving checking and
 * once arriving in savings. Left alone the user is shown two movements for one, and any figure
 * summed over internal transfers counts the same money twice.
 *
 * <p>The match is deliberately narrow - same connection, <em>different</em> accounts, opposite signs,
 * identical magnitude, within three days - and applies only to transfers. Two of those conditions
 * are doing real work:
 *
 * <ul>
 *   <li><strong>Only transfers.</strong> A $34.99 purchase and a $34.99 refund three days later are
 *       opposite and equal too. Collapsing those would delete the refund and overstate spending by
 *       the whole amount.
 *   <li><strong>Different accounts.</strong> One account cannot transfer to itself, and this is what
 *       keeps a purchase and its refund - always on the same card - out of reach of the rule even if
 *       a provider ever tagged them as transfers.
 * </ul>
 *
 * <p>The outflow is the half that survives, because the outflow is where the user acted. The
 * unmatched half of a transfer - the usual case, when only one of the two accounts is connected -
 * is left exactly as it was classified.
 *
 * <p>Run over the stored ledger rather than at import, so it does not matter whether the two halves
 * arrive in the same sync, in different pages, or days apart.
 */
public final class TransferPairs {

    /** Three days each way: banks post the two halves of one transfer on different days. */
    private static final int MATCH_WITHIN_DAYS = 3;

    private TransferPairs() {}

    public static List<LedgerEntry> collapse(List<LedgerEntry> entries) {
        List<LedgerEntry> outflows = new ArrayList<>();
        List<LedgerEntry> inflows = new ArrayList<>();
        for (LedgerEntry entry : entries) {
            if (entry.classification().kind() == TransactionKind.TRANSFER_INTERNAL) {
                (SignConvention.isMoneyOut(entry.transaction().amount()) ? outflows : inflows).add(entry);
            }
        }
        // Sorted so that the result never depends on the order rows happened to be stored in.
        outflows.sort(byDateThenId());
        inflows.sort(byDateThenId());

        Set<String> absorbed = new HashSet<>();
        for (LedgerEntry outflow : outflows) {
            inflows.stream()
                    .filter(inflow -> !absorbed.contains(inflow.transaction().externalId()))
                    .filter(inflow -> isOtherHalfOf(outflow, inflow))
                    .min(Comparator.comparingLong((LedgerEntry inflow) -> daysBetween(outflow, inflow))
                            .thenComparing(inflow -> inflow.transaction().externalId()))
                    .ifPresent(match -> absorbed.add(match.transaction().externalId()));
        }
        if (absorbed.isEmpty()) {
            return List.copyOf(entries);
        }
        return entries.stream()
                .filter(entry -> !absorbed.contains(entry.transaction().externalId()))
                .toList();
    }

    private static boolean isOtherHalfOf(LedgerEntry outflow, LedgerEntry inflow) {
        return outflow.connectionId() == inflow.connectionId()
                && !outflow.transaction().accountId().equals(inflow.transaction().accountId())
                && outflow.transaction().amount().equals(SignConvention.shownToUser(inflow.transaction().amount()))
                && daysBetween(outflow, inflow) <= MATCH_WITHIN_DAYS;
    }

    private static long daysBetween(LedgerEntry one, LedgerEntry other) {
        return Math.abs(ChronoUnit.DAYS.between(one.transaction().date(), other.transaction().date()));
    }

    private static Comparator<LedgerEntry> byDateThenId() {
        return Comparator.comparing((LedgerEntry entry) -> entry.transaction().date())
                .thenComparing(entry -> entry.transaction().externalId());
    }
}
