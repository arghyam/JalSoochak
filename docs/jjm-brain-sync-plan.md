# JJM Brain → JalSoochak master-data sync

**Sources**
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
| **Delta** | `/schemes?updated_since=<last APPLY watermark − 30 min>` + up to 25 lenient-ingestion placeholders by IMIS id | `STATE_SYNC_DELTA_CRON`, default hourly at :15 | `updated_at` moves on SO, SDO, Jal Mitra and village changes (confirmed). The overlap absorbs the undocumented timezone. The placeholder lookup turns a reading for an unknown scheme into a real scheme within an hour. |
| **Full** | All 8 masters, `/user-master`, every scheme, `/archived-schemes`, `/blocked-users` | `STATE_SYNC_FULL_CRON`, default 02:00 IST | Masters and users have no `updated_since`. EE and SDO reassignments do not move a scheme's `updated_at`. Archives and blocks only show up when the whole list is read. |
| **Scheme refresh** | `/schemes?code=` or `?centre_scheme_id=` | `POST /api/v1/scheme/state-sync/schemes/refresh` | Support tickets |
| **Manual run** | FULL or DELTA | `POST /api/v1/scheme/state-sync/runs` | Onboarding, cut-over |

Every run:
1. **Claims the lock.** It inserts a RUNNING row into `common_schema.state_sync_run_table`. A partial
   unique index allows one RUNNING row per tenant, so with N replicas exactly one runs and the rest
   skip. If a pod crashes, its claim is taken over once the heartbeat is older than
   `STATE_SYNC_STALE_RUN_AFTER` (default 2 h). A delta that fires during the nightly full run skips.
2. **Fetches everything** before touching the database.
3. **Reconciles in one transaction.** In `DRY_RUN` that transaction is rolled back.
4. **Publishes analytics events** after commit, in `APPLY` mode only: `SCHEME_DIMENSION_REPLACED`,
   `SCHEME_READINGS_REASSIGNED`, `DEPARTMENT_LOCATION_UPDATED`, `USER_CREATED`/`USER_UPDATED`,
   `USER_SCHEME_MAPPINGS_REPLACED` (see §2a).
5. **Finishes the run row** with its counts, and writes its issues to
   `common_schema.state_sync_issue_table`. This happens in both modes.

## 2. What it writes

| Upstream | Our table | Rule |
| --- | --- | --- |
| Zone / circle / division / subdivision | `department_location_master_table` levels 2–5 | Matched by `state_dept_id`, else by name under the same parent (ignoring suffixes like "Division"), else by name alone. Unmatched nodes are **inserted**. A node whose parent differs is **re-parented**. Matched titles are left alone, because uploads still resolve departments by title. |
| District / block / panchayat / village | `lgd_location_master_table` levels 2–5 | **Matched only**: by `state_lgd_id`, else by name under the matched parent (districts may match by name alone). Upstream has no national `lgd_code`, so an unmatched node is reported (`LGD_UNMATCHED`), never created. A parent disagreement is reported, not applied. |
| User master + officers embedded in schemes | `user_table` | Allow-list: jal-mitra, section-officer, sdo, executive-engineer. Matched by `state_user_id`, else by phone hash (`91XXXXXXXXXX`). On a match: name updated, code stamped, role updated, except that admin accounts are never re-typed and a promotion to EE is withheld. New users are created encrypted, with a NULL email. |
| Scheme | `scheme_master_table` | Matched by `state_scheme_code`, else by the IMIS+SMT id pair, else by one id when the other is unused (and adopted). Conflicting or ambiguous ids raise an issue and nothing is written. Upstream owns the name, ids, code, statuses, FHTC counts and coordinates. A blank or unrecognised upstream value never overwrites ours. |
| `village[]`, `subdivisions[]` | `scheme_lgd_mapping_table`, `scheme_department_mapping_table` | Missing rows are added and rows no longer listed are retired. The sync only manages rows pointing at villages, sub-divisions or the state placeholder. |
| `section-officer[]`, `sdo[]`, `executive-engineer[]`, `pump-operator[]` | `user_scheme_mapping_table` | Missing rows are added and retired pairs revived. Rows no longer listed are retired, but only for the four managed user types. |
| `/archived-schemes` | `user_scheme_mapping_table` | Officers are retired, so nudges and escalations stop. The scheme row, its locations and its readings stay. A scheme with a reading in the last 90 days is **spared** (`ARCHIVED_BUT_REPORTING`). |
| `/blocked-users` | `user_table.status = 0` + mappings retired | Admin accounts are never blocked by the sync. |
| Schemes in neither `/schemes` nor the archive | — | Reported only (`UPSTREAM_SCHEME_MISSING`) |

