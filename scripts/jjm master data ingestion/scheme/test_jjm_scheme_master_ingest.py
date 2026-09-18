"""Tests for the bulk write paths in jjm_scheme_master_ingest.

These cover the two places that were converted from a per-row loop to a batched
statement, because that conversion is the only part of the script that builds
SQL dynamically. Everything runs against a real PostgreSQL — the point is to
prove the generated SQL executes and produces byte-identical results to the
straightforward per-row implementation, which a mock cannot show.

  export JJM_TEST_DSN='postgresql://postgres:testpw@localhost:55432/shared_db'
  python3 -m pytest scripts/test_jjm_scheme_master_ingest.py -v
"""

from __future__ import annotations

import base64
import os
import sys

import psycopg2
import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from jjm_scheme_master_ingest import (  # noqa: E402
    CAT_BOTH_MATCH,
    CAT_NEW,
    CAT_REVIVED,
    EMAIL_DOMAIN,
    EMAIL_PREFIX,
    MAPPING_STATUS_ACTIVE,
    MAPPING_STATUS_INACTIVE,
    REMOVAL_DUPLICATE,
    REMOVAL_NOT_IN_SNAPSHOT,
    REMOVAL_SCHEME_RETIRED,
    ROLE_PUMP_OPERATOR,
    ROLE_SECTION_OFFICER,
    SCHEME_UPDATE_COLUMN_TYPES,
    STATE_SCHEME_CODE_COLUMN,
    AnalyticsDb,
    AnalyticsWriter,
    DimSchemeRow,
    LEGACY_KEEP_RECENT_READINGS,
    LEGACY_RETIRE,
    MappingRowState,
    PiiCrypto,
    SchemeActivity,
    SchemeAttributes,
    SchemeDecision,
    SchemeIndex,
    SchemeSnapshot,
    SheetRow,
    SourceShape,
    TenantDb,
    TenantWriter,
    UserPlan,
    classify_scheme,
    find_legacy_schemes,
    load_source,
    reconcile_pairs,
    resolve_public_ids,
)

DSN = os.environ.get("JJM_TEST_DSN", "postgresql://postgres:testpw@localhost:55432/shared_db")
ACTOR_ID = 999
TENANT_ID = 1

SCHEME_DDL = """
CREATE TABLE {schema}.scheme_master_table (
    id                  SERIAL          PRIMARY KEY,
    uuid                VARCHAR(36)     NOT NULL UNIQUE DEFAULT gen_random_uuid()::TEXT,
    state_scheme_id     VARCHAR(255)    NOT NULL,
    centre_scheme_id    VARCHAR(255)    NOT NULL,
    state_scheme_code   VARCHAR(255),
    scheme_name         VARCHAR(255)    NOT NULL,
    fhtc_count          INTEGER         NOT NULL DEFAULT 0,
    planned_fhtc        INTEGER         NOT NULL DEFAULT 0,
    house_hold_count    INTEGER         NOT NULL DEFAULT 0,
    latitude            DOUBLE PRECISION,
    longitude           DOUBLE PRECISION,
    channel             INTEGER,
    work_status         INTEGER         NOT NULL,
    operating_status    INTEGER         NOT NULL,
    is_active           BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    created_by          INTEGER,
    updated_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_by          INTEGER,
    deleted_at          TIMESTAMP,
    deleted_by          INTEGER
);
CREATE TABLE {schema}.user_table (
    id                        SERIAL       PRIMARY KEY,
    uuid                      VARCHAR(36)  NOT NULL UNIQUE,
    tenant_id                 INTEGER      NOT NULL,
    title                     TEXT,
    title_hash                VARCHAR(64),
    email                     VARCHAR(255) UNIQUE,
    user_type                 INTEGER,
    phone_number              TEXT,
    phone_number_hash         VARCHAR(64),
    password                  VARCHAR(255),
    status                    INTEGER,
    email_verification_status BOOLEAN,
    phone_verification_status BOOLEAN,
    created_at                TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by                INTEGER,
    updated_at                TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by                INTEGER,
    deleted_at                TIMESTAMP,
    deleted_by                INTEGER
);
CREATE TABLE {schema}.user_scheme_mapping_table (
    id          SERIAL      PRIMARY KEY,
    uuid        VARCHAR(36) NOT NULL UNIQUE DEFAULT gen_random_uuid()::TEXT,
    user_id     INTEGER     NOT NULL,
    scheme_id   INTEGER     NOT NULL,
    status      INTEGER     NOT NULL,
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    created_by  INTEGER,
    updated_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_by  INTEGER,
    deleted_at  TIMESTAMP,
    deleted_by  INTEGER
);
CREATE TABLE {schema}.scheme_lgd_mapping_table (
    id               SERIAL       PRIMARY KEY,
    scheme_id        INTEGER      NOT NULL,
    parent_lgd_id    INTEGER      NOT NULL,
    parent_lgd_level VARCHAR(255) NOT NULL,
    created_by       INTEGER      NOT NULL,
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by       INTEGER      NOT NULL,
    updated_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted_at       TIMESTAMP,
    deleted_by       INTEGER
);
CREATE TABLE {schema}.scheme_department_mapping_table (
    id                      SERIAL       PRIMARY KEY,
    scheme_id               INTEGER      NOT NULL,
    parent_department_id    INTEGER      NOT NULL,
    parent_department_level VARCHAR(255) NOT NULL,
    created_by              INTEGER      NOT NULL,
    created_at              TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by              INTEGER      NOT NULL,
    updated_at              TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted_at              TIMESTAMP,
    deleted_by              INTEGER
);
CREATE TABLE {schema}.flow_reading_table (
    id                SERIAL    PRIMARY KEY,
    scheme_id         INTEGER   NOT NULL,
    reading_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    reading_date      DATE      NOT NULL,
    extracted_reading NUMERIC   NOT NULL DEFAULT 0,
    confirmed_reading NUMERIC   NOT NULL DEFAULT 0,
    correlation_id    VARCHAR(255) NOT NULL DEFAULT '',
    quantity          NUMERIC   NOT NULL DEFAULT 0,
    created_by        INTEGER   NOT NULL DEFAULT 1,
    created_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_by        INTEGER   NOT NULL DEFAULT 1,
    updated_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at        TIMESTAMP,
    deleted_by        INTEGER
);
-- V38's partial UNIQUE index. Present here because half of what the public-code
-- handling does is stay on the right side of it.
CREATE UNIQUE INDEX uq_{schema}_scheme_state_scheme_code
    ON {schema}.scheme_master_table(state_scheme_code)
    WHERE state_scheme_code IS NOT NULL AND deleted_at IS NULL;
"""

# The columns update_schemes may touch, in the order the assertions compare them.
COMPARED = (
    "id, state_scheme_id, centre_scheme_id, scheme_name, fhtc_count, planned_fhtc, "
    "latitude, longitude, work_status, operating_status, updated_by"
)


def _pii() -> PiiCrypto:
    key = base64.b64encode(b"\x01" * 32).decode()
    return PiiCrypto(key, base64.b64encode(b"\x02" * 32).decode())


def _row(row_no: int) -> SheetRow:
    """update_schemes only reads .changes and .scheme_id; the row is ballast."""
    return SheetRow(
        row_no=row_no, scheme_name="", centre_id="", state_id="", centre_key="",
        state_key="", work_status=None, operating_status=None, planned_fhtc=None,
        achieved_fhtc=None, latitude=None, longitude=None, zone="", circle="",
        division="", sub_division="", district="", block="", panchayat="", villages=[],
    )


def _decision(scheme_id: int, changes: dict) -> SchemeDecision:
    d = SchemeDecision(_row(scheme_id), "BOTH_IDS_MATCH_SAME_SCHEME", scheme_id=scheme_id)
    # compute_scheme_changes stores (old, new); only new is written.
    d.changes = {col: (None, new) for col, new in changes.items()}
    return d


def update_schemes_per_row(writer: TenantWriter, decisions: list[SchemeDecision]) -> int:
    """The implementation this replaced, kept as the oracle to compare against."""
    updated = 0
    with writer.conn.cursor() as cur:
        for decision in decisions:
            if not decision.changes:
                continue
            columns = list(decision.changes)
            assignments = ", ".join(f"{c} = %s" for c in columns)
            values = [decision.changes[c][1] for c in columns]
            cur.execute(
                f"UPDATE {writer.schema}.scheme_master_table "
                f"SET {assignments}, updated_by = %s, updated_at = NOW() "
                f"WHERE id = %s AND deleted_at IS NULL",
                (*values, writer.actor_id, decision.scheme_id),
            )
            updated += cur.rowcount
    return updated


@pytest.fixture(scope="module")
def conn():
    try:
        connection = psycopg2.connect(DSN)
    except psycopg2.OperationalError as exc:
        pytest.skip(f"no PostgreSQL at {DSN}: {exc}")
    connection.autocommit = False
    yield connection
    connection.close()


@pytest.fixture
def writers(conn):
    """Two identical schemas: 'new' takes the batched path, 'old' the per-row one."""
    with conn.cursor() as cur:
        for schema in ("tenant_new", "tenant_old"):
            cur.execute(f"DROP SCHEMA IF EXISTS {schema} CASCADE")
            cur.execute(f"CREATE SCHEMA {schema}")
            cur.execute(SCHEME_DDL.format(schema=schema))

    pii = _pii()
    made = {
        name: TenantWriter(
            TenantDb(conn, schema, pii), TENANT_ID, ACTOR_ID,
            {ROLE_PUMP_OPERATOR: 5, ROLE_SECTION_OFFICER: 6},
        )
        for name, schema in (("new", "tenant_new"), ("old", "tenant_old"))
    }
    yield made
    conn.rollback()


def seed_schemes(conn, count: int) -> None:
    """Identical starting rows in both schemas."""
    with conn.cursor() as cur:
        for schema in ("tenant_new", "tenant_old"):
            cur.execute(f"""
                INSERT INTO {schema}.scheme_master_table
                    (id, state_scheme_id, centre_scheme_id, scheme_name,
                     fhtc_count, planned_fhtc, latitude, longitude,
                     work_status, operating_status)
                SELECT g, 'S' || g, 'C' || g, 'name ' || g, g, g * 2,
                       26.0 + g / 1000.0, 91.0 + g / 1000.0, 1, 1
                FROM generate_series(1, %s) AS g
            """, (count,))
            cur.execute(
                f"SELECT setval(pg_get_serial_sequence("
                f"'{schema}.scheme_master_table', 'id'), %s)", (count,)
            )


