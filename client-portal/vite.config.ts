/// <reference types="vitest/config" />
import { fileURLToPath } from 'node:url';
import react from '@vitejs/plugin-react';
import { defineConfig, loadEnv } from 'vite';

// Keys live in the one .env at the repository root, which every packet shares. A second .env in
// this folder would shadow it and drift.
const repoRoot = fileURLToPath(new URL('..', import.meta.url));

/**
 * Exactly two values reach the browser, named one by one.
 *
 * Vite's usual mechanism is a prefix (`VITE_`, or anything configured as `envPrefix`), which ships
 * every matching variable. A prefix of `SUPABASE_` would also ship a service-role key the day
 * somebody adds one to `.env` - and that key bypasses every row-level policy in the project. An
 * allowlist cannot grow by accident.
 */
const BROWSER_KEYS = ['SUPABASE_URL', 'SUPABASE_ANON_KEY'] as const;

/**
 * Refuses to bundle a key that can act as an administrator.
 *
 * Legacy Supabase keys are JWTs whose `role` claim says which one they are; the newer format says
 * so in its prefix. Either way the difference between "safe to publish" and "anyone can read every
 * user's data" is visible in the string, so it is checked here rather than trusted.
 */
function assertPublishable(key: string): void {
  if (key.startsWith('sb_secret_')) {
    throw new Error('SUPABASE_ANON_KEY holds a secret key. Only the publishable key may be bundled.');
  }
  const payload = key.split('.')[1];
  if (!payload) return;
  try {
    const claims = JSON.parse(Buffer.from(payload, 'base64url').toString('utf8')) as { role?: string };
    if (claims.role === 'service_role') {
      throw new Error('SUPABASE_ANON_KEY holds the service-role key. Only the anon key may be bundled.');
    }
  } catch (error) {
    if (error instanceof SyntaxError) return;
    throw error;
  }
}

export default defineConfig(({ mode }) => {
  // Tests never see the real keys: a test that passes only because .env happens to exist would fail
  // on CI and in every fresh worktree.
  const env = mode === 'test' ? {} : loadEnv(mode, repoRoot, '');
  const browserEnv = Object.fromEntries(BROWSER_KEYS.map((key) => [key, env[key] ?? '']));
  if (browserEnv.SUPABASE_ANON_KEY) assertPublishable(browserEnv.SUPABASE_ANON_KEY);

  return {
    plugins: [react()],
    define: {
      __BROWSER_ENV__: JSON.stringify(browserEnv),
    },
    server: {
      port: 5173,
      strictPort: true,
      // Same-origin in development, so the browser never makes a cross-origin call and neither the
      // gateway nor budget-core needs a CORS policy written for a dev server. API_TARGET points at
      // the gateway once it exists; until then, at a budget-core run locally (see README.md).
      proxy: {
        '/api': {
          target: env.API_TARGET || 'http://localhost:8090',
          changeOrigin: true,
        },
      },
    },
    test: {
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      restoreMocks: true,
    },
  };
});
