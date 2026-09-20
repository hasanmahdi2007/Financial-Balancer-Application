# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A goal-based personal budgeting platform. A user supplies their city, income, and savings goals;
the system evaluates their finances against that city's real cost of living and produces a concrete
monthly allocation. Adding a goal ("save for a car in 5 months") recomputes the whole plan and
reports the tradeoffs needed to hit it.

## Build and test

Maven is **not** on PATH. A distribution was downloaded to `~/.m2/maven-dist`. `JAVA_HOME` must be
set explicitly.

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-25.0.4.7-hotspot"
$mvn = "$env:USERPROFILE\.m2\maven-dist\apache-maven-3.9.16\bin\mvn.cmd"

# full suite
Set-Location budget-core; & $mvn --batch-mode test

# one test class
& $mvn --batch-mode test -Dtest=GreedyPriorityAllocatorTest

# one test method
& $mvn --batch-mode test -Dtest=GreedyPriorityAllocatorTest#fundsHigherPriorityGoalsFirst
```

`budget-core/mvnw` exists but its bootstrap downloader has proven unreliable here; prefer the
distribution above.

## Architecture

Distributed, but deliberately not split for its own sake — see `README.md` for the diagram and the
justification for each boundary. Redis Streams is the broker (not Kafka: already proven in the
author's stack and correctly sized for this volume).

- `budget-core` — the domain heart. Internally a modular monolith: `shared`, `profile`,
  `costofliving`, `planning`. Highly relational, so splitting it would buy distributed transactions
  for no benefit.
- `api-gateway` — adapted from the author's `jobs-tracker-distributed-system`. Auth and rate
  limiting, which bank data makes non-optional.
- `transaction-ingestion` — Plaid webhooks, genuinely async, must stay off the request path.
- `analytics-engine` — telemetry (reused as-is) plus spending trends (same Streams → OLAP pattern).
- `ai-enrichment` — hobby-cost estimation; slow and rate-limited, so it is isolated behind the broker.

`budget-core` is built standalone with the gateway placed in front, so the gateway can never become
a build blocker.

## Conventions that matter

**The `planning.domain` package must stay framework-free.** No Spring, no JPA, no I/O, no clock
access — the evaluation date arrives on `AllocationRequest.asOf` so results are reproducible. This
is what makes the engine exhaustively testable and portable, and an ArchUnit rule will enforce it.
`AllocationStrategy` exists so a future `LinearProgrammingAllocator` can be swapped in without
touching callers.

**Never use `double` for currency.** `com.hasan.budget.shared.Money` wraps `BigDecimal` at scale 2.
It normalises scale in its compact constructor, which is what makes the record's generated
`equals()` behave (`BigDecimal.equals` is scale-sensitive, so `1.5` and `1.50` would otherwise
differ). `Money.spreadOver(months)` rounds **up** on purpose: rounding down leaves a goal short of
its target on the final month.

**Goal ranking is priority-descending, then required-amount ascending.** Cheapest-first within a
tier is not an accident — it maximises how many goals stay on pace, which is the stated objective.
Largest-first would starve several small goals to serve one large one. `GreedyPriorityAllocatorTest`
has a test that fails if this is reversed.

## Spring Boot 4 notes

This project is on Boot 4.1.1, which restructured the starters. `spring-boot-starter-web` is now
`spring-boot-starter-webmvc`, there is a dedicated `spring-boot-starter-flyway`, and the single
`spring-boot-starter-test` has been replaced by granular per-feature test starters
(`spring-boot-starter-webmvc-test`, etc.). JUnit and AssertJ are declared directly in `pom.xml` so
the framework-free domain tests do not depend on Spring's test starters at all.

## External data

- **Cost of living** — **curated figures only, for about ten cities.** Hand-researched USD amounts for
  six Lebanese cities and a few US metros, committed as CSV and seeded into Postgres via Flyway,
  behind a `CostOfLivingProvider` port. They are labelled `CROWDSOURCED`, never `OFFICIAL`, because
  they are researched estimates rather than statistics — mislabelling them would make the whole
  confidence model dishonest.

  **Deriving baselines from BLS, BEA and Census is deferred**, not abandoned: it bought one
  user-visible sentence for 2–3 days of work and carried an unvalidated modelling risk. The port, the
  `OFFICIAL` confidence tier and an empty `OFFICIAL` resolver layer all exist so that reviving it is a
  new class rather than a refactor. Full specification, including the reference figures and the
  measurement that must run first, is in `.claude/packets/P9-DEFERRED-official-cost-of-living.md`.
  **`city_category_baseline.income_quintile` must stay nullable** — that one column is what keeps the
  revival free of a migration.
- **Bank data** — Plaid **Sandbox**, test user `user_transactions_dynamic` with any non-blank password
  (**not** `user_good`, which lacks the credit-card and loan accounts needed to exercise the
  credit-card double-count case). Cursor-based `/transactions/sync`, not the legacy
  `/transactions/get`. Note the sign convention: **positive means money leaving the account**, so a
  deposit arrives negative and must be flipped for display. Access tokens are encrypted at rest.

Secrets live in `.env`, which is gitignored. Never commit Plaid or Supabase keys.

## Environment quirks

The network on this machine drops intermittently — DNS alternates between resolving and "No such
host is known." Maven and `winget` both fail mid-download as a result. Retry loops help; a failure
is usually connectivity rather than a real build error, so confirm reachability before debugging a
dependency resolution error.