def dump(conn, schema: str) -> list[tuple]:
    with conn.cursor() as cur:
        cur.execute(
            f"SELECT {COMPARED} FROM {schema}.scheme_master_table ORDER BY id"
        )
        return cur.fetchall()


class TestUpdateSchemes:
    def test_matches_per_row_implementation_across_mixed_column_sets(self, conn, writers):
        """The real sheet yields ~60 distinct column sets; grouping must not mix them."""
        seed_schemes(conn, 40)
        variants = [
            {"fhtc_count": 111},
            {"fhtc_count": 222, "operating_status": 2},
            {"fhtc_count": 333, "planned_fhtc": 444},
            {"scheme_name": "renamed"},
            {"planned_fhtc": 555, "scheme_name": "both", "work_status": 4},
            {"latitude": 25.5, "longitude": 90.25},
            {"state_scheme_id": "S-NEW"},
            {"centre_scheme_id": "C-NEW"},
            {"fhtc_count": 1, "planned_fhtc": 2, "scheme_name": "x",
             "work_status": 2, "operating_status": 0, "latitude": 24.0,
             "longitude": 89.0, "state_scheme_id": "S9", "centre_scheme_id": "C9"},
        ]
        decisions = [
            _decision(i, dict(variants[i % len(variants)]))
            for i in range(1, 41)
        ]

        batched = writers["new"].update_schemes(decisions)
        per_row = update_schemes_per_row(writers["old"], decisions)

        assert batched == per_row == 40
        assert dump(conn, "tenant_new") == dump(conn, "tenant_old")

    def test_numeric_looking_ids_keep_their_leading_zeros(self, conn, writers):
        """scheme ids live in varchar columns but read as numbers; the round trip
        through the VALUES list must not turn '00987' into 987."""
        seed_schemes(conn, 2)
        decisions = [
            _decision(1, {"scheme_name": "12345", "state_scheme_id": "00987"}),
            _decision(2, {"scheme_name": "0", "state_scheme_id": "1e5"}),
        ]

        assert writers["new"].update_schemes(decisions) == 2
        update_schemes_per_row(writers["old"], decisions)

        assert dump(conn, "tenant_new") == dump(conn, "tenant_old")
        with conn.cursor() as cur:
            cur.execute(
                "SELECT scheme_name, state_scheme_id FROM tenant_new.scheme_master_table "
                "ORDER BY id"
            )
            assert cur.fetchall() == [("12345", "00987"), ("0", "1e5")]

    def test_declared_cast_types_match_the_real_columns(self, conn, writers):
        """The casts are only correct while they agree with the table. If the DDL
        changes, this fails here rather than mid-run against production."""
        with conn.cursor() as cur:
            cur.execute("""
                SELECT column_name, data_type
                FROM information_schema.columns
                WHERE table_schema = 'tenant_new' AND table_name = 'scheme_master_table'
            """)
            actual = dict(cur.fetchall())

        equivalent = {"varchar": "character varying", "integer": "integer",
                      "double precision": "double precision"}
        for column, declared in SCHEME_UPDATE_COLUMN_TYPES.items():
            assert actual[column] == equivalent[declared], (
                f"{column} is {actual[column]} in the table but declared {declared}"
            )

    def test_a_column_whose_page_is_entirely_null_still_assigns(self, conn, writers):
        """compute_scheme_changes never emits NULL today, so this pins the reason
        the casts are there: without them the inferred type is text and the
        UPDATE aborts the whole transaction."""
        seed_schemes(conn, 3)
        decisions = [_decision(i, {"latitude": None, "longitude": None}) for i in (1, 2, 3)]

        assert writers["new"].update_schemes(decisions) == 3

        with conn.cursor() as cur:
            cur.execute(
                "SELECT count(*) FROM tenant_new.scheme_master_table "
                "WHERE latitude IS NULL AND longitude IS NULL"
            )
            assert cur.fetchone()[0] == 3

    def test_spans_multiple_pages_within_one_column_set(self, conn, writers):
        """page_size is 500, so a single group of 1200 exercises the paging path
        that cur.rowcount silently gets wrong."""
        seed_schemes(conn, 1200)
        decisions = [_decision(i, {"fhtc_count": i * 3}) for i in range(1, 1201)]

        assert writers["new"].update_schemes(decisions) == 1200

        with conn.cursor() as cur:
            cur.execute(
                "SELECT count(*) FROM tenant_new.scheme_master_table WHERE fhtc_count = id * 3"
            )
            assert cur.fetchone()[0] == 1200

    def test_soft_deleted_rows_are_neither_updated_nor_counted(self, conn, writers):
        seed_schemes(conn, 3)
        with conn.cursor() as cur:
            cur.execute("UPDATE tenant_new.scheme_master_table SET deleted_at = NOW() WHERE id = 2")
            cur.execute("UPDATE tenant_old.scheme_master_table SET deleted_at = NOW() WHERE id = 2")
        decisions = [_decision(i, {"fhtc_count": 77}) for i in (1, 2, 3)]

        assert writers["new"].update_schemes(decisions) == 2
        assert update_schemes_per_row(writers["old"], decisions) == 2
        assert dump(conn, "tenant_new") == dump(conn, "tenant_old")

    def test_empty_diffs_touch_nothing(self, conn, writers):
        seed_schemes(conn, 3)
        before = dump(conn, "tenant_new")

        assert writers["new"].update_schemes([_decision(1, {}), _decision(2, {})]) == 0
        assert dump(conn, "tenant_new") == before

    def test_unregistered_column_is_rejected_rather_than_interpolated(self, writers):
        with pytest.raises(ValueError, match="No column type registered"):
            writers["new"].update_schemes([_decision(1, {"house_hold_count; DROP": 1})])


def _plan(phone: str, name: str, role: str = ROLE_PUMP_OPERATOR) -> UserPlan:
    return UserPlan(phone=phone, phone_hash=_pii().hmac(phone), role=role, name=name)


class TestInsertUsers:
    def test_assigns_the_returned_ids_to_the_right_plans(self, conn, writers):
        plans = [_plan(f"9198765432{i:02d}", f"Operator {i}") for i in range(12)]

        writers["new"].insert_users(plans)

        assert all(p.existing_id is not None for p in plans)
        assert len({p.existing_id for p in plans}) == len(plans)
        pii = _pii()
        with conn.cursor() as cur:
            cur.execute("SELECT id, title, email, uuid FROM tenant_new.user_table ORDER BY id")
            stored = {row[0]: row[1:] for row in cur.fetchall()}
        for plan in plans:
            title, email, uuid = stored[plan.existing_id]
            # The name must land on the row whose id was handed back to that plan.
            assert pii.decrypt(title) == plan.name
            assert email == f"{EMAIL_PREFIX[plan.role]}{plan.phone}{EMAIL_DOMAIN}"
            assert uuid == plan.existing_uuid

    def test_existing_email_gets_a_suffixed_address_instead_of_colliding(self, conn, writers):
        taken = _plan("919876543210", "Already There")
        colliding_email = f"{EMAIL_PREFIX[taken.role]}{taken.phone}{EMAIL_DOMAIN}"
        with conn.cursor() as cur:
            cur.execute(
                "INSERT INTO tenant_new.user_table (uuid, tenant_id, email, status) "
                "VALUES ('pre-existing', %s, %s, 1)", (TENANT_ID, colliding_email),
            )

        plans = [taken, _plan("919876543211", "Fresh")]
        writers["new"].insert_users(plans)

        with conn.cursor() as cur:
            cur.execute("SELECT email FROM tenant_new.user_table WHERE uuid = %s",
                        (taken.existing_uuid,))
            assigned = cur.fetchone()[0]
        assert assigned != colliding_email
        assert assigned.startswith(f"{EMAIL_PREFIX[taken.role]}{taken.phone}_")
        assert assigned.endswith(EMAIL_DOMAIN)

    def test_soft_deleted_row_still_counts_as_a_collision(self, conn, writers):
        """The unique constraint on email does not exclude soft-deleted rows."""
        plan = _plan("919876543212", "Recycled", ROLE_SECTION_OFFICER)
        email = f"{EMAIL_PREFIX[plan.role]}{plan.phone}{EMAIL_DOMAIN}"
        with conn.cursor() as cur:
            cur.execute(
                "INSERT INTO tenant_new.user_table (uuid, tenant_id, email, status, deleted_at) "
                "VALUES ('soft-deleted', %s, %s, 1, NOW())", (TENANT_ID, email),
            )

        writers["new"].insert_users([plan])  # must not raise a unique violation

        with conn.cursor() as cur:
            cur.execute("SELECT email FROM tenant_new.user_table WHERE uuid = %s",
                        (plan.existing_uuid,))
            assert cur.fetchone()[0] != email

    def test_empty_plan_list_issues_no_statement(self, writers):
        writers["new"].insert_users([])


class TestEmailsInUse:
    def test_returns_only_the_addresses_that_exist(self, conn, writers):
        with conn.cursor() as cur:
            cur.execute(
                "INSERT INTO tenant_new.user_table (uuid, tenant_id, email, status) "
                "VALUES ('u1', %s, 'po_919000000001@pump-operator.local', 1), "
                "       ('u2', %s, 'PO_919000000002@Pump-Operator.Local', 1)",
                (TENANT_ID, TENANT_ID),
            )
        db = writers["new"].db

        found = db.emails_in_use([
            "po_919000000001@pump-operator.local",
            "po_919000000002@pump-operator.local",  # stored with different casing
            "po_919000000003@pump-operator.local",  # absent
        ])

        assert found == {
            "po_919000000001@pump-operator.local",
            "po_919000000002@pump-operator.local",
        }

    def test_empty_input_returns_empty_set(self, writers):
        assert writers["new"].db.emails_in_use([]) == set()


# ─────────────────────────────────────────────────────────────────────────────
# Analytics warehouse
# ─────────────────────────────────────────────────────────────────────────────

