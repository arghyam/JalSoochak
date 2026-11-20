# Situation report layouts (per tenant)

**Scope:** how a tenant's Daily and Weekly Water Service Situation Reports are configured: who receives
each report, and what each officer's PDF shows. It covers the sections, their order, the KPIs and columns,
every label, and the date and number formats.

**Services:** `tenant-service` (config write path, validation, recipients), `message-service` (layout
resolution and rendering), `analytics-service` (KPIs, including the two thresholds a label can quote).

**For:** whoever sets up or changes a tenant's reports, and whoever investigates a report that didn't
arrive or looks wrong.

A tenant with no config gets exactly the reports it got before this feature: the same recipients, the
same PDFs and the same WhatsApp messages. Nothing has to be written for an existing tenant.

---

## 1. What a tenant can and can't change

| Per tenant (this config) | Per instance (env or other config keys) |
| --- | --- |
| Which officer roles receive each report | WhatsApp templates and document captions |
| Header, sections, footer: which, in what order | Report times (`DAILY_SITUATION_REPORT_TIME` / `WEEKLY_SITUATION_REPORT_TIME`, their own config keys) |
| KPIs, lists and columns, from a fixed catalogue (§5) | The low-LPCD threshold and supply-days bars (analytics env, §6) |
| Every label and title (Latin script only) | Dry-run switches |
| Date patterns, decimals (0 or 1) | Fonts, font sizes, page size |
| Dashboard URL and support phone | |

Only data analytics already computes can be shown. A new KPI, list or column is a code change in all
three services (§11).

## 2. How a report is resolved

The config is one GENERIC tenant config key, `SITUATION_REPORT_LAYOUT`, stored in
`common_schema.tenant_config_master_table`. Both services read the live row on every run, with no cache,
so a change applies from the next run.

| Situation | Recipients (tenant-service) | Layout (message-service) |
| --- | --- | --- |
| No row, `{"version": 1}`, or no `daily` / `weekly` block | The env roles (`DAILY_REPORT_OFFICER_USER_TYPES`, `WEEKLY_REPORT_OFFICER_USER_TYPES`) | The built-in layout for the role (§8). Daily for an SDO has none, so it is skipped, as before |
| Block present | Exactly the roles under `roles`, in document order. `"roles": {}` means nobody | Listed role: its entry. Unlisted role: skipped (`SKIPPED_NO_LAYOUT`) |
| `{"useDefault": true}` | (listed) | The built-in layout. Only daily SO, weekly SO and weekly SDO have one |
| `{"layout": {...}}` | (listed) | That layout |
| Stored row unusable (§10) | The env roles, with a WARN | The built-ins, with an ERROR and the `report.layout.fallback` counter |

`settings` applies to every report the tenant gets, **built-in layouts included**: a tenant with
`"numberFormat": {"decimals": 0}` gets whole numbers in its default weekly report too. A setting left out
falls back to env (URL, phone) or to the built-in format.

To return a tenant to the defaults, PUT `{"version": 1}`. There is no delete endpoint.

## 3. The config document

```json
{
  "version": 1,
  "settings": {
    "dashboardUrl": "https://dashboard.example.org/staff/",
    "supportPhone": "1800-000-0000",
    "dateFormats":  { "date": "dd MMMM yyyy", "shortDate": "dd-MMM-yyyy", "time": "HH:mm" },
    "numberFormat": { "decimals": 1, "trimTrailingZeros": true }
  },
  "daily": {
    "roles": {
      "SECTION_OFFICER":        { "useDefault": true },
      "SUB_DIVISIONAL_OFFICER": { "layout": { "...": "a full layout, §4" } }
    }
  },
  "weekly": {
    "roles": {
      "SECTION_OFFICER":        { "layout": { "...": "..." } },
      "SUB_DIVISIONAL_OFFICER": { "useDefault": true }
    }
  }
}
```

Everything except `version` is optional. Roles are `SECTION_OFFICER` and `SUB_DIVISIONAL_OFFICER` only,
written exactly so.

