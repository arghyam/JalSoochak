"""Tests for jjm_subdivision_sdo_mapping_ingest.

Two layers:

  * pure logic — CSV parsing (including the state export's 'pubic_id' typo), the
    sub-division title fallback, the ancestry rendering that disambiguates two
    same-named nodes, and the role gate. These run anywhere.
  * write paths — resolution at sub-division level, mapping planning and every
    INSERT/UPDATE against a real PostgreSQL. Resolution walks a hierarchy and
    the writes build SQL dynamically, and a mock cannot show that the generated
    statement executes, types its columns correctly, or lands the right values
    on the right rows.

What is deliberately *not* re-tested here is the engine itself — subtree walks,
the user matching contract, the soft delete, the warehouse projection. That is
div-ee-mapping/test_jjm_division_ee_mapping_ingest.py's job, and this module
imports the same code. What is tested is everything the sub-division/SDO
contract changes: the level, the suffixes, the fixed role, the ambiguity
reporting and the CSV shape.

The DDL and seed helpers are imported from the division/EE suite for the same
reason the production module imports its engine — one schema definition, not
two that drift.

  export JJM_TEST_DSN='postgresql://postgres:testpw@localhost:55432/shared_db'
  python3 -m pytest "scripts/jjm master data ingestion/subdiv-sdo-mapping/test_jjm_subdivision_sdo_mapping_ingest.py" -v
"""

from __future__ import annotations

import os
import sys

import pandas as pd
import psycopg2
import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(
    0, os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, "div-ee-mapping")
)

from jjm_division_ee_mapping_ingest import (  # noqa: E402
    BY_STATE_DEPT_ID,
    BY_TITLE,
    BY_TITLE_SUFFIXED,
    CAT_EXISTING,
    CAT_NEW,
    DIV_AMBIGUOUS,
    DIV_MATCHED,
    DIV_NOT_FOUND,
    DeptNode,
    DivisionDb,
    MappingWriter,
    RolePlan,
    UserWriter,
    title_core,
)
from test_jjm_division_ee_mapping_ingest import (  # noqa: E402
    ACTOR_ID,
    COMMON_DDL,
    TENANT_ID,
    _create_schema,
    _pii,
    seed_dept,
    seed_mapping,
    seed_scheme,
    seed_user,
)

from jjm_subdivision_sdo_mapping_ingest import (  # noqa: E402
    SDO_ROLE,
    SUB_DIVISION_KIND,
    SUB_DIVISION_LEVEL,
    SUB_DIVISION_SUFFIXES,
    SdoIngestPlan,
    build_plan,
    execute_tenant,
    load_csv,
    node_path,
    resolve_public_id_column,
    unusable_roles,
    write_analysis_workbook,
)

DSN = os.environ.get("JJM_TEST_DSN", "postgresql://postgres:testpw@localhost:55432/shared_db")
SCHEMA = "tenant_sdotest"
LEGACY_SCHEMA = "tenant_sdotest_prev37"

DIVISION_LEVEL = 4
BELOW_SUB_DIVISION_LEVEL = SUB_DIVISION_LEVEL + 1

# The state export's real header, typo and all.
CSV_HEADER = (
    "subdivision-sdo-mapping,,,,\n"
    "subdivision_public_id,subdivision,pubic_id,sdo_name,sdo_phone\n"
)
FIXED_CSV_HEADER = (
    "subdivision-sdo-mapping,,,,\n"
    "subdivision_public_id,subdivision,public_id,sdo_name,sdo_phone\n"
)


def write_csv(tmp_path, *lines: str, header: str = CSV_HEADER) -> str:
    path = tmp_path / "map.csv"
    path.write_text(header + "".join(line + "\n" for line in lines), encoding="utf-8")
    return str(path)


def rows_of(tmp_path, *lines: str, header: str = CSV_HEADER):
    rows, issues, _ = load_csv(
        write_csv(tmp_path, *lines, header=header), header_row=2, encoding="utf-8")
    return rows, issues


# ─────────────────────────────────────────────────────────────────────────────
# CSV parsing
# ─────────────────────────────────────────────────────────────────────────────

