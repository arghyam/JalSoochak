#!/usr/bin/env python3
"""Repair the historical contents of analytics_schema.fact_water_quantity_table.

Why this exists
---------------
Two defects wrote every historical row in that table, and both are fixed in the code
as of the V45 deploy:

1. **Wrong unit.** The column is denominated in litres — that is what every consumer
   reads (total_water_supplied_liters in the CSV/JSON, avgKld = litres/1000 and
   avgLpcd = litres/population in the officer daily report, and the litre-denominated
   efficient-range and performance-score SQL) — but the writers stored the meter's
   native cubic metres. Every stored value is 1000x low.

2. **Wrong baseline.** The day's supply was taken against the *previous calendar day's*
   reading with a COALESCE(..., 0) fallback. On a scheme's first-ever reading, and after
   any gap in submissions, that fallback made the whole cumulative meter index one day's
   supply — values in the millions of m3.

The corrected value for a row is a pure function of the readings:

    (latest reading on D  -  latest reading before D with confirmed_reading > 0) * 1000

which is why this is one uniform recompute-from-readings rather than two targeted
patches. fact_water_quantity_table holds rows from three writers (the pre-go-live
backfill_water_quantity.sql, live ingestion, and the telemetry correction path — the last
of which already applied the *correct* delta rule), and a recompute lands on the right
answer for all three. Trying to identify "rows with the baseline bug" and separately
"multiply everything by 1000" would double-apply on the third.

The recompute is therefore idempotent, cannot double-apply, and agrees exactly with what
the deployed code now writes — so a reading arriving mid-run is harmless: it fails this
script's value guard and keeps the value the new code just wrote correctly.

The definition of "the corrected value" lives in
backend/analytics-service/src/main/resources/db/scripts/recompute_water_quantity.sql,
which this script reads and WaterQuantityBackfillParityIntegrationTest asserts against
live ingestion. It is not duplicated here.

Run this AFTER deploying analytics-service (Flyway V45 widens the column to BIGINT).
Running it before would leave live ingestion writing cubic metres into a litre table for
the same days.

Phases
------
  0 backup       copy in-window rows of both fact tables to public.*_backup_<suffix>
  1 identify     build public.fact_water_quantity_recompute — the review artefact
  2 water        apply the recompute, chunked by month, value-guarded
  3 performance  replay the daily performance score over the corrected quantities

Modes
-----
  --dry-run   (default) phases 0-1 only; nothing is written to the fact tables
  --execute   phases 0-3
  --verify    report only: what changed, before/after distribution, exceptions
  --rollback  restore both fact tables by id from a phase-0 backup

Usage
-----
  export ANALYTICS_DSN='host=localhost port=5432 dbname=shared_db user=postgres password=...'

  # what would change? review public.fact_water_quantity_recompute afterwards,
  # especially the exception list this prints
  python3 scripts/water_quantity_units_fix.py --dry-run

  # apply it, then check
  python3 scripts/water_quantity_units_fix.py --execute
  python3 scripts/water_quantity_units_fix.py --verify

  # water only, over one month
  python3 scripts/water_quantity_units_fix.py --execute --phase water \
      --start-date 2026-04-01 --end-date 2026-04-30

  # undo
  python3 scripts/water_quantity_units_fix.py --rollback --backup-suffix 20260903_181500

After --execute, flush the Redis dashboard keys: SchemeRegularityServiceImpl caches for
24h, so stale 1000x-low values would keep being served for a full day otherwise.
"""

from __future__ import annotations

import argparse
import datetime as dt
import logging
import os
import re
import sys
from pathlib import Path

try:
    import psycopg2
    import psycopg2.extras
except ImportError:  # pragma: no cover
    sys.exit("psycopg2 is required:  pip install psycopg2-binary")

LOG = logging.getLogger("water-quantity-fix")

REPO_ROOT = Path(__file__).resolve().parent.parent
RECOMPUTE_SQL_PATH = (REPO_ROOT / "backend" / "analytics-service" / "src" / "main"
                      / "resources" / "db" / "scripts" / "recompute_water_quantity.sql")

