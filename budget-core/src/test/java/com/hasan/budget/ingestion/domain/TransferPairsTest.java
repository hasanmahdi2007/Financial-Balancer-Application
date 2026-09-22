package com.hasan.budget.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import com.hasan.budget.shared.TransactionKind;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * When two rows are one movement, and - more importantly - when they are not.
 *
 * <p>Collapsing too eagerly is worse than not collapsing at all: a purchase and its refund are
 * opposite and equal too, and treating them as one transfer would delete the refund and overstate
 * spending by the full amount.
 */
class TransferPairsTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 14);

    @Test
    @DisplayName("the two halves of one transfer become one row, and the outflow is the one kept")
    void bothHalvesOfATransferCollapse() {
        LedgerEntry out = transfer("out", "checking", "250.00", MONDAY);
        LedgerEntry in = transfer("in", "savings", "-250.00", MONDAY.plusDays(1));

        List<LedgerEntry> collapsed = TransferPairs.collapse(List.of(out, in));

        assertThat(collapsed).singleElement().satisfies(kept -> assertThat(
                        kept.transaction().externalId())
                .isEqualTo("out"));
    }

    @Test
    @DisplayName("a purchase and its refund are never collapsed, however alike they look")
    void spendingIsNeverTreatedAsATransfer() {
        LedgerEntry bought = spend("bought", "card", "34.99", MONDAY);
        LedgerEntry refunded = spend("refunded", "card", "-34.99", MONDAY.plusDays(2));

        assertThat(TransferPairs.collapse(List.of(bought, refunded))).hasSize(2);
    }

    @Test
    @DisplayName("an account cannot transfer to itself")
    void twoTransfersOnOneAccountAreTwoMovements() {
        LedgerEntry out = transfer("out", "checking", "250.00", MONDAY);
        LedgerEntry in = transfer("in", "checking", "-250.00", MONDAY);

        assertThat(TransferPairs.collapse(List.of(out, in))).hasSize(2);
    }

    @Test
    @DisplayName("halves more than three days apart are left alone")
    void aDistantMatchIsNotTheSameMoney() {
        LedgerEntry out = transfer("out", "checking", "250.00", MONDAY);
        LedgerEntry in = transfer("in", "savings", "-250.00", MONDAY.plusDays(4));

        assertThat(TransferPairs.collapse(List.of(out, in))).hasSize(2);
    }

    @Test
    @DisplayName("two different banks cannot be two halves of one transfer")
    void matchingNeverCrossesAConnection() {
        LedgerEntry out = new LedgerEntry(1, transaction("out", "checking", "250.00", MONDAY, internal()));
        LedgerEntry in = new LedgerEntry(2, transaction("in", "savings", "-250.00", MONDAY, internal()));

        assertThat(TransferPairs.collapse(List.of(out, in))).hasSize(2);
    }

    @Test
    @DisplayName("different amounts are different movements")
    void onlyAnExactMagnitudeMatches() {
        LedgerEntry out = transfer("out", "checking", "250.00", MONDAY);
        LedgerEntry in = transfer("in", "savings", "-249.99", MONDAY);

        assertThat(TransferPairs.collapse(List.of(out, in))).hasSize(2);
    }

    @Test
    @DisplayName("with two candidates the nearer in time is the pair")
    void theClosestMatchWins() {
        LedgerEntry out = transfer("out", "checking", "250.00", MONDAY);
        LedgerEntry near = transfer("near", "savings", "-250.00", MONDAY.plusDays(1));
        LedgerEntry far = transfer("far", "savings", "-250.00", MONDAY.plusDays(3));

        List<LedgerEntry> collapsed = TransferPairs.collapse(List.of(out, far, near));

        assertThat(collapsed)
                .extracting(entry -> entry.transaction().externalId())
                .containsExactlyInAnyOrder("out", "far");
    }

    @Test
    @DisplayName("two transfers of the same size take one half each rather than sharing one")
    void eachOutflowConsumesItsOwnMatch() {
        LedgerEntry firstOut = transfer("out-1", "checking", "250.00", MONDAY);
        LedgerEntry secondOut = transfer("out-2", "checking", "250.00", MONDAY.plusDays(1));
        LedgerEntry firstIn = transfer("in-1", "savings", "-250.00", MONDAY);
        LedgerEntry secondIn = transfer("in-2", "savings", "-250.00", MONDAY.plusDays(1));

        List<LedgerEntry> collapsed =
                TransferPairs.collapse(List.of(firstOut, secondOut, firstIn, secondIn));

        assertThat(collapsed)
                .extracting(entry -> entry.transaction().externalId())
                .containsExactlyInAnyOrder("out-1", "out-2");
    }

    @Test
    @DisplayName("an unmatched half is left exactly as it was")
    void oneSidedTransfersSurvive() {
        LedgerEntry out = transfer("out", "checking", "250.00", MONDAY);

        assertThat(TransferPairs.collapse(List.of(out))).containsExactly(out);
    }

    @Test
    @DisplayName("which half is absorbed does not depend on the order rows were stored in")
    void collapsingIsDeterministic() {
        LedgerEntry out = transfer("out", "checking", "250.00", MONDAY);
        LedgerEntry first = transfer("in-a", "savings", "-250.00", MONDAY.plusDays(1));
        LedgerEntry second = transfer("in-b", "savings", "-250.00", MONDAY.plusDays(1));

        // Equally close, so the tie is broken by identifier rather than by whichever was read first.
        assertThat(survivors(List.of(out, first, second))).containsExactlyInAnyOrder("out", "in-b");
        assertThat(survivors(List.of(second, first, out))).containsExactlyInAnyOrder("out", "in-b");
    }

    private static List<String> survivors(List<LedgerEntry> entries) {
        return TransferPairs.collapse(entries).stream()
                .map(entry -> entry.transaction().externalId())
                .toList();
    }

    private static LedgerEntry transfer(String id, String account, String amount, LocalDate date) {
        return new LedgerEntry(1, transaction(id, account, amount, date, internal()));
    }

    private static LedgerEntry spend(String id, String account, String amount, LocalDate date) {
        return new LedgerEntry(1, transaction(id, account, amount, date, Classification.spend(SpendCategory.OTHER)));
    }

    private static Classification internal() {
        return Classification.notSpending(TransactionKind.TRANSFER_INTERNAL);
    }

    private static NormalisedTransaction transaction(
            String id, String account, String amount, LocalDate date, Classification classification) {
        return new NormalisedTransaction(
                id, account, date, Money.of(amount), null, null, null, null, classification, false);
    }
}
