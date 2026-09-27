"""Tests for jjm_dept_master_ingest.

Two layers:

  * pure logic — reading both master CSVs (title line above the header, blank
    cells, duplicate rows), the identity a row is keyed by, the ancestry
    rendering and the hierarchy check. These run anywhere.
  * resolution and writes — division and sub-division resolution, the
    withholding rules and the UPDATE itself, against a real PostgreSQL.
    Resolution walks a hierarchy and the write builds SQL dynamically; a mock
    cannot show that the statement executes, types its columns correctly, or
    lands the right value on the right row. It also cannot show that the
    withholding rules are what keep V37's partial UNIQUE index from rejecting
    the batch, which is the point of two of these tests.

The DDL and schema helpers are imported from the division/EE suite for the same
reason the production module imports its engine: one schema definition, not two
that drift.

  export JJM_TEST_DSN='postgresql://postgres:postgres@localhost:5432/shared_db'
  python3 -m pytest "scripts/jjm master data ingestion/dept-master/test_jjm_dept_master_ingest.py" -v
"""

from __future__ import annotations

import os
import sys

import pandas as pd
import psycopg2
import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
_PARENT = os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir)
sys.path.insert(0, os.path.join(_PARENT, "div-ee-mapping"))
sys.path.insert(0, os.path.join(_PARENT, "subdiv-sdo-mapping"))

from jjm_division_ee_mapping_ingest import (  # noqa: E402
    BY_STATE_DEPT_ID,
    BY_TITLE,
    BY_TITLE_SUFFIXED,
    DIV_AMBIGUOUS,
    DIV_MATCHED,
    DIV_NOT_FOUND,
    DeptNode,
)
from test_jjm_division_ee_mapping_ingest import (  # noqa: E402
    ACTOR_ID,
    _create_schema,
)

from jjm_dept_master_ingest import (  # noqa: E402
    BY_TITLE_IN_PARENT,
    BY_TITLE_SUFFIXED_IN_PARENT,
    CHANGE_NEW,
    CHANGE_NONE,
    CHANGE_OVERWRITE,
    DIVISION_LEVEL,
    PARENT_CONFIRMED,
    PARENT_MISMATCH,
    PARENT_UNRESOLVED,
    SUB_DIVISION_LEVEL,
    WITHHELD_CONTESTED,
    WITHHELD_ID_CONFLICT,
    WITHHELD_PARENT_MISMATCH,
    DeptMasterDb,
    DeptMasterWriter,
    build_extra_in_csv_frame,
    build_extra_in_db_frame,
    build_plan,
    dedupe_rows,
    execute_tenant,
    hierarchy_status,
    load_division_csv,
    load_subdivision_csv,
    main,
    node_path,
    write_analysis_workbook,
)

DSN = os.environ.get("JJM_TEST_DSN", "postgresql://postgres:testpw@localhost:55432/shared_db")
SCHEMA = "tenant_deptmastertest"
LEGACY_SCHEMA = "tenant_deptmastertest_prev37"

DIVISION_CSV_HEADER = "divisions-master,\npublic_id,division_name\n"
SUBDIVISION_CSV_HEADER = (
    "panchayats(2),,,\npublic_id,subdivision,division_public_id,division_name\n"
)


def write_csv(tmp_path, name: str, header: str, body: str) -> str:
    path = tmp_path / name
    path.write_text(header + body, encoding="utf-8")
    return str(path)


def division_csv(tmp_path, body: str) -> str:
    return write_csv(tmp_path, "divisions.csv", DIVISION_CSV_HEADER, body)


def subdivision_csv(tmp_path, body: str) -> str:
    return write_csv(tmp_path, "subdivisions.csv", SUBDIVISION_CSV_HEADER, body)


# ─────────────────────────────────────────────────────────────────────────────
# Pure logic
# ─────────────────────────────────────────────────────────────────────────────