WATER_TABLE = "analytics_schema.fact_water_quantity_table"
PERFORMANCE_TABLE = "analytics_schema.fact_scheme_performance_table"
RECOMPUTE_TABLE = "public.fact_water_quantity_recompute"

SAFE_SUFFIX_RE = re.compile(r"^[A-Za-z0-9_]+$")

# Mirrors SchemePerformanceSchedulerRepository.insertDailySchemePerformanceScores. Kept as one
# expression so the replay cannot disagree with the scheduler on the thresholds; the "5" is the
# assumed persons-per-household in that query's own comment.
PERFORMANCE_SCORE_CASE = """
    CASE
        WHEN COALESCE(supply.total_water_supplied, 0) <= 0 THEN 0.0
        WHEN COALESCE(supply.total_water_supplied, 0) <
             (
                 COALESCE(ds.fhtc_count, 0) * COALESCE(ds.house_hold_count, 0) * 5
                 * COALESCE(dt.required_lpcd, 0)
             ) THEN 0.5
        ELSE 1.0
    END
"""


# --------------------------------------------------------------------------------------
# helpers
# --------------------------------------------------------------------------------------

def load_recompute_sql() -> str:
    """Reads the canonical recompute definition shared with the parity test."""
    if not RECOMPUTE_SQL_PATH.is_file():
        sys.exit(f"missing recompute definition: {RECOMPUTE_SQL_PATH}\n"
                 "Run this from a checkout of the repository — the SQL is deliberately not "
                 "duplicated in this script.")
    return RECOMPUTE_SQL_PATH.read_text(encoding="utf-8")


def describe_connection(conn) -> str:
    with conn.cursor() as cur:
        cur.execute("SELECT current_database(), current_user, inet_server_addr(), version()")
        db, user, host, version = cur.fetchone()
    return f"{db} as {user} on {host or 'local socket'} — {version.split(',')[0]}"


def resolve_window(conn, start: dt.date | None, end: dt.date | None) -> tuple[dt.date, dt.date]:
    """Window defaults to the full extent of the fact table.

    Deliberately starts before go-live: the pre-go-live rows written by
    backfill_water_quantity.sql are in cubic metres too and need the same conversion, so
    nobody has to pin down the exact go-live date.
    """
    if start and end:
        return start, end
    with conn.cursor() as cur:
        cur.execute(f"SELECT MIN(date), MAX(date) FROM {WATER_TABLE}")
        min_date, max_date = cur.fetchone()
    if min_date is None:
        sys.exit(f"{WATER_TABLE} is empty — nothing to repair, and almost certainly the "
                 "wrong database.")
    return start or min_date, end or max_date


def month_chunks(start: dt.date, end: dt.date):
    """Yields [chunk_start, chunk_end] calendar-month slices covering the window inclusively."""
    cursor = start.replace(day=1)
    while cursor <= end:
        next_month = (cursor.replace(day=28) + dt.timedelta(days=4)).replace(day=1)
        yield max(cursor, start), min(next_month - dt.timedelta(days=1), end)
        cursor = next_month


def scalar(conn, sql: str, params=None):
    with conn.cursor() as cur:
        cur.execute(sql, params)
        row = cur.fetchone()
    return row[0] if row else None


# --------------------------------------------------------------------------------------
# phase 0 — backup
# --------------------------------------------------------------------------------------

def _backup_covers_window(conn, backup_table: str, live_table: str, date_column: str,
                           start: dt.date, end: dt.date) -> bool:
    """True iff every row currently in live_table's window has a matching id in backup_table.

    Id-presence rather than MIN/MAX(date_column) on purpose: a resumed run may ask for a window
    wider than, narrower than, or offset from the one the kept backup was originally taken over,
    and comparing endpoints can't tell "backup is missing dates in the middle" from "there's
    genuinely no data there". Checking that every live row's id is backed up catches both.
    """
    live_count = scalar(conn, f"SELECT COUNT(*) FROM {live_table} WHERE {date_column} BETWEEN %s AND %s",
                        (start, end))
    if not live_count:
        return True
    covered_count = scalar(conn, f"""
        SELECT COUNT(*) FROM {live_table} t
        WHERE t.{date_column} BETWEEN %s AND %s
          AND EXISTS (SELECT 1 FROM {backup_table} b WHERE b.id = t.id)
        """, (start, end))
    return covered_count == live_count


