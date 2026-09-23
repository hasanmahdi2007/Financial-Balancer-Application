package com.hasan.budget.planning.application;

import com.hasan.budget.costofliving.domain.Confidence;
import com.hasan.budget.costofliving.domain.ResolvedBaseline;
import com.hasan.budget.costofliving.domain.Staleness;
import com.hasan.budget.planning.domain.GreedyPriorityAllocator;
import com.hasan.budget.planning.domain.surplus.UserLineItem;
import com.hasan.budget.profile.domain.DiscretionaryFloor;
import com.hasan.budget.profile.domain.FloorRequest;
import com.hasan.budget.profile.domain.LifestyleTier;
import com.hasan.budget.shared.CountryCode;
import com.hasan.budget.shared.MetroId;
import com.hasan.budget.shared.Money;
import com.hasan.budget.shared.SpendCategory;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A whole planning service with everything external replaced in memory, for tests that are about the
 * service's own behaviour - who may see what, and what a change does to a plan.
 *
 * <p>The stores here behave exactly as the real ones must: every lookup takes a user id, and an id
 * belonging to somebody else finds nothing. That is what makes a scoping test meaningful here rather
 * than merely green; the integration tier then proves the SQL keeps the same promise.
 */
public final class PlanningFixture {

    public static final CountryCode LEBANON = new CountryCode("LB");
    public static final MetroId BEIRUT = new MetroId("beirut");
    private static final LocalDate GATHERED = LocalDate.of(2026, 3, 1);

    private final AtomicInteger nextId = new AtomicInteger();
    private final InMemoryProfiles profiles = new InMemoryProfiles();
    private final InMemorySpending spending = new InMemorySpending();
    private final InMemoryGoals goals = new InMemoryGoals();
    private final InMemorySnapshots snapshots = new InMemorySnapshots();
    private final PlanService service;

    public PlanningFixture(LocalDate today) {
        this(today, BankSpending.NONE);
    }

    /**
     * The same user, with a bank connected.
     *
     * <p>A separate constructor rather than a setter because what a bank has recorded is an input to
     * every plan this fixture makes, not something that can change halfway through one.
     */
    public PlanningFixture(LocalDate today, BankSpending bank) {
        Clock clock = Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        this.service = new PlanService(
                profiles,
                spending,
                goals,
                snapshots,
                new StubPlaces(),
                (profile, income) -> Optional.empty(),
                new PlanAssembler(new GreedyPriorityAllocator(), PlanningFixture::floor),
                bank,
                clock,
                () -> "id-" + nextId.incrementAndGet());
    }

    public PlanService plans() {
        return service;
    }

    /**
     * $250 a month for enjoying life, split across the categories the floor protects.
     *
     * <p>Stated outright rather than derived, so tests that are about planning are not also about the
     * floor policy - and split per category, because that split is what an affordability check asks
     * for when the question is about eating out rather than about rent.
     */
    private static DiscretionaryFloor floor(FloorRequest request) {
        Map<SpendCategory, Money> perCategory = new EnumMap<>(SpendCategory.class);
        perCategory.put(SpendCategory.DINING_OUT, Money.of(150));
        perCategory.put(SpendCategory.ENTERTAINMENT, Money.of(80));
        perCategory.put(SpendCategory.CLOTHING, Money.of(20));
        return new DiscretionaryFloor(
                Money.of(250), perCategory, "typical for someone in Beirut who goes out regularly",
                false, Money.of(900));
    }

    /** One user, ready to plan: living in Beirut, $2,000 a month, $600 of rent, and a stated balance. */
    public void onboard(String userId, Money balance) {
        service.saveProfile(userId, new PlanService.ProfileChange(
                LEBANON, BEIRUT, null, LifestyleTier.REGULAR, true, null));
        service.saveMoney(userId, new StatedMoney(Money.of(2000), balance));
        Map<SpendCategory, Money> stated = new EnumMap<>(SpendCategory.class);
        stated.put(SpendCategory.RENT, Money.of(600));
        stated.put(SpendCategory.GROCERIES, Money.of(300));
        stated.put(SpendCategory.DINING_OUT, Money.of(150));
        service.saveSpending(userId, stated);
    }

    /** Figures for a city we hold data for, each still carrying where it came from. */
    private static final class StubPlaces implements Places {

        @Override
        public Optional<String> countryName(CountryCode country) {
            return LEBANON.equals(country) ? Optional.of("Lebanon") : Optional.empty();
        }

        @Override
        public Optional<City> city(CountryCode country, MetroId city) {
            return LEBANON.equals(country) && BEIRUT.equals(city)
                    ? Optional.of(new City(BEIRUT, "Beirut", "Researched by us", "A careful estimate.", GATHERED))
                    : Optional.empty();
        }

        @Override
        public void recordCityNotListed(String userId, CountryCode country, String cityName) {
            // Nothing to record in memory; the catalogue owns this in production.
        }