class TestLoadCsv:
    def test_reads_the_state_export_layout(self, tmp_path):
        """The export puts a title line above the header, and row numbers in the
        report have to line up with what the operator sees in the file."""
        rows, issues = rows_of(
            tmp_path,
            "SDV-028,Bihpuria,USR-016067,Pritam Singh,7002506100",
            "SDV-016,Bongaigaon,USR-019589,Pallabi Gogoi,9101092388",
        )

        assert [r.row_no for r in rows] == [3, 4]
        assert rows[0].subdivision_public_id == "SDV-028"
        assert rows[0].subdivision_title == "Bihpuria"
        assert rows[0].user_public_id == "USR-016067"
        assert rows[0].phone == "917002506100"
        assert issues == []

    def test_every_row_is_an_sdo_without_a_role_column(self, tmp_path):
        """The file has no role column: the role is what the file is. It must be
        applied silently rather than reported once per row."""
        rows, issues = rows_of(tmp_path, "SDV-1,Alpha,USR-1,Some Person,9000000001")

        assert rows[0].role == SDO_ROLE
        assert rows[0].role_raw == ""
        assert rows[0].blocking_issues == []
        assert issues == []

    def test_accepts_both_spellings_of_the_public_id_column(self, tmp_path):
        """The state misspells it 'pubic_id'. A corrected re-export must not
        break the tool, and neither must the original."""
        typo, _, typo_column = load_csv(
            write_csv(tmp_path, "SDV-1,Alpha,USR-1,A,9000000001"),
            header_row=2, encoding="utf-8",
        )
        fixed, _, fixed_column = load_csv(
            write_csv(tmp_path, "SDV-1,Alpha,USR-1,A,9000000001", header=FIXED_CSV_HEADER),
            header_row=2, encoding="utf-8",
        )

        assert typo[0].user_public_id == "USR-1"
        assert fixed[0].user_public_id == "USR-1"
        # Which spelling was read is reported, not silently absorbed.
        assert (typo_column, fixed_column) == ("pubic_id", "public_id")

    def test_public_id_wins_over_the_typo_when_both_are_present(self):
        assert resolve_public_id_column(["subdivision", "pubic_id", "public_id"]) == "public_id"
        assert resolve_public_id_column(["subdivision", "pubic_id"]) == "pubic_id"
        assert resolve_public_id_column(["subdivision", "sdo_name"]) is None

    def test_a_file_without_a_public_id_column_is_refused(self, tmp_path):
        path = tmp_path / "map.csv"
        path.write_text(
            "subdivision-sdo-mapping,,,\n"
            "subdivision_public_id,subdivision,sdo_name,sdo_phone\n"
            "SDV-1,Alpha,A,9000000001\n",
            encoding="utf-8",
        )

        with pytest.raises(SystemExit) as exc:
            load_csv(str(path), header_row=2, encoding="utf-8")
        assert "public_id or pubic_id" in str(exc.value)

    def test_flags_unusable_rows_without_echoing_the_number(self, tmp_path):
        """An invalid phone is reported, but the number itself is PII and must
        not be written into the issue text."""
        rows, issues = rows_of(
            tmp_path,
            "SDV-1,Alpha,USR-1,Bad Phone,1234567890",
            "SDV-2,Beta,USR-2,,9000000002",
            ",,USR-3,No Sub-division,9000000003",
            ",Gamma,USR-4,No Sub-div Id,9000000004",
        )

        assert rows[0].blocking_issues == [
            "row:sdo_phone is not a valid Indian mobile number"
        ]
        assert rows[1].blocking_issues == ["row:blank sdo_name"]
        assert rows[2].blocking_issues == [
            "row:blank subdivision — nothing to resolve the sub-division by"
        ]
        # A missing subdivision_public_id alone is reported, never blocking: the
        # title still resolves the sub-division.
        assert rows[3].blocking_issues == []
        assert not any("1234567890" in i["issue"] for i in issues)

    def test_issue_records_use_the_csv_vocabulary(self, tmp_path):
        _, issues = rows_of(tmp_path, ",Gamma,,No Ids,9000000004")

        kinds = {i["issue_kind"] for i in issues}
        assert kinds == {"state_dept_id", "state_user_id"}
        assert issues[0]["subdivision"] == "Gamma"
        assert "subdivision_public_id" in issues[0]

    def test_subdivision_key_prefers_the_state_id(self, tmp_path):
        rows, _ = rows_of(
            tmp_path,
            "SDV-1,Alpha,USR-1,A,9000000001",
            ",Beta,USR-2,B,9000000002",
        )

        assert rows[0].division_key == "sdv-1"
        assert rows[1].division_key == "beta"

    def test_the_real_export_parses(self):
        """The file this tool exists for, as shipped: 106 rows, two of which
        carry a phone number that is not an Indian mobile."""
        csv_path = os.path.join(
            os.path.dirname(os.path.abspath(__file__)), "subdivision-sdo-mapping.csv"
        )
        if not os.path.exists(csv_path):
            pytest.skip("the state export is not checked out here")

        rows, _, _ = load_csv(csv_path, header_row=2, encoding="utf-8-sig")

        assert len(rows) == 106
        assert all(r.role == SDO_ROLE for r in rows)
        assert len([r for r in rows if r.blocking_issues]) == 2