def backup(conn, suffix: str, start: dt.date, end: dt.date, overwrite: bool) -> None:
    water_backup = f"public.fact_water_quantity_backup_{suffix}"
    performance_backup = f"public.fact_scheme_performance_backup_{suffix}"

    # Resuming an interrupted run means re-running --execute with the same --backup-suffix, and
    # re-taking the backup then would capture the half-repaired table and destroy the only copy of
    # the original values. An existing backup is therefore kept, not replaced — but only if it
    # actually covers this run's window; a --backup-suffix reused across a wider or shifted window
    # would otherwise silently leave the extra rows with no rollback point.
    water_backup_exists = bool(scalar(conn, "SELECT to_regclass(%s)", (water_backup,)))
    if water_backup_exists and not overwrite:
        performance_backup_exists = bool(scalar(conn, "SELECT to_regclass(%s)", (performance_backup,)))
        if not performance_backup_exists:
            sys.exit(f"{water_backup} exists but {performance_backup} does not — backups for a "
                     "suffix must be taken together. Use --overwrite-backup or a different "
                     "--backup-suffix.")
        if not (_backup_covers_window(conn, water_backup, WATER_TABLE, "date", start, end)
                and _backup_covers_window(conn, performance_backup, PERFORMANCE_TABLE,
                                          "last_water_supply_date", start, end)):
            sys.exit(f"{water_backup} / {performance_backup} do not cover every row in "
                     f"{start}..{end} — this --backup-suffix was taken over a different window. "
                     "Use --overwrite-backup to retake it (only safe before any --execute has run "
                     "against this suffix) or pick a fresh --backup-suffix.")
        LOG.info("phase 0: %s already exists and covers %s..%s — kept as the rollback point, "
                 "not re-taken", water_backup, start, end)
    else:
        with conn.cursor() as cur:
            cur.execute(f"DROP TABLE IF EXISTS {water_backup}")
            cur.execute(f"""
                CREATE TABLE {water_backup} AS
                SELECT * FROM {WATER_TABLE} WHERE date BETWEEN %s AND %s
                """, (start, end))
            cur.execute(f"DROP TABLE IF EXISTS {performance_backup}")
            cur.execute(f"""
                CREATE TABLE {performance_backup} AS
                SELECT * FROM {PERFORMANCE_TABLE} WHERE last_water_supply_date BETWEEN %s AND %s
                """, (start, end))

    water_rows = scalar(conn, f"SELECT COUNT(*) FROM {water_backup}")
    water_sum = scalar(conn, f"SELECT COALESCE(SUM(water_quantity), 0) FROM {water_backup}")
    performance_rows = scalar(conn, f"SELECT COUNT(*) FROM {performance_backup}")
    performance_sum = scalar(
        conn, f"SELECT COALESCE(SUM(performance_score), 0) FROM {performance_backup}")

    LOG.info("phase 0: %s — %d row(s), SUM(water_quantity)=%s",
             water_backup, water_rows, water_sum)
    LOG.info("phase 0: %s — %d row(s), SUM(performance_score)=%s",
             performance_backup, performance_rows, performance_sum)
    if water_rows == 0:
        LOG.warning("backed up ZERO water rows for %s..%s — check the window and the database",
                    start, end)


# --------------------------------------------------------------------------------------
# phase 1 — identify
# --------------------------------------------------------------------------------------

