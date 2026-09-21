package com.hasan.budget.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

import com.hasan.budget.costofliving.domain.Confidence;
import com.hasan.budget.profile.application.TaxRateResolver;
import com.hasan.budget.profile.domain.BankAccountBalance;
import com.hasan.budget.profile.domain.BankConsideredFunds;
import com.hasan.budget.profile.domain.ConsiderationMode;
import com.hasan.budget.profile.domain.ConsideredFunds;
import com.hasan.budget.profile.domain.Deposit;
import com.hasan.budget.profile.domain.LifestyleTier;
import com.hasan.budget.profile.domain.ManualConsideredFunds;
import com.hasan.budget.profile.domain.Rate;
import com.hasan.budget.profile.domain.ResolvedTaxRate;
import com.hasan.budget.profile.domain.TaxReserve;
import com.hasan.budget.profile.domain.TaxTreatment;
import com.hasan.budget.profile.domain.UserProfile;
import com.hasan.budget.profile.port.TaxRateLayer;
import com.hasan.budget.shared.CountryCode;
import com.hasan.budget.shared.IncomeQuintile;
import com.hasan.budget.shared.MetroId;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.Rigidity;
import com.hasan.budget.shared.SpendCategory;
import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Executable specification for the profile, the considered balance, tax and authorization
 * (Stages 4 and 5).
 */
class ProfileAndFundsSpecTest {

    private static final LocalDate SEEDED_ON = LocalDate.of(2025, 1, 1);
    private static final LocalDate ANSWERED_ON = LocalDate.of(2026, 3, 14);

    @Nested
    @DisplayName("how much money the app is allowed to consider")
    class ConsideredBalance {

        /**
         * Path A, percentage. $150,000 in the account, the user says 80% ⇒ the app plans against
         * $120,000 and behaves as though the other $30,000 does not exist.
         */
        @Test
        void aPercentageOfTheBalanceLimitsWhatThePlanSees() {
            ConsideredFunds funds = BankConsideredFunds.share(
                            List.of(BankAccountBalance.visible("chk", "Current account", Money.of(150_000))),
                            List.of(Deposit.of("payroll", "chk", Money.of(4_000))),
                            Rate.ofPercent("80"))
                    .resolve();

            assertThat(funds.consideredBalance()).isEqualTo(Money.of(120_000));
            assertThat(funds.mode()).isEqualTo(ConsiderationMode.PERCENTAGE);
            // The other fifth is not lost, not hidden, and not planned with.
            assertThat(funds.setAside()).isEqualTo(Money.of(30_000));
        }

        /** Path A, absolute. The user names $50,000 of a $150,000 balance ⇒ the plan sees $50,000. */
        @Test
        void anAbsoluteFigureLimitsWhatThePlanSees() {
            BankConsideredFunds source = BankConsideredFunds.upTo(
                    List.of(BankAccountBalance.visible("chk", "Current account", Money.of(150_000))),
                    List.of(),
                    Money.of(50_000));

            assertThat(source.resolve().consideredBalance()).isEqualTo(Money.of(50_000));
            assertThat(source.resolve().setAside()).isEqualTo(Money.of(100_000));
            assertThat(source.clampedToAvailableBalance()).isFalse();
        }

        /**
         * Naming a figure above the real balance must clamp to the balance and say so, rather than
         * planning against money that is not there.
         */
        @Test
        void anAbsoluteFigureAboveTheRealBalanceIsClampedAndReported() {
            BankConsideredFunds source = BankConsideredFunds.upTo(
                    List.of(BankAccountBalance.visible("chk", "Current account", Money.of(150_000))),
                    List.of(),
                    Money.of(200_000));

            assertThat(source.resolve().consideredBalance()).isEqualTo(Money.of(150_000));
            assertThat(source.clampedToAvailableBalance())
                    .describedAs("the user must be told the plan is built on $150,000, not their $200,000")
                    .isTrue();
            // Nothing was withheld; there was simply less money than they named.
            assertThat(source.resolve().setAside()).isEqualTo(Money.ZERO);
        }

