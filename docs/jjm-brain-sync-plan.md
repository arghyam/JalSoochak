# JJM Brain → JalSoochak master-data sync

**Sources** (none of them is in this repo)
- `JJM_Brain_Arghyam_API_Documentation_v1.pdf` — v1.0, September 2026 (12 endpoints)
- `API_Review_Issues_and_suggesions.pdf` — the Assam team's answers to our review
- `docs/jjm-brain-api-gap-analysis.md` — our August review of the earlier draft

**Status:** implemented in `scheme-service` (package `statesync`), behind `STATE_SYNC_ENABLED` (off by
default). Everything that did not depend on an answer from Assam is built; where an answer is still
outstanding, the code takes the conservative choice and records an issue instead of guessing (§6).

`state_scheme_id` **is the SMT id** (confirmed). It is stored as a string, so its size is unbounded.

---

## 1. How it syncs

| Trigger | What it pulls | When | Why |
| --- | --- | --- | --- |
| **Delta** | `/schemes?updated_since=<last APPLY watermark − 30 min>` + up to 25 lenient-ingestion placeholders by IMIS id + `/blocked-users` | `STATE_SYNC_DELTA_CRON`, default hourly at :15 | `updated_at` moves on SO, SDO, Jal Mitra and village changes (confirmed). `updated_at` is IST and `updated_since` is inclusive (confirmed); the overlap only re-reads. The placeholder lookup turns a reading for an unknown scheme into a real scheme within an hour. |
| **Full** | All 8 masters, `/user-master`, every scheme, `/archived-schemes`, `/blocked-users` | `STATE_SYNC_FULL_CRON`, default 02:00 IST | Masters and users have no `updated_since`. EE and SDO reassignments do not move a scheme's `updated_at`. Archives and blocks only show up when the whole list is read. |
| **Scheme refresh** | `/schemes?code=` or `?centre_scheme_id=`, + `/blocked-users` | `POST /api/v1/scheme/state-sync/schemes/refresh` | Support tickets |
| **Manual run** | FULL or DELTA | `POST /api/v1/scheme/state-sync/runs` | Onboarding, cut-over |

Every run:
1. **Claims the lock.** It inserts a RUNNING row into `common_schema.state_sync_run_table`. A partial
   unique index allows one RUNNING row per tenant, so with N replicas exactly one runs and the rest
   skip. If a pod crashes, its claim is taken over once the heartbeat is older than
   `STATE_SYNC_STALE_RUN_AFTER` (default 2 h). A delta that fires during the nightly full run skips.
2. **Fetches everything** before touching the database.
3. **Reconciles in one transaction.** In `DRY_RUN` that transaction is rolled back.
4. **Publishes analytics events** after commit, in `APPLY` mode only: `SCHEME_MAPPINGS_REPLACED`,
   `SCHEME_READINGS_REASSIGNED`, `DEPARTMENT_LOCATION_UPDATED`, `USER_CREATED`/`USER_UPDATED`,
   `USER_SCHEME_MAPPINGS_REPLACED` (see §2a).
5. **Finishes the run row** with its counts, and writes its issues to
   `common_schema.state_sync_issue_table`. This happens in both modes.

## 2. What it writes