| Setting | Default | Notes |
| --- | --- | --- |
| `dashboardUrl` | `DAILY_REPORT_DASHBOARD_URL` | Absolute `https://` URL; becomes the PDF's link |
| `supportPhone` | `DAILY_REPORT_SUPPORT_PHONE` | Blank hides the `SUPPORT_CONTACT` footer |
| `dateFormats.date` | `dd MMMM yyyy` | Java `DateTimeFormatter` pattern, English |
| `dateFormats.shortDate` | `dd-MMM-yyyy` | Used by the week placeholders |
| `dateFormats.time` | `HH:mm` | Used by `{cutoff}` |
| `numberFormat.decimals` | `1` | `0` or `1`; analytics already rounds to 1 place |
| `numberFormat.trimTrailingZeros` | `true` | `63.0` prints as `63` |

Each role entry is all-or-nothing: `useDefault` or a complete `layout`, never a partial override of the
default. To change one label, copy the built-in layout (§8) and edit the copy.

## 4. Layout

```json
{
  "numberSections": true,
  "header":   [ { "type": "TITLE", "text": "Daily Water Supply Report" } ],
  "sections": [ { "type": "SUMMARY", "title": "Summary", "columns": [], "rows": [] } ],
  "footer":   [ { "type": "SUPPORT_CONTACT", "text": "Helpline: {supportPhone}" } ]
}
```

With `numberSections` on (the default), section titles print as `1. Summary`, `2. ...`, numbered over the
sections actually drawn.

| Header element | Fields | Notes |
| --- | --- | --- |
| `TITLE` | `text` | Bold, centred |
| `SUBTITLE` | `text` | Centred |
| `FIELD` | `label`, `value` | `label` bold, then `value` |
| `SPACER` | | A blank line |
| `DASHBOARD_LINK` | `prefix`, `suffix` | `prefix` + the dashboard URL as a link + `suffix`. Left out when the URL is blank |

| Footer element | Fields | Notes |
| --- | --- | --- |
| `NOTE` | `text` | |
| `SUPPORT_CONTACT` | `text` | Must contain `{supportPhone}`. Left out when the phone is blank |
| `SPACER` | | |

| Section | Fields | Notes |
| --- | --- | --- |
| `SUMMARY` | `title`, `columns`, `rows`, `footnotes` | At most one. The first column must be `LABEL` |
| `SCHEME_LIST` | `list`, `title`, `columns`, `emptyText`, `footnotes` | Each list at most once. `emptyText` (default `None`) fills the row printed when the list is empty |
| `OFFICER_PERFORMANCE` | `title`, `columns`, `emptyText`, `footnotes` | **Weekly SDO only:** analytics computes the per-Section-Officer rows only for an SDO |

A column is `{"field": "SCHEME_NAME", "label": "Scheme", "width": 30}`. `width` is a relative weight. Set
it on every column of a table or on none; with none, the catalogue's default weights (§5) apply.

A summary row is `{"kpi": "AVG_LPCD", "label": "Average LPCD*"}`. Labels must be unique within a summary.
A household KPI may add `"display"`: `COUNT_WITH_PERCENT` (default, `14200 (95.9%)`), `COUNT` or
`PERCENT`.

Font sizes, padding and alignment aren't configurable. They follow fixed rules: a table with more columns
gets smaller type and tighter padding, and each kind of table keeps the alignment it has in the built-in
layouts.

## 5. Catalogue

**Summary KPIs.** In the weekly trend column, a rise in a "rise is bad" KPI is coloured as a decline.

| KPI | Daily | Weekly | Rise is bad |
| --- | --- | --- | --- |
| `TOTAL_SCHEMES` | ✓ | ✓ | |
| `SCHEMES_SUPPLYING` | ✓ | ✓ | |
| `SCHEMES_NOT_SUPPLYING` | ✓ | ✓ | ✓ |
| `SCHEMES_LOW_LPCD` | | ✓ | ✓ |
| `HOUSEHOLDS_WITH_SUPPLY` | ✓ | | |
| `HOUSEHOLDS_WITHOUT_SUPPLY` | ✓ | | ✓ |
| `TOTAL_HOUSEHOLDS` | ✓ | | |
| `AVG_LPCD` | ✓ | ✓ | |
| `ANOMALOUS_SUBMISSIONS` | ✓ | | ✓ |

**Summary columns** (default weight): daily `LABEL` (40), `VALUE` (25); weekly `LABEL` (40), `CURRENT`
(22), `PREVIOUS` (22), `TREND` (16).

**Scheme lists:** daily `NO_SUPPLY`, `ANOMALIES`; weekly `NO_SUPPLY`, `LOW_SUPPLY_DAYS` (1 to 3 days),
`LOW_LPCD`.

