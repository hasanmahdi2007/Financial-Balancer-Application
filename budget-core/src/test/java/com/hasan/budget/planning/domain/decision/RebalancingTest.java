package com.hasan.budget.planning.domain.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.Rigidity;
import com.hasan.budget.shared.SpendCategory;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The parts of rebalancing the executable specification does not reach: the tie-break inside a
 * rigidity tier, the category the engine refuses to name a number for, and the inputs a caller can
 * get wrong.
 */
class RebalancingTest {

    /**
     * Rigidity decides who gives; the category's own cut order only breaks ties inside a tier. Both
     * of these lines are equally flexible as far as the user said, so the tie is broken by the stated
     * order rather than by whichever happened to be listed first — which is what stops the same plan
     * producing two different answers depending on how the caller assembled its list.
     */
    @Test
    void withinOneRigidityTierTheCategorysOwnCutOrderDecides() {
        List<BudgetLine> listedClothesFirst = List.of(
                flexible("clothes", "Clothes", SpendCategory.CLOTHING, Money.of(100)),
                flexible("eatingOut", "Eating out", SpendCategory.DINING_OUT, Money.of(100)),
                flexible("goingOut", "Going out", SpendCategory.ENTERTAINMENT, Money.of(50)));
        List<BudgetLine> listedTheOtherWayRound = List.of(
                flexible("eatingOut", "Eating out", SpendCategory.DINING_OUT, Money.of(100)),
                flexible("clothes", "Clothes", SpendCategory.CLOTHING, Money.of(100)),
                flexible("goingOut", "Going out", SpendCategory.ENTERTAINMENT, Money.of(50)));

        RebalanceResult first = Rebalancing.apply(new RebalanceRequest(listedClothesFirst, "goingOut", Money.of(160)));
        RebalanceResult second =
                Rebalancing.apply(new RebalanceRequest(listedTheOtherWayRound, "goingOut", Money.of(160)));

        // Eating out is the earlier cut candidate of the two, so it gives all it has before clothes is
        // asked for anything, whichever order the lines arrived in.
        assertThat(first.adjustmentFor("eatingOut").orElseThrow().to()).isEqualTo(Money.ZERO);
        assertThat(first.adjustmentFor("clothes").orElseThrow().to()).isEqualTo(Money.of(40));
        assertThat(second.adjustments()).isEqualTo(first.adjustments());
    }

    /**
     * "Spend $60 less on spending we could not identify" is not something anyone can act on, so the
     * engine does not say it — here for the same reason the allocator does not. The money is still
     * really there, which is exactly why this has to be a deliberate rule rather than an oversight:
     * the arithmetic would happily balance without it.
     */
    @Test
    void spendingTheEngineCannotNameIsNeverTakenFrom() {
        List<BudgetLine> lines = List.of(
                flexible("unknown", "Everything else", SpendCategory.OTHER, Money.of(200)),
                flexible("goingOut", "Going out", SpendCategory.ENTERTAINMENT, Money.of(50)));

        RebalanceResult result = Rebalancing.apply(new RebalanceRequest(lines, "goingOut", Money.of(60)));

        assertThat(result.outcome()).isEqualTo(RebalanceOutcome.INFEASIBLE);
        assertThat(result.adjustmentFor("unknown")).isEmpty();
        assertThat(result.residualGap()).isEqualTo(Money.of(60));
        // And it gets the same treatment a locked line gets: advice about the real lever, which here is
        // finding out what the spending was in the first place.
        assertThat(result.hintFor("unknown").orElseThrow().lever()).contains("naming it");
    }

    /**
     * The raised line comes first in the adjustments, because the answer a person reads is "your
     * eating out goes up to $350, and here is what pays for it" rather than a list of cuts they have
     * to work backwards from.
     */
    @Test
    void theRaisedLineIsReportedBeforeWhatPaysForIt() {
        List<BudgetLine> lines = List.of(
                flexible("clothes", "Clothes", SpendCategory.CLOTHING, Money.of(100)),
                flexible("eatingOut", "Eating out", SpendCategory.DINING_OUT, Money.of(300)));

        RebalanceResult result = Rebalancing.apply(new RebalanceRequest(lines, "eatingOut", Money.of(50)));

        assertThat(result.adjustments()).hasSize(2);
        assertThat(result.adjustments().getFirst().lineItemId()).isEqualTo("eatingOut");
        assertThat(result.adjustments().getFirst().to()).isEqualTo(Money.of(350));
        assertThat(result.adjustments().getFirst().change()).isEqualTo(Money.of(50));
        assertThat(result.adjustments().getLast().change()).isEqualTo(Money.of(-50));
    }

