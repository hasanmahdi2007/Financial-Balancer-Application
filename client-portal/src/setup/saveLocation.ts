import { ApiError, type ApiClient } from '../api/http';
import { plan, type Profile, type ProfileEdit } from '../api/plan';
import type { ChosenLocation } from '../onboarding/OnboardingDraft';
import { sameAmount } from '../ui/amount';

/** A 404 here means "not told us yet", which is an answer rather than a failure. */
export async function orNothing<T>(request: Promise<T>): Promise<T | null> {
  try {
    return await request;
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) return null;
    throw error;
  }
}

/**
 * The profile with a new location, keeping everything else the user has already told us.
 *
 * `PUT /api/v1/profile` takes the whole profile, so changing where someone lives must not quietly
 * reset how they live. `incomeArrivesTaxed` starts true for a new profile because the income
 * question asks for what lands in the account, which is after tax.
 */
export function withLocation(existing: Profile | null, location: ChosenLocation): ProfileEdit {
  return {
    country: location.countryCode,
    city: location.kind === 'listed' ? location.cityId : null,
    cityNotListed: location.kind === 'unlisted' ? location.cityLabel : null,
    lifestyle: existing?.lifestyle?.key ?? null,
    incomeArrivesTaxed: existing?.incomeArrivesTaxed ?? true,
    leastForEnjoyingLife: existing?.leastForEnjoyingLife ?? null,
  };
}

/**
 * Commits the location step: the profile first, then - for a city we hold no figures for - the
 * answers the user changed on the manual form, each as their own figure for that category.
 *
 * Only changed answers are sent. An answer left at our suggestion is still our estimate, and sending
 * it as an override would label it "Your own figure" on every screen afterwards.
 */
export async function saveLocation(api: ApiClient, location: ChosenLocation): Promise<void> {
  const existing = await orNothing(plan.profile(api));
  await plan.saveProfile(api, withLocation(existing, location));
  if (location.kind !== 'unlisted') return;
  for (const answer of location.answers) {
    // A draft saved before answers carried their category cannot be routed, so it is skipped.
    if (!answer.category) continue;
    if (answer.suggested && sameAmount(answer.amount, answer.suggested)) continue;
    await plan.saveOverride(api, answer.category, answer.amount);
  }
}