class TestLoadDivisionCsv:
    def test_reads_rows_below_the_title_line(self, tmp_path):
        rows, issues = load_division_csv(
            division_csv(tmp_path, "DIV-001,Guwahati I Division\nDIV-004,Rangia Division\n"),
            header_row=2, encoding="utf-8",
        )
        assert [(r.public_id, r.title) for r in rows] == [
            ("DIV-001", "Guwahati I Division"), ("DIV-004", "Rangia Division")
        ]
        assert issues == []

    def test_row_numbers_are_the_files_own(self, tmp_path):
        rows, _ = load_division_csv(
            division_csv(tmp_path, "DIV-001,A Division\nDIV-002,B Division\n"),
            header_row=2, encoding="utf-8",
        )
        # Title line is 1, header is 2, so the first data row is 3.
        assert [r.row_no for r in rows] == [3, 4]

    def test_blank_lines_are_skipped_not_reported(self, tmp_path):
        rows, issues = load_division_csv(
            division_csv(tmp_path, "DIV-001,A Division\n,\nDIV-002,B Division\n"),
            header_row=2, encoding="utf-8",
        )
        assert len(rows) == 2
        assert issues == []

    def test_blank_public_id_blocks_the_row(self, tmp_path):
        rows, issues = load_division_csv(
            division_csv(tmp_path, ",A Division\n"), header_row=2, encoding="utf-8",
        )
        assert rows[0].blocking_issues
        assert any("blank public_id" in i["issue"] for i in issues)

    def test_blank_title_blocks_the_row(self, tmp_path):
        rows, _ = load_division_csv(
            division_csv(tmp_path, "DIV-001,\n"), header_row=2, encoding="utf-8",
        )
        assert rows[0].blocking_issues

    def test_missing_column_aborts_with_what_it_found(self, tmp_path):
        path = write_csv(tmp_path, "bad.csv", "title,\npublic_id,name\n", "DIV-001,X\n")
        with pytest.raises(SystemExit) as exc:
            load_division_csv(path, header_row=2, encoding="utf-8")
        assert "division_name" in str(exc.value)

    def test_ids_are_read_as_text_not_numbers(self, tmp_path):
        """keep_default_na off, dtype str: a numeric-looking id keeps its shape."""
        rows, _ = load_division_csv(
            division_csv(tmp_path, "0040,Umrangsu Division\n"), header_row=2, encoding="utf-8",
        )
        assert rows[0].public_id == "0040"


class TestLoadSubdivisionCsv:
    def test_reads_the_division_columns(self, tmp_path):
        rows, _ = load_subdivision_csv(
            subdivision_csv(tmp_path, "SDV-001,Guwahati I,DIV-001,Guwahati I Division\n"),
            header_row=2, encoding="utf-8",
        )
        row = rows[0]
        assert (row.public_id, row.title) == ("SDV-001", "Guwahati I")
        assert (row.parent_public_id, row.parent_title) == ("DIV-001", "Guwahati I Division")
        assert row.level == SUB_DIVISION_LEVEL

    def test_blank_division_is_reported_but_not_blocking(self, tmp_path):
        rows, issues = load_subdivision_csv(
            subdivision_csv(tmp_path, "SDV-001,Guwahati I,,\n"),
            header_row=2, encoding="utf-8",
        )
        assert rows[0].blocking_issues == []
        assert [i["issue_kind"] for i in issues] == ["hierarchy"]

    def test_blank_subdivision_name_blocks_the_row(self, tmp_path):
        rows, _ = load_subdivision_csv(
            subdivision_csv(tmp_path, "SDV-001,,DIV-001,Guwahati I Division\n"),
            header_row=2, encoding="utf-8",
        )
        assert rows[0].blocking_issues


class TestRowKey:
    def test_public_id_identifies_the_row(self, tmp_path):
        rows, _ = load_subdivision_csv(
            subdivision_csv(tmp_path, "SDV-001,Guwahati I,DIV-001,Guwahati I Division\n"),
            header_row=2, encoding="utf-8",
        )
        assert rows[0].key == "sdv-001"
        assert rows[0].parent_key == "div-001"

    def test_same_name_under_two_divisions_stays_two_rows_without_ids(self, tmp_path):
        """The key falls back to parent+title, not title alone.

        Without this, the state file's three duplicated sub-division names would
        collapse into one plan each and two real nodes would go unlabelled.
        """
        rows, _ = load_subdivision_csv(
            subdivision_csv(
                tmp_path,
                ",Hatisingimari,,South Salmara Division\n"
                ",Hatisingimari,,Dhubri Division\n",
            ),
            header_row=2, encoding="utf-8",
        )
        assert rows[0].key != rows[1].key


class TestDedupeRows:
    def test_repeated_public_id_keeps_the_first_and_reports(self, tmp_path):
        rows, _ = load_division_csv(
            division_csv(
                tmp_path,
                "DIV-001,Guwahati I Division\n"
                "DIV-002,Rangia Division\n"
                "DIV-001,Guwahati One Division\n",
            ),
            header_row=2, encoding="utf-8",
        )
        kept, duplicates = dedupe_rows(rows)
        assert [r.title for r in kept] == ["Guwahati I Division", "Rangia Division"]
        assert len(duplicates) == 1
        assert duplicates[0]["row_no"] == 5 and duplicates[0]["kept_row_no"] == 3

    def test_nothing_to_dedupe_reports_nothing(self, tmp_path):
        rows, _ = load_division_csv(
            division_csv(tmp_path, "DIV-001,A Division\nDIV-002,B Division\n"),
            header_row=2, encoding="utf-8",
        )
        kept, duplicates = dedupe_rows(rows)
        assert len(kept) == 2 and duplicates == []


