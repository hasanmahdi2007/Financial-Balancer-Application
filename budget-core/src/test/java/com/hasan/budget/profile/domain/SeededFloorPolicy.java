package com.hasan.budget.profile.domain;

import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.util.List;
import java.util.Map;

/**
 * The floor policy exactly as migration {@code V10__discretionary_floor_policy.sql} seeds it.
 *
 * <p>Restated here so that every tier, band and clamp can be exercised in milliseconds with no
 * database. The obvious risk of restating data is that the two copies drift, so
 * {@code DiscretionaryFloorPolicyIT} reads the real tables and asserts they still produce this
 * exact policy. A change to either side that is not made to both fails that test.
 */
public final class SeededFloorPolicy {

    private SeededFloorPolicy() {}

    public static FloorPolicy policy() {
        return new FloorPolicy(
                Map.of(
                        LifestyleTier.HOMEBODY, sharedAcrossCategories("25"),
                        LifestyleTier.OCCASIONAL, sharedAcrossCategories("35"),
                        LifestyleTier.REGULAR, sharedAcrossCategories("45"),
                        LifestyleTier.FREQUENT, sharedAcrossCategories("55")),
                List.of(
                        new ObligationBand(Rate.ofPercent("40"), Rate.ofPercent("115")),
                        new ObligationBand(Rate.ofPercent("55"), Rate.ofPercent("100")),
                        new ObligationBand(Rate.ofPercent("70"), Rate.ofPercent("85")),
                        ObligationBand.above(Rate.ofPercent("70"))),
                Map.of(
                        SpendCategory.ENTERTAINMENT, Rate.ofPercent("4.6"),
                        SpendCategory.DINING_OUT, Rate.ofPercent("5"),
                        SpendCategory.CLOTHING, Rate.ofPercent("2.5")),
                Rate.ofPercent("3"),
                Rate.ofPercent("12"));
    }

    /** What a Beirut user's own city figures would supply in place of the national fallback. */
    public static Map<SpendCategory, Money> beirutBaselines() {
        return Map.of(
                SpendCategory.ENTERTAINMENT, Money.of(160),
                SpendCategory.DINING_OUT, Money.of(220),
                SpendCategory.CLOTHING, Money.of(90));
    }

    private static Map<SpendCategory, Rate> sharedAcrossCategories(String percent) {
        Rate share = Rate.ofPercent(percent);
        return Map.of(
                SpendCategory.ENTERTAINMENT, share,
                SpendCategory.DINING_OUT, share,
                SpendCategory.CLOTHING, share);
    }
}
