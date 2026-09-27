-- =============================================================================
-- 10x spike correction  —  SOURCE DB (jalsoochak)  —  STEP 1: IDENTIFY + BACKUP
-- =============================================================================
-- RUN THIS AGAINST THE SOURCE DATABASE (the one that has tenant_as).
-- The analytics DB is fixed by the separate decimalfix10x_analytics_*.sql set —
-- the two databases cannot be joined, so each self-detects the same spikes.
--
-- Scope:  tenant_as ,  reading_date  2026-05-27 .. 2026-06-07.
--
-- Detection (same rule as the analytics side): collapse to the latest reading
-- per scheme per day, compare each to the *immediately previous calendar day's*
-- reading, flag when >= 8x OR the text contains the previous value, and only for
-- the scheme's *latest* reading overall.
--
-- READ-ONLY against real data. Only fills public.flow_reading_10x_fix_source.
-- Review it, DELETE any row you do NOT want fixed (e.g. meter_change_reason set),
-- then run decimalfix10x_source_02_apply.sql.
-- =============================================================================

BEGIN;

CREATE TABLE IF NOT EXISTS public.flow_reading_10x_fix_source (
    fix_id              BIGSERIAL PRIMARY KEY,
    tenant_schema       TEXT    NOT NULL,
    scheme_id           INTEGER NOT NULL,
    reading_date        DATE    NOT NULL,
    src_id              BIGINT  NOT NULL,
    old_extracted       NUMERIC,
    old_confirmed       NUMERIC,
    old_payload         JSONB,
    new_extracted       NUMERIC,
    new_confirmed       NUMERIC,
    prev_confirmed      NUMERIC,
    prev_date           DATE,
    ratio               NUMERIC,
    image_url           TEXT,
    meter_change_reason TEXT,
    applied             BOOLEAN NOT NULL DEFAULT FALSE,
    applied_at          TIMESTAMP,
    detected_at         TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (src_id)
);

-- Safe to re-run: drop only candidates that were never applied.
DELETE FROM public.flow_reading_10x_fix_source WHERE applied = FALSE;

INSERT INTO public.flow_reading_10x_fix_source (
    tenant_schema, scheme_id, reading_date, src_id,
    old_extracted, old_confirmed, old_payload, new_extracted, new_confirmed,
    prev_confirmed, prev_date, ratio, image_url, meter_change_reason
)
WITH per_day AS (
    -- latest reading per scheme per day (mirrors the one-row-per-day analytics view)
    SELECT DISTINCT ON (scheme_id, reading_date)
           id, scheme_id, reading_date, observation_time,
           confirmed_reading, extracted_reading, payload_json, image_url, meter_change_reason
    FROM   tenant_as.flow_reading_table
    WHERE  deleted_at IS NULL
      AND  confirmed_reading IS NOT NULL
    ORDER  BY scheme_id, reading_date, observation_time DESC, id DESC
),
seq AS (
    SELECT p.*,
           LAG(confirmed_reading) OVER w AS prev_confirmed,
           LAG(reading_date)      OVER w AS prev_date
    FROM   per_day p
    WINDOW w AS (PARTITION BY scheme_id ORDER BY reading_date)
),
candidates AS (
    SELECT s.*,
           ROUND(s.confirmed_reading / NULLIF(s.prev_confirmed, 0), 2) AS ratio
    FROM   seq s
    WHERE  s.reading_date BETWEEN DATE '2026-05-27' AND DATE '2026-06-07'
      AND  s.prev_date = s.reading_date - 1
      AND  s.prev_confirmed > 0
      AND  (    s.confirmed_reading / s.prev_confirmed >= 8
             OR floor(s.confirmed_reading)::bigint::text
                  LIKE '%' || floor(s.prev_confirmed)::bigint::text || '%' )
      AND  NOT EXISTS (
              SELECT 1 FROM tenant_as.flow_reading_table f
              WHERE  f.scheme_id = s.scheme_id
                AND  f.deleted_at IS NULL
                AND  f.reading_date > s.reading_date )
)
SELECT 'tenant_as', c.scheme_id, c.reading_date, c.id,
       c.extracted_reading, c.confirmed_reading, c.payload_json,
       trim_scale(c.extracted_reading / 10), trim_scale(c.confirmed_reading / 10),
       c.prev_confirmed, c.prev_date, c.ratio, c.image_url, c.meter_change_reason
FROM   candidates c
ON CONFLICT (src_id) DO NOTHING;

-- Preview  (=== source 10x candidates: tenant_as ===)
SELECT fix_id, scheme_id, reading_date, prev_date, prev_confirmed, ratio,
       old_confirmed, new_confirmed, meter_change_reason, image_url
FROM   public.flow_reading_10x_fix_source
WHERE  applied = FALSE
ORDER  BY scheme_id, reading_date;

-- NEXT: curate the table (DELETE meter replacements / false positives),
-- then run decimalfix10x_source_02_apply.sql.

COMMIT;
