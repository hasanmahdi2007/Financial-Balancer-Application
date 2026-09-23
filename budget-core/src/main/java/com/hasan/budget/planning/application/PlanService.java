package com.hasan.budget.planning.application;

import com.hasan.budget.planning.domain.Priority;
import com.hasan.budget.planning.domain.surplus.UserLineItem;
import com.hasan.budget.profile.domain.LifestyleTier;
import com.hasan.budget.shared.CountryCode;
import com.hasan.budget.shared.MetroId;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Everything a signed-in user can do to their plan, each operation scoped to that user.
 *
 * <p><strong>Every public method takes the user id first, and every store call passes it on.</strong>
 * That is the whole of authorization inside this service: there is no method that finds a goal, an
 * item or a snapshot by its id alone, so one user's request cannot reach another's rows however its
 * ids were obtained. The user id itself comes from the gateway, which has already checked the token.
 *
 * <p>Plans are appended, never edited. Every recompute - a new goal, a changed balance, a plain
 * refresh - is a new snapshot with a reason attached, which is what lets history say what changed.
 */
public final class PlanService {

    private final PlanningProfileStore profiles;
    private final SpendingStore spending;
    private final GoalStore goals;
    private final PlanSnapshotStore snapshots;
    private final Places places;
    private final TaxReserves tax;
    private final PlanAssembler assembler;
    private final Clock clock;
    private final Supplier<String> ids;

    public PlanService(
            PlanningProfileStore profiles,
            SpendingStore spending,
            GoalStore goals,
            PlanSnapshotStore snapshots,
            Places places,
            TaxReserves tax,
            PlanAssembler assembler,
            Clock clock,
            Supplier<String> ids) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.spending = Objects.requireNonNull(spending, "spending");
        this.goals = Objects.requireNonNull(goals, "goals");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.places = Objects.requireNonNull(places, "places");
        this.tax = Objects.requireNonNull(tax, "tax");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    // --- profile and money -------------------------------------------------------------------------

    /**
     * @param city a listed city's id, or null when the user's city is not listed
     * @param cityNotListed what the user calls their city when it is not listed; display only
     */
    public record ProfileChange(
            CountryCode country,
            MetroId city,
            String cityNotListed,
            LifestyleTier lifestyle,
            boolean incomeArrivesTaxed,
            Money leastForEnjoyingLife) {}

    public PlanningProfile saveProfile(String userId, ProfileChange change) {
        if (places.countryName(change.country()).isEmpty()) {
            throw new IllegalArgumentException(
                    "We do not hold figures for " + change.country().value() + " yet. Choose a country from the list.");
        }
        if (change.city() != null && places.city(change.country(), change.city()).isEmpty()) {
            throw new IllegalArgumentException(
                    "That city is not in our list for " + change.country().value()
                            + ". Choose one from the list, or tell us what your city is called.");
        }
        String notListed = change.cityNotListed() == null || change.cityNotListed().isBlank()
                ? null
                : change.cityNotListed().strip();
        PlanningProfile profile = new PlanningProfile(
                userId,
                change.country(),
                change.city(),
                notListed,
                change.lifestyle(),
                change.incomeArrivesTaxed(),
                change.leastForEnjoyingLife());
        if (profile.city() == null) {
            places.recordCityNotListed(userId, profile.country(), profile.cityNotListed());
        }
        profiles.save(profile);
        return profile;
    }

    public Optional<PlanningProfile> profile(String userId) {
        return profiles.profile(userId);
    }

    public Optional<Places.City> cityOf(PlanningProfile profile) {
        return profile.listedCity().flatMap(city -> places.city(profile.country(), city));
    }

    public Optional<String> countryNameOf(PlanningProfile profile) {
        return places.countryName(profile.country());
    }

    public StatedMoney saveMoney(String userId, StatedMoney money) {
        profiles.saveMoney(userId, money);
        return money;
    }

    public Optional<StatedMoney> money(String userId) {
        return profiles.money(userId);
    }

    // --- spending and named items ------------------------------------------------------------------

    public Map<SpendCategory, Money> saveSpending(String userId, Map<SpendCategory, Money> stated) {
        if (stated.containsKey(SpendCategory.TAX_RESERVE)) {
            throw new IllegalArgumentException(
                    "Tax set aside is worked out from your tax rate, so it is not something to enter here.");
        }
        stated.forEach((category, amount) -> {
            if (amount.isNegative()) {
                throw new IllegalArgumentException(category.label() + " cannot be below zero.");
            }
        });
        spending.replaceSpending(userId, stated);
        return spending.spending(userId);
    }