def ensure_backfill_index(conn) -> None:
    """Composite index the recompute's cur/prev LATERAL lookups need to avoid a per-row scan.

    Both lookups in recompute_water_quantity.sql filter by (tenant_id, scheme_id) and order by
    (reading_date, reading_at, id) — the only existing index covers (tenant_id, scheme_id), so
    without this one every row of fact_water_quantity_table drives an unindexed scan of its
    scheme's whole reading history. It also matches the ORDER BY on
    FactMeterReadingRepository's findTopBy...ReadingDateOrderByReadingAtDescIdDesc and
    findLatestBefore, so it keeps paying for itself on the live ingestion path after the backfill.
    """
    with conn.cursor() as cur:
        cur.execute("""
            CREATE INDEX IF NOT EXISTS idx_fact_meter_reading_tenant_scheme_date_lookup
                ON analytics_schema.fact_meter_reading_table
                (tenant_id, scheme_id, reading_date DESC, reading_at DESC, id DESC)
            """)
    conn.commit()
    LOG.info("prep: idx_fact_meter_reading_tenant_scheme_date_lookup present on "
             "fact_meter_reading_table")


def identify(conn, start: dt.date, end: dt.date) -> dict:
    recompute_sql = load_recompute_sql()
    with conn.cursor() as cur:
        cur.execute(f"DROP TABLE IF EXISTS {RECOMPUTE_TABLE}")
        cur.execute(f"""
            CREATE TABLE {RECOMPUTE_TABLE} AS
            WITH recompute AS (
            {recompute_sql}
            )
            SELECT id, tenant_id, scheme_id, date, old_qty, new_qty,
                   current_reading, previous_reading, previous_date, is_latest,
                   FALSE AS applied
            FROM recompute
            WHERE date BETWEEN %s AND %s
            """, (start, end))
        cur.execute(f"CREATE UNIQUE INDEX ON {RECOMPUTE_TABLE} (id)")
        cur.execute(f"CREATE INDEX ON {RECOMPUTE_TABLE} (date)")

    stats = {
        "total": scalar(conn, f"SELECT COUNT(*) FROM {RECOMPUTE_TABLE}"),
        "changing": scalar(conn, f"SELECT COUNT(*) FROM {RECOMPUTE_TABLE} "
                                 "WHERE new_qty IS NOT NULL AND new_qty <> old_qty"),
        "already_correct": scalar(conn, f"SELECT COUNT(*) FROM {RECOMPUTE_TABLE} "
                                        "WHERE new_qty IS NOT NULL AND new_qty = old_qty"),
        "no_reading": scalar(conn, f"SELECT COUNT(*) FROM {RECOMPUTE_TABLE} "
                                   "WHERE new_qty IS NULL"),
        "no_baseline": scalar(conn, f"SELECT COUNT(*) FROM {RECOMPUTE_TABLE} "
                                    "WHERE current_reading IS NOT NULL "
                                    "AND previous_reading IS NULL"),
        "duplicates": scalar(conn, f"SELECT COUNT(*) FROM {RECOMPUTE_TABLE} "
                                   "WHERE NOT is_latest"),
    }
    LOG.info("phase 1: %s built — %d row(s) in %s..%s",
             RECOMPUTE_TABLE, stats["total"], start, end)
    LOG.info("phase 1:   %d changing, %d already correct, %d with no reading on their date",
             stats["changing"], stats["already_correct"], stats["no_reading"])
    LOG.info("phase 1:   %d with no prior reading (correctly 0, previously the whole meter index)",
             stats["no_baseline"])
    if stats["duplicates"]:
        LOG.info("phase 1:   %d shadow duplicate row(s) behind the latest row of their "
                 "(tenant, scheme, date) — repaired to the same value so none is left in m3",
                 stats["duplicates"])
    return stats


