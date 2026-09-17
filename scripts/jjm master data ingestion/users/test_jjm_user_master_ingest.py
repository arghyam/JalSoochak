"""Tests for jjm_user_master_ingest.

Two layers:

  * pure logic — CSV parsing, role canonicalisation, the matching contract and
    the field-level diff. These run anywhere.
  * write paths — insert_users / update_users / create_roles against a real
    PostgreSQL, because both build SQL dynamically (a bulk INSERT template and
    a grouped UPDATE ... FROM (VALUES ...)) and a mock cannot show that the
    generated statement executes, types its columns correctly, or lands the
    right values on the right rows.

  export JJM_TEST_DSN='postgresql://postgres:testpw@localhost:55432/shared_db'
  python3 -m pytest "scripts/jjm master data ingestion/users/test_jjm_user_master_ingest.py" -v
"""

from __future__ import annotations

import base64
import os
import sys

import psycopg2
import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from jjm_user_master_ingest import (  # noqa: E402
    CAT_DUPLICATE,
    CAT_EXISTING,
    CAT_INVALID,
    CAT_NEW,
    CAT_ROLE_NOT_INGESTED,
    FIELD_NAME,
    FIELD_ROLE,
    FIELD_STATE_USER_ID,
    PiiCrypto,
    RolePlan,
    UserDb,
    UserDecision,
    UserRow,
    EMAIL_PREFIXES,
    INGESTED_ROLES,
    UserWriter,
    build_role_plans,
    canonical_role,
    classify_users,
    email_prefix,
    execute_tenant,
    find_csv_duplicates,
    load_csv,
    safe_mask,
)
from jjm_user_master_ingest import IngestPlan  # noqa: E402

DSN = os.environ.get("JJM_TEST_DSN", "postgresql://postgres:testpw@localhost:55432/shared_db")
SCHEMA = "tenant_usertest"
LEGACY_SCHEMA = "tenant_usertest_prev36"
# The one in-scope role a stock deployment does not hold. The db fixture deletes
# it so "a role we do not have yet" is true whatever the target database contains.
NEW_ROLE = "EXECUTIVE_ENGINEER"
TENANT_ID = 1
ACTOR_ID = 1

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
    state_user_id             VARCHAR(255),
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
CREATE UNIQUE INDEX uq_{schema}_user_state_user_id
    ON {schema}.user_table(state_user_id)
    WHERE state_user_id IS NOT NULL AND deleted_at IS NULL;
