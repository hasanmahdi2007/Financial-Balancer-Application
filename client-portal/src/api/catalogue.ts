import type { ApiClient } from './http';

/**
 * The three catalogue endpoints, typed exactly as `CatalogueController` serialises them.
 *
 * Every string here is already worded for a person by the server - `basis` and `explanation` come
 * from the `Confidence` enum and `covers` from the category table. Render them; never map them to
 * other words here, or the client becomes a second copy of that policy and drifts from the first.
 */

export interface Country {
  code: string;
  name: string;
  /** The caveat a person needs before trusting the figures, e.g. that prices are in US dollars. */
  note: string | null;
}

export interface City {
  /** The slug, and the only lookup key there is. Send it back; never a typed name. */
  id: string;
  name: string;
  region: string | null;
  /** What these figures are, in a person's words, e.g. "Researched by us". */
  basis: string;
  /** The sentence behind `basis`, for someone deciding how far to trust it. */
  explanation: string;
  /** ISO date the oldest of this city's figures was recorded. */
  gathered: string;
}

export interface SpendingQuestion {
  question: string;
  why: string;
  /** Rendered lines such as "Rent - your rent or mortgage payment". */
  covers: string[];
  /** The pre-filled answer, as a decimal string, e.g. "350.00". */
  suggested: string;
  /** Where the suggestion came from. */
  basis: string;
}

const code = (countryCode: string) => encodeURIComponent(countryCode);

export const catalogue = {
  countries: (api: ApiClient, signal?: AbortSignal) =>
    api.getJson<Country[]>('/api/catalogue/countries', signal),
  cities: (api: ApiClient, countryCode: string, signal?: AbortSignal) =>
    api.getJson<City[]>(`/api/catalogue/countries/${code(countryCode)}/cities`, signal),
  manualForm: (api: ApiClient, countryCode: string, signal?: AbortSignal) =>
    api.getJson<SpendingQuestion[]>(`/api/catalogue/countries/${code(countryCode)}/manual-form`, signal),
};