| Upstream | Our table | Rule |
| --- | --- | --- |
| Zone / circle / division / subdivision | `department_location_master_table` levels 2–5 | Matched by `state_dept_id`, else by name under the same parent (ignoring suffixes like "Division"), else by name alone. Unmatched nodes are **inserted**. A node whose parent differs is **re-parented**. Matched titles are left alone, because uploads still resolve departments by title. |
| District / block / panchayat / village | `lgd_location_master_table` levels 2–5 | **Matched only**: by `state_lgd_id`, else by name under the matched parent (districts may match by name alone). Upstream has no national `lgd_code`, so an unmatched node is reported (`LGD_UNMATCHED`), never created. A parent disagreement is reported, not applied. |
| User master + officers embedded in schemes | `user_table` | Allow-list: jal-mitra, section-officer, sdo, executive-engineer. Matched by `state_user_id`, else by phone hash (`91XXXXXXXXXX`). On a match: name updated, code stamped, role updated, except that admin accounts are never re-typed and a promotion to EE is withheld. New users are created encrypted, with a NULL email. One person can hold several roles (confirmed): they keep the **highest** (EE > SDO > SO > Jal Mitra); hourly and refresh runs only raise a role, and only the nightly full run, which sees every listing, lowers one (`users.role_downgrade_deferred` until then). A role outside the upstream's confirmed set raises `UNKNOWN_ROLE`. |
| Scheme | `scheme_master_table` | Matched by `state_scheme_code`, else by the IMIS+SMT id pair, else by one id when the other is unused (and adopted). Conflicting or ambiguous ids raise an issue and nothing is written. Upstream owns the name, ids, code, statuses, FHTC counts and coordinates. A blank or unrecognised upstream value never overwrites ours. |
| `village[]`, `subdivisions[]` | `scheme_lgd_mapping_table`, `scheme_department_mapping_table` | Missing rows are added and rows no longer listed are retired. The sync only manages rows pointing at villages, sub-divisions or the state placeholder. |
| `section-officer[]`, `sdo[]`, `executive-engineer[]`, `pump-operator[]` | `user_scheme_mapping_table`, `user_table.status` | Upstream is the source of truth: an officer listed on the scheme and not on `/blocked-users` is active, so an inactive user (`status = 0`) is **reactivated** (`users.reactivated`) and the pair added or revived. A listed officer who is blocked is never created, is deactivated if we hold them, and stays unlinked (`skipped_blocked_user`). Admin accounts are never reactivated (`REACTIVATION_ON_ADMIN_WITHHELD`). Rows no longer listed are retired, but only for the four managed user types. |
| `/archived-schemes` | `user_scheme_mapping_table` | Officers are retired, so nudges and escalations stop. The scheme row, its locations and its readings stay. A scheme with a reading in the last 90 days is **spared** (`ARCHIVED_BUT_REPORTING`). |
| `/blocked-users` | `user_table.status = 0` + mappings retired | Read on every run (one unpaginated call), before the schemes, so a blocked officer is never reactivated. Admin accounts are never blocked by the sync. JJM confirmed blocks are permanent. |
| Coded schemes in neither `/schemes` nor the archive | `scheme_master_table` | Reported every full run (`UPSTREAM_SCHEME_MISSING`). Codes are permanent and the list is complete, so one missing from **this and the previous successful full run** has become a child or test scheme upstream: with `STATE_SYNC_RETIRE_MISSING_SCHEMES=true` it is **retired** (soft-deleted, `deleted_by` = the sync's actor, officer links retired, villages / sub-divisions and readings kept) and `SCHEME_RETIRED` goes to analytics. Skipped (`RETIREMENT_SKIPPED`) when the run listed fewer schemes than `STATE_SYNC_RETIRE_MIN_LISTED_RATIO` × our coded schemes, or would retire more than `STATE_SYNC_RETIRE_MAX_PER_RUN`. Candidates are counted either way (`schemes.retire_candidates`). A retired scheme the upstream lists again is revived with the same id (`SCHEME_REVIVED`); one an admin deleted is not. |
| Our schemes no upstream scheme matched (no code) | — | Reported every full run (`LOCAL_SCHEME_NOT_IN_JJM`) with name, IMIS and SMT ids and last reading date, never changed. `GET /api/v1/scheme/state-sync/unmatched-schemes?tenantCode=&format=csv` downloads both lists from the last successful full run (dry runs included) to review with the JJM team. |

Safety rules:
- An **empty** village, sub-division or officer list keeps what we hold and raises an issue
  (`STATE_SYNC_RETIRE_ON_EMPTY_LIST=false`). JJM confirmed an empty list means "field official has not
  linked yet", so this setting should stay `false`.
- A list containing anything we could not resolve (an unmatched village, or an officer whose phone
  is invalid) retires nothing on that side.
- A lenient-ingestion placeholder is never matched as a real scheme. When a real scheme carrying the
  placeholder's IMIS id is written or matched, the placeholder's readings move to it (§2a). JJM does
  not enforce unique IMIS or SMT ids (legacy duplicates exist), so when two upstream schemes in the run,
  or two of our real schemes, carry that IMIS id, nothing moves and `PLACEHOLDER_IMIS_NOT_UNIQUE` is
  raised. A lookup by IMIS id returns every match.

## 2a. Analytics

| Event (`scheme-service-topic`) | Analytics does |
| --- | --- |
| `SCHEME_MAPPINGS_REPLACED` (dev's event, reused) | The scheme's details plus its full village and sub-division lists. Analytics keeps one `dim_scheme_table` row per village × sub-division pair, deletes the rows of pairs that are gone and writes the details to every row. The sync sends it for every scheme it inserted or changed, through `SchemeService.publishSchemeMappingsReplaced`, keyed `tenantId:schemeId`. |
| `SCHEME_RETIRED {schemeId}` (new; keyed `tenantId:schemeId`) | Drops the scheme's dim rows and daily aggregates so it leaves the dashboards, then re-aggregates the days it had readings. Its facts stay; a revived scheme gets its dim rows back from its next `SCHEME_MAPPINGS_REPLACED`. A send Kafka refuses is recorded as `RETIREMENT_EVENT_FAILED`. |
| `SCHEME_READINGS_REASSIGNED {from, to}` (new; keyed like the real scheme, so it follows that scheme's own message) | In one transaction holding both schemes' ingestion locks: moves the meter-reading facts (including pre-V56 rows with no `source_reading_id`, which a republish would have duplicated), plus attendance, anomaly and escalation facts. It then works out water quantity again for each affected day (removed from the placeholder, recalculated on the real scheme) and drops the placeholder's daily aggregates and dim rows. After commit it re-aggregates the affected dates in the background, in 31-day `backfillWindow` chunks. |

The mapping CSV upload sends the same event; the scheme upload and status PATCH send `SCHEME_UPDATED`
(details only). `POST /api/v1/scheme/schemes/analytics-resync` (from dev) re-sends every scheme of the
caller's tenant, and repairs any `SCHEME_MAPPINGS_REPLACED` the sync failed to send.

**Moving placeholder readings** (`STATE_SYNC_MOVE_PLACEHOLDER_READINGS`, default on). In the tenant DB:
`flow_reading_table` and `anomaly_table` rows are re-pointed to the real scheme (each reading's
`updated_at` moves), and the placeholder is soft-deleted. Every case still raises
`PLACEHOLDER_SUPERSEDED` with the counts, as an audit trail. Each reading keeps its `ingestion_source`
bits, so it stays visible as having arrived for an unknown scheme. With the flag off, the case is only
reported.

## 3. Configuration

| Env var | Default | Meaning |
| --- | --- | --- |
| `STATE_SYNC_ENABLED` | `false` | Master switch. Off means no schedule, no upstream calls, and the admin POSTs return 409. |
| `STATE_SYNC_MODE` | `DRY_RUN` | `DRY_RUN` rolls every write back; `APPLY` commits |
| `STATE_SYNC_TENANT_CODE` | — | e.g. `as` |
| `STATE_SYNC_ACTOR_USER_ID` | — | `user_table.id` stamped on `created_by` / `updated_by` (required by the mapping tables) |
| `STATE_SYNC_DELTA_CRON` / `STATE_SYNC_FULL_CRON` | `0 15 * * * *` / `0 0 2 * * *` | Spring 6-field crons |
| `STATE_SYNC_ZONE` | `Asia/Kolkata` | Timezone the crons are evaluated in |
| `STATE_SYNC_DELTA_OVERLAP` | `PT30M` | How far before the watermark each delta re-reads |
| `STATE_SYNC_STALE_RUN_AFTER` | `PT2H` | Lock takeover after a crash. Must exceed the longest full run. |
| `STATE_SYNC_ARCHIVE_SPARE_READING_DAYS` | `90` | |
| `STATE_SYNC_RETIRE_ON_EMPTY_LIST` | `false` | Keep `false`: JJM confirmed an empty list means "not linked yet" |
| `STATE_SYNC_RETIRE_MISSING_SCHEMES` | `false` | Retire coded schemes missing from two consecutive full runs (§2) |
| `STATE_SYNC_RETIRE_MIN_LISTED_RATIO` / `STATE_SYNC_RETIRE_MAX_PER_RUN` | `0.95` / `200` | Guards on that retirement |
| `STATE_SYNC_PLACEHOLDER_LOOKUPS_PER_RUN` / `_RETRY_AFTER` | `25` / `PT24H` | Placeholder lookups per delta, and the cool-down for ids upstream does not know |
| `STATE_SYNC_MOVE_PLACEHOLDER_READINGS` | `true` | Move a superseded placeholder's readings to the real scheme (§2a) |
| `JJM_BRAIN_BASE_URL` / `JJM_BRAIN_API_KEY` | `https://jjmbrain.in/api/v1` / — | |
| `JJM_BRAIN_MIN_REQUEST_INTERVAL` / `JJM_BRAIN_MAX_ATTEMPTS` | `PT1.1S` / `4` | Throttle and retry. JJM allows 60 requests a minute per IP, so a full run (about 1,000 calls) takes about 20 minutes. 429 and 5xx back off exponentially and honour `Retry-After`. |

`PII_ENCRYPTION_KEY` / `PII_HMAC_KEY` must be set on scheme-service. A run that needs to write users
fails rather than store plaintext.

## 4. Roll-out

1. Deploy with the flag off (V64 runs, nothing else changes).
2. Set `STATE_SYNC_ENABLED=true` with `STATE_SYNC_MODE=DRY_RUN` and the Assam tenant and actor. Trigger
   `POST …/runs?kind=FULL`, then read `GET …/runs` and `GET …/issues`.
3. Work through the issue categories, especially `LGD_UNMATCHED`, `AMBIGUOUS_*`, `CONFLICT_*` and
   `PROMOTION_WITHHELD`. Leave the nightly dry run on for about a week.
4. Switch to `STATE_SYNC_MODE=APPLY`. Retire the Assam spreadsheet scripts.

## 5. Not in this change

| Item | Why |
| --- | --- |
| Re-aggregation surviving a pod restart | The background re-aggregation after a reassignment is in memory. If the pod dies mid-queue, the WARN names the range, and `ANALYTICS_AGG_BACKFILL_*` re-runs it. Only matters with `ANALYTICS_READ_FROM_AGGREGATES` on. |
| `fact_submission_activity_hourly_table` | Not keyed by scheme, so a reassignment does not change it |
| Creating LGD nodes | JJM does not hold the national `lgd_code`, so LGD nodes stay match-only. |
| Reviving archived schemes or unblocked users | Not needed: JJM confirmed archives and blocks are permanent. |
| Language / email for users | Not in the API; still open with JJM. |

## 6. JJM team's answers (15 Oct 2026)

| # | Question | Answer | What we did |
| --- | --- | --- | --- |
| A1 | `updated_at` timezone; `updated_since` bounds | IST (Asia/Kolkata); inclusive (≥) | No change; the 30-minute overlap only re-reads |
| A2 | Empty village / sub-division / officer list | Field official has not linked yet | Keep our links (`STATE_SYNC_RETIRE_ON_EMPTY_LIST` stays `false`) |
| A3 | Can archives or blocks be reversed? | No; permanent | No reversal logic needed |
| A4 | Are codes permanent? | Yes; never reused | Matching on code first stays the primary rule |
| A5 | Scheme count and scope | 27,662 parent schemes; child and test schemes excluded and not to be stored; readings on child schemes are invalid; some new schemes lack `centre_scheme_id` and can be ignored | A new scheme without an IMIS id is not created (`CANNOT_CREATE_INCOMPLETE`) |
| A6 | Are IMIS / SMT ids unique? | Should be, but not enforced; legacy duplicates exist; corrections there flow to us | Matching by code is unaffected; placeholder readings never move onto a duplicated IMIS id (`PLACEHOLDER_IMIS_NOT_UNIQUE`) |
| A7 | Shared phones, multiple roles | One person can hold several roles; each user's phone is unique | A person keeps their highest role; a shared phone stays an issue |
| A8 | Phone format | 10-digit mobiles; anything else invalid | No change (`INVALID_PHONE`) |
| A9 | Value sets | Complete as listed | Unknown status keeps ours; a role outside the set raises `UNKNOWN_ROLE` |
| A10 | `/schemes` filters | AND; district and division are separate hierarchies | Not used by the sync |
| B1 | National LGD code | Not held by JJM | LGD nodes stay match-only |
| B2 | `updated_since` / `updated_at` on user and location masters | Will be provided (location masters have never changed) | Use for users in the hourly run once available |
| B3 | `archived_at` / `blocked_at` | Will be provided | Record when available |
| B4 | Household counts | Already in `planned_fhtc_imis` / `achieved_fhtc_imis` | Already used |
| B5 | SMT (`state_scheme_id`) filter on `/schemes` | Will be provided | Use for readings that carry only the SMT id |
| C1 | One paging format, real host in links | Local URL was a typo; production host is `https://jjmbrain.in/`; mismatches will be fixed | We already ignore `links` |
| C2 | Rate limit | 60 requests a minute per IP; can be raised for a specific IP | Throttle set to 1.1 s between calls |
| C3 | Staging | Will be provided; data may be inconsistent | Point dev at it via `JJM_BRAIN_BASE_URL` / `JJM_BRAIN_API_KEY` |
| C4 | Dedicated, rotated key | Use the existing key for now; rotation later | Key kept only as a secret |
| C6 | Change webhook | Can be implemented | Later |

**Still open with JJM**
1. **Technical contact** and how breaking changes or a v2 will be announced (C5, unanswered).
2. **`Retry-After` on 429** (unanswered); we back off exponentially either way.
3. **User language and email** on `/user-master` (our earlier B13, not answered).
4. **Key rotation** and a dedicated JalSoochak key: deferred by JJM ("later").
5. **Rate-limit raise** for our egress IP, if a 20-minute full run is too slow: JJM offered it; we need to send the IP.

**Agreed additions from JJM** (to adopt when they ship)
1. `updated_since` filter and `updated_at` field on `/user-master` and the location masters (B2).
2. `archived_at` / `blocked_at` dates on `/archived-schemes` and `/blocked-users` (B3).
3. `state_scheme_id` (SMT) filter on `/schemes` (B5).
4. One paging format with the real host in links (C1).
5. Staging URL and key (C3).
6. A change webhook for create, update, archive and block (C6).
7. Key regeneration and rotation, later (C4).