def report_exceptions(conn, limit: int) -> int:
    """Rows with no reading on their date but a non-zero stored quantity.

    Live ingestion writes nothing for such a day, so the recompute has no defensible value
    to put there. They are listed, never touched — silently zeroing them would discard data
    this script cannot re-derive.
    """
    with conn.cursor() as cur:
        cur.execute(f"""
            SELECT id, tenant_id, scheme_id, date, old_qty
            FROM {RECOMPUTE_TABLE}
            WHERE new_qty IS NULL AND old_qty <> 0
            ORDER BY old_qty DESC, id
            """)
        rows = cur.fetchall()
    if not rows:
        LOG.info("exceptions: none — every row with no reading on its date is already 0")
        return 0
    LOG.warning("exceptions: %d row(s) have NO reading on their date but a non-zero quantity. "
                "Left untouched — no reading means no derivable volume.", len(rows))
    for row_id, tenant_id, scheme_id, date, old_qty in rows[:limit]:
        LOG.warning("  id=%s tenant=%s scheme=%s date=%s water_quantity=%s",
                    row_id, tenant_id, scheme_id, date, old_qty)
    if len(rows) > limit:
        LOG.warning("  ... %d more; full list: SELECT * FROM %s WHERE new_qty IS NULL "
                    "AND old_qty <> 0", len(rows) - limit, RECOMPUTE_TABLE)
    return len(rows)


def report_outliers(conn, threshold_litres: int, limit: int) -> None:
    """The review list: corrected values still too large to be a real day's supply.

    Mirrors the analytics.water-quantity.implausible-daily-cubic-metres warning the service
    now emits, so the same days show up here before the write as they would in the logs after.
    """
    with conn.cursor() as cur:
        cur.execute(f"""
            SELECT id, tenant_id, scheme_id, date, old_qty, new_qty,
                   previous_reading, current_reading, previous_date
            FROM {RECOMPUTE_TABLE}
            WHERE new_qty > %s
            ORDER BY new_qty DESC
            LIMIT %s
            """, (threshold_litres, limit))
        rows = cur.fetchall()
    if not rows:
        LOG.info("outliers: none above %d L/day", threshold_litres)
        return
    LOG.warning("outliers: %d row(s) recompute to more than %d L/day — a bad reading, not a "
                "bad recompute. Applied as derived; review before accepting.",
                len(rows), threshold_litres)
    for r in rows:
        LOG.warning("  id=%s tenant=%s scheme=%s date=%s  %s -> %s L  (reading %s on %s -> %s)",
                    r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[8], r[7])


# --------------------------------------------------------------------------------------
# phase 2 — apply
# --------------------------------------------------------------------------------------

def apply_water(conn, start: dt.date, end: dt.date) -> int:
    """Applies the recompute one calendar month at a time, committing each chunk.

    The UPDATE is guarded on the value this script read (water_quantity = old_qty), which is
    what makes it resumable and safe to run against a live system: a row the new code rewrote
    since phase 1 simply fails the guard and keeps the value the new code wrote.
    """
    total = 0
    for chunk_start, chunk_end in month_chunks(start, end):
        with conn.cursor() as cur:
            cur.execute(f"""
                UPDATE {WATER_TABLE} AS fwq
                SET water_quantity = r.new_qty,
                    updated_at = NOW()
                FROM {RECOMPUTE_TABLE} r
                WHERE fwq.id = r.id
                  AND r.date BETWEEN %s AND %s
                  AND r.new_qty IS NOT NULL
                  AND r.new_qty <> r.old_qty
                  AND fwq.water_quantity = r.old_qty
                """, (chunk_start, chunk_end))
            updated = cur.rowcount
            cur.execute(f"""
                UPDATE {RECOMPUTE_TABLE} r
                SET applied = TRUE
                FROM {WATER_TABLE} fwq
                WHERE fwq.id = r.id
                  AND r.date BETWEEN %s AND %s
                  AND r.new_qty IS NOT NULL
                  AND fwq.water_quantity = r.new_qty
                """, (chunk_start, chunk_end))
        conn.commit()
        total += updated
        LOG.info("phase 2: %s..%s — %d row(s) updated", chunk_start, chunk_end, updated)

    skipped = scalar(conn, f"""
        SELECT COUNT(*) FROM {RECOMPUTE_TABLE}
        WHERE new_qty IS NOT NULL AND new_qty <> old_qty AND NOT applied
        """)
    if skipped:
        LOG.warning("phase 2: %d row(s) failed the value guard — rewritten by live ingestion "
                    "since phase 1, so they already hold a correctly derived value. Re-run "
                    "--dry-run to confirm.", skipped)
    LOG.info("phase 2: %d row(s) updated in total", total)
    return total