class TestNodePath:
    NODES = {
        1: DeptNode(1, "Assam", 1, None, None),
        2: DeptNode(2, "Lower Assam Zone", 2, 1, None),
        3: DeptNode(3, "Guwahati Circle", 3, 2, None),
        4: DeptNode(4, "Guwahati I Division", 4, 3, "DIV-001"),
        5: DeptNode(5, "Boko", 5, 4, None),
    }

    def test_renders_the_chain_root_first(self):
        assert node_path(5, self.NODES, depth=3) == \
            "Guwahati Circle > Guwahati I Division > Boko"

    def test_unknown_node_renders_empty(self):
        assert node_path(99, self.NODES) == ""
        assert node_path(None, self.NODES) == ""

    def test_a_cycle_terminates(self):
        nodes = {1: DeptNode(1, "A", 4, 2, None), 2: DeptNode(2, "B", 4, 1, None)}
        assert "cycle" in node_path(1, nodes, depth=10)


class TestHierarchyStatus:
    NODES = {
        4: DeptNode(4, "Guwahati I Division", 4, 3, None),
        5: DeptNode(5, "Boko", 5, 4, None),
        6: DeptNode(6, "Rangia Division", 4, 3, None),
        7: DeptNode(7, "Baihata", 5, 6, None),
        8: DeptNode(8, "Deep", 6, 5, None),
    }

    def test_child_of_the_division_is_confirmed(self):
        assert hierarchy_status(5, 4, self.NODES) == PARENT_CONFIRMED

    def test_child_of_another_division_is_a_mismatch(self):
        assert hierarchy_status(7, 4, self.NODES) == PARENT_MISMATCH

    def test_no_division_is_unresolved(self):
        assert hierarchy_status(5, None, self.NODES) == PARENT_UNRESOLVED

    def test_a_deeper_descendant_still_counts_as_under_the_division(self):
        """The whole chain is walked, so an extra rung does not read as a mismatch."""
        assert hierarchy_status(8, 4, self.NODES) == PARENT_CONFIRMED

    def test_a_cycle_terminates(self):
        nodes = {1: DeptNode(1, "A", 5, 2, None), 2: DeptNode(2, "B", 5, 1, None)}
        assert hierarchy_status(1, 99, nodes) == PARENT_MISMATCH


class TestCli:
    def test_tenant_dsn_is_required(self, monkeypatch, tmp_path, caplog):
        monkeypatch.delenv("TENANT_DSN", raising=False)
        assert main([
            "--divisions-csv", division_csv(tmp_path, "DIV-001,A Division\n"),
            "--actor-id", "1",
        ]) == 2
        assert "TENANT_DSN" in caplog.text

    def test_an_unsafe_schema_name_is_refused(self, tmp_path, caplog):
        assert main([
            "--divisions-csv", division_csv(tmp_path, "DIV-001,A Division\n"),
            "--actor-id", "1", "--tenant-dsn", DSN, "--schema", "tenant_as; DROP SCHEMA x",
        ]) == 2
        assert "not a valid schema name" in caplog.text


# ─────────────────────────────────────────────────────────────────────────────
# Database fixtures
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


@pytest.fixture
def conn_ready(conn):
    """A throwaway schema inside a transaction that is never committed.

    Nothing here commits, so the rollback at teardown removes the schemas —
    PostgreSQL rolls DDL back like any other statement. That is deliberate:
    pointing JJM_TEST_DSN at a database that matters must not be able to
    destroy anything in it.
    """
    conn.rollback()      # clear a transaction a previous failure may have aborted
    with conn.cursor() as cur:
        _create_schema(cur, SCHEMA, with_columns=True)
        _create_schema(cur, LEGACY_SCHEMA, with_columns=False)
    yield conn
    conn.rollback()


@pytest.fixture
def db(conn_ready):
    return DeptMasterDb(conn_ready, SCHEMA)


@pytest.fixture
def writer(db):
    return DeptMasterWriter(db, ACTOR_ID)


def seed_dept(db: DeptMasterDb, title: str, level: int, parent_id=None,
              state_dept_id=None, deleted=False) -> int:
    with db.conn.cursor() as cur:
        cur.execute(
            f"SELECT id FROM {db.schema}.location_config_master_table "
            f"WHERE region_type = 2 AND level = %s", (level,)
        )
        config_id = cur.fetchone()[0]
        cur.execute(
            f"INSERT INTO {db.schema}.department_location_master_table "
            f"(title, department_location_config_id, parent_id, state_dept_id, status"
            f"{', deleted_at' if deleted else ''}) "
            f"VALUES (%s, %s, %s, %s, 1{', NOW()' if deleted else ''}) RETURNING id",
            (title, config_id, parent_id, state_dept_id),
        )
        return cur.fetchone()[0]


def seed_level(db: DeptMasterDb, level: int, name: str) -> None:
    """Add a departmental rung the shared DDL does not seed."""
    with db.conn.cursor() as cur:
        cur.execute(
            f"INSERT INTO {db.schema}.location_config_master_table "
            f"(region_type, level, level_name) VALUES (2, %s, %s::jsonb)",
            (level, f'{{"en": "{name}"}}'),
        )