        /**
         * Path B. A user who types their money in has already chosen what is in scope, so asking
         * for a percentage of it would be incoherent. The figure is taken whole and no share
         * question is asked.
         */
        @Test
        void manualEntryIsTakenWholeWithNoShareQuestion() {
            ConsideredFunds funds =
                    new ManualConsideredFunds(Money.of(12_000), Money.of(4_000)).resolve();

            assertThat(funds.consideredBalance()).isEqualTo(Money.of(12_000));
            assertThat(funds.setAside()).isEqualTo(Money.ZERO);
            assertThat(funds.mode()).isEqualTo(ConsiderationMode.WHOLE);
            assertThat(funds.mode().asksWhatShareIsInScope()).isFalse();
            // The question exists; it just belongs to the path where the app can see more than the
            // user ever chose to show it.
            assertThat(ConsiderationMode.PERCENTAGE.asksWhatShareIsInScope()).isTrue();
            assertThat(ConsiderationMode.ABSOLUTE.asksWhatShareIsInScope()).isTrue();
        }

        /** A Path B user who wants more in scope simply raises the total; no provenance is required. */
        @Test
        void aManualTotalCanBeRaisedWithoutExplainingWhereTheMoneyCameFrom() {
            ManualConsideredFunds before = new ManualConsideredFunds(Money.of(12_000), Money.of(4_000));

            ConsideredFunds after = before.revisedTo(Money.of(20_000)).resolve();

            assertThat(after.consideredBalance()).isEqualTo(Money.of(20_000));
            assertThat(after.mode()).isEqualTo(ConsiderationMode.WHOLE);
            assertThat(after.monthlyIncome()).isEqualTo(Money.of(4_000));
            // And back out again. Taking money off the table is the same action as putting it on,
            // or a user who changes their mind has to start over to get their own money back.
            assertThat(before.revisedTo(Money.of(5_000)).resolve().consideredBalance())
                    .isEqualTo(Money.of(5_000));
            // Structural, because the requirement is about what we do NOT ask for: a manual total
            // carries the figure and the income, and nowhere to record where the money came from.
            assertThat(Arrays.stream(ManualConsideredFunds.class.getRecordComponents())
                            .map(RecordComponent::getName))
                    .containsExactly("statedTotal", "monthlyIncome");
        }
    }

    @Nested
    @DisplayName("money the user wants left alone")
    class RingFencing {

        /** An excluded account contributes nothing to the considered balance or to any category. */
        @Test
        void anExcludedAccountIsInvisibleToEveryCalculation() {
            BankConsideredFunds source = BankConsideredFunds.share(
                    List.of(
                            BankAccountBalance.visible("chk", "Current account", Money.of(100_000)),
                            BankAccountBalance.excluded("joint", "Joint account", Money.of(20_000))),
                    List.of(
                            Deposit.of("payroll", "chk", Money.of(4_000)),
                            Deposit.of("rent-share", "joint", Money.of(900))),
                    Rate.ofPercent("100"));

            assertThat(source.consideredBalance()).isEqualTo(Money.of(100_000));
            assertThat(source.monthlyIncome())
                    .describedAs("a deposit into an excluded account is not income either")
                    .isEqualTo(Money.of(4_000));
            assertThat(source.setAsideBreakdown().excludedAccounts()).isEqualTo(Money.of(20_000));
        }

        /** The $7,000 gift case: one flagged deposit never reaches income or surplus. */
        @Test
        void aFlaggedDepositNeverReachesIncomeOrSurplus() {
            BankConsideredFunds source = BankConsideredFunds.share(
                    List.of(BankAccountBalance.visible("chk", "Current account", Money.of(100_000))),
                    List.of(
                            Deposit.of("payroll", "chk", Money.of(4_000)),
                            Deposit.ringFenced("from-family", "chk", Money.of(7_000))),
                    Rate.ofPercent("100"));

            assertThat(source.monthlyIncome())
                    .describedAs("counting the gift as income would inflate every month's surplus")
                    .isEqualTo(Money.of(4_000));
            assertThat(source.consideredBalance()).isEqualTo(Money.of(93_000));
            assertThat(source.setAsideBreakdown().flaggedDeposits()).isEqualTo(Money.of(7_000));
        }