# ─────────────────────────────────────────────────────────────────────────────
# Title suffixes
# ─────────────────────────────────────────────────────────────────────────────

class TestSubDivisionTitleCore:
    @pytest.mark.parametrize("raw,expected", [
        ("Amguri Sub-division", "amguri"),
        ("Amguri Sub Division", "amguri"),
        ("Amguri Subdivision", "amguri"),
        ("Amguri Sub-Divn.", "amguri"),
        ("Amguri SD", "amguri"),
        ("  Bihpuria  ", "bihpuria"),
        ("Tezpur II", "tezpur ii"),
        ("Diphu Rural", "diphu rural"),
    ])
    def test_strips_only_a_sub_division_suffix(self, raw, expected):
        assert title_core(raw, SUB_DIVISION_SUFFIXES) == expected

    def test_a_longer_suffix_wins_over_its_own_tail(self):
        """'sub division' has to be tried before anything ending in 'division',
        or 'Amguri Sub Division' would come back as 'amguri sub'."""
        assert title_core("Amguri Sub Division", SUB_DIVISION_SUFFIXES) == "amguri"

    def test_a_bare_division_suffix_is_left_alone(self):
        """A node called '<X> Division' is a division, not a sub-division;
        stripping the word here would make it collide with sub-division '<X>'."""
        assert title_core("Nagaon Division", SUB_DIVISION_SUFFIXES) == "nagaon division"


# ─────────────────────────────────────────────────────────────────────────────
# Ancestry rendering
# ─────────────────────────────────────────────────────────────────────────────

def _nodes(*specs) -> dict[int, DeptNode]:
    return {
        node_id: DeptNode(node_id, title, level, parent, None)
        for node_id, title, level, parent in specs
    }


class TestNodePath:
    def test_renders_the_chain_that_distinguishes_two_namesakes(self):
        nodes = _nodes(
            (1, "Central Zone", 2, None),
            (2, "Nagaon Circle", 3, 1),
            (3, "Nagaon Division", 4, 2),
            (4, "Tezpur", 5, 3),
        )

        assert node_path(4, nodes) == "Tezpur < Nagaon Division < Nagaon Circle < Central Zone"

    def test_elides_only_when_there_is_more_chain_above(self):
        nodes = _nodes(
            (1, "State", 1, None),
            (2, "Zone", 2, 1),
            (3, "Circle", 3, 2),
            (4, "Division", 4, 3),
            (5, "Sub", 5, 4),
        )

        assert node_path(5, nodes, depth=2) == "Sub < Division < Circle < …"
        # Exactly at the limit, with nothing above: no ellipsis.
        assert node_path(4, nodes, depth=3) == "Division < Circle < Zone < State"

    def test_a_cycle_terminates(self):
        nodes = _nodes((1, "A", 5, 2), (2, "B", 4, 1))

        assert node_path(1, nodes) == "A < B"

    def test_an_unknown_node_renders_as_nothing(self):
        assert node_path(99, _nodes((1, "A", 5, None))) == ""
        assert node_path(None, {}) == ""


# ─────────────────────────────────────────────────────────────────────────────
# The role gate
# ─────────────────────────────────────────────────────────────────────────────

def _plan_with_role(action: str, existing_id=None) -> SdoIngestPlan:
    return SdoIngestPlan(
        engineers=[], divisions={}, user_types={}, csv_issues=[],
        role_plans=[RolePlan(
            role=SDO_ROLE, csv_slugs=[], csv_rows=7,
            existing_id=existing_id, action=action,
        )],
    )


class TestUnusableRoles:
    def test_the_expected_case_is_silent(self):
        assert unusable_roles(_plan_with_role("existing", existing_id=4)) == []

    def test_a_missing_role_blocks_rather_than_being_created(self):
        """Minting a second SUB_DIVISIONAL_OFFICER would split the role in two
        and strand whichever officers landed on the wrong id."""
        problems = unusable_roles(_plan_with_role("create"))

        assert len(problems) == 1
        assert "not in common_schema.user_type_master_table" in problems[0]
        assert "7 officer(s)" in problems[0]

    def test_a_soft_deleted_role_blocks(self):
        problems = unusable_roles(_plan_with_role("blocked_soft_deleted", existing_id=9))

        assert len(problems) == 1
        assert "soft-deleted" in problems[0]


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
            "VALUES ('SUB_DIVISIONAL_OFFICER'), ('SECTION_OFFICER'), ('STATE_ADMIN') "
            "ON CONFLICT (c_name) DO NOTHING"
        )
        _create_schema(cur, SCHEMA, with_columns=True)
        _create_schema(cur, LEGACY_SCHEMA, with_columns=False)
        # The shared DDL stops at sub-division, which is as deep as the division
        # tool ever looks. One rung further down is what proves the subtree walk
        # still reaches schemes a tenant hung below a sub-division.
        for schema in (SCHEMA, LEGACY_SCHEMA):
            cur.execute(
                f"INSERT INTO {schema}.location_config_master_table "
                f"(region_type, level, level_name) VALUES (2, %s, %s::jsonb)",
                (BELOW_SUB_DIVISION_LEVEL, '{"en": "Section"}'),
            )
    yield conn
    conn.rollback()