"""

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

CSV_HEADER = "users(17),,,\npublic_id,name,phone,role\n"


def _pii() -> PiiCrypto:
    return PiiCrypto(
        base64.b64encode(b"\x01" * 32).decode(),
        base64.b64encode(b"\x02" * 32).decode(),
    )


def _row(row_no: int, public_id: str, name: str, phone: str, role: str) -> UserRow:
    return UserRow(
        row_no=row_no, public_id=public_id, name=name, phone_raw=phone,
        phone=phone, role_raw=role.lower().replace("_", "-"), role=role,
    )


# ─────────────────────────────────────────────────────────────────────────────
# CSV parsing
# ─────────────────────────────────────────────────────────────────────────────

class TestLoadCsv:
    def test_reads_the_state_export_layout(self, tmp_path):
        """The export puts a title line above the header, and row numbers in the
        report have to line up with what the operator sees in the file."""
        path = tmp_path / "users.csv"
        path.write_text(
            CSV_HEADER
            + "USR-015755,Debojit Patir,8638029838,sdo\n"
            + "USR-015758,Ashif Ahmed,7002921268,section-officer\n",
            encoding="utf-8",
        )

        rows, issues = load_csv(str(path), header_row=2, encoding="utf-8")

        assert [r.row_no for r in rows] == [3, 4]
        assert rows[0].public_id == "USR-015755"
        assert rows[0].phone == "918638029838"
        assert rows[0].role == "SUB_DIVISIONAL_OFFICER"
        assert rows[1].role == "SECTION_OFFICER"
        assert issues == []

    def test_flags_unusable_rows_without_echoing_the_number(self, tmp_path):
        """An invalid phone is reported, but the number itself is PII and must
        not be written into the issue text."""
        path = tmp_path / "users.csv"
        path.write_text(
            CSV_HEADER
            + "USR-1,xyz,1234567890,section-officer\n"
            + "USR-2,,9000000001,jal-mitra\n"
            + ",Named Person,9000000002,jal-mitra\n",
            encoding="utf-8",
        )

        rows, issues = load_csv(str(path), header_row=2, encoding="utf-8")

        assert rows[0].phone == ""
        assert rows[0].blocking_issues == ["row:phone is not a valid Indian mobile number"]
        assert rows[1].blocking_issues == ["row:blank name"]
        # A missing public_id is reported but does not block the user.
        assert rows[2].blocking_issues == []
        assert any(i["issue_kind"] == "state_user_id" for i in issues)
        assert not any("1234567890" in i["issue"] for i in issues)

    def test_keeps_leading_zeros_and_trims_names(self, tmp_path):
        path = tmp_path / "users.csv"
        path.write_text(
            CSV_HEADER + "USR-3, NISHI SARKAR ,09000000003,jal-mitra\n", encoding="utf-8"
        )

        rows, _ = load_csv(str(path), header_row=2, encoding="utf-8")

        assert rows[0].name == "NISHI SARKAR"
        assert rows[0].phone == "919000000003"


class TestCanonicalRole:
    @pytest.mark.parametrize("raw,expected", [
        ("jal-mitra", "PUMP_OPERATOR"),
        ("Jal Mitra", "PUMP_OPERATOR"),
        ("section-officer", "SECTION_OFFICER"),
        ("sdo", "SUB_DIVISIONAL_OFFICER"),
        ("executive-engineer", "EXECUTIVE_ENGINEER"),
        # Everything below is off the allow-list: no canonical name, so the
        # row is skipped rather than onboarded under an invented role.
        ("jal-sahayak", ""),
        ("khalasi", ""),
        ("block-coordinator", ""),
        ("", ""),
        ("!!!", ""),
    ])
    def test_maps_csv_slugs_to_c_names(self, raw, expected):
        assert canonical_role(raw) == expected

    def test_email_prefix_per_role(self):
        assert email_prefix("PUMP_OPERATOR") == "po_"
        assert email_prefix("SUB_DIVISIONAL_OFFICER") == "sdo_"
        assert email_prefix("EXECUTIVE_ENGINEER") == "ee_"
        # Defensive only — every allow-listed role has a prefix of its own.
        assert email_prefix("BLOCK_COORDINATOR") == "usr_"

    def test_every_allow_listed_role_has_an_email_prefix(self):
        """A role without one would mint addresses under the fallback prefix."""
        assert INGESTED_ROLES <= set(EMAIL_PREFIXES)


class TestSafeMask:
    def test_never_reveals_a_short_value(self):
        assert safe_mask("919000000001") == "91XXXXXXXX01"
        assert safe_mask("1234") == "XXXX"
        assert safe_mask("") == ""


class TestRolesOutOfScope:
    """Only jal-mitra, section-officer, sdo and executive-engineer are ingested."""

    def test_load_csv_marks_them_without_calling_the_row_broken(self, tmp_path):
        path = tmp_path / "users.csv"
        path.write_text(
            CSV_HEADER
            + "USR-1,Sahayak Person,9000000001,jal-sahayak\n"
            + "USR-2,Khalasi Person,9000000002,khalasi\n"
            + "USR-3,Mitra Person,9000000003,jal-mitra\n",
            encoding="utf-8",
        )

        rows, issues = load_csv(str(path), header_row=2, encoding="utf-8")

        assert [r.role_not_ingested for r in rows] == [True, True, False]
        # Not a blocking issue: the row is legible, just out of scope.
        assert all(r.blocking_issues == [] for r in rows)
        role_issues = [i for i in issues if i["issue_kind"] == "role"]
        assert len(role_issues) == 2
        assert "jal-sahayak" in role_issues[0]["issue"]
        assert rows[2].role == "PUMP_OPERATOR"

    def test_they_are_categorised_as_skipped_and_never_written(self, db):
        rows = [
            UserRow(row_no=3, public_id="USR-1", name="Sahayak Person",
                    phone_raw="919000000001", phone="919000000001",
                    role_raw="jal-sahayak", role=""),
            _row(4, "USR-2", "Mitra Person", "919000000002", "PUMP_OPERATOR"),
        ]

        decisions = classify_users(rows, db)

        assert decisions[0].category == CAT_ROLE_NOT_INGESTED
        assert decisions[0].will_write is False
        assert "jal-sahayak" in decisions[0].reason
        assert decisions[1].category == CAT_NEW

    def test_an_out_of_scope_row_does_not_knock_out_its_phone_twin(self, db):
        """A khalasi row sharing a jal-mitra's number must not skip the jal-mitra:
        a role we are not ingesting has no say over one we are."""
        rows = [
            UserRow(row_no=3, public_id="USR-1", name="Same Person",
                    phone_raw="919000000001", phone="919000000001",
                    role_raw="khalasi", role=""),
            _row(4, "USR-2", "Same Person", "919000000001", "PUMP_OPERATOR"),
        ]

        decisions = classify_users(rows, db)

        assert decisions[0].category == CAT_ROLE_NOT_INGESTED
        assert decisions[1].category == CAT_NEW

    def test_two_in_scope_rows_on_one_phone_still_skip_each_other(self, db):
        """The duplicate rule is unchanged for rows that are both candidates."""
        rows = [
            _row(3, "USR-1", "A", "919000000001", "SECTION_OFFICER"),
            _row(4, "USR-2", "B", "919000000001", "SUB_DIVISIONAL_OFFICER"),
        ]

        decisions = classify_users(rows, db)

        assert [d.category for d in decisions] == [CAT_DUPLICATE, CAT_DUPLICATE]


class TestFindCsvDuplicates:
    def test_reports_repeats_of_both_keys(self):
        rows = [
            _row(3, "USR-1", "A", "919000000001", "PUMP_OPERATOR"),
            _row(4, "USR-2", "B", "919000000001", "SECTION_OFFICER"),
            _row(5, "USR-3", "C", "919000000003", "PUMP_OPERATOR"),
            _row(6, "USR-3", "D", "919000000004", "PUMP_OPERATOR"),
        ]

        by_phone, by_public_id = find_csv_duplicates(rows)

        assert by_phone == {"919000000001": [3, 4]}
        assert by_public_id == {"usr-3": [5, 6]}


class TestBuildRolePlans:
    def test_splits_roles_into_existing_create_blocked_and_skipped(self):
        from jjm_user_master_ingest import UserTypeRow

        khalasi = UserRow(row_no=6, public_id="USR-4", name="D", phone_raw="919000000004",
                          phone="919000000004", role_raw="khalasi", role="")
        decisions = [
            UserDecision(_row(3, "USR-1", "A", "919000000001", "PUMP_OPERATOR"), CAT_NEW),
            UserDecision(_row(4, "USR-2", "B", "919000000002", NEW_ROLE), CAT_NEW),
            UserDecision(_row(5, "USR-3", "C", "919000000003", "SECTION_OFFICER"), CAT_EXISTING),
            UserDecision(khalasi, CAT_ROLE_NOT_INGESTED),
            # Skipped rows must not drag a role into existence.
            UserDecision(_row(7, "USR-5", "E", "919000000005", "SUB_DIVISIONAL_OFFICER"),
                         CAT_INVALID),
        ]
        user_types = {
            "PUMP_OPERATOR": UserTypeRow(4, "PUMP_OPERATOR", False),
            "SECTION_OFFICER": UserTypeRow(9, "SECTION_OFFICER", True),
        }

        plans = {p.role: p for p in build_role_plans(decisions, user_types)}

        assert plans["PUMP_OPERATOR"].action == "existing"
        assert plans[NEW_ROLE].action == "create"
        assert plans["SECTION_OFFICER"].action == "blocked_soft_deleted"
        # Out-of-scope roles are reported by their CSV slug, with a row count,
        # so the operator can see what the run left out.
        assert plans["khalasi"].action == "not_ingested"
        assert plans["khalasi"].csv_rows == 1
        assert "SUB_DIVISIONAL_OFFICER" not in plans


# ─────────────────────────────────────────────────────────────────────────────
# Database-backed tests
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
def db(conn):
    """A throwaway schema inside a transaction that is never committed.

    Nothing here commits, so the rollback at teardown removes the schema, the
    users and any role row a test created — PostgreSQL rolls DDL back like any
    other statement. That is deliberate: pointing JJM_TEST_DSN at a database
    that matters must not be able to destroy anything in it.
    """
    conn.rollback()  # clear a transaction a previous failure may have aborted
    with conn.cursor() as cur:
        cur.execute("CREATE SCHEMA IF NOT EXISTS common_schema")
        cur.execute(COMMON_DDL)
        # Additive: a real database already holds these, and their ids are read
        # back below rather than assumed.
        cur.execute(
            "INSERT INTO common_schema.user_type_master_table (c_name) "
            "VALUES ('SUPER_USER'), ('STATE_ADMIN'), ('SECTION_OFFICER'), "
            "('PUMP_OPERATOR'), ('SUB_DIVISIONAL_OFFICER') "
            "ON CONFLICT (c_name) DO NOTHING"
        )
        # Rolled back with everything else; guarantees a clean slate even if a
        # previous interrupted run left one behind.
        cur.execute(
            "DELETE FROM common_schema.user_type_master_table WHERE c_name = %s",
            (NEW_ROLE,),
        )
        cur.execute(f"DROP SCHEMA IF EXISTS {SCHEMA} CASCADE")
        cur.execute(f"CREATE SCHEMA {SCHEMA}")
        cur.execute(DDL.format(schema=SCHEMA))

    yield UserDb(conn, SCHEMA, _pii(), with_state_user_id=True)
    conn.rollback()


@pytest.fixture
def roles(db) -> dict[str, int]:
    """Role name -> id as this database actually assigned them."""
    return {name: row.id for name, row in db.load_user_types().items()}


@pytest.fixture
def writer(db):
    return UserWriter(db, TENANT_ID, ACTOR_ID)


def seed_user(db: UserDb, name: str, phone: str, user_type: int,
              state_user_id=None, email=None) -> int:
    import uuid as uuid_mod
    with db.conn.cursor() as cur:
        cur.execute(f"""
            INSERT INTO {SCHEMA}.user_table
                (uuid, tenant_id, title, title_hash, email, user_type,
                 phone_number, phone_number_hash, state_user_id, password, status)
            VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,'x',1)
            RETURNING id
        """, (
            str(uuid_mod.uuid4()), TENANT_ID,
            db.pii.encrypt(name), db.pii.title_hash(name),
            email or f"seed_{phone}@pump-operator.local", user_type,
            db.pii.encrypt(phone), db.pii.hmac(phone), state_user_id,
        ))
        return cur.fetchone()[0]


def dump(db: UserDb, user_id: int) -> tuple:
    with db.conn.cursor() as cur:
        cur.execute(
            f"SELECT title, title_hash, user_type, state_user_id, updated_by "
            f"FROM {SCHEMA}.user_table WHERE id = %s", (user_id,)
        )
        title, title_hash, user_type, state_user_id, updated_by = cur.fetchone()
        return db.pii.safe_decrypt(title), title_hash, user_type, state_user_id, updated_by


def plan_of(decisions, db) -> IngestPlan:
    user_types = db.load_user_types()
    return IngestPlan(
        decisions=decisions,
        role_plans=build_role_plans(decisions, user_types),
        user_types=user_types,
        csv_issues=[],
        dup_phone={},
        dup_public_id={},
        with_state_user_id=db.with_state_user_id,
    )


class TestClassifyUsers:
    def test_matches_on_phone_and_diffs_every_field(self, db, roles):
        """Phone is the identity; name, role and state_user_id are the payload."""
        existing = seed_user(db, "Old Name", "919000000001", roles["PUMP_OPERATOR"])
        rows = [
            _row(3, "USR-1", "New Name", "919000000001", "SECTION_OFFICER"),
            _row(4, "USR-2", "Fresh Person", "919000000002", "PUMP_OPERATOR"),
        ]

        matched, inserted = classify_users(rows, db)

        assert matched.category == CAT_EXISTING
        assert matched.existing_id == existing
        assert matched.changes[FIELD_NAME] == ("Old Name", "New Name")
        assert matched.changes[FIELD_ROLE] == ("PUMP_OPERATOR", "SECTION_OFFICER")
        assert matched.changes[FIELD_STATE_USER_ID] == (None, "USR-1")
        assert inserted.category == CAT_NEW
        assert inserted.changes == {}

    def test_name_diff_ignores_case_and_punctuation(self, db, roles):
        """'PRATIJNA MOHAN BORA' vs 'PRATIJNA Mohan Bora' is not a change —
        re-encrypting on every run would churn the ciphertext for nothing."""
        seed_user(db, "PRATIJNA Mohan Bora", "919000000001", roles["PUMP_OPERATOR"])

        decision = classify_users(
            [_row(3, "", "PRATIJNA MOHAN BORA", "919000000001", "PUMP_OPERATOR")], db
        )[0]

        assert decision.category == CAT_EXISTING
        assert decision.changes == {}

    def test_administrative_roles_are_never_demoted(self, db, roles):
        seed_user(db, "Admin Person", "919000000001", roles["STATE_ADMIN"])

        decision = classify_users(
            [_row(3, "USR-1", "Admin Person", "919000000001", "PUMP_OPERATOR")], db
        )[0]

        assert FIELD_ROLE not in decision.changes
        assert "STATE_ADMIN" in decision.withheld[FIELD_ROLE]
        # The rest of the row still applies.
        assert decision.changes[FIELD_STATE_USER_ID] == (None, "USR-1")

    def test_no_role_updates_withholds_every_role_change(self, db, roles):
        seed_user(db, "Field Person", "919000000001", roles["PUMP_OPERATOR"])

        decision = classify_users(
            [_row(3, "USR-1", "Field Person", "919000000001", "SECTION_OFFICER")],
            db, update_roles=False,
        )[0]

        assert FIELD_ROLE not in decision.changes
        assert FIELD_ROLE in decision.withheld

    def test_state_user_id_owned_by_someone_else_is_withheld(self, db, roles):
        """The tenant's partial UNIQUE index would reject a second owner, so the
        collision is reported instead of aborting the run."""
        owner = seed_user(db, "First Owner", "919000000001", roles["PUMP_OPERATOR"],
                          state_user_id="USR-1")
        seed_user(db, "Other Person", "919000000002", roles["PUMP_OPERATOR"])

        decisions = classify_users(
            [_row(3, "USR-1", "Other Person", "919000000002", "PUMP_OPERATOR")], db
        )

        assert FIELD_STATE_USER_ID not in decisions[0].changes
        assert f"user id {owner}" in decisions[0].withheld[FIELD_STATE_USER_ID]

    def test_an_owner_holding_the_code_in_another_case_still_counts(self, db, roles):
        """The owner lookup is keyed on lower(code); a code we already hold in
        another case must not read as free."""
        owner = seed_user(db, "First Owner", "919000000001", roles["PUMP_OPERATOR"],
                          state_user_id="usr-1")
        seed_user(db, "Other Person", "919000000002", roles["PUMP_OPERATOR"])

        decisions = classify_users(
            [_row(3, "USR-1", "Other Person", "919000000002", "PUMP_OPERATOR")], db
        )

        assert FIELD_STATE_USER_ID not in decisions[0].changes
        assert f"user id {owner}" in decisions[0].withheld[FIELD_STATE_USER_ID]

    def test_duplicate_and_invalid_rows_never_reach_the_database(self, db):
        rows = [
            _row(3, "USR-1", "A", "919000000001", "PUMP_OPERATOR"),
            _row(4, "USR-2", "B", "919000000001", "SECTION_OFFICER"),
            UserRow(row_no=5, public_id="USR-3", name="", phone_raw="9000000003",
                    phone="919000000003", role_raw="jal-mitra", role="PUMP_OPERATOR",
                    issues=["row:blank name"]),
        ]

        decisions = classify_users(rows, db)

        assert [d.category for d in decisions] == [CAT_DUPLICATE, CAT_DUPLICATE, CAT_INVALID]
        assert all(not d.will_write for d in decisions)


class TestOfficerPromotionGate:
    """Promotions into GATED_TARGET_ROLES need --allow-officer-promotions."""

    def test_promotion_to_executive_engineer_is_withheld_by_default(self, db, roles):
        """A sheet must not widen someone's access on its own say-so."""
        seed_user(db, "Officer Person", "919000000001", roles["SECTION_OFFICER"])

        decision = classify_users(
            [_row(3, "USR-1", "Officer Person", "919000000001", "EXECUTIVE_ENGINEER")], db
        )[0]

        assert FIELD_ROLE not in decision.changes
        assert decision.gated_promotion is True
        assert "--allow-officer-promotions" in decision.withheld[FIELD_ROLE]
        # Everything else about the row still applies.
        assert decision.changes[FIELD_STATE_USER_ID] == (None, "USR-1")

    @pytest.mark.parametrize("held", [
        "SECTION_OFFICER", "SUB_DIVISIONAL_OFFICER", "PUMP_OPERATOR",
    ])
    def test_the_gate_is_on_the_target_role_not_the_starting_point(self, db, roles, held):
        seed_user(db, "Person", "919000000001", roles[held])

        decision = classify_users(
            [_row(3, "USR-1", "Person", "919000000001", "EXECUTIVE_ENGINEER")], db
        )[0]

        assert decision.gated_promotion is True
        assert FIELD_ROLE not in decision.changes

    def test_allow_officer_promotions_applies_it(self, db, roles):
        seed_user(db, "Officer Person", "919000000001", roles["SUB_DIVISIONAL_OFFICER"])

        decision = classify_users(
            [_row(3, "USR-1", "Officer Person", "919000000001", "EXECUTIVE_ENGINEER")],
            db, allow_promotions=True,
        )[0]

        assert decision.changes[FIELD_ROLE] == ("SUB_DIVISIONAL_OFFICER", "EXECUTIVE_ENGINEER")
        assert decision.gated_promotion is False
        assert FIELD_ROLE not in decision.withheld

    def test_a_new_executive_engineer_is_onboarded_not_gated(self, db):
        """The gate is about promotions. Onboarding is not a promotion."""
        decision = classify_users(
            [_row(3, "USR-1", "Fresh Engineer", "919000000002", "EXECUTIVE_ENGINEER")], db
        )[0]

        assert decision.category == CAT_NEW
        assert decision.gated_promotion is False
        assert decision.withheld == {}

    def test_an_existing_executive_engineer_is_not_a_promotion(self, db, roles):
        with db.conn.cursor() as cur:
            cur.execute(
                "INSERT INTO common_schema.user_type_master_table (c_name) VALUES (%s) "
                "ON CONFLICT (c_name) DO NOTHING", ("EXECUTIVE_ENGINEER",))
        engineer = {name: row.id for name, row in db.load_user_types().items()}
        seed_user(db, "Engineer Person", "919000000001", engineer["EXECUTIVE_ENGINEER"])

        decision = classify_users(
            [_row(3, "", "Engineer Person", "919000000001", "EXECUTIVE_ENGINEER")], db
        )[0]

        assert decision.changes == {}
        assert decision.gated_promotion is False

    def test_an_ungated_role_change_still_applies_by_default(self, db, roles):
        seed_user(db, "Field Person", "919000000001", roles["PUMP_OPERATOR"])

        decision = classify_users(
            [_row(3, "", "Field Person", "919000000001", "SECTION_OFFICER")], db
        )[0]

        assert decision.changes[FIELD_ROLE] == ("PUMP_OPERATOR", "SECTION_OFFICER")
        assert decision.gated_promotion is False

    def test_no_role_updates_wins_over_allow_promotions(self, db, roles):
        """--no-role-updates means no role changes at all, gated or otherwise."""
        seed_user(db, "Officer Person", "919000000001", roles["SECTION_OFFICER"])

        decision = classify_users(
            [_row(3, "", "Officer Person", "919000000001", "EXECUTIVE_ENGINEER")],
            db, update_roles=False, allow_promotions=True,
        )[0]

        assert FIELD_ROLE not in decision.changes
        assert "--no-role-updates" in decision.withheld[FIELD_ROLE]
        assert decision.gated_promotion is False

    def test_an_admin_is_protected_even_from_a_promotion(self, db, roles):
        """PROTECTED_ROLES is checked first: the message must name the real reason."""
        seed_user(db, "Admin Person", "919000000001", roles["STATE_ADMIN"])

        decision = classify_users(
            [_row(3, "", "Admin Person", "919000000001", "EXECUTIVE_ENGINEER")],
            db, allow_promotions=True,
        )[0]

        assert FIELD_ROLE not in decision.changes
        assert "STATE_ADMIN" in decision.withheld[FIELD_ROLE]