def seed_scheme(db: DeptMasterDb, name: str, dept_id: int) -> int:
    with db.conn.cursor() as cur:
        cur.execute(
            f"INSERT INTO {db.schema}.scheme_master_table "
            f"(state_scheme_id, centre_scheme_id, scheme_name) VALUES (%s, %s, %s) "
            f"RETURNING id", (name, name, name),
        )
        scheme_id = cur.fetchone()[0]
        cur.execute(
            f"INSERT INTO {db.schema}.scheme_department_mapping_table "
            f"(scheme_id, parent_department_id, parent_department_level, created_by, updated_by) "
            f"VALUES (%s, %s, 'Sub-division', %s, %s)",
            (scheme_id, dept_id, ACTOR_ID, ACTOR_ID),
        )
        return scheme_id


def state_dept_id_of(db: DeptMasterDb, node_id: int):
    with db.conn.cursor() as cur:
        cur.execute(
            f"SELECT state_dept_id, updated_by FROM "
            f"{db.schema}.department_location_master_table WHERE id = %s", (node_id,)
        )
        return cur.fetchone()


def plan_for(db, tmp_path, divisions: str, subdivisions: str = "",
             match_on_id: bool = True, accept_parent_mismatch: bool = False):
    div_rows, _ = load_division_csv(
        division_csv(tmp_path, divisions), header_row=2, encoding="utf-8"
    )
    div_rows, _ = dedupe_rows(div_rows)
    sub_rows = []
    if subdivisions:
        sub_rows, _ = load_subdivision_csv(
            subdivision_csv(tmp_path, subdivisions), header_row=2, encoding="utf-8"
        )
        sub_rows, _ = dedupe_rows(sub_rows)
    return build_plan(div_rows, sub_rows, db, match_on_id, accept_parent_mismatch)


@pytest.fixture
def hierarchy(db):
    """A circle with two divisions, each with one sub-division."""
    circle = seed_dept(db, "Guwahati Circle", 3)
    guwahati = seed_dept(db, "Guwahati I Division", DIVISION_LEVEL, circle)
    rangia = seed_dept(db, "Rangia Division", DIVISION_LEVEL, circle)
    boko = seed_dept(db, "Boko", SUB_DIVISION_LEVEL, guwahati)
    baihata = seed_dept(db, "Baihata", SUB_DIVISION_LEVEL, rangia)
    return {"circle": circle, "guwahati": guwahati, "rangia": rangia,
            "boko": boko, "baihata": baihata}


# ─────────────────────────────────────────────────────────────────────────────
# Division resolution
# ─────────────────────────────────────────────────────────────────────────────

class TestDivisionResolution:
    def test_exact_title_match_plans_the_write(self, db, tmp_path, hierarchy):
        plan = plan_for(db, tmp_path, "DIV-001,Guwahati I Division\n")
        div = plan.division_plans["div-001"]
        assert div.category == DIV_MATCHED and div.matched_by == BY_TITLE
        assert div.node_id == hierarchy["guwahati"]
        assert div.state_dept_id_change == (None, "DIV-001")
        assert div.change_kind == CHANGE_NEW

    def test_the_division_suffix_is_dropped_as_a_fallback(self, db, tmp_path):
        seed_dept(db, "Umrangsu", DIVISION_LEVEL)
        plan = plan_for(db, tmp_path, "DIV-040,Umrangsu Division\n")
        div = plan.division_plans["div-040"]
        assert div.category == DIV_MATCHED and div.matched_by == BY_TITLE_SUFFIXED

    def test_an_existing_state_dept_id_wins_over_the_title(self, db, tmp_path):
        """A renamed node still resolves once its id is on it."""
        node = seed_dept(db, "Renamed Since", DIVISION_LEVEL, state_dept_id="DIV-040")
        plan = plan_for(db, tmp_path, "DIV-040,Umrangsu Division\n")
        div = plan.division_plans["div-040"]
        assert div.matched_by == BY_STATE_DEPT_ID and div.node_id == node
        assert div.change_kind == CHANGE_NONE and div.state_dept_id_change is None

    def test_no_match_on_id_re_derives_from_the_title(self, db, tmp_path):
        seed_dept(db, "Renamed Since", DIVISION_LEVEL, state_dept_id="DIV-040")
        umrangsu = seed_dept(db, "Umrangsu Division", DIVISION_LEVEL)
        plan = plan_for(db, tmp_path, "DIV-040,Umrangsu Division\n", match_on_id=False)
        div = plan.division_plans["div-040"]
        assert div.node_id == umrangsu
        # DIV-040 is on another live node, so V37's index would reject the write.
        assert WITHHELD_ID_CONFLICT in div.withheld

    def test_two_same_named_divisions_are_ambiguous_not_guessed(self, db, tmp_path):
        a = seed_dept(db, "Kokrajhar Division", DIVISION_LEVEL)
        b = seed_dept(db, "Kokrajhar Division", DIVISION_LEVEL)
        plan = plan_for(db, tmp_path, "DIV-053,Kokrajhar Division\n")
        div = plan.division_plans["div-053"]
        assert div.category == DIV_AMBIGUOUS
        assert div.candidate_ids == sorted([a, b])
        assert not div.writable

    def test_a_division_we_do_not_have_is_not_found(self, db, tmp_path, hierarchy):
        plan = plan_for(db, tmp_path, "DIV-099,Nowhere Division\n")
        assert plan.division_plans["div-099"].category == DIV_NOT_FOUND

    def test_a_soft_deleted_node_is_invisible(self, db, tmp_path):
        seed_dept(db, "Gone Division", DIVISION_LEVEL, deleted=True)
        plan = plan_for(db, tmp_path, "DIV-001,Gone Division\n")
        assert plan.division_plans["div-001"].category == DIV_NOT_FOUND

    def test_a_sub_division_node_never_matches_a_division_row(self, db, tmp_path):
        seed_dept(db, "Boko", SUB_DIVISION_LEVEL)
        plan = plan_for(db, tmp_path, "DIV-001,Boko\n")
        assert plan.division_plans["div-001"].category == DIV_NOT_FOUND