def _db(conn_ready, schema: str, with_columns: bool) -> DivisionDb:
    return DivisionDb(
        conn_ready, schema, _pii(),
        with_state_user_id=with_columns,
        with_state_dept_id=with_columns,
        division_level=SUB_DIVISION_LEVEL,
        node_kind=SUB_DIVISION_KIND,
    )


@pytest.fixture
def db(conn_ready):
    return _db(conn_ready, SCHEMA, with_columns=True)


@pytest.fixture
def legacy_db(conn_ready):
    """The same tool against a tenant where neither V36 nor V37 has been applied."""
    return _db(conn_ready, LEGACY_SCHEMA, with_columns=False)


@pytest.fixture
def roles(db) -> dict[str, int]:
    return {name: row.id for name, row in db.load_user_types().items()}


@pytest.fixture
def writers(db):
    return UserWriter(db, TENANT_ID, ACTOR_ID), MappingWriter(db, ACTOR_ID)


def plan_for(db, tmp_path, *lines: str, **kwargs) -> SdoIngestPlan:
    rows, issues = rows_of(tmp_path, *lines)
    return build_plan(rows, issues, db, **kwargs)


def sub_plan(plan: SdoIngestPlan, key: str):
    return plan.subdivisions[key]


def officer(plan: SdoIngestPlan, index: int = 0):
    return plan.officers[index]


# ─────────────────────────────────────────────────────────────────────────────
# Sub-division resolution
# ─────────────────────────────────────────────────────────────────────────────