# Mirrors what production actually has, as read back from pg_constraint:
#   dim_scheme_table_pkey                        PRIMARY KEY (id)
#   uq_dim_scheme_tenant_scheme_parent_lgd_dept  UNIQUE NULLS NOT DISTINCT
#       (tenant_id, scheme_id, parent_lgd_location_id, parent_department_location_id)
# The arbiter is a table CONSTRAINT on plain columns, not an expression index —
# that distinction is the whole point of these tests.
DIM_SCHEME_DDL = """
CREATE TABLE analytics_schema.dim_scheme_table (
    id                            SERIAL PRIMARY KEY,
    scheme_id                     INT NOT NULL,
    tenant_id                     INT NOT NULL,
    scheme_name                   VARCHAR(255),
    state_scheme_id               INT,
    centre_scheme_id              INT,
    longitude                     DOUBLE PRECISION,
    latitude                      DOUBLE PRECISION,
    parent_lgd_location_id        INT NOT NULL,
    level_1_lgd_id INT, level_2_lgd_id INT, level_3_lgd_id INT,
    level_4_lgd_id INT, level_5_lgd_id INT, level_6_lgd_id INT,
    parent_department_location_id INT,
    level_1_dept_id INT, level_2_dept_id INT, level_3_dept_id INT,
    level_4_dept_id INT, level_5_dept_id INT, level_6_dept_id INT,
    operating_status INT, work_status INT,
    fhtc_count INT, planned_fhtc INT, house_hold_count INT,
    created_at TIMESTAMP, updated_at TIMESTAMP
);
ALTER TABLE analytics_schema.dim_scheme_table
    ADD CONSTRAINT uq_dim_scheme_tenant_scheme_parent_lgd_dept
    UNIQUE NULLS NOT DISTINCT (
        tenant_id, scheme_id, parent_lgd_location_id, parent_department_location_id);
"""


@pytest.fixture
def analytics(conn):
    with conn.cursor() as cur:
        cur.execute("DROP SCHEMA IF EXISTS analytics_schema CASCADE")
        cur.execute("CREATE SCHEMA analytics_schema")
        cur.execute(DIM_SCHEME_DDL)
    yield AnalyticsWriter(conn, TENANT_ID)
    conn.rollback()


def dim_row(scheme_id: int, village: int, dept, name="scheme", fhtc=10) -> DimSchemeRow:
    return DimSchemeRow(
        scheme_id=scheme_id, scheme_name=name, state_scheme_id=1, centre_scheme_id=2,
        latitude=26.1, longitude=91.2,
        parent_lgd_location_id=village, lgd_levels=[1, 2, 3, 4, village],
        parent_department_location_id=dept,
        dept_levels=[1, 2, 3, 4, dept] if dept else [None] * 5,
        operating_status=1, work_status=2,
        fhtc_count=fhtc, planned_fhtc=20, house_hold_count=30,
    )


def dim_count(conn) -> int:
    with conn.cursor() as cur:
        cur.execute("SELECT count(*) FROM analytics_schema.dim_scheme_table")
        return cur.fetchone()[0]


class TestUpsertSchemes:
    def test_upserts_against_the_constraint_production_actually_has(self, conn, analytics):
        """Regression: the arbiter used to be written as COALESCE(col, -1), which
        only matches a bare expression index. Against prod's plain-column
        constraint that raises InvalidColumnReference and aborts the run."""
        analytics.upsert_schemes([dim_row(1, 100, 500), dim_row(2, 101, 501)])

        assert dim_count(conn) == 2

    def test_second_run_updates_in_place_rather_than_duplicating(self, conn, analytics):
        analytics.upsert_schemes([dim_row(1, 100, 500, name="before", fhtc=10)])
        analytics.upsert_schemes([dim_row(1, 100, 500, name="after", fhtc=99)])

        assert dim_count(conn) == 1
        with conn.cursor() as cur:
            cur.execute(
                "SELECT scheme_name, fhtc_count FROM analytics_schema.dim_scheme_table"
            )
            assert cur.fetchone() == ("after", 99)

    def test_null_department_collapses_to_one_row_per_scheme_and_village(self, conn, analytics):
        """NULLS NOT DISTINCT: a scheme with no resolved sub-division must not
        accumulate a new row on every run."""
        analytics.upsert_schemes([dim_row(1, 100, None, name="first")])
        analytics.upsert_schemes([dim_row(1, 100, None, name="second")])

        assert dim_count(conn) == 1
        with conn.cursor() as cur:
            cur.execute("SELECT scheme_name FROM analytics_schema.dim_scheme_table")
            assert cur.fetchone()[0] == "second"

    def test_one_row_per_scheme_village_department_combination(self, conn, analytics):
        analytics.upsert_schemes([
            dim_row(1, 100, 500), dim_row(1, 101, 500),  # same scheme, two villages
            dim_row(1, 100, 501),                        # same village, other dept
        ])

        assert dim_count(conn) == 3

    def test_spans_multiple_pages(self, conn, analytics):
        rows = [dim_row(i, 1000 + i, 500) for i in range(1, 1201)]

        assert analytics.upsert_schemes(rows) == 1200
        assert dim_count(conn) == 1200

    def test_empty_input_writes_nothing(self, conn, analytics):
        assert analytics.upsert_schemes([]) == 0
        assert dim_count(conn) == 0


# ─────────────────────────────────────────────────────────────────────────────
# Mapping reconciliation — the rules that make a re-run idempotent
# ─────────────────────────────────────────────────────────────────────────────

def row(row_id: int, left: int, right: int, live: bool = True,
        status: int = MAPPING_STATUS_ACTIVE) -> MappingRowState:
    return MappingRowState(row_id, left, right, status, live)


def ledger(*rows: MappingRowState) -> dict:
    built: dict = {}
    for r in rows:
        built.setdefault(r.pair, []).append(r)
    return built


def always_prune(_pair):
    return (REMOVAL_NOT_IN_SNAPSHOT, "test")


class TestReconcilePairs:
    def test_a_pair_with_no_row_is_inserted(self):
        result = reconcile_pairs("t", {(1, 10)}, {})

        assert result.to_insert == [(1, 10)]
        assert result.revivals == [] and result.removals == []

    def test_a_pair_already_live_is_left_alone(self):
        result = reconcile_pairs("t", {(1, 10)}, ledger(row(5, 1, 10)))

        assert result.to_insert == [] and result.revivals == []
        assert result.unchanged == 1

    def test_a_soft_deleted_row_is_revived_not_re_inserted(self):
        """The whole point: after a retirement, a re-run must restore the row it
        retired instead of stacking a second one on the same pair."""
        result = reconcile_pairs("t", {(1, 10)}, ledger(row(5, 1, 10, live=False)))

        assert result.revivals == [5]
        assert result.to_insert == []

    def test_a_live_but_inactive_row_is_revived_too(self):
        """Services want deleted_at IS NULL AND status = 1; failing either one
        makes the row unusable, so status 0 is just as much a revival."""
        result = reconcile_pairs(
            "t", {(1, 10)}, ledger(row(5, 1, 10, status=MAPPING_STATUS_INACTIVE))
        )

        assert result.revivals == [5]

    def test_the_earliest_row_is_the_one_revived(self):
        """Keeping the oldest row keeps the pair's original created_at."""
        result = reconcile_pairs(
            "t", {(1, 10)},
            ledger(row(5, 1, 10, live=False), row(9, 1, 10, live=False)),
        )

        assert result.revivals == [5]

    def test_a_live_row_wins_over_a_retired_one_for_the_same_pair(self):
        result = reconcile_pairs(
            "t", {(1, 10)}, ledger(row(5, 1, 10, live=False), row(9, 1, 10)),
        )

        assert result.revivals == []
        assert result.unchanged == 1
        assert result.removals == []

    def test_duplicate_live_rows_are_collapsed_to_one(self):
        result = reconcile_pairs("t", {(1, 10)}, ledger(row(5, 1, 10), row(9, 1, 10)))

        assert [(r.row_id, r.reason) for r in result.removals] == [(9, REMOVAL_DUPLICATE)]

    def test_duplicates_are_collapsed_even_when_nothing_is_pruned(self):
        """prune_reason=None is the additive mode; it must still not leave the
        duplicates an earlier run created."""
        result = reconcile_pairs(
            "t", {(1, 10)}, ledger(row(5, 1, 10), row(9, 1, 10)), prune_reason=None
        )

        assert [r.row_id for r in result.removals] == [9]

    def test_a_pair_the_snapshot_dropped_is_retired_when_prunable(self):
        result = reconcile_pairs("t", set(), ledger(row(5, 1, 10)), always_prune)

        assert [(r.row_id, r.reason) for r in result.removals] == [
            (5, REMOVAL_NOT_IN_SNAPSHOT)
        ]

    def test_a_pair_the_run_has_no_standing_over_is_untouched(self):
        """prune_reason returning None is how a source that omits a column keeps
        its hands off that column's mappings."""
        result = reconcile_pairs("t", set(), ledger(row(5, 1, 10)), lambda _p: None)

        assert result.removals == []

    def test_the_reason_reaches_the_removal_so_the_report_can_tell_them_apart(self):
        """A row going because its scheme was retired and a row going because the
        snapshot reassigned it are different findings; the reason carries which."""
        def cascade(pair):
            return (REMOVAL_SCHEME_RETIRED, "the scheme itself is being retired")

        result = reconcile_pairs("t", set(), ledger(row(5, 1, 10)), cascade)

        assert result.counts_by_reason() == {REMOVAL_SCHEME_RETIRED: 1}
        assert result.removals[0].detail == "the scheme itself is being retired"

    def test_an_already_retired_row_outside_the_snapshot_is_not_retired_again(self):
        result = reconcile_pairs(
            "t", set(), ledger(row(5, 1, 10, live=False)), always_prune
        )

        assert result.removals == []

    def test_running_twice_over_its_own_output_changes_nothing(self):
        """Idempotence, end to end: apply the first verdict to the ledger and the
        second pass must find nothing left to do."""
        before = ledger(row(5, 1, 10, live=False), row(6, 1, 11), row(7, 1, 11), row(8, 2, 12))
        desired = {(1, 10), (1, 11)}
        first = reconcile_pairs("t", desired, before, always_prune)

        retired = set(first.removal_ids)
        revived = set(first.revivals)
        after = ledger(*[
            MappingRowState(r.id, r.left_id, r.right_id, MAPPING_STATUS_ACTIVE,
                            live=(r.id not in retired))
            if r.id in revived else
            MappingRowState(r.id, r.left_id, r.right_id, r.status,
                            live=r.live and r.id not in retired)
            for rows in before.values() for r in rows
        ])
        second = reconcile_pairs("t", desired, after, always_prune)

        assert (second.to_insert, second.revivals, second.removals) == ([], [], [])


