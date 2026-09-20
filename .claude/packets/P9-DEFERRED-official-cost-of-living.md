# P9 (DEFERRED) — Official cost-of-living pipeline

> **Not scheduled. Do not build this unless explicitly asked.**
>
> This brief exists so the feature can be picked up cold, months later, by someone with none of the
> context that produced it. Everything needed is here: the sources, the formula, the known problems,
> and the experiment that must run before any of it is trusted.

---

## Why it was deferred

The plan originally had P2 derive city baselines from US government statistics. It was cut before any
of it was written, for reasons that still apply and should be re-checked before reviving it:

1. **It buys one user-visible sentence.** "Your groceries are high for your city." Useful, not
   essential. The core product — income, spending, goals, ranking, tradeoffs — works without it.
2. **It was 2–3 days of the ~12-day plan**, and the only packet whose shape was genuinely unknown.
3. **It carried an unvalidated modelling risk** (below) that could have turned into rework rather
   than tuning.
4. **It can be added later for free**, which is what made deferring safe rather than merely cheap.

Hasan's rule at the time: get the app running first, then decide where sophistication earns its
place.

## Why adding it later is safe

The seams were built for this swap and are already on `main`:

- `costofliving/port/CostOfLivingProvider` is frozen. A BLS/BEA-backed source is **a new class
  implementing it**, nothing more.
- `Confidence.OFFICIAL` already exists in the enum, ranked above `CONTRIBUTED` and `CROWDSOURCED`.
- `BaselineResolver` is an **ordered list of layers**. The official source is appended as one entry.
- `city_category_baseline` already carries `confidence`, `source_id`, `derivation` and
  `income_quintile`.
- No caller changes. `PlanAssembler` receives `ResolvedBaseline` either way.

**The one thing that must have been done right:** `city_category_baseline.income_quintile` must be
**nullable**. Curated rows have no quintile; BLS-derived rows do. If it was made `NOT NULL`, reviving
this needs a schema migration and a backfill. Check that first.

---

## What to build

### The sources

| Need | Source | Access |
|---|---|---|
| National spend per category, **by income quintile** | BLS Consumer Expenditure Survey, via LABSTAT (`CXU…` series) or the published tables | free; key raises quota |
| Metro price localisation | BEA Regional Price Parities — states + ~380 metros, split **rents / goods / other services** | free key, <https://apps.bea.gov/API/signup/> |
| Independent rent anchor for validation | Census ACS table `B25064`, median gross rent | free key |

### The formula

```
localBaseline(category) = BLS_CEX(category, income quintile) x RPP_component(metro) / 100
```

Category to RPP component mapping is already encoded on `SpendCategory.priceComponent()`:
`RENT → RENTS`; `GROCERIES`, `TRANSPORT_FUEL`, `CLOTHING → GOODS`; everything else →
`OTHER_SERVICES`. `DEBT_PAYMENT` and `TAX_RESERVE` have none — they are set by contract or statute,
not by local prices.

**The critical distinction, which a previous design got wrong and nearly shipped:** a cost-of-living
index says what things **cost**, not what you **should spend**. The naive
`housingCap = income x 0.30 x cityIndex/100` recommends 54% of income on rent in San Francisco. The
baseline is a descriptive benchmark and a cap for *flexible* essentials only; fixed commitments are
never capped against it.

### Reference figures (BLS CEX 2024)

Recorded so they need not be re-found:

- Average annual expenditure **$78,535**; average pre-tax income **$104,207**; average consumer unit
  **2.5 people**.
- Shares: housing 33.4%, transportation 17.0%, food 12.9%, personal insurance and pensions 12.5%,
  healthcare 7.9%, entertainment 4.6%, apparel 2.5%, education 2.0%.
- Food away from home **$3,945/yr**; food at home **$6,224/yr**.

---

## Run this experiment first, before building anything on top

For five metros of known different cost (San Francisco and Wichita at the extremes), compare:

```
CEX_housing(quintile 3) x RPP_rents / 100     versus     ACS B25064 median gross rent
```

Within ~15% is defensible. **A 2x gap means the category-to-component mapping is wrong**, and every
downstream number is poisoned silently — nothing crashes, the plan just lies. One afternoon. If it
fails, stop and report rather than proceeding.

## The two known mismatches, which pull in opposite directions

This is the analysis that caused the deferral. Do not re-derive it.

1. **Income basis.** BLS quintiles are defined on *pre-tax household* income; this product collects
   *net personal* income. Comparing a net figure against pre-tax boundaries places many users a tier
   too low, so their baseline reads **LOW**.
2. **Household size.** CEX describes a consumer unit averaging 2.5 people; this product is explicitly
   single-person. That makes the same baseline read **HIGH**.

They partially cancel, so **the net error cannot be reasoned out — it must be measured**, which is
what the ACS experiment above is for. Applying a theoretical correction first risks over-correcting
past the true value.

When correcting, both fixes are **data, never constants in code**:

- **Income basis:** `gross ≈ net / (1 − effectiveRate)`, reusing the per-country effective tax rate
  the resolver already provides. No new user input, and it self-adjusts where income tax is minimal.
- **Household size:** a **per-category** factor. A single global number would be wrong in both
  directions at once — rent scales weakly with the number of people (a one-bedroom is not 40% of a
  three-bedroom) while groceries scale almost linearly. Starting hypothesis only: the OECD-modified
  equivalence scale (first adult 1.0, further adults 0.5, children 0.3) puts a 2.5-person unit near
  1.6 equivalent adults, implying roughly 0.6 for one person. Treat it as something for the ACS check
  to confirm, not as an answer.

## Engineering constraints carried over

- **Ingestion runs offline, by hand.** Never at build time, never at boot. Commit both the raw API
  responses and the generated CSVs, so no build or test ever depends on BLS or BEA being reachable —
  this machine's DNS drops constantly.
- **Materialise `city_category_baseline`** rather than computing per request. Lebanon has no RPP
  equivalent, so a read-time computation grows a branch per country on the hot path. Keep the
  `derivation` column populated (`"CEX(HOUSING,q3) 1842.00 x RPP_RENTS 178.2/100"`) for audit.
- **A scheduled refresh must not poll daily.** BEA, BLS and Census publish **annually**; a daily job
  fetches identical bytes 364 days a year. Default weekly, and no-op when the upstream `as_of` is
  unchanged.
- **A refresh must never overwrite a `USER_PROVIDED` figure.** The user's own number stays top of the
  resolver chain regardless of what arrives upstream.
- **~20 metros is enough**, not 382. The marginal metro adds nothing to a demo.
- Whatever is concluded about the mismatches goes in the README. Visible assumptions read as rigour;
  hidden ones read as naivety.
