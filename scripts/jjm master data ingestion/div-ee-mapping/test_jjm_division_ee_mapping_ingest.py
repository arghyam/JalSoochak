"""Tests for jjm_division_ee_mapping_ingest.

Two layers:

  * pure logic — CSV parsing, the division title fallback, the subtree walk and
    the collapse of several CSV rows onto one engineer. These run anywhere.
  * write paths — division resolution, mapping planning and every INSERT/UPDATE
    against a real PostgreSQL. The resolution walks a hierarchy and the writes
    build SQL dynamically (a bulk INSERT, an UPDATE ... FROM (VALUES ...) and a
    soft delete), and a mock cannot show that the generated statement executes,
    types its columns correctly, or lands the right values on the right rows.

  export JJM_TEST_DSN='postgresql://postgres:testpw@localhost:55432/shared_db'
  python3 -m pytest "scripts/jjm master data ingestion/div-ee-mapping/test_jjm_division_ee_mapping_ingest.py" -v
"""

from __future__ import annotations

import base64
import os
import sys

import psycopg2
import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from jjm_division_ee_mapping_ingest import (  # noqa: E402
    BY_STATE_DEPT_ID,
    BY_TITLE,
    BY_TITLE_SUFFIXED,
    CAT_EXISTING,
    CAT_INVALID,
    CAT_NEW,
    DIV_AMBIGUOUS,
    DIV_MATCHED,
    DIV_NOT_FOUND,
    SKIP_NO_DIVISION,
    DeptNode,
    DivisionDb,
    MappingWriter,
    PiiCrypto,
    UserWriter,
    build_children_index,
    build_engineer_plans,
    build_plan,
    collapse_engineers,
    execute_tenant,
    load_csv,
    resolve_divisions,
    subtree_ids,
    title_core,
)

DSN = os.environ.get("JJM_TEST_DSN", "postgresql://postgres:testpw@localhost:55432/shared_db")
SCHEMA = "tenant_divtest"
LEGACY_SCHEMA = "tenant_divtest_prev37"
TENANT_ID = 1
ACTOR_ID = 1

# region_type 2 = department. Only the levels this tool touches are seeded.
CONFIG_LEVELS = [(2, 2, "Zone"), (2, 3, "Circle"), (2, 4, "Division"), (2, 5, "Sub-division")]

DDL = """
CREATE TABLE {schema}.user_table (
    id                        SERIAL       PRIMARY KEY,
    uuid                      VARCHAR(36)  NOT NULL UNIQUE,
    tenant_id                 INTEGER      NOT NULL,
    title                     TEXT,
    title_hash                TEXT,
    email                     VARCHAR(255) UNIQUE,
    user_type                 INTEGER,
    phone_number              TEXT,
    phone_number_hash         TEXT,
    {state_user_id_column}
    password                  TEXT,
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

CREATE TABLE {schema}.location_config_master_table (
    id          SERIAL       PRIMARY KEY,
    region_type INTEGER      NOT NULL,
    level       INTEGER      NOT NULL,
    level_name  JSONB        NOT NULL,
    deleted_at  TIMESTAMP
);

CREATE TABLE {schema}.department_location_master_table (
    id                            SERIAL       PRIMARY KEY,
    uuid                          VARCHAR(36)  NOT NULL UNIQUE DEFAULT gen_random_uuid()::TEXT,
    title                         VARCHAR(255) NOT NULL,
    department_location_config_id INTEGER,
    parent_id                     INTEGER,
    {state_dept_id_column}
    created_at                    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at                    TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by                    INTEGER,
    updated_by                    INTEGER,
    status                        INTEGER      NOT NULL DEFAULT 1,
    deleted_at                    TIMESTAMP,
    deleted_by                    INTEGER
);

CREATE TABLE {schema}.scheme_master_table (
    id               SERIAL       PRIMARY KEY,
    uuid             VARCHAR(36)  NOT NULL UNIQUE DEFAULT gen_random_uuid()::TEXT,
    state_scheme_id  VARCHAR(255) NOT NULL,
    centre_scheme_id VARCHAR(255) NOT NULL,
    scheme_name      VARCHAR(255) NOT NULL,
    is_active        BOOLEAN      NOT NULL DEFAULT TRUE,
    deleted_at       TIMESTAMP
);

CREATE TABLE {schema}.scheme_department_mapping_table (
    id                      SERIAL       PRIMARY KEY,
    scheme_id               INTEGER      NOT NULL,
    parent_department_id    INTEGER      NOT NULL,
    parent_department_level VARCHAR(255) NOT NULL,
    created_by              INTEGER,
    created_at              TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by              INTEGER,
    updated_at              TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted_at              TIMESTAMP,
    deleted_by              INTEGER
);

CREATE TABLE {schema}.user_scheme_mapping_table (
    id         SERIAL      PRIMARY KEY,
    uuid       VARCHAR(36) NOT NULL UNIQUE DEFAULT gen_random_uuid()::TEXT,
    user_id    INTEGER     NOT NULL,
    scheme_id  INTEGER     NOT NULL,
    status     INTEGER     NOT NULL,
    created_at TIMESTAMP   NOT NULL DEFAULT NOW(),
    created_by INTEGER,
    updated_at TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_by INTEGER,
    deleted_at TIMESTAMP,
    deleted_by INTEGER
);
"""

STATE_USER_ID_COLUMN = "state_user_id VARCHAR(255),"
STATE_DEPT_ID_COLUMN = "state_dept_id VARCHAR(255),"

