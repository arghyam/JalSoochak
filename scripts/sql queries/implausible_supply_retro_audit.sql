-- =====================================================================================
-- Implausible water supply — retrospective audit for one date   (READ ONLY, PROD DB)
-- =====================================================================================
-- Plain SQL — no psql meta-commands, no bind variables. Runs as-is in DBeaver.
--
--   HOW TO RUN IN DBEAVER
--     1. Open this file against the connection holding shared_db (common_schema + tenant_as).
--     2. Edit the literals in the `params` CTE — normally only `target_date`.
--     3. Ctrl+Enter runs the statement under the cursor. Nothing here writes.
--     If DBeaver prompts for a parameter value, turn off
--        Preferences > Editors > SQL Editor > SQL Processing > "Bind variables".
--     Schema is hard-coded as tenant_as (Assam) in three places — search/replace for another tenant.
--
-- WHAT THIS REPRODUCES
-- --------------------
-- ImplausibleSupplyPolicy.evaluate() as shipped, applied backwards over stored readings:
--
--   baseline    = latest confirmed_reading strictly before the reading's own day
--                 (confirmed_reading > 0, not deleted, not quarantined; observation_time DESC,
--                  created_at DESC, LIMIT 1)
--   litres      = round((confirmed_reading - baseline) * 1000)     -- m3 index -> litres, HALF_UP
--   connections = first non-zero of fhtc_count, planned_fhtc, house_hold_count
--   population  = connections * AVERAGE_MEMBERS_PER_HOUSEHOLD (tenant config, else the default 5)
--   ceiling     = population * limit_per_person_litres            -- 150 L/person/day
--   implausible iff litres > ceiling                               -- strictly greater
--
-- Skip conditions are mirrored too (non-BFM channel, meter replacement, no earlier reading,
-- delta <= 0, no population at all). They are NOT implausible — they are readings the check never
-- judges. The final WHERE keeps only 'IMPLAUSIBLE'; drop it to see every verdict.
--
-- CAVEATS — read before quoting these numbers
-- -------------------------------------------
--   * Master data is read AS IT STANDS TODAY. fhtc_count / planned_fhtc / house_hold_count and the
--     AVERAGE_MEMBERS_PER_HOUSEHOLD config may have changed since the reading date, so the ceiling
--     here is a reconstruction, not the ceiling the service would have applied on the day. The
--     ceiling as it stood is only ever in the telemetry log line; it is deliberately not persisted.
--   * The live check runs on POST/PUT /api/v1/telemetry/readings only (the Assam API); the
--     Glific/WhatsApp path is untouched. No column marks a row as API- vs WhatsApp-submitted, so
--     this assesses EVERY BFM reading for the date. `confirmed_reading_source = 3`
--     (EXTERNALLY_ASSERTED) is the closest proxy — it marks a value asserted by an API caller
--     rather than read off a photo. It is in the output; there is an optional filter at the bottom.
--   * Only stored readings can be audited. A submission rejected by an earlier validation never
--     reached flow_reading_table and is invisible here.
--   * Several readings for one scheme on one day are each measured against the same pre-day
--     baseline — that is what the service does, not a bug in this query.
--   * `observation_time` is the post-V4 column name. A schema that predates V4 still calls it
--     `reading_at`; swap the name in both places if so.
--
-- For a scheme-level roll-up, wrap the whole statement:
--   SELECT scheme_id, state_scheme_id, scheme_name, count(*), max(litres_over_ceiling)
--   FROM ( <this query> ) t GROUP BY 1,2,3 ORDER BY 5 DESC;
-- =====================================================================================

WITH params AS (
    SELECT DATE '2026-09-10'    AS target_date,                    -- <<< reading_date to audit
           1::int               AS tenant_id,                      -- <<< 1 = Assam
           200::numeric         AS limit_per_person_litres,        -- HIGH_QUANTITY_THRESHOLD_LIMIT_PER_PERSON
           5::numeric           AS default_members_per_household   -- DEFAULT_MEMBERS_PER_HOUSEHOLD
),

-- AVERAGE_MEMBERS_PER_HOUSEHOLD, stored as {"value":"4.5"}. A malformed value falls back to the
-- default rather than failing, exactly as SupplyPlausibilityGuard.readPositiveDecimalConfig does.
cfg AS (
    SELECT replace(btrim(c.config_value::jsonb ->> 'value'), ',', '') AS raw
    FROM common_schema.tenant_config_master_table c
    JOIN params p ON p.tenant_id = c.tenant_id
    WHERE c.config_key = 'AVERAGE_MEMBERS_PER_HOUSEHOLD'
      AND c.deleted_at IS NULL
      AND c.config_value ~ '^\s*\{'              -- only the JSON-object form is read by the service
    ORDER BY c.id
    LIMIT 1
),
members_per_household AS (
    SELECT COALESCE(
               (SELECT raw::numeric FROM cfg
                 WHERE raw ~ '^[0-9]+(\.[0-9]+)?$' AND raw::numeric > 0),
               (SELECT default_members_per_household FROM params)
           ) AS members
),

