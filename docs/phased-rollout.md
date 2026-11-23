# Phased rollout (onboarded districts) — ops runbook

A state can go live on the dashboards a few districts at a time. With the switch on, only the districts
it has onboarded, and the regions under them that have schemes, are reachable. The dashboard shows every
other region greyed out on the map, and leaves it out of dropdowns, tables and charts. The API answers
404 for it.

This runbook is for whoever runs the pilot: turning it on, onboarding districts and checking that state
numbers aren't affected. Built for the Punjab pilot.

**Scope.** analytics-service and the dashboard only. Scheme upload, the state sync and every other service
are untouched. Schemes can be loaded into any district at any time (see §6).

---

## 1. The rule

With `PHASED_ROLLOUT_ENABLED=true`, a region below the state, LGD or department, is **onboarded** when
`analytics_schema.dim_scheme_table` holds at least one of its schemes whose district has
`is_onboarded = TRUE` in `analytics_schema.dim_lgd_location_table`. The state is always onboarded.

- **A district is hidden until you onboard it.** The flag defaults to `FALSE`, and the migration's
  CHECK constraint only allows it on district rows (`lgd_level = 2`).
- **A district needs the flag and at least one scheme.** An onboarded district with no schemes yet stays
  hidden until its first scheme arrives. A district with schemes but no flag stays hidden however many it
  has.
- **Blocks, GPs, villages and department nodes have no flag.** Each one appears when it has a scheme in an
  onboarded district. A department node whose schemes are all in districts that aren't onboarded stays
  hidden.
- **Retired schemes don't count.** Their `dim_scheme_table` rows are deleted.

The rule is evaluated live, on every request. A flag change, or a new scheme, takes effect without a
restart, a re-aggregation or a cache flush.

## 2. What the switch changes

| | Off (default) | On |
| --- | --- | --- |
| Region-scoped analytics endpoints | Unchanged | 404 for a region that isn't onboarded |
| `GET /api/v1/analytics/onboarded-regions` | `phasedRollout: false`, `onboardedChildIds: null` | The parent's onboarded children |
| `GET /api/v1/analytics/national/onboarded-districts` | `phasedRollout: false`, `tenants: null` | Every tenant's onboarded districts (403 in single-tenant mode) |
| Extra queries | None | Up to two small lookups per region a request names |
| `is_onboarded` column | Ignored | Read on every such request |

The switch is `analytics.phased-rollout.enabled` in analytics-service's `application.yml`. It applies to
the whole deployment. On a deployment that hosts more than one tenant, every tenant would need its
districts flagged. The pilot runs its own deployment.

**What gets a 404.** The guard reads `lgd_id`, `parent_lgd_id`, `department_id` and
`parent_department_id` on every `/api/v1/analytics/**` request, checking each against the request's
`tenant_id`. If any of them names a region that isn't onboarded, the request is rejected before the
controller and its response cache run:

```json
{ "timestamp": "2026-11-16T10:15:30.123", "status": 404, "error": "Not Found", "message": "Region not found" }
```

The service logs each refusal at WARN, with the request, tenant, hierarchy and region id. A region the
tenant doesn't have isn't the guard's business: it passes through and gets the endpoint's usual answer.

## 3. Going live

1. **Deploy analytics-service with `PHASED_ROLLOUT_ENABLED=true`.** Migration V62 adds the column on
   startup. From then on, every district is hidden.
2. **Load the LGD dimension for the state** as usual, with every district and its geometry. The map needs
   every polygon, including those of districts that stay grey.
3. **Find the tenant**, and keep its `tenant_id` for the rest of this runbook:
   ```sql
   SELECT tenant_id, state_code, title FROM analytics_schema.dim_tenant_table ORDER BY tenant_id;
   ```
