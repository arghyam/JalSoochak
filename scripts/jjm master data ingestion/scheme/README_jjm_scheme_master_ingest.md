# JJM master-data ingestion (`jjm_scheme_master_ingest.py`)

Reconciles a JJM master snapshot against a tenant schema and the analytics warehouse.

Two modes, same code path — the analysis is a dry run of the execution, so what
the workbook says is what `--execute` does.

| Mode | Flag | Effect |
| --- | --- | --- |
| analyze | *(default)* | read-only; writes the analysis workbook, rolls back |
| execute | `--execute` | applies everything in two transactions (tenant, analytics) |

## Sources

Two files are supported, and **what a file carries decides what the run has
authority over**. A source that never mentions villages has not *dropped* a
village mapping — it has said nothing about villages — so its absence switches
that half of the run off entirely, writes and deletions together.

| Source | Flag | Carries | Run touches |
| --- | --- | --- | --- |
| `all_ascheme_exist.xlsx` | `--excel` | locations + users | everything |
| `schemes-master-data.csv` | `--csv` | scheme columns + `public_id` | scheme attributes, `state_scheme_code`, legacy schemes |

Required in both: `scheme_name`, `imis_id`, `smt_id`, `work_status`,
`operating_status`, `planned_fhtc_imis`, `provided_fhtc_imis`, `latitude`,
`longitude`. Everything else is optional; the run logs which optional columns
are absent and prints the resulting authority as
`public_id=…, villages=…, sub_divisions=…, users=…`.

`--skip-users` forces the user half off even for a file that carries it.
Headers are on **row 2** in both formats (`--header-row` to change).

## What it writes

**Tenant DB**, schema `tenant_<code>`:

| Table | Operation |
| --- | --- |
| `scheme_master_table` | insert / update / **revive** / **retire** |
| `user_table` | insert / update / **revive** (`PUMP_OPERATOR`, `SECTION_OFFICER`) |
| `user_scheme_mapping_table` | insert / **revive** / **retire** |
| `scheme_lgd_mapping_table` | insert / **revive** / **retire** (village) |
| `scheme_department_mapping_table` | insert / **revive** / **retire** (sub-division) |

**Analytics DB**, schema `analytics_schema`:

| Table | Operation |
| --- | --- |
| `dim_scheme_table` | upsert one row per scheme × village × sub-division, **plus an attribute sync across every row of a scheme** |
| `dim_user_table` | upsert |
| `dim_user_scheme_mapping_table` | delete-then-insert per touched user, from the tenant DB's post-state |

## Idempotence

**A second identical run writes nothing, and a run after a retirement restores
rather than duplicates.** Every lookup that decides "does this already exist?"
reads soft-deleted rows too:

- **schemes** — matched on the id pair across live *and* retired rows. A match
  found only among the retired rows is category `REVIVED_SOFT_DELETED_SCHEME`:
  the row is un-deleted, keeping its id, its readings and its mappings. (Before
  this, the index filtered `deleted_at IS NULL`, so every scheme a retirement
  removed was re-inserted on the next run.)
- **users** — matched on `phone_number_hash` across live *and* retired rows; a
  soft-deleted user is revived, never onboarded a second time under a new id.
- **all three mapping tables** — a reconciler diffs the desired set against
  *every physical row* the table holds:
  1. a pair the snapshot states ends with exactly one live, usable row — the
     **earliest** retired row is revived rather than a new one inserted, so the
     pair keeps its original `created_at`;
  2. a pair already holding several live rows is **collapsed to one** — reported
     under its own reason so it is never mistaken for a real removal;
  3. only then is a contradicted row retired, and only where this source has
     the standing to judge it.

Rules 1 and 2 apply with or without `--replace`: a run that declined to revive,
or that left duplicates standing, would be the run that created them.

## Legacy data (`--replace`)

The snapshot is the complete current truth for everything it speaks for.

- A **live scheme whose ids appear nowhere in the snapshot** is retired:
  `is_active = FALSE` **and** `deleted_at` set. Both are needed —
  scheme-service's `SchemeActivitySyncScheduler` recomputes `is_active` from
  recent readings on a timer and would flip it back, but it skips rows whose
  `deleted_at` is set. Its village, sub-division and user mappings are retired
  with it, and its `dim_scheme_table` rows drop to `operating_status = 0`.
- **A scheme is spared, loudly, when it still has a flow reading inside
  `--reading-window-days` (default 90).** Data is arriving for it, so the
  snapshot is out of date, not the scheme; retiring it would break the
  operator's uploads and orphan facts already in the warehouse. These get their
  own bucket in `legacy_summary` and their own sheet — they are the rows a human
  has to reconcile by hand.