# ─────────────────────────────────────────────────────────────────────────────
# Sub-division resolution
# ─────────────────────────────────────────────────────────────────────────────

class TestSubDivisionResolution:
    def test_resolved_within_its_division(self, db, tmp_path, hierarchy):
        plan = plan_for(
            db, tmp_path,
            "DIV-001,Guwahati I Division\n",
            "SDV-002,Boko,DIV-001,Guwahati I Division\n",
        )
        sub = plan.subdivision_plans["sdv-002"]
        assert sub.matched_by == BY_TITLE_IN_PARENT
        assert sub.node_id == hierarchy["boko"]
        assert sub.parent_status == PARENT_CONFIRMED
        assert sub.state_dept_id_change == (None, "SDV-002")

    def test_the_sub_division_suffix_is_dropped_within_the_division(self, db, tmp_path):
        div = seed_dept(db, "Amguri Division", DIVISION_LEVEL)
        node = seed_dept(db, "Amguri", SUB_DIVISION_LEVEL, div)
        plan = plan_for(
            db, tmp_path, "DIV-060,Amguri Division\n",
            "SDV-070,Amguri Sub-division,DIV-060,Amguri Division\n",
        )
        sub = plan.subdivision_plans["sdv-070"]
        assert sub.matched_by == BY_TITLE_SUFFIXED_IN_PARENT and sub.node_id == node

    def test_a_name_shared_across_divisions_resolves_by_hierarchy(self, db, tmp_path):
        """The case the state file actually contains, three times over.

        Tenant-wide the name matches two nodes; restricted to each division it
        matches exactly one, so both get the right id instead of both being
        skipped as ambiguous.
        """
        salmara = seed_dept(db, "South Salmara Mankachar Division", DIVISION_LEVEL)
        dhubri = seed_dept(db, "Dhubri Division", DIVISION_LEVEL)
        under_salmara = seed_dept(db, "Hatisingimari", SUB_DIVISION_LEVEL, salmara)
        under_dhubri = seed_dept(db, "Hatisingimari", SUB_DIVISION_LEVEL, dhubri)

        plan = plan_for(
            db, tmp_path,
            "DIV-006,South Salmara Mankachar Division\nDIV-012,Dhubri Division\n",
            "SDV-011,Hatisingimari,DIV-006,South Salmara Mankachar Division\n"
            "SDV-019,Hatisingimari,DIV-012,Dhubri Division\n",
        )
        assert plan.subdivision_plans["sdv-011"].node_id == under_salmara
        assert plan.subdivision_plans["sdv-019"].node_id == under_dhubri
        assert all(p.parent_status == PARENT_CONFIRMED
                   for p in plan.subdivision_plans.values())
        assert len(plan.writable) == 4

    def test_the_same_name_is_ambiguous_without_the_division(self, db, tmp_path):
        """Drop the hierarchy and the tie is real — and is never guessed."""
        salmara = seed_dept(db, "South Salmara Mankachar Division", DIVISION_LEVEL)
        dhubri = seed_dept(db, "Dhubri Division", DIVISION_LEVEL)
        seed_dept(db, "Hatisingimari", SUB_DIVISION_LEVEL, salmara)
        seed_dept(db, "Hatisingimari", SUB_DIVISION_LEVEL, dhubri)
        plan = plan_for(db, tmp_path, "", "SDV-011,Hatisingimari,,\n")
        sub = plan.subdivision_plans["sdv-011"]
        assert sub.category == DIV_AMBIGUOUS and len(sub.candidate_ids) == 2
        assert sub.parent_node_id is None and not sub.writable

    def test_a_tenant_wide_match_elsewhere_is_withheld(self, db, tmp_path, hierarchy):
        """Our hierarchy says Baihata is under Rangia; the CSV says Guwahati."""
        plan = plan_for(
            db, tmp_path, "DIV-001,Guwahati I Division\n",
            "SDV-009,Baihata,DIV-001,Guwahati I Division\n",
        )
        sub = plan.subdivision_plans["sdv-009"]
        assert sub.category == DIV_MATCHED and sub.matched_by == BY_TITLE
        assert sub.parent_status == PARENT_MISMATCH
        assert WITHHELD_PARENT_MISMATCH in sub.withheld and not sub.writable
        assert "Rangia Division" in sub.reason

    def test_accept_parent_mismatch_writes_it(self, db, tmp_path, hierarchy):
        plan = plan_for(
            db, tmp_path, "DIV-001,Guwahati I Division\n",
            "SDV-009,Baihata,DIV-001,Guwahati I Division\n",
            accept_parent_mismatch=True,
        )
        sub = plan.subdivision_plans["sdv-009"]
        assert sub.writable and sub.state_dept_id_change == (None, "SDV-009")

    def test_an_unresolved_division_still_allows_a_unique_match(self, db, tmp_path):
        """Nothing contradicts the match, so it is written — and flagged."""
        node = seed_dept(db, "Boko", SUB_DIVISION_LEVEL)
        plan = plan_for(db, tmp_path, "", "SDV-002,Boko,DIV-999,Unknown Division\n")
        sub = plan.subdivision_plans["sdv-002"]
        assert sub.node_id == node and sub.parent_status == PARENT_UNRESOLVED
        assert sub.writable

    def test_a_division_missing_from_the_master_file_is_resolved_by_name(
        self, db, tmp_path, hierarchy
    ):
        """The sub-division file names a division the division file omits."""
        plan = plan_for(db, tmp_path, "", "SDV-002,Boko,DIV-001,Guwahati I Division\n")
        sub = plan.subdivision_plans["sdv-002"]
        assert sub.parent_node_id == hierarchy["guwahati"]
        assert sub.parent_status == PARENT_CONFIRMED

    def test_a_division_node_never_matches_a_sub_division_row(self, db, tmp_path, hierarchy):
        plan = plan_for(db, tmp_path, "", "SDV-002,Rangia Division,DIV-001,Guwahati I Division\n")
        assert plan.subdivision_plans["sdv-002"].category == DIV_NOT_FOUND

    def test_a_grandchild_of_the_division_still_confirms(self, db, tmp_path):
        """A tenant that nests an extra rung is not read as a mismatch."""
        seed_level(db, 6, "Section")
        div = seed_dept(db, "Deep Division", DIVISION_LEVEL)
        middle = seed_dept(db, "Middle", 6, div)
        node = seed_dept(db, "Boko", SUB_DIVISION_LEVEL, middle)
        plan = plan_for(
            db, tmp_path, "DIV-001,Deep Division\n",
            "SDV-002,Boko,DIV-001,Deep Division\n",
        )
        sub = plan.subdivision_plans["sdv-002"]
        assert sub.node_id == node and sub.parent_status == PARENT_CONFIRMED