# --------------------------------------------------------------------------------------
# phase 3 — performance
# --------------------------------------------------------------------------------------

def apply_performance(conn, start: dt.date, end: dt.date) -> int:
    """Replays the daily performance score over the corrected quantities.

    Updates existing rows only. The scheduler's own INSERT is guarded by NOT EXISTS, so it
    would no-op over history; and creating scores for days the scheduler never ran would
    invent history rather than repair it. Days with no performance row are counted and
    reported, not filled in.
    """
    total = 0
    for chunk_start, chunk_end in month_chunks(start, end):
        with conn.cursor() as cur:
            cur.execute(f"""
                WITH latest AS (
                    SELECT DISTINCT ON (fwq.tenant_id, fwq.scheme_id, fwq.date)
                           fwq.tenant_id, fwq.scheme_id, fwq.date, fwq.water_quantity
                    FROM {WATER_TABLE} fwq
                    WHERE fwq.date BETWEEN %s AND %s
                    ORDER BY fwq.tenant_id, fwq.scheme_id, fwq.date,
                             fwq.updated_at DESC, fwq.id DESC
                ),
                supply AS (
                    SELECT tenant_id, scheme_id, date,
                           SUM(water_quantity) AS total_water_supplied
                    FROM latest
                    GROUP BY tenant_id, scheme_id, date
                ),
                scores AS (
                    SELECT ds.tenant_id,
                           ds.scheme_id,
                           supply.date,
                           {PERFORMANCE_SCORE_CASE} AS performance_score
                    FROM analytics_schema.dim_scheme_table ds
                    JOIN analytics_schema.dim_tenant_table dt
                      ON dt.tenant_id = ds.tenant_id
                    JOIN supply
                      ON supply.tenant_id = ds.tenant_id
                     AND supply.scheme_id = ds.scheme_id
                    WHERE ds.operating_status > 0
                )
                UPDATE {PERFORMANCE_TABLE} fp
                SET performance_score = scores.performance_score,
                    updated_at = NOW()
                FROM scores
                WHERE fp.tenant_id = scores.tenant_id
                  AND fp.scheme_id = scores.scheme_id
                  AND fp.last_water_supply_date = scores.date
                  AND fp.performance_score IS DISTINCT FROM scores.performance_score
                """, (chunk_start, chunk_end))
            updated = cur.rowcount
        conn.commit()
        total += updated
        LOG.info("phase 3: %s..%s — %d score(s) updated", chunk_start, chunk_end, updated)

    missing = scalar(conn, f"""
        SELECT COUNT(*)
        FROM (
            SELECT DISTINCT fwq.tenant_id, fwq.scheme_id, fwq.date
            FROM {WATER_TABLE} fwq
            JOIN analytics_schema.dim_scheme_table ds
              ON ds.tenant_id = fwq.tenant_id AND ds.scheme_id = fwq.scheme_id
             AND ds.operating_status > 0
            WHERE fwq.date BETWEEN %s AND %s
              AND NOT EXISTS (
                  SELECT 1 FROM {PERFORMANCE_TABLE} fp
                  WHERE fp.tenant_id = fwq.tenant_id
                    AND fp.scheme_id = fwq.scheme_id
                    AND fp.last_water_supply_date = fwq.date
              )
        ) gaps
        """, (start, end))
    if missing:
        LOG.info("phase 3: %d (scheme, date) pair(s) have water but no performance row — the "
                 "scheduler never scored those days. Not created here.", missing)
    LOG.info("phase 3: %d score(s) updated in total", total)
    return total


# --------------------------------------------------------------------------------------
# phase 4 — verify
# --------------------------------------------------------------------------------------