- Mappings the snapshot contradicts are retired, **scoped** to the schemes the
  snapshot names and the roles/columns the file carries. A scheme whose village
  name simply failed to resolve keeps its mapping; a scheme whose own person
  could not be written keeps whoever covers it, rather than being left with
  nobody.

Retirement is **opt-in**, but the legacy distribution is computed and reported
on *every* run. `--replace` is refused with `--limit`: retirement decides what to
delete from what the file does *not* contain, so it needs the whole file.

`dim_scheme_table` rows are never deleted — `fact_water_quantity_table`,
`fact_meter_reading_table`, `fact_scheme_performance_table` and
`dim_operator_attendance_table` all carry a foreign key to
`(tenant_id, scheme_id)`, and a scheme retired for having no *recent* readings
can still have years of older facts.

## dim_scheme drift

`dim_scheme_table` holds one row per scheme × village × sub-division, and each
row carries its own copy of the scheme's name, ids, coordinates, statuses and
FHTC counts. The upsert only reaches the combinations the snapshot reproduces,
so every other row of that scheme keeps whatever it was last given — and the
same scheme starts reporting two different names depending on which row a query
groups by.

After the upsert the run pushes each scheme's post-state onto **all** of its
rows, matching on `(tenant_id, scheme_id)` alone. Location columns are pointedly
not touched; overwriting them from one row's location would destroy the fan-out.
An `IS DISTINCT FROM` guard means `dim_scheme_rows_realigned` counts rows that
were genuinely carrying drift, and `dim_scheme_drift` in the workbook lists them
before the fact.

The warehouse is read in analyze mode too (pass `--analytics-dsn`), because that
drift only exists there.

## Scheme matching contract

Evaluated in order; the first rule that fires wins.

**Rule 1 — the pair.** Exactly one live scheme matches both `imis_id` and
`smt_id` → update it. This wins *even when either id on its own also matches
other schemes*, because the pair is unique.

**Rule 2 — single ids**, over live rows:

| Situation | Category | Action |
| --- | --- | --- |
| centre matches 1, `smt_id` unused anywhere | `CENTRE_MATCH_STATE_ID_UNKNOWN` | update, adopt `smt_id` |
| `imis_id` unused anywhere, state matches 1 | `STATE_MATCH_CENTRE_ID_UNKNOWN` | update, adopt `imis_id` |
| centre → scheme X, state → different scheme Y | `CONFLICT_IDS_POINT_TO_DIFFERENT_SCHEMES` | **skip** |
| either id matches >1 scheme, pair does not resolve | `AMBIGUOUS_ID_MATCHES_MULTIPLE_SCHEMES` | **skip** |
| unusable row (blank name / both ids / `work_status`, or an id repeated *inside the source*) | `INVALID_SHEET_ROW` | **skip** |

**Rule 3 — the retired index.** No live scheme carries either id, but a
soft-deleted one does → `REVIVED_SOFT_DELETED_SCHEME`: revive it. Rules 1 and 2
again, applied to the retired rows; a live row always outranks a retired one
carrying the same id.

**Rule 4.** Neither id found anywhere, live or retired → `NEW_SCHEME`, insert.

### Columns written on a match

`scheme_name`, `planned_fhtc` ← `planned_fhtc_imis`, `fhtc_count` ←
`provided_fhtc_imis`, `work_status`, `operating_status`, `state_scheme_code` ←
`public_id`. Latitude/longitude are **only backfilled when ours are missing** —
the source never overwrites coordinates we already hold. A matched scheme whose
columns already agree is not written at all.

`work_status` and `operating_status` use the same maps as `SchemeServiceImpl`;
the sheet's hyphenated spellings (`handed-over`, `partially-operative`) fold to
the app's spacing. A blank `operating_status` leaves the existing value alone on
an update and defaults to `1` only on an insert. A blank `work_status` leaves the
existing value alone on an update and blocks an insert (the column is `NOT NULL`).

### `public_id` → `state_scheme_code`

The CSV export carries a public scheme code (`SCH-034035`), distinct from both
ids we already hold:

| Column | Source |
| --- | --- |
| `centre_scheme_id` | IMIS id, central system |
| `state_scheme_id` | SMT id, state system |
| `state_scheme_code` | the state system's **public code** |