**Scheme columns** (default weight): `SERIAL_NO` (6), `SCHEME_NAME` (30), `CENTRE_SCHEME_ID` (20, the IMIS
id), `VILLAGES` (18), `PUMP_OPERATOR_NAMES` (24), `PUMP_OPERATOR_MOBILES` (20), `SECTION_OFFICER_NAMES`
(20), `SECTION_OFFICER_MOBILES` (18), `ANOMALY_TYPE` (23, `ANOMALIES` list only). `VILLAGES` and the two
Section Officer columns each cost one more query per report. A list the layout doesn't show isn't queried.

**Officer-performance columns** (default weight): `SERIAL_NO` (5), `OFFICER_NAME` (19), `OFFICER_MOBILE`
(15), `TOTAL_SCHEMES` (10), `SCHEMES_SUPPLYING` (15), `SCHEMES_NOT_SUPPLYING` (13), `SCHEMES_LOW_LPCD` (12),
`AVG_LPCD` (11).

Ids use platform terms. "Jal Mitra" and "IMIS" are only labels.

## 6. Placeholders

Any text may contain `{name}` placeholders: plain substitution, no expressions, no escaping. A name that
isn't listed here, or isn't available in that report, is rejected when the config is written.

| Placeholder | Daily | Weekly | Prints |
| --- | --- | --- | --- |
| `{officerName}` | ✓ | ✓ | The officer's name, or `Officer` |
| `{generatedDate}` | ✓ | ✓ | Today in IST, `date` format |
| `{reportDate}` | ✓ | | The reported day, `date` format |
| `{cutoff}` | ✓ | | The window's end, `time` format; 16:00 if analytics sent none |
| `{weekStart}` `{weekEnd}` `{previousWeekStart}` `{previousWeekEnd}` | | ✓ | `shortDate` format |
| `{weekStartDayName}` `{weekEndDayName}` | | ✓ | English weekday, e.g. `Thursday` |
| `{lowLpcdThreshold}` | | ✓ | The LPCD bar analytics applied to `LOW_LPCD` (`WEEKLY_REPORT_LOW_LPCD_THRESHOLD`) |
| `{minSupplyDays}` | | ✓ | The supply-days bar analytics applied to `SCHEMES_SUPPLYING` for this officer's role (`WEEKLY_REPORT_SO_MIN_SUPPLY_DAYS` / `_SDO_`) |
| `{supportPhone}` | ✓ | ✓ | Only inside `SUPPORT_CONTACT` |

Prefer the two threshold placeholders to writing the number into a label: they always print the bar
analytics actually used. The built-in layouts write `<=15` and `>=4` literally, so their output is
unchanged.

## 7. Validation

`PUT` validates the whole document and lists every problem in one 400, each with its path:

```text
SITUATION_REPORT_LAYOUT has 2 problems: weekly.roles.SUB_DIVISIONAL_OFFICER.layout.sections[1].rows[0].kpi: 'HOUSEHOLDS_WITH_SUPPLY' is not available in WEEKLY reports (allowed: ...); daily.roles.SECTION_OFFICER.layout.footer[0].text: must contain {supportPhone}
```

| Rule | Limit |
| --- | --- |
| Document | `version` is `1`; unknown properties are rejected; at most 64 KB as stored |
| Block | `roles` is required (`{}` allowed); role keys `SECTION_OFFICER` / `SUB_DIVISIONAL_OFFICER` |
| Role entry | Exactly one of `"useDefault": true` or `layout`; `useDefault` only where a built-in exists |
| Layout | 0 to 15 header elements, 1 to 10 sections, 0 to 5 footer elements |
| Sections | At most one `SUMMARY` and one `OFFICER_PERFORMANCE`; each list at most once and available in the report |
| Summary | 2 to 4 columns, `LABEL` first, no duplicates; 1 to 12 rows, no duplicate KPI or label; `display` on household KPIs only |
| Tables | 1 to 10 columns, valid for the table and list, no duplicates; widths on all or none, each in (0, 100] |
| Text | Required text non-blank; at most 200 characters (footnotes 300) |
| Characters | Printable Latin (Basic Latin, Latin-1), dashes, quotes, bullet, ellipsis, `≤ ≥ ₹`. No control characters, no Indic scripts: the report font has no glyphs for them |
| Placeholders | Balanced braces; names valid for the report; `{supportPhone}` only in, and required in, `SUPPORT_CONTACT` |
| Settings | `dashboardUrl` absolute `https://` with a host, at most 300 characters; `supportPhone` matches `^[0-9+()\- ]{3,20}$`; date patterns at most 40 characters, and must format a date (a time, for `time`) in English into allowed characters; `decimals` 0 or 1 |