class TestInsertUsers:
    def test_writes_the_full_onboarding_row(self, db, writer, roles):
        decisions = [
            UserDecision(_row(3, "USR-1", "Fresh Person", "919000000001", "PUMP_OPERATOR"), CAT_NEW),
            UserDecision(_row(4, "USR-2", "Officer Person", "919000000002",
                              "SUB_DIVISIONAL_OFFICER"), CAT_NEW),
        ]

        writer.insert_users(decisions, roles)

        assert all(d.existing_id for d in decisions)
        with db.conn.cursor() as cur:
            cur.execute(
                f"SELECT title, phone_number, phone_number_hash, email, user_type, "
                f"state_user_id, password, status, created_by "
                f"FROM {SCHEMA}.user_table ORDER BY id"
            )
            first, second = cur.fetchall()
        assert db.pii.safe_decrypt(first[0]) == "Fresh Person"
        assert db.pii.safe_decrypt(first[1]) == "919000000001"
        assert first[2] == db.pii.hmac("919000000001")
        assert first[3] == "po_919000000001@pump-operator.local"
        assert (first[4], first[5], first[6], first[7], first[8]) == (
            roles["PUMP_OPERATOR"], "USR-1", "CSV_ONBOARDED", 1, ACTOR_ID)
        assert second[3] == "sdo_919000000002@pump-operator.local"

        # And the new rows are findable the way the app finds them.
        found = db.load_users_for_phones([db.pii.hmac("919000000001")])
        assert found[db.pii.hmac("919000000001")]["title"] == "Fresh Person"

    def test_email_collision_falls_back_to_a_unique_address(self, db, writer, roles):
        """A soft-deleted row still occupies the address: the UNIQUE constraint
        on email does not exclude deleted users."""
        taken = seed_user(db, "Ghost", "919000000009", roles["PUMP_OPERATOR"],
                          email="po_919000000001@pump-operator.local")
        with db.conn.cursor() as cur:
            cur.execute(f"UPDATE {SCHEMA}.user_table SET deleted_at = NOW() WHERE id = %s",
                        (taken,))

        decision = UserDecision(
            _row(3, "USR-1", "Fresh Person", "919000000001", "PUMP_OPERATOR"), CAT_NEW)
        writer.insert_users([decision], roles)

        with db.conn.cursor() as cur:
            cur.execute(f"SELECT email FROM {SCHEMA}.user_table WHERE id = %s",
                        (decision.existing_id,))
            email = cur.fetchone()[0]
        assert email.startswith("po_919000000001_")
        assert email.endswith("@pump-operator.local")

    def test_withheld_state_user_id_is_left_null(self, db, writer, roles):
        seed_user(db, "First Owner", "919000000009", roles["PUMP_OPERATOR"],
                  state_user_id="USR-1")
        decision = UserDecision(
            _row(3, "USR-1", "Fresh Person", "919000000001", "PUMP_OPERATOR"), CAT_NEW)
        decision.withheld[FIELD_STATE_USER_ID] = "already owned"

        writer.insert_users([decision], roles)

        assert dump(db, decision.existing_id)[3] is None


