package com.hasan.budget.profile.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.hasan.budget.costofliving.domain.Confidence;
import com.hasan.budget.profile.application.DiscretionaryFloorService;
import com.hasan.budget.profile.application.ProfileConfiguration;
import com.hasan.budget.profile.application.TaxRateResolver;
import com.hasan.budget.profile.domain.FloorRequest;
import com.hasan.budget.profile.domain.LifestyleTier;
import com.hasan.budget.profile.domain.Rate;
import com.hasan.budget.profile.domain.ResolvedTaxRate;
import com.hasan.budget.profile.domain.SeededFloorPolicy;
import com.hasan.budget.shared.CountryCode;
import com.hasan.budget.shared.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs this packet's migrations against a real Postgres and reads back what they seeded.
 *
 * <p>The migrations are the only place the discretionary floor policy and the country tax rates
 * exist, so an unapplied or mis-seeded one is not a cosmetic failure: it is a floor of zero and a
 * tax reserve funded at nothing. Nothing else in the suite would notice.
 *
 * <p>Its own container rather than the shared development stack, because four packets are being
 * built on this machine at once and a test that depends on whichever database happens to be running
 * is a test that fails for reasons unrelated to the code.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({
    JdbcFloorPolicySource.class,
    JdbcCountryTaxRateLayer.class,
    JdbcUserTaxRateLayer.class,
    ProfileConfiguration.class
})
@Testcontainers
class ProfilePolicyIT {

    // Pinned to the image the compose stack ships, so a difference between this test and production
    // can never be a difference in the database.
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:15-alpine");

    @Autowired
    private JdbcFloorPolicySource floorPolicy;

    @Autowired
    private JdbcUserTaxRateLayer typedByTheUser;

    @Autowired
    private JdbcCountryTaxRateLayer seededForTheCountry;

    @Autowired
    private DiscretionaryFloorService floorService;

    @Autowired
    private TaxRateResolver wiredResolver;

    /**
     * The one assertion that stops the seeded tables and the policy the unit tests exercise from
     * drifting apart. Everything about the floor is proven in milliseconds against the fixture;
     * this is what makes that proof also true of the running system.
     */
    @Test
    void theSeededTablesProduceExactlyThePolicyTheFloorTestsUse() {
        assertThat(floorPolicy.load()).isEqualTo(SeededFloorPolicy.policy());
    }

    /** Both countries the product covers have a rate, and neither claims to be a statistic. */
    @Test
    void everySeededCountryRateIsLabelledAsAnEstimate() {
        for (CountryCode country : List.of(CountryCode.LEBANON, CountryCode.US)) {
            ResolvedTaxRate rate =
                    seededForTheCountry.rateFor("anyone", country).orElseThrow();

            assertThat(rate.confidence()).isEqualTo(Confidence.ESTIMATED);
            assertThat(rate.sourceName()).isNotBlank();
            assertThat(rate.rate().basisPoints()).isPositive();
        }
    }

    /**
     * Empty rather than zero. A fabricated rate would fund a tax reserve out of money with no bill
     * behind it; empty is the signal to ask the user instead.
     */
    @Test
    void aCountryWeHoldNoRateForAnswersNothingRatherThanZero() {
        assertThat(seededForTheCountry.rateFor("anyone", new CountryCode("FR"))).isEmpty();
    }

    /**
     * The module as Spring assembles it, which nothing else in the suite covers: the unit tests
     * construct these by hand, so a configuration that could not wire would fail for the first time
     * at boot. It also proves the whole path - seeded rows to a question a person can answer.
     */
    @Test
    void theFloorQuestionIsAssembledFromTheSeededRows() {
        var question = floorService.questionFor(
                FloorRequest.of(LifestyleTier.REGULAR, Money.of(4_000), Money.of(1_800))
                        .in("Beirut", Map.of()));

        assertThat(question.suggested()).isEqualTo(Money.of("217.80"));
        assertThat(question.basis()).isEqualTo("typical for someone in Beirut who goes out regularly");
        assertThat(question.explainedCoverage()).hasSize(3);
    }

    /** The precedence the configuration declares is the precedence the wired resolver applies. */
    @Test
    void theWiredChainPutsTheUsersOwnFigureFirst() {
        typedByTheUser.record("the-wired-user", Rate.ofPercent("31"), LocalDate.of(2026, 3, 14));

        assertThat(wiredResolver.resolve("the-wired-user", CountryCode.LEBANON).orElseThrow().rate())
                .isEqualTo(Rate.ofPercent("31"));
        assertThat(wiredResolver.resolve("anyone-else", CountryCode.LEBANON).orElseThrow().confidence())
                .isEqualTo(Confidence.ESTIMATED);
    }

    /**
     * The chain end to end, against the real tables: one user's answer wins for them and changes
     * nothing for anybody else.
     */
    @Test
    void aTypedRateWinsForItsOwnerAndLeavesEveryoneElseOnTheSeededFigure() {
        typedByTheUser.record("the-freelancer", Rate.ofPercent("22"), LocalDate.of(2026, 3, 14));
        TaxRateResolver resolver = new TaxRateResolver(List.of(typedByTheUser, seededForTheCountry));

        ResolvedTaxRate theirs = resolver.resolve("the-freelancer", CountryCode.LEBANON).orElseThrow();
        ResolvedTaxRate everyoneElse = resolver.resolve("someone-else", CountryCode.LEBANON).orElseThrow();

        assertThat(theirs.rate()).isEqualTo(Rate.ofPercent("22"));
        assertThat(theirs.confidence()).isEqualTo(Confidence.USER_PROVIDED);
        assertThat(everyoneElse.confidence()).isEqualTo(Confidence.ESTIMATED);
        assertThat(everyoneElse.rate()).isNotEqualTo(theirs.rate());
    }
}