## 8. Built-in layouts

The three built-in layouts are the JSON files message-service ships with. They reproduce the reports
exactly as they were before this feature:

- [daily-section-officer.json](../backend/message-service/src/main/resources/report-layouts/daily-section-officer.json)
- [weekly-section-officer.json](../backend/message-service/src/main/resources/report-layouts/weekly-section-officer.json)
- [weekly-sub-divisional-officer.json](../backend/message-service/src/main/resources/report-layouts/weekly-sub-divisional-officer.json)

Each file is a complete `layout`, so it is the starting point for a custom one. tenant-service keeps
identical copies under `src/test/resources/report-layouts/`, and a drift test fails if they differ.

There is no built-in daily layout for an SDO. To send SDOs a daily report, give them a full `layout`. It
covers every scheme mapped to the SDO, and its LPCD is that of the schemes that supplied. Agree this with
the state's product owner before enabling it. The SDO daily WhatsApp templates
(`WHATSAPP_DAILY_REPORT_SDO_TEMPLATE_ID`, `WHATSAPP_DAILY_REPORT_SDO_LINK_TEMPLATE_ID`) are optional: without
them the SO template is used.

## 9. Example: a Punjab-style daily report

Section Officers get a short daily report that leads with the no-supply list, under the officer title
the state uses. The weekly reports stay on the defaults. The complete sample, which also has an SDO daily
layout, is
[custom-layout-sample.json](../backend/tenant-service/src/test/resources/report-layouts/custom-layout-sample.json);
tenant-service's tests check that it validates. It is illustrative: the real Punjab layout is agreed with
the product owner.

```json
{
  "version": 1,
  "settings": {
    "dashboardUrl": "https://dashboard.example.org/staff/",
    "supportPhone": "1800-000-0000",
    "dateFormats": { "date": "dd-MM-yyyy", "shortDate": "dd-MM-yyyy" },
    "numberFormat": { "decimals": 0 }
  },
  "daily": {
    "roles": {
      "SECTION_OFFICER": { "layout": {
        "header": [
          { "type": "TITLE", "text": "Daily Water Supply Report" },
          { "type": "FIELD", "label": "Junior Engineer: ", "value": "{officerName}" },
          { "type": "FIELD", "label": "Date: ", "value": "{reportDate} (till {cutoff})" },
          { "type": "SPACER" },
          { "type": "DASHBOARD_LINK", "prefix": "Dashboard: ", "suffix": "" } ],
        "sections": [
          { "type": "SCHEME_LIST", "list": "NO_SUPPLY", "title": "Schemes with no supply today",
            "columns": [
              { "field": "SERIAL_NO", "label": "S. No." },
              { "field": "SCHEME_NAME", "label": "Scheme" },
              { "field": "VILLAGES", "label": "Village" },
              { "field": "PUMP_OPERATOR_NAMES", "label": "Pump Operator" },
              { "field": "PUMP_OPERATOR_MOBILES", "label": "Mobile" } ] },
          { "type": "SUMMARY", "title": "Summary",
            "columns": [ { "field": "LABEL", "label": "Indicator" }, { "field": "VALUE", "label": "Today" } ],
            "rows": [
              { "kpi": "TOTAL_SCHEMES", "label": "Total schemes" },
              { "kpi": "SCHEMES_NOT_SUPPLYING", "label": "Schemes not supplying" },
              { "kpi": "HOUSEHOLDS_WITHOUT_SUPPLY", "label": "Households without supply", "display": "COUNT" } ] } ],
        "footer": [ { "type": "SUPPORT_CONTACT", "text": "Helpline: {supportPhone}" } ] } }
    }
  },
  "weekly": {
    "roles": {
      "SECTION_OFFICER": { "useDefault": true },
      "SUB_DIVISIONAL_OFFICER": { "useDefault": true }
    }
  }
}
```

The `daily` block lists only `SECTION_OFFICER`, so SDOs get no daily report, whatever
`DAILY_REPORT_OFFICER_USER_TYPES` says.

## 10. Runbook: change a tenant's report

Writing the key needs `SUPER_USER`, or `STATE_ADMIN` of that tenant. No UI exposes it.