class TestUpdateUsers:
    def test_applies_each_rows_own_column_set(self, db, writer, roles):
        """Rows are grouped by which columns they change; the grouping must not
        leak one row's values into another's columns."""
        operator = roles["PUMP_OPERATOR"]
        name_only = seed_user(db, "Old A", "919000000001", operator)
        role_only = seed_user(db, "B", "919000000002", operator)
        everything = seed_user(db, "Old C", "919000000003", operator,
                               state_user_id="USR-OLD")

        decisions = []
        for user_id, phone, changes in (
            (name_only, "919000000001", {FIELD_NAME: ("Old A", "New A")}),
            (role_only, "919000000002", {FIELD_ROLE: ("PUMP_OPERATOR", "SECTION_OFFICER")}),
            (everything, "919000000003", {
                FIELD_NAME: ("Old C", "New C"),
                FIELD_ROLE: ("PUMP_OPERATOR", "SUB_DIVISIONAL_OFFICER"),
                FIELD_STATE_USER_ID: ("USR-OLD", "USR-NEW"),
            }),
        ):
            decision = UserDecision(_row(3, "", "", phone, ""), CAT_EXISTING)
            decision.existing_id = user_id
            decision.changes = changes
            decisions.append(decision)

        updated = writer.update_users(decisions, roles)

        assert updated == 3
        assert dump(db, name_only) == (
            "New A", db.pii.title_hash("New A"), operator, None, ACTOR_ID)
        assert dump(db, role_only) == (
            "B", db.pii.title_hash("B"), roles["SECTION_OFFICER"], None, ACTOR_ID)
        assert dump(db, everything) == (
            "New C", db.pii.title_hash("New C"), roles["SUB_DIVISIONAL_OFFICER"],
            "USR-NEW", ACTOR_ID)

    def test_a_decision_with_no_changes_writes_nothing(self, db, writer, roles):
        user_id = seed_user(db, "Unchanged", "919000000001", roles["PUMP_OPERATOR"])
        before = dump(db, user_id)

        decision = UserDecision(_row(3, "", "", "919000000001", ""), CAT_EXISTING)
        decision.existing_id = user_id

        assert writer.update_users([decision], {}) == 0
        assert dump(db, user_id) == before

    def test_rejects_a_column_it_has_no_type_for(self, db, writer):
        """The dynamic SQL is only safe while every column is on the allow-list."""
        decision = UserDecision(_row(3, "", "", "919000000001", ""), CAT_EXISTING)
        decision.existing_id = 1
        decision.db_updates = lambda pii, ids: {"password": "hunter2"}  # type: ignore[assignment]

        with pytest.raises(ValueError, match="password"):
            writer.update_users([decision], {})

    def test_soft_deleted_rows_are_not_updated(self, db, writer, roles):
        user_id = seed_user(db, "Gone", "919000000001", roles["PUMP_OPERATOR"])
        with db.conn.cursor() as cur:
            cur.execute(f"UPDATE {SCHEMA}.user_table SET deleted_at = NOW() WHERE id = %s",
                        (user_id,))

        decision = UserDecision(_row(3, "", "", "919000000001", ""), CAT_EXISTING)
        decision.existing_id = user_id
        decision.changes = {FIELD_NAME: ("Gone", "Back")}

        assert writer.update_users([decision], {}) == 0


