import type { ApiClient } from './http';

/**
 * Everything under `/api/v1`, typed as the gateway serialises it.
 *
 * The shape to keep in mind while reading this file: every choice the user makes travels as a
 * lowercase `key`, and every key arrives beside a `label` written for a person. Keys are for
 * machines and are the only thing sent back; labels are the only thing rendered. There is no map
 * from one to the other anywhere in this client, and there must never be - the server owns that
 * wording, and a copy here would be a second policy that drifts the first time a category is added.
 *
 * Money is a string in both directions, for the reason `Money` exists on the server: a JavaScript
 * number cannot hold "1570.00" without becoming a binary float, and a plan that rounds differently
 * from the server's is worse than no plan.
 */

/** A key to send back, and the words to offer it in. */
export interface Choice {
  key: string;
  label: string;
  /** The explanation under the label, e.g. "streaming, gym, apps, anything billed monthly". */
  covers: string;
}

export interface Choices {
  categories: Choice[];
  howWilling: Choice[];
  priorities: Choice[];
  lifestyles: Choice[];
  mealBands: Choice[];
}

/** A key with its label, as it comes back attached to a saved answer. */
export interface Labelled {
  key: string;
  label: string;
}

/** A label with the sentence behind it. The sentence is the whole point; never render one alone. */
export interface Meaning {
  label: string;
  meaning: string;
}

/** Where a figure came from, in the words the server assembled from its `Confidence` tier. */
export interface Basis {
  label: string;
  explanation: string;
  /** ISO date the figures were recorded. */
  gathered: string;
  /**
   * How far prices have probably moved since, as a label and the sentence behind it - null while the
   * figure is still current, so only a figure worth questioning is flagged. An object, not a string:
   * the server sends `{label, meaning}` (`PlanView.Basis`).
   */
  ageing: Meaning | null;
}

export interface CityBasis {
  id: string;
  name: string;
  basis: string;
  explanation: string;
  gathered: string;
}

export interface Profile {
  country: { code: string; name: string };
  city: CityBasis | null;
  /** The name the user typed when theirs was not listed. Display only; it selects nothing. */
  cityNotListed: string | null;
  lifestyle: Labelled | null;
  incomeArrivesTaxed: boolean;
  leastForEnjoyingLife: string | null;
}

export interface ProfileEdit {
  country: string;
  city: string | null;
  cityNotListed: string | null;
  lifestyle: string | null;
  incomeArrivesTaxed: boolean;
  leastForEnjoyingLife: string | null;
}

/**
 * One thing still to ask, explaining itself. `answerWith` names the request that answers it, which
 * is how the server keeps the order of setup without the client hard-coding a sequence of screens.
 */
export interface OpenQuestion {
  key: string;
  answerWith: string;
  question: string;
  why: string;
  covers: string[];
  suggested: string | null;
  basis: string | null;
  /** Non-empty when the answer is one of a fixed set - "How often do you go out?" - and empty
   *  when it is an amount. Which of the two a question is, is the server's to say, not ours. */
  choices: { key: string; label: string }[];
}

export interface Money {
  monthlyIncome: string;
  balance: string;
  setAside: string;
  explanation: string;
  /**
   * What the user said they already move into savings each month, or null when they have not said -
   * in which case a connected bank supplies it. Null is not zero: "0.00" is the user saying they save
   * nothing, which outranks the bank.
   */
  alreadySaving: string | null;
  alreadySavingExplanation: string;
}

/**
 * The body of `PUT /api/v1/money`. `alreadySaving` left out *clears* a saved figure (checked against
 * the live server), so anything that rewrites the money must carry it through unless the user
 * emptied it.
 */
export interface MoneyEdit {
  monthlyIncome: string;
  balance: string;
  alreadySaving?: string;
}

export interface Override {
  category: Labelled;
  amount: string;
  setOn: string;
  basis: Meaning;
}

export interface LineItem {
  id: string;
  label: string;
  category: Labelled;
  amount: string;
  howWilling: Labelled;
  kind: Labelled & { meaning: string };
}

export interface LineItemEdit {
  label: string;
  category: string;
  amount: string;
  howWilling: string;
  kind: string;
}

/** One line of what the plan counted, and why it counted that rather than what was spent. */
export interface PlanLine {
  id: string;
  label: string;
  category: Labelled;
  /** What the user spends. */
  spent: string;
  /** What the city says it costs, or null for a named commitment with no local equivalent. */
  localFigure: string | null;
  /** What the plan worked with. */
  counted: string;
  /** True when nobody told us, so the local figure stands in. Say so on the line. */
  assumed: boolean;
  /**
   * The month this figure was read off the user's own account, worded for them ("what you spent in
   * February 2026"), or null when it did not come from a bank.
   */
  measuredFrom: string | null;
  howWilling: Labelled;
  basis: Basis | null;
}

export interface Reduction {
  label: string;
  from: string;
  to: string;
  by: string;
}