        /**
         * Exclusions, flagged deposits and the withheld percentage are three routes to the same
         * "not in scope" total, and it is shown explicitly. Money the user cannot see is money they
         * stop trusting the app about.
         */
        @Test
        void everythingSetAsideIsReportedAsOneVisibleTotal() {
            BankConsideredFunds source = BankConsideredFunds.share(
                    List.of(
                            BankAccountBalance.visible("chk", "Current account", Money.of(100_000)),
                            BankAccountBalance.excluded("joint", "Joint account", Money.of(20_000))),
                    List.of(Deposit.ringFenced("from-family", "chk", Money.of(7_000))),
                    Rate.ofPercent("80"));

            assertThat(source.setAsideBreakdown().excludedAccounts()).isEqualTo(Money.of(20_000));
            assertThat(source.setAsideBreakdown().flaggedDeposits()).isEqualTo(Money.of(7_000));
            assertThat(source.setAsideBreakdown().withheldShare()).isEqualTo(Money.of(18_600));
            assertThat(source.resolve().setAside()).isEqualTo(Money.of(45_600));

            // The identity that makes it honest: every dollar the bank shows is either planned with
            // or accounted for. A route that quietly dropped money would break this.
            assertThat(source.consideredBalance().plus(source.resolve().setAside()))
                    .isEqualTo(source.visibleBalance());
        }
    }

    @Nested
    @DisplayName("tax")
    class Tax {

        /**
         * The double-subtraction guard. Payroll deposits are already net of tax, so a user who says
         * their income arrives taxed gets no tax line at all. Applying a rate on top would quietly
         * remove another 20% of money they actually have.
         */
        @Test
        void alreadyTaxedIncomeGetsNoTaxLine() {
            TaxTreatment treatment = resolver().treatmentFor(employedInLebanon()).orElseThrow();

            assertThat(treatment.monthlyReserve(Money.of(4_000)))
                    .describedAs("no tax line at all, rather than a zero one someone later fills in")
                    .isEmpty();
            // The rate is still resolved; it explains the gap rather than widening it.
            assertThat(treatment.impliedGrossIncome(Money.of(4_000))).isEqualTo(Money.of("4705.88"));
        }

        /** A freelancer whose income arrives untaxed gets a TAX_RESERVE funded at the resolved rate. */
        @Test
        void untaxedIncomeGetsALockedTaxReserve() {
            TaxTreatment treatment = resolver().treatmentFor(freelancingInLebanon()).orElseThrow();

            TaxReserve reserve = treatment.monthlyReserve(Money.of(4_000)).orElseThrow();

            assertThat(reserve.monthlyAmount()).isEqualTo(Money.of(600));
            assertThat(reserve.category()).isEqualTo(SpendCategory.TAX_RESERVE);
            assertThat(reserve.rigidity())
                    .describedAs("a tax reserve is not something to offer to cut")
                    .isEqualTo(Rigidity.LOCKED);
        }

        /** The rate resolves through the same precedence chain as baselines: the user's own wins. */
        @Test
        void aTypedTaxRateBeatsTheSeededCountryRate() {
            ResolvedTaxRate resolved =
                    resolver().resolve("the-freelancer", CountryCode.LEBANON).orElseThrow();

            assertThat(resolved.rate()).isEqualTo(Rate.ofPercent("22"));
            assertThat(resolved.confidence()).isEqualTo(Confidence.USER_PROVIDED);
        }

        /** A typed rate stays private; every other account keeps the seeded figure. */
        @Test
        void aTypedTaxRateDoesNotLeakToOtherAccounts() {
            ResolvedTaxRate someoneElse =
                    resolver().resolve("someone-else", CountryCode.LEBANON).orElseThrow();

            assertThat(someoneElse.rate()).isEqualTo(Rate.ofPercent("15"));
            assertThat(someoneElse.confidence()).isEqualTo(Confidence.ESTIMATED);
        }