Safety rules:
- An **empty** village, sub-division or officer list keeps what we hold and raises an issue
  (`STATE_SYNC_RETIRE_ON_EMPTY_LIST=false`).
- A list containing anything we could not resolve (an unmatched village, or an officer whose phone
  is invalid) retires nothing on that side.
- A lenient-ingestion placeholder is never matched as a real scheme. When a real scheme carrying the
  placeholder's IMIS id is written or matched, the placeholder's readings move to it (§2a).

## 2a. Analytics

| Event (`scheme-service-topic`) | Analytics does |
| --- | --- |
| `SCHEME_DIMENSION_REPLACED` | Replaces the scheme's whole `dim_scheme_table` set: one row per village × sub-division it is mapped to, each with its ancestor ids at levels 1–6. Rows for locations it left are deleted, and every remaining row carries the same name, ids, statuses and FHTC counts. This ends the per-row drift the old single-row `SCHEME_UPDATED` left behind. An event with no rows and `locationsKnown: true` (what scheme-service always sends) deletes the scheme's rows, because it has no location left; an event without the flag only realigns the attributes. |
| `SCHEME_READINGS_REASSIGNED {from, to}` | In one transaction holding both schemes' ingestion locks: moves the meter-reading facts (including pre-V56 rows with no `source_reading_id`, which a republish would have duplicated), plus attendance, anomaly and escalation facts. It then works out water quantity again for each affected day (removed from the placeholder, recalculated on the real scheme) and drops the placeholder's daily aggregates and dim rows. After commit it re-aggregates the affected dates in the background, in 31-day `backfillWindow` chunks. |

Every other path that changes a scheme sends the same event too: the scheme and mapping CSV uploads
and the status PATCH. `SCHEME_UPDATED` is no longer sent by scheme-service. For existing schemes,
`POST /api/v1/scheme/schemes/dimensions/republish?tenantCode=` re-sends every live scheme of a tenant,
as a one-off backfill.

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
| `STATE_SYNC_RETIRE_ON_EMPTY_LIST` | `false` | Flip once Assam confirms that empty means "none" |
| `STATE_SYNC_PLACEHOLDER_LOOKUPS_PER_RUN` / `_RETRY_AFTER` | `25` / `PT24H` | Placeholder lookups per delta, and the cool-down for ids upstream does not know |
| `STATE_SYNC_MOVE_PLACEHOLDER_READINGS` | `true` | Move a superseded placeholder's readings to the real scheme (§2a) |
| `JJM_BRAIN_BASE_URL` / `JJM_BRAIN_API_KEY` | `https://jjmbrain.in/api/v1` / — | |
| `JJM_BRAIN_MIN_REQUEST_INTERVAL` / `JJM_BRAIN_MAX_ATTEMPTS` | `PT0.5S` / `4` | Throttle and retry. 429 and 5xx back off exponentially and honour `Retry-After`. |

`PII_ENCRYPTION_KEY` / `PII_HMAC_KEY` must be set on scheme-service. A run that needs to write users
fails rather than store plaintext.

## 4. Roll-out

1. Deploy with the flag off (V61 runs, nothing else changes).
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
| Creating LGD nodes | Needs the national `lgd_code` from upstream (question 11). |
| Reviving archived schemes or unblocked users | Needs question 3 answered. |
| Language / email for users | Not in the API (question 13). |

## 6. Questions for the Assam team

See the list at the end of this document, which is written to be sent as-is.

---

### Questions for the JJM Brain team (Assam)

Thank you for the v1 API and the review responses. We have built the integration against v1 and it
is ready to run. The points below decide how a few edge cases behave. Until we hear back, we have
taken the safest option for each, noted in brackets.

**A. Behaviour we need confirmed**

1. **Timezone of `updated_at`.** Is it IST or UTC? Is `updated_since` inclusive (≥) or exclusive (>)?
   *(We re-read the last 30 minutes on every pull to be safe.)*