class TestSubDivisionResolution:
    def test_resolves_a_sub_division_by_title(self, db, tmp_path):
        division = seed_dept(db, "Nagaon Division", DIVISION_LEVEL)
        sub = seed_dept(db, "Kathiatoli", SUB_DIVISION_LEVEL, parent_id=division)
        scheme = seed_scheme(db, "S1", sub)

        plan = plan_for(db, tmp_path, "SDV-052,Kathiatoli,USR-1,A,9000000001")

        resolved = sub_plan(plan, "sdv-052")
        assert resolved.category == DIV_MATCHED
        assert resolved.matched_by == BY_TITLE
        assert resolved.node_id == sub
        assert resolved.scheme_ids == {scheme}
        assert len(resolved.scheme_ids) == 1

    def test_a_division_of_the_same_name_is_not_a_candidate(self, db, tmp_path):
        """The whole point of moving to level 5: 'Nagaon' the division and
        'Nagaon' the sub-division are different nodes with different schemes."""
        division = seed_dept(db, "Nagaon", DIVISION_LEVEL)
        seed_scheme(db, "DIVISION_SCHEME", division)
        sub = seed_dept(db, "Nagaon", SUB_DIVISION_LEVEL, parent_id=division)
        sub_scheme = seed_scheme(db, "SUB_SCHEME", sub)

        plan = plan_for(db, tmp_path, "SDV-053,Nagaon,USR-1,A,9000000001")

        resolved = sub_plan(plan, "sdv-053")
        assert resolved.node_id == sub
        assert resolved.scheme_ids == {sub_scheme}

    def test_falls_back_to_the_title_without_its_sub_division_suffix(self, db, tmp_path):
        division = seed_dept(db, "Nagaon Division", DIVISION_LEVEL)
        sub = seed_dept(db, "Kathiatoli Sub-division", SUB_DIVISION_LEVEL, parent_id=division)
        seed_scheme(db, "S1", sub)

        plan = plan_for(db, tmp_path, "SDV-052,Kathiatoli,USR-1,A,9000000001")

        resolved = sub_plan(plan, "sdv-052")
        assert resolved.category == DIV_MATCHED
        assert resolved.matched_by == BY_TITLE_SUFFIXED
        assert resolved.node_id == sub

    def test_the_state_id_wins_when_it_is_already_on_the_node(self, db, tmp_path):
        division = seed_dept(db, "Nagaon Division", DIVISION_LEVEL)
        renamed = seed_dept(db, "Kathiatoli (old name)", SUB_DIVISION_LEVEL,
                            parent_id=division, state_dept_id="SDV-052")
        seed_scheme(db, "S1", renamed)
        seed_dept(db, "Kathiatoli", SUB_DIVISION_LEVEL, parent_id=division)

        plan = plan_for(db, tmp_path, "SDV-052,Kathiatoli,USR-1,A,9000000001")

        resolved = sub_plan(plan, "sdv-052")
        assert resolved.matched_by == BY_STATE_DEPT_ID
        assert resolved.node_id == renamed

    def test_two_namesakes_are_ambiguous_and_report_their_ancestry(self, db, tmp_path):
        """Two sub-divisions under different divisions can share a name and the
        CSV carries no division column to tell them apart. Guessing would map an
        officer onto someone else's schemes, so the row is skipped — and the
        report has to name both nodes well enough for a human to fix it."""
        first = seed_dept(db, "Tezpur Division", DIVISION_LEVEL)
        second = seed_dept(db, "Sonitpur Division", DIVISION_LEVEL)
        a = seed_dept(db, "Tezpur", SUB_DIVISION_LEVEL, parent_id=first)
        b = seed_dept(db, "Tezpur", SUB_DIVISION_LEVEL, parent_id=second)

        plan = plan_for(db, tmp_path, "SDV-020,Tezpur,USR-1,A,9000000001")

        resolved = sub_plan(plan, "sdv-020")
        assert resolved.category == DIV_AMBIGUOUS
        assert resolved.candidate_ids == sorted([a, b])
        assert "sub-division" in resolved.reason
        assert node_path(a, plan.nodes) == "Tezpur < Tezpur Division"
        assert node_path(b, plan.nodes) == "Tezpur < Sonitpur Division"
        # An officer with nothing resolvable is not onboarded.
        assert not officer(plan).will_write
        assert officer(plan).skip_reason == SUB_DIVISION_KIND.no_node_reason

    def test_an_unknown_sub_division_is_reported_not_guessed(self, db, tmp_path):
        seed_dept(db, "Nagaon Division", DIVISION_LEVEL)

        plan = plan_for(db, tmp_path, "SDV-999,Nowhere,USR-1,A,9000000001")

        resolved = sub_plan(plan, "sdv-999")
        assert resolved.category == DIV_NOT_FOUND
        assert f"department level {SUB_DIVISION_LEVEL}" in resolved.reason

    def test_the_state_id_is_backfilled_onto_the_node_it_matched(self, db, tmp_path, writers):
        division = seed_dept(db, "Nagaon Division", DIVISION_LEVEL)
        sub = seed_dept(db, "Kathiatoli", SUB_DIVISION_LEVEL, parent_id=division)
        seed_scheme(db, "S1", sub)

        plan = plan_for(db, tmp_path, "SDV-052,Kathiatoli,USR-1,A,9000000001")
        assert sub_plan(plan, "sdv-052").state_dept_id_change == (None, "SDV-052")

        execute_tenant(plan, *writers, create_roles=False)

        with db.conn.cursor() as cur:
            cur.execute(
                f"SELECT state_dept_id FROM {SCHEMA}.department_location_master_table "
                f"WHERE id = %s", (sub,)
            )
            assert cur.fetchone()[0] == "SDV-052"


# ─────────────────────────────────────────────────────────────────────────────
# Mapping
# ─────────────────────────────────────────────────────────────────────────────