export interface Surplus {
  amount: string;
  explanation: string;
  /** What the amount above already assumes the user cuts. Never show the amount without it. */
  assumedReduction: string;
  assumedReductionExplanation: string;
  reductions: Reduction[];
  lines: PlanLine[];
  leastForEnjoyingLife: string | null;
  leastForEnjoyingLifeBasis: string | null;
  alreadySaving: string;
  alreadySavingExplanation: string;
}

/**
 * A goal as the user described it. This is what `GET /api/v1/goals` and the `goal` of a save return:
 * no funding figures, because how a goal is doing is a property of a plan and not of the goal.
 */
export interface SavedGoal {
  id: string;
  name: string;
  priority: Labelled;
  target: string;
  deadline: string;
  /** True when the user asked this goal to take the balance ahead of priority order. */
  finishFirst: boolean;
}

/** The same goal seen from inside a plan, which is the only place it has a status or a shortfall. */
export interface PlanGoal extends SavedGoal {
  /** The part of the balance already standing against this goal. */
  fromBalance: string;
  stillNeeded: string;
  monthlyNeeded: string;
  monthlyFunded: string;
  shortBy: string;
  status: Meaning;
  /**
   * Whole percent of the target already covered, rounded down so a goal never reads as done early.
   * Null on plans made before the server sent it.
   */
  percentCovered?: number | null;
}

export interface SuggestedCut {
  label: string;
  category: Labelled;
  by: string;
  howWilling: Labelled;
  lineIds: string[];
}

/** A real way out when the arithmetic will not close, with the request that acts on it. */
export interface WayOut {
  label: string;
  meaning: string;
  answerWith: string;
}

export interface Cuts {
  suggested: SuggestedCut[];
  suggestedTotal: string;
  /** What the surplus already assumed. The suggested cuts are only the part on top of this. */
  alreadyAssumed: string;
  totalChange: string;
  totalChangeExplanation: string;
  stillShort: string;
  options: WayOut[];
}

/**
 * A sentence about where a line could actually move. Never a number, and never added into a total -
 * "a roommate, or renegotiating the lease" is advice, not money the plan has found.
 */
export interface Hint {
  id: string;
  label: string;
  hint: string;
}

/**
 * Where the user stands before the plan changes anything: what is really left each month from the
 * figures exactly as they gave them. Nothing in it is assumed, which is why it is shown first and the
 * plan's own figure only after the changes that produce it.
 */
export interface Today {
  income: string;
  /** Null for anyone whose pay arrives with tax already taken off. */
  taxSetAside: string | null;
  spent: string;
  /** Negative when they spend more than they earn - never clamped, because that is the first thing to see. */
  leftAsEntered: string;
  leftAsEnteredExplanation: string;
  /**
   * How long their savings cover that shortfall; null when there is none. Measured against the real
   * gap - the plan's own runway comes after its changes, and would call this user "not running down".
   */
  runway: string | null;
  /** Part of what is left, not taken from it. Null when nothing is put by. */
  alreadySaving: string | null;
  alreadySavingNote: string | null;
  /** How many of the figures behind this are our local estimate rather than the user's own. */
  estimatedLines: number;
  estimatedNote: string | null;
  /** Introduces the plan's own monthly figure, once the changes that produce it are listed. */
  planResult: string;
}

export interface Plan {
  id: string;
  takenAt: string;
  asOf: string;
  /** Why this plan was made, e.g. "You added a goal: Emergency fund". */
  reason: string;
  /**
   * Absent on every plan made before it existed, and never worked out here from an old plan's lines:
   * a past plan is a record of what the user was told, and they were not told this.
   */
  today?: Today | null;
  money: {
    monthlyIncome: string;
    balanceInScope: string;
    setAside: string;
    putTowardGoals: string;
    leftUnassigned: string;
    runway: { label: string; months: number | null };
  };
  surplus: Surplus;
  goals: PlanGoal[];
  cuts: Cuts;
  hints: Hint[];
  leftOver: { amount: string; explanation: string };
}

export interface GoalEdit {
  name: string;
  target: string;
  deadline: string;
  priority: string;
}

/**
 * The answer to adding or changing a goal. `plan` is null and `waitingFor` is a sentence when the
 * goal is saved but no plan can be made yet - before any income is known, for instance.
 */
/** A recomputed plan, or - when one cannot be made yet - the sentence saying what is missing. */
export interface PlanOrWaiting {
  plan: Plan | null;
  waitingFor: string | null;
}

export interface GoalSaved extends PlanOrWaiting {
  goal: SavedGoal;
}

export interface PlanChange {
  goalsAdded: string[];
  goalsRemoved: string[];
  surplus: { before: string; after: string };
  goals: {
    id: string;
    name: string;
    before: { status: string; monthlyFunded: string; fromBalance: string };
    after: { status: string; monthlyFunded: string; fromBalance: string };
  }[];
}

/** One past plan. `changes` is null on the oldest entry - there was nothing before it. */
export interface PlanHistoryEntry {
  id: string;
  takenAt: string;
  reason: string;
  changes: PlanChange | null;
}

export interface Priced {
  label: string;
  covers: string;
  price: string;
  basis: Meaning;
}