2. **Empty lists on a scheme.** If `village`, `subdivisions` or an officer array (`section-officer`,
   `sdo`, `executive-engineer`, `pump-operator`) comes back empty, does that mean the scheme has none,
   or that the data has not been entered yet? *(We currently keep our existing mappings when a list is
   empty.)*
3. **Reversals.** Can an archived scheme be un-archived, or a blocked user unblocked? If yes, does the
   code simply disappear from `/archived-schemes` / `/blocked-users`, and does the scheme reappear in
   `/schemes`? *(We do not reverse anything automatically yet.)*
4. **Code stability.** Are the public codes (`SCH-`, `USR-`, `ZON-`, `CIR-`, `DIV-`, `SDV-`, `DST-`,
   `BLK-`, `PAN-`, `VIL-`) permanent? Can a code ever be regenerated, or reused after a record is
   archived or blocked?
5. **Scheme scope.** How many schemes does `/schemes` return in total? The documentation says it returns
   parent schemes only and excludes known test schemes. Can field staff submit readings against a child
   scheme? If so, how can we get child schemes?
6. **Uniqueness.** Is `centre_scheme_id` (IMIS) always present and unique across schemes? Is
   `state_scheme_id` (SMT) unique?
7. **Shared phones and multiple roles.** Can two different users have the same phone number? Can one
   person hold two roles? For example, in the sample "NIBIR PABAN BORAH" appears as both EE
   (`USR-047218`) and SDO (`USR-047387`) with different phone numbers. *(Users are identified by
   phone on our side, so a shared phone is flagged and not written.)*
8. **Phone format.** Are phone numbers always 10-digit Indian mobiles? Do you ever send a `+91` prefix,
   a leading 0, landlines or placeholders? The division sample has an EE phone of `1234123490`, which
   is not a valid mobile. *(Numbers that are not valid mobiles are flagged and not written.)*
9. **Closed lists.** Are these the complete value sets?
   - `work_status`: `ongoing`, `completed`, `not-started`, `handed-over`
   - `operating_status`: `operative`, `non-operative`, `partially-operative`
   - `role`: `executive-engineer`, `section-officer`, `sdo`, `jal-mitra`, `khalasi`, `jal-sahayak`, `other`

   *(An unknown status leaves our current value unchanged. We onboard only `jal-mitra`,
   `section-officer`, `sdo` and `executive-engineer`.)*
10. **Filters.** On `/schemes`, is `?district=…&division=…` an AND? Does `?district=` return schemes that
    touch the district, or only schemes whose primary district it is?

**B. Fields we would like added**

11. **National LGD code** (`lgd_code`) on district, block, panchayat and village, in the masters and in
    the scheme's `block` / `panchayat` / `village` arrays. Without it we can only match villages by
    name, and cannot add a new village on our side.
12. **`updated_since` filter and `updated_at` field** on `/user-master` and the four location masters,
    so we can pull only what changed instead of about 800 pages every night.
13. **User language and email** on `/user-master`, so notifications go out in the right language.
14. **`archived_at` / `blocked_at`** dates on `/archived-schemes` and `/blocked-users`.
15. **Beneficiary / household count** on schemes (`beneficiaries_count`) and villages.
16. A **`state_scheme_id` (SMT) filter** on `/schemes`. Some readings reach us carrying only the SMT id.

**C. Paging and operations**

17. **One paging format on every endpoint.** Please always include `meta.last_page`. Please also
    return the real host in `links`: block, panchayat and village master return
    `http://127.0.0.1:8000/…`, and subdivision master and user master return empty links with no
    `last_page`. *(We ignore `links` and page until a short page arrives, which works but is fragile.)*
18. **Rate limit.** What request rate is allowed? Do you send `Retry-After` on 429? *(We send at most 2
    requests per second and back off on 429 and 5xx.)*
19. **Test environment.** Is there a staging or UAT base URL and key we can test against?
20. **API key.** Please issue a key dedicated to JalSoochak, delivered outside email or documents, and
    tell us the rotation policy. The earlier PDFs contained a live key, so please rotate it.
21. **Contact.** Who is the technical contact, and how will you announce breaking changes or a v2?
22. **Optional, later:** a change webhook (create, update, archive, block) so we can stop polling.
