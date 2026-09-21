package com.hasan.budget.costofliving.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hasan.budget.shared.CountryCode;
import com.hasan.budget.shared.MetroId;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two gates a shared figure has to pass, checked on the record itself.
 *
 * <p>They live on {@link Contribution} rather than in the service so that no future caller can reach
 * publication by another route. An approval screen is exactly the place where a tired person waves
 * something through, so the refusal has to be somewhere a second code path cannot get around.
 */
class ContributionTest {

    private static final MetroId BEIRUT = new MetroId("beirut");
    private static final LocalDate SUBMITTED = LocalDate.parse("2026-09-20");

    private Contribution contribution(MetroId metro, String cityLabel, ContributionState state) {
        return new Contribution(
                1L,
                "user-a",
                CountryCode.LEBANON,
                metro,
                cityLabel,
                SpendCategory.GROCERIES,
                Money.of("270.00"),
                SUBMITTED,
                state,
                Corroboration.CORROBORATED,
                "");
    }

    @Test
    @DisplayName("a figure that passed validation and names a listed city can be published")
    void approvingAQueuedContributionPublishesIt() {
        Contribution published =
                contribution(BEIRUT, "Beirut", ContributionState.QUEUED).approved();

        assertThat(published.state()).isEqualTo(ContributionState.PUBLISHED);
        assertThat(published.amount()).isEqualTo(Money.of("270.00"));
    }

    @Test
    @DisplayName("approval cannot be used to get around validation")
    void aRejectedContributionCannotBeApproved() {
        Contribution rejected = contribution(BEIRUT, "Beirut", ContributionState.REJECTED);

        assertThatThrownBy(rejected::approved)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("passed validation");
    }

    @Test
    @DisplayName("a figure still awaiting validation cannot be approved either")
    void aCandidateCannotSkipStraightToPublished() {
        Contribution candidate = contribution(BEIRUT, "Beirut", ContributionState.CANDIDATE);

        assertThatThrownBy(candidate::approved).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a figure submitted for a typed city name can never become a city default")
    void aTypedCityNameCannotBePublishedAsACityDefault() {
        // Publishing this would mean guessing which metro "Bcharre" meant. A wrong guess is
        // invisible once it is data: nothing fails, and every plan for that city is quietly built
        // on somebody else's prices. Their own figure still applies to them - it is theirs.
        Contribution typed = contribution(null, "Bcharre", ContributionState.QUEUED);

        assertThatThrownBy(typed::approved)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Bcharre");
    }

    @Test
    @DisplayName("a contribution must name a place of one kind or the other")
    void aContributionWithNoPlaceAtAllIsRefused() {
        assertThatThrownBy(() -> contribution(null, "  ", ContributionState.CANDIDATE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
