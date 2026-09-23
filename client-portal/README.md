# client-portal

The browser half of the Financial Balancer: sign-up and sign-in, where you live, the rest of setup,
the plan itself, goals, the history of every plan, and the two decision screens ("can I afford
this?" and "give me more for something").

## Running it

Two processes. The client proxies `/api` to the second one, so the browser only ever talks to its
own origin and nothing needs a CORS policy written for a dev server.

```bash
# 1. the API: budget-core behind the gateway. Every /api/v1 route needs a real Supabase token,
#    which only the gateway checks - budget-core on its own refuses them (it trusts an id the
#    gateway injects, and there is none). The catalogue routes answer either way.
docker compose up -d --build     # gateway on 8090, budget-core unpublished, as designed

# 2. the client
cd client-portal
npm install
npm run dev     # http://localhost:5173
```

`8090` rather than `8080`: the jobs-tracker stack on this machine holds 8080, and Postgres for this
project is on 5434 (see `docker-compose.yml` for why). `API_TARGET` in the repository-root `.env`
overrides the proxy target.

**8090 is the gateway's port**, as `api-gateway/happy-path.http` uses it. `budget-core` borrows it
only while the gateway is not running — the two cannot both hold it, and once the gateway is up it
is the one the browser should be talking to anyway, since it is what validates the token this client
issues (`Authorization: Bearer <supabase access token>`, which is what every request here already
sends). Run `budget-core` on any other port then, or let Compose run it with no published port at
all, which is the arrangement it is designed for.

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
- `onboarding/` — where you live, plus the draft of what the user has answered so far.
- `setup/` — saving the location, and the rest of setup driven by `GET /api/v1/questions`.
- `plan/` — the plan, what changed to make it, the history of every plan, and the money screen.
- `goals/` — adding and changing a goal; saving one recomputes the plan on the server.
- `decisions/` — "can I afford this?" and "give me more for something". Neither saves anything.
- `ui/` — amounts (validated and sent as text, never as floats), the money field, loading and
  failure states.

**All wording about data comes from the API.** `basis`, `explanation`, `covers` and `why` are
assembled on the server from the `Confidence` enum and the category table, so a new tier or category
cannot ship without wording. A label map keyed by constant names here would be a second copy of that
policy and would drift the first time one is added — `src/test/internalWords.test.ts` reads every
enum constant out of the Java source and fails if one appears in this codebase.

## Rules the screens are written around

These come from the packet and from the API contract (`api-gateway/happy-path.http`), and each has a
test that fails if it is broken - checked by breaking each one on purpose.

- **The monthly figure never appears without what it already assumes you change.** It is only
  reachable if those reductions happen; shown alone it reads as money in hand.
- **Suggested cuts never appear without `alreadyAssumed` and `totalChange`.** They are only the part
  on top; alone they read as the whole change.
- **A line the plan cannot cut gets a sentence, never a number.** Hints are shown beside their line and
  are never added into anything.
- **A verdict is never shown without the cheaper option and that option's own verdict** - including
  when the cheaper option is refused too.
- **A goal that lost its money to a more important one is explained, not just shown at zero.** The
  plan's "what changed" panel lists every goal whose funding moved, before and after, and is shown
  only when the newest history entry is the plan on screen.
- **Our estimate is never sent back as the user's figure.** A suggestion left blank (setup) or left
  unchanged (the manual form) stays out of the request, so the plan keeps saying it assumed it.

### Routes that replace rather than merge

`PUT /api/v1/spending` replaces the whole map, `PUT /api/v1/profile` replaces the whole profile, and
`PUT /api/v1/money` refuses one figure without the other. Two single-category spending PUTs against
the live server leave only the second. So every write here reads what is saved, merges, and sends the
whole body - see `setup/QuestionsStep.tsx` and `setup/saveLocation.ts`.

The money route has a sharper edge: `alreadySaving` *left out* clears a saved figure, while empty in
the form means "let a connected bank say". So a figure on file travels again with every write unless
the user emptied the box.

Which setup questions may be skipped is also the server's call. It cannot be read off a question
("already saving" has no suggestion and is optional; income has none and is not), so the client checks
only the form of an answer, leaves empty ones out, and shows the server's sentence if the plan needs
something it was not given.

### Setup follows the server

`GET /api/v1/questions` says what is still open and `answerWith` says where each answer goes
(`PUT /api/v1/spending rent`). The client parses that rather than switching on question keys, so a
question added on the server is answered here without a client release.

### The contract test

`src/api/contract.test.ts` *assigns* every recorded fixture to the type the client reads it at. An
assignment fails when the server drops or renames a field; a cast (`as`) would not, and the difference
was confirmed by adding a field to a type under each. Re-record the fixtures from a live budget-core
with an `X-User-Id` header when the API changes, and let `tsc` say what moved.

One key is known to the client on purpose: `dining-out`, the only category the server prices by kind of
meal (`decisions/AffordPage.tsx`). It is sent, never rendered.
