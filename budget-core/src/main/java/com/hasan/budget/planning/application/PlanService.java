package com.hasan.budget.planning.application;

import com.hasan.budget.planning.domain.Priority;
import com.hasan.budget.planning.domain.surplus.UserLineItem;
import com.hasan.budget.profile.domain.LifestyleTier;
import com.hasan.budget.shared.CountryCode;
import com.hasan.budget.shared.MetroId;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
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
    private final BankSpending bank;
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
            BankSpending bank,
            Clock clock,
            Supplier<String> ids) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.spending = Objects.requireNonNull(spending, "spending");
        this.goals = Objects.requireNonNull(goals, "goals");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.places = Objects.requireNonNull(places, "places");
        this.tax = Objects.requireNonNull(tax, "tax");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.bank = Objects.requireNonNull(bank, "bank");
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

    /**
     * Saves how the user lives in the place their active plan is for.
     *
     * <p>The first place anyone tells us about becomes their first plan. After that, changing the place
     * of a plan that has already been made is a move, and is refused: re-pricing that plan with the new
     * place's figures is exactly the bug this guards against, because the old place's spending, goals
     * and history would all be carried into a place they were never about. A plan still being set up
     * has shown the user nothing yet, so changing its place there is only correcting an answer.
     */
    public PlanningProfile saveProfile(String userId, ProfileChange change) {
        PlanningProfile profile = profileFor(
                userId,
                change.country(),
                change.city(),
                change.cityNotListed(),
                change.lifestyle(),
                change.incomeArrivesTaxed(),
                change.leastForEnjoyingLife());
        Optional<PlanKey> active = profiles.active(userId);
        if (active.isEmpty()) {
            PlanKey first = new PlanKey(userId, ids.get());
            store(first, profile);
            profiles.activate(first);
            return profile;
        }
        PlanKey plan = active.get();
        Optional<PlanningProfile> current = profiles.profile(plan);
        if (current.isPresent() && !samePlace(current.get(), profile) && snapshots.latest(plan).isPresent()) {
            String was = placeOf(current.get()).label();
            throw new ChooseAPlanException("Your plan was made for " + was + ". To plan for "
                    + placeOf(profile).label() + ", pick up a plan you already have there or start a new "
                    + "one. Your plan for " + was + " stays exactly as it is.");
        }
        store(plan, profile);
        return profile;
    }

    public Optional<PlanningProfile> profile(String userId) {
        return profiles.active(userId).flatMap(profiles::profile);
    }

    // --- plans, one per place ----------------------------------------------------------------------

    /** What a plan said last, for choosing between plans. Amounts are figures; the client formats them. */
    public record Summary(String leftEachMonth, String leftEachMonthLabel, int goals, String goalsLabel) {}

    /** A button's words, and what pressing it does. */
    public record Action(String label, String meaning) {}

    /** @param summary null for a plan that was never made, because setup stopped before it */
    public record PlanChoice(
            String id, PlanView.Place place, boolean active, Instant lastUsedAt, Summary summary, Action use) {}

    public record StartNew(String label, String meaning, String bringGoalsLabel) {}

    public record PlanChoices(List<PlanChoice> plans, StartNew startNew) {}

    /**
     * @param city a listed city's id, or null when the user's city is not listed
     * @param bringGoals whether the goals of the plan in use now come along, under new ids. Nothing else
     *     is copied: spending belongs to the place it was spent in.
     */
    public record NewPlan(CountryCode country, MetroId city, String cityNotListed, boolean bringGoals) {}

    private static final StartNew START_NEW = new StartNew(
            "Start a new plan here",
            "A fresh plan for this place. What you spend starts from what is typical here, not from where "
                    + "you lived before. The money you have comes with you.",
            "Bring my goals with me");

    /** Every plan this user has, most recently used first; only those in one country when it is given. */
    public PlanChoices plans(String userId, Optional<CountryCode> country) {
        Optional<PlanKey> active = profiles.active(userId);
        List<PlanChoice> choices = profiles.plans(userId).stream()
                .filter(stored -> country.map(stored.profile().country()::equals).orElse(true))
                .map(stored -> choiceFor(stored, active.map(stored.key()::equals).orElse(false)))
                .toList();
        return new PlanChoices(choices, START_NEW);
    }

    /**
     * Picks up a plan the user already has. Its spending, goals and history come back exactly as they
     * were; the money they have now stays as it is, because that belongs to them and not to a place.
     * No snapshot is taken, so history gains nothing that says nothing: {@code GET /plan} then returns
     * the last one this plan made, unchanged.
     */
    public PlanChoice usePlan(String userId, String planId) {
        PlanKey plan = new PlanKey(userId, planId);
        if (profiles.profile(plan).isEmpty()) {
            throw new NotFoundException("You have no plan with id \"" + planId + "\".");
        }
        profiles.activate(plan);
        return chosen(plan);
    }

    /** Starts a plan for a new place and makes it the one in use. */
    public PlanChoice startPlan(String userId, NewPlan request) {
        Optional<PlanKey> previous = profiles.active(userId);
        PlanningProfile profile = profileFor(
                userId,
                request.country(),
                request.city(),
                request.cityNotListed(),
                null,
                previous.flatMap(profiles::profile).map(PlanningProfile::incomeArrivesTaxed).orElse(true),
                null);
        PlanKey plan = new PlanKey(userId, ids.get());
        store(plan, profile);
        if (request.bringGoals()) {
            previous.ifPresent(from -> copyGoals(from, plan));
        }
        profiles.activate(plan);
        return chosen(plan);
    }

    /** The plan just made active, as the chooser describes it. */
    private PlanChoice chosen(PlanKey plan) {
        return profiles.plans(plan.userId()).stream()
                .filter(stored -> stored.key().equals(plan))
                .findFirst()
                .map(stored -> choiceFor(stored, true))
                .orElseThrow();
    }

    /**
     * New ids for the copies, so the two plans' goals can change independently from here: saving for a
     * car in the new city must not quietly move the target of the one left behind.
     */
    private void copyGoals(PlanKey from, PlanKey to) {
        Optional<String> first = goals.finishFirst(from);
        for (GoalDraft goal : goals.goals(from)) {
            GoalDraft copy = new GoalDraft(ids.get(), goal.name(), goal.target(), goal.deadline(), goal.priority());
            goals.save(to, copy);
            if (first.filter(goal.id()::equals).isPresent()) {
                goals.setFinishFirst(to, Optional.of(copy.id()));
            }
        }
    }

    private PlanChoice choiceFor(PlanningProfileStore.StoredPlan stored, boolean active) {
        PlanView.Place place = placeOf(stored.profile());
        Summary summary = snapshots.latest(stored.key())
                .map(view -> new Summary(
                        view.today() == null ? view.surplus().amount() : view.today().leftAsEntered(),
                        "Left each month, from what you entered",
                        view.goals().size(),
                        view.goals().size() == 1 ? "1 goal" : view.goals().size() + " goals"))
                .orElse(null);
        Action use = active
                ? new Action("The plan you are using", "This is the plan on your dashboard now.")
                : new Action(
                        "Use this plan",
                        "Your goals, spending and history from " + whereIn(stored.profile())
                                + " come back exactly as you left them. The money you have now stays as it is.");
        return new PlanChoice(stored.key().planId(), place, active, stored.lastUsedAt(), summary, use);
    }

    /** A place as a person reads it, with the ids the client sends back. */
    PlanView.Place placeOf(PlanningProfile profile) {
        String country = places.countryName(profile.country()).orElse(profile.country().value());
        Optional<Places.City> city = cityOf(profile);
        return new PlanView.Place(
                new PlanView.Country(profile.country().value(), country),
                city.map(listed -> new PlanView.City(listed.id().slug(), listed.name())).orElse(null),
                profile.cityNotListed(),
                whereIn(profile) + ", " + country);
    }

    private String whereIn(PlanningProfile profile) {
        return cityOf(profile).map(Places.City::name).orElse(profile.cityNotListed());
    }

    private static boolean samePlace(PlanningProfile a, PlanningProfile b) {
        return a.country().equals(b.country())
                && Objects.equals(a.city(), b.city())
                && Objects.equals(a.cityNotListed(), b.cityNotListed());
    }

    private PlanningProfile profileFor(
            String userId,
            CountryCode country,
            MetroId city,
            String cityNotListed,
            LifestyleTier lifestyle,
            boolean incomeArrivesTaxed,
            Money leastForEnjoyingLife) {
        if (places.countryName(country).isEmpty()) {
            throw new IllegalArgumentException(
                    "We do not hold figures for " + country.value() + " yet. Choose a country from the list.");
        }
        if (city != null && places.city(country, city).isEmpty()) {
            throw new IllegalArgumentException(
                    "That city is not in our list for " + country.value()
                            + ". Choose one from the list, or tell us what your city is called.");
        }
        String notListed = cityNotListed == null || cityNotListed.isBlank() ? null : cityNotListed.strip();
        return new PlanningProfile(
                userId, country, city, notListed, lifestyle, incomeArrivesTaxed, leastForEnjoyingLife);
    }

    private void store(PlanKey plan, PlanningProfile profile) {
        if (profile.city() == null) {
            places.recordCityNotListed(plan.userId(), profile.country(), profile.cityNotListed());
        }
        profiles.save(plan, profile);
    }

    /** The plan in use, for anything that changes it. Before a place is chosen there is none to change. */
    private PlanKey planInUse(String userId) {
        return profiles.active(userId).orElseThrow(() -> new NeedsMoreInformationException(
                "Tell us which country and city you live in first."));
    }

    public Optional<Places.City> cityOf(PlanningProfile profile) {
        return profile.listedCity().flatMap(city -> places.city(profile.country(), city));
    }

    public Optional<String> countryNameOf(PlanningProfile profile) {
        return places.countryName(profile.country());
    }

    public StatedMoney saveMoney(String userId, StatedMoney money) {
        profiles.saveMoney(planInUse(userId), money);
        return money;
    }

    public Optional<StatedMoney> money(String userId) {
        return profiles.active(userId).flatMap(profiles::money);
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
        PlanKey plan = planInUse(userId);
        spending.replaceSpending(plan, stated);
        return spending.spending(plan);
    }

    public Map<SpendCategory, Money> spending(String userId) {
        return profiles.active(userId).map(spending::spending).orElse(Map.of());
    }

    public List<UserLineItem> lineItems(String userId) {
        return profiles.active(userId).map(spending::lineItems).orElse(List.of());
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
        spending.saveLineItem(planInUse(userId), item);
        return item;
    }

    public void deleteLineItem(String userId, String itemId) {
        boolean deleted = profiles.active(userId).map(plan -> spending.deleteLineItem(plan, itemId)).orElse(false);
        if (!deleted) {
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
        return profiles.active(userId).map(goals::goals).orElse(List.of());
    }

    public Optional<String> finishFirst(String userId) {
        return profiles.active(userId).flatMap(goals::finishFirst);
    }

    public GoalAndPlan addGoal(String userId, GoalChange change) {
        GoalDraft goal = new GoalDraft(ids.get(), change.name().strip(), change.target(), change.deadline(), change.priority());
        goals.save(planInUse(userId), goal);
        return withPlan(userId, goal, "You added a goal: " + goal.name());
    }

    public GoalAndPlan updateGoal(String userId, String goalId, GoalChange change) {
        ownGoal(userId, goalId);
        GoalDraft goal = new GoalDraft(goalId, change.name().strip(), change.target(), change.deadline(), change.priority());
        goals.save(planInUse(userId), goal);
        return withPlan(userId, goal, "You changed a goal: " + goal.name());
    }

    public Optional<PlanView> removeGoal(String userId, String goalId) {
        GoalDraft goal = ownGoal(userId, goalId);
        PlanKey plan = planInUse(userId);
        if (goals.finishFirst(plan).filter(goalId::equals).isPresent()) {
            goals.setFinishFirst(plan, Optional.empty());
        }
        goals.delete(plan, goalId);
        return tryPlan(userId, "You removed a goal: " + goal.name());
    }

    /** Empty goes back to handing out the balance in order of importance. */
    public Optional<PlanView> finishFirst(String userId, Optional<String> goalId) {
        Optional<GoalDraft> goal = goalId.map(id -> ownGoal(userId, id));
        goals.setFinishFirst(planInUse(userId), goalId);
        return tryPlan(userId, goal
                .map(chosen -> "You chose " + chosen.name() + " to finish first")
                .orElse("You went back to finishing goals in order of importance"));
    }

    private GoalDraft ownGoal(String userId, String goalId) {
        // Someone with no plan at all has no goals either, so they hear the same "not there" as anyone
        // else asking for a goal that is not theirs - never a hint that it exists somewhere.
        return profiles.active(userId).flatMap(plan -> goals.goal(plan, goalId))
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
        return profiles.active(userId).flatMap(snapshots::latest);
    }

    public List<PlanHistory.Entry> history(String userId) {
        return PlanHistory.of(profiles.active(userId).map(snapshots::all).orElse(List.of()));
    }

    public PlanView snapshot(String userId, String snapshotId) {
        return snapshots.find(userId, snapshotId)
                .orElseThrow(() -> new NotFoundException("You have no plan with id \"" + snapshotId + "\"."));
    }

    /** The plan as it would be now, without recording it. For questions and decisions, which do not snapshot. */
    public AssembledPlan assemble(String userId) {
        return assemble(planInUse(userId));
    }

    private AssembledPlan assemble(PlanKey plan) {
        String userId = plan.userId();
        PlanningProfile profile = profiles.profile(plan).orElseThrow(() -> new NeedsMoreInformationException(
                "Tell us which country and city you live in first."));
        StatedMoney money = profiles.money(plan).orElseThrow(() -> new NeedsMoreInformationException(
                "Tell us how much comes in each month, and how much you already have, first."));
        if (!money.monthlyIncome().isPositive()) {
            throw new NeedsMoreInformationException(
                    "A plan is built from what comes in each month, so tell us your monthly income first.");
        }
        Map<SpendCategory, com.hasan.budget.costofliving.domain.ResolvedBaseline> baselines =
                places.baselinesFor(profile);
        checkNamedItemsFit(plan, baselines);
        LocalDate asOf = LocalDate.now(clock);
        return assembler.assemble(new PlanningInputs(
                money.asFunds().resolve(),
                baselines,
                spending.spending(plan),
                bank.measuredMonth(userId, lastCompleteMonth(asOf)),
                spending.lineItems(plan),
                money.alreadySaving(),
                tax.monthlyReserve(profile, money.monthlyIncome()),
                profile.lifestyle(),
                profile.leastForEnjoyingLife(),
                cityNameOf(profile),
                goals.goals(plan),
                goals.finishFirst(plan),
                asOf));
    }

    /**
     * The month a plan reads off a bank statement: the last one that is over.
     *
     * <p>Never the month in progress. Reading it on the 3rd would price a whole life off three days
     * of it and hand the user a surplus that does not exist - and this is the one direction a budget
     * must never be wrong in.
     */
    private static YearMonth lastCompleteMonth(LocalDate asOf) {
        return YearMonth.from(asOf).minusMonths(1);
    }

    /**
     * An item the user named as part of a category has to fit inside what that category actually
     * costs them. Naming $45 of gym inside $40 of subscriptions is a real thing to get into - they
     * lowered one without the other - and the calculation rejects it, rightly, but in words written
     * for whoever is reading a stack trace. Caught here so the user is told which two figures
     * disagree, by the names they gave them, and which they might want to change.
     */
    private void checkNamedItemsFit(
            PlanKey plan, Map<SpendCategory, com.hasan.budget.costofliving.domain.ResolvedBaseline> baselines) {

        Map<SpendCategory, Money> stated = spending.spending(plan);
        Map<SpendCategory, Money> namedSoFar = new java.util.EnumMap<>(SpendCategory.class);
        for (UserLineItem item : spending.lineItems(plan)) {
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
        PlanKey plan = planInUse(userId);
        PlanningProfile profile = profiles.profile(plan).orElseThrow(() -> new NeedsMoreInformationException(
                "Tell us which country and city you live in first."));
        PlanView view = PlanViews.from(assemble(plan), ids.get(), clock.instant(), reason, placeOf(profile));
        snapshots.append(plan, view);
        profiles.activate(plan);
        return view;
    }

    private String cityNameOf(PlanningProfile profile) {
        return cityOf(profile).map(Places.City::name).orElse(profile.cityNotListed());
    }
}