    /** The two options are offered exactly when something is actually missing, and not otherwise. */
    @Test
    void theTwoOptionsAppearOnlyWhenSomethingIsMissing() {
        List<BudgetLine> lines = List.of(
                flexible("clothes", "Clothes", SpendCategory.CLOTHING, Money.of(100)),
                flexible("eatingOut", "Eating out", SpendCategory.DINING_OUT, Money.of(300)));

        assertThat(Rebalancing.apply(new RebalanceRequest(lines, "eatingOut", Money.of(50))).options())
                .isEmpty();
        assertThat(Rebalancing.apply(new RebalanceRequest(lines, "eatingOut", Money.of(500))).options())
                .containsExactly(
                        ResolutionOption.COUNT_MORE_OF_YOUR_BALANCE, ResolutionOption.GIVE_A_GOAL_MORE_TIME);
    }

    @Test
    void aRequestThatCannotBeAnsweredIsRejectedRatherThanGuessed() {
        List<BudgetLine> lines =
                List.of(flexible("eatingOut", "Eating out", SpendCategory.DINING_OUT, Money.of(300)));

        assertThatThrownBy(() -> new RebalanceRequest(lines, "eatingOut", Money.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("increase must be positive");

        assertThatThrownBy(() -> new RebalanceRequest(lines, "somethingElse", Money.of(50)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no line has id");

        // Two lines sharing an id would make "your gym goes to $40" ambiguous about which one moved,
        // which is the whole reason lines are named rather than merely categorised.
        List<BudgetLine> duplicated = List.of(
                flexible("gym", "Your gym", SpendCategory.SUBSCRIPTIONS, Money.of(60)),
                flexible("gym", "The other gym", SpendCategory.SUBSCRIPTIONS, Money.of(40)));
        assertThatThrownBy(() -> new RebalanceRequest(duplicated, "gym", Money.of(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("line ids must be unique");
    }

    /**
     * What the user called unimportant may go to zero, and what they called flexible may not go below
     * its floor. Both are the same instruction read faithfully: a local baseline is an estimate of
     * what something costs people here, and "not very important" is a better answer about this
     * person's own spending than any estimate.
     */
    @Test
    void whatTheUserCalledUnimportantMayGoToZeroAndTheRestMayNot() {
        BudgetLine disposable = new BudgetLine(
                "goingOut", "Going out", SpendCategory.ENTERTAINMENT, Money.of(100), Money.of(80), Rigidity.DISPOSABLE);
        BudgetLine flexible = new BudgetLine(
                "clothes", "Clothes", SpendCategory.CLOTHING, Money.of(100), Money.of(80), Rigidity.FLEXIBLE);
        BudgetLine essential = new BudgetLine(
                "streaming",
                "Streaming and apps",
                SpendCategory.SUBSCRIPTIONS,
                Money.of(100),
                Money.of(80),
                Rigidity.ESSENTIAL);

        assertThat(disposable.cutFloor()).isEqualTo(Money.ZERO);
        assertThat(disposable.headroom()).isEqualTo(Money.of(100));
        assertThat(flexible.headroom()).isEqualTo(Money.of(20));
        assertThat(essential.headroom()).isEqualTo(Money.of(20));

        BudgetLine locked = new BudgetLine(
                "rent", "Your rent", SpendCategory.RENT, Money.of(1200), Money.of(1000), Rigidity.LOCKED);
        assertThat(locked.headroom()).isEqualTo(Money.ZERO);
        assertThat(locked.mayGive()).isFalse();
        assertThat(locked.mayRise()).isFalse();
    }

    /**
     * Every answer this engine can give has to be sayable to a person. A user shown TARGET_IS_LOCKED,
     * or told their increase was not absorbed because of its rigidity, is being asked to guess at the
     * vocabulary of the codebase — which the project treats as a defect rather than a polish item.
     *
     * <p>Driven off the enums themselves rather than a hand-written list, so an outcome added later
     * cannot ship without wording.
     */
    @Test
    void everyOutcomeExplainsItselfWithoutInternalVocabulary() {
        for (RebalanceOutcome outcome : RebalanceOutcome.values()) {
            assertThat(outcome.label())
                    .as("%s has a label for a person", outcome)
                    .isNotBlank()
                    .doesNotContain(outcome.name());
            assertThat(outcome.meaning())
                    .as("%s explains itself in the user's words", outcome)
                    .isNotBlank()
                    .doesNotContain(outcome.name(), "rigidity", "residual", "discretionary", "baseline");
        }
        for (ResolutionOption option : ResolutionOption.values()) {
            assertThat(option.label()).isNotBlank().doesNotContain(option.name());
            assertThat(option.meaning()).isNotBlank().doesNotContain(option.name());
        }
    }

    /** A line the user called flexible, with no floor under it, so headroom is its whole amount. */
    private static BudgetLine flexible(String id, String label, SpendCategory category, Money amount) {
        return new BudgetLine(id, label, category, amount, Money.ZERO, Rigidity.FLEXIBLE);
    }
}
