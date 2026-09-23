package com.hasan.budget.planning.application;

import com.hasan.budget.profile.domain.LifestyleTier;
import com.hasan.budget.profile.domain.UserProfile;
import com.hasan.budget.shared.CountryCode;
import com.hasan.budget.shared.IncomeQuintile;
import com.hasan.budget.shared.MetroId;
import com.hasan.budget.shared.Money;
import java.util.Objects;
import java.util.Optional;

/**
 * What a plan needs to know about the person: where they live, how they live, and how their income
 * arrives.
 *
 * <p>Stored by the planning module because nothing else stores it yet; the profile module owns the
 * domain type and the policies, and {@link #asUserProfile()} is the bridge to them.
 *
 * @param city null when the user's city is not one we hold figures for
 * @param cityNotListed what they called their city in that case. Display only - it never selects a
 *     city, which is what keeps fuzzy matching out of the product.
 * @param lifestyle null until asked, which the floor treats as a real state rather than a gap
 * @param leastForEnjoyingLife the user's own answer to the floor question, or null until given
 */
public record PlanningProfile(
        String userId,
        CountryCode country,
        MetroId city,
        String cityNotListed,
        LifestyleTier lifestyle,
        boolean incomeArrivesTaxed,
        Money leastForEnjoyingLife) {

    /**
     * v1's curated figures carry no income quintile, so none is asked for and this stands in where the
     * profile type still requires one. It changes nothing any lookup returns today. It becomes a real
     * question only if the deferred official pipeline is revived, whose brief covers how to ask it.
     */
    static final IncomeQuintile UNUSED_BY_CURATED_FIGURES = IncomeQuintile.Q3;

    public PlanningProfile {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(country, "country");
        if (city == null && (cityNotListed == null || cityNotListed.isBlank())) {
            throw new IllegalArgumentException(
                    "choose a city from the list, or tell us what your city is called if it is not there");
        }
        if (city != null && cityNotListed != null) {
            throw new IllegalArgumentException(
                    "choose a city from the list, or name one that is not there - not both");
        }
        if (leastForEnjoyingLife != null && leastForEnjoyingLife.isNegative()) {
            throw new IllegalArgumentException("the least you want to spend cannot be below zero");
        }
    }

    public Optional<MetroId> listedCity() {
        return Optional.ofNullable(city);
    }

    public UserProfile asUserProfile() {
        return new UserProfile(
                userId, country, city, cityNotListed, lifestyle, UNUSED_BY_CURATED_FIGURES, incomeArrivesTaxed);
    }
}