class TestMapping:
    def test_one_officer_across_two_sub_divisions_gets_the_union(self, db, tmp_path):
        """Eight people in the real file are listed twice. They are one person,
        and they cover both sub-divisions."""
        division = seed_dept(db, "Golaghat Division", DIVISION_LEVEL)
        first = seed_dept(db, "Golaghat", SUB_DIVISION_LEVEL, parent_id=division)
        second = seed_dept(db, "Sarupathar", SUB_DIVISION_LEVEL, parent_id=division)
        s1 = seed_scheme(db, "S1", first)
        s2 = seed_scheme(db, "S2", second)

        plan = plan_for(
            db, tmp_path,
            "SDV-048,Golaghat,USR-022689,Kasturi Saikia,9000000001",
            "SDV-049,Sarupathar,USR-022689,Kasturi Saikia,9000000001",
        )

        assert len(plan.officers) == 1
        assert officer(plan).target_scheme_ids == {s1, s2}
        assert officer(plan).csv_rows == [3, 4]

    def test_two_officers_on_one_sub_division_are_both_mapped(self, db, tmp_path):
        """23 sub-divisions in the real file name more than one SDO.
        user_scheme_mapping is many-to-many, so that is a fact, not a conflict
        to resolve — but it is still reported."""
        division = seed_dept(db, "Karbi Anglong Division", DIVISION_LEVEL)
        sub = seed_dept(db, "Diphu Rural", SUB_DIVISION_LEVEL, parent_id=division)
        scheme = seed_scheme(db, "S1", sub)

        plan = plan_for(
            db, tmp_path,
            "SDV-075,Diphu Rural,USR-1,First Officer,9000000001",
            "SDV-075,Diphu Rural,USR-2,Second Officer,9000000002",
        )

        assert len(plan.officers) == 2
        assert all(p.target_scheme_ids == {scheme} for p in plan.officers)

    def test_existing_mappings_are_kept_by_default(self, db, tmp_path, roles):
        division = seed_dept(db, "Nagaon Division", DIVISION_LEVEL)
        sub = seed_dept(db, "Kathiatoli", SUB_DIVISION_LEVEL, parent_id=division)
        wanted = seed_scheme(db, "S1", sub)
        elsewhere = seed_scheme(db, "S2", seed_dept(db, "Other", SUB_DIVISION_LEVEL))
        user = seed_user(db, "A", "919000000001", roles[SDO_ROLE])
        seed_mapping(db, user, elsewhere)

        plan = plan_for(db, tmp_path, "SDV-052,Kathiatoli,USR-1,A,9000000001")

        assert officer(plan).to_insert == {wanted}
        assert officer(plan).to_remove == {elsewhere}
        assert plan.remove_pairs == []          # additive: nothing is removed

    def test_replace_makes_the_csv_authoritative(self, db, tmp_path, roles, writers):
        division = seed_dept(db, "Nagaon Division", DIVISION_LEVEL)
        sub = seed_dept(db, "Kathiatoli", SUB_DIVISION_LEVEL, parent_id=division)
        wanted = seed_scheme(db, "S1", sub)
        elsewhere = seed_scheme(db, "S2", seed_dept(db, "Other", SUB_DIVISION_LEVEL))
        user = seed_user(db, "A", "919000000001", roles[SDO_ROLE])
        seed_mapping(db, user, elsewhere)

        plan = plan_for(db, tmp_path, "SDV-052,Kathiatoli,USR-1,A,9000000001", replace=True)
        stats = execute_tenant(plan, *writers, create_roles=False)

        assert stats["scheme_mappings_inserted"] == 1
        assert stats["scheme_mappings_soft_deleted"] == 1
        assert db.load_user_scheme_mappings([user]) == {user: {wanted}}

    def test_schemes_below_the_sub_division_are_included(self, db, tmp_path):
        """The scheme ingest attaches schemes at sub-division level, but nothing
        in the schema forbids a tenant nesting further."""
        division = seed_dept(db, "Nagaon Division", DIVISION_LEVEL)
        sub = seed_dept(db, "Kathiatoli", SUB_DIVISION_LEVEL, parent_id=division)
        below = seed_dept(db, "Kathiatoli Section", BELOW_SUB_DIVISION_LEVEL, parent_id=sub)
        s1 = seed_scheme(db, "S1", sub)
        s2 = seed_scheme(db, "S2", below)

        plan = plan_for(db, tmp_path, "SDV-052,Kathiatoli,USR-1,A,9000000001")

        assert sub_plan(plan, "sdv-052").scheme_ids == {s1, s2}


# ─────────────────────────────────────────────────────────────────────────────
# Officers
# ─────────────────────────────────────────────────────────────────────────────