4. **Onboard the pilot districts.** `lgd_code` is the national LGD code:
   ```sql
   BEGIN;
   UPDATE analytics_schema.dim_lgd_location_table
      SET is_onboarded = TRUE, updated_at = NOW()
    WHERE tenant_id = :tenant_id AND lgd_level = 2 AND lgd_code IN (:pilot_district_lgd_codes);
   -- The row count must equal the number of pilot districts. If it doesn't, ROLLBACK and check the codes.
   COMMIT;
   ```
   To take a district off again, set it back to `FALSE`.
5. **Upload the pilot districts' schemes.** A region appears as soon as its first scheme reaches
   `dim_scheme_table` through Kafka.
6. **Check** (§4), before and after every scheme upload.
7. **Full go-live:** turn the switch off (§5), or onboard every district.

## 4. Checking

**Which districts are onboarded:**

```sql
SELECT lgd_id, lgd_code, title
  FROM analytics_schema.dim_lgd_location_table
 WHERE tenant_id = :tenant_id AND lgd_level = 2 AND is_onboarded
 ORDER BY title;
```

**What the dashboard sees.** The API takes the dimension's `lgd_id`, not the `lgd_code`. Use the state's
`lgd_id` (the `lgd_level = 1` row) as the parent:

```bash
curl "<gateway>/api/v1/analytics/onboarded-regions?tenant_id=<tenant_id>&parent_lgd_id=<state_lgd_id>"
```

`onboardedChildIds` lists the districts that are both flagged and have schemes. A flagged district missing
from it has no schemes yet. For the department hierarchy, pass `parent_department_id` instead.

The national District View asks for every state's districts at once, with
`GET <gateway>/api/v1/analytics/national/onboarded-districts`. Its `tenants[].onboardedDistrictIds` match
each state's `onboardedChildIds` above.

**Stray schemes.** Schemes in a district that isn't onboarded still count in the state's numbers (§6), so
this list should be empty:

```sql
SELECT d.title AS district, COUNT(DISTINCT s.scheme_id) AS schemes
  FROM analytics_schema.dim_scheme_table s
  JOIN analytics_schema.dim_lgd_location_table d
    ON d.tenant_id = s.tenant_id AND d.lgd_id = s.level_2_lgd_id AND d.lgd_level = 2
 WHERE s.tenant_id = :tenant_id AND NOT d.is_onboarded
 GROUP BY d.title
 ORDER BY d.title;
```

## 5. Rolling back

Set `PHASED_ROLLOUT_ENABLED=false` and redeploy. Behaviour is back to what it was before the pilot, and
the column is ignored. The flags can stay in place for the next time the switch is turned on.

## 6. Known trade-offs

- **Schemes in districts that aren't onboarded still count in state-level numbers.** Uploads aren't
  gated and KPI scope is unchanged, so these schemes count in the state KPIs, the state scheme lists
  (`/schemes/dashboard`, critical and continuous schemes) and the state totals, even though their district
  is grey. To keep state numbers clean, upload a district's schemes only when, or shortly before, you
  onboard it. Keep `STATE_SYNC_ENABLED` off for this tenant, and run the stray-scheme check in §4.
- **A department node spanning onboarded and non-onboarded districts** is onboarded. Its own numbers then
  include any stray schemes from the other side. The same mitigation applies.
- **Raw API child lists still include regions that aren't onboarded**, as zero-metric rows, when a metric
  endpoint is called directly for an onboarded parent. The dashboard filters them out using
  `onboarded-regions`.

## 7. Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| A pilot district stays grey | It has no schemes in `dim_scheme_table` yet, or its row isn't flagged (§4). An open dashboard only picks up the change on its next fetch of the onboarded list |
| Every district is grey | The switch is on and no district is flagged yet, or the LGD dimension was reloaded (below) |
| Flags disappeared after a dimension reload | Upserts keep the flag, because the JPA entity doesn't map the column. Deleting and re-inserting district rows resets it to `FALSE`. Re-run §3 step 4 |
| `UPDATE` fails on `ck_dim_lgd_location_is_onboarded_district_only` | The `WHERE` matched a row that isn't a district. Keep `lgd_level = 2` in it |
| A block or village returns 404 although its district is onboarded | It has no scheme of its own. It appears with its first scheme |
