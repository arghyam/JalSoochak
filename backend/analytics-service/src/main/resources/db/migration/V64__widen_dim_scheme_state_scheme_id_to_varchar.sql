-- ============================================================
-- V64 - dim_scheme_table.state_scheme_id: INT -> VARCHAR(255)
-- ------------------------------------------------------------
-- state_scheme_id is the state system's own id for a scheme. Some states' ids are numeric, but others
-- are alphanumeric (TW20826, SPL17673), so INT cannot hold them: scheme-service sent 0 for every id it
-- could not parse. VARCHAR(255) matches tenant_<state>.scheme_master_table.state_scheme_id, the source of
-- truth. (backend/database/V42's header calls state_scheme_id "the numeric SMT id"; that is out of date,
-- but V42 has already run and editing it would fail Flyway's checksum validation, so it is corrected here.)
--
-- Existing values keep their decimal text (5001 -> '5001'; the 0 placeholders become '0'). The column
-- stays NOT NULL. centre_scheme_id, the IMIS id from the central system, is numeric everywhere and
-- stays INT.
--
-- Comparisons become textual: analytics now matches a rejected submission's state id to a scheme by
-- trimmed, case-insensitive text, so '00123' no longer matches a stored 123.
--
-- NOTE: INT -> VARCHAR is not binary-coercible, so this rewrites the table under an ACCESS EXCLUSIVE
-- lock. No index covers the column, so the rewrite is the whole cost: seconds at this row count.
--
-- lock_timeout is scoped to this migration transaction only (Flyway wraps each migration in one, so
-- SET LOCAL reverts automatically at commit). Without it, the ALTER queued behind a long-running query on
-- this table would wait indefinitely for its lock, and every later request, reads included, would queue
-- behind it, turning a slow query into an outage on the table. Failing fast surfaces that contention as a
-- failed deploy instead.
--
-- Deploy analytics-service before scheme-service starts sending the ids as text: this version reads the
-- old numeric messages too.
-- ============================================================
SET LOCAL lock_timeout = '5s';

ALTER TABLE analytics_schema.dim_scheme_table
    ALTER COLUMN state_scheme_id TYPE VARCHAR(255) USING state_scheme_id::text;

COMMENT ON COLUMN analytics_schema.dim_scheme_table.state_scheme_id IS
    'The state system''s own id for the scheme, as stored in scheme_master_table; may be alphanumeric (e.g. TW20826).';