class TestWithoutStateUserId:
    """The pre-V36 path: the column does not exist, and no statement may name it."""

    @pytest.fixture
    def legacy_db(self, conn):
        """A tenant whose user_table predates V36 — no state_user_id at all."""
        conn.rollback()
        with conn.cursor() as cur:
            cur.execute("CREATE SCHEMA IF NOT EXISTS common_schema")
            cur.execute(COMMON_DDL)
            cur.execute(
                "INSERT INTO common_schema.user_type_master_table (c_name) "
                "VALUES ('STATE_ADMIN'), ('SECTION_OFFICER'), ('PUMP_OPERATOR') "
                "ON CONFLICT (c_name) DO NOTHING"
            )
            cur.execute(f"DROP SCHEMA IF EXISTS {LEGACY_SCHEMA} CASCADE")
            cur.execute(f"CREATE SCHEMA {LEGACY_SCHEMA}")
            cur.execute(
                DDL.format(schema=LEGACY_SCHEMA)
                .replace("    state_user_id             VARCHAR(255),\n", "")
                .split("CREATE UNIQUE INDEX")[0]
            )
        yield UserDb(conn, LEGACY_SCHEMA, _pii(), with_state_user_id=False)
        conn.rollback()

    def test_classify_reads_a_table_without_the_column(self, legacy_db):
        """Analysis must run before the migration, which is the whole point."""
        assert legacy_db.state_user_id_column_exists() is False
        with legacy_db.conn.cursor() as cur:
            cur.execute(f"""
                INSERT INTO {LEGACY_SCHEMA}.user_table
                    (uuid, tenant_id, title, title_hash, email, user_type,
                     phone_number, phone_number_hash, password, status)
                VALUES (gen_random_uuid()::text, 1, %s, %s, 'seed@x.local',
                        (SELECT id FROM common_schema.user_type_master_table
                          WHERE c_name = 'PUMP_OPERATOR'),
                        %s, %s, 'x', 1)
            """, (
                legacy_db.pii.encrypt("Old Name"), legacy_db.pii.title_hash("Old Name"),
                legacy_db.pii.encrypt("919000000001"), legacy_db.pii.hmac("919000000001"),
            ))

        decisions = classify_users([
            _row(3, "USR-1", "New Name", "919000000001", "SECTION_OFFICER"),
            _row(4, "USR-2", "Fresh Person", "919000000002", "PUMP_OPERATOR"),
        ], legacy_db)

        # Name and role still reconcile; the public_id is simply out of scope.
        assert decisions[0].changes[FIELD_NAME] == ("Old Name", "New Name")
        assert decisions[0].changes[FIELD_ROLE] == ("PUMP_OPERATOR", "SECTION_OFFICER")
        assert FIELD_STATE_USER_ID not in decisions[0].changes
        assert decisions[0].withheld == {}
        assert decisions[1].category == CAT_NEW

    def test_insert_and_update_never_mention_the_column(self, legacy_db):
        writer = UserWriter(legacy_db, TENANT_ID, ACTOR_ID)
        roles = {name: row.id for name, row in legacy_db.load_user_types().items()}

        new_user = UserDecision(
            _row(3, "USR-1", "Fresh Person", "919000000001", "PUMP_OPERATOR"), CAT_NEW)
        writer.insert_users([new_user], roles)

        update = UserDecision(_row(4, "", "", "919000000001", ""), CAT_EXISTING)
        update.existing_id = new_user.existing_id
        update.changes = {FIELD_NAME: ("Fresh Person", "Renamed Person")}
        assert writer.update_users([update], roles) == 1

        with legacy_db.conn.cursor() as cur:
            cur.execute(
                f"SELECT title, email FROM {LEGACY_SCHEMA}.user_table WHERE id = %s",
                (new_user.existing_id,))
            title, email = cur.fetchone()
        assert legacy_db.pii.safe_decrypt(title) == "Renamed Person"
        assert email == "po_919000000001@pump-operator.local"

    def test_a_state_user_id_run_against_an_unmigrated_tenant_is_refused(self, legacy_db):
        legacy_db.with_state_user_id = True

        with pytest.raises(SystemExit, match="V36"):
            legacy_db.assert_state_user_id_column()

    def test_the_option_on_a_migrated_tenant_passes_the_check(self, db):
        assert db.state_user_id_column_exists() is True
        db.assert_state_user_id_column()  # does not raise


