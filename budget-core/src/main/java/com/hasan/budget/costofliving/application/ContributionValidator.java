package com.hasan.budget.costofliving.application;

import com.hasan.budget.costofliving.domain.BankEvidence;
import com.hasan.budget.costofliving.domain.Contribution;
import com.hasan.budget.costofliving.domain.ContributionState;
import com.hasan.budget.costofliving.domain.ContributionVerdict;
import com.hasan.budget.costofliving.domain.Corroboration;
import com.hasan.budget.costofliving.domain.PlausibleBand;
import com.hasan.budget.costofliving.port.CountryBaselineSource;
import com.hasan.budget.shared.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The battery a submitted figure has to survive before it can become anybody else's default.
 *
 * <p>A battery from the start, rather than a single check in an admin screen, because the ones that
 * need a crowd cannot run yet and the ones that do not can. Quorum and outlier rejection - require
 * <em>k</em> submissions, take the median, reject beyond three times the median absolute deviation -
 * are designed and not built, because they are meaningless at one user. Turning them on later is
 * adding a check to a list that already exists.
 *
 * <p>Automating this does not need a model and would be weaker with one. What makes a figure
 * trustworthy is evidence or agreement, and a model supplies neither: it cannot tell whether 400
 * dollars of rent in Zahle is real. Only a bank transaction or twenty other residents can.
 */
public final class ContributionValidator {

    private final CountryBaselineSource countryBaselines;
    private final UserFigureService userFigures;

    public ContributionValidator(
            CountryBaselineSource countryBaselines, UserFigureService userFigures) {
        this.countryBaselines = Objects.requireNonNull(countryBaselines, "countryBaselines");
        this.userFigures = Objects.requireNonNull(userFigures, "userFigures");
    }

    public ContributionVerdict validate(Contribution contribution, Optional<BankEvidence> evidence) {
        List<String> reasons = new ArrayList<>();
        Corroboration corroboration =
                userFigures.corroborate(contribution.category(), contribution.amount(), evidence);

        boolean plausible = checkRatioBand(contribution, reasons);
        if (corroboration == Corroboration.CORROBORATED) {
            reasons.add("Your own transactions agree with this figure.");
        } else if (corroboration == Corroboration.CONTRADICTED) {
            reasons.add("Your own transactions show a different amount for this, so we have kept this "
                    + "figure to your account rather than sharing it.");
        } else {
            reasons.add("We have nothing to check this against yet, so it stays your own figure until "
                    + "we do.");
        }

        boolean passed = plausible && corroboration == Corroboration.CORROBORATED;
        return new ContributionVerdict(
                passed ? ContributionState.QUEUED : ContributionState.REJECTED, corroboration, reasons);
    }

    /**
     * Catches the mistake that actually happens: a figure typed into the wrong box.
     *
     * <p>The band comes from the country estimate for that same category, so the check is per
     * category rather than a single tolerance applied to everything. Rent in one country spans
     * Wichita to San Francisco while a single person's grocery bill does not, and a tolerance loose
     * enough for the first is useless against the second.
     */
    private boolean checkRatioBand(Contribution contribution, List<String> reasons) {
        Optional<Money> estimate =
                countryBaselines.estimateAmount(contribution.country(), contribution.category());
        Optional<PlausibleBand> band =
                countryBaselines.plausibleBand(contribution.country(), contribution.category());
        if (estimate.isEmpty() || band.isEmpty()) {
            reasons.add("We have no typical figure for this in " + contribution.country().value()
                    + " to compare against.");
            return false;
        }
        if (band.get().accepts(contribution.amount(), estimate.get())) {
            return true;
        }
        reasons.add("At " + contribution.amount() + " this sits outside what we would expect for "
                + contribution.category().label().toLowerCase(Locale.ENGLISH)
                + " - somewhere between " + band.get().lowerBound(estimate.get()) + " and "
                + band.get().upperBound(estimate.get()) + " a month. Did it belong in another box?");
        return false;
    }
}