COMMON_DDL = """
CREATE TABLE IF NOT EXISTS common_schema.user_type_master_table (
    id         SERIAL       PRIMARY KEY,
    uuid       VARCHAR(36)  NOT NULL UNIQUE DEFAULT gen_random_uuid()::TEXT,
    c_name     VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by INTEGER,
    updated_by INTEGER,
    deleted_at TIMESTAMP,
    deleted_by INTEGER
);
"""

CSV_HEADER = (
    "divsion-executive-engineer-mapping,,,,,\n"
    "division_public_id,division,public_id,executive_engineer_name,"
    "executive_engineer_phone,role\n"
)


def _pii() -> PiiCrypto:
    return PiiCrypto(
        base64.b64encode(b"\x01" * 32).decode(),
        base64.b64encode(b"\x02" * 32).decode(),
    )


# ─────────────────────────────────────────────────────────────────────────────
# CSV parsing
# ─────────────────────────────────────────────────────────────────────────────

class TestLoadCsv:
    def test_reads_the_state_export_layout(self, tmp_path):
        """The export puts a title line above the header, and row numbers in the
        report have to line up with what the operator sees in the file."""
        path = tmp_path / "map.csv"
        path.write_text(
            CSV_HEADER
            + "DIV-040,Umrangsu Division,USR-015883,Monjit Kemprai,9954672248,executive-engineer\n"
            + "DIV-034,Silchar II Division,USR-015926,Debdulal Das,9435322388,ee\n",
            encoding="utf-8",
        )

        rows, issues = load_csv(str(path), header_row=2, encoding="utf-8")

        assert [r.row_no for r in rows] == [3, 4]
        assert rows[0].division_public_id == "DIV-040"
        assert rows[0].division_title == "Umrangsu Division"
        assert rows[0].user_public_id == "USR-015883"
        assert rows[0].phone == "919954672248"
        assert rows[0].role == "EXECUTIVE_ENGINEER"
        assert rows[1].role == "EXECUTIVE_ENGINEER"     # 'ee' is an alias
        assert issues == []

    def test_blank_role_defaults_without_blocking_the_row(self, tmp_path):
        """The file is the executive-engineer mapping by definition — a missing
        role cell is filled in and reported, not treated as unusable."""
        path = tmp_path / "map.csv"
        path.write_text(
            CSV_HEADER + "DIV-1,Alpha Division,USR-1,Some Person,9000000001,\n",
            encoding="utf-8",
        )

        rows, issues = load_csv(str(path), header_row=2, encoding="utf-8")

        assert rows[0].role == "EXECUTIVE_ENGINEER"
        assert rows[0].blocking_issues == []
        assert [i["issue_kind"] for i in issues] == ["role"]

    def test_flags_unusable_rows_without_echoing_the_number(self, tmp_path):
        """An invalid phone is reported, but the number itself is PII and must
        not be written into the issue text."""
        path = tmp_path / "map.csv"
        path.write_text(
            CSV_HEADER
            + "DIV-1,Alpha Division,USR-1,Bad Phone,1234567890,executive-engineer\n"
            + "DIV-2,Beta Division,USR-2,,9000000002,executive-engineer\n"
            + ",,USR-3,No Division,9000000003,executive-engineer\n"
            + ",Gamma Division,USR-4,No Div Id,9000000004,executive-engineer\n",
            encoding="utf-8",
        )

        rows, issues = load_csv(str(path), header_row=2, encoding="utf-8")

        assert rows[0].blocking_issues == [
            "row:executive_engineer_phone is not a valid Indian mobile number"
        ]
        assert rows[1].blocking_issues == ["row:blank executive_engineer_name"]
        assert rows[2].blocking_issues == [
            "row:blank division — nothing to resolve the division by"
        ]
        # A missing division_public_id alone is reported, never blocking: the
        # title still resolves the division.
        assert rows[3].blocking_issues == []
        assert not any("1234567890" in i["issue"] for i in issues)

    def test_division_key_prefers_the_state_id(self, tmp_path):
        path = tmp_path / "map.csv"
        path.write_text(
            CSV_HEADER
            + "DIV-1,Alpha Division,USR-1,A,9000000001,executive-engineer\n"
            + ",Beta Division,USR-2,B,9000000002,executive-engineer\n",
            encoding="utf-8",
        )

        rows, _ = load_csv(str(path), header_row=2, encoding="utf-8")

        assert rows[0].division_key == "div-1"
        assert rows[1].division_key == "beta division"


class TestTitleCore:
    @pytest.mark.parametrize("raw,expected", [
        ("Umrangsu Division", "umrangsu"),
        ("Silchar II Division", "silchar ii"),
        ("  Baksa  ", "baksa"),
        ("Guwahati I Div", "guwahati i"),
        # 'Division' only comes off the end, never out of the middle.
        ("Division Road Circle", "division road circle"),
    ])
    def test_strips_only_a_trailing_suffix(self, raw, expected):
        assert title_core(raw) == expected


class TestSubtree:
    def test_returns_the_node_and_every_descendant(self):
        nodes = {
            1: DeptNode(1, "Division", 4, None, None),
            2: DeptNode(2, "Sub A", 5, 1, None),
            3: DeptNode(3, "Sub B", 5, 1, None),
            4: DeptNode(4, "Deeper", 6, 2, None),
            9: DeptNode(9, "Elsewhere", 4, None, None),
        }

        assert subtree_ids(1, build_children_index(nodes)) == [1, 2, 3, 4]
        assert subtree_ids(9, build_children_index(nodes)) == [9]

    def test_a_cycle_terminates(self):
        """Corrupt parent chains exist; the walk must not hang on one."""
        nodes = {
            1: DeptNode(1, "A", 4, 2, None),
            2: DeptNode(2, "B", 5, 1, None),
        }

        assert sorted(subtree_ids(1, build_children_index(nodes))) == [1, 2]


