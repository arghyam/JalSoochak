-- =============================================================================
-- 10x spike correction  —  ANALYTICS DB  —  STEP 1: IDENTIFY + BACKUP
-- =============================================================================
-- RUN THIS AGAINST THE ANALYTICS DATABASE (the one that has analytics_schema).
-- The source database (jalsoochak / tenant_as) is fixed by the separate
-- decimalfix10x_source_*.sql set — the two DBs cannot be joined.
--
-- Scope:  tenant_id = 1 ,  reading_date  2026-05-27 .. 2026-06-07.
--
-- Detection (your query): on fact_meter_reading_table, per scheme, compare a
-- reading to the *immediately previous calendar day's* reading and flag it when
-- it is >= 8x that value OR its text contains the previous value (the tell-tale
-- appended digit of a 10x). Only the scheme's *latest* reading is considered.
--
-- READ-ONLY against real data. Only fills public.flow_reading_10x_fix_analytics.
-- Review it, DELETE any row you do NOT want fixed, then run
-- decimalfix10x_analytics_02_apply.sql.
-- =============================================================================

BEGIN;

CREATE TABLE IF NOT EXISTS public.flow_reading_10x_fix_analytics (
    fix_id          BIGSERIAL PRIMARY KEY,
    tenant_id       INTEGER NOT NULL,
    scheme_id       INTEGER NOT NULL,
    reading_date    DATE    NOT NULL,
    fmr_id          BIGINT  NOT NULL,
    old_extracted   INTEGER,
    old_confirmed   INTEGER,
    new_extracted   INTEGER,
    new_confirmed   INTEGER,
    prev_confirmed  INTEGER,
    prev_date       DATE,
    ratio           NUMERIC,
    image_url       TEXT,
    applied         BOOLEAN NOT NULL DEFAULT FALSE,
    applied_at      TIMESTAMP,
    detected_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (fmr_id)
);

-- Safe to re-run: drop only candidates that were never applied.
DELETE FROM public.flow_reading_10x_fix_analytics WHERE applied = FALSE;

INSERT INTO public.flow_reading_10x_fix_analytics (
    tenant_id, scheme_id, reading_date, fmr_id,
    old_extracted, old_confirmed, new_extracted, new_confirmed,
    prev_confirmed, prev_date, ratio, image_url
)
WITH per_day AS (
    -- latest reading per scheme per day (mirrors the source-side one-row-per-day view) so the LAG
    -- below compares against the previous *calendar day's* latest reading, not another same-day row.
    SELECT DISTINCT ON (scheme_id, reading_date)
           id AS fmr_id, scheme_id, reading_date, reading_at,
           confirmed_reading, extracted_reading, image_url
    FROM   analytics_schema.fact_meter_reading_table
    WHERE  tenant_id = 1
      AND  confirmed_reading IS NOT NULL
    ORDER  BY scheme_id, reading_date, reading_at DESC, id DESC
),
an_readings AS (
    SELECT p.*,
           LAG(confirmed_reading) OVER w AS prev_confirmed,
           LAG(reading_date)      OVER w AS prev_date
    FROM   per_day p
    WINDOW w AS (PARTITION BY scheme_id ORDER BY reading_date)
),
candidates AS (
    SELECT a.*,
           ROUND(a.confirmed_reading::numeric / NULLIF(a.prev_confirmed, 0), 2) AS ratio
    FROM   an_readings a
    WHERE  a.reading_date BETWEEN DATE '2026-05-27' AND DATE '2026-06-07'
      AND  a.prev_date = a.reading_date - 1
      AND  a.prev_confirmed > 0
      AND  (    a.confirmed_reading::numeric / a.prev_confirmed >= 8
             OR a.confirmed_reading::text LIKE '%' || a.prev_confirmed::text || '%' )
      AND  NOT EXISTS (
              SELECT 1 FROM analytics_schema.fact_meter_reading_table f
              WHERE  f.tenant_id = 1
                AND  f.scheme_id = a.scheme_id
                AND  f.reading_date > a.reading_date )
)
SELECT 1, c.scheme_id, c.reading_date, c.fmr_id,
       c.extracted_reading, c.confirmed_reading,
       floor(c.extracted_reading / 10.0)::int, floor(c.confirmed_reading / 10.0)::int,
       c.prev_confirmed, c.prev_date, c.ratio, c.image_url
FROM   candidates c
ON CONFLICT (fmr_id) DO NOTHING;

-- Preview  (=== analytics 10x candidates: tenant_id=1 ===)
SELECT fix_id, scheme_id, reading_date, prev_date, prev_confirmed, ratio,
       old_confirmed, new_confirmed, image_url
FROM   public.flow_reading_10x_fix_analytics
WHERE  applied = FALSE
ORDER  BY scheme_id, reading_date;

-- NEXT: curate the table, then run decimalfix10x_analytics_02_apply.sql.
-- Cross-check this candidate list against the source preview
-- (decimalfix10x_source_01_identify.sql) — they should name the same readings.

COMMIT;
