# Packet rules

You are building one packet of the Financial Balancer in parallel with other agents working on other
packets. These rules exist because multi-agent work fails in specific, predictable ways. Each rule
prevents one of them.

**Read `ARCHITECTURE.md` at the repo root first.** It is the authoritative design record and explains
*why* everything is shaped the way it is. Your packet brief in `.claude/packets/` says *what* to build.

---

## The rules

1. **You only create and edit files inside the packages your brief says you own.** Ownership is
   disjoint across packets. If you believe you need to change a file you do not own, **stop and
   report it** rather than editing it — someone else is working in that file right now.

2. **The P0 contracts are frozen.** The shared interfaces and records (`ResolvedBaseline`,
   `CostOfLivingProvider`, the `surplus` records, `BankDataProvider`, `ConsideredFunds`) are fixed so
   that every packet codes against identical signatures. If you find one genuinely wrong, stop and
   report. Do not "adjust" it — three other agents are mid-flight against the current shape.

3. **`pom.xml` is frozen.** P0 added every dependency all packets need, precisely so that four agents
   do not each edit the same twelve lines. If you need one that is missing, stop and report.

4. **Use only your assigned Flyway version range.** Two agents both writing `V2__…sql` is a merge
   conflict that Flyway then refuses to resolve at runtime. Your range is in your brief.

5. **Enable only your own spec file.** Removing another packet's `@Disabled` makes their unfinished
   work look like your failure.

6. **Done means the named spec tests pass, `mvn verify` is green, and ArchUnit is green.** Not "the
   code is written".

7. **Never weaken a test or an ArchUnit rule to get green.** Those rules encode bugs this codebase
   has already made — rule 3 forbids clock access in the domain because reproducibility depends on
   it, rule 5 forbids raw `BigDecimal` because scale-sensitive equality caused real bugs, rule 8
   protects the allocator's narrow input contract. If a guardrail fires, assume it is right and
   report the conflict.

8. **If your packet covers something the specs do not, write the missing tests.** The 93 specs are a
   floor, not a ceiling. A behaviour you implement that nothing exercises is untested code. New tests
   are held to the same standard: green before you stop, genuinely exercising the behaviour rather
   than merely passing, and named for the requirement rather than for the method.

9. **No test may touch the network.** This machine's DNS drops intermittently, and a suite that fails
   for connectivity reasons stops being trusted within a week. Record fixtures and commit them.

10. **Anything you ask a user for must explain itself.** Never surface an internal term — not
    "discretionary", "floor", "baseline" or "rigidity" — and never a constant name like
    `DINING_OUT`. Every `SpendCategory` carries a `label()` and `covers()` written for a person, and
    `SpendingQuestion` assembles a question, its reason, what it covers, and the basis for any
    suggested default. Derive explanations from that data rather than hand-writing them in a
    template, so they cannot drift when the taxonomy changes. A question the user has to guess at is
    a defect, not a polish item.

11. **Commit messages explain why, not what.** The diff already shows what changed.

12. **The `jobs-tracker-distributed-system` repository is read-only.** Copy files out of it if your
    brief says to; never edit or commit to it.

---

## Working in a worktree

Your packet runs in its own git worktree, alongside three others on the same machine. Two
consequences that are easy to get wrong:

**There is one shared container stack, and it is already running.** Postgres is on **5434** and Redis
on **6380** — non-standard because this machine also runs the jobs-tracker stack (which holds 6379 and
8080) and a native Windows Postgres (which holds 5432). **Do not run `docker compose up` from your
worktree.** Four worktrees starting their own stacks collide on those same ports, and the failure
presents as "no container appeared" rather than as an error. Check what is already running instead:

```bash
wsl docker ps --filter "name=fb-"
```

If you need a database for an integration test, use Testcontainers, which allocates its own random
port and does not touch the shared stack. Pin **postgres:15-alpine**, matching what is shipped.

**`.env` does not exist in your worktree.** It is gitignored, so `git worktree add` does not copy it.
If your packet needs API keys, copy the file across once:

```bash
cp ../newProject/.env .env
```

If that file does not exist yet, ask — do not invent keys, and never commit one.

## Environment

**Docker** is Docker Desktop with the WSL2 backend. The `docker` CLI exists **only while Docker
Desktop is running** — with it stopped, `docker --version` fails from PowerShell, Git Bash *and*
WSL, which looks exactly like Docker not being installed. If that happens, ask for Docker Desktop to
be started rather than concluding it is missing.

**Maven is not on PATH.** There is a distribution at `~/.m2/maven-dist`:

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-25.0.4.7-hotspot"
MVN="$USERPROFILE/.m2/maven-dist/apache-maven-3.9.16/bin/mvn.cmd"
"$MVN" --batch-mode verify
```

`budget-core/mvnw` exists but its bootstrap downloader is unreliable here; prefer the distribution.

**The network drops mid-build.** `No such host is known` is connectivity, not a broken build. Retry
in a loop before debugging a dependency error:

```bash
for i in 1 2 3; do "$MVN" --batch-mode verify && break; sleep 10; done
```

**Spring Boot 4.1 targets Testcontainers 2.x**, which renamed every module to a `testcontainers-`
prefix and relocated container classes into module-specific packages. Nearly every tutorial online is
1.x and copying one produces import errors that look exactly like a network drop. Use
`@ServiceConnection`.

**Python is the Microsoft Store stub**, not a real interpreter. Write tooling in Java.

**Verify CI through the Actions API** (`/actions/runs`), never the rendered Actions page — that page
once reported success for a run that had actually failed.

---

## Test tiers

- **`*Test` → Surefire.** Pure JUnit. No Spring context, no database, no network. Milliseconds.
  Most of your tests belong here.
- **`*IT` → Failsafe, bound to `verify`.** Testcontainers Postgres for migrations and repositories.
  Skipped locally unless `-DskipITs=false`; always run in CI.

## Before you open a PR

```bash
"$MVN" --batch-mode verify
```

- Your named spec behaviours are enabled and green.
- `ArchitectureTest` green, unweakened.
- `GreedyPriorityAllocatorTest` still green — if it broke, a contract was widened and something is
  wrong.
- No test touches the network.