class TestCollapseEngineers:
    def test_one_person_over_two_divisions_becomes_one_user_row(self, tmp_path):
        """Two rows for the same number are one person covering two divisions —
        not the duplicate the user master ingest would refuse to guess at."""
        path = tmp_path / "map.csv"
        path.write_text(
            CSV_HEADER
            + "DIV-1,Alpha Division,USR-1,Same Person,9000000001,executive-engineer\n"
            + "DIV-2,Beta Division,USR-1,Same Person,9000000001,executive-engineer\n"
            + "DIV-3,Gamma Division,USR-2,Other Person,9000000002,executive-engineer\n",
            encoding="utf-8",
        )
        rows, _ = load_csv(str(path), header_row=2, encoding="utf-8")

        user_rows, grouped = collapse_engineers(rows)

        assert len(user_rows) == 2
        assert user_rows[0].phone == "919000000001"
        assert [r.row_no for r in grouped["919000000001"]] == [3, 4]

    def test_unusable_rows_stay_separate(self, tmp_path):
        """Two rows with no usable phone are not the same person."""
        path = tmp_path / "map.csv"
        path.write_text(
            CSV_HEADER
            + "DIV-1,Alpha Division,USR-1,A,111,executive-engineer\n"
            + "DIV-2,Beta Division,USR-2,B,222,executive-engineer\n",
            encoding="utf-8",
        )
        rows, _ = load_csv(str(path), header_row=2, encoding="utf-8")

        user_rows, _ = collapse_engineers(rows)

        assert len(user_rows) == 2


# ─────────────────────────────────────────────────────────────────────────────
# Database-backed fixtures
# ─────────────────────────────────────────────────────────────────────────────

@pytest.fixture(scope="module")
def conn():
    try:
        connection = psycopg2.connect(DSN)
    except psycopg2.OperationalError as exc:
        pytest.skip(f"no PostgreSQL at {DSN}: {exc}")
    connection.autocommit = False
    yield connection
    connection.close()


def _create_schema(cur, schema: str, with_columns: bool) -> None:
    cur.execute(f"DROP SCHEMA IF EXISTS {schema} CASCADE")
    cur.execute(f"CREATE SCHEMA {schema}")
    cur.execute(DDL.format(
        schema=schema,
        state_user_id_column=STATE_USER_ID_COLUMN if with_columns else "",
        state_dept_id_column=STATE_DEPT_ID_COLUMN if with_columns else "",
    ))
    if with_columns:
        cur.execute(
            f"CREATE UNIQUE INDEX uq_{schema}_dept_state_dept_id "
            f"ON {schema}.department_location_master_table(state_dept_id) "
            f"WHERE state_dept_id IS NOT NULL AND deleted_at IS NULL"
        )
        cur.execute(
            f"CREATE UNIQUE INDEX uq_{schema}_user_state_user_id "
            f"ON {schema}.user_table(state_user_id) "
            f"WHERE state_user_id IS NOT NULL AND deleted_at IS NULL"
        )
    for region_type, level, name in CONFIG_LEVELS:
        cur.execute(
            f"INSERT INTO {schema}.location_config_master_table (region_type, level, level_name) "
            f"VALUES (%s, %s, %s::jsonb)",
            (region_type, level, f'{{"en": "{name}"}}'),
        )


@pytest.fixture
def conn_ready(conn):
    """A throwaway schema inside a transaction that is never committed.

    Nothing here commits, so the rollback at teardown removes the schemas, the
    users and any role row a test created — PostgreSQL rolls DDL back like any
    other statement. That is deliberate: pointing JJM_TEST_DSN at a database
    that matters must not be able to destroy anything in it.
    """
    conn.rollback()  # clear a transaction a previous failure may have aborted
    with conn.cursor() as cur:
        cur.execute("CREATE SCHEMA IF NOT EXISTS common_schema")
        cur.execute(COMMON_DDL)
        # Additive: a real database already holds these, and their ids are read
        # back rather than assumed.
        cur.execute(
            "INSERT INTO common_schema.user_type_master_table (c_name) "
            "VALUES ('EXECUTIVE_ENGINEER'), ('SECTION_OFFICER'), ('STATE_ADMIN') "
            "ON CONFLICT (c_name) DO NOTHING"
        )
        _create_schema(cur, SCHEMA, with_columns=True)
        _create_schema(cur, LEGACY_SCHEMA, with_columns=False)
    yield conn
    conn.rollback()


@pytest.fixture
def db(conn_ready):
    return DivisionDb(
        conn_ready, SCHEMA, _pii(),
        with_state_user_id=True, with_state_dept_id=True,
    )


@pytest.fixture
def legacy_db(conn_ready):
    """The same tool against a tenant where neither V36 nor V37 has been applied."""
    return DivisionDb(
        conn_ready, LEGACY_SCHEMA, _pii(),
        with_state_user_id=False, with_state_dept_id=False,
    )


@pytest.fixture
def roles(db) -> dict[str, int]:
    return {name: row.id for name, row in db.load_user_types().items()}