def verify(conn, start: dt.date, end: dt.date) -> int:
    """Reports on an applied (or partially applied) repair and returns the still-unrepaired count.

    That count (at_old) is the caller's signal for a non-zero exit status: rows recomputable from a
    reading but still holding their pre-repair value are a failure to finish the job. It is distinct
    from report_exceptions's rows, which have no reading to recompute from and are intentionally
    left untouched — informational, not a failure.
    """
    if not scalar(conn, "SELECT to_regclass(%s)", (RECOMPUTE_TABLE,)):
        sys.exit(f"{RECOMPUTE_TABLE} does not exist — run --dry-run or --execute first.")

    with conn.cursor() as cur:
        cur.execute(f"""
            SELECT COUNT(*),
                   COUNT(*) FILTER (WHERE fwq.water_quantity = r.new_qty),
                   COUNT(*) FILTER (WHERE fwq.water_quantity = r.old_qty
                                      AND r.old_qty <> r.new_qty),
                   COUNT(*) FILTER (WHERE fwq.water_quantity NOT IN (r.old_qty, r.new_qty))
            FROM {RECOMPUTE_TABLE} r
            JOIN {WATER_TABLE} fwq ON fwq.id = r.id
            WHERE r.new_qty IS NOT NULL
            """)
        total, at_new, at_old, elsewhere = cur.fetchone()
    LOG.info("verify: %d recomputable row(s) — %d at the corrected value, %d still at the old "
             "value, %d at neither (rewritten by live ingestion since)",
             total, at_new, at_old, elsewhere)
    if at_old:
        LOG.warning("verify: %d row(s) were NOT applied — re-run --execute", at_old)

    with conn.cursor() as cur:
        cur.execute(f"""
            SELECT MIN(old_qty), MAX(old_qty), ROUND(AVG(old_qty)),
                   MIN(new_qty), MAX(new_qty), ROUND(AVG(new_qty))
            FROM {RECOMPUTE_TABLE}
            WHERE new_qty IS NOT NULL
            """)
        row = cur.fetchone()
    # Distribution as of whenever phase 1 last ran. Inside --execute that is the pre-repair data, so
    # the two lines are genuinely before/after. A standalone --verify long after the fact reads a
    # recompute table built from already-corrected rows, where the two lines simply agree — which is
    # itself the confirmation that nothing has drifted since.
    LOG.info("verify: old_qty  min=%s max=%s avg=%s", row[0], row[1], row[2])
    LOG.info("verify: new_qty  min=%s max=%s avg=%s", row[3], row[4], row[5])

    report_exceptions(conn, limit=20)

    supplied_before = scalar(conn, f"""
        SELECT COALESCE(SUM(old_qty), 0) FROM {RECOMPUTE_TABLE}
        WHERE is_latest AND new_qty IS NOT NULL
        """)
    supplied_after = scalar(conn, f"""
        SELECT COALESCE(SUM(new_qty), 0) FROM {RECOMPUTE_TABLE}
        WHERE is_latest AND new_qty IS NOT NULL
        """)
    LOG.info("verify: total supplied over %s..%s — %s L at old_qty, %s L at new_qty",
             start, end, supplied_before, supplied_after)
    LOG.info("verify: remember to flush the Redis dashboard keys; the cache TTL is 24h")

    return at_old


# --------------------------------------------------------------------------------------
# rollback
# --------------------------------------------------------------------------------------

def rollback(conn, suffix: str) -> None:
    water_backup = f"public.fact_water_quantity_backup_{suffix}"
    performance_backup = f"public.fact_scheme_performance_backup_{suffix}"
    for table in (water_backup, performance_backup):
        if not scalar(conn, "SELECT to_regclass(%s)", (table,)):
            sys.exit(f"{table} does not exist — check --backup-suffix.")

    with conn.cursor() as cur:
        cur.execute(f"""
            UPDATE {WATER_TABLE} fwq
            SET water_quantity = b.water_quantity,
                updated_at = b.updated_at
            FROM {water_backup} b
            WHERE fwq.id = b.id
              AND fwq.water_quantity IS DISTINCT FROM b.water_quantity
            """)
        water_restored = cur.rowcount
        cur.execute(f"""
            UPDATE {PERFORMANCE_TABLE} fp
            SET performance_score = b.performance_score,
                updated_at = b.updated_at
            FROM {performance_backup} b
            WHERE fp.id = b.id
              AND fp.performance_score IS DISTINCT FROM b.performance_score
            """)
        performance_restored = cur.rowcount
    conn.commit()
    LOG.info("rollback: restored %d water row(s) and %d performance row(s) from %s",
             water_restored, performance_restored, suffix)


