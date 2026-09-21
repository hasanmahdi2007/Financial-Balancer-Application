package com.hasan.budget.profile.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Connecting a bank is the moment the app sees money the user never offered it, so the question
 * that follows is the one most likely to feel like an interrogation. It has to explain itself for
 * the same reason the floor question does.
 */
class ConsideredFundsQuestionTest {

    private static final List<BankAccountBalance> ACCOUNTS = List.of(
            BankAccountBalance.visible("chk", "Current account", Money.of(100_000)),
            BankAccountBalance.excluded("joint", "Joint account", Money.of(20_000)));

    /** What is being asked, why, and exactly what the app can see before it asks. */
    @Test
    void theShareQuestionSaysWhatItIsAskingWhyAndWhatWeCanSee() {
        ConsideredFundsQuestion question = ConsideredFundsQuestion.forAccounts(ACCOUNTS);

        assertThat(question.question()).isEqualTo("How much of this money should the plan work with?");
        assertThat(question.why())
                .describedAs("the reason has to be given, or the question reads as prying")
                .contains("may not be yours to spend");
        assertThat(question.visibleBalance()).isEqualTo(Money.of(120_000));
        assertThat(question.explainedCoverage())
                .containsExactly(
                        "Current account", "Joint account - you asked us to leave this one out");
    }

    /**
     * Both forms are offered, because "60%" and "$50,000" are the same decision expressed the two
     * ways people think about their own money, and the model accepts either.
     */
    @Test
    void theShareQuestionOffersBothWaysOfAnswering() {
        assertThat(ConsideredFundsQuestion.forAccounts(ACCOUNTS).why())
                .contains("a share of it or with an amount");
    }

    /**
     * An excluded account still appears. The point of the question is to show the user everything
     * the app can see before asking how much of it to use; hiding a row would make the total they
     * are being asked about disagree with their own bank.
     */
    @Test
    void anExcludedAccountIsStillShownInTheQuestionAndSaysWhy() {
        ConsideredFundsQuestion question = ConsideredFundsQuestion.forAccounts(ACCOUNTS);

        assertThat(question.accountsWeCanSee()).hasSize(2);
        assertThat(question.explainedCoverage().get(1))
                .describedAs("shown, and marked, rather than quietly dropped from the list")
                .contains("leave this one out");
        assertThat(question.visibleBalance())
                .describedAs("the figure the user can check against their own bank")
                .isEqualTo(Money.of(120_000));
    }

    /** Not every bank sends a name, and no screen should ever render "null" for an account. */
    @Test
    void anAccountWithNoNameFromTheBankStillReadsAsSomething() {
        ConsideredFundsQuestion question = ConsideredFundsQuestion.forAccounts(
                List.of(BankAccountBalance.visible("chk", null, Money.of(500))));

        assertThat(question.explainedCoverage()).containsExactly("Account");
    }

    @Test
    void thereIsNothingToAskAboutUntilAnAccountIsConnected() {
        assertThatThrownBy(() -> ConsideredFundsQuestion.forAccounts(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The structural half of "Path B never asks this". There is no manual equivalent: the question
     * is built from visible accounts, and a user who typed a figure has none.
     */
    @Test
    void thereIsNoWayToAskThisOnTheManualPath() {
        assertThat(ConsiderationMode.WHOLE.asksWhatShareIsInScope()).isFalse();
        assertThat(ConsiderationMode.PERCENTAGE.asksWhatShareIsInScope()).isTrue();
        assertThat(ConsiderationMode.ABSOLUTE.asksWhatShareIsInScope()).isTrue();
    }

    /** Same rule as every other question: no internal term, and no constant name. */
    @Test
    void theShareQuestionNeverShowsAnInternalTerm() {
        ConsideredFundsQuestion question = ConsideredFundsQuestion.forAccounts(ACCOUNTS);
        String rendered = question.question() + " " + question.why() + " "
                + String.join(" ", question.explainedCoverage());

        assertThat(rendered.toLowerCase())
                .doesNotContain("considered")
                .doesNotContain("ring-fence")
                .doesNotContain("set aside")
                .doesNotContain("baseline")
                .doesNotContain("discretionary");
        for (SpendCategory category : SpendCategory.values()) {
            assertThat(rendered).doesNotContain(category.name());
        }
        for (ConsiderationMode mode : ConsiderationMode.values()) {
            assertThat(rendered).doesNotContain(mode.name());
        }
    }
}
