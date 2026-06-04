# Scheme Regularity — How It Works (and What We Changed)

This note explains, in plain terms, how "regularity" is calculated in the
**analytics-service**, the problems with the old approach, the new approach we
moved to, and the things to watch out for. Keep this handy when touching any
regularity API or query.

Code lives in:
- `analytics-service/.../repository/SchemeRegularityRepository.java` (the SQL)
- `analytics-service/.../service/serviceImpl/SchemeRegularityServiceImpl.java` (the maths on top of the SQL)

---

## 1. What "regularity" is supposed to mean

A *scheme* supplies water. We want a single number per region (block / district /
state / nation) that says **how reliably its schemes deliver water** over a chosen
date range.

A **"supply day"** = one calendar day on which a scheme had at least one meter
reading with `confirmed_reading > 0` (i.e. water actually flowed that day).

---

## 2. OLD formula (before this change)

Regularity was the **average fraction of days that schemes supplied water**:

```
regularity = (sum of supply days across all schemes) / (number of schemes × days in range)
```

Example: 5 schemes, 10-day window, together they had 35 supply days
→ 35 / (5 × 10) = 0.70 → "70% regular".

So one scheme that supplied 7 of 10 days contributed **7 supply days** to the
total. Partial credit. The number degrades gracefully.

### Problems with the old formula
- **Day-weighted, so big/active schemes dominate** a district's number (a kind of
  Simpson's paradox). Each scheme is *not* weighted equally.
- Mixes "water actually supplied" with "did the operator submit a reading" — a
  missing submission looks the same as a real outage.
- Schemes with **zero readings still sit in the denominator**, dragging the score
  down (this is intentional-ish, but worth knowing).
- `confirmed_reading > 0` is a rough proxy for "supplied today" — a meter reading
  is often cumulative, so a positive value doesn't strictly prove water moved that
  day.

---

## 3. NEW formula (what we implemented)

We dropped the idea of partial "supply days". Now regularity is decided
**per scheme as all-or-nothing**:

```
For ONE scheme:
    regular = 1  if it supplied water on EVERY day in the range
    regular = 0  otherwise
```

And for a region it becomes the **share of schemes that were fully regular**:

```
regularity = (number of fully-regular schemes) / (total number of schemes)
```

Example: 5 schemes, 10-day window. Only 2 schemes supplied water on all 10 days
→ 2 / 5 = 0.40 → "40% of schemes are regular".

The same scheme that supplied 7 of 10 days now contributes **0** — it was not
regular.

### Configurable threshold
"Every day" (100%) is the default, but it is **configurable** so we can later
soften it (e.g. "≥ 90% of days counts as regular") without another code change:

```
analytics.regularity.full-supply-threshold = 1.0   # default = 100%
```

A scheme is counted as regular when:

```
its supply days  >=  ceil( threshold × days_in_range )
```

- `1.0` → must supply every single day (the behaviour requested).
- `0.9` → must supply at least 90% of the days.

Set it via env/property `ANALYTICS_REGULARITY_FULL_SUPPLY_THRESHOLD` (or the YAML
key above).

---

## 4. Old vs New — the key differences

| Aspect | OLD | NEW |
|---|---|---|
| Per-scheme value | fraction 0.0–1.0 (e.g. 0.7) | binary 0 or 1 |
| Region value means | avg fraction of days supplied | share of fully-regular schemes |
| Partial credit | yes (7/10 days = 0.7) | no (7/10 days = 0) |
| Weighting | per-day (big schemes dominate) | per-scheme (each scheme equal) |
| Behaviour over long ranges | degrades smoothly | trends toward 0 (few schemes are perfect for months) |
| Sensitivity | tolerant | brittle — one missed day zeroes a scheme |

**Important:** the API field names did **not** change. `averageRegularity` is
still returned, but its *meaning* is now "share of fully-regular schemes". The UI
must be told, because the numbers will look different (usually lower).

`totalSupplyDays` is still returned for context (how many supply days happened),
but it is **no longer** what drives `averageRegularity`.

---

## 5. Known issues / things to watch (READ THIS)

### 5a. Denominator = raw calendar days — HIGHLY AFFECTED FIELD ⚠️
We kept the denominator as the **full calendar range** (`days_in_range`), exactly
as before. This means:
- A scheme onboarded **in the middle** of the range can never be "regular" — it
  literally did not exist on the earlier days, so it can't have supplied water
  then. It will always score 0 until a full clean window passes.
- After any onboarding drive, regularity will look artificially low.

If this becomes a problem, the fix is to measure each scheme against the days it
was **expected/active** (using a commissioning/created date), instead of raw
calendar days. We chose **not** to do this now — but it is the single biggest
lever on the reported number, so flag it before trusting cross-period comparisons.

### 5b. "Supply day" still = `confirmed_reading > 0`
Same proxy as before. Under all-or-nothing it bites harder: one day with a `0`
reading, a missing submission, or a genuine outage **all** drop the scheme to 0.
Regularity is therefore partly a *reporting-discipline* measure, not purely a
water-reliability measure.

### 5c. Submission rate is unchanged
The reading-submission-rate metric (`confirmed_reading >= 0`) was **left as the
old fractional formula** on purpose. Only regularity became binary.

### 5d. Cache versions bumped
Regularity responses are Redis-cached (24h TTL) with versioned keys. We bumped the
version suffixes so old-formula values are not served after deploy. If you change
the formula again, bump them again.

---

## 6. Where the change lives (for the next person)

- **Threshold + helper:** `SchemeRegularityRepository` (`requiredSupplyDays(...)`)
  and a matching `@Value` in `SchemeRegularityServiceImpl` for the per-scheme list.
- **SQL:** every regularity query now also returns `regular_scheme_count`
  (`COUNT(*) FILTER (WHERE supply_days >= requiredDays)`), alongside the old
  `total_supply_days`. Covers: single region (LGD + department), child regions,
  state-wise, level-2/district, and periodic.
- **Maths:** the service divides `regularSchemeCount / schemeCount` instead of
  `totalSupplyDays / (schemeCount × daysInRange)`. Pooled rollups (child/state/
  nation) sum `regularSchemeCount` and `schemeCount` then divide.
- **Per-scheme list:** each scheme's `averageRegularity` is `1` if its supply days
  meet the threshold else `0`; its `submissionRate` stays fractional.
