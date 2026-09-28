# Location Affinity Check

How JalSoochak decides that a meter reading was submitted from somewhere other than its scheme, what
the operator sees when it happens, and what has to be configured for any of it to run.

---

## 1. What this does

The WhatsApp flow has always recorded the operator's location alongside a reading. Nothing compared
it to the scheme's own coordinates, so a reading submitted from anywhere at all looked identical to
one submitted at the pump.

Now the distance is measured against a configurable radius:

- **WhatsApp** — over the radius, the operator is warned and asked whether to proceed. **Yes**
  continues to image submission and the backend records a `LOCATION_MISMATCH` anomaly against that
  reading. **No** returns to the main menu and nothing is recorded.
- **State IT system API** — no interactive step. If `geolocation` is sent and it is over the radius,
  the reading is captured _and_ the anomaly is recorded.

`LOCATION_MISMATCH` is anomaly type **11**. It is the only type that does not reject, withhold or
alter the reading — the value is stored and counted normally, and the anomaly records only where it
was taken from. It appears as **Location Mismatch** in the SO daily report.

---

## 2. When the check actually runs

All four conditions must hold. Anything missing is _skipped_, never reported as a mismatch — a false
mismatch accuses a named operator on a report their officer reads, so ambiguity always resolves the
safe way.

| Condition                                                           | Where it comes from                                                   |
| ------------------------------------------------------------------- | --------------------------------------------------------------------- |
| Tenant's `LOCATION_CHECK_REQUIRED` is `YES`                         | `common_schema.tenant_config_master_table`, per tenant                |
| `LOCATION_AFFINITY_THRESHOLD` is set to a positive number of metres | same table at **`tenant_id = 0`** (system-level, Super Admin)         |
| The submission carries coordinates                                  | WhatsApp: the operator shared a location. API: `geolocation` was sent |
| The scheme has `latitude` and `longitude`                           | `scheme_master_table`, usually from the scheme CSV upload             |

---

## 3. Where the anomaly is written, and when

**The backend is never told the operator's Yes or No.** It does not need to be:

- **Yes** is observed as the image submission arriving. `POST /location` has already written the
  coordinates onto that day's reading row; when the reading lands it reuses the same row, so the
  coordinates are still there and the check re-runs against stored data.
- **No** sends nothing. The placeholder row keeps its coordinates and no reading, which every
  reporting query already excludes (`confirmed_reading > 0`).

So the anomaly exists only where a reading exists. There is no confirmation webhook to add and
nothing to reconcile.

The anomaly is recorded at the moment the reading is persisted, on all three reading paths — the
WhatsApp image submission, the WhatsApp manual entry, and the state-IT API. It carries
`flow_reading_id` on the tenant row and the reading's `correlation_id` on the analytics event, so it
is traceable to the exact submission from either side.

**One anomaly per operator, per scheme, per day.** An operator retrying a blurry image three times
produces one row, not three.

The measured distance is **not stored**. The anomaly reason is a fixed string so the column stays
groupable, and no numeric column on `anomaly_table` means metres. The distance is always
recomputable from the reading's and the scheme's stored coordinates, and it is in the server log
(`location_mismatch … distanceMetres= thresholdMetres=`) and the `location_affinity.mismatch` metric.

---

## 4. Glific flow changes

### What the backend now returns

`POST /api/v1/telemetry/location` (unchanged request, unchanged auth header) returns one extra
field:

```json
{
  "success": true,
  "locationMismatch": true,
  "qualityStatus": "CONFIRMED",
  "message": "System detected that reading is being submitted outside the Scheme boundary. Do you want to proceed?"
}
```

- `locationMismatch` is always present and is `false` unless the check actually fired.
- On a mismatch `success` stays **`true`** and `qualityStatus` stays **`CONFIRMED`** — the
  coordinates _were_ saved. **Branch on `locationMismatch` only, never on `success`.**
- `message` carries the localized warning, so the flow must not hard-code the English text.

The same flag reaches a flow resumed after async image processing as the variable
`location_mismatch`.

### The branch, as built

Inserted on the success path of the location webhook, between the `@results.success` check and the
existing "Location saved successfully" message:

```
[success router]  @results.success
   │
   ├─ Results ─→ Extract flag        set_run_result locationMismatch
   │                 ↓                 = @results.locationresponse.locationMismatch
   │             Boundary check      split on @results.locationmismatch
   │                 │
   │                 ├─ Outside Boundary ─→ Boundary warning   send @results.locationresponse.message
   │                 │                          ↓
   │                 │                      Confirm            interactive Yes/No (template 37140)
   │                 │                          ↓
   │                 │                      Boundary answer    wait for reply
   │                 │                          ├─ Yes    ─→ /intro webhook  (existing: asks for the photo)
   │                 │                          ├─ No     ─→ main menu       (enter_flow wrapper)
   │                 │                          └─ Other  ─→ main menu
   │                 │
   │                 └─ Other ──────────────→ "Location saved successfully"  (existing, unchanged)
   │
   └─ Other ─→ (existing failure path, unchanged)
```

Four things about this shape are deliberate:

- **The warning is a separate message from the buttons.** A Glific interactive template carries
  static text, so it cannot render the backend's per-tenant, per-language wording. Sending the
  localized text first and then reusing the existing generic **"Are you sure?"** template (id
  `37140`, already shared by the language-confirmation steps) keeps both the localization and the
  tappable buttons, and adds no new Glific object.
- **Yes skips the "Location saved" message** and goes straight to the `/intro` webhook. Routing it
  through the normal path would show `locationresponse.message` again — which on a mismatch is the
  warning, so the operator would read it twice.
- **Neither answer calls the backend.** Yes is signalled by the image submission that follows; No
  sends nothing. There is no confirmation webhook and nothing to reconcile.
- **An unrecognised reply goes to the main menu**, matching every other Yes/No router in this flow.

## 5. Configuration

### Per platform (Super Admin, once)

`LOCATION_AFFINITY_THRESHOLD` at `tenant_id = 0`, in metres:

```sql
INSERT INTO common_schema.tenant_config_master_table (tenant_id, config_key, config_value)
VALUES (0, 'LOCATION_AFFINITY_THRESHOLD', '{"value":"200"}');
```

One value covers every tenant. If states ever need different radii, a per-tenant tier goes in front
of this one in `LocationAffinityService.resolveThresholdMetres` — the method is written so that is
the only change.

### Per tenant

1. `LOCATION_CHECK_REQUIRED` = `YES`. This is the feature's off switch and governs the state-IT API
   as well as WhatsApp.
2. Scheme coordinates in `scheme_master_table` — set by the scheme CSV upload's `latitude` /
   `longitude` columns.
3. The localized warning.

   ```json
   {
     "screens": {
       "LOCATION_BOUNDARY": {
         "message": {
           "en": "System detected that reading is being submitted outside the Scheme boundary. Do you want to proceed?",
           "hi": "सिस्टम ने पाया कि रीडिंग योजना की सीमा के बाहर से जमा की जा रही है। क्या आप आगे बढ़ना चाहते हैं?"
         }
       }
     }
   }
   ```

   Or as legacy rows `location_boundary_warning_<language-name>` (full lowercase language names, not
   ISO codes — `location_boundary_warning_english`, `_hindi`, `_assamese`), plus a bare
   `location_boundary_warning` fallback. A tenant that configures nothing gets the English text
   above.

Config is cached for 120 seconds, so edits take up to two minutes to take effect.

---

## 6. Observability

| Metric                                   | Meaning                                                                                                      |
| ---------------------------------------- | ------------------------------------------------------------------------------------------------------------ |
| `location_affinity.mismatch{path}`       | A reading was recorded as out of bounds                                                                      |
| `location_affinity.within{path}`         | Checked and in bounds                                                                                        |
| `location_affinity.skipped{path,reason}` | Not checked. `reason` is `check_not_required`, `no_reading_location`, `no_scheme_location` or `no_threshold` |

`path` is `location_webhook`, `image_submission`, `manual_reading` or `state_api`.

`no_scheme_location` is logged at `WARN` because it is a fixable master-data gap that silently
disables the check for every reading on that scheme. The others are `DEBUG`.

---