class TestOfficers:
    def test_a_new_officer_is_onboarded_with_the_sdo_email_prefix(
        self, db, tmp_path, roles, writers
    ):
        division = seed_dept(db, "Nagaon Division", DIVISION_LEVEL)
        sub = seed_dept(db, "Kathiatoli", SUB_DIVISION_LEVEL, parent_id=division)
        scheme = seed_scheme(db, "S1", sub)

        plan = plan_for(db, tmp_path, "SDV-052,Kathiatoli,USR-1,Pritam Singh,9000000001")
        assert officer(plan).decision.category == CAT_NEW

        stats = execute_tenant(plan, *writers, create_roles=False)

        assert stats["users_inserted"] == 1
        assert stats["user_types_created"] == 0    # the role already existed
        assert stats["scheme_mappings_inserted"] == 1
        user_id = officer(plan).decision.existing_id
        with db.conn.cursor() as cur:
            cur.execute(
                f"SELECT email, user_type, state_user_id FROM {SCHEMA}.user_table "
                f"WHERE id = %s", (user_id,)
            )
            email, user_type, state_user_id = cur.fetchone()
        assert email.startswith("sdo_919000000001")
        assert user_type == roles[SDO_ROLE]
        assert state_user_id == "USR-1"
        assert db.load_user_scheme_mappings([user_id]) == {user_id: {scheme}}

    def test_an_existing_section_officer_is_promoted(self, db, tmp_path, roles, writers):
        division = seed_dept(db, "Nagaon Division", DIVISION_LEVEL)
        sub = seed_dept(db, "Kathiatoli", SUB_DIVISION_LEVEL, parent_id=division)
        seed_scheme(db, "S1", sub)
        user = seed_user(db, "Old Name", "919000000001", roles["SECTION_OFFICER"])

        plan = plan_for(db, tmp_path, "SDV-052,Kathiatoli,USR-1,New Name,9000000001")

        decision = officer(plan).decision
        assert decision.category == CAT_EXISTING
        assert decision.existing_id == user
        assert decision.changes["role"] == ("SECTION_OFFICER", SDO_ROLE)
        assert decision.changes["name"] == ("Old Name", "New Name")

        execute_tenant(plan, *writers, create_roles=False)

        with db.conn.cursor() as cur:
            cur.execute(f"SELECT user_type FROM {SCHEMA}.user_table WHERE id = %s", (user,))
            assert cur.fetchone()[0] == roles[SDO_ROLE]

    def test_an_administrator_is_never_demoted_to_sdo(self, db, tmp_path, roles):
        division = seed_dept(db, "Nagaon Division", DIVISION_LEVEL)
        sub = seed_dept(db, "Kathiatoli", SUB_DIVISION_LEVEL, parent_id=division)
        seed_scheme(db, "S1", sub)
        seed_user(db, "Admin", "919000000001", roles["STATE_ADMIN"])

        plan = plan_for(db, tmp_path, "SDV-052,Kathiatoli,USR-1,Admin,9000000001")

        decision = officer(plan).decision
        assert "role" not in decision.changes
        assert "STATE_ADMIN" in decision.withheld["role"]

    def test_no_role_updates_withholds_the_promotion(self, db, tmp_path, roles):
        division = seed_dept(db, "Nagaon Division", DIVISION_LEVEL)
        sub = seed_dept(db, "Kathiatoli", SUB_DIVISION_LEVEL, parent_id=division)
        seed_scheme(db, "S1", sub)
        seed_user(db, "A", "919000000001", roles["SECTION_OFFICER"])

        plan = plan_for(db, tmp_path, "SDV-052,Kathiatoli,USR-1,A,9000000001",
                        update_roles=False)

        assert "role" not in officer(plan).decision.changes
        assert "--no-role-updates" in officer(plan).decision.withheld["role"]

    def test_an_officer_with_no_resolvable_sub_division_is_not_created(
        self, db, tmp_path, writers
    ):
        seed_dept(db, "Nagaon Division", DIVISION_LEVEL)

        plan = plan_for(db, tmp_path, "SDV-999,Nowhere,USR-1,A,9000000001")
        stats = execute_tenant(plan, *writers, create_roles=False)

        assert stats["users_inserted"] == 0
        assert stats["scheme_mappings_inserted"] == 0

    def test_create_users_without_schemes_onboards_them_anyway(self, db, tmp_path, writers):
        seed_dept(db, "Nagaon Division", DIVISION_LEVEL)

        plan = plan_for(db, tmp_path, "SDV-999,Nowhere,USR-1,A,9000000001",
                        create_users_without_schemes=True)
        stats = execute_tenant(plan, *writers, create_roles=False)

        assert stats["users_inserted"] == 1
        assert stats["scheme_mappings_inserted"] == 0

    def test_the_role_gate_passes_against_a_seeded_database(self, db, tmp_path):
        division = seed_dept(db, "Nagaon Division", DIVISION_LEVEL)
        sub = seed_dept(db, "Kathiatoli", SUB_DIVISION_LEVEL, parent_id=division)
        seed_scheme(db, "S1", sub)

        plan = plan_for(db, tmp_path, "SDV-052,Kathiatoli,USR-1,A,9000000001")

        assert [p.role for p in plan.role_plans] == [SDO_ROLE]
        assert unusable_roles(plan) == []


# ─────────────────────────────────────────────────────────────────────────────
# Before the migrations land
# ─────────────────────────────────────────────────────────────────────────────

