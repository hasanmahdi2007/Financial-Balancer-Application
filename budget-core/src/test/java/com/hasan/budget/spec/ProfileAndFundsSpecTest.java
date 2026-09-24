package com.hasan.budget.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hasan.budget.costofliving.domain.Confidence;
import com.hasan.budget.planning.application.GoalDraft;
import com.hasan.budget.planning.application.PlanHistory;
import com.hasan.budget.planning.application.PlanService;
import com.hasan.budget.planning.application.PlanView;
import com.hasan.budget.planning.application.PlanningFixture;
import com.hasan.budget.planning.application.NotFoundException;
import com.hasan.budget.planning.application.StatedMoney;
import com.hasan.budget.planning.domain.Priority;
import com.hasan.budget.planning.domain.surplus.UserLineItem;
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
import com.hasan.budget.web.CurrentUser;
import com.hasan.budget.web.CurrentUserArgumentResolver;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.ServletWebRequest;
import org.yaml.snakeyaml.Yaml;

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
    @DisplayName("authorization")
    class Authorization {

        private final PlanningFixture fixture = new PlanningFixture(ANSWERED_ON);
        private final PlanService plans = fixture.plans();

        /**
         * The single most valuable test in the API surface. Bank data is the most sensitive thing
         * here, and one user reading another's plan is the failure that ends the product.
         *
         * <p>Note what it asserts about the failure: the answer to "show me that plan" is the same
         * whether the plan belongs to someone else or never existed. Anything else would let one
         * account confirm the existence of another's data by the shape of the refusal.
         */
        @Test
        void oneUserCannotReadAnotherUsersPlan() {
            fixture.onboard("user-a", Money.of(4000));
            fixture.onboard("user-b", Money.of(4000));
            plans.addGoal("user-a", new PlanService.GoalChange(
                    "Car", Money.of(6000), LocalDate.of(2027, 3, 1), Priority.HIGH));
            PlanView theirs = plans.plan("user-a");

            assertThat(plans.latest("user-b")).isEmpty();
            assertThat(plans.history("user-b")).isEmpty();
            assertThatThrownBy(() -> plans.snapshot("user-b", theirs.id()))
                    .isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> plans.snapshot("user-b", "a-plan-that-never-existed"))
                    .as("a plan of someone else's and a plan that never was must be indistinguishable")
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("no plan");
        }

        /** Nor their goals, their spending, their named items, their figures or their profile. */
        @Test
        void everyQueryIsScopedToTheAuthenticatedUser() {
            fixture.onboard("user-a", Money.of(4000));
            GoalDraft theirGoal = plans.addGoal("user-a", new PlanService.GoalChange(
                            "Car", Money.of(6000), LocalDate.of(2027, 3, 1), Priority.HIGH))
                    .goal();
            plans.saveLineItem("user-a", UserLineItem.onTopOf(
                    "gym", "Gym membership", SpendCategory.SUBSCRIPTIONS, Money.of(40)));

            assertThat(plans.profile("user-b")).isEmpty();
            assertThat(plans.money("user-b")).isEmpty();
            assertThat(plans.goals("user-b")).isEmpty();
            assertThat(plans.spending("user-b")).isEmpty();
            assertThat(plans.lineItems("user-b")).isEmpty();

            // Nor may they reach one by its id, which is the only handle they could have got hold of.
            assertThatThrownBy(() -> plans.updateGoal("user-b", theirGoal.id(), new PlanService.GoalChange(
                            "Mine now", Money.of(10), LocalDate.of(2027, 3, 1), Priority.LOW)))
                    .isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> plans.removeGoal("user-b", theirGoal.id()))
                    .isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> plans.finishFirst("user-b", Optional.of(theirGoal.id())))
                    .isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> plans.deleteLineItem("user-b", "gym"))
                    .isInstanceOf(NotFoundException.class);

            // And none of it moved: a refused request must not half-happen.
            assertThat(plans.goals("user-a")).containsExactly(theirGoal);
            assertThat(plans.lineItems("user-a")).hasSize(1);
        }

        /**
         * budget-core trusts the user-id header the gateway injects, which is safe only while
         * budget-core has no published port. If that isolation is ever relaxed the header becomes
         * forgeable, so the topology itself is under test.
         */
        @Test
        @SuppressWarnings("unchecked")
        void budgetCoreIsUnreachableFromOutsideTheComposeNetwork() throws Exception {
            Path composeFile = Path.of("..", "docker-compose.yml");
            String compose = Files.readString(composeFile);
            Map<String, Object> services = (Map<String, Object>) ((Map<String, Object>)
                            new Yaml().load(compose))
                    .get("services");

            assertThat(services)
                    .as("the two application services must be in the compose file to be under test")
                    .containsKeys("budget-core", "api-gateway");

            Map<String, Object> budgetCore = (Map<String, Object>) services.get("budget-core");
            assertThat(budgetCore)
                    .as("publishing a port here would make the injected user-id header forgeable "
                            + "from outside, and one user could then read another's bank data")
                    .doesNotContainKey("ports");
            assertThat(budgetCore).doesNotContainKey("network_mode");

            List<String> publishing = services.entrySet().stream()
                    .filter(service -> ((Map<String, Object>) service.getValue()).containsKey("ports"))
                    .map(Map.Entry::getKey)
                    .toList();
            assertThat(publishing)
                    .as("only the gateway may be reachable from outside; the databases are bound to "
                            + "localhost for development and are not application services")
                    .containsExactlyInAnyOrder("api-gateway", "postgres", "redis");

            // The comment is load-bearing: this is exactly the line a later change deletes to make
            // local testing easier, and the test above is what it would silently disarm.
            assertThat(compose).containsIgnoringCase("must NOT publish a port");
        }

        /**
         * A request with no valid Supabase JWT is rejected at the gateway, which has its own tests.
         * What budget-core can promise is the half that is its own: with no user injected, nothing
         * user-scoped is served at all, and no endpoint takes the user's identity from the caller.
         *
         * <p>Both halves matter. The first is what makes a misconfigured gateway fail loudly rather
         * than quietly serving anonymous traffic. The second is the one a new endpoint could break by
         * accident: an id in a path or a query is chosen by whoever is calling.
         */
        @Test
        void anUnauthenticatedRequestNeverReachesTheDomain() {
            CurrentUserArgumentResolver resolver = new CurrentUserArgumentResolver();
            assertThatThrownBy(() -> resolver.resolveArgument(
                            null, null, new ServletWebRequest(new MockHttpServletRequest()), null))
                    .isInstanceOf(CurrentUserArgumentResolver.NotSignedInException.class);

            MockHttpServletRequest blank = new MockHttpServletRequest();
            blank.addHeader(CurrentUserArgumentResolver.USER_HEADER, "   ");
            assertThatThrownBy(() ->
                            resolver.resolveArgument(null, null, new ServletWebRequest(blank), null))
                    .isInstanceOf(CurrentUserArgumentResolver.NotSignedInException.class);

            for (Method handler : userScopedEndpoints()) {
                assertThat(Arrays.stream(handler.getParameters())
                                .anyMatch(parameter -> parameter.isAnnotationPresent(CurrentUser.class)))
                        .as("%s.%s serves user data, so it must take the injected user",
                                handler.getDeclaringClass().getSimpleName(), handler.getName())
                        .isTrue();
                assertThat(Arrays.stream(handler.getParameters())
                                .filter(parameter -> !parameter.isAnnotationPresent(CurrentUser.class))
                                .map(Parameter::getName))
                        .as("%s.%s must not let the caller name whose data it is",
                                handler.getDeclaringClass().getSimpleName(), handler.getName())
                        .doesNotContain("userId", "user", "accountId");
            }
        }

        /**
         * Every request-handling method of a controller under /api/v1, other than the two that serve
         * nobody's data in particular: the choices a client renders, which are the same for everyone.
         */
        private static List<Method> userScopedEndpoints() {
            ClassPathScanningCandidateComponentProvider scanner =
                    new ClassPathScanningCandidateComponentProvider(false);
            scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

            List<Method> handlers = new ArrayList<>();
            for (BeanDefinition candidate : scanner.findCandidateComponents("com.hasan.budget")) {
                Class<?> controller = ClassUtils.resolveClassName(candidate.getBeanClassName(), null);
                RequestMapping mapping = AnnotationUtils.findAnnotation(controller, RequestMapping.class);
                String path = mapping == null || mapping.value().length == 0 ? "" : mapping.value()[0];
                if (!path.startsWith("/api/v1") || path.startsWith("/api/v1/choices")) {
                    continue;
                }
                for (Method method : controller.getDeclaredMethods()) {
                    if (AnnotationUtils.findAnnotation(method, RequestMapping.class) != null) {
                        handlers.add(method);
                    }
                }
            }
            assertThat(handlers)
                    .as("the scan must actually find the endpoints, or this test proves nothing")
                    .hasSizeGreaterThan(10);
            return handlers;
        }
    }

    @Nested
    @DisplayName("plan history")
    class History {

        private final PlanningFixture fixture = new PlanningFixture(ANSWERED_ON);
        private final PlanService plans = fixture.plans();

        /** Snapshots are append-only, so adding a goal never rewrites what the plan said before. */
        @Test
        void addingAGoalLeavesEarlierSnapshotsUntouched() {
            fixture.onboard("saver", Money.of(3000));
            PlanView first = plans.plan("saver");

            plans.addGoal("saver", new PlanService.GoalChange(
                    "Car", Money.of(9000), LocalDate.of(2027, 3, 1), Priority.HIGH));
            plans.saveMoney("saver", new StatedMoney(Money.of(2000), Money.of(500)));
            plans.plan("saver");

            assertThat(plans.snapshot("saver", first.id()))
                    .as("what the user was told then is not rewritten by what they do later")
                    .isEqualTo(first);
            assertThat(plans.history("saver")).hasSize(3);
            assertThat(plans.history("saver").stream().map(PlanHistory.Entry::id))
                    .doesNotHaveDuplicates()
                    .contains(first.id());
        }

        /**
         * History is only a promise if the database outlives the containers. A volume Compose owns is
         * deleted by {@code docker compose down -v}, which is how every plan a user had was once lost
         * in one command. An external volume is one Compose never deletes, whatever flag it is given.
         */
        @Test
        @SuppressWarnings("unchecked")
        void theDatabaseOutlivesTheStackBeingTakenDown() throws Exception {
            Map<String, Object> compose = new Yaml().load(Files.readString(Path.of("..", "docker-compose.yml")));
            Map<String, Object> postgres = (Map<String, Object>)
                    ((Map<String, Object>) compose.get("services")).get("postgres");
            String mounted = ((List<String>) postgres.get("volumes")).stream()
                    .filter(volume -> volume.endsWith(":/var/lib/postgresql/data"))
                    .map(volume -> volume.substring(0, volume.indexOf(':')))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("postgres keeps its data in no named volume"));

            Map<String, Object> declared = (Map<String, Object>)
                    ((Map<String, Object>) compose.get("volumes")).get(mounted);
            assertThat(declared)
                    .as("a volume Compose owns is deleted by `down -v`, and every plan with it")
                    .containsEntry("external", true);
        }

        /** The user can see exactly what changed at the moment a goal was added. */
        @Test
        void theChangeCausedByAddingAGoalIsVisibleAfterTheFact() {
            fixture.onboard("saver", Money.of(0));
            GoalDraft car = plans.addGoal("saver", new PlanService.GoalChange(
                            "Car", Money.of(9000), LocalDate.of(2027, 3, 1), Priority.MEDIUM))
                    .goal();
            plans.addGoal("saver", new PlanService.GoalChange(
                    "Emergency fund", Money.of(6000), LocalDate.of(2027, 3, 1), Priority.CRITICAL));

            PlanHistory.Entry latest = plans.history("saver").getFirst();
            assertThat(latest.reason()).isEqualTo("You added a goal: Emergency fund");
            assertThat(latest.changes().goalsAdded()).containsExactly("Emergency fund");
            assertThat(latest.changes().goalsRemoved()).isEmpty();

            PlanHistory.GoalChange carChanged = latest.changes().goals().stream()
                    .filter(changed -> changed.id().equals(car.id()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("adding a goal ahead of the car must show on the car"));
            assertThat(carChanged.before().status()).isEqualTo("On track");
            assertThat(carChanged.after().status()).isEqualTo("Behind");
            assertThat(new BigDecimal(carChanged.after().monthlyFunded()))
                    .as("the new goal took money the car was getting")
                    .isLessThan(new BigDecimal(carChanged.before().monthlyFunded()));

            // The first plan of all has nothing before it, and says so rather than inventing a change.
            assertThat(plans.history("saver").getLast().changes()).isNull();
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