# ─────────────────────────────────────────────────────────────────────────────
# What gets written, and what is held back
# ─────────────────────────────────────────────────────────────────────────────

class TestWritePlanning:
    def test_a_node_already_carrying_the_id_is_left_alone(self, db, tmp_path):
        seed_dept(db, "Guwahati I Division", DIVISION_LEVEL, state_dept_id="DIV-001")
        plan = plan_for(db, tmp_path, "DIV-001,Guwahati I Division\n")
        div = plan.division_plans["div-001"]
        assert div.change_kind == CHANGE_NONE and not div.writable
        assert plan.writable == []

    def test_a_different_existing_id_is_overwritten_and_reported(self, db, tmp_path):
        node = seed_dept(db, "Guwahati I Division", DIVISION_LEVEL, state_dept_id="DIV-OLD")
        plan = plan_for(db, tmp_path, "DIV-001,Guwahati I Division\n")
        div = plan.division_plans["div-001"]
        assert div.change_kind == CHANGE_OVERWRITE
        assert div.state_dept_id_change == ("DIV-OLD", "DIV-001") and div.writable
        assert div.node_id == node

    def test_two_rows_resolving_to_one_node_write_nothing(self, db, tmp_path):
        seed_dept(db, "Kokrajhar Division", DIVISION_LEVEL)
        plan = plan_for(
            db, tmp_path,
            "DIV-053,Kokrajhar Division\nDIV-054,Kokrajhar Div\n",
        )
        assert all(WITHHELD_CONTESTED in p.withheld for p in plan.division_plans.values())
        assert plan.writable == []

    def test_an_id_held_by_another_node_is_withheld(self, db, tmp_path):
        seed_dept(db, "Somewhere Else", DIVISION_LEVEL, state_dept_id="DIV-001")
        seed_dept(db, "Guwahati I Division", DIVISION_LEVEL)
        plan = plan_for(db, tmp_path, "DIV-001,Guwahati I Division\n", match_on_id=False)
        div = plan.division_plans["div-001"]
        assert WITHHELD_ID_CONFLICT in div.withheld and not div.writable

    def test_the_id_conflict_rule_is_what_keeps_the_unique_index_happy(
        self, db, writer, tmp_path
    ):
        """Without the withholding, this batch would violate V37's index."""
        seed_dept(db, "Somewhere Else", DIVISION_LEVEL, state_dept_id="DIV-001")
        seed_dept(db, "Guwahati I Division", DIVISION_LEVEL)
        plan = plan_for(db, tmp_path, "DIV-001,Guwahati I Division\n", match_on_id=False)
        assert writer.write_state_dept_ids(plan.writable) == 0

    def test_an_id_conflict_across_levels_is_also_withheld(self, db, tmp_path):
        """V37's index is not scoped to a level, so neither is the check."""
        seed_dept(db, "A Sub-division", SUB_DIVISION_LEVEL, state_dept_id="DIV-001")
        seed_dept(db, "Guwahati I Division", DIVISION_LEVEL)
        plan = plan_for(db, tmp_path, "DIV-001,Guwahati I Division\n", match_on_id=False)
        assert WITHHELD_ID_CONFLICT in plan.division_plans["div-001"].withheld