Stored by `backend/database/V38__add_state_scheme_code_to_scheme_master_table.sql`,
which also puts a partial `UNIQUE` index on it (live rows only). The script
therefore refuses to write a code another live scheme owns, or one the source
itself repeats: that row keeps everything else and only loses that column, with
the reason in `scheme_detail.public_id_blocked`. Against a database that has not
taken V38 the column is neither read nor written and the run warns once.

It is **stored, not matched on** — the matching contract above is unchanged.

## Location mapping contract

A mapping is written **only when the name resolves to exactly one location**.
When several locations share a name, the sheet's hierarchy columns disambiguate:

- village → `panchayat_name` > `blocks` > `district`
- sub-division → `division` > `circle` > `zone`

Each filter is kept only if it leaves at least one candidate, so a wrong ancestor
name degrades to `ambiguous` instead of silently selecting the wrong location.
Anything still ambiguous is reported and left unwritten — nothing is guessed.

## Users

Identity is the **phone number**, normalised to the `91XXXXXXXXXX` form the DB
stores (`PhoneNumberUtil.normalizeIndianMobileForDb`). Lookup goes through
`phone_number_hash` because the column itself is encrypted.

- Existing user, same role, different name → name updated.
- Existing user, **soft-deleted** → revived and renamed, never duplicated.
- Existing user, **different role** → skipped, never downgraded (mirrors
  `PumpOperatorUploadChunkProcessor`).
- New user → created exactly as the app's bulk upload does: generated email
  `po_/so_<phone>@pump-operator.local`, password literal `CSV_ONBOARDED`, active.

`jalmitras`/`jalmitra_phone` and `so_name`/`so_phone` are comma-separated and
paired **positionally**. If the two cells disagree in length the whole cell is
dropped and reported — mispairing would attach one person's name to another's
phone number.

People whose every row was skipped get no mapping, so by default they are
reported but **not created**. Pass `--create-orphan-users` to onboard them anyway.

## Prerequisites

```bash
pip install pandas openpyxl psycopg2-binary cryptography
```

The tenant `user_table` stores `title` and `phone_number` AES-256-GCM encrypted
and is searched by HMAC-SHA256, so the script needs the **target environment's**
keys:

```bash
export PII_ENCRYPTION_KEY=...      # base64, 32 bytes
export PII_HMAC_KEY=...            # base64, 32 bytes
export TENANT_DSN='postgresql://user:pw@host:5432/shared_db'
export ANALYTICS_DSN='postgresql://user:pw@host:5432/analytics'
```

Using the wrong keys silently fails to match existing users and would create
duplicates, so verify them against the environment you are targeting.

Preconditions the script checks and fails loudly on:

- `common_schema.user_type_master_table` must contain `PUMP_OPERATOR` and
  `SECTION_OFFICER` (only when the source carries users).
- `--actor-id` must be a live `tenant_<code>.user_table.id` — both mapping tables
  have a real foreign key to it from `created_by`/`updated_by`.
- `analytics_schema.dim_tenant_table` must hold the tenant id.

## Running

```bash
# 1. dry run — produces the analysis workbook, writes nothing
python3 "scripts/jjm master data ingestion/scheme/jjm_scheme_master_ingest.py" \
    --csv "scripts/jjm master data ingestion/scheme/schemes-master-data.csv" --actor-id 21357 \
    --out "scripts/jjm master data ingestion/scheme/jjm_scheme_analysis_new.xlsx" --replace

# 2. rehearse on a slice (no --replace: a slice is not a snapshot)
python3 jjm_scheme_master_ingest.py ... --limit 500 --execute

# 3. apply
python3 jjm_scheme_master_ingest.py ... --execute

# 4. apply and retire everything the snapshot has dropped
python3 jjm_scheme_master_ingest.py ... --execute --replace
```

