# JJM master-data ingestion (`jjm_scheme_master_ingest.py`)

Reconciles the JJM master sheet (`scripts/jjm master data/all_ascheme_exist.xlsx`,
headers on **row 2**) against a tenant schema and the analytics warehouse.

Two modes, same code path — the analysis is a dry run of the execution, so what
the workbook says is what `--execute` does.

| Mode | Flag | Effect |
| --- | --- | --- |
| analyze | *(default)* | read-only; writes the analysis workbook, rolls back |
| execute | `--execute` | applies everything in two transactions (tenant, analytics) |

## What it writes

**Tenant DB**, schema `tenant_<code>`:

| Table | Operation |
| --- | --- |
| `scheme_master_table` | insert / update |
| `user_table` | insert / update (`PUMP_OPERATOR`, `SECTION_OFFICER`) |
| `user_scheme_mapping_table` | insert — **additive, never deletes** |
| `scheme_lgd_mapping_table` | insert (village), only when unambiguously resolved |
| `scheme_department_mapping_table` | insert (sub-division), only when unambiguously resolved |

**Analytics DB**, schema `analytics_schema`:

| Table | Operation |
| --- | --- |
| `dim_scheme_table` | upsert, one row per scheme × village × sub-division |
| `dim_user_table` | upsert |
| `dim_user_scheme_mapping_table` | delete-then-insert per touched user, from the tenant DB's post-state |

## Scheme matching contract

Evaluated in order; the first rule that fires wins.

1. **Exactly one scheme matches both `imis_id` and `smt_id`** → update it.
   This wins *even when either id on its own also matches other schemes*, because
   the pair is unique.
2. Otherwise fall back to single-id matching, where any multiplicity is unresolvable:

| Situation | Category | Action |
| --- | --- | --- |
| centre matches 1, `smt_id` unused anywhere | `CENTRE_MATCH_STATE_ID_UNKNOWN` | update, adopt `smt_id` |
| `imis_id` unused anywhere, state matches 1 | `STATE_MATCH_CENTRE_ID_UNKNOWN` | update, adopt `imis_id` |
| centre → scheme X, state → different scheme Y | `CONFLICT_IDS_POINT_TO_DIFFERENT_SCHEMES` | **skip** |
| either id matches >1 scheme, pair does not resolve | `AMBIGUOUS_ID_MATCHES_MULTIPLE_SCHEMES` | **skip** |
| neither id found | `NEW_SCHEME` | insert |
| unusable row (blank name / both ids / `work_status`, or an id repeated *inside the sheet*) | `INVALID_SHEET_ROW` | **skip** |

### Columns written on a match

`scheme_name`, `planned_fhtc` ← `planned_fhtc_imis`, `fhtc_count` ← `provided_fhtc_imis`,
`work_status`, `operating_status`. Latitude/longitude are **only backfilled when
ours are missing** — the sheet never overwrites coordinates we already hold.
A matched scheme whose columns already agree is not written at all.

`work_status` and `operating_status` use the same maps as `SchemeServiceImpl`; the
sheet's hyphenated spellings (`handed-over`, `partially-operative`) are folded to the
app's spacing. A blank `operating_status` defaults to `1` (operative), as the app does.
A blank `work_status` makes the row unusable (the column is `NOT NULL`).

## Location mapping contract

A mapping is written **only when the name resolves to exactly one location**.
When several locations share a name, the sheet's hierarchy columns disambiguate:

- village → `panchayat_name` > `blocks` > `district`
- sub-division → `division` > `circle` > `zone`

Each filter is kept only if it leaves at least one candidate, so a wrong ancestor
name in the sheet degrades to `ambiguous` instead of silently selecting the wrong
location. Anything still ambiguous is reported and left unwritten — nothing is guessed.

## Users

Identity is the **phone number**, normalised to the `91XXXXXXXXXX` form the DB stores
(`PhoneNumberUtil.normalizeIndianMobileForDb`). Lookup goes through
`phone_number_hash` because the column itself is encrypted.

- Existing user, same role, different name → name updated.
- Existing user, **different role** → skipped, never downgraded (mirrors
  `PumpOperatorUploadChunkProcessor`).
- New user → created exactly as the app's bulk upload does: generated email
  `po_/so_<phone>@pump-operator.local`, password literal `CSV_ONBOARDED`, status active.
- Mappings are **additive** — a scheme the person is already mapped to is left alone,
  and mappings for other schemes are never removed.

`jalmitras`/`jalmitra_phone` and `so_name`/`so_phone` are comma-separated and paired
**positionally**. If the two cells disagree in length the whole cell is dropped and
reported — mispairing would attach one person's name to another's phone number.

People whose every sheet row was skipped get no mapping, so by default they are
reported but **not created** (an account with no schemes cannot do anything).
Pass `--create-orphan-users` to onboard them anyway.

## Prerequisites

```bash
pip install pandas openpyxl psycopg2-binary cryptography
```