# ─────────────────────────────────────────────────────────────────────────────
# Drift between the state's list and ours
# ─────────────────────────────────────────────────────────────────────────────

class TestDrift:
    def test_nodes_no_row_resolved_to_are_listed_with_their_schemes(
        self, db, tmp_path, hierarchy
    ):
        seed_scheme(db, "S1", hierarchy["baihata"])
        seed_scheme(db, "S2", hierarchy["baihata"])
        plan = plan_for(
            db, tmp_path, "DIV-001,Guwahati I Division\n",
            "SDV-002,Boko,DIV-001,Guwahati I Division\n",
        )
        extras = {n.id for n in plan.extra_db_nodes()}
        assert extras == {hierarchy["rangia"], hierarchy["baihata"]}

        frame = build_extra_in_db_frame(plan)
        baihata = frame[frame["node_id"] == hierarchy["baihata"]].iloc[0]
        assert baihata["schemes_direct"] == 2
        assert "missing from the state" in baihata["note"]
        rangia = frame[frame["node_id"] == hierarchy["rangia"]].iloc[0]
        assert rangia["schemes_direct"] == 0 and rangia["schemes_subtree"] == 2
        assert rangia["node_path"].endswith("Guwahati Circle > Rangia Division")

    def test_a_circle_is_not_reported_as_extra(self, db, tmp_path, hierarchy):
        """Only the two levels these files speak about are reconciled."""
        plan = plan_for(db, tmp_path, "")
        assert hierarchy["circle"] not in {n.id for n in plan.extra_db_nodes()}

    def test_rows_we_cannot_place_are_listed_with_the_division_verdict(
        self, db, tmp_path, hierarchy
    ):
        plan = plan_for(
            db, tmp_path, "DIV-001,Guwahati I Division\n",
            "SDV-050,Nowhere,DIV-001,Guwahati I Division\n"
            "SDV-051,Elsewhere,DIV-099,Unknown Division\n",
        )
        frame = build_extra_in_csv_frame(plan)
        verdicts = dict(zip(frame["public_id"], frame["division_resolved"]))
        assert verdicts == {"SDV-050": "yes", "SDV-051": "no"}

    def test_an_id_neither_file_names_is_stale(self, db, tmp_path, hierarchy):
        seed_dept(db, "Legacy Division", DIVISION_LEVEL, state_dept_id="DIV-OLD")
        plan = plan_for(db, tmp_path, "DIV-001,Guwahati I Division\n")
        assert [n.state_dept_id for n in plan.stale_state_dept_ids()] == ["DIV-OLD"]

    def test_an_id_this_run_replaces_is_not_stale(self, db, tmp_path):
        seed_dept(db, "Guwahati I Division", DIVISION_LEVEL, state_dept_id="DIV-OLD")
        plan = plan_for(db, tmp_path, "DIV-001,Guwahati I Division\n")
        assert plan.stale_state_dept_ids() == []


# ─────────────────────────────────────────────────────────────────────────────
# Writing
# ─────────────────────────────────────────────────────────────────────────────

class TestExecute:
    def test_the_ids_land_on_the_right_nodes(self, db, writer, tmp_path, hierarchy):
        plan = plan_for(
            db, tmp_path,
            "DIV-001,Guwahati I Division\nDIV-004,Rangia Division\n",
            "SDV-002,Boko,DIV-001,Guwahati I Division\n"
            "SDV-009,Baihata,DIV-004,Rangia Division\n",
        )
        counts = execute_tenant(plan, writer)
        assert counts == {"state_dept_id_written": 4}
        assert state_dept_id_of(db, hierarchy["guwahati"]) == ("DIV-001", ACTOR_ID)
        assert state_dept_id_of(db, hierarchy["boko"]) == ("SDV-002", ACTOR_ID)
        assert state_dept_id_of(db, hierarchy["baihata"]) == ("SDV-009", ACTOR_ID)

    def test_re_running_the_same_files_writes_nothing(self, db, writer, tmp_path, hierarchy):
        divisions = "DIV-001,Guwahati I Division\n"
        subdivisions = "SDV-002,Boko,DIV-001,Guwahati I Division\n"
        execute_tenant(plan_for(db, tmp_path, divisions, subdivisions), writer)
        second = plan_for(db, tmp_path, divisions, subdivisions)
        assert second.writable == []
        assert execute_tenant(second, writer) == {"state_dept_id_written": 0}

    def test_a_node_soft_deleted_after_the_analysis_is_skipped(
        self, db, writer, tmp_path, hierarchy
    ):
        plan = plan_for(db, tmp_path, "DIV-001,Guwahati I Division\n")
        with db.conn.cursor() as cur:
            cur.execute(
                f"UPDATE {db.schema}.department_location_master_table "
                f"SET deleted_at = NOW() WHERE id = %s", (hierarchy["guwahati"],)
            )
        assert writer.write_state_dept_ids(plan.writable) == 0

    def test_nothing_is_written_when_there_is_nothing_to_write(self, db, writer):
        assert writer.write_state_dept_ids([]) == 0