Useful flags: `--schema` (default `tenant_as`), `--tenant-id` (else resolved from
the schema's state code), `--sheet`, `--header-row` (default 2), `--encoding`,
`--replace`, `--reading-window-days` (default 90), `--skip-users`,
`--skip-analytics`, `--create-orphan-users`, `--include-pii`, `--limit`, `-v`.

## Run time and locking

Every write is batched, so the run costs a few hundred network round trips
regardless of row count. Estimate the round-trip cost for your host with:

```bash
python3 -c "
import time,os,psycopg2
c=psycopg2.connect(os.environ['TENANT_DSN']);cur=c.cursor()
t=time.time()
for _ in range(1000): cur.execute('SELECT 1'); cur.fetchone()
print(f'RTT {(time.time()-t):.2f} ms/query')"
```

The tenant side is a single transaction, so every lock it takes is held until the
final commit:

- **Table level** it takes only `ROW EXCLUSIVE`, which does *not* block app
  `SELECT`/`INSERT`/`UPDATE`/`DELETE` — only DDL, non-concurrent `CREATE INDEX`
  and `VACUUM FULL`. Reads are never blocked.
- **Row level** it holds ~25k scheme rows and ~1.4k user rows exclusively. A
  concurrent app write *to one of those rows* waits for the commit.
- **Analytics** is the real contention point: `replace_user_scheme_mappings`
  deletes and reinserts mappings for ~22k users, and `DimensionServiceImpl` in
  analytics-service does the same thing from the Kafka consumer. Pause that
  consumer for the run, or apply `--skip-analytics` first and do the warehouse
  leg separately.

Afterwards run `VACUUM ANALYZE` on `tenant_<code>.scheme_master_table` — the
updates leave dead tuples that autovacuum could not reclaim while the
transaction was open.

## Tests

```bash
# needs a throwaway PostgreSQL 15+ (UNIQUE NULLS NOT DISTINCT); the suite
# creates and drops its own schemas
docker run -d --rm --name jjm-test-pg -p 55432:5432 \
    -e POSTGRES_DB=shared_db -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=testpw \
    postgres:16-alpine

JJM_TEST_DSN='postgresql://postgres:testpw@localhost:55432/shared_db' \
    python3 -m pytest "scripts/jjm master data ingestion/scheme/test_jjm_scheme_master_ingest.py" -v
```

Covers the batched write paths (including a per-row reference implementation the
batched `update_schemes` is asserted to match exactly), the reconciler's three
rules, the revive-vs-insert classification, the reading-window guard, the
`state_scheme_code` uniqueness handling, the attribute sync, source loading for
both formats, and whole-plan idempotence — applying a plan twice must leave every
table's physical row count unchanged.

## Analysis workbook

| Sheet | Contents |
| --- | --- |
| `run_info` | settings the run used, including what the source supplies |
| `scheme_summary` | **the headline** — rows per match category, public-code outcomes, and the legacy block |
| `legacy_summary` | schemes absent from the snapshot, bucketed by reading recency |
| `mapping_summary` | per mapping table: inserts, revivals, and retirements by reason |
| `analytics_summary` | dim rows written, the fan-out that can drift, and how much of it had |
| `user_summary` | people per role × action, plus mapping revivals and retirements |
| `location_summary` | village / sub-division resolution outcomes |
| `legacy_schemes` | every absent scheme with its reading counts and last reading date |
| `mapping_removals` | every row to be retired, with the reason and who held it |
| `dim_scheme_drift` | warehouse rows out of sync with the post-state, column by column |
| `scheme_conflicts` | every skipped conflict/ambiguous row, showing both schemes it collides with |
| `sheet_issues` | per-row data problems (blank status, bad lat/long, name/phone mismatches) |
| `location_unresolved` | every village / sub-division that could not be resolved, with the reason |
| `user_conflicts` | role clashes and same-phone-different-name cases |
| `user_detail` | per-person plan |
| `scheme_detail` | per-row classification, reason, exact column diff, public-code verdict |

Phone numbers are **masked** (`91XXXXXXXX01`) unless `--include-pii` is passed.

## Safety properties

- **Idempotent** — a second identical run inserts, updates and retires nothing,
  and no table gains a physical row (verified against the full 27,662-row CSV).
- **Transactional** — tenant and analytics each commit once, at the end; any
  failure rolls both back.
- **Deletion is soft and opt-in** — `--replace` only, never a hard `DELETE` on a
  tenant table, and never for a scheme still receiving readings.
- **Scoped** — a source only prunes what it speaks for: no village column, no
  village deletions; jalmitras only, no section-officer deletions.
- Skipped rows are always *reported*, never silently dropped.

## Notes on the sources

`schemes-master-data.csv` — 27,662 rows, 27,619 usable:

- 43 rows are unusable (blank `work_status`, blank `imis_id`, or an id repeated
  inside the file).
- 35 `imis_id` values and 1 `smt_id` appear twice; those rows are skipped, as an
  id repeated in the source cannot be matched 1:1.
- All 27,662 `public_id` values are distinct and non-null.
- No village, sub-division or user columns at all — a run against it touches
  scheme attributes and legacy schemes only.

`all_ascheme_exist.xlsx` — 27,664 rows, carrying locations and ~21.9k distinct
people (≈21.2k jalmitras, 649 section officers); no phone number is used for
both roles. It has no `public_id` column.

Fix problems in the source and re-run — the script is idempotent, so re-running
after a correction only applies the difference.
