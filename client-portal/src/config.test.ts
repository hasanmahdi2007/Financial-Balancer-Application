import { describe, expect, it } from 'vitest';
import { MissingConfigError, readConfig } from './config';

describe('client configuration', () => {
  it('names every missing key, so a blank .env reads as a blank .env and not as a network fault', () => {
    expect(() => readConfig({ SUPABASE_URL: ' ' })).toThrow(
      new MissingConfigError(['SUPABASE_URL', 'SUPABASE_ANON_KEY']),
    );
  });

  it('accepts the two keys a browser is allowed to hold', () => {
    expect(readConfig({ SUPABASE_URL: 'https://x.supabase.co', SUPABASE_ANON_KEY: 'anon' })).toEqual({
      supabaseUrl: 'https://x.supabase.co',
      supabaseAnonKey: 'anon',
    });
  });
});