# ─────────────────────────────────────────────────────────────────────────────
# Scheme classification — reviving instead of duplicating
# ─────────────────────────────────────────────────────────────────────────────

def sheet_row(centre: str, state: str, name: str = "scheme", public_id: str = "") -> SheetRow:
    from jjm_scheme_master_ingest import scheme_id_key
    return SheetRow(
        row_no=3, scheme_name=name, centre_id=centre, state_id=state,
        centre_key=scheme_id_key(centre), state_key=scheme_id_key(state),
        work_status=1, operating_status=1, planned_fhtc=10, achieved_fhtc=5,
        latitude=None, longitude=None, zone="", circle="", division="",
        sub_division="", district="", block="", panchayat="", villages=[],
        public_id=public_id,
    )


def snapshot(scheme_id: int, centre: str, state: str, live: bool = True,
             code: str = None) -> SchemeSnapshot:
    return SchemeSnapshot(
        id=scheme_id, state_scheme_id=state, centre_scheme_id=centre,
        scheme_name=f"scheme {scheme_id}", planned_fhtc=1, fhtc_count=1,
        house_hold_count=1, latitude=None, longitude=None, work_status=1,
        operating_status=1, state_scheme_code=code, is_active=True, live=live,
    )


def index_of(*snapshots: SchemeSnapshot) -> SchemeIndex:
    by_centre, by_state, retired_c, retired_s, owners = {}, {}, {}, {}, {}
    from jjm_scheme_master_ingest import scheme_id_key
    snaps = {}
    for snap in snapshots:
        snaps[snap.id] = snap
        c = by_centre if snap.live else retired_c
        s = by_state if snap.live else retired_s
        c.setdefault(scheme_id_key(snap.centre_scheme_id), []).append(snap.id)
        s.setdefault(scheme_id_key(snap.state_scheme_id), []).append(snap.id)
        if snap.live and snap.state_scheme_code:
            owners.setdefault(snap.state_scheme_code.lower(), snap.id)
    return SchemeIndex(by_centre, by_state, snaps, retired_c, retired_s, owners)


class TestClassifySchemeRevival:
    def test_a_live_match_is_an_update(self):
        index = index_of(snapshot(7, "100", "200"))

        decision = classify_scheme(sheet_row("100", "200"), index, {}, {})

        assert (decision.category, decision.scheme_id) == (CAT_BOTH_MATCH, 7)

    def test_only_a_soft_deleted_match_is_a_revival_not_an_insert(self):
        """Regression: the index used to filter deleted_at IS NULL, so every
        scheme a --replace run retired was re-inserted on the next run."""
        index = index_of(snapshot(7, "100", "200", live=False))

        decision = classify_scheme(sheet_row("100", "200"), index, {}, {})

        assert (decision.category, decision.scheme_id) == (CAT_REVIVED, 7)

    def test_a_live_scheme_outranks_a_retired_one_carrying_the_same_ids(self):
        index = index_of(snapshot(7, "100", "200", live=False), snapshot(8, "100", "200"))

        decision = classify_scheme(sheet_row("100", "200"), index, {}, {})

        assert (decision.category, decision.scheme_id) == (CAT_BOTH_MATCH, 8)

    def test_a_revival_still_adopts_the_id_it_is_missing(self):
        index = index_of(snapshot(7, "100", "999", live=False))

        decision = classify_scheme(sheet_row("100", "200"), index, {}, {})

        assert decision.category == CAT_REVIVED
        assert decision.adopt_state_id and not decision.adopt_centre_id

    def test_unknown_on_both_sides_is_still_an_insert(self):
        index = index_of(snapshot(7, "100", "200", live=False))

        decision = classify_scheme(sheet_row("555", "666"), index, {}, {})

        assert decision.category == CAT_NEW


# ─────────────────────────────────────────────────────────────────────────────
# Legacy schemes — the reading-window guard
# ─────────────────────────────────────────────────────────────────────────────

class TestFindLegacySchemes:
    def test_a_silent_absent_scheme_is_retired(self):
        index = index_of(snapshot(1, "100", "200"), snapshot(2, "300", "400"))

        legacy = find_legacy_schemes(
            index, claimed_ids={1},
            activity={2: SchemeActivity(2, 0, 120, "2024-01-01")}, window_days=90,
        )

        assert [(l.snapshot.id, l.verdict) for l in legacy] == [(2, LEGACY_RETIRE)]

    def test_a_scheme_still_receiving_readings_is_spared(self):
        """The one thing that must never happen: retiring a scheme an operator is
        actively uploading to because the snapshot is out of date."""
        index = index_of(snapshot(1, "100", "200"), snapshot(2, "300", "400"))

        legacy = find_legacy_schemes(
            index, claimed_ids={1},
            activity={2: SchemeActivity(2, 3, 500, "2026-08-30")}, window_days=90,
        )

        assert [(l.snapshot.id, l.verdict) for l in legacy] == [
            (2, LEGACY_KEEP_RECENT_READINGS)
        ]
        assert "NOT retired" in legacy[0].reason

    def test_a_scheme_the_snapshot_names_is_never_legacy(self):
        index = index_of(snapshot(1, "100", "200"))

        assert find_legacy_schemes(index, {1}, {}, 90) == []

    def test_a_soft_deleted_scheme_is_not_legacy_it_is_already_gone(self):
        index = index_of(snapshot(1, "100", "200", live=False))

        assert find_legacy_schemes(index, set(), {}, 90) == []

    def test_a_scheme_with_no_readings_at_all_says_so(self):
        index = index_of(snapshot(2, "300", "400"))

        legacy = find_legacy_schemes(index, set(), {}, 90)

        assert legacy[0].verdict == LEGACY_RETIRE
        assert "no readings ever" in legacy[0].reason


# ─────────────────────────────────────────────────────────────────────────────
# Public scheme code (V38 state_scheme_code)
# ─────────────────────────────────────────────────────────────────────────────

def csv_shape(**kwargs) -> SourceShape:
    return SourceShape(columns=frozenset({"public_id"}), **kwargs)


class TestResolvePublicIds:
    def test_a_free_code_is_claimed(self):
        index = index_of(snapshot(7, "100", "200"))
        decision = classify_scheme(sheet_row("100", "200", public_id="SCH-1"), index, {}, {})

        resolve_public_ids([decision], index, csv_shape())

        assert decision.public_id_to_write == "SCH-1"
        assert decision.public_id_blocked_by == ""

    def test_a_code_another_live_scheme_owns_is_refused(self):
        """V38's partial UNIQUE index would abort the whole transaction; one
        mislabelled row must cost that one column, not the run."""
        index = index_of(snapshot(7, "100", "200"), snapshot(8, "300", "400", code="SCH-1"))
        decision = classify_scheme(sheet_row("100", "200", public_id="SCH-1"), index, {}, {})

        resolve_public_ids([decision], index, csv_shape())

        assert decision.public_id_to_write is None
        assert "scheme id 8 already holds" in decision.public_id_blocked_by

    def test_the_scheme_that_already_holds_the_code_keeps_it(self):
        index = index_of(snapshot(7, "100", "200", code="SCH-1"))
        decision = classify_scheme(sheet_row("100", "200", public_id="SCH-1"), index, {}, {})

        resolve_public_ids([decision], index, csv_shape())

        assert decision.public_id_to_write == "SCH-1"

    def test_a_code_the_source_repeats_is_claimed_by_neither_row(self):
        index = index_of(snapshot(7, "100", "200"), snapshot(8, "300", "400"))
        first = classify_scheme(sheet_row("100", "200", public_id="SCH-1"), index, {}, {})
        second = classify_scheme(sheet_row("300", "400", public_id="SCH-1"), index, {}, {})
        second.row.row_no = 4

        repeated = resolve_public_ids([first, second], index, csv_shape())

        assert list(repeated) == ["sch-1"]
        assert first.public_id_to_write is None and second.public_id_to_write is None
        assert "appears on rows" in first.public_id_blocked_by

    def test_nothing_is_claimed_when_the_database_predates_v38(self):
        index = index_of(snapshot(7, "100", "200"))
        decision = classify_scheme(sheet_row("100", "200", public_id="SCH-1"), index, {}, {})

        resolve_public_ids([decision], index, csv_shape(state_scheme_code_supported=False))

        assert decision.public_id_to_write is None


