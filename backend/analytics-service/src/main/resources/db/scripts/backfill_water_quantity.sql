-- ============================================================
-- BACKFILL: fact_water_quantity_table FROM fact_meter_reading_table
-- ============================================================
-- For each (tenant_id, scheme_id), generates a full calendar row
-- from its first to last reading_date.
--
-- Rules:
--   effective_confirmed_reading = carry forward last known confirmed reading
--   water_quantity = GREATEST(0, today_effective - prev_day_effective)
--   Gap days (no reading) get water_quantity = 0, user_id = NULL,
--   submission_status = NULL.
--   First day per scheme: prev_day_effective defaults to 0 (matches
--   existing application behaviour in FactServiceImpl).
--
-- Idempotent: skips (tenant_id, scheme_id, date) rows that already exist.
-- ============================================================

BEGIN;

-- ----------------------------------------------------------------
-- Step 1: Ensure dim_date_table covers every date in the reading range.
--         Required because fact_water_quantity_table.date is a FK to
--         dim_date_table.full_date (added in V8).
-- ----------------------------------------------------------------
INSERT INTO analytics_schema.dim_date_table (
    date_key, full_date, day, month, month_name,
    quarter, year, week, is_weekend, fiscal_year
)
SELECT
    TO_CHAR(d, 'YYYYMMDD')::INT              AS date_key,
    d::DATE                                   AS full_date,
    EXTRACT(DAY     FROM d)::INT              AS day,
    EXTRACT(MONTH   FROM d)::INT              AS month,
    TO_CHAR(d, 'FMMonth')                     AS month_name,
    EXTRACT(QUARTER FROM d)::INT              AS quarter,
    EXTRACT(YEAR    FROM d)::INT              AS year,
    EXTRACT(WEEK    FROM d)::INT              AS week,
    (EXTRACT(ISODOW FROM d) IN (6, 7))        AS is_weekend,
    CASE
        WHEN EXTRACT(MONTH FROM d) >= 4
            THEN EXTRACT(YEAR FROM d)::INT
        ELSE (EXTRACT(YEAR FROM d) - 1)::INT
    END                                       AS fiscal_year
FROM (
    SELECT generate_series(
        (SELECT MIN(reading_date) FROM analytics_schema.fact_meter_reading_table WHERE tenant_id = 1),
        (SELECT MAX(reading_date) FROM analytics_schema.fact_meter_reading_table WHERE tenant_id = 1),
        '1 day'::INTERVAL
    ) AS d
) dates
ON CONFLICT (date_key) DO NOTHING;

-- ----------------------------------------------------------------
-- Step 2: Backfill water quantity rows.
-- ----------------------------------------------------------------
WITH
-- Best reading per (tenant, scheme, day):
--   prefer confirmed_reading over extracted_reading;
--   when multiple rows share the same day, pick the latest reading_at.
best_reading_per_day AS (
    SELECT DISTINCT ON (tenant_id, scheme_id, reading_date)
        tenant_id,
        scheme_id,
        reading_date,
        COALESCE(confirmed_reading, extracted_reading) AS effective_reading,
        user_id,
        submission_status
    FROM analytics_schema.fact_meter_reading_table
    WHERE tenant_id = 1
      AND (confirmed_reading IS NOT NULL OR extracted_reading IS NOT NULL)
    ORDER BY tenant_id, scheme_id, reading_date, reading_at DESC
),

-- First and last reading date per scheme
scheme_date_ranges AS (
    SELECT
        tenant_id,
        scheme_id,
        MIN(reading_date) AS min_date,
        MAX(reading_date) AS max_date
    FROM analytics_schema.fact_meter_reading_table
    WHERE tenant_id = 1
    GROUP BY tenant_id, scheme_id
),

-- Full calendar for every scheme (min_date..max_date inclusive)
all_dates AS (
    SELECT
        sdr.tenant_id,
        sdr.scheme_id,
        gs.cal_date::DATE
    FROM scheme_date_ranges sdr
    CROSS JOIN LATERAL
        generate_series(sdr.min_date, sdr.max_date, '1 day'::INTERVAL) AS gs(cal_date)
),

-- Left-join calendar with actual readings (NULL columns on gap days)
dates_with_readings AS (
    SELECT
        ad.tenant_id,
        ad.scheme_id,
        ad.cal_date,
        br.effective_reading,
        br.user_id,
        br.submission_status,
        (br.reading_date IS NOT NULL) AS has_reading
    FROM all_dates ad
    LEFT JOIN best_reading_per_day br
           ON br.tenant_id   = ad.tenant_id
          AND br.scheme_id   = ad.scheme_id
          AND br.reading_date = ad.cal_date
),

-- Assign a monotonically increasing group number every time a new
-- non-null reading is encountered (standard PostgreSQL carry-forward trick).
grouped AS (
    SELECT
        tenant_id,
        scheme_id,
        cal_date,
        effective_reading,
        user_id,
        submission_status,
        has_reading,
        COUNT(effective_reading) OVER (
            PARTITION BY tenant_id, scheme_id
            ORDER BY cal_date
            ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
        ) AS reading_group
    FROM dates_with_readings
),

-- Carry forward: all rows in the same reading_group share the same
-- effective reading (the one that opened the group).
carried AS (
    SELECT
        tenant_id,
        scheme_id,
        cal_date,
        has_reading,
        -- Preserve user_id / submission_status only for actual submission days
        CASE WHEN has_reading THEN user_id       ELSE NULL END AS user_id,
        CASE WHEN has_reading THEN submission_status ELSE NULL END AS submission_status,
        FIRST_VALUE(effective_reading) OVER (
            PARTITION BY tenant_id, scheme_id, reading_group
            ORDER BY cal_date
        ) AS effective_reading
    FROM grouped
),

-- water_quantity = today's effective reading − previous day's effective reading
-- GREATEST(0, ...) keeps it non-negative.
-- First day per scheme: previous reading defaults to 0 (matches FactServiceImpl).
water_quantities AS (
    SELECT
        tenant_id,
        scheme_id,
        cal_date,
        user_id,
        submission_status,
        GREATEST(
            0,
            effective_reading - COALESCE(
                LAG(effective_reading) OVER (
                    PARTITION BY tenant_id, scheme_id
                    ORDER BY cal_date
                ),
                0
            )
        ) AS water_quantity
    FROM carried
)

INSERT INTO analytics_schema.fact_water_quantity_table (
    tenant_id, scheme_id, user_id, water_quantity,
    date, submission_status, created_at
)
SELECT
    wq.tenant_id,
    wq.scheme_id,
    wq.user_id,
    wq.water_quantity,
    wq.cal_date,
    wq.submission_status,
    NOW()
FROM water_quantities wq
WHERE NOT EXISTS (
    SELECT 1
    FROM analytics_schema.fact_water_quantity_table existing
    WHERE existing.tenant_id = wq.tenant_id
      AND existing.scheme_id = wq.scheme_id
      AND existing.date      = wq.cal_date
);

COMMIT;
