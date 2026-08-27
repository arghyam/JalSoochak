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
    EMAIL_DOMAIN,
    EMAIL_PREFIX,
    ROLE_PUMP_OPERATOR,
    ROLE_SECTION_OFFICER,
    SCHEME_UPDATE_COLUMN_TYPES,
    AnalyticsWriter,
    DimSchemeRow,
    PiiCrypto,
    SchemeDecision,
    SheetRow,
    TenantDb,
    TenantWriter,
    UserPlan,
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