    public Map<SpendCategory, Money> spending(String userId) {
        return spending.spending(userId);
    }

    public List<UserLineItem> lineItems(String userId) {
        return spending.lineItems(userId);
    }

    public UserLineItem saveLineItem(String userId, UserLineItem item) {
        if (item.parent() == SpendCategory.TAX_RESERVE) {
            throw new IllegalArgumentException(
                    "Tax set aside is worked out from your tax rate, so nothing can be named inside it.");
        }
        // A plan's lines are named by category key or by item id, in one flat space - which is what
        // lets advice say "your gym" and a rebalance name the line to raise. An item calling itself
        // "rent" would collide with the rent line, and moving money between two lines with one name
        // is not something either the user or the engine could resolve. Refused here, in words,
        // rather than surfacing later as a failure to rebalance at all.
        for (SpendCategory category : SpendCategory.values()) {
            if (Keys.of(category).equals(item.id())) {
                throw new IllegalArgumentException("\"" + item.id() + "\" is already the name of a kind of "
                        + "spending. Give this one a name of its own, such as \"my-" + item.id() + "\".");
            }
        }
        spending.saveLineItem(userId, item);
        return item;
    }

    public void deleteLineItem(String userId, String itemId) {
        if (!spending.deleteLineItem(userId, itemId)) {
            throw new NotFoundException("You have no named item called \"" + itemId + "\".");
        }
    }

    // --- goals -------------------------------------------------------------------------------------

    public record GoalChange(String name, Money target, LocalDate deadline, Priority priority) {}

    /**
     * @param plan the recomputed plan, or null when the plan cannot be made yet
     * @param waitingFor what the user still has to tell us before a plan can be made, when plan is null
     */
    public record GoalAndPlan(GoalDraft goal, PlanView plan, String waitingFor) {}

    public List<GoalDraft> goals(String userId) {
        return goals.goals(userId);
    }

    public Optional<String> finishFirst(String userId) {
        return goals.finishFirst(userId);
    }

    public GoalAndPlan addGoal(String userId, GoalChange change) {
        GoalDraft goal = new GoalDraft(ids.get(), change.name().strip(), change.target(), change.deadline(), change.priority());
        goals.save(userId, goal);
        return withPlan(userId, goal, "You added a goal: " + goal.name());
    }

    public GoalAndPlan updateGoal(String userId, String goalId, GoalChange change) {
        ownGoal(userId, goalId);
        GoalDraft goal = new GoalDraft(goalId, change.name().strip(), change.target(), change.deadline(), change.priority());
        goals.save(userId, goal);
        return withPlan(userId, goal, "You changed a goal: " + goal.name());
    }

    public Optional<PlanView> removeGoal(String userId, String goalId) {
        GoalDraft goal = ownGoal(userId, goalId);
        if (goals.finishFirst(userId).filter(goalId::equals).isPresent()) {
            goals.setFinishFirst(userId, Optional.empty());
        }
        goals.delete(userId, goalId);
        return tryPlan(userId, "You removed a goal: " + goal.name());
    }

    /** Empty goes back to handing out the balance in order of importance. */
    public Optional<PlanView> finishFirst(String userId, Optional<String> goalId) {
        Optional<GoalDraft> goal = goalId.map(id -> ownGoal(userId, id));
        goals.setFinishFirst(userId, goalId);
        return tryPlan(userId, goal
                .map(chosen -> "You chose " + chosen.name() + " to finish first")
                .orElse("You went back to finishing goals in order of importance"));
    }

    private GoalDraft ownGoal(String userId, String goalId) {
        return goals.goal(userId, goalId)
                .orElseThrow(() -> new NotFoundException("You have no goal with id \"" + goalId + "\"."));
    }

    private GoalAndPlan withPlan(String userId, GoalDraft goal, String reason) {
        try {
            return new GoalAndPlan(goal, recompute(userId, reason), null);
        } catch (NeedsMoreInformationException waiting) {
            return new GoalAndPlan(goal, null, waiting.getMessage());
        }
    }

    private Optional<PlanView> tryPlan(String userId, String reason) {
        try {
            return Optional.of(recompute(userId, reason));
        } catch (NeedsMoreInformationException waiting) {
            return Optional.empty();
        }
    }

