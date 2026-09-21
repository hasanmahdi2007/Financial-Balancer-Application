package com.hasan.budget.profile.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hasan.budget.shared.BaselinePolicy;
import com.hasan.budget.shared.SpendCategory;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The policy is loaded from tables, so a half-seeded or badly edited table is a real failure mode.
 * These are the invariants that make it fail loudly there rather than quietly produce a floor of
 * zero somewhere much later.
 */
class FloorPolicyTest {

    @ParameterizedTest
    @CsvSource({
        "10, 115", "40, 115", // inclusive at the top of the band
        "41, 100", "45, 100", "55, 100",
        "56, 85", "70, 85",
        "71, 70", "95, 70" // the unbounded band catches the most stretched users
    })
    void theObligationLadderPicksTheBandTheRatioFallsIn(String ratio, String multiplier) {
        assertThat(SeededFloorPolicy.policy().multiplierFor(Rate.ofPercent(ratio)))
                .isEqualTo(Rate.ofPercent(multiplier));
    }

    /**
     * A tier with no rows would compute a floor of zero for everyone in it, which is exactly the
     * behaviour the floor exists to prevent - and it would look like a working system.
     */
    @Test
    void aTierWithNoRowsIsRejectedRatherThanTreatedAsZero() {
        Map<LifestyleTier, Map<SpendCategory, Rate>> missingFrequent = Map.of(
                LifestyleTier.HOMEBODY, everyProtectedCategoryAt("25"),
                LifestyleTier.OCCASIONAL, everyProtectedCategoryAt("35"),
                LifestyleTier.REGULAR, everyProtectedCategoryAt("45"));

        assertThatThrownBy(() -> new FloorPolicy(
                        missingFrequent,
                        seededBands(),
                        seededNationalShares(),
                        Rate.ofPercent("3"),
                        Rate.ofPercent("12")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FREQUENT");
    }

    /** Same failure, one category down: a missing row silently stops protecting that spending. */
    @Test
    void aTierMissingOneCategoryIsRejected() {
        Map<SpendCategory, Rate> withoutClothing = Map.of(
                SpendCategory.ENTERTAINMENT, Rate.ofPercent("45"),
                SpendCategory.DINING_OUT, Rate.ofPercent("45"));
        Map<LifestyleTier, Map<SpendCategory, Rate>> shares = new EnumMap<>(LifestyleTier.class);
        for (LifestyleTier tier : LifestyleTier.values()) {
            shares.put(tier, everyProtectedCategoryAt("45"));
        }
        shares.put(LifestyleTier.REGULAR, withoutClothing);

        assertThatThrownBy(() -> new FloorPolicy(
                        shares, seededBands(), seededNationalShares(), Rate.ofPercent("3"), Rate.ofPercent("12")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * A ladder that stops short leaves the most heavily committed users - the group the floor
     * matters most for - with no multiplier at all.
     */
    @Test
    void aLadderWithNoUnboundedBandIsRejected() {
        List<ObligationBand> stopsAtSeventy = List.of(
                new ObligationBand(Rate.ofPercent("40"), Rate.ofPercent("115")),
                new ObligationBand(Rate.ofPercent("70"), Rate.ofPercent("85")));

        assertThatThrownBy(() -> seededPolicyWithBands(stopsAtSeventy))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unbounded");
    }

    /** An unbounded band anywhere but last would swallow every band after it. */
    @Test
    void anUnboundedBandBeforeTheEndIsRejected() {
        List<ObligationBand> unboundedFirst = List.of(
                ObligationBand.above(Rate.ofPercent("115")),
                new ObligationBand(Rate.ofPercent("70"), Rate.ofPercent("85")));

        assertThatThrownBy(() -> seededPolicyWithBands(unboundedFirst))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bandsThatDoNotAscendAreRejected() {
        List<ObligationBand> outOfOrder = List.of(
                new ObligationBand(Rate.ofPercent("70"), Rate.ofPercent("115")),
                new ObligationBand(Rate.ofPercent("40"), Rate.ofPercent("100")),
                ObligationBand.above(Rate.ofPercent("70")));

        assertThatThrownBy(() -> seededPolicyWithBands(outOfOrder))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ascend");
    }

    @Test
    void clampsTheWrongWayRoundAreRejected() {
        assertThatThrownBy(() -> new FloorPolicy(
                        everyTierAt("45"),
                        seededBands(),
                        seededNationalShares(),
                        Rate.ofPercent("12"),
                        Rate.ofPercent("3")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The floor question, the policy table and the engine all read this one list. Deriving it is
     * what stops adding a category protecting it in one place and not the other two.
     */
    @Test
    void theProtectedSetIsEveryDiscretionaryCategoryTheEngineWouldCut() {
        assertThat(ProtectedSpending.categories())
                .containsExactly(
                        SpendCategory.ENTERTAINMENT, SpendCategory.DINING_OUT, SpendCategory.CLOTHING);

        // OTHER is discretionary, but "reduce Everything else by $150" is advice nobody can act on,
        // so the engine never proposes it and there is nothing there to protect.
        assertThat(SpendCategory.OTHER.baselinePolicy()).isEqualTo(BaselinePolicy.DISCRETIONARY);
        assertThat(ProtectedSpending.categories()).doesNotContain(SpendCategory.OTHER);
    }

    private static FloorPolicy seededPolicyWithBands(List<ObligationBand> bands) {
        return new FloorPolicy(
                everyTierAt("45"), bands, seededNationalShares(), Rate.ofPercent("3"), Rate.ofPercent("12"));
    }

    private static Map<LifestyleTier, Map<SpendCategory, Rate>> everyTierAt(String percent) {
        Map<LifestyleTier, Map<SpendCategory, Rate>> shares = new EnumMap<>(LifestyleTier.class);
        for (LifestyleTier tier : LifestyleTier.values()) {
            shares.put(tier, everyProtectedCategoryAt(percent));
        }
        return shares;
    }

    private static Map<SpendCategory, Rate> everyProtectedCategoryAt(String percent) {
        Map<SpendCategory, Rate> shares = new EnumMap<>(SpendCategory.class);
        ProtectedSpending.categories().forEach(category -> shares.put(category, Rate.ofPercent(percent)));
        return shares;
    }

    private static List<ObligationBand> seededBands() {
        return new ArrayList<>(SeededFloorPolicy.policy().obligationBands());
    }

    private static Map<SpendCategory, Rate> seededNationalShares() {
        return SeededFloorPolicy.policy().typicalShareOfNetIncome();
    }
}
