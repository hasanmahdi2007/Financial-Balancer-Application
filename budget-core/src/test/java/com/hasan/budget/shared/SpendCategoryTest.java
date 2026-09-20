package com.hasan.budget.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards the invariants of the category table.
 *
 * <p>The point of putting policy in a table is that adding a category is one row. These tests are
 * what make that safe rather than merely short: a new row with a contradictory combination of axes,
 * or a duplicated cut order, fails here instead of silently changing what the engine recommends.
 */
class SpendCategoryTest {

    @Test
    void everyCategoryDeclaresAllOfItsPolicy() {
        for (SpendCategory category : SpendCategory.values()) {
            assertThat(category.commitment()).as("commitment of %s", category).isNotNull();
            assertThat(category.cutSpeed()).as("cut speed of %s", category).isNotNull();
            assertThat(category.baselinePolicy()).as("baseline policy of %s", category).isNotNull();
            assertThat(category.defaultRigidity()).as("rigidity of %s", category).isNotNull();
        }
    }

    @Test
    void onlyFixedCommitmentsAreTakenAsIs() {
        // Taking an amount as-is means declining to cap it against a local baseline, which is only
        // defensible when it is genuinely owed this month.
        assertThat(SpendCategory.values())
                .filteredOn(category -> category.baselinePolicy() == BaselinePolicy.TAKE_AS_IS)
                .allMatch(category -> category.commitment() == Commitment.FIXED);
    }

    @Test
    void onlyVariableSpendingIsMeasuredAgainstABaseline() {
        assertThat(SpendCategory.values())
                .filteredOn(category -> category.baselinePolicy() != BaselinePolicy.TAKE_AS_IS)
                .allMatch(category -> category.commitment() == Commitment.VARIABLE);
    }

    @Test
    void nothingLockedByDefaultIsEverProposedForACut() {
        assertThat(SpendCategory.values())
                .filteredOn(category -> category.defaultRigidity() == Rigidity.LOCKED)
                .noneMatch(SpendCategory::autoSuggestCuts);
    }

    @Test
    void cutOrdersAreUniqueAmongTheCategoriesTheEngineMayPropose() {
        List<Integer> orders = suggestible().map(SpendCategory::cutOrder).toList();

        assertThat(orders).doesNotHaveDuplicates();
    }

    @Test
    void theProposedCutOrderIsTheDocumentedOne() {
        List<SpendCategory> order = suggestible()
                .sorted(Comparator
                        .comparing(SpendCategory::defaultRigidity)
                        .thenComparingInt(SpendCategory::cutOrder))
                .toList();

        // Subscriptions come last among cuttables: cancelling is a more permanent decision than
        // skipping a night out, but they stay available, which is the forgotten-subscription case.
        assertThat(order).containsExactly(
                SpendCategory.ENTERTAINMENT,
                SpendCategory.DINING_OUT,
                SpendCategory.CLOTHING,
                SpendCategory.SUBSCRIPTIONS);
    }

    @Test
    void unidentifiedSpendingIsDiscretionaryButNeverProposed() {
        assertThat(SpendCategory.OTHER.baselinePolicy()).isEqualTo(BaselinePolicy.DISCRETIONARY);
        assertThat(SpendCategory.OTHER.autoSuggestCuts()).isFalse();
    }

    @Test
    void onlyCategoriesWithoutALocalPriceLackAPriceComponent() {
        // Debt instalments and tax are set by a contract or a rate, not by what things cost locally,
        // so there is no regional index to localise them with.
        assertThat(SpendCategory.values())
                .filteredOn(category -> category.priceComponent().isEmpty())
                .containsExactlyInAnyOrder(SpendCategory.DEBT_PAYMENT, SpendCategory.TAX_RESERVE);
    }

    @Test
    void everyCategoryCanBeExplainedToAUser() {
        // A prompt built from this table is only honest if every entry has something to say.
        for (SpendCategory category : SpendCategory.values()) {
            assertThat(category.label()).as("label of %s", category).isNotBlank();
            assertThat(category.covers()).as("covers of %s", category).isNotBlank();
        }
    }

    @Test
    void noUserFacingLabelLeaksTheConstantName() {
        // "DINING_OUT" in an interface is a developer's word escaping into a user's question.
        assertThat(SpendCategory.values())
                .allSatisfy(category -> {
                    assertThat(category.label()).doesNotContain("_");
                    assertThat(category.label()).isNotEqualTo(category.name());
                });
    }

    @Test
    void labelsAreDistinctSoTwoRowsCannotReadTheSame() {
        assertThat(java.util.Arrays.stream(SpendCategory.values()).map(SpendCategory::label).toList())
                .doesNotHaveDuplicates();
    }

    @Test
    void subscriptionsAreOwedThisMonthYetStillCuttable() {
        // The case a single flexibility rank could not express, and the reason the axes are separate.
        assertThat(SpendCategory.SUBSCRIPTIONS.baselinePolicy()).isEqualTo(BaselinePolicy.TAKE_AS_IS);
        assertThat(SpendCategory.SUBSCRIPTIONS.cutSpeed()).isEqualTo(CutSpeed.IMMEDIATE);
        assertThat(SpendCategory.SUBSCRIPTIONS.autoSuggestCuts()).isTrue();
    }

    private static java.util.stream.Stream<SpendCategory> suggestible() {
        return java.util.Arrays.stream(SpendCategory.values()).filter(SpendCategory::autoSuggestCuts);
    }
}