# --------------------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------------------

def iso_date(value: str) -> dt.date:
    try:
        return dt.date.fromisoformat(value)
    except ValueError:
        raise argparse.ArgumentTypeError(f"expected YYYY-MM-DD, got {value!r}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--dry-run", action="store_true",
                      help="(default) back up and identify; write nothing to the fact tables")
    mode.add_argument("--execute", action="store_true", help="apply the repair")
    mode.add_argument("--verify", action="store_true",
                      help="report on an already-applied repair")
    mode.add_argument("--rollback", action="store_true",
                      help="restore from a phase-0 backup; needs --backup-suffix")
    parser.add_argument("--phase", choices=("water", "performance", "all"), default="all",
                        help="performance must run after water and over the same window")
    parser.add_argument("--dsn", default=os.environ.get("ANALYTICS_DSN"),
                        help="libpq DSN; defaults to $ANALYTICS_DSN")
    parser.add_argument("--start-date", type=iso_date, default=None,
                        help="defaults to MIN(date) of the fact table")
    parser.add_argument("--end-date", type=iso_date, default=None,
                        help="defaults to MAX(date) of the fact table")
    parser.add_argument("--backup-suffix", default=None,
                        help="names the phase-0 backup tables; defaults to a UTC timestamp")
    parser.add_argument("--overwrite-backup", action="store_true",
                        help="re-take a phase-0 backup that already exists, discarding the "
                             "original values it holds")
    parser.add_argument("--implausible-daily-cubic-metres", type=int, default=100_000,
                        help="outlier review threshold; matches the service's default")
    parser.add_argument("--statement-timeout", default="15min",
                        help="server-side statement_timeout for every statement")
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args()

    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO,
                        format="%(levelname)s %(message)s")

    if not args.dsn:
        sys.exit("no DSN: pass --dsn or set ANALYTICS_DSN")

    suffix = args.backup_suffix or dt.datetime.now(dt.timezone.utc).strftime("%Y%m%d_%H%M%S")
    if not SAFE_SUFFIX_RE.match(suffix):
        sys.exit(f"--backup-suffix must be alphanumeric/underscore, got {suffix!r}")

    conn = psycopg2.connect(args.dsn)
    conn.autocommit = False
    try:
        with conn.cursor() as cur:
            cur.execute("SET statement_timeout = %s", (args.statement_timeout,))
        LOG.info("connected: %s", describe_connection(conn))

        if args.rollback:
            if not args.backup_suffix:
                sys.exit("--rollback needs --backup-suffix (the timestamp phase 0 printed)")
            rollback(conn, suffix)
            return 0

        start, end = resolve_window(conn, args.start_date, args.end_date)
        LOG.info("window: %s..%s, phase=%s", start, end, args.phase)

        if args.verify:
            at_old = verify(conn, start, end)
            return 1 if at_old else 0

        backup(conn, suffix, start, end, args.overwrite_backup)
        conn.commit()

        ensure_backfill_index(conn)

        stats = identify(conn, start, end)
        conn.commit()
        exceptions = report_exceptions(conn, limit=20)
        report_outliers(conn, args.implausible_daily_cubic_metres * 1000, limit=20)

        if not args.execute:
            LOG.info("dry run — nothing written to %s. Review %s, then re-run with --execute.",
                     WATER_TABLE, RECOMPUTE_TABLE)
            LOG.info("dry run: %d row(s) would change, %d left as exceptions",
                     stats["changing"], exceptions)
            return 0

        if args.phase in ("water", "all"):
            apply_water(conn, start, end)
        if args.phase in ("performance", "all"):
            apply_performance(conn, start, end)

        at_old = verify(conn, start, end)
        LOG.info("done. Backup suffix for --rollback: %s", suffix)
        return 1 if at_old else 0
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


if __name__ == "__main__":
    sys.exit(main())
