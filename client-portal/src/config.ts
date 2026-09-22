export interface ClientConfig {
  supabaseUrl: string;
  supabaseAnonKey: string;
}

/**
 * Thrown before anything renders, so a missing key reads as a missing key.
 *
 * Without this check the Supabase client is created with an empty URL and fails on the first
 * sign-in attempt with a network error - which on this machine looks exactly like the DNS dropping.
 */
export class MissingConfigError extends Error {
  constructor(readonly missing: string[]) {
    super(`Missing ${missing.join(' and ')} in the .env at the repository root.`);
    this.name = 'MissingConfigError';
  }
}

export function readConfig(raw: Record<string, string | undefined>): ClientConfig {
  const supabaseUrl = raw.SUPABASE_URL?.trim() ?? '';
  const supabaseAnonKey = raw.SUPABASE_ANON_KEY?.trim() ?? '';
  const missing = [
    ...(supabaseUrl ? [] : ['SUPABASE_URL']),
    ...(supabaseAnonKey ? [] : ['SUPABASE_ANON_KEY']),
  ];
  if (missing.length > 0) throw new MissingConfigError(missing);
  return { supabaseUrl, supabaseAnonKey };
}