@pytest.fixture
def writers(db):
    return UserWriter(db, TENANT_ID, ACTOR_ID), MappingWriter(db, ACTOR_ID)


def seed_dept(db: DivisionDb, title: str, level: int, parent_id=None,
              state_dept_id=None, deleted=False) -> int:
    with db.conn.cursor() as cur:
        cur.execute(
            f"SELECT id FROM {db.schema}.location_config_master_table "
            f"WHERE region_type = 2 AND level = %s", (level,)
        )
        config_id = cur.fetchone()[0]
        columns = "title, department_location_config_id, parent_id, status"
        values = "%s, %s, %s, 1"
        args = [title, config_id, parent_id]
        if db.with_state_dept_id:
            columns += ", state_dept_id"
            values += ", %s"
            args.append(state_dept_id)
        if deleted:
            columns += ", deleted_at"
            values += ", NOW()"
        cur.execute(
            f"INSERT INTO {db.schema}.department_location_master_table ({columns}) "
            f"VALUES ({values}) RETURNING id", args
        )
        return cur.fetchone()[0]


def seed_scheme(db: DivisionDb, name: str, dept_id: int, deleted=False,
                is_active=True) -> int:
    with db.conn.cursor() as cur:
        cur.execute(
            f"INSERT INTO {db.schema}.scheme_master_table "
            f"(uuid, state_scheme_id, centre_scheme_id, scheme_name, is_active, deleted_at) "
            f"VALUES (gen_random_uuid()::text, %s, %s, %s, %s, "
            f"{'NOW()' if deleted else 'NULL'}) RETURNING id",
            (name, name, name, is_active),
        )
        scheme_id = cur.fetchone()[0]
        cur.execute(
            f"INSERT INTO {db.schema}.scheme_department_mapping_table "
            f"(scheme_id, parent_department_id, parent_department_level, created_by, updated_by) "
            f"VALUES (%s, %s, 'Sub-division', %s, %s)",
            (scheme_id, dept_id, ACTOR_ID, ACTOR_ID),
        )
        return scheme_id


def seed_user(db: DivisionDb, name: str, phone: str, user_type: int,
              state_user_id=None) -> int:
    import uuid as uuid_mod
    columns = ("uuid, tenant_id, title, title_hash, email, user_type, "
               "phone_number, phone_number_hash, password, status")
    values = "%s,%s,%s,%s,%s,%s,%s,%s,'x',1"
    args = [
        str(uuid_mod.uuid4()), TENANT_ID,
        db.pii.encrypt(name), db.pii.title_hash(name),
        f"seed_{phone}@pump-operator.local", user_type,
        db.pii.encrypt(phone), db.pii.hmac(phone),
    ]
    if db.with_state_user_id:
        columns += ", state_user_id"
        values += ",%s"
        args.append(state_user_id)
    with db.conn.cursor() as cur:
        cur.execute(
            f"INSERT INTO {db.schema}.user_table ({columns}) VALUES ({values}) RETURNING id",
            args,
        )
        return cur.fetchone()[0]


def seed_mapping(db: DivisionDb, user_id: int, scheme_id: int) -> int:
    with db.conn.cursor() as cur:
        cur.execute(
            f"INSERT INTO {db.schema}.user_scheme_mapping_table "
            f"(user_id, scheme_id, status, created_by, updated_by) "
            f"VALUES (%s, %s, 1, %s, %s) RETURNING id",
            (user_id, scheme_id, ACTOR_ID, ACTOR_ID),
        )
        return cur.fetchone()[0]


def write_csv(tmp_path, *lines: str) -> str:
    path = tmp_path / "map.csv"
    path.write_text(CSV_HEADER + "".join(line + "\n" for line in lines), encoding="utf-8")
    return str(path)


def rows_of(tmp_path, *lines: str):
    return load_csv(write_csv(tmp_path, *lines), header_row=2, encoding="utf-8")


def live_mappings(db: DivisionDb, user_id: int) -> set[int]:
    with db.conn.cursor() as cur:
        cur.execute(
            f"SELECT scheme_id FROM {db.schema}.user_scheme_mapping_table "
            f"WHERE user_id = %s AND deleted_at IS NULL", (user_id,)
        )
        return {scheme_id for (scheme_id,) in cur}


# ─────────────────────────────────────────────────────────────────────────────
# Division resolution
# ─────────────────────────────────────────────────────────────────────────────

