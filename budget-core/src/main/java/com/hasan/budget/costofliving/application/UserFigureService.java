package com.hasan.budget.costofliving.application;

import com.hasan.budget.costofliving.domain.BankEvidence;
import com.hasan.budget.costofliving.domain.Corroboration;
import com.hasan.budget.costofliving.domain.CountryProfile;
import com.hasan.budget.costofliving.domain.ResolvedBaseline;
import com.hasan.budget.costofliving.domain.Staleness;
import com.hasan.budget.costofliving.domain.StalenessPolicy;
import com.hasan.budget.costofliving.domain.UserOverride;
import com.hasan.budget.costofliving.port.CityDirectory;
import com.hasan.budget.costofliving.port.UserOverrideStore;
import com.hasan.budget.profile.domain.SpendingQuestion;
import com.hasan.budget.shared.CountryCode;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * What the user is asked about a figure, and what happens when they answer.
 *
 * <p>This is where the rule about never auto-inflating a stored figure is actually enforced. When a
 * number has drifted far enough to be worth questioning, the inflation-adjusted value is offered as
 * a pre-filled suggestion and nothing is written. Confirming it is what turns it into the user's own
 * figure. Quietly writing it back instead would leave a machine's guess wearing a curated badge -
 * more precise-looking than the original and no better grounded.
 */
public final class UserFigureService {

    private static final DateTimeFormatter MONTH_AND_YEAR =
            DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    /** How far a claim may sit from the user's own transactions and still count as corroborated. */
    private static final int CORROBORATION_TOLERANCE_PERCENT = 20;

    private final UserOverrideStore overrides;
    private final CityDirectory directory;
    private final Clock clock;

    public UserFigureService(UserOverrideStore overrides, CityDirectory directory, Clock clock) {
        this.overrides = Objects.requireNonNull(overrides, "overrides");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * The question to put to a user whose figure has aged, with the adjusted number pre-filled.
     *
     * <p>Empty when the figure is still fresh: asking somebody to re-confirm a number that has not
     * meaningfully moved trains them to click through without reading, which costs more than the
     * occasional stale figure it would catch.
     */
    public Optional<SpendingQuestion> confirmationFor(
            CountryCode country, SpendCategory category, ResolvedBaseline current) {
        if (current.staleness() == Staleness.FRESH) {
            return Optional.empty();
        }
        CountryProfile profile = directory
                .country(country)
                .orElseThrow(() -> new IllegalArgumentException(
                        "we hold no data for country " + country.value()));
        LocalDate today = LocalDate.now(clock);
        int inflation = profile.annualInflationBasisPoints();
        Money adjusted = StalenessPolicy.inflationAdjusted(current.amount(), current.asOf(), today, inflation);
        String drift = StalenessPolicy.driftPercent(current.asOf(), today, inflation).toPlainString();
        return Optional.of(new SpendingQuestion(
                "Is " + adjusted + " still about right for what you spend on "
                        + category.label().toLowerCase(Locale.ENGLISH) + " each month?",
                "We have not changed anything. We only suggest this because prices have moved since "
                        + "our figure was gathered, and a plan built on an out-of-date number quietly "
                        + "goes wrong.",
                List.of(category),
                adjusted,
                "Our figure of " + current.amount() + " was gathered "
                        + current.asOf().format(MONTH_AND_YEAR) + ", and prices in " + profile.name()
                        + " have risen roughly " + drift + "% since. This is that figure moved by the "
                        + "same amount - a suggestion, not a measurement."));
    }

    /**
     * Records what the user typed. It applies to their plan immediately and to nobody else's.
     *
     * <p>Corroboration is recorded rather than enforced. Their bank disagreeing with them does not
     * make their figure wrong - a rent paid in cash leaves no trace - and overruling a person about
     * their own rent is not a trade this product makes.
     */
    public UserOverride record(
            String userId, SpendCategory category, Money amount, Optional<BankEvidence> evidence) {
        UserOverride saved = new UserOverride(
                userId, category, amount, LocalDate.now(clock), corroborate(category, amount, evidence));
        overrides.save(saved);
        return saved;
    }

    /**
     * The one validator that works with a single user, and the strongest one available.
     *
     * <p>What makes a figure trustworthy is evidence or agreement. A crowd supplies agreement and
     * this product does not have one yet; a person's own transactions supply evidence, today, for
     * the first user who ever signs up.
     */
    public Corroboration corroborate(
            SpendCategory category, Money claim, Optional<BankEvidence> evidence) {
        return evidence
                .filter(e -> e.category() == category)
                .map(e -> withinTolerance(claim, e.observedMonthly())
                        ? Corroboration.CORROBORATED
                        : Corroboration.CONTRADICTED)
                .orElse(Corroboration.NOT_CHECKED);
    }

    private static boolean withinTolerance(Money claim, Money observed) {
        if (observed.isZero()) {
            return claim.isZero();
        }
        Money gap = claim.minus(observed);
        Money distance = gap.isNegative() ? Money.ZERO.minus(gap) : gap;
        return distance.times(100).compareTo(observed.times(CORROBORATION_TOLERANCE_PERCENT)) <= 0;
    }
}