class TestWithoutTheMigration:
    def test_a_schema_without_v37_is_refused_up_front(self, conn_ready):
        db = DeptMasterDb(conn_ready, LEGACY_SCHEMA)
        assert db.state_dept_id_column_exists() is False
        with pytest.raises(SystemExit) as exc:
            db.assert_state_dept_id_column()
        assert "V37" in str(exc.value)

    def test_a_missing_schema_is_refused_up_front(self, conn_ready):
        with pytest.raises(SystemExit) as exc:
            DeptMasterDb(conn_ready, "tenant_does_not_exist").assert_schema_exists()
        assert "does not exist" in str(exc.value)

    def test_an_unsafe_schema_name_never_reaches_sql(self, conn_ready):
        with pytest.raises(ValueError):
            DeptMasterDb(conn_ready, 'tenant_as"; DROP SCHEMA public CASCADE; --')


# ─────────────────────────────────────────────────────────────────────────────
# Workbook
# ─────────────────────────────────────────────────────────────────────────────

class TestAnalysisWorkbook:
    def test_every_sheet_is_written_even_when_empty(self, db, tmp_path, hierarchy):
        plan = plan_for(
            db, tmp_path,
            "DIV-001,Guwahati I Division\nDIV-099,Nowhere Division\n",
            "SDV-002,Boko,DIV-001,Guwahati I Division\n"
            "SDV-009,Baihata,DIV-001,Guwahati I Division\n",
        )
        out = str(tmp_path / "analysis.xlsx")
        write_analysis_workbook(plan, out, {"schema": SCHEMA})
        sheets = pd.read_excel(out, sheet_name=None)
        assert set(sheets) == {
            "run_info", "summary", "division_detail", "subdivision_detail",
            "extra_in_csv", "extra_in_db", "ambiguous", "hierarchy_review",
            "conflicts", "overwrites", "stale_ids", "csv_duplicates", "csv_issues",
        }
        assert len(sheets["division_detail"]) == 2
        assert list(sheets["extra_in_csv"]["public_id"]) == ["DIV-099"]
        # Baihata matched tenant-wide against our hierarchy: reported, withheld.
        assert list(sheets["hierarchy_review"]["public_id"]) == ["SDV-009"]
        assert list(sheets["hierarchy_review"]["will_write"]) == ["no"]
        assert list(sheets["conflicts"]["public_id"]) == ["SDV-009"]

    def test_the_summary_counts_both_directions_of_drift(self, db, tmp_path, hierarchy):
        plan = plan_for(db, tmp_path, "DIV-001,Guwahati I Division\nDIV-099,Nowhere Division\n")
        out = str(tmp_path / "analysis.xlsx")
        write_analysis_workbook(plan, out, {})
        summary = pd.read_excel(out, sheet_name="summary")

        def value(section, metric):
            hit = summary[(summary["section"] == section) & (summary["metric"] == metric)]
            return hit.iloc[0]["value"]

        assert value("divisions", "resolved") == 1
        assert value("divisions", "unresolved: NOT_FOUND") == 1
        assert value("divisions", "extra in our DB") == 1        # Rangia
        assert value("sub-divisions", "extra in our DB") == 2    # Boko, Baihata
        assert value("totals", "rows to write") == 1

    def test_the_ambiguous_sheet_names_the_rivals_with_their_ancestry(self, db, tmp_path):
        circle = seed_dept(db, "Guwahati Circle", 3)
        seed_dept(db, "Kokrajhar Division", DIVISION_LEVEL, circle)
        seed_dept(db, "Kokrajhar Division", DIVISION_LEVEL, circle)
        plan = plan_for(db, tmp_path, "DIV-053,Kokrajhar Division\n")
        out = str(tmp_path / "analysis.xlsx")
        write_analysis_workbook(plan, out, {})
        row = pd.read_excel(out, sheet_name="ambiguous").iloc[0]
        assert row["candidate_count"] == 2
        assert row["candidates"].count("Guwahati Circle > Kokrajhar Division") == 2
