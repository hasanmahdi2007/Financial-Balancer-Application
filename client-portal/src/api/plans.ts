import type { ApiClient } from './http';

/**
 * A user's plans, one per place, and which one is in use (`/api/v1/plans`).
 *
 * Everything else under `/api/v1` acts on the plan in use, so switching here is all a move takes:
 * the dashboard, goals, spending and history follow without changing shape. Every sentence below
 * arrives from the server; this client decides nothing about what a plan carries across.
 */

/** A place as a person reads it. Render `label`; send back only the ids. */
export interface Place {
  country: { code: string; name: string };
  /** Null when the user's city is not one we list. */
  city: { id: string; name: string } | null;
  /** What they called their city in that case. */
  cityNotListed: string | null;
  /** The whole place in words, e.g. "Beirut, Lebanon". */
  label: string;
}

/** What a plan showed last. The amount is a figure; format it with `formatMoney` like every other. */
export interface PlanSummary {
  leftEachMonth: string;
  leftEachMonthLabel: string;
  goals: number;
  goalsLabel: string;
}

export interface PlanChoice {
  id: string;
  place: Place;
  active: boolean;
  /** ISO instant the plan was last in use. */
  lastUsedAt: string;
  /** Null for a plan that was started but never made. */
  summary: PlanSummary | null;
  /** The button's words, and what pressing it does. */
  use: { label: string; meaning: string };
}

export interface PlanChoices {
  plans: PlanChoice[];
  startNew: { label: string; meaning: string; bringGoalsLabel: string };
}

/** Exactly one of `city` and `cityNotListed`. */
export interface NewPlan {
  country: string;
  city: string | null;
  cityNotListed: string | null;
  bringGoals: boolean;
}

export const plans = {
  /** Every plan, or only those in one country: the list to offer before starting another there. */
  list: (api: ApiClient, country?: string, signal?: AbortSignal) =>
    api.getJson<PlanChoices>(
      country ? `/api/v1/plans?country=${encodeURIComponent(country)}` : '/api/v1/plans',
      signal,
    ),

  /** Starts a plan for a place and makes it the one in use. Its setup questions come next. */
  start: (api: ApiClient, plan: NewPlan) => api.sendJson<PlanChoice>('POST', '/api/v1/plans', plan),

  /** Picks up a plan the user already has. `GET /api/v1/plan` then returns the last one it made. */
  use: (api: ApiClient, planId: string) => api.sendJson<PlanChoice>('PUT', '/api/v1/plans/active', { planId }),
};

/** Whether the user already has a plan in this country: the question both ways of starting one ask. */
export async function plansIn(api: ApiClient, country: string, signal?: AbortSignal): Promise<PlanChoices> {
  return plans.list(api, country, signal);
}