class TestResolveDivisions:
    def test_matches_on_state_dept_id_before_title(self, db, tmp_path):
        """Once backfilled, the state's id is decisive — a renamed node still
        resolves, and a title that happens to collide cannot steal the match."""
        wanted = seed_dept(db, "Renamed Since", 4, state_dept_id="DIV-1")
        seed_dept(db, "Alpha Division", 4)
        rows, _ = rows_of(tmp_path, "DIV-1,Alpha Division,USR-1,A,9000000001,executive-engineer")

        plans, _ = resolve_divisions(rows, db)

        plan = plans["div-1"]
        assert plan.category == DIV_MATCHED
        assert plan.matched_by == BY_STATE_DEPT_ID
        assert plan.node_id == wanted

    def test_falls_back_to_the_title_then_to_the_suffix(self, db, tmp_path):
        exact = seed_dept(db, "Alpha Division", 4)
        suffixed = seed_dept(db, "Beta", 4)
        rows, _ = rows_of(
            tmp_path,
            "DIV-1,Alpha Division,USR-1,A,9000000001,executive-engineer",
            "DIV-2,Beta Division,USR-2,B,9000000002,executive-engineer",
        )

        plans, _ = resolve_divisions(rows, db)

        assert (plans["div-1"].node_id, plans["div-1"].matched_by) == (exact, BY_TITLE)
        assert (plans["div-2"].node_id, plans["div-2"].matched_by) == (
            suffixed, BY_TITLE_SUFFIXED)

    def test_a_node_at_another_level_is_not_a_division(self, db, tmp_path):
        """Sub-divisions and divisions share names all the time; only the
        division level is searched."""
        seed_dept(db, "Alpha", 5)
        rows, _ = rows_of(tmp_path, "DIV-1,Alpha,USR-1,A,9000000001,executive-engineer")

        plans, _ = resolve_divisions(rows, db)

        assert plans["div-1"].category == DIV_NOT_FOUND

    def test_two_candidates_are_ambiguous_not_a_coin_toss(self, db, tmp_path):
        seed_dept(db, "Alpha Division", 4)
        seed_dept(db, "Alpha Division", 4)
        rows, _ = rows_of(tmp_path, "DIV-1,Alpha Division,USR-1,A,9000000001,executive-engineer")

        plans, _ = resolve_divisions(rows, db)

        assert plans["div-1"].category == DIV_AMBIGUOUS
        assert plans["div-1"].node_id is None

    def test_a_soft_deleted_node_is_not_a_candidate(self, db, tmp_path):
        seed_dept(db, "Alpha Division", 4, deleted=True)
        rows, _ = rows_of(tmp_path, "DIV-1,Alpha Division,USR-1,A,9000000001,executive-engineer")

        plans, _ = resolve_divisions(rows, db)

        assert plans["div-1"].category == DIV_NOT_FOUND

    def test_collects_schemes_across_the_whole_subtree(self, db, tmp_path):
        """The scheme ingest attaches schemes at sub-division level, so a
        division's schemes are only reachable by walking down to them."""
        division = seed_dept(db, "Alpha Division", 4)
        sub_a = seed_dept(db, "Sub A", 5, parent_id=division)
        sub_b = seed_dept(db, "Sub B", 5, parent_id=division)
        other = seed_dept(db, "Beta Division", 4)
        mine = {
            seed_scheme(db, "s1", sub_a),
            seed_scheme(db, "s2", sub_b),
            seed_scheme(db, "s3", division),      # mapped to the division itself
            # is_active tracks recent readings, not responsibility.
            seed_scheme(db, "s4", sub_a, is_active=False),
        }
        seed_scheme(db, "deleted", sub_a, deleted=True)
        seed_scheme(db, "theirs", other)
        rows, _ = rows_of(tmp_path, "DIV-1,Alpha Division,USR-1,A,9000000001,executive-engineer")

        plans, _ = resolve_divisions(rows, db)

        plan = plans["div-1"]
        assert sorted(plan.subtree_ids) == sorted([division, sub_a, sub_b])
        assert plan.scheme_ids == mine

    def test_backfills_the_state_id_and_withholds_a_taken_one(self, db, tmp_path):
        """V37's partial UNIQUE index would reject a second owner, so the
        collision is reported instead of aborting the run."""
        target = seed_dept(db, "Alpha Division", 4)
        owner = seed_dept(db, "Beta Division", 4, state_dept_id="DIV-2")
        rows, _ = rows_of(
            tmp_path,
            "DIV-1,Alpha Division,USR-1,A,9000000001,executive-engineer",
            "DIV-2,Alpha Division,USR-2,B,9000000002,executive-engineer",
        )

        plans, _ = resolve_divisions(rows, db)

        assert plans["div-1"].node_id == target
        assert plans["div-1"].state_dept_id_change == (None, "DIV-1")
        # DIV-2 resolves to Beta on its state id, which it already carries.
        assert plans["div-2"].node_id == owner
        assert plans["div-2"].state_dept_id_change is None

    def test_two_csv_ids_for_one_node_write_neither(self, db, tmp_path):
        """Only one of them could ever stick; picking one would be a coin toss."""
        node = seed_dept(db, "Alpha Division", 4)
        rows, _ = rows_of(
            tmp_path,
            "DIV-1,Alpha Division,USR-1,A,9000000001,executive-engineer",
            "DIV-9,Alpha Division,USR-2,B,9000000002,executive-engineer",
        )

        plans, _ = resolve_divisions(rows, db)

        assert {p.node_id for p in plans.values()} == {node}
        assert all(p.state_dept_id_change is None for p in plans.values())
        assert all("more than one division_public_id" in p.withheld["state_dept_id"]
                   for p in plans.values())

    def test_runs_against_a_tenant_without_v37(self, legacy_db, tmp_path):
        """No statement may mention state_dept_id when the option is off — the
        column does not exist on this schema, so a stray reference would throw."""
        division = seed_dept(legacy_db, "Alpha Division", 4)
        sub = seed_dept(legacy_db, "Sub A", 5, parent_id=division)
        scheme = seed_scheme(legacy_db, "s1", sub)
        rows, _ = rows_of(tmp_path, "DIV-1,Alpha Division,USR-1,A,9000000001,executive-engineer")

        plans, _ = resolve_divisions(rows, legacy_db)

        assert plans["div-1"].node_id == division
        assert plans["div-1"].matched_by == BY_TITLE
        assert plans["div-1"].scheme_ids == {scheme}
        assert plans["div-1"].state_dept_id_change is None


# ─────────────────────────────────────────────────────────────────────────────
# Engineer + mapping planning
# ─────────────────────────────────────────────────────────────────────────────