    // --- plans -------------------------------------------------------------------------------------

    public PlanView plan(String userId) {
        return recompute(userId, "You asked for a fresh plan");
    }

    public Optional<PlanView> latest(String userId) {
        return snapshots.latest(userId);
    }

    public List<PlanHistory.Entry> history(String userId) {
        return PlanHistory.of(snapshots.all(userId));
    }

    public PlanView snapshot(String userId, String snapshotId) {
        return snapshots.find(userId, snapshotId)
                .orElseThrow(() -> new NotFoundException("You have no plan with id \"" + snapshotId + "\"."));
    }

    /** The plan as it would be now, without recording it. For questions and decisions, which do not snapshot. */
    public AssembledPlan assemble(String userId) {
        PlanningProfile profile = profiles.profile(userId).orElseThrow(() -> new NeedsMoreInformationException(
                "Tell us which country and city you live in first."));
        StatedMoney money = profiles.money(userId).orElseThrow(() -> new NeedsMoreInformationException(
                "Tell us how much comes in each month, and how much you already have, first."));
        if (!money.monthlyIncome().isPositive()) {
            throw new NeedsMoreInformationException(
                    "A plan is built from what comes in each month, so tell us your monthly income first.");
        }
        Map<SpendCategory, com.hasan.budget.costofliving.domain.ResolvedBaseline> baselines =
                places.baselinesFor(profile);
        checkNamedItemsFit(userId, baselines);
        return assembler.assemble(new PlanningInputs(
                money.asFunds().resolve(),
                baselines,
                spending.spending(userId),
                spending.lineItems(userId),
                // What the user already moves into savings each month, which is shown and never
                // subtracted. It is zero until bank data exists: it comes from classifying transfers,
                // which belongs to the ingestion module, and inventing a figure here would put a
                // number on the screen that nothing measured.
                Money.ZERO,
                tax.monthlyReserve(profile, money.monthlyIncome()),
                profile.lifestyle(),
                profile.leastForEnjoyingLife(),
                cityNameOf(profile),
                goals.goals(userId),
                goals.finishFirst(userId),
                LocalDate.now(clock)));
    }

    /**
     * An item the user named as part of a category has to fit inside what that category actually
     * costs them. Naming $45 of gym inside $40 of subscriptions is a real thing to get into - they
     * lowered one without the other - and the calculation rejects it, rightly, but in words written
     * for whoever is reading a stack trace. Caught here so the user is told which two figures
     * disagree, by the names they gave them, and which they might want to change.
     */
    private void checkNamedItemsFit(
            String userId, Map<SpendCategory, com.hasan.budget.costofliving.domain.ResolvedBaseline> baselines) {

        Map<SpendCategory, Money> stated = spending.spending(userId);
        Map<SpendCategory, Money> namedSoFar = new java.util.EnumMap<>(SpendCategory.class);
        for (UserLineItem item : spending.lineItems(userId)) {
            if (item.scope().isSubtractedInItsOwnRight()) {
                continue;
            }
            Money spentThere = stated.get(item.parent());
            if (spentThere == null && baselines.containsKey(item.parent())) {
                spentThere = baselines.get(item.parent()).amount();
            }
            String category = item.parent().label().toLowerCase(java.util.Locale.ENGLISH);
            if (spentThere == null) {
                throw new NeedsMoreInformationException("You said \"" + item.label() + "\" is part of what you "
                        + "spend on " + category + ", but you have not told us what you spend on " + category
                        + " yet.");
            }
            Money named = namedSoFar.merge(item.parent(), item.monthlyAmount(), Money::plus);
            if (named.compareTo(spentThere) > 0) {
                throw new NeedsMoreInformationException("What you have named inside " + category
                        + " comes to " + named + " a month, which is more than the " + spentThere
                        + " you said you spend there. Raise what you spend on " + category
                        + ", or lower what you named inside it.");
            }
        }
    }

    private PlanView recompute(String userId, String reason) {
        PlanView plan = PlanViews.from(assemble(userId), ids.get(), clock.instant(), reason);
        snapshots.append(userId, plan);
        return plan;
    }

    private String cityNameOf(PlanningProfile profile) {
        return cityOf(profile).map(Places.City::name).orElse(profile.cityNotListed());
    }
}
