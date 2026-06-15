# Escalation System — JalSoochak V2

**Audience:** Product / Operations Managers
**Last updated:** April 2026

---

## What Is an Escalation?

An escalation is a system-generated alert raised when something goes wrong with a water scheme — either the water supply is abnormal, or the pump operator has stopped submitting daily readings. These alerts are surfaced in the dashboard to the responsible Section Officer (SO) or District Officer (SDO/DO), who can then act and update the resolution status.

---

## Escalation Types

| Type | Trigger | Source |
|---|---|---|
| `TYPE_NO_WATER_SUPPLY` | No water flow detected for a scheme | Meter reading (real-time) |
| `TYPE_LOW_WATER_SUPPLY` | Water flow below acceptable threshold | Meter reading (real-time) |
| `TYPE_OVER_WATER_SUPPLY` | Water flow above acceptable threshold | Meter reading (real-time) |
| `TYPE_NO_SUBMISSION` | Pump operator has not submitted readings for 3+ consecutive days | Scheduled job (daily, 9 AM) |

---

## Escalation Statuses

Each escalation moves through the following lifecycle:

```
Unresolved  →  In-Progress  →  Resolved
```

| Status | Meaning |
|---|---|
| **Unresolved** | Alert raised; no action taken yet |
| **In-Progress** | Officer has acknowledged and is working on it |
| **Resolved** | Issue has been addressed |

The officer updates the status manually from the dashboard.

---

## Who Sees Which Escalations?

Escalations are **assigned to an officer at creation time** — each officer only sees the alerts that belong to their schemes. Two officer roles are involved:

| Role | Also Called | When They Are Escalated To |
|---|---|---|
| **Section Officer (SO)** | Level 1 Officer | Operator has missed 3–6 consecutive days of submissions |
| **District Officer (SDO/DO)** | Level 2 Officer | Operator has missed 7+ consecutive days, OR has never submitted at all |

> **Important:** Water supply anomalies (`NO_WATER_SUPPLY`, `LOW_WATER_SUPPLY`, `OVER_WATER_SUPPLY`) are created and shown to the relevant officer of the scheme in real time — they are not subject to the day-threshold logic above.

---

## How `TYPE_NO_SUBMISSION` Escalations Are Created

Every morning at **9:00 AM**, an automated job runs for each tenant (state). It checks every active pump operator's submission history and applies the following rules:

### Decision Logic

| Days Since Last Submission | Action |
|---|---|
| 0 – 2 days | No escalation |
| 3 – 6 days | Escalate to **Section Officer (Level 1)** |
| 7 or more days | Escalate to **District Officer (Level 2)** |
| Never submitted at all | Escalate to **District Officer (Level 2)** |

The thresholds (3 days and 7 days) are configurable per tenant.

### What Gets Recorded

For each operator who crosses the threshold, the system records:
- Which scheme they are responsible for
- How many consecutive days they have missed
- Their last recorded reading (if any)
- Which officer (SO or SDO) the alert belongs to
- A unique correlation ID to prevent duplicate alerts

---

## How Water Anomaly Escalations Are Created

When a meter reading is submitted (via the Glific WhatsApp workflow), the system immediately checks whether the flow value is within the acceptable range for the scheme. If it is outside the range:

1. The anomaly type (`NO_WATER_SUPPLY`, `LOW_WATER_SUPPLY`, or `OVER_WATER_SUPPLY`) is identified.
2. An alert is created in real time and assigned to the officer responsible for that scheme.
3. The alert appears in the officer's dashboard immediately.

---

## End-to-End Flow Summary

```
DAILY (9 AM)
─────────────────────────────────────────────────────────────────
Scheduler checks all pump operators
  └─ Missed 3–6 days   → Alert created → Assigned to SO
  └─ Missed 7+ days    → Alert created → Assigned to SDO/DO
  └─ Never submitted   → Alert created → Assigned to SDO/DO
  └─ Each alert is also sent to the officer via WhatsApp (PDF report)


REAL-TIME (on meter reading submission)
─────────────────────────────────────────────────────────────────
Meter value received
  └─ No / Low / Over water supply detected
       → Alert created immediately → Assigned to scheme's officer
```

In both cases, the alert is stored in the analytics database and visible in the dashboard under the officer's login. Duplicate alerts (same operator, same streak) are automatically prevented by the system.

---

## Officer Dashboard Experience

- An SO or SDO logs in and sees only the escalations for their assigned schemes.
- They can filter by escalation type, scheme name, resolution status, and date range.
- They can mark an escalation as **In-Progress** or **Resolved** from the dashboard.
- Historical escalations remain visible for audit purposes.

---

## Configuration (per Tenant / State)

The following parameters can be adjusted per state without any code change:

| Parameter | Default | Description |
|---|---|---|
| Level 1 threshold (days) | 3 | Days missed before escalating to SO |
| Level 2 threshold (days) | 7 | Days missed before escalating to SDO/DO |
| Level 1 officer role | `SECTION_OFFICER` | Role name for SO escalations |
| Level 2 officer role | `DISTRICT_OFFICER` | Role name for SDO escalations |

These are stored in the `tenant_config_master_table` under the `FIELD_STAFF_ESCALATION_RULES` key.
