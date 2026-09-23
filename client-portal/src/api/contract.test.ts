import { describe, expect, it } from 'vitest';
import type {
  Affordability,
  Choices,
  LineItem,
  Money,
  OpenQuestion,
  Override,
  GoalSaved,
  Plan,
  PlanHistoryEntry,
  PlanOrWaiting,
  Profile,
  Rebalance,
  SavedGoal,
} from './plan';
import affordBand from '../test/fixtures/afford-band.json';
import affordPriced from '../test/fixtures/afford-priced.json';
import choices from '../test/fixtures/choices.json';
import goalCar from '../test/fixtures/goal-car.json';
import finishFirst from '../test/fixtures/finish-first.json';
import goalRemoved from '../test/fixtures/goal-removed.json';
import goalWaiting from '../test/fixtures/goal-waiting.json';
import goals from '../test/fixtures/goals.json';
import lineItem from '../test/fixtures/line-item.json';
import money from '../test/fixtures/money.json';
import override from '../test/fixtures/override.json';
import planFixture from '../test/fixtures/plan.json';
import planHistory from '../test/fixtures/plan-history.json';
import profile from '../test/fixtures/profile.json';
import questions from '../test/fixtures/questions-new.json';
import rebalance from '../test/fixtures/rebalance.json';
import tightPlan from '../test/fixtures/tight-plan.json';
import tightHistory from '../test/fixtures/tight-plan-history.json';

/**
 * The fixtures are recorded from a live budget-core, so they are the server's actual answers. Naming
 * each one at the type the client reads it as makes `tsc` the contract test: a field the server
 * renames, drops, or starts returning null for stops this file compiling, and the failure names the
 * field instead of surfacing as `undefined` in a screen three layers away.
 *
 * The runtime assertions below are deliberately few. They cover only the things a type cannot say -
 * that a list is not empty, and that the fixtures really do carry the awkward cases the screens are
 * written against - because everything else is already checked at compile time.
 */
describe('the recorded fixtures match the types the client reads them at', () => {
  it('types every payload the second half depends on', () => {
    // Annotations, never `as`: a cast only asks whether the two types are comparable, which a
    // fixture missing a field the type requires still satisfies. An assignment is what actually
    // fails when the server stops sending something. Verified by adding a field to a type and
    // watching this stop compiling.
    const typedChoices: Choices = choices;
    const typedProfile: Profile = profile;
    const typedQuestions: OpenQuestion[] = questions;
    const typedMoney: Money = money;
    const typedOverride: Override = override;
    const typedLineItem: LineItem = lineItem;
    const typedGoals: SavedGoal[] = goals;
    const typedSaved: GoalSaved = goalCar;
    const typedWaiting: GoalSaved = goalWaiting;
    const typedFinishFirst: PlanOrWaiting = finishFirst;
    const typedRemoved: PlanOrWaiting = goalRemoved;
    const typedPlan: Plan = planFixture;
    const typedTightPlan: Plan = tightPlan;
    const typedHistory: PlanHistoryEntry[] = planHistory;
    const typedTightHistory: PlanHistoryEntry[] = tightHistory;
    const typedAffordBand: Affordability = affordBand;
    const typedAffordPriced: Affordability = affordPriced;
    const typedRebalance: Rebalance = rebalance;

    for (const [name, value] of Object.entries({
      typedChoices,
      typedProfile,
      typedQuestions,
      typedMoney,
      typedOverride,
      typedLineItem,
      typedGoals,
      typedSaved,
      typedWaiting,
      typedFinishFirst,
      typedRemoved,
      typedPlan,
      typedTightPlan,
      typedHistory,
      typedTightHistory,
      typedAffordBand,
      typedAffordPriced,
      typedRebalance,
    })) {
      expect(value, name).toBeTruthy();
    }
  });

  it('carries a line the plan may never put a number against, and a hint for it instead', () => {
    // A locked line is the case the dashboard is written around: there is no cut to suggest, so the
    // only honest thing to show is the sentence about where it could actually move.
    const plan: Plan = planFixture;
    const locked = plan.surplus.lines.filter((line) => line.howWilling.key === 'locked');
    expect(locked.length).toBeGreaterThan(0);
    for (const line of locked) {
      expect(plan.cuts.suggested.flatMap((cut) => cut.lineIds)).not.toContain(line.id);
    }
    expect(plan.hints.map((hint) => hint.id)).toEqual(expect.arrayContaining(locked.map((line) => line.id)));
  });

  it('carries a refused purchase whose cheaper alternative is refused too', () => {
    // The awkward case for "never show a verdict without the next cheapest option": it would be easy
    // to render `cheaper` only when it is affordable, which is exactly when it is least needed.
    const afford: Affordability = affordBand;
    expect(afford.cheaper).not.toBeNull();
    expect(afford.cheaper?.verdict.label).toBeTruthy();
  });

  it('carries a plan where a goal lost its funding to a higher-priority one', () => {
    // The re-ranking the dashboard has to explain, rather than silently show a goal at zero.
    const plan: Plan = tightPlan;
    const starved = plan.goals.find((goal) => goal.monthlyFunded === '0.00' && goal.shortBy !== '0.00');
    expect(starved).toBeDefined();
    expect(plan.cuts.options.length).toBeGreaterThan(0);

    const tightHistoryTyped: PlanHistoryEntry[] = tightHistory;
    const entry = tightHistoryTyped[0]!;
    const demoted = entry.changes?.goals.find((g) => g.before.monthlyFunded !== g.after.monthlyFunded);
    expect(demoted).toBeDefined();
    expect(tightHistoryTyped.at(-1)?.changes).toBeNull();
  });
});

describe('a goal saved before a plan can be made', () => {
  it('comes back without a plan and with a sentence saying what is missing', () => {
    const saved: GoalSaved = goalWaiting;
    expect(saved.plan).toBeNull();
    expect(saved.waitingFor).toBeTruthy();
  });
});