/**
 * The answer to "can I afford this today?".
 *
 * `cheaper` carries a verdict of its own, and the two are shown together: a verdict on its own,
 * when the answer is no, is a scold rather than help.
 */
export interface Affordability {
  verdict: Meaning;
  category: Labelled;
  purchase: Priced;
  allowance: string;
  allowanceBasis: string;
  spentThisMonth: string;
  left: string;
  daysLeft: number;
  perDay: string;
  catchUp: {
    perDayAfter: string;
    lessPerDay: string;
    days: number;
    fitsThisMonth: boolean;
    runsIntoNextMonth: string;
  } | null;
  cheaper: (Priced & { verdict: Meaning }) | null;
}

export interface AffordAsk {
  category: string;
  band?: string;
  label?: string;
  price?: string;
  spentThisMonth?: string;
}

export interface Rebalance {
  outcome: Meaning;
  granted: string;
  stillShort: string;
  changes: (Reduction & { id: string })[];
  hints: Hint[];
  options: WayOut[];
}

export interface RebalanceAsk {
  raise: string;
  amount?: string;
  percentOfBalance?: number;
}

const key = (value: string) => encodeURIComponent(value);

export const plan = {
  choices: (api: ApiClient, signal?: AbortSignal) => api.getJson<Choices>('/api/v1/choices', signal),
  itemKinds: (api: ApiClient, category: string, signal?: AbortSignal) =>
    api.getJson<(Choice & { meaning?: string })[]>(`/api/v1/choices/item-kinds?category=${key(category)}`, signal),

  profile: (api: ApiClient, signal?: AbortSignal) => api.getJson<Profile>('/api/v1/profile', signal),
  saveProfile: (api: ApiClient, edit: ProfileEdit) => api.sendJson<Profile>('PUT', '/api/v1/profile', edit),

  questions: (api: ApiClient, signal?: AbortSignal) => api.getJson<OpenQuestion[]>('/api/v1/questions', signal),

  money: (api: ApiClient, signal?: AbortSignal) => api.getJson<Money>('/api/v1/money', signal),
  saveMoney: (api: ApiClient, body: MoneyEdit) =>
    api.sendJson<Money>('PUT', '/api/v1/money', body),

  spending: (api: ApiClient, signal?: AbortSignal) => api.getJson<Record<string, string>>('/api/v1/spending', signal),
  saveSpending: (api: ApiClient, byCategory: Record<string, string>) =>
    api.sendJson<Record<string, string>>('PUT', '/api/v1/spending', byCategory),

  overrides: (api: ApiClient, signal?: AbortSignal) => api.getJson<Override[]>('/api/v1/overrides', signal),
  saveOverride: (api: ApiClient, category: string, amount: string) =>
    api.sendJson<Override>('PUT', `/api/v1/overrides/${key(category)}`, { amount }),

  lineItems: (api: ApiClient, signal?: AbortSignal) => api.getJson<LineItem[]>('/api/v1/line-items', signal),
  saveLineItem: (api: ApiClient, id: string, edit: LineItemEdit) =>
    api.sendJson<LineItem>('PUT', `/api/v1/line-items/${key(id)}`, edit),
  removeLineItem: (api: ApiClient, id: string) =>
    api.sendJson<void>('DELETE', `/api/v1/line-items/${key(id)}`),

  latest: (api: ApiClient, signal?: AbortSignal) => api.getJson<Plan>('/api/v1/plan', signal),
  recompute: (api: ApiClient) => api.sendJson<Plan>('POST', '/api/v1/plan'),
  history: (api: ApiClient, signal?: AbortSignal) => api.getJson<PlanHistoryEntry[]>('/api/v1/plan/history', signal),
  pastPlan: (api: ApiClient, id: string, signal?: AbortSignal) =>
    api.getJson<Plan>(`/api/v1/plan/history/${key(id)}`, signal),

  goals: (api: ApiClient, signal?: AbortSignal) => api.getJson<SavedGoal[]>('/api/v1/goals', signal),
  addGoal: (api: ApiClient, edit: GoalEdit) => api.sendJson<GoalSaved>('POST', '/api/v1/goals', edit),
  changeGoal: (api: ApiClient, id: string, edit: GoalEdit) =>
    api.sendJson<GoalSaved>('PUT', `/api/v1/goals/${key(id)}`, edit),
  removeGoal: (api: ApiClient, id: string) => api.sendJson<PlanOrWaiting>('DELETE', `/api/v1/goals/${key(id)}`),
  /** Send `null` to go back to priority order. */
  finishFirst: (api: ApiClient, goalId: string | null) =>
    api.sendJson<PlanOrWaiting>('PUT', '/api/v1/goals/finish-first', { goalId }),

  afford: (api: ApiClient, ask: AffordAsk) => api.sendJson<Affordability>('POST', '/api/v1/decisions/afford', ask),
  rebalance: (api: ApiClient, ask: RebalanceAsk) =>
    api.sendJson<Rebalance>('POST', '/api/v1/decisions/rebalance', ask),
};