def _division_with_schemes(db: DivisionDb, title: str, count: int) -> tuple[int, list[int]]:
    division = seed_dept(db, title, 4)
    sub = seed_dept(db, f"{title} Sub", 5, parent_id=division)
    return division, [seed_scheme(db, f"{title}-{i}", sub) for i in range(count)]


class TestBuildEngineerPlans:
    def test_new_engineer_takes_every_scheme_in_the_division(self, db, roles, tmp_path):
        _, schemes = _division_with_schemes(db, "Alpha Division", 3)
        rows, _ = rows_of(tmp_path, "DIV-1,Alpha Division,USR-1,A,9000000001,executive-engineer")
        divisions, _ = resolve_divisions(rows, db)

        plan = build_engineer_plans(rows, divisions, db)[0]

        assert plan.decision.category == CAT_NEW
        assert plan.target_scheme_ids == set(schemes)
        assert plan.to_insert == set(schemes)
        assert plan.to_remove == set()

    def test_existing_engineer_only_gets_the_missing_mappings(self, db, roles, tmp_path):
        _, schemes = _division_with_schemes(db, "Alpha Division", 3)
        user = seed_user(db, "A", "919000000001", roles["EXECUTIVE_ENGINEER"])
        seed_mapping(db, user, schemes[0])
        rows, _ = rows_of(tmp_path, "DIV-1,Alpha Division,USR-1,A,9000000001,executive-engineer")
        divisions, _ = resolve_divisions(rows, db)

        plan = build_engineer_plans(rows, divisions, db)[0]

        assert plan.decision.category == CAT_EXISTING
        assert plan.decision.existing_id == user
        assert plan.existing_scheme_ids == {schemes[0]}
        assert plan.to_insert == {schemes[1], schemes[2]}
        assert plan.to_remove == set()

    def test_mappings_outside_the_division_are_reported_as_removable(self, db, roles, tmp_path):
        _, schemes = _division_with_schemes(db, "Alpha Division", 1)
        _, others = _division_with_schemes(db, "Beta Division", 1)
        user = seed_user(db, "A", "919000000001", roles["EXECUTIVE_ENGINEER"])
        seed_mapping(db, user, others[0])
        rows, _ = rows_of(tmp_path, "DIV-1,Alpha Division,USR-1,A,9000000001,executive-engineer")
        divisions, _ = resolve_divisions(rows, db)

        plan = build_engineer_plans(rows, divisions, db)[0]

        assert plan.to_insert == {schemes[0]}
        assert plan.to_remove == {others[0]}

    def test_one_engineer_over_two_divisions_gets_the_union(self, db, roles, tmp_path):
        _, alpha = _division_with_schemes(db, "Alpha Division", 2)
        _, beta = _division_with_schemes(db, "Beta Division", 2)
        rows, _ = rows_of(
            tmp_path,
            "DIV-1,Alpha Division,USR-1,Same Person,9000000001,executive-engineer",
            "DIV-2,Beta Division,USR-1,Same Person,9000000001,executive-engineer",
        )
        divisions, _ = resolve_divisions(rows, db)

        plans = build_engineer_plans(rows, divisions, db)

        assert len(plans) == 1
        assert plans[0].csv_rows == [3, 4]
        assert plans[0].target_scheme_ids == set(alpha) | set(beta)

    def test_two_engineers_on_one_division_both_get_its_schemes(self, db, roles, tmp_path):
        """A division genuinely having two EEs is a fact the CSV may state;
        user_scheme_mapping is many-to-many."""
        _, schemes = _division_with_schemes(db, "Baksa Division", 2)
        rows, _ = rows_of(
            tmp_path,
            "DIV-56,Baksa Division,USR-1,Pulak,9000000001,executive-engineer",
            "DIV-56,Baksa Division,USR-2,Pranjal,9000000002,executive-engineer",
        )
        divisions, _ = resolve_divisions(rows, db)

        plans = build_engineer_plans(rows, divisions, db)

        assert len(plans) == 2
        assert all(p.target_scheme_ids == set(schemes) for p in plans)
        assert all(p.will_write for p in plans)

    def test_an_engineer_with_no_resolvable_division_is_skipped(self, db, roles, tmp_path):
        rows, _ = rows_of(tmp_path, "DIV-1,Nowhere Division,USR-1,A,9000000001,executive-engineer")
        divisions, _ = resolve_divisions(rows, db)

        plan = build_engineer_plans(rows, divisions, db)[0]

        assert not plan.will_write
        assert plan.skip_reason == SKIP_NO_DIVISION

    def test_create_users_without_schemes_onboards_them_anyway(self, db, roles, tmp_path):
        rows, _ = rows_of(tmp_path, "DIV-1,Nowhere Division,USR-1,A,9000000001,executive-engineer")
        divisions, _ = resolve_divisions(rows, db)

        plan = build_engineer_plans(
            rows, divisions, db, create_users_without_schemes=True)[0]

        assert plan.will_write
        assert plan.target_scheme_ids == set()

    def test_an_unusable_row_never_reaches_the_database(self, db, roles, tmp_path):
        _division_with_schemes(db, "Alpha Division", 1)
        rows, _ = rows_of(tmp_path, "DIV-1,Alpha Division,USR-1,A,123,executive-engineer")
        divisions, _ = resolve_divisions(rows, db)

        plan = build_engineer_plans(rows, divisions, db)[0]

        assert plan.decision.category == CAT_INVALID
        assert not plan.will_write

    def test_an_administrative_role_is_never_demoted(self, db, roles, tmp_path):
        """A spreadsheet listing someone as an EE must not strip an admin's
        access — the rest of the row still applies, and they still get mapped."""
        _, schemes = _division_with_schemes(db, "Alpha Division", 1)
        user = seed_user(db, "Admin Person", "919000000001", roles["STATE_ADMIN"])
        rows, _ = rows_of(
            tmp_path, "DIV-1,Alpha Division,USR-1,Admin Person,9000000001,executive-engineer")
        divisions, _ = resolve_divisions(rows, db)

        plan = build_engineer_plans(rows, divisions, db)[0]

        assert plan.decision.existing_id == user
        assert "role" not in plan.decision.changes
        assert "STATE_ADMIN" in plan.decision.withheld["role"]
        assert plan.to_insert == set(schemes)