The tenant `user_table` stores `title` and `phone_number` AES-256-GCM encrypted and
is searched by HMAC-SHA256, so the script needs the **target environment's** keys:

```bash
export PII_ENCRYPTION_KEY=...      # base64, 32 bytes
export PII_HMAC_KEY=...            # base64, 32 bytes
export TENANT_DSN='postgresql://user:pw@host:5432/shared_db'
export ANALYTICS_DSN='postgresql://user:pw@host:5432/analytics'
```

Using the wrong keys silently fails to match existing users and would create
duplicates, so verify them against the environment you are targeting.

Two more preconditions the script checks and fails loudly on:

- `common_schema.user_type_master_table` must contain `PUMP_OPERATOR` and
  `SECTION_OFFICER`. (Note: some dev databases only have `SUPER_USER`, `STATE_ADMIN`,
  `SECTION_OFFICER`.)
- `--actor-id` must be a live `tenant_<code>.user_table.id` — both mapping tables
  have a real foreign key to it from `created_by`/`updated_by`.
- `analytics_schema.dim_tenant_table` must hold the tenant id.

## Running

```bash
# 1. dry run — produces the analysis workbook, writes nothing
python3 scripts/jjm_scheme_master_ingest.py \
    --excel "scripts/jjm master data/all_ascheme_exist.xlsx" \
    --actor-id <tenant user id> \
    --out jjm_analysis.xlsx

# 2. rehearse on a slice
python3 scripts/jjm_scheme_master_ingest.py ... --limit 500 --execute

# 3. apply
python3 scripts/jjm_scheme_master_ingest.py ... --execute
```

Useful flags: `--schema` (default `tenant_as`), `--tenant-id` (else resolved from the
schema's state code), `--sheet`, `--header-row` (default 2), `--skip-analytics`,
`--include-pii`, `--limit`, `-v`.

## Run time and locking

Every write is batched, so the run costs roughly **400–500 network round trips**
regardless of row count — on the current sheet that is ~2 minutes even over a
60 ms link, most of it spent reading the workbook and writing the analysis.
Estimate it for your own host with:

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
updates leave ~25k dead tuples that autovacuum could not reclaim while the
transaction was open.

## Tests

```bash
# needs a throwaway PostgreSQL; the suite creates and drops its own schemas
docker run -d --rm --name jjm-test-pg -p 55432:5432 \
    -e POSTGRES_DB=shared_db -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=testpw \
    postgres:16-alpine

JJM_TEST_DSN='postgresql://postgres:testpw@localhost:55432/shared_db' \
    python3 -m pytest scripts/test_jjm_scheme_master_ingest.py -v
```

`test_jjm_scheme_master_ingest.py` covers the two batched write paths, including a
per-row reference implementation that the batched `update_schemes` is asserted
to match exactly.

## Analysis workbook

| Sheet | Contents |
| --- | --- |
| `run_info` | settings the run used |
| `scheme_summary` | **the headline** — rows per match category, plus how many matched rows need no write |
| `analytics_summary` | dim rows to be written, and schemes the warehouse cannot take |
| `user_summary` | people per role × action, and new mapping count |
| `location_summary` | village / sub-division resolution outcomes |
| `scheme_conflicts` | every skipped conflict/ambiguous row, showing both schemes it collides with |
| `sheet_issues` | per-row data problems (blank status, bad lat/long, name/phone mismatches, invalid phones) |
| `location_unresolved` | every village / sub-division that could not be resolved, with the reason |
| `user_conflicts` | role clashes and same-phone-different-name cases |
| `user_detail` | per-person plan |
| `scheme_detail` | per-row classification, reason, and the exact column diff |

Phone numbers are **masked** (`91XXXXXXXX01`) unless `--include-pii` is passed.

## Safety properties

- **Idempotent** — a second identical run inserts and updates nothing
  (verified: 0 inserts / 0 updates / 0 new mappings on re-run).
- **Transactional** — tenant and analytics each commit once, at the end; any failure
  rolls both back.
- Never deletes tenant rows; the only delete is the per-user
  `dim_user_scheme_mapping_table` refresh, which the analytics consumer does the same way.
- Skipped rows are always *reported*, never silently dropped.

## Notes on the current sheet (27,664 rows)

Findings from a dry run, all reported in the workbook:

- 21 rows with blank `work_status`, 10 more with a blank `imis_id` → unusable.
- 5 `imis_id` values appear on two rows each, and one `smt_id` on two rows —
  those 12 rows are skipped, as an id repeated in the sheet cannot be matched 1:1.
- 43 rows where the jalmitra name and phone cells disagree in length; 7 invalid
  jalmitra phone numbers.
- 5 rows with a latitude or longitude that is not a usable pair.
- ~21.9k distinct people (≈21.2k jalmitras, 649 section officers); no phone number
  is used for both roles.

Fix these in the sheet and re-run — the script is idempotent, so re-running after a
correction only applies the difference.