        /**
         * Real systems are progressive with brackets and allowances. A single percentage is an
         * approximation and must be labelled ESTIMATED, never presented as a statutory truth.
         */
        @Test
        void theStoredRateIsLabelledAsAnEstimate() {
            ResolvedTaxRate seeded =
                    resolver().resolve("someone-else", CountryCode.LEBANON).orElseThrow();

            assertThat(seeded.confidence()).isEqualTo(Confidence.ESTIMATED);
            assertThat(seeded.sourceName()).isNotBlank();
            assertThat(seeded.asOf()).isEqualTo(SEEDED_ON);

            // Not merely convention: an effective rate cannot be dressed up as a statistic at all.
            assertThatThrownBy(() -> new ResolvedTaxRate(
                            Rate.ofPercent("15"), Confidence.OFFICIAL, "a statute", SEEDED_ON))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("never OFFICIAL");
        }
    }

    @Nested
    @Disabled("Stage 5: authorization belongs to packet P6")
    @DisplayName("authorization")
    class Authorization {

        /**
         * The single most valuable test in the API surface. Bank data is the most sensitive thing
         * here, and one user reading another's plan is the failure that ends the product.
         */
        @Test
        void oneUserCannotReadAnotherUsersPlan() {
            fail("not implemented");
        }

        /** Nor their transactions, their overrides, their goals, or their connected accounts. */
        @Test
        void everyQueryIsScopedToTheAuthenticatedUser() {
            fail("not implemented");
        }

        /**
         * budget-core trusts the user-id header the gateway injects, which is safe only while
         * budget-core has no published port. If that isolation is ever relaxed the header becomes
         * forgeable, so the topology itself is under test.
         */
        @Test
        void budgetCoreIsUnreachableFromOutsideTheComposeNetwork() {
            fail("not implemented");
        }

        /** A request with no valid Supabase JWT is rejected at the gateway, not deeper in. */
        @Test
        void anUnauthenticatedRequestNeverReachesTheDomain() {
            fail("not implemented");
        }
    }

    @Nested
    @Disabled("Stage 5: plan history belongs to packet P6")
    @DisplayName("plan history")
    class History {

        /** Snapshots are append-only, so adding a goal never rewrites what the plan said before. */
        @Test
        void addingAGoalLeavesEarlierSnapshotsUntouched() {
            fail("not implemented");
        }

        /** The user can see exactly what changed at the moment a goal was added. */
        @Test
        void theChangeCausedByAddingAGoalIsVisibleAfterTheFact() {
            fail("not implemented");
        }
    }

    /**
     * The chain as production wires it: the user's own figure first, then the seeded country rate.
     * Both layers are stated in full here rather than read from a database, because precedence is
     * the behaviour under test and a database would only obscure it.
     */
    private static TaxRateResolver resolver() {
        TaxRateLayer typedByTheUser = (userId, country) -> "the-freelancer".equals(userId)
                ? Optional.of(ResolvedTaxRate.statedByUser(Rate.ofPercent("22"), ANSWERED_ON))
                : Optional.empty();
        TaxRateLayer seededForTheCountry = (userId, country) -> CountryCode.LEBANON.equals(country)
                ? Optional.of(ResolvedTaxRate.estimatedForCountry(
                        Rate.ofPercent("15"),
                        "Lebanon Law 144/2019 non-salaried schedule (4%-25%), mid-band estimate",
                        SEEDED_ON))
                : Optional.empty();
        return new TaxRateResolver(List.of(typedByTheUser, seededForTheCountry));
    }

    private static UserProfile employedInLebanon() {
        return profile("the-employee", true);
    }

    private static UserProfile freelancingInLebanon() {
        return profile("the-consultant", false);
    }

    private static UserProfile profile(String userId, boolean incomeArrivesTaxed) {
        return new UserProfile(
                userId,
                CountryCode.LEBANON,
                new MetroId("beirut"),
                null,
                LifestyleTier.REGULAR,
                IncomeQuintile.Q3,
                incomeArrivesTaxed);
    }
}