class TestWithoutTheMigrations:
    def test_the_whole_mapping_works_without_v36_or_v37(self, legacy_db, tmp_path, conn_ready):
        """The mapping itself needs neither external id column, so it can be
        ingested before either migration is applied."""
        division = seed_dept(legacy_db, "Nagaon Division", DIVISION_LEVEL)
        sub = seed_dept(legacy_db, "Kathiatoli", SUB_DIVISION_LEVEL, parent_id=division)
        scheme = seed_scheme(legacy_db, "S1", sub)

        plan = plan_for(legacy_db, tmp_path, "SDV-052,Kathiatoli,USR-1,A,9000000001")
        assert sub_plan(plan, "sdv-052").node_id == sub
        assert sub_plan(plan, "sdv-052").state_dept_id_change is None

        stats = execute_tenant(
            plan,
            UserWriter(legacy_db, TENANT_ID, ACTOR_ID),
            MappingWriter(legacy_db, ACTOR_ID),
            create_roles=False,
        )

        assert stats["users_inserted"] == 1
        assert stats["state_dept_ids_backfilled"] == 0
        user_id = officer(plan).decision.existing_id
        assert legacy_db.load_user_scheme_mappings([user_id]) == {user_id: {scheme}}

    def test_the_missing_column_is_reported_before_anything_is_read(self, legacy_db):
        legacy_db.with_state_dept_id = True

        with pytest.raises(SystemExit) as exc:
            legacy_db.assert_state_dept_id_column()
        assert "V37" in str(exc.value)


# ─────────────────────────────────────────────────────────────────────────────
# The analysis workbook
# ─────────────────────────────────────────────────────────────────────────────

class TestAnalysisWorkbook:
    def test_every_sheet_is_written_and_says_sub_division(self, db, tmp_path, roles):
        """The workbook is what an operator reads before authorising a
        production write, so it has to survive every outcome the plan can hold —
        a clean match, an ambiguity, an unresolvable name and a skipped person —
        and it has to describe them in the file's own vocabulary."""
        nagaon = seed_dept(db, "Nagaon Division", DIVISION_LEVEL)
        tezpur = seed_dept(db, "Tezpur Division", DIVISION_LEVEL)
        sonitpur = seed_dept(db, "Sonitpur Division", DIVISION_LEVEL)
        matched = seed_dept(db, "Kathiatoli", SUB_DIVISION_LEVEL, parent_id=nagaon)
        seed_scheme(db, "S1", matched)
        seed_dept(db, "Tezpur", SUB_DIVISION_LEVEL, parent_id=tezpur)
        seed_dept(db, "Tezpur", SUB_DIVISION_LEVEL, parent_id=sonitpur)
        seed_user(db, "Existing", "919000000001", roles["SECTION_OFFICER"])

        plan = plan_for(
            db, tmp_path,
            "SDV-052,Kathiatoli,USR-1,Existing,9000000001",   # matched, promoted
            "SDV-020,Tezpur,USR-2,Ambiguous Person,9000000002",
            "SDV-999,Nowhere,USR-3,Lost Person,9000000003",
            "SDV-052,Kathiatoli,USR-4,Bad Phone,1234567890",  # unusable row
        )
        out = tmp_path / "analysis.xlsx"
        write_analysis_workbook(plan, str(out), include_pii=False, context={"mode": "TEST"})

        book = pd.read_excel(out, sheet_name=None)
        assert set(book) == {
            "run_info", "summary", "subdivision_detail", "mapping_detail", "sdo_detail",
            "role_summary", "analytics_summary", "conflicts", "csv_issues",
        }

        summary = dict(zip(book["summary"]["metric"], book["summary"]["value"]))
        assert summary["distinct sub-divisions named"] == 3
        assert summary["  sub-divisions resolved"] == 1
        assert summary["  sub-divisions ambiguous"] == 1
        assert summary["  sub-divisions not found"] == 1
        assert summary["  SDOs skipped"] == 3          # ambiguous, lost, bad phone

        # The ambiguity has to arrive with the ancestry that resolves it.
        conflicts = book["conflicts"]
        ambiguous = conflicts[conflicts["kind"] == "SUBDIVISION_AMBIGUOUS"]
        assert len(ambiguous) == 1
        assert "Tezpur < Tezpur Division" in ambiguous.iloc[0]["detail"]
        assert "Tezpur < Sonitpur Division" in ambiguous.iloc[0]["detail"]

        assert "subdivision_public_id" in book["subdivision_detail"].columns
        assert "schemes_in_subdivisions" in book["mapping_detail"].columns
        assert book["role_summary"].iloc[0]["canonical_role"] == SDO_ROLE

    def test_phone_numbers_are_masked_unless_asked_for(self, db, tmp_path):
        seed_dept(db, "Nagaon Division", DIVISION_LEVEL)

        plan = plan_for(db, tmp_path, "SDV-999,Nowhere,USR-1,A,9000000001")
        masked = tmp_path / "masked.xlsx"
        write_analysis_workbook(plan, str(masked), include_pii=False, context={})

        text = pd.read_excel(masked, sheet_name="sdo_detail").to_string()
        assert "9000000001" not in text