# ─────────────────────────────────────────────────────────────────────────────
# Writes
# ─────────────────────────────────────────────────────────────────────────────

class TestMappingWriter:
    def test_inserts_mappings_the_way_the_app_reads_them(self, db, writers, roles):
        _, schemes = _division_with_schemes(db, "Alpha Division", 2)
        user = seed_user(db, "A", "919000000001", roles["EXECUTIVE_ENGINEER"])
        _, mapping_writer = writers

        inserted = mapping_writer.insert_mappings([(user, s) for s in schemes])

        assert inserted == 2
        assert live_mappings(db, user) == set(schemes)
        with db.conn.cursor() as cur:
            cur.execute(
                f"SELECT status, created_by, updated_by FROM {SCHEMA}.user_scheme_mapping_table "
                f"WHERE user_id = %s LIMIT 1", (user,)
            )
            assert cur.fetchone() == (1, ACTOR_ID, ACTOR_ID)

    def test_soft_deletes_rather_than_removing(self, db, writers, roles):
        """Mirrors UserUploadRepository: the row stays, deleted_at/deleted_by
        record who dropped it and when."""
        _, schemes = _division_with_schemes(db, "Alpha Division", 2)
        user = seed_user(db, "A", "919000000001", roles["EXECUTIVE_ENGINEER"])
        for scheme in schemes:
            seed_mapping(db, user, scheme)
        _, mapping_writer = writers

        removed = mapping_writer.soft_delete_mappings([(user, schemes[0])])

        assert removed == 1
        assert live_mappings(db, user) == {schemes[1]}
        with db.conn.cursor() as cur:
            cur.execute(
                f"SELECT deleted_at IS NOT NULL, deleted_by, updated_by "
                f"FROM {SCHEMA}.user_scheme_mapping_table "
                f"WHERE user_id = %s AND scheme_id = %s", (user, schemes[0])
            )
            assert cur.fetchone() == (True, ACTOR_ID, ACTOR_ID)

    def test_soft_delete_ignores_a_row_already_gone(self, db, writers, roles):
        _, schemes = _division_with_schemes(db, "Alpha Division", 1)
        user = seed_user(db, "A", "919000000001", roles["EXECUTIVE_ENGINEER"])
        _, mapping_writer = writers

        assert mapping_writer.soft_delete_mappings([(user, schemes[0])]) == 0

    def test_backfills_state_dept_ids(self, db, writers, tmp_path):
        node = seed_dept(db, "Alpha Division", 4)
        rows, _ = rows_of(tmp_path, "DIV-1,Alpha Division,USR-1,A,9000000001,executive-engineer")
        divisions, _ = resolve_divisions(rows, db)
        _, mapping_writer = writers

        assert mapping_writer.backfill_state_dept_ids(divisions.values()) == 1

        with db.conn.cursor() as cur:
            cur.execute(
                f"SELECT state_dept_id, updated_by "
                f"FROM {SCHEMA}.department_location_master_table WHERE id = %s", (node,)
            )
            assert cur.fetchone() == ("DIV-1", ACTOR_ID)