class TestCreateRoles:
    def test_creates_only_the_missing_roles(self, db, writer, roles):
        plans = [
            RolePlan("PUMP_OPERATOR", ["jal-mitra"], 10, roles["PUMP_OPERATOR"], "existing"),
            RolePlan(NEW_ROLE, ["executive-engineer"], 43, None, "create"),
            # An out-of-scope role is in the report but must never be created.
            RolePlan("khalasi", ["khalasi"], 143, None, "not_ingested"),
        ]

        created = writer.create_roles(plans)

        assert list(created) == [NEW_ROLE]
        with db.conn.cursor() as cur:
            cur.execute(
                "SELECT c_name, created_by FROM common_schema.user_type_master_table "
                "WHERE upper(c_name) = ANY(%s)", ([NEW_ROLE, "KHALASI"],)
            )
            rows = dict(cur.fetchall())
        # created_by carries an FK to tenant_admin_user_master_table, which
        # --actor-id is not an id in — it stays NULL rather than breaking.
        assert rows == {NEW_ROLE: None}


class TestExecuteTenant:
    def test_end_to_end_insert_update_and_new_role(self, db, writer, roles):
        existing = seed_user(db, "Old Name", "919000000001", roles["PUMP_OPERATOR"])
        rows = [
            _row(3, "USR-1", "New Name", "919000000001", "SECTION_OFFICER"),
            _row(4, "USR-2", "Engineer Person", "919000000002", NEW_ROLE),
        ]
        decisions = classify_users(rows, db)
        plan = plan_of(decisions, db)

        stats = execute_tenant(plan, writer, create_roles=True)

        assert stats["user_types_created"] == 1
        assert stats["users_inserted"] == 1
        assert stats["users_updated"] == 1
        assert dump(db, existing)[0] == "New Name"
        assert dump(db, existing)[3] == "USR-1"
        with db.conn.cursor() as cur:
            cur.execute(
                f"SELECT ut.c_name, u.state_user_id FROM {SCHEMA}.user_table u "
                f"JOIN common_schema.user_type_master_table ut ON ut.id = u.user_type "
                f"WHERE u.phone_number_hash = %s", (db.pii.hmac("919000000002"),)
            )
            assert cur.fetchone() == (NEW_ROLE, "USR-2")

    def test_no_create_roles_aborts_before_writing_anything(self, db, writer):
        rows = [_row(3, "USR-2", "Engineer Person", "919000000002", NEW_ROLE)]
        plan = plan_of(classify_users(rows, db), db)

        with pytest.raises(SystemExit, match=NEW_ROLE):
            execute_tenant(plan, writer, create_roles=False)

        with db.conn.cursor() as cur:
            cur.execute(f"SELECT count(*) FROM {SCHEMA}.user_table")
            assert cur.fetchone()[0] == 0
