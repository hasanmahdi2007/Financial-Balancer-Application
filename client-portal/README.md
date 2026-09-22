# client-portal

The browser half of the Financial Balancer: sign-up and sign-in, and the cascading country → city
picker with the pre-filled form for everyone whose city we do not hold figures for.

## Running it

Two processes. The client proxies `/api` to the second one, so the browser only ever talks to its
own origin and nothing needs a CORS policy written for a dev server.

```bash
# 1. the API. Anything that serves /api/catalogue/** will do; today that is budget-core, and once
#    the gateway lands it is the gateway (set API_TARGET to wherever it listens).
cd budget-core
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-25.0.4.7-hotspot"
MVN="$USERPROFILE/.m2/maven-dist/apache-maven-3.9.16/bin/mvn.cmd"
"$MVN" --batch-mode -DskipTests package
java -jar target/budget-core-0.0.1-SNAPSHOT.jar --server.port=8090

# 2. the client
cd client-portal
npm install
npm run dev     # http://localhost:5173
```

`8090` rather than `8080`: the jobs-tracker stack on this machine holds 8080, and Postgres for this
project is on 5434 (see `docker-compose.yml` for why). `API_TARGET` in the repository-root `.env`
overrides the proxy target.

## Keys

`SUPABASE_URL` and `SUPABASE_ANON_KEY` come from the `.env` at the **repository root** — there is no
second `.env` in this folder, and `.gitignore` here keeps one from appearing and silently shadowing
it. `vite.config.ts` names those two keys one at a time rather than exposing a prefix, so a
service-role key added to that file later cannot be swept into a browser bundle, and it refuses to
build if the value it is given is a secret key rather than the publishable one.

`SUPABASE_JWKS_URI` belongs to the gateway and never reaches the browser.

## Checks

```bash
npm test         # vitest, no network, no browser
npm run typecheck
npm run build
```

Tests answer from fixtures in `src/test/fixtures/`, recorded from a real `budget-core` against the
seeded database. Nothing in the suite touches the network: the setup file replaces `fetch` with one
that throws, so a test that forgets its fake fails loudly instead of quietly reaching for whatever
happens to be listening.

## How it is put together

- `auth/` — `AuthGateway` is everything the client needs from an identity provider;
  `supabaseAuthGateway.ts` is the only file that knows Supabase exists, and the tests pass an
  in-memory implementation instead.
- `api/` — one client, which attaches the access token to every request and ends the session when
  the server refuses one. Returning the user to sign-in matters: a dashboard full of empty state
  looks exactly like a data bug.
- `provenance/` — the badge, the gathered date and the staleness banner, reused by every screen that
  shows a number that came from data.
- `onboarding/` — the steps, plus the draft of what the user has answered so far.

**All wording about data comes from the API.** `basis`, `explanation`, `covers` and `why` are
assembled on the server from the `Confidence` enum and the category table, so a new tier or category
cannot ship without wording. A label map keyed by constant names here would be a second copy of that
policy and would drift the first time one is added — `src/test/internalWords.test.ts` reads every
enum constant out of the Java source and fails if one appears in this codebase.

## Where setup stops

Income, lifestyle, the considered share of savings, the dashboard, goals and the two decision
surfaces all need endpoints that do not exist on `main` yet. Setup therefore ends after the location
step, saying so plainly, and keeps the answers in `sessionStorage` until there is somewhere to save
them.