class TestExecuteTenant:
    def test_onboards_the_engineer_and_maps_the_whole_division(
            self, db, writers, roles, tmp_path):
        node, schemes = _division_with_schemes(db, "Alpha Division", 3)
        rows, issues = rows_of(
            tmp_path, "DIV-1,Alpha Division,USR-1,Fresh EE,9000000001,executive-engineer")
        plan = build_plan(rows, issues, db)
        user_writer, mapping_writer = writers

        stats = execute_tenant(plan, user_writer, mapping_writer, create_roles=True)

        assert stats["users_inserted"] == 1
        assert stats["scheme_mappings_inserted"] == 3
        assert stats["state_dept_ids_backfilled"] == 1
        user_id = plan.engineers[0].decision.existing_id
        assert live_mappings(db, user_id) == set(schemes)
        with db.conn.cursor() as cur:
            cur.execute(
                f"SELECT title, email, user_type, state_user_id, password, status "
                f"FROM {SCHEMA}.user_table WHERE id = %s", (user_id,)
            )
            title, email, user_type, state_user_id, password, status = cur.fetchone()
            cur.execute(
                f"SELECT state_dept_id FROM {SCHEMA}.department_location_master_table "
                f"WHERE id = %s", (node,)
            )
            assert cur.fetchone()[0] == "DIV-1"
        assert db.pii.safe_decrypt(title) == "Fresh EE"
        assert email == "ee_919000000001@pump-operator.local"
        assert (user_type, state_user_id, password, status) == (
            roles["EXECUTIVE_ENGINEER"], "USR-1", "CSV_ONBOARDED", 1)

    def test_is_additive_by_default(self, db, writers, roles, tmp_path):
        _, alpha = _division_with_schemes(db, "Alpha Division", 1)
        _, beta = _division_with_schemes(db, "Beta Division", 1)
        user = seed_user(db, "A", "919000000001", roles["EXECUTIVE_ENGINEER"])
        seed_mapping(db, user, beta[0])
        rows, issues = rows_of(
            tmp_path, "DIV-1,Alpha Division,USR-1,A,9000000001,executive-engineer")
        plan = build_plan(rows, issues, db)
        user_writer, mapping_writer = writers

        stats = execute_tenant(plan, user_writer, mapping_writer, create_roles=True)

        assert stats["scheme_mappings_soft_deleted"] == 0
        assert live_mappings(db, user) == {alpha[0], beta[0]}

    def test_replace_makes_the_csv_authoritative(self, db, writers, roles, tmp_path):
        _, alpha = _division_with_schemes(db, "Alpha Division", 2)
        _, beta = _division_with_schemes(db, "Beta Division", 1)
        user = seed_user(db, "A", "919000000001", roles["EXECUTIVE_ENGINEER"])
        seed_mapping(db, user, alpha[0])
        seed_mapping(db, user, beta[0])
        rows, issues = rows_of(
            tmp_path, "DIV-1,Alpha Division,USR-1,A,9000000001,executive-engineer")
        plan = build_plan(rows, issues, db, replace=True)
        user_writer, mapping_writer = writers

        stats = execute_tenant(plan, user_writer, mapping_writer, create_roles=True)

        assert stats["scheme_mappings_inserted"] == 1        # alpha[1]
        assert stats["scheme_mappings_soft_deleted"] == 1    # beta[0]
        assert live_mappings(db, user) == set(alpha)

    def test_rerunning_changes_nothing(self, db, writers, roles, tmp_path):
        """The whole tool has to be safe to run twice — a second pass must not
        duplicate a mapping the first one created."""
        _, schemes = _division_with_schemes(db, "Alpha Division", 2)
        line = "DIV-1,Alpha Division,USR-1,Fresh EE,9000000001,executive-engineer"
        rows, issues = rows_of(tmp_path, line)
        user_writer, mapping_writer = writers
        execute_tenant(build_plan(rows, issues, db), user_writer, mapping_writer,
                       create_roles=True)

        second = build_plan(rows, issues, db)
        stats = execute_tenant(second, user_writer, mapping_writer, create_roles=True)

        assert stats["users_inserted"] == 0
        assert stats["scheme_mappings_inserted"] == 0
        assert stats["scheme_mappings_already_correct"] == 2
        assert live_mappings(db, second.engineers[0].decision.existing_id) == set(schemes)

    def test_two_engineers_on_one_division_are_both_mapped(
            self, db, writers, roles, tmp_path):
        _, schemes = _division_with_schemes(db, "Baksa Division", 2)
        rows, issues = rows_of(
            tmp_path,
            "DIV-56,Baksa Division,USR-1,Pulak,9000000001,executive-engineer",
            "DIV-56,Baksa Division,USR-2,Pranjal,9000000002,executive-engineer",
        )
        plan = build_plan(rows, issues, db)
        user_writer, mapping_writer = writers

        stats = execute_tenant(plan, user_writer, mapping_writer, create_roles=True)

        assert stats["users_inserted"] == 2
        assert stats["scheme_mappings_inserted"] == 4
        for engineer in plan.engineers:
            assert live_mappings(db, engineer.decision.existing_id) == set(schemes)

    def test_a_skipped_engineer_is_neither_created_nor_mapped(
            self, db, writers, roles, tmp_path):
        _division_with_schemes(db, "Alpha Division", 1)
        rows, issues = rows_of(
            tmp_path,
            "DIV-1,Alpha Division,USR-1,Good EE,9000000001,executive-engineer",
            "DIV-9,Nowhere Division,USR-2,Orphan EE,9000000002,executive-engineer",
        )
        plan = build_plan(rows, issues, db)
        user_writer, mapping_writer = writers

        stats = execute_tenant(plan, user_writer, mapping_writer, create_roles=True)

        assert stats["users_inserted"] == 1
        with db.conn.cursor() as cur:
            cur.execute(f"SELECT COUNT(*) FROM {SCHEMA}.user_table")
            assert cur.fetchone()[0] == 1

    def test_runs_against_a_tenant_without_v36_or_v37(self, conn_ready, legacy_db, tmp_path):
        """Neither migration applied: names and mappings still reconcile, and no
        statement may name a column this schema does not have."""
        division = seed_dept(legacy_db, "Alpha Division", 4)
        sub = seed_dept(legacy_db, "Alpha Sub", 5, parent_id=division)
        schemes = {seed_scheme(legacy_db, "s1", sub), seed_scheme(legacy_db, "s2", sub)}
        rows, issues = rows_of(
            tmp_path, "DIV-1,Alpha Division,USR-1,Fresh EE,9000000001,executive-engineer")
        plan = build_plan(rows, issues, legacy_db)

        stats = execute_tenant(
            plan,
            UserWriter(legacy_db, TENANT_ID, ACTOR_ID),
            MappingWriter(legacy_db, ACTOR_ID),
            create_roles=True,
        )

        assert stats["users_inserted"] == 1
        assert stats["scheme_mappings_inserted"] == 2
        assert stats["state_dept_ids_backfilled"] == 0
        assert live_mappings(legacy_db, plan.engineers[0].decision.existing_id) == schemes