class TestStateSchemeCodeColumn:
    def test_the_declared_cast_matches_the_real_column(self, conn, writers):
        with conn.cursor() as cur:
            cur.execute("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema = 'tenant_new' AND table_name = 'scheme_master_table'
                  AND column_name = %s
            """, (STATE_SCHEME_CODE_COLUMN,))
            assert cur.fetchone()[0] == "character varying"
        assert SCHEME_UPDATE_COLUMN_TYPES[STATE_SCHEME_CODE_COLUMN] == "varchar"

    def test_the_column_is_detected_when_present(self, writers):
        assert writers["new"].db.state_scheme_code_column_exists() is True

    def test_a_database_without_the_column_is_detected_and_not_read(self, conn):
        with conn.cursor() as cur:
            cur.execute("DROP SCHEMA IF EXISTS tenant_old_db CASCADE")
            cur.execute("CREATE SCHEMA tenant_old_db")
            cur.execute(SCHEME_DDL.format(schema="tenant_old_db"))
            cur.execute(
                f"ALTER TABLE tenant_old_db.scheme_master_table "
                f"DROP COLUMN {STATE_SCHEME_CODE_COLUMN}"
            )
            cur.execute("""
                INSERT INTO tenant_old_db.scheme_master_table
                    (state_scheme_id, centre_scheme_id, scheme_name, work_status, operating_status)
                VALUES ('S1', 'C1', 'n', 1, 1)
            """)
        db = TenantDb(conn, "tenant_old_db", _pii())

        assert db.state_scheme_code_column_exists() is False
        # The whole tool still runs: the code simply reads as NULL.
        index = db.load_scheme_index()
        assert next(iter(index.snapshots.values())).state_scheme_code is None
        conn.rollback()

    def test_update_writes_the_code(self, conn, writers):
        seed_schemes(conn, 2)
        decisions = [_decision(1, {STATE_SCHEME_CODE_COLUMN: "SCH-000001"})]

        assert writers["new"].update_schemes(decisions) == 1

        with conn.cursor() as cur:
            cur.execute(
                f"SELECT {STATE_SCHEME_CODE_COLUMN} FROM tenant_new.scheme_master_table "
                f"WHERE id = 1"
            )
            assert cur.fetchone()[0] == "SCH-000001"

    def test_insert_writes_the_code_when_the_source_carries_one(self, conn, writers):
        decision = SchemeDecision(
            sheet_row("100", "200", name="new scheme", public_id="SCH-9"), CAT_NEW
        )
        decision.public_id_to_write = "SCH-9"

        ids = writers["new"].insert_schemes([decision], with_public_id=True)

        with conn.cursor() as cur:
            cur.execute(
                f"SELECT {STATE_SCHEME_CODE_COLUMN} FROM tenant_new.scheme_master_table "
                f"WHERE id = %s", (list(ids.values())[0],)
            )
            assert cur.fetchone()[0] == "SCH-9"

    def test_insert_leaves_the_code_null_when_it_was_refused(self, conn, writers):
        """A blocked row is still inserted — just without the column that would
        have collided."""
        decision = SchemeDecision(sheet_row("100", "200", public_id="SCH-9"), CAT_NEW)
        decision.public_id_blocked_by = "taken"

        ids = writers["new"].insert_schemes([decision], with_public_id=True)

        with conn.cursor() as cur:
            cur.execute(
                f"SELECT {STATE_SCHEME_CODE_COLUMN} FROM tenant_new.scheme_master_table "
                f"WHERE id = %s", (list(ids.values())[0],)
            )
            assert cur.fetchone()[0] is None

    def test_insert_omits_the_column_entirely_for_a_pre_v38_database(self, conn, writers):
        decision = SchemeDecision(sheet_row("100", "200"), CAT_NEW)

        ids = writers["new"].insert_schemes([decision], with_public_id=False)

        assert len(ids) == 1


# ─────────────────────────────────────────────────────────────────────────────
# Scheme retire / revive
# ─────────────────────────────────────────────────────────────────────────────

def scheme_state(conn, scheme_id: int) -> tuple:
    with conn.cursor() as cur:
        cur.execute(
            "SELECT deleted_at IS NULL, is_active, deleted_by "
            "FROM tenant_new.scheme_master_table WHERE id = %s", (scheme_id,)
        )
        return cur.fetchone()


class TestRetireAndReviveSchemes:
    def test_retire_sets_both_the_soft_delete_and_is_active(self, conn, writers):
        """is_active alone is not durable: SchemeActivitySyncScheduler recomputes
        it, and only skips rows whose deleted_at is set."""
        seed_schemes(conn, 3)

        assert writers["new"].retire_schemes([1, 2]) == 2

        assert scheme_state(conn, 1) == (False, False, ACTOR_ID)
        assert scheme_state(conn, 3) == (True, True, None)

    def test_retiring_an_already_retired_scheme_is_a_no_op(self, conn, writers):
        seed_schemes(conn, 2)
        writers["new"].retire_schemes([1])

        assert writers["new"].retire_schemes([1]) == 0

    def test_revive_clears_deleted_by_as_well_as_deleted_at(self, conn, writers):
        """A live row still carrying the id of whoever retired it misreports its
        own history."""
        seed_schemes(conn, 2)
        writers["new"].retire_schemes([1])

        assert writers["new"].revive_schemes([1]) == 1

        live, _is_active, deleted_by = scheme_state(conn, 1)
        assert live is True and deleted_by is None

    def test_reviving_a_live_scheme_is_a_no_op(self, conn, writers):
        seed_schemes(conn, 2)

        assert writers["new"].revive_schemes([1]) == 0

    def test_retire_then_revive_round_trips(self, conn, writers):
        seed_schemes(conn, 1)
        writers["new"].retire_schemes([1])
        writers["new"].revive_schemes([1])

        with conn.cursor() as cur:
            cur.execute("SELECT count(*) FROM tenant_new.scheme_master_table")
            assert cur.fetchone()[0] == 1


# ─────────────────────────────────────────────────────────────────────────────
# Mapping ledgers and row lifecycle
# ─────────────────────────────────────────────────────────────────────────────

def seed_mappings(conn) -> None:
    with conn.cursor() as cur:
        cur.execute("""
            INSERT INTO tenant_new.user_scheme_mapping_table
                (id, user_id, scheme_id, status, deleted_at)
            VALUES (1, 10, 100, 1, NULL),
                   (2, 10, 101, 1, NOW()),
                   (3, 10, 101, 1, NULL),
                   (4, 11, 100, 0, NULL)
        """)
        cur.execute("""
            INSERT INTO tenant_new.scheme_lgd_mapping_table
                (id, scheme_id, parent_lgd_id, parent_lgd_level, created_by, updated_by, deleted_at)
            VALUES (1, 100, 500, 'VILLAGE', 1, 1, NULL),
                   (2, 100, 501, 'VILLAGE', 1, 1, NOW())
        """)


class TestMappingLedger:
    def test_the_ledger_shows_retired_rows_too(self, conn, writers):
        """Filtering them out is what used to make a re-run insert a duplicate."""
        seed_mappings(conn)

        led = writers["new"].db.load_user_mapping_ledger()

        assert [(r.id, r.live) for r in led[(10, 101)]] == [(2, False), (3, True)]

    def test_the_ledger_is_ordered_by_id_within_a_pair(self, conn, writers):
        seed_mappings(conn)

        led = writers["new"].db.load_user_mapping_ledger()

        assert [r.id for r in led[(10, 101)]] == [2, 3]

    def test_status_is_read_for_user_mappings_and_null_for_location_ones(self, conn, writers):
        seed_mappings(conn)

        users = writers["new"].db.load_user_mapping_ledger()
        villages = writers["new"].db.load_lgd_mapping_ledger()

        assert users[(11, 100)][0].status == MAPPING_STATUS_INACTIVE
        assert users[(11, 100)][0].usable is False   # live but inactive
        assert villages[(100, 500)][0].status is None
        assert villages[(100, 500)][0].usable is True

    def test_live_active_mappings_exclude_inactive_rows(self, conn, writers):
        """The warehouse's view has to match what the services read."""
        seed_mappings(conn)

        assert writers["new"].db.load_user_scheme_mappings([10, 11]) == {10: {100, 101}}


class TestMappingRowLifecycle:
    def test_retiring_a_user_mapping_drops_status_as_well(self, conn, writers):
        seed_mappings(conn)

        assert writers["new"].retire_mapping_rows("user_scheme_mapping_table", [1]) == 1

        with conn.cursor() as cur:
            cur.execute(
                "SELECT deleted_at IS NULL, status, deleted_by "
                "FROM tenant_new.user_scheme_mapping_table WHERE id = 1"
            )
            assert cur.fetchone() == (False, MAPPING_STATUS_INACTIVE, ACTOR_ID)

    def test_retiring_a_location_mapping_touches_no_status_column(self, conn, writers):
        """scheme_lgd_mapping_table has no status column; naming one would fail."""
        seed_mappings(conn)

        assert writers["new"].retire_mapping_rows("scheme_lgd_mapping_table", [1]) == 1

        with conn.cursor() as cur:
            cur.execute(
                "SELECT deleted_at IS NULL FROM tenant_new.scheme_lgd_mapping_table WHERE id = 1"
            )
            assert cur.fetchone()[0] is False

    def test_reviving_restores_both_deleted_at_and_status(self, conn, writers):
        seed_mappings(conn)
        writers["new"].retire_mapping_rows("user_scheme_mapping_table", [1])

        assert writers["new"].revive_mapping_rows("user_scheme_mapping_table", [1]) == 1

        with conn.cursor() as cur:
            cur.execute(
                "SELECT deleted_at IS NULL, status, deleted_by "
                "FROM tenant_new.user_scheme_mapping_table WHERE id = 1"
            )
            assert cur.fetchone() == (True, MAPPING_STATUS_ACTIVE, None)

    def test_retire_is_idempotent(self, conn, writers):
        seed_mappings(conn)
        writers["new"].retire_mapping_rows("user_scheme_mapping_table", [1])

        assert writers["new"].retire_mapping_rows("user_scheme_mapping_table", [1]) == 0

    def test_an_unknown_table_is_rejected_rather_than_interpolated(self, writers):
        with pytest.raises(ValueError, match="not one of the mapping tables"):
            writers["new"].retire_mapping_rows("user_table; DROP SCHEMA public", [1])

    def test_empty_input_writes_nothing(self, writers):
        assert writers["new"].retire_mapping_rows("user_scheme_mapping_table", []) == 0
        assert writers["new"].revive_mapping_rows("user_scheme_mapping_table", []) == 0

    def test_a_revived_row_is_the_one_the_reconciler_would_reuse(self, conn, writers):
        """End to end: retire, then let the reconciler decide, and confirm the
        physical row count never grows."""
        seed_mappings(conn)
        writers["new"].retire_mapping_rows("user_scheme_mapping_table", [1])

        led = writers["new"].db.load_user_mapping_ledger()
        result = reconcile_pairs("user_scheme_mapping_table", {(10, 100)}, led)
        writers["new"].revive_mapping_rows("user_scheme_mapping_table", result.revivals)
        writers["new"].insert_user_scheme_mappings(result.to_insert)

        with conn.cursor() as cur:
            cur.execute(
                "SELECT count(*) FROM tenant_new.user_scheme_mapping_table "
                "WHERE user_id = 10 AND scheme_id = 100"
            )
            assert cur.fetchone()[0] == 1


# ─────────────────────────────────────────────────────────────────────────────
# Reading activity — the guard that spares a scheme from retirement
# ─────────────────────────────────────────────────────────────────────────────

class TestSchemeActivity:
    def test_readings_are_split_by_the_window(self, conn, writers):
        seed_schemes(conn, 3)
        with conn.cursor() as cur:
            cur.execute("""
                INSERT INTO tenant_new.flow_reading_table (scheme_id, reading_date)
                VALUES (1, CURRENT_DATE - 10),
                       (1, CURRENT_DATE - 200),
                       (2, CURRENT_DATE - 200)
            """)

        activity = writers["new"].db.load_scheme_activity([1, 2, 3], 90)

        assert (activity[1].recent_readings, activity[1].total_readings) == (1, 2)
        assert (activity[2].recent_readings, activity[2].total_readings) == (0, 1)
        assert (activity[3].recent_readings, activity[3].total_readings) == (0, 0)

    def test_a_scheme_with_no_readings_still_gets_an_entry(self, conn, writers):
        """It never reaches the GROUP BY, and a missing entry would read as
        'unknown' rather than 'silent'."""
        seed_schemes(conn, 1)

        assert writers["new"].db.load_scheme_activity([1], 90)[1].last_reading_date is None

    def test_soft_deleted_readings_do_not_keep_a_scheme_alive(self, conn, writers):
        seed_schemes(conn, 1)
        with conn.cursor() as cur:
            cur.execute("""
                INSERT INTO tenant_new.flow_reading_table (scheme_id, reading_date, deleted_at)
                VALUES (1, CURRENT_DATE - 1, NOW())
            """)

        assert writers["new"].db.load_scheme_activity([1], 90)[1].recent_readings == 0

    def test_empty_input_asks_the_database_nothing(self, writers):
        assert writers["new"].db.load_scheme_activity([], 90) == {}


# ─────────────────────────────────────────────────────────────────────────────
# dim_scheme attribute sync — one scheme, many rows, no drift
# ─────────────────────────────────────────────────────────────────────────────

def attrs(scheme_id: int, name="scheme", fhtc=10, op=1) -> SchemeAttributes:
    return SchemeAttributes(
        scheme_id=scheme_id, scheme_name=name, state_scheme_id=1, centre_scheme_id=2,
        latitude=26.1, longitude=91.2, operating_status=op, work_status=2,
        fhtc_count=fhtc, planned_fhtc=20, house_hold_count=30,
    )


def dim_names(conn, scheme_id: int) -> list[tuple]:
    with conn.cursor() as cur:
        cur.execute(
            "SELECT parent_lgd_location_id, scheme_name, fhtc_count "
            "FROM analytics_schema.dim_scheme_table WHERE scheme_id = %s "
            "ORDER BY parent_lgd_location_id", (scheme_id,)
        )
        return cur.fetchall()


class TestSyncSchemeAttributes:
    def test_every_row_of_a_scheme_gets_the_same_attributes(self, conn, analytics):
        """The drift this exists to stop: only the (scheme, village) pairs the
        snapshot reproduces get upserted, so a scheme's other rows keep whatever
        they were last given and the same scheme reports two different names."""
        analytics.upsert_schemes([
            dim_row(1, 100, 500, name="old", fhtc=1),
            dim_row(1, 101, 500, name="old", fhtc=1),
            dim_row(1, 102, 500, name="old", fhtc=1),
        ])
        # A later run only reproduces one of the three villages.
        analytics.upsert_schemes([dim_row(1, 100, 500, name="new", fhtc=99)])

        assert dim_names(conn, 1) == [
            (100, "new", 99), (101, "old", 1), (102, "old", 1)
        ]

        realigned = analytics.sync_scheme_attributes([attrs(1, name="new", fhtc=99)])

        assert len(realigned) == 2      # the two rows that had drifted
        assert dim_names(conn, 1) == [
            (100, "new", 99), (101, "new", 99), (102, "new", 99)
        ]

    def test_it_reports_only_the_rows_that_had_drifted(self, conn, analytics):
        analytics.upsert_schemes([dim_row(1, 100, 500, name="same", fhtc=7)])

        assert analytics.sync_scheme_attributes([attrs(1, name="same", fhtc=7)]) == []

    def test_it_is_idempotent(self, conn, analytics):
        analytics.upsert_schemes([dim_row(1, 100, 500, name="a"), dim_row(1, 101, 500, name="a")])
        analytics.sync_scheme_attributes([attrs(1, name="b")])

        assert analytics.sync_scheme_attributes([attrs(1, name="b")]) == []

    def test_location_columns_are_never_touched(self, conn, analytics):
        """Overwriting them from one row's location is what would destroy the
        fan-out this table exists to hold."""
        analytics.upsert_schemes([dim_row(1, 100, 500), dim_row(1, 101, 501)])

        analytics.sync_scheme_attributes([attrs(1, name="renamed")])

        with conn.cursor() as cur:
            cur.execute(
                "SELECT parent_lgd_location_id, parent_department_location_id, level_5_lgd_id "
                "FROM analytics_schema.dim_scheme_table ORDER BY parent_lgd_location_id"
            )
            assert cur.fetchall() == [(100, 500, 100), (101, 501, 101)]

    def test_it_does_not_reach_another_scheme(self, conn, analytics):
        analytics.upsert_schemes([dim_row(1, 100, 500, name="one"), dim_row(2, 200, 500, name="two")])

        analytics.sync_scheme_attributes([attrs(1, name="renamed")])

        assert dim_names(conn, 2) == [(200, "two", 10)]

    def test_it_does_not_reach_another_tenant(self, conn, analytics):
        analytics.upsert_schemes([dim_row(1, 100, 500, name="ours")])
        with conn.cursor() as cur:
            cur.execute("""
                INSERT INTO analytics_schema.dim_scheme_table
                    (scheme_id, tenant_id, scheme_name, state_scheme_id, centre_scheme_id,
                     parent_lgd_location_id, operating_status)
                VALUES (1, 99, 'theirs', 1, 2, 100, 1)
            """)

        analytics.sync_scheme_attributes([attrs(1, name="renamed")])

        with conn.cursor() as cur:
            cur.execute(
                "SELECT scheme_name FROM analytics_schema.dim_scheme_table WHERE tenant_id = 99"
            )
            assert cur.fetchone()[0] == "theirs"

    def test_a_scheme_with_no_dim_rows_is_harmless(self, conn, analytics):
        assert analytics.sync_scheme_attributes([attrs(42)]) == []

    def test_it_spans_multiple_pages(self, conn, analytics):
        analytics.upsert_schemes([dim_row(i, 1000 + i, 500, name="old") for i in range(1, 1201)])

        realigned = analytics.sync_scheme_attributes(
            [attrs(i, name="new") for i in range(1, 1201)]
        )

        assert len(realigned) == 1200

    def test_empty_input_writes_nothing(self, analytics):
        assert analytics.sync_scheme_attributes([]) == []


class TestDeactivateSchemes:
    def test_retired_schemes_drop_to_inactive_without_losing_their_rows(self, conn, analytics):
        """The dim row cannot be deleted — fact_water_quantity_table and three
        others carry a foreign key to (tenant_id, scheme_id), and a scheme
        retired for having no *recent* readings can still have years of facts."""
        analytics.upsert_schemes([dim_row(1, 100, 500), dim_row(1, 101, 500), dim_row(2, 200, 500)])

        assert analytics.deactivate_schemes([1]) == 2

        with conn.cursor() as cur:
            cur.execute(
                "SELECT scheme_id, operating_status FROM analytics_schema.dim_scheme_table "
                "ORDER BY scheme_id, parent_lgd_location_id"
            )
            assert cur.fetchall() == [(1, 0), (1, 0), (2, 1)]

    def test_it_is_idempotent(self, conn, analytics):
        analytics.upsert_schemes([dim_row(1, 100, 500)])
        analytics.deactivate_schemes([1])

        assert analytics.deactivate_schemes([1]) == 0

    def test_empty_input_writes_nothing(self, analytics):
        assert analytics.deactivate_schemes([]) == 0


class TestAnalyticsDbReads:
    def test_it_returns_every_row_a_scheme_holds(self, conn, analytics):
        analytics.upsert_schemes([
            dim_row(1, 100, 500, name="a"), dim_row(1, 101, 500, name="a"),
            dim_row(2, 200, None, name="b"),
        ])

        rows = AnalyticsDb(conn, TENANT_ID).load_dim_scheme_rows()

        assert sorted(r.parent_lgd_location_id for r in rows[1]) == [100, 101]
        assert rows[2][0].parent_department_location_id is None
        assert rows[1][0].attributes.scheme_name == "a"

    def test_it_is_scoped_to_the_tenant(self, conn, analytics):
        analytics.upsert_schemes([dim_row(1, 100, 500)])
        with conn.cursor() as cur:
            cur.execute("""
                INSERT INTO analytics_schema.dim_scheme_table
                    (scheme_id, tenant_id, scheme_name, state_scheme_id, centre_scheme_id,
                     parent_lgd_location_id, operating_status)
                VALUES (7, 99, 'theirs', 1, 2, 100, 1)
            """)

        assert 7 not in AnalyticsDb(conn, TENANT_ID).load_dim_scheme_rows()

    def test_differs_from_names_the_columns_that_disagree(self):
        assert attrs(1, name="a", fhtc=1).differs_from(attrs(1, name="b", fhtc=2)) == [
            "scheme_name", "fhtc_count"
        ]

    def test_identical_attributes_differ_in_nothing(self):
        assert attrs(1).differs_from(attrs(1)) == []


# ─────────────────────────────────────────────────────────────────────────────
# Source loading — one code path for the workbook and the CSV
# ─────────────────────────────────────────────────────────────────────────────

CSV_HEADER = (
    "public_id,scheme_name,imis_id,smt_id,district,division,work_status,"
    "operating_status,planned_fhtc_imis,provided_fhtc_imis,latitude,longitude"
)


def write_csv(tmp_path, body: str, title: str = "schemes-master-data") -> str:
    path = tmp_path / "snapshot.csv"
    path.write_text(f"{title}\n{CSV_HEADER}\n{body}", encoding="utf-8")
    return str(path)


class TestLoadSource:
    def test_the_csv_export_loads_with_only_its_own_columns(self, tmp_path):
        """It carries no village, sub-division or user column at all — requiring
        them would make the CSV unusable."""
        path = write_csv(
            tmp_path,
            "SCH-1,Namati PWSS,7922693,100001,Bajali,Bajali Division,completed,"
            "non-operative,49,70,NULL,NULL\n",
        )

        rows, issues, shape = load_source(path, None, 2)

        assert len(rows) == 1
        assert (rows[0].scheme_name, rows[0].centre_id, rows[0].state_id) == (
            "Namati PWSS", "7922693", "100001"
        )
        assert rows[0].public_id == "SCH-1"
        assert (rows[0].work_status, rows[0].operating_status) == (2, 0)

    def test_the_csv_shape_claims_no_authority_over_what_it_omits(self, tmp_path):
        path = write_csv(tmp_path, "SCH-1,n,1,2,d,dv,ongoing,operative,1,2,26.1,91.2\n")

        _rows, _issues, shape = load_source(path, None, 2)

        assert shape.has_public_id is True
        assert shape.has_villages is False
        assert shape.has_sub_divisions is False
        assert shape.has_users is False
        assert shape.roles == ()

    def test_the_literal_NULL_the_csv_writes_reads_as_blank(self, tmp_path):
        path = write_csv(tmp_path, "SCH-1,n,1,2,d,dv,ongoing,operative,1,2,NULL,NULL\n")

        rows, _issues, _shape = load_source(path, None, 2)

        assert rows[0].latitude is None and rows[0].longitude is None
        assert not any(i["issue_kind"] == "location" for i in _issues)

    def test_a_missing_required_column_is_fatal(self, tmp_path):
        path = tmp_path / "bad.csv"
        path.write_text("title\nscheme_name,imis_id\nx,1\n", encoding="utf-8")

        with pytest.raises(SystemExit, match="missing required column"):
            load_source(str(path), None, 2)

    def test_skip_users_switches_the_user_half_off(self, tmp_path):
        path = tmp_path / "full.csv"
        path.write_text(
            "title\n" + CSV_HEADER + ",jalmitras,jalmitra_phone\n"
            "SCH-1,n,1,2,d,dv,ongoing,operative,1,2,26.1,91.2,Ram,9876543210\n",
            encoding="utf-8",
        )

        rows, _issues, shape = load_source(str(path), None, 2, skip_users=True)

        assert shape.has_users is False and shape.roles == ()
        assert rows[0].people == []

    def test_without_skip_users_the_people_are_read(self, tmp_path):
        path = tmp_path / "full.csv"
        path.write_text(
            "title\n" + CSV_HEADER + ",jalmitras,jalmitra_phone\n"
            "SCH-1,n,1,2,d,dv,ongoing,operative,1,2,26.1,91.2,Ram,9876543210\n",
            encoding="utf-8",
        )

        rows, _issues, shape = load_source(str(path), None, 2)

        assert shape.roles == (ROLE_PUMP_OPERATOR,)
        assert [(p.name, p.phone) for p in rows[0].people] == [("Ram", "919876543210")]

    def test_a_row_blank_in_every_column_it_has_is_skipped(self, tmp_path):
        """Optional columns that are absent must not vote on blankness, or every
        row of the CSV would look empty."""
        path = write_csv(
            tmp_path,
            "SCH-1,n,1,2,d,dv,ongoing,operative,1,2,26.1,91.2\n,,,,,,,,,,,\n",
        )

        rows, _issues, _shape = load_source(path, None, 2)

        assert len(rows) == 1

    def test_row_numbers_match_the_spreadsheet_gutter(self, tmp_path):
        path = write_csv(
            tmp_path,
            "SCH-1,a,1,2,d,dv,ongoing,operative,1,2,26.1,91.2\n"
            "SCH-2,b,3,4,d,dv,ongoing,operative,1,2,26.1,91.2\n",
        )

        rows, _issues, _shape = load_source(path, None, 2)

        assert [r.row_no for r in rows] == [3, 4]


class TestSourceShape:
    def test_a_role_is_only_claimed_when_both_its_columns_are_present(self):
        half = SourceShape(columns=frozenset({"jalmitras"}))

        assert half.has_operators is False and half.roles == ()

    def test_both_roles_are_claimed_when_the_workbook_supplies_both(self):
        full = SourceShape(columns=frozenset(
            {"jalmitras", "jalmitra_phone", "so_name", "so_phone"}
        ))

        assert full.roles == (ROLE_PUMP_OPERATOR, ROLE_SECTION_OFFICER)

    def test_the_public_code_needs_the_database_column_too(self):
        shape = SourceShape(columns=frozenset({"public_id"}),
                            state_scheme_code_supported=False)

        assert shape.has_public_id is False


# ─────────────────────────────────────────────────────────────────────────────
# Whole-plan integration: what a source may prune, and what it may not
# ─────────────────────────────────────────────────────────────────────────────

FULL_HEADER = (
    "public_id,scheme_name,imis_id,smt_id,work_status,operating_status,"
    "planned_fhtc_imis,provided_fhtc_imis,latitude,longitude,"
    "district,blocks,panchayat_name,village_name,zone,circle,division,sub_divisions,"
    "jalmitras,jalmitra_phone"
)
FULL_ROW = (
    "SCH-1,Alpha,100,200,ongoing,operative,10,5,26.1,91.2,"
    "Kamrup,Block A,Panchayat A,Village A,Zone A,Circle A,Division A,Subdiv A,"
    "Ram,9876543210"
)


@pytest.fixture
def plan_schema(conn):
    """One tenant schema with a location hierarchy and a couple of schemes."""
    with conn.cursor() as cur:
        cur.execute("DROP SCHEMA IF EXISTS tenant_plan CASCADE")
        cur.execute("CREATE SCHEMA tenant_plan")
        cur.execute(SCHEME_DDL.format(schema="tenant_plan"))
        cur.execute("""
            CREATE TABLE tenant_plan.location_config_master_table
                (id SERIAL PRIMARY KEY, level INT, region_type INT);
            CREATE TABLE tenant_plan.lgd_location_master_table
                (id SERIAL PRIMARY KEY, title VARCHAR(255), parent_id INT,
                 lgd_location_config_id INT, deleted_at TIMESTAMP);
            CREATE TABLE tenant_plan.department_location_master_table
                (id SERIAL PRIMARY KEY, title VARCHAR(255), parent_id INT,
                 department_location_config_id INT, deleted_at TIMESTAMP);
            INSERT INTO tenant_plan.location_config_master_table (id, level, region_type)
            VALUES (1,1,1),(2,2,1),(3,3,1),(4,4,1),(5,5,1),
                   (11,1,2),(12,2,2),(13,3,2),(14,4,2),(15,5,2);
            INSERT INTO tenant_plan.lgd_location_master_table (id, title, parent_id, lgd_location_config_id)
            VALUES (1,'Assam',NULL,1),(2,'Kamrup',1,2),(3,'Block A',2,3),
                   (4,'Panchayat A',3,4),(5,'Village A',4,5),(6,'Village B',4,5);
            INSERT INTO tenant_plan.department_location_master_table
                (id, title, parent_id, department_location_config_id)
            VALUES (1,'Assam',NULL,11),(2,'Zone A',1,12),(3,'Circle A',2,13),
                   (4,'Division A',3,14),(5,'Subdiv A',4,15);
            -- The scheme the CSV names, plus one it does not.
            INSERT INTO tenant_plan.scheme_master_table
                (id, state_scheme_id, centre_scheme_id, scheme_name, work_status, operating_status)
            VALUES (1,'200','100','Alpha',1,1),(2,'400','300','Orphan',1,1);
            SELECT setval(pg_get_serial_sequence('tenant_plan.scheme_master_table','id'), 100);
            INSERT INTO tenant_plan.user_table
                (id, uuid, tenant_id, email, user_type, status)
            VALUES (1,'u1',1,'actor@x.y',5,1);
            SELECT setval(pg_get_serial_sequence('tenant_plan.user_table','id'), 100);
        """)
    yield TenantDb(conn, "tenant_plan", _pii())
    conn.rollback()


def make_plan(tenant, tmp_path, header=FULL_HEADER, body=FULL_ROW + "\n", **kwargs):
    from jjm_scheme_master_ingest import build_plan
    path = tmp_path / "src.csv"
    path.write_text(f"title\n{header}\n{body}", encoding="utf-8")
    rows, issues, shape = load_source(str(path), None, 2, skip_users=kwargs.pop("skip_users", False))
    return build_plan(rows, issues, tenant, shape, **kwargs)


class TestPlanPruningScope:
    def test_a_source_without_village_columns_never_prunes_a_village_mapping(
        self, conn, plan_schema, tmp_path
    ):
        """The whole reason SourceShape exists: running the CSV export must not
        wipe every village mapping in the tenant just because it says nothing
        about villages."""
        with conn.cursor() as cur:
            cur.execute("""
                INSERT INTO tenant_plan.scheme_lgd_mapping_table
                    (scheme_id, parent_lgd_id, parent_lgd_level, created_by, updated_by)
                VALUES (1, 5, 'VILLAGE', 1, 1)
            """)

        plan = make_plan(
            plan_schema, tmp_path,
            header=CSV_HEADER,
            body="SCH-1,Alpha,100,200,Kamrup,Division A,ongoing,operative,10,5,26.1,91.2\n",
            replace=True,
        )

        assert plan.lgd_reconciliation.removals == []

    def test_a_source_with_village_columns_prunes_the_village_it_dropped(
        self, conn, plan_schema, tmp_path
    ):
        with conn.cursor() as cur:
            cur.execute("""
                INSERT INTO tenant_plan.scheme_lgd_mapping_table
                    (scheme_id, parent_lgd_id, parent_lgd_level, created_by, updated_by)
                VALUES (1, 6, 'VILLAGE', 1, 1)
            """)   # Village B, which the CSV no longer names

        plan = make_plan(plan_schema, tmp_path, replace=True)

        assert [(r.right_id, r.reason) for r in plan.lgd_reconciliation.removals] == [
            (6, REMOVAL_NOT_IN_SNAPSHOT)
        ]
        assert plan.lgd_reconciliation.to_insert == [(1, 5)]   # Village A is added

    def test_without_replace_nothing_is_pruned_but_duplicates_still_collapse(
        self, conn, plan_schema, tmp_path
    ):
        with conn.cursor() as cur:
            cur.execute("""
                INSERT INTO tenant_plan.scheme_lgd_mapping_table
                    (scheme_id, parent_lgd_id, parent_lgd_level, created_by, updated_by)
                VALUES (1, 6, 'VILLAGE', 1, 1),
                       (1, 5, 'VILLAGE', 1, 1), (1, 5, 'VILLAGE', 1, 1)
            """)

        plan = make_plan(plan_schema, tmp_path, replace=False)

        reasons = plan.lgd_reconciliation.counts_by_reason()
        assert reasons == {REMOVAL_DUPLICATE: 1}   # Village B survives, the copy goes

    def test_a_scheme_whose_village_never_resolved_keeps_its_mappings(
        self, conn, plan_schema, tmp_path
    ):
        """A name we simply failed to look up must not read as 'the snapshot
        dropped it' and take the existing mapping with it."""
        with conn.cursor() as cur:
            cur.execute("""
                INSERT INTO tenant_plan.scheme_lgd_mapping_table
                    (scheme_id, parent_lgd_id, parent_lgd_level, created_by, updated_by)
                VALUES (1, 5, 'VILLAGE', 1, 1)
            """)
        body = FULL_ROW.replace("Village A", "Village Nobody Has Heard Of") + "\n"

        plan = make_plan(plan_schema, tmp_path, body=body, replace=True)

        assert plan.lgd_reconciliation.removals == []

    def test_a_retired_scheme_takes_its_mappings_with_it(
        self, conn, plan_schema, tmp_path
    ):
        """Scheme 2 is absent from the source and silent, so it retires — and a
        retired scheme must not stay mapped to anyone or anywhere."""
        with conn.cursor() as cur:
            cur.execute("""
                INSERT INTO tenant_plan.scheme_lgd_mapping_table
                    (scheme_id, parent_lgd_id, parent_lgd_level, created_by, updated_by)
                VALUES (2, 5, 'VILLAGE', 1, 1);
                INSERT INTO tenant_plan.user_scheme_mapping_table (user_id, scheme_id, status)
                VALUES (1, 2, 1);
            """)

        plan = make_plan(plan_schema, tmp_path, replace=True)

        assert plan.schemes_to_retire == {2}
        assert [r.reason for r in plan.lgd_reconciliation.removals] == [REMOVAL_SCHEME_RETIRED]
        assert [r.reason for r in plan.user_reconciliation.removals] == [REMOVAL_SCHEME_RETIRED]

    def test_a_scheme_with_recent_readings_keeps_everything(
        self, conn, plan_schema, tmp_path
    ):
        with conn.cursor() as cur:
            cur.execute("""
                INSERT INTO tenant_plan.flow_reading_table (scheme_id, reading_date)
                VALUES (2, CURRENT_DATE - 1);
                INSERT INTO tenant_plan.scheme_lgd_mapping_table
                    (scheme_id, parent_lgd_id, parent_lgd_level, created_by, updated_by)
                VALUES (2, 5, 'VILLAGE', 1, 1);
            """)

        plan = make_plan(plan_schema, tmp_path, replace=True)

        assert plan.schemes_to_retire == set()
        assert plan.spared_scheme_ids == {2}
        assert plan.lgd_reconciliation.removals == []

    def test_the_reading_window_is_configurable(self, conn, plan_schema, tmp_path):
        with conn.cursor() as cur:
            cur.execute("""
                INSERT INTO tenant_plan.flow_reading_table (scheme_id, reading_date)
                VALUES (2, CURRENT_DATE - 45)
            """)

        wide = make_plan(plan_schema, tmp_path, replace=True, window_days=90)
        assert wide.spared_scheme_ids == {2}

        narrow = make_plan(plan_schema, tmp_path, replace=True, window_days=30)
        assert narrow.schemes_to_retire == {2}

    def test_legacy_is_reported_even_without_replace(self, conn, plan_schema, tmp_path):
        """The distribution is the point of the analysis; --replace only decides
        whether it is acted on."""
        plan = make_plan(plan_schema, tmp_path, replace=False)

        assert [l.snapshot.id for l in plan.legacy] == [2]
        assert plan.retirement_candidates == {2}   # judged retirable …
        assert plan.schemes_to_retire == set()     # … but not acted on

    def test_skip_users_leaves_user_mappings_entirely_alone(
        self, conn, plan_schema, tmp_path
    ):
        with conn.cursor() as cur:
            cur.execute("""
                INSERT INTO tenant_plan.user_scheme_mapping_table (user_id, scheme_id, status)
                VALUES (1, 1, 1)
            """)

        plan = make_plan(plan_schema, tmp_path, skip_users=True, replace=True)

        assert plan.user_plans == {}
        assert plan.user_reconciliation.removals == []
        assert plan.user_reconciliation.to_insert == []

    def test_a_users_source_prunes_only_the_roles_it_carries(
        self, conn, plan_schema, tmp_path
    ):
        """Ingesting a jalmitra sheet must never retire a section officer's
        coverage — that belongs to the SO mapping tool."""
        with conn.cursor() as cur:
            cur.execute("""
                INSERT INTO tenant_plan.user_table (id, uuid, tenant_id, email, user_type, status)
                VALUES (2,'u2',1,'operator@x.y',5,1), (3,'u3',1,'officer@x.y',6,1);
                INSERT INTO tenant_plan.user_scheme_mapping_table (user_id, scheme_id, status)
                VALUES (2, 1, 1), (3, 1, 1);
            """)

        plan = make_plan(plan_schema, tmp_path, replace=True)

        # The sheet carries jalmitras only, so only the operator's row goes.
        assert [r.left_id for r in plan.user_reconciliation.removals] == [2]

    def test_the_holder_of_a_retired_mapping_is_named_in_the_report(
        self, conn, plan_schema, tmp_path
    ):
        pii = _pii()
        with conn.cursor() as cur:
            cur.execute("""
                INSERT INTO tenant_plan.user_table
                    (id, uuid, tenant_id, email, user_type, status, title)
                VALUES (2,'u2',1,'operator@x.y',5,1,%s);
                INSERT INTO tenant_plan.user_scheme_mapping_table (user_id, scheme_id, status)
                VALUES (2, 1, 1);
            """, (pii.encrypt("Departing Operator"),))

        plan = make_plan(plan_schema, tmp_path, replace=True)

        assert plan.holder_names[2] == "Departing Operator"


class TestPlanIdempotence:
    def test_applying_the_plan_twice_writes_nothing_the_second_time(
        self, conn, plan_schema, tmp_path
    ):
        """The end-to-end version of the property: apply, re-plan, and the second
        plan must have nothing left to do anywhere."""
        from jjm_scheme_master_ingest import execute_tenant

        writer = TenantWriter(plan_schema, TENANT_ID, 1, {ROLE_PUMP_OPERATOR: 5,
                                                          ROLE_SECTION_OFFICER: 6})
        first = make_plan(plan_schema, tmp_path, replace=True)
        execute_tenant(first, writer)

        second = make_plan(plan_schema, tmp_path, replace=True)
        stats = execute_tenant(second, writer)

        assert stats["schemes_inserted"] == 0
        assert stats["schemes_updated"] == 0
        assert stats["schemes_revived"] == 0
        assert stats["users_inserted"] == 0
        assert stats["user_scheme_mappings_inserted"] == 0
        assert stats["scheme_lgd_mappings_inserted"] == 0
        assert stats["scheme_department_mappings_inserted"] == 0

    def test_no_table_grows_a_physical_row_on_the_second_run(
        self, conn, plan_schema, tmp_path
    ):
        from jjm_scheme_master_ingest import execute_tenant

        writer = TenantWriter(plan_schema, TENANT_ID, 1, {ROLE_PUMP_OPERATOR: 5,
                                                          ROLE_SECTION_OFFICER: 6})
        execute_tenant(make_plan(plan_schema, tmp_path, replace=True), writer)
        before = table_counts(conn)

        execute_tenant(make_plan(plan_schema, tmp_path, replace=True), writer)

        assert table_counts(conn) == before

    def test_a_retired_scheme_is_revived_not_duplicated_when_it_returns(
        self, conn, plan_schema, tmp_path
    ):
        """Retire everything the source does not name, then hand it a source that
        names it again."""
        from jjm_scheme_master_ingest import execute_tenant

        writer = TenantWriter(plan_schema, TENANT_ID, 1, {ROLE_PUMP_OPERATOR: 5,
                                                          ROLE_SECTION_OFFICER: 6})
        execute_tenant(make_plan(plan_schema, tmp_path, replace=True), writer)
        assert scheme_is_live(conn, 2) is False

        returned = FULL_ROW + "\nSCH-2,Orphan,300,400,ongoing,operative,1,1,26.1,91.2," \
                              "Kamrup,Block A,Panchayat A,Village B,Zone A,Circle A," \
                              "Division A,Subdiv A,Sita,9876543211\n"
        stats = execute_tenant(
            make_plan(plan_schema, tmp_path, body=returned, replace=True), writer
        )

        assert (stats["schemes_revived"], stats["schemes_inserted"]) == (1, 0)
        assert scheme_is_live(conn, 2) is True
        with conn.cursor() as cur:
            cur.execute("SELECT count(*) FROM tenant_plan.scheme_master_table")
            assert cur.fetchone()[0] == 2


def table_counts(conn) -> dict:
    counts = {}
    with conn.cursor() as cur:
        for table in ("scheme_master_table", "user_table", "user_scheme_mapping_table",
                      "scheme_lgd_mapping_table", "scheme_department_mapping_table"):
            cur.execute(f"SELECT count(*) FROM tenant_plan.{table}")
            counts[table] = cur.fetchone()[0]
    return counts


def scheme_is_live(conn, scheme_id: int) -> bool:
    with conn.cursor() as cur:
        cur.execute(
            "SELECT deleted_at IS NULL FROM tenant_plan.scheme_master_table WHERE id = %s",
            (scheme_id,),
        )
        return cur.fetchone()[0]