1. **Start from what's stored.** Read the current value, if there is one:

   ```bash
   curl -s -H "Authorization: Bearer $TOKEN" \
     "$GATEWAY/api/v1/tenants/$TENANT_ID/config?keys=SITUATION_REPORT_LAYOUT"
   ```

   For a new layout, copy the closest built-in file (§8) into the role's `layout`.
2. **Try it on staging first.** Set `NOTIFICATIONS_DAILY_REPORT_DRY_RUN=true` and/or
   `NOTIFICATIONS_WEEKLY_REPORT_DRY_RUN=true` on message-service. A dry run still renders and uploads
   every PDF, and only skips the WhatsApp send.
3. **Write it.** The body wraps the document in `configs`:

   ```bash
   jq '{configs: {SITUATION_REPORT_LAYOUT: .}}' layout.json |
     curl -s -X PUT -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
       --data-binary @- "$GATEWAY/api/v1/tenants/$TENANT_ID/config"
   ```

   A 400 lists every problem (§7). Fix them all and PUT again. Nothing is stored until the whole
   document is valid.
4. **Run it.** Move the tenant's `DAILY_SITUATION_REPORT_TIME` / `WEEKLY_SITUATION_REPORT_TIME` a few
   minutes ahead, or wait for the next run.
5. **Check.**
   - Recipients: the job's start line, `[DailyReportJob] ... roles=[...] rolesSource=config` (or
     `[WeeklyReportJob]`), shows the roles it used and where they came from.
   - Output: open the PDFs in the bucket, one per role.
6. **Go live.** Turn the dry run off, put the report time back, and repeat steps 3 to 5 on production.

**Roll back:** PUT `{"version": 1}`. The next run uses the env roles and the built-in layouts.

## 11. Deploy order

Deploy **analytics-service, then message-service, then tenant-service**. Each version works with the
previous version of the others:

- analytics first, so the threshold values are in the payload before a layout can quote them. Until
  then, the placeholders print blank (§12).
- message-service before tenant-service, so a layout can't be stored before the renderer understands it.
  Each service keeps its own copy of the catalogue.

Follow the same order for any later catalogue addition. To roll back the code, redeploy the previous
images in reverse order. An older message-service never reads the key, and an older tenant-service
ignores keys it doesn't know, so a stored layout is then simply unused.

## 12. Troubleshooting

| Symptom | Log / metric | Cause and fix |
| --- | --- | --- |
| An officer got no report | `[Router/DAILY_REPORT]` or `[Router/WEEKLY_REPORT] ... result=SKIPPED_NO_LAYOUT role=...` | message-service has no layout for that role. The role isn't listed in the tenant's block, or it's a daily SDO with no config (there is no built-in). If the role should get the report, add it to the block |
| A whole role got nothing, no `SKIPPED_NO_LAYOUT` | `[DailyReportJob] ... roles=[...] rolesSource=...` | tenant-service never requested it. With `rolesSource=config`, the role isn't in the block; with `env`, it isn't in `*_REPORT_OFFICER_USER_TYPES` |
| The tenant's layout was ignored; the reports look like the defaults | ERROR `[ReportLayout] tenant=... report=... role=...: SITUATION_REPORT_LAYOUT is unusable, so the built-in layouts are used: <reason>`; counter `report.layout.fallback{report=daily\|weekly}` (`report_layout_fallback_total` in Prometheus); WARN `[TenantConfig] Unusable SITUATION_REPORT_LAYOUT ... default roles` | The stored row doesn't parse or uses something message-service doesn't know. Usually the deploy order was broken (§11): deploy message-service. Otherwise the row was written around the API: PUT it again. Officers keep getting the default reports meanwhile |
| A threshold prints blank | WARN `[WeeklyReportPdf] The layout uses {lowLpcdThreshold}, but the KPI payload has no value for it, so it prints blank` | analytics-service predates the threshold fields. Deploy it |
| A label states a threshold that doesn't match the list | | The label has the number written in. Use `{lowLpcdThreshold}` / `{minSupplyDays}` |
| PUT answers 400 | | Read the message: it lists every problem with its path (§7) |

### Log tag rename

The daily router's skip tag `result=SKIPPED_SDO_DAILY_RETIRED` is now `result=SKIPPED_NO_LAYOUT`, and the
weekly router uses it too. It is logged for any role without a layout, not only daily SDOs. Update any
saved log query, dashboard or alert that matches the old tag. Every other log tag is unchanged.