        @Override
        public Map<SpendCategory, ResolvedBaseline> baselinesFor(PlanningProfile profile) {
            Map<SpendCategory, ResolvedBaseline> baselines = new EnumMap<>(SpendCategory.class);
            baselines.put(SpendCategory.RENT, figure("500"));
            baselines.put(SpendCategory.GROCERIES, figure("250"));
            baselines.put(SpendCategory.UTILITIES, figure("80"));
            baselines.put(SpendCategory.TRANSPORT_FUEL, figure("60"));
            baselines.put(SpendCategory.DINING_OUT, figure("120"));
            baselines.put(SpendCategory.ENTERTAINMENT, figure("80"));
            return baselines;
        }

        private static ResolvedBaseline figure(String amount) {
            return new ResolvedBaseline(
                    Money.of(amount), Confidence.CROWDSOURCED, "curated", GATHERED, Staleness.FRESH);
        }
    }

    private static final class InMemoryProfiles implements PlanningProfileStore {
        private final Map<String, PlanningProfile> byUser = new LinkedHashMap<>();
        private final Map<String, StatedMoney> money = new LinkedHashMap<>();

        @Override
        public Optional<PlanningProfile> profile(String userId) {
            return Optional.ofNullable(byUser.get(userId));
        }

        @Override
        public void save(PlanningProfile profile) {
            byUser.put(profile.userId(), profile);
        }

        @Override
        public Optional<StatedMoney> money(String userId) {
            return Optional.ofNullable(money.get(userId));
        }

        @Override
        public void saveMoney(String userId, StatedMoney stated) {
            money.put(userId, stated);
        }
    }

    private static final class InMemorySpending implements SpendingStore {
        private final Map<String, Map<SpendCategory, Money>> byUser = new LinkedHashMap<>();
        private final Map<String, List<UserLineItem>> items = new LinkedHashMap<>();

        @Override
        public Map<SpendCategory, Money> spending(String userId) {
            return Map.copyOf(byUser.getOrDefault(userId, Map.of()));
        }

        @Override
        public void replaceSpending(String userId, Map<SpendCategory, Money> spending) {
            byUser.put(userId, new EnumMap<>(spending));
        }

        @Override
        public List<UserLineItem> lineItems(String userId) {
            return List.copyOf(items.getOrDefault(userId, List.of()));
        }

        @Override
        public void saveLineItem(String userId, UserLineItem item) {
            List<UserLineItem> mine = items.computeIfAbsent(userId, user -> new ArrayList<>());
            mine.removeIf(existing -> existing.id().equals(item.id()));
            mine.add(item);
        }

        @Override
        public boolean deleteLineItem(String userId, String itemId) {
            return items.getOrDefault(userId, new ArrayList<>()).removeIf(item -> item.id().equals(itemId));
        }
    }

    private static final class InMemoryGoals implements GoalStore {
        private final Map<String, List<GoalDraft>> byUser = new LinkedHashMap<>();
        private final Map<String, String> finishFirst = new LinkedHashMap<>();

        @Override
        public List<GoalDraft> goals(String userId) {
            return List.copyOf(byUser.getOrDefault(userId, List.of()));
        }

        @Override
        public Optional<GoalDraft> goal(String userId, String goalId) {
            return goals(userId).stream().filter(goal -> goal.id().equals(goalId)).findFirst();
        }

        @Override
        public void save(String userId, GoalDraft goal) {
            List<GoalDraft> mine = byUser.computeIfAbsent(userId, user -> new ArrayList<>());
            mine.removeIf(existing -> existing.id().equals(goal.id()));
            mine.add(goal);
        }

        @Override
        public boolean delete(String userId, String goalId) {
            return byUser.getOrDefault(userId, new ArrayList<>()).removeIf(goal -> goal.id().equals(goalId));
        }

        @Override
        public Optional<String> finishFirst(String userId) {
            return Optional.ofNullable(finishFirst.get(userId));
        }

        @Override
        public void setFinishFirst(String userId, Optional<String> goalId) {
            goalId.ifPresentOrElse(id -> finishFirst.put(userId, id), () -> finishFirst.remove(userId));
        }
    }

    /** Append-only in the same way the table is: there is no way in here to change what was stored. */
    private static final class InMemorySnapshots implements PlanSnapshotStore {
        private final Map<String, List<PlanView>> byUser = new LinkedHashMap<>();
        private final AtomicInteger order = new AtomicInteger();
        private final Map<String, Integer> appendedAt = new LinkedHashMap<>();

        @Override
        public void append(String userId, PlanView plan) {
            byUser.computeIfAbsent(userId, user -> new ArrayList<>()).add(plan);
            appendedAt.put(plan.id(), order.incrementAndGet());
        }

        @Override
        public Optional<PlanView> latest(String userId) {
            return all(userId).stream().findFirst();
        }

        @Override
        public List<PlanView> all(String userId) {
            List<PlanView> mine = new ArrayList<>(byUser.getOrDefault(userId, List.of()));
            // Newest first, as the table's index returns them. A fixed clock makes every snapshot
            // share a timestamp, so insertion order is what orders them here.
            mine.sort((left, right) -> appendedAt.get(right.id()) - appendedAt.get(left.id()));
            return List.copyOf(mine);
        }

        @Override
        public Optional<PlanView> find(String userId, String snapshotId) {
            return all(userId).stream().filter(plan -> plan.id().equals(snapshotId)).findFirst();
        }
    }

    public static Instant at(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