-- The readings the check would have assessed on that date.
readings AS (
    SELECT fr.id                        AS reading_id,
           fr.scheme_id,
           fr.created_by                AS operator_user_id,
           fr.reading_date,
           fr.observation_time,
           fr.confirmed_reading,
           fr.extracted_reading,
           fr.channel,
           fr.confirmed_reading_source,
           fr.quarantine_reason,
           fr.correlation_id
    FROM tenant_as.flow_reading_table fr
    JOIN params p ON fr.reading_date = p.target_date
    WHERE fr.deleted_at IS NULL
      AND fr.confirmed_reading > 0
      AND fr.meter_change_reason IS NULL       -- meter swap: the delta is meaningless, check skipped
      AND COALESCE(fr.channel, 1) = 1          -- BFM only; a null channel reads as BFM
),

assessed AS (
    SELECT r.*,
           b.confirmed_reading AS baseline_reading,
           s.state_scheme_id,
           s.centre_scheme_id,
           s.scheme_name,
           s.fhtc_count,
           s.planned_fhtc,
           s.house_hold_count,
           CASE WHEN s.fhtc_count   > 0 THEN s.fhtc_count
                WHEN s.planned_fhtc > 0 THEN s.planned_fhtc
                ELSE GREATEST(s.house_hold_count, 0)
           END AS connections,
           CASE WHEN s.fhtc_count       > 0 THEN 'fhtc_count'
                WHEN s.planned_fhtc     > 0 THEN 'planned_fhtc'
                WHEN s.house_hold_count > 0 THEN 'house_hold_count'
                ELSE 'none'
           END AS connection_source,
           m.members
    FROM readings r
    JOIN tenant_as.scheme_master_table s ON s.id = r.scheme_id
    CROSS JOIN members_per_household m
    LEFT JOIN LATERAL (
        SELECT b.confirmed_reading
        FROM tenant_as.flow_reading_table b
        WHERE b.scheme_id = r.scheme_id
          AND b.confirmed_reading > 0
          AND b.deleted_at IS NULL
          AND b.quarantine_reason = 0                                 -- quarantined rows are not baselines
          AND b.observation_time < date_trunc('day', r.observation_time)
        ORDER BY b.observation_time DESC, b.created_at DESC
        LIMIT 1
    ) b ON TRUE
),

verdicts AS (
    SELECT a.*,
           a.connections::numeric * a.members                                 AS population,
           a.connections::numeric * a.members * p.limit_per_person_litres     AS ceiling_litres,
           CASE WHEN a.baseline_reading IS NULL                     THEN NULL
                WHEN a.confirmed_reading <= a.baseline_reading      THEN 0
                ELSE round((a.confirmed_reading - a.baseline_reading) * 1000)
           END                                                                AS implied_litres
    FROM assessed a
    CROSS JOIN params p
)

SELECT CASE WHEN baseline_reading IS NULL                THEN 'SKIPPED_NO_BASELINE'
            WHEN confirmed_reading <= baseline_reading   THEN 'SKIPPED_NON_POSITIVE_DELTA'
            WHEN connections <= 0                        THEN 'SKIPPED_NO_POPULATION'
            WHEN implied_litres > ceiling_litres         THEN 'IMPLAUSIBLE'
            ELSE 'PLAUSIBLE'
       END                                               AS verdict,
       scheme_id,
       state_scheme_id,
       centre_scheme_id,
       scheme_name,
       reading_id,
       reading_date,
       observation_time,
       operator_user_id                                  AS user_id,
       baseline_reading,
       confirmed_reading,
       confirmed_reading - baseline_reading              AS delta_cubic_metres,
       implied_litres,
       ceiling_litres,
       implied_litres - ceiling_litres                   AS litres_over_ceiling,
       round(implied_litres / NULLIF(ceiling_litres, 0), 2) AS times_over_ceiling,
       round(implied_litres / NULLIF(population, 0), 1)  AS implied_lpcd,
       connections,
       connection_source,
       fhtc_count                                        AS achieved_fhtc_count,
       planned_fhtc,
       house_hold_count,
       members                                           AS members_per_household,
       population,
       confirmed_reading_source,   -- 0=as extracted, 1=rollover resolved, 2=manual, 3=API-asserted
       quarantine_reason,          -- 0=none, 1=IMPLAUSIBLE_WATER_SUPPLY (set only once live)
       correlation_id
FROM verdicts
WHERE CASE WHEN baseline_reading IS NULL              THEN 'SKIPPED_NO_BASELINE'
           WHEN confirmed_reading <= baseline_reading THEN 'SKIPPED_NON_POSITIVE_DELTA'
           WHEN connections <= 0                      THEN 'SKIPPED_NO_POPULATION'
           WHEN implied_litres > ceiling_litres       THEN 'IMPLAUSIBLE'
           ELSE 'PLAUSIBLE'
      END = 'IMPLAUSIBLE'
  -- Optional: only submissions whose value came from an API caller rather than a meter photo.
  -- AND confirmed_reading_source = 3
ORDER BY litres_over_ceiling DESC, scheme_id;
