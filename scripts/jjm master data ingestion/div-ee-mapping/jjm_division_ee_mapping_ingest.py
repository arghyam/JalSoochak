#!/usr/bin/env python3
"""
JJM division -> executive-engineer mapping ingestion for a Jal Soochak tenant
(default: Assam / tenant_as).

Reads the state's division/EE mapping CSV (a title line, then headers on row 2:
division_public_id, division, public_id, executive_engineer_name,
executive_engineer_phone, role) and reconciles it against the tenant database
and the analytics warehouse.

Two modes:

  analyze  (default)  read-only. Writes an Excel analysis workbook describing
                      exactly what an execute run would do, and why.
  execute  (--execute) applies the inserts/updates inside transactions.

What it touches
---------------
common_schema:
  user_type_master_table            insert (roles the CSV uses that we lack)

tenant DB (shared_db), schema tenant_<code>:
  user_table                        insert / update (title, user_type, state_user_id)
  department_location_master_table  update (state_dept_id) — OPT-IN, see below
  user_scheme_mapping_table         insert, revive, and retire (unless --additive)

analytics DB, schema analytics_schema:
  dim_user_table                    upsert (every EE this run wrote)
  dim_user_scheme_mapping_table     replaced with the tenant's post-state, per user

The three halves of a row
-------------------------
1. the executive engineer      resolved and written exactly as the user master
                               ingest does — that module is imported rather than
                               re-implemented, so the matching contract, the PII
                               crypto and the onboarding row stay identical.
2. the division                resolved against department_location_master_table.
3. the schemes under it        every live scheme mapped, through
                               scheme_department_mapping_table, to the division
                               node or any of its descendants (the scheme ingest
                               attaches schemes at sub-division level, so the
                               subtree walk is what makes them reachable from the
                               division).

Division matching contract
--------------------------
  state_dept_id equal to the CSV's division_public_id     (--with-state-dept-id only)
  else normalised title equal, at the division level
  else normalised title equal once a trailing 'Division' is dropped from both
  more than one candidate, or none                        -> the row is skipped

Whichever way it resolved, the CSV's division_public_id is then written back to
state_dept_id (with --with-state-dept-id), so the next run matches on the id.

Schemes are taken regardless of scheme_master_table.is_active: that flag tracks
recent flow readings, not whether the scheme is the EE's responsibility.

Executive engineer matching contract
------------------------------------
The phone number is the identity, as it is everywhere else in the platform, and
the diff applied to a matched user is the user master ingest's: name always,
role unless it is administrative (--no-role-updates withholds every role
change), state_user_id only with --with-state-user-id. Phone number, email,
password and status of an existing user are never touched.

A division listed twice with two different engineers maps both of them — a
user_scheme_mapping is many-to-many, so two EEs covering one division is a fact
the CSV is allowed to state. It is reported in the conflicts sheet regardless.
One engineer listed against several divisions is mapped to the union of their
schemes.

An engineer whose divisions all failed to resolve is not written at all: an
account with no schemes cannot do anything, and once the division name is fixed
a re-run picks them up. --create-users-without-schemes onboards them anyway.

Scheme mapping semantics
------------------------
The CSV is the whole truth about the roles it names, tenant-wide, so by default:

    every live mapping of those roles is made to match exactly what this file
    states — nothing more and nothing less

Three consequences worth being sure about before running it:

  * an engineer the file has dropped keeps no schemes at all. Absence from the
    latest dataset is read as a statement about the person, not about the
    schemes they happen to hold, so even a scheme outside every division this
    CSV names is taken off them;
  * an engineer the file names but this run could not process — an unreadable
    phone number, a duplicate one, or divisions that all failed to resolve — is
    treated the same way and stripped too, because a run that cannot place them
    cannot vouch for their coverage either. They are reported apart from the
    genuinely absent, under SKIPPED_HOLDER_STRIPPED;
  * a mapping held by a role this CSV does not name is never touched.
    user_scheme_mapping_table is shared by every role, and the sweep is scoped
    to the roles the file actually assigns.

The removal_detail sheet lists every row that goes, with the reason and whether
it costs anybody coverage; the conflicts sheet names each stripped user once.

Removal is a soft delete: the row stays, deleted_at/deleted_by record who
dropped it and when — mirroring UserUploadRepository — and status drops to 0.
Every service read path already demands `deleted_at IS NULL AND status = 1`
together, so a retired row that kept status 1 was failing only one of the two
guards it should.

Nothing is ever duplicated. A pair the CSV asks for again revives the row a
previous run retired rather than stacking a second one on it, and a pair that
already carried several live rows — nothing in the schema forbids it, there is
no uniqueness on (user_id, scheme_id) — is collapsed onto its earliest row and
reported in duplicate_detail. Running the same CSV twice is therefore a no-op
the second time.

--additive turns the removal off entirely: missing mappings are still inserted,
retired ones the CSV wants are still revived and duplicates are still collapsed,
but nothing is ever taken away.

Migrations
----------
Both external-id reconciliations are opt-in, so the whole tool — analysis and
execution both — runs against a database where neither migration has been
applied. The mapping itself never needs either column.

  --with-state-dept-id   needs V37 (department_location_master_table.state_dept_id)
  --with-state-user-id   needs V36 (user_table.state_user_id)

PII
---
user_table.title and .phone_number are AES-256-GCM encrypted and looked up via
HMAC-SHA256 hashes, mirroring the services' PiiEncryptionService. This script
therefore needs the target environment's PII_ENCRYPTION_KEY and PII_HMAC_KEY.
Phone numbers are masked in the analysis workbook unless --include-pii is given.

Usage
-----
  export PII_ENCRYPTION_KEY=...  PII_HMAC_KEY=...
  export TENANT_DSN='host=localhost port=5432 dbname=shared_db user=postgres password=password@1123'
  export ANALYTICS_DSN='host=localhost port=5432 dbname=analytics user=postgres password=password@1123'

  # dry run -> analysis workbook only. Works before V36/V37 are applied.
  python3 "scripts/jjm master data ingestion/div-ee-mapping/jjm_division_ee_mapping_ingest.py" \
      --csv "scripts/jjm master data ingestion/div-ee-mapping/divsion-executive-engineer-mapping.csv" \
      --actor-id 21357 --out "scripts/jjm master data ingestion/div-ee-mapping/jjm_division_ee_analysis.xlsx"

  # apply, adding only (nothing is ever retired)
  python3 "scripts/jjm master data ingestion/div-ee-mapping/jjm_division_ee_mapping_ingest.py" \
      --csv "scripts/jjm master data ingestion/div-ee-mapping/divsion-executive-engineer-mapping.csv" \
      --actor-id 21357 --out jjm_division_ee_analysis.xlsx --additive --execute

  # apply in full: the CSV becomes the whole truth about the roles it names,
  # and once V36/V37 are applied both public ids are reconciled too
  python3 "scripts/jjm master data ingestion/div-ee-mapping/jjm_division_ee_mapping_ingest.py" \
      --csv "scripts/jjm master data ingestion/div-ee-mapping/divsion-executive-engineer-mapping.csv" \
      --actor-id 21357 --out jjm_division_ee_analysis.xlsx \
      --with-state-dept-id --with-state-user-id --execute
"""

from __future__ import annotations

import argparse
import logging
import os
import sys
from collections import defaultdict, deque
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Iterable, Optional

try:
    import pandas as pd
except ImportError:  # pragma: no cover
    sys.exit("pandas is required:  pip install pandas openpyxl")

try:
    import psycopg2
    import psycopg2.extras
except ImportError:  # pragma: no cover
    sys.exit("psycopg2 is required:  pip install psycopg2-binary")

# The two sibling tools already own everything this one has in common with them:
# the scheme tool the PII crypto, the department hierarchy and the warehouse
# writer; the user tool the whole executive-engineer half — matching on phone,
# the field-level diff, role creation and the onboarding row. All of that has to
# stay byte-identical to what the services do, so it is imported rather than
# copied into a third place that can drift.
_BASE_DIR = os.path.dirname(os.path.abspath(__file__))
_SCHEME_DIR = os.path.join(_BASE_DIR, os.pardir, "scheme")
_USERS_DIR = os.path.join(_BASE_DIR, os.pardir, "users")
sys.path.insert(0, _SCHEME_DIR)
sys.path.insert(0, _USERS_DIR)
try:
    from jjm_scheme_master_ingest import (  # noqa: E402
        DEPT_LEVELS,
        AnalyticsWriter,
        PiiCrypto,
        norm_name,
        normalise_phone,
    )
    from jjm_user_master_ingest import (  # noqa: E402
        CAT_DUPLICATE,
        CAT_EXISTING,
        CAT_INVALID,
        CAT_NEW,
        FIELD_STATE_USER_ID,
        IngestPlan as UserIngestPlan,
        RolePlan,
        UserDb,
        UserDecision,
        UserRow,
        UserTypeRow,
        UserWriter,
        build_role_plans,
        canonical_role,
        clean,
        classify_users,
        execute_tenant as execute_user_tenant,
        safe_mask,
    )
except ImportError as exc:  # pragma: no cover
    sys.exit(
        f"Cannot import the sibling ingestion modules from {_SCHEME_DIR!r} / "
        f"{_USERS_DIR!r}: {exc}\n"
        f"Keep this script alongside 'scheme/jjm_scheme_master_ingest.py' and "
        f"'users/jjm_user_master_ingest.py'."
    )


LOG = logging.getLogger("jjm-division-ee-ingest")

# ─────────────────────────────────────────────────────────────────────────────
# Domain constants — mirrored from the Java services. Keep in sync.
# ─────────────────────────────────────────────────────────────────────────────

CSV_COLUMNS = [
    "division_public_id", "division", "public_id",
    "executive_engineer_name", "executive_engineer_phone", "role",
]

# The file is the executive-engineer mapping by definition, so a blank role cell
# is filled in rather than treated as an unusable row. A role that is present
# and says something else is honoured — canonical_role decides what it means.
DEFAULT_ROLE = "EXECUTIVE_ENGINEER"

# location_config_master_table.region_type 2 = department; level 4 = division.
DEPT_REGION_TYPE = 2
DIVISION_LEVEL = DEPT_LEVELS["division"]

# user_scheme_mapping_table.status — 1 = active, as PumpOperatorUploadChunkProcessor
# and the scheme ingest both write it. 0 is written alongside deleted_at when a
# mapping is retired: every service read path filters on both
# (`usm.deleted_at IS NULL AND usm.status = 1` — PersonSchemeRepository,
# TenantStaffRepository, SchemeDbRepository), so a retired row that kept
# status = 1 was only ever hidden by one of the two guards it should fail.
MAPPING_STATUS_ACTIVE = 1
MAPPING_STATUS_INACTIVE = 0

# Why a live mapping is being retired. Only DUPLICATE_ROW leaves coverage
# unchanged; the other four each take a scheme away from somebody, so they are
# counted apart in the summary and listed in full in the removal_detail sheet.
REMOVAL_ABSENT = "ABSENT_FROM_CSV"
REMOVAL_SKIPPED = "SKIPPED_OFFICER"
REMOVAL_REASSIGNED = "REASSIGNED"
REMOVAL_OUTSIDE_TARGET = "OUTSIDE_CSV_TARGET"
REMOVAL_DUPLICATE = "DUPLICATE_ROW"

# Removal reasons that cost somebody a scheme, as opposed to row hygiene.
COVERAGE_REMOVALS = (
    REMOVAL_ABSENT, REMOVAL_SKIPPED, REMOVAL_REASSIGNED, REMOVAL_OUTSIDE_TARGET,
)

# Trailing words a state sheet adds to a node's name that our own title may not
# carry ('Umrangsu Division' vs 'Umrangsu'). Only ever stripped as a fallback,
# after an exact title match has already failed.
TITLE_SUFFIXES = ("division", "div")

# Division resolution outcomes.
DIV_MATCHED = "MATCHED"
DIV_NOT_FOUND = "NOT_FOUND"
DIV_AMBIGUOUS = "AMBIGUOUS"

# How a division resolved, reported per division.
BY_STATE_DEPT_ID = "state_dept_id"
BY_TITLE = "title"
BY_TITLE_SUFFIXED = "title (ignoring the 'Division' suffix)"

# Person-level outcomes that are this tool's own, on top of the user module's
# CAT_* categories.
SKIP_NO_DIVISION = "no division on any of this engineer's rows resolved"


@dataclass(frozen=True)
class NodeKind:
    """Which rung of the departmental hierarchy a CSV row names, and how to
    talk about it.

    Everything below this line — resolving the node, walking its subtree for
    schemes, reconciling state_dept_id, mapping the person onto the schemes — is
    the same job whichever rung it is; only the level, the wording and the
    suffixes a state sheet appends differ. Bundling those four into one value
    lets the sub-division/SDO tool drive this engine instead of copying it.

    The defaults are the division/executive-engineer contract this module was
    written for, so nothing that omits node_kind changes behaviour.
    """
    level: int = DIVISION_LEVEL
    label: str = "division"
    id_column: str = "division_public_id"
    title_suffixes: tuple[str, ...] = TITLE_SUFFIXES
    no_node_reason: str = SKIP_NO_DIVISION


DIVISION_KIND = NodeKind()


# ─────────────────────────────────────────────────────────────────────────────
# CSV model
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class MappingRow:
    """One CSV line: a division on the left, an executive engineer on the right."""
    row_no: int                 # 1-based row number as shown in the CSV
    division_public_id: str
    division_title: str
    user_public_id: str
    name: str
    phone_raw: str
    phone: str                  # normalised 91XXXXXXXXXX, '' when unusable
    role_raw: str
    role: str                   # canonical c_name, '' when unusable
    issues: list[str] = field(default_factory=list)

    @property
    def blocking_issues(self) -> list[str]:
        """Issues that stop the row from being written at all."""
        return [i for i in self.issues if i.startswith("row:")]

    @property
    def division_key(self) -> str:
        """What identifies this division across rows: the state's id when it
        gave us one, the normalised title otherwise."""
        return self.division_public_id.lower() or norm_name(self.division_title)


def load_csv(path: str, header_row: int, encoding: str) -> tuple[list[MappingRow], list[dict]]:
    """Read the CSV and normalise every row. Returns (rows, per-row issue records).

    The state's export puts a title line above the header, hence the header_row
    argument; keep_default_na is off so a phone like '0091…' survives as text
    rather than becoming a float.
    """
    frame = pd.read_csv(
        path,
        header=header_row - 1,
        dtype=str,
        keep_default_na=False,
        encoding=encoding,
    )
    frame.columns = [
        str(c).strip().lower().replace(" ", "_").replace("-", "_") for c in frame.columns
    ]

    missing = [c for c in CSV_COLUMNS if c not in frame.columns]
    if missing:
        raise SystemExit(
            f"CSV is missing expected column(s): {', '.join(missing)}\n"
            f"Found: {', '.join(frame.columns)}"
        )

    rows: list[MappingRow] = []
    issue_records: list[dict] = []

    for offset, raw in enumerate(frame.to_dict("records")):
        # +1 for the header row itself, +1 to make it 1-based like the file.
        row_no = header_row + 1 + offset
        if all(not clean(raw.get(c)) for c in CSV_COLUMNS):
            continue

        issues: list[str] = []

        division_public_id = clean(raw.get("division_public_id"))
        division_title = clean(raw.get("division"))
        if not division_public_id:
            issues.append(
                "state_dept_id:blank division_public_id — the division is matched on "
                "its title and no state_dept_id is written"
            )
        if not division_title and not division_public_id:
            issues.append("row:blank division — nothing to resolve the division by")

        user_public_id = clean(raw.get("public_id"))
        if not user_public_id:
            issues.append("state_user_id:blank public_id — user written without a state_user_id")

        name = clean(raw.get("executive_engineer_name"))
        if not name:
            issues.append("row:blank executive_engineer_name")

        phone_raw = clean(raw.get("executive_engineer_phone"))
        phone = normalise_phone(phone_raw) or ""
        if not phone:
            # Never echo the number itself: the workbook carries a masked copy.
            issues.append("row:executive_engineer_phone is not a valid Indian mobile number")

        role_raw = clean(raw.get("role"))
        role = canonical_role(role_raw) if role_raw else DEFAULT_ROLE
        if not role_raw:
            issues.append(f"role:blank role — defaulted to {DEFAULT_ROLE}")
        elif not role:
            issues.append(f"row:role '{role_raw}' does not canonicalise to a usable role name")

        row = MappingRow(
            row_no=row_no,
            division_public_id=division_public_id,
            division_title=division_title,
            user_public_id=user_public_id,
            name=name,
            phone_raw=phone_raw,
            phone=phone,
            role_raw=role_raw,
            role=role,
            issues=issues,
        )
        rows.append(row)

        for issue in issues:
            kind, _, detail = issue.partition(":")
            issue_records.append({
                "row_no": row_no,
                "division_public_id": division_public_id,
                "division": division_title,
                "public_id": user_public_id,
                "name": name,
                "issue_kind": kind,
                "issue": detail,
            })

    return rows, issue_records


# ─────────────────────────────────────────────────────────────────────────────
# Division resolution
# ─────────────────────────────────────────────────────────────────────────────

def title_core(title: str, suffixes: Iterable[str] = TITLE_SUFFIXES) -> str:
    """'Umrangsu Division' -> 'umrangsu'. Normalised, trailing suffix removed.

    suffixes must be ordered longest-first where one is a tail of another
    ('sub division' before 'division'), because the first match wins.
    """
    normalised = norm_name(title)
    for suffix in suffixes:
        if normalised.endswith(" " + suffix):
            return normalised[: -(len(suffix) + 1)].strip()
    return normalised


@dataclass
class DeptNode:
    id: int
    title: str
    level: int
    parent_id: Optional[int]
    state_dept_id: Optional[str]


@dataclass
class DivisionPlan:
    """One division as the CSV names it, and what it resolved to."""
    key: str
    public_id: str
    title: str
    csv_rows: list[int] = field(default_factory=list)
    category: str = DIV_NOT_FOUND
    matched_by: str = ""
    reason: str = ""
    node_id: Optional[int] = None
    node_title: str = ""
    # Every node the winning key matched. One entry on a clean match, several on
    # an AMBIGUOUS one — which is what lets a report name the rival nodes.
    candidate_ids: list[int] = field(default_factory=list)
    existing_state_dept_id: Optional[str] = None
    subtree_ids: list[int] = field(default_factory=list)
    scheme_ids: set[int] = field(default_factory=set)
    # Set when the CSV's division_public_id should be written to state_dept_id.
    state_dept_id_change: Optional[tuple[Optional[str], str]] = None
    withheld: dict[str, str] = field(default_factory=dict)
    # Titles the CSV used for this division id, when it used more than one.
    conflicting_titles: list[str] = field(default_factory=list)

    @property
    def resolved(self) -> bool:
        return self.category == DIV_MATCHED and self.node_id is not None


@dataclass(frozen=True)
class MappingRowState:
    """One physical user_scheme_mapping_table row, whatever state it is in.

    The reconciler has to see retired and duplicate rows, not just the live
    ones: resurrecting the row a previous run retired is what stops a re-run
    stacking a second row on the same pair, and seeing the duplicates an earlier
    additive run left behind is what lets them be collapsed.
    """
    id: int
    user_id: int
    scheme_id: int
    status: int
    live: bool          # deleted_at IS NULL

    @property
    def pair(self) -> tuple[int, int]:
        return (self.user_id, self.scheme_id)

    @property
    def usable(self) -> bool:
        """Live *and* active — the only state the services will read."""
        return self.live and self.status == MAPPING_STATUS_ACTIVE


class DivisionDb(UserDb):
    """Reads the departmental hierarchy and the scheme mappings hanging off it.

    Extends the user tool's UserDb (same connection, schema validation, PII
    crypto and executive-engineer lookups) so one object serves both halves of a
    CSV row.

    state_dept_id is opt-in (--with-state-dept-id). With it off the column is
    neither read nor written and nothing here references it, so the whole tool
    runs against a database where V37 has not been applied yet; divisions
    resolve on their title exactly as they otherwise would.
    """

    def __init__(self, conn, schema: str, pii: PiiCrypto,
                 with_state_user_id: bool = False,
                 with_state_dept_id: bool = False,
                 division_level: Optional[int] = None,
                 node_kind: NodeKind = DIVISION_KIND) -> None:
        super().__init__(conn, schema, pii, with_state_user_id)
        self.with_state_dept_id = with_state_dept_id
        self.node_kind = node_kind
        # An explicit level still wins, so --division-level keeps working; the
        # kind supplies it otherwise.
        self.division_level = (
            division_level if division_level is not None else node_kind.level
        )

    def state_dept_id_column_exists(self) -> bool:
        with self.conn.cursor() as cur:
            cur.execute("""
                SELECT 1 FROM information_schema.columns
                WHERE table_schema = %s AND table_name = 'department_location_master_table'
                  AND column_name = 'state_dept_id'
            """, (self.schema,))
            return cur.fetchone() is not None

    def assert_state_dept_id_column(self) -> None:
        """Fail on the first query, not halfway through the run."""
        if not self.state_dept_id_column_exists():
            raise SystemExit(
                f"{self.schema}.department_location_master_table has no state_dept_id "
                f"column, which --with-state-dept-id needs. Apply "
                f"backend/database/V37__add_state_dept_id_to_department_location_table.sql "
                f"first, or drop the option to match divisions on their title only."
            )

    def load_dept_nodes(self) -> dict[int, DeptNode]:
        """Every live departmental node, with the level its config gives it."""
        # With the option off the column may not exist at all, so it is not
        # named; the placeholder keeps the row shape identical either way.
        state_dept_id_expr = (
            "d.state_dept_id" if self.with_state_dept_id else "NULL::varchar"
        )
        nodes: dict[int, DeptNode] = {}
        with self.conn.cursor() as cur:
            cur.execute(f"""
                SELECT d.id, d.title, c.level, d.parent_id, {state_dept_id_expr}
                FROM {self.schema}.department_location_master_table d
                JOIN {self.schema}.location_config_master_table c
                  ON c.id = d.department_location_config_id AND c.region_type = %s
                WHERE d.deleted_at IS NULL
            """, (DEPT_REGION_TYPE,))
            for node_id, title, level, parent_id, state_dept_id in cur:
                nodes[node_id] = DeptNode(node_id, title, level, parent_id, state_dept_id)
        return nodes

    def load_schemes_by_dept(self) -> dict[int, set[int]]:
        """parent_department_id -> live scheme ids mapped to it.

        is_active is deliberately not filtered: it tracks whether a scheme has
        had a recent flow reading, not whether it is the engineer's to cover.
        """
        result: dict[int, set[int]] = defaultdict(set)
        with self.conn.cursor() as cur:
            cur.execute(f"""
                SELECT sdm.parent_department_id, sdm.scheme_id
                FROM {self.schema}.scheme_department_mapping_table sdm
                JOIN {self.schema}.scheme_master_table s
                  ON s.id = sdm.scheme_id AND s.deleted_at IS NULL
                WHERE sdm.deleted_at IS NULL
            """)
            for dept_id, scheme_id in cur:
                result[dept_id].add(scheme_id)
        return dict(result)

    def load_state_dept_id_owners(self, codes: Iterable[str]) -> dict[str, int]:
        """lower(state_dept_id) -> id of the live node already holding it."""
        if not self.with_state_dept_id:
            return {}
        # Callers look these up by lower(code), so the query has to match that
        # way too — otherwise a code we hold in another case reads as free and
        # we write a second copy of the same state id.
        wanted = [c for c in dict.fromkeys(c.strip().lower() for c in codes) if c]
        if not wanted:
            return {}
        owners: dict[str, int] = {}
        with self.conn.cursor() as cur:
            for start in range(0, len(wanted), 5000):
                cur.execute(f"""
                    SELECT lower(state_dept_id), id
                    FROM {self.schema}.department_location_master_table
                    WHERE deleted_at IS NULL AND lower(state_dept_id) = ANY(%s)
                """, (wanted[start:start + 5000],))
                for code, node_id in cur:
                    owners.setdefault(code, node_id)
        return owners

    def load_mapping_rows(
        self, user_ids: Iterable[int]
    ) -> dict[tuple[int, int], list[MappingRowState]]:
        """(user_id, scheme_id) -> every physical row for that pair, oldest first.

        Unlike load_user_scheme_mappings this does *not* filter deleted_at: the
        retired rows are exactly what makes a re-run idempotent, because the
        reconciler revives one of them instead of inserting a duplicate. Ordering
        by id is what makes 'keep the earliest row' deterministic.
        """
        ids = sorted(set(user_ids))
        if not ids:
            return {}
        rows: dict[tuple[int, int], list[MappingRowState]] = defaultdict(list)
        with self.conn.cursor() as cur:
            for start in range(0, len(ids), 5000):
                cur.execute(f"""
                    SELECT id, user_id, scheme_id, status, deleted_at IS NULL
                    FROM {self.schema}.user_scheme_mapping_table
                    WHERE user_id = ANY(%s)
                    ORDER BY id
                """, (ids[start:start + 5000],))
                for row_id, user_id, scheme_id, status, live in cur:
                    rows[(user_id, scheme_id)].append(
                        MappingRowState(row_id, user_id, scheme_id, status, live)
                    )
        return dict(rows)

    def load_user_names(self, user_ids: Iterable[int]) -> dict[int, str]:
        """id -> decrypted name, for holders of a mapping the CSV never names.

        The removal report has to be able to say *whose* coverage is going, and
        a user the file does not carry has no name in it to borrow.
        """
        ids = sorted(set(user_ids))
        if not ids:
            return {}
        names: dict[int, str] = {}
        with self.conn.cursor() as cur:
            for start in range(0, len(ids), 5000):
                cur.execute(
                    f"SELECT id, title FROM {self.schema}.user_table WHERE id = ANY(%s)",
                    (ids[start:start + 5000],),
                )
                for user_id, title_enc in cur:
                    names[user_id] = self.pii.safe_decrypt(title_enc) or ""
        return names

    def load_role_holder_ids(self, roles: Iterable[str]) -> set[int]:
        """Every live user of these roles holding at least one live mapping.

        This is the universe the CSV claims authority over. It is role-scoped
        because user_scheme_mapping_table is shared by every role: ingesting the
        section-officer file must not so much as read a pump operator's mapping,
        let alone retire it. A file that names several roles claims all of them,
        and only them.
        """
        wanted = sorted({r.strip().upper() for r in roles if r and r.strip()})
        if not wanted:
            return set()
        with self.conn.cursor() as cur:
            cur.execute(f"""
                SELECT DISTINCT m.user_id
                FROM {self.schema}.user_scheme_mapping_table m
                JOIN {self.schema}.user_table u
                  ON u.id = m.user_id AND u.deleted_at IS NULL
                JOIN common_schema.user_type_master_table ut
                  ON ut.id = u.user_type AND upper(ut.c_name) = ANY(%s)
                WHERE m.deleted_at IS NULL
            """, (wanted,))
            return {user_id for (user_id,) in cur}


def build_children_index(nodes: dict[int, DeptNode]) -> dict[int, list[int]]:
    children: dict[int, list[int]] = defaultdict(list)
    for node in nodes.values():
        if node.parent_id is not None:
            children[node.parent_id].append(node.id)
    return dict(children)


def subtree_ids(root_id: int, children: dict[int, list[int]]) -> list[int]:
    """The node itself plus every descendant, breadth-first.

    The scheme ingest attaches schemes at sub-division level, one level below a
    division, but nothing in the schema forbids a scheme hanging off the
    division node directly — walking the whole subtree covers both without
    assuming which of them the data does.
    """
    seen = {root_id}
    order = [root_id]
    queue = deque([root_id])
    while queue:
        for child in children.get(queue.popleft(), ()):
            if child in seen:      # a cycle would otherwise never terminate
                continue
            seen.add(child)
            order.append(child)
            queue.append(child)
    return order


def resolve_divisions(
    rows: list[MappingRow], db: DivisionDb
) -> tuple[dict[str, DivisionPlan], dict[int, DeptNode]]:
    """One plan per division the CSV names, resolved against the dept hierarchy."""
    plans: dict[str, DivisionPlan] = {}
    for row in rows:
        if row.blocking_issues:
            continue
        key = row.division_key
        if not key:
            continue
        plan = plans.get(key)
        if plan is None:
            plan = DivisionPlan(
                key=key, public_id=row.division_public_id, title=row.division_title
            )
            plans[key] = plan
        elif row.division_title and norm_name(row.division_title) != norm_name(plan.title):
            # Same id, two names. Report it and keep the first — the id is what
            # identifies the division, and state_dept_id will settle it anyway.
            if row.division_title not in plan.conflicting_titles:
                plan.conflicting_titles.append(row.division_title)
        plan.csv_rows.append(row.row_no)

    if not plans:
        return plans, {}

    LOG.info("  resolving %d division(s) against %s …", len(plans), db.schema)
    nodes = db.load_dept_nodes()
    children = build_children_index(nodes)
    schemes_by_dept = db.load_schemes_by_dept()

    by_state_id: dict[str, list[int]] = defaultdict(list)
    by_title: dict[str, list[int]] = defaultdict(list)
    by_core: dict[str, list[int]] = defaultdict(list)
    for node in nodes.values():
        if node.level != db.division_level:
            continue
        if node.state_dept_id:
            by_state_id[node.state_dept_id.strip().lower()].append(node.id)
        by_title[norm_name(node.title)].append(node.id)
        by_core[title_core(node.title, db.node_kind.title_suffixes)].append(node.id)

    owners = db.load_state_dept_id_owners(p.public_id for p in plans.values() if p.public_id)

    for plan in plans.values():
        _resolve_one_division(plan, db, nodes, by_state_id, by_title, by_core)
        if not plan.resolved:
            continue
        node = nodes[plan.node_id]
        plan.node_title = node.title
        plan.existing_state_dept_id = node.state_dept_id
        plan.subtree_ids = subtree_ids(plan.node_id, children)
        plan.scheme_ids = set().union(
            *(schemes_by_dept.get(n, set()) for n in plan.subtree_ids)
        ) if plan.subtree_ids else set()
        if not plan.scheme_ids:
            plan.reason += (
                f"; no scheme is mapped to this {db.node_kind.label} or any of its "
                f"{len(plan.subtree_ids) - 1} descendant node(s)"
            )
        _plan_state_dept_id(plan, db, owners)

    _withhold_contested_nodes(plans, db.node_kind.id_column)
    return plans, nodes


def _withhold_contested_nodes(
    plans: dict[str, DivisionPlan], id_column: str = DIVISION_KIND.id_column
) -> None:
    """Two CSV divisions that resolve to one node cannot both own its id.

    The mapping itself is unaffected — both engineers still get that node's
    schemes — but writing state_dept_id would be a coin toss between two codes,
    and V37's partial UNIQUE index means only one of them can ever stick.
    """
    by_node: dict[int, list[DivisionPlan]] = defaultdict(list)
    for plan in plans.values():
        if plan.resolved:
            by_node[plan.node_id].append(plan)

    for node_id, contenders in by_node.items():
        codes = {p.public_id for p in contenders if p.public_id}
        if len(contenders) < 2 or len(codes) < 2:
            continue
        for plan in contenders:
            plan.state_dept_id_change = None
            plan.withheld["state_dept_id"] = (
                f"the CSV gives departmental node id {node_id} more than one "
                f"{id_column} ({', '.join(sorted(codes))}) — none is written"
            )


def _resolve_one_division(
    plan: DivisionPlan,
    db: DivisionDb,
    nodes: dict[int, DeptNode],
    by_state_id: dict[str, list[int]],
    by_title: dict[str, list[int]],
    by_core: dict[str, list[int]],
) -> None:
    """Try each key in turn; an ambiguous hit stops the search rather than
    falling through to a weaker key that might look decisive but is not."""
    attempts = []
    if db.with_state_dept_id and plan.public_id:
        attempts.append((BY_STATE_DEPT_ID, by_state_id.get(plan.public_id.strip().lower(), [])))
    if plan.title:
        attempts.append((BY_TITLE, by_title.get(norm_name(plan.title), [])))
        attempts.append((
            BY_TITLE_SUFFIXED,
            by_core.get(title_core(plan.title, db.node_kind.title_suffixes), []),
        ))

    for matched_by, candidates in attempts:
        if not candidates:
            continue
        plan.candidate_ids = sorted(candidates)
        if len(candidates) > 1:
            plan.category = DIV_AMBIGUOUS
            plan.matched_by = matched_by
            plan.reason = (
                f"{matched_by} matches {len(candidates)} departmental nodes "
                f"({', '.join(f'id {c} {nodes[c].title!r}' for c in sorted(candidates)[:5])}"
                f"{'…' if len(candidates) > 5 else ''}) — cannot tell which "
                f"{db.node_kind.label} is meant"
            )
            return
        plan.category = DIV_MATCHED
        plan.matched_by = matched_by
        plan.node_id = candidates[0]
        plan.reason = f"matched on {matched_by}"
        return

    plan.category = DIV_NOT_FOUND
    plan.reason = (
        f"no live node at department level {db.division_level} matches "
        f"{plan.title!r}"
        + (f" or state_dept_id {plan.public_id}" if db.with_state_dept_id and plan.public_id
           else "")
    )


def _plan_state_dept_id(plan: DivisionPlan, db: DivisionDb, owners: dict[str, int]) -> None:
    """Fill in the state's id on the node we matched, or say why we did not.

    The partial UNIQUE index V37 creates would reject a second owner, so a code
    another node already holds is reported instead of aborting the run.
    """
    if not db.with_state_dept_id or not plan.public_id:
        return
    if (plan.existing_state_dept_id or "") == plan.public_id:
        return
    owner = owners.get(plan.public_id.strip().lower())
    if owner is not None and owner != plan.node_id:
        plan.withheld["state_dept_id"] = (
            f"division_public_id {plan.public_id} already belongs to departmental "
            f"node id {owner}"
        )
        return
    plan.state_dept_id_change = (plan.existing_state_dept_id, plan.public_id)


# ─────────────────────────────────────────────────────────────────────────────
# Executive engineers, and the mapping between the two halves
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class EngineerPlan:
    """One person, the divisions they were listed against, and their mappings."""
    phone: str
    decision: UserDecision
    csv_rows: list[int] = field(default_factory=list)
    divisions: list[DivisionPlan] = field(default_factory=list)
    target_scheme_ids: set[int] = field(default_factory=set)
    existing_scheme_ids: set[int] = field(default_factory=set)
    to_insert: set[int] = field(default_factory=set)
    to_remove: set[int] = field(default_factory=set)
    skip_reason: str = ""
    # Names/roles the CSV gave this same number on different rows.
    conflicts: list[str] = field(default_factory=list)

    @property
    def will_write(self) -> bool:
        return not self.skip_reason and self.decision.will_write

    @property
    def resolved_divisions(self) -> list[DivisionPlan]:
        return [d for d in self.divisions if d.resolved]

    @property
    def action(self) -> str:
        if self.skip_reason:
            return "skip"
        return self.decision.action


def collapse_engineers(rows: list[MappingRow]) -> tuple[list[UserRow], dict[str, list[MappingRow]]]:
    """One UserRow per phone number, so the user module sees a 1:1 CSV.

    A person listed against several divisions is one person: collapsing them
    here is what turns "two rows" into "the union of two divisions" instead of
    the duplicate the user master ingest would rightly refuse to guess at.
    """
    grouped: dict[str, list[MappingRow]] = {}
    order: list[str] = []
    for row in rows:
        key = row.phone or f"row:{row.row_no}"   # unusable rows stay separate
        if key not in grouped:
            grouped[key] = []
            order.append(key)
        grouped[key].append(row)

    user_rows: list[UserRow] = []
    for key in order:
        member_rows = grouped[key]
        first = member_rows[0]
        # Later rows win on name, matching the scheme ingest; the disagreement
        # is reported either way.
        last_named = member_rows[-1]
        # The state only fills public_id on some of a person's rows, so take the
        # first one that carries a code rather than losing it to row order.
        public_id = next(
            (r.user_public_id for r in member_rows if r.user_public_id),
            first.user_public_id,
        )
        user_rows.append(UserRow(
            row_no=first.row_no,
            public_id=public_id,
            name=last_named.name,
            phone_raw=first.phone_raw,
            phone=first.phone,
            role_raw=first.role_raw,
            role=first.role,
            issues=list(first.issues),
        ))
    return user_rows, grouped


def build_engineer_plans(
    rows: list[MappingRow],
    divisions: dict[str, DivisionPlan],
    db: DivisionDb,
    update_roles: bool = True,
    create_users_without_schemes: bool = False,
) -> list[EngineerPlan]:
    """Resolve every engineer against the tenant DB and work out their mappings."""
    user_rows, grouped = collapse_engineers(rows)
    decisions = classify_users(user_rows, db, update_roles)

    plans: list[EngineerPlan] = []
    for user_row, decision in zip(user_rows, decisions):
        key = user_row.phone or f"row:{user_row.row_no}"
        member_rows = grouped[key]
        plan = EngineerPlan(
            phone=user_row.phone,
            decision=decision,
            csv_rows=[r.row_no for r in member_rows],
        )
        seen_keys: set[str] = set()
        for member in member_rows:
            division = divisions.get(member.division_key)
            if division is not None and division.key not in seen_keys:
                seen_keys.add(division.key)
                plan.divisions.append(division)
            if norm_name(member.name) != norm_name(user_row.name):
                plan.conflicts.append(
                    f"row {member.row_no} names this number {member.name!r}, "
                    f"row {member_rows[-1].row_no} names it {user_row.name!r}"
                )
            if member.role != user_row.role:
                plan.conflicts.append(
                    f"row {member.row_no} gives this number the role {member.role!r}, "
                    f"row {member_rows[0].row_no} gives it {user_row.role!r}"
                )
        plans.append(plan)

    for plan in plans:
        if plan.decision.category in (CAT_DUPLICATE, CAT_INVALID):
            plan.skip_reason = plan.decision.reason
            continue
        plan.target_scheme_ids = set().union(
            *(d.scheme_ids for d in plan.resolved_divisions)
        ) if plan.resolved_divisions else set()
        if not plan.resolved_divisions and not create_users_without_schemes:
            plan.skip_reason = db.node_kind.no_node_reason

    # Which mappings do the matched engineers already hold?
    existing_ids = [
        p.decision.existing_id for p in plans
        if p.will_write and p.decision.category == CAT_EXISTING and p.decision.existing_id
    ]
    current = db.load_user_scheme_mappings(existing_ids)
    for plan in plans:
        if not plan.will_write:
            continue
        plan.existing_scheme_ids = current.get(plan.decision.existing_id, set())
        plan.to_insert = plan.target_scheme_ids - plan.existing_scheme_ids
        plan.to_remove = plan.existing_scheme_ids - plan.target_scheme_ids

    return plans


# ─────────────────────────────────────────────────────────────────────────────
# Mapping reconciliation
# ─────────────────────────────────────────────────────────────────────────────

@dataclass(frozen=True)
class Removal:
    """One live row this run retires, and why a human should be unsurprised."""
    row_id: int
    user_id: int
    scheme_id: int
    reason: str
    detail: str = ""

    @property
    def costs_coverage(self) -> bool:
        return self.reason in COVERAGE_REMOVALS


@dataclass(frozen=True)
class DuplicateGroup:
    """A (user, scheme) pair that already held more than one live row.

    Nothing in the schema forbids it — user_scheme_mapping_table has plain
    indexes on user_id and scheme_id and no uniqueness at all — so every
    additive run before this one could stack another row on a pair it had
    already mapped. They are collapsed to one and reported apart from real
    removals, because collapsing them costs nobody any coverage.
    """
    user_id: int
    scheme_id: int
    kept_row_id: Optional[int]      # None when the pair is being retired outright
    collapsed_row_ids: list[int]


@dataclass(frozen=True)
class Revival:
    """A retired row the CSV asks for again, reused instead of duplicated."""
    row_id: int
    user_id: int
    scheme_id: int


@dataclass
class MappingReconciliation:
    """What the tenant's mapping rows must become for the CSV to be true.

    Expressed as row ids wherever a row already exists, so that the writes are
    UPDATEs against known rows rather than blind inserts. That is what makes a
    second run of the same CSV a no-op instead of a second copy of everything.
    """
    to_insert: list[tuple[int, int]] = field(default_factory=list)
    revivals: list[Revival] = field(default_factory=list)
    removals: list[Removal] = field(default_factory=list)
    duplicates: list[DuplicateGroup] = field(default_factory=list)
    unchanged: int = 0

    @property
    def to_resurrect(self) -> list[int]:
        return [r.row_id for r in self.revivals]

    @property
    def to_deactivate(self) -> list[int]:
        return [r.row_id for r in self.removals]

    def inserts_by_user(self) -> dict[int, set[int]]:
        out: dict[int, set[int]] = defaultdict(set)
        for user_id, scheme_id in self.to_insert:
            out[user_id].add(scheme_id)
        return out

    def revivals_by_user(self) -> dict[int, set[int]]:
        out: dict[int, set[int]] = defaultdict(set)
        for revival in self.revivals:
            out[revival.user_id].add(revival.scheme_id)
        return out

    def removals_by_user(self) -> dict[int, set[int]]:
        """Coverage lost, per user. Duplicate collapses are not coverage."""
        out: dict[int, set[int]] = defaultdict(set)
        for removal in self.coverage_removals:
            out[removal.user_id].add(removal.scheme_id)
        return out

    @property
    def coverage_removals(self) -> list[Removal]:
        return [r for r in self.removals if r.costs_coverage]

    @property
    def duplicate_removals(self) -> list[Removal]:
        return [r for r in self.removals if r.reason == REMOVAL_DUPLICATE]

    @property
    def affected_user_ids(self) -> set[int]:
        """Everyone whose live mapping set this run changes."""
        users = {u for u, _ in self.to_insert}
        users |= {r.user_id for r in self.removals}
        return users

    @property
    def stripped_user_ids(self) -> set[int]:
        """Users losing coverage because the latest dataset does not carry them."""
        return {r.user_id for r in self.removals
                if r.reason in (REMOVAL_ABSENT, REMOVAL_SKIPPED)}

    def removals_by_reason(self) -> dict[str, int]:
        counts: dict[str, int] = defaultdict(int)
        for removal in self.removals:
            counts[removal.reason] += 1
        return dict(counts)


def reconcile_mappings(
    desired: dict[int, set[int]],
    ledger: dict[tuple[int, int], list[MappingRowState]],
    strip_users: set[int],
    skip_reasons: Optional[dict[int, str]] = None,
    prune: bool = True,
) -> MappingReconciliation:
    """Diff what the CSV states against every physical row the tenant holds.

    Three rules, in order of how much damage getting them wrong would do:

    1. a pair the CSV states ends with exactly one live, active row — an
       existing row is revived rather than duplicated, and the *earliest* row
       wins so the pair keeps its original created_at;
    2. a live row the CSV contradicts is retired, whether it belongs to an
       officer the file names or to one it has dropped entirely;
    3. a pair that already held several live rows is collapsed to one, which
       changes no coverage and is reported separately so it cannot be mistaken
       for one of the removals in rule 2.

    `ledger` must cover every user in `desired` and every user in `strip_users`;
    anything outside those two sets is not this file's to touch and is ignored
    even if it appears.

    prune=False (the --additive runs) suppresses rule 2 only. Rules 1 and 3
    still apply: an additive run that declined to revive a retired row, or that
    left a pair holding two live rows, would be exactly the run that put the
    duplicates there in the first place.
    """
    skip_reasons = skip_reasons or {}
    result = MappingReconciliation()

    wanted = {(user_id, scheme_id)
              for user_id, scheme_ids in desired.items()
              for scheme_id in scheme_ids}
    # A scheme somebody in the CSV now covers: losing it is a reassignment
    # rather than the file simply dropping it.
    claimed_schemes = {scheme_id for _, scheme_id in wanted}
    survivor: dict[tuple[int, int], int] = {}

    for pair in sorted(wanted):
        rows = ledger.get(pair, [])
        if not rows:
            result.to_insert.append(pair)
            continue
        usable = [r for r in rows if r.usable]
        if usable:
            keep = usable[0]            # load_mapping_rows orders by id
            result.unchanged += 1
        else:
            # Revive the earliest row rather than stacking a new one on top.
            keep = rows[0]
            result.revivals.append(Revival(keep.id, keep.user_id, keep.scheme_id))
        survivor[pair] = keep.id

    for pair, rows in sorted(ledger.items()):
        user_id, scheme_id = pair
        live = [r for r in rows if r.live]
        if not live:
            continue

        if pair in wanted:
            kept = survivor[pair]
            extras = [r for r in live if r.id != kept]
            for row in extras:
                result.removals.append(Removal(
                    row.id, user_id, scheme_id, REMOVAL_DUPLICATE,
                    f"a second live row for a pair already held by row id {kept}; "
                    f"collapsed, coverage unchanged",
                ))
            if extras:
                result.duplicates.append(DuplicateGroup(
                    user_id, scheme_id, kept, [r.id for r in extras]))
            continue

        if not prune:
            continue
        if user_id in desired:
            reason = (REMOVAL_REASSIGNED if scheme_id in claimed_schemes
                      else REMOVAL_OUTSIDE_TARGET)
            detail = ("the CSV gives this scheme to another officer"
                      if reason == REMOVAL_REASSIGNED else
                      "the CSV no longer gives this officer this scheme")
        elif user_id in strip_users:
            skipped = skip_reasons.get(user_id)
            reason = REMOVAL_SKIPPED if skipped else REMOVAL_ABSENT
            detail = (f"named in the CSV but not written: {skipped}" if skipped else
                      "this user is not in the latest dataset at all")
        else:
            continue        # another role's mapping — not ours to read or retire

        for row in live:
            result.removals.append(Removal(row.id, user_id, scheme_id, reason, detail))
        if len(live) > 1:
            result.duplicates.append(DuplicateGroup(
                user_id, scheme_id, None, [r.id for r in live]))

    return result


def build_reconciliation(
    engineers: list[EngineerPlan],
    db: DivisionDb,
    roles: Iterable[str],
    prune: bool = True,
) -> MappingReconciliation:
    """Gather what the reconciler needs from the tenant and run it.

    Only users that already exist take part: an officer this run is about to
    onboard holds no rows yet, so their whole target is an insert, and
    MappingIngestPlan.insert_pairs adds it once the user write has minted an id.

    The strip universe is read with the *current* roles, before any of this
    run's writes, so the workbook an operator signs off on and the statement
    that finally executes describe the same set of people.
    """
    desired = {
        p.decision.existing_id: set(p.target_scheme_ids)
        for p in engineers if p.will_write and p.decision.existing_id
    }
    skip_reasons = {
        p.decision.existing_id: (p.skip_reason or p.decision.reason)
        for p in engineers if not p.will_write and p.decision.existing_id
    }

    strip_users: set[int] = set()
    if prune:
        strip_users = db.load_role_holder_ids(roles) - set(desired)
        if strip_users:
            LOG.warning(
                "%d user(s) of role %s hold live mappings but are not in the latest "
                "dataset — every one of those mappings is retired "
                "(see the removal_detail sheet)",
                len(strip_users), "/".join(sorted(roles)),
            )

    ledger = db.load_mapping_rows(set(desired) | strip_users)
    return reconcile_mappings(desired, ledger, strip_users, skip_reasons, prune=prune)


# ─────────────────────────────────────────────────────────────────────────────
# Tenant database writes
# ─────────────────────────────────────────────────────────────────────────────

class MappingWriter:
    """Every write is parameterised; only the validated schema is interpolated."""

    def __init__(self, db: DivisionDb, actor_id: int) -> None:
        self.db = db
        self.conn = db.conn
        self.schema = db.schema
        self.actor_id = actor_id

    def backfill_state_dept_ids(self, divisions: Iterable[DivisionPlan]) -> int:
        """Write the state's division id onto the nodes we matched."""
        payload = [
            (d.node_id, d.state_dept_id_change[1])
            for d in divisions
            if d.resolved and d.state_dept_id_change is not None
        ]
        if not payload:
            return 0
        sql = f"""
            UPDATE {self.schema}.department_location_master_table AS t
            SET state_dept_id = v.state_dept_id, updated_by = v.updated_by, updated_at = NOW()
            FROM (VALUES %s) AS v (id, state_dept_id, updated_by)
            WHERE t.id = v.id AND t.deleted_at IS NULL
            RETURNING t.id
        """
        with self.conn.cursor() as cur:
            touched = psycopg2.extras.execute_values(
                cur, sql,
                [(node_id, code, self.actor_id) for node_id, code in payload],
                template="(%s::integer, %s::varchar, %s::integer)",
                page_size=500, fetch=True,
            )
        return len(touched)

    def insert_mappings(self, pairs: list[tuple[int, int]]) -> int:
        """Additive: only pairs the engineer does not already hold reach here."""
        if not pairs:
            return 0
        sql = f"""
            INSERT INTO {self.schema}.user_scheme_mapping_table
                (user_id, scheme_id, status, created_by, created_at, updated_by, updated_at)
            VALUES %s
            RETURNING id
        """
        with self.conn.cursor() as cur:
            inserted = psycopg2.extras.execute_values(
                cur, sql,
                [(u, s, MAPPING_STATUS_ACTIVE, self.actor_id, self.actor_id) for u, s in pairs],
                template="(%s,%s,%s,%s,NOW(),%s,NOW())",
                page_size=1000, fetch=True,
            )
        return len(inserted)

    def soft_delete_mappings(self, pairs: list[tuple[int, int]]) -> int:
        """Retire every live row for these pairs, by (user_id, scheme_id).

        Follows UserUploadRepository's soft delete — the row stays,
        deleted_at/deleted_by record who dropped it and when — and additionally
        drops status to 0. Every service read path already demands
        `deleted_at IS NULL AND status = 1` together, so leaving status at 1 on
        a retired row left it failing only one of the two guards it should.
        """
        if not pairs:
            return 0
        sql = f"""
            UPDATE {self.schema}.user_scheme_mapping_table AS t
            SET deleted_at = NOW(), deleted_by = v.actor, status = v.retired,
                updated_by = v.actor, updated_at = NOW()
            FROM (VALUES %s) AS v (user_id, scheme_id, actor, retired)
            WHERE t.user_id = v.user_id AND t.scheme_id = v.scheme_id
              AND t.deleted_at IS NULL
            RETURNING t.id
        """
        with self.conn.cursor() as cur:
            removed = psycopg2.extras.execute_values(
                cur, sql,
                [(u, s, self.actor_id, MAPPING_STATUS_INACTIVE) for u, s in pairs],
                template="(%s::integer, %s::integer, %s::integer, %s::integer)",
                page_size=1000, fetch=True,
            )
        return len(removed)

    def deactivate_rows(self, row_ids: Iterable[int]) -> int:
        """Retire specific rows, by id.

        The reconciler addresses rows rather than pairs because a pair may hold
        several of them: collapsing a duplicate has to retire one row and spare
        another that carries the same (user_id, scheme_id).
        """
        ids = sorted(set(row_ids))
        if not ids:
            return 0
        with self.conn.cursor() as cur:
            cur.execute(f"""
                UPDATE {self.schema}.user_scheme_mapping_table
                SET deleted_at = NOW(), deleted_by = %s, status = %s,
                    updated_by = %s, updated_at = NOW()
                WHERE id = ANY(%s) AND deleted_at IS NULL
                RETURNING id
            """, (self.actor_id, MAPPING_STATUS_INACTIVE, self.actor_id, ids))
            return len(cur.fetchall())

    def resurrect_rows(self, row_ids: Iterable[int]) -> int:
        """Bring retired rows back, rather than inserting a second copy.

        Clearing deleted_by as well as deleted_at matters: a row that kept the
        id of whoever retired it, while being live again, would misreport its
        own history to anybody reading the audit columns.
        """
        ids = sorted(set(row_ids))
        if not ids:
            return 0
        with self.conn.cursor() as cur:
            cur.execute(f"""
                UPDATE {self.schema}.user_scheme_mapping_table
                SET deleted_at = NULL, deleted_by = NULL, status = %s,
                    updated_by = %s, updated_at = NOW()
                WHERE id = ANY(%s)
                RETURNING id
            """, (MAPPING_STATUS_ACTIVE, self.actor_id, ids))
            return len(cur.fetchall())


# ─────────────────────────────────────────────────────────────────────────────
# Plan assembly
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class MappingIngestPlan:
    engineers: list[EngineerPlan]
    divisions: dict[str, DivisionPlan]
    role_plans: list[RolePlan]
    user_types: dict[str, UserTypeRow]
    csv_issues: list[dict]
    replace: bool = False
    with_state_dept_id: bool = False
    with_state_user_id: bool = False
    # The roles this file speaks for. The strip in rule 2 is scoped to them, so
    # ingesting one role's mapping can never retire another role's.
    roles: tuple[str, ...] = (DEFAULT_ROLE,)
    reconciliation: MappingReconciliation = field(default_factory=MappingReconciliation)
    # Decrypted names for every user the reconciliation touches, so the removal
    # sheet can name a legacy holder the CSV itself never mentions.
    holder_names: dict[int, str] = field(default_factory=dict)

    @property
    def writable(self) -> list[EngineerPlan]:
        return [p for p in self.engineers if p.will_write]

    @property
    def blocked_roles(self) -> list[RolePlan]:
        return [p for p in self.role_plans if p.action == "blocked_soft_deleted"]

    @property
    def new_user_ids(self) -> set[int]:
        """Officers this run onboarded. Empty until the user writes have run."""
        return {p.decision.existing_id for p in self.writable
                if p.decision.category == CAT_NEW and p.decision.existing_id}

    @property
    def insert_pairs(self) -> list[tuple[int, int]]:
        """(user_id, scheme_id) to create. Only meaningful once ids exist.

        The reconciler settled every pair belonging to a user that already
        existed; a user this run onboarded was not in its ledger at all, so
        their whole target is added here, once the user write has given them an
        id to be addressed by.
        """
        pairs = set(self.reconciliation.to_insert)
        onboarded = self.new_user_ids
        for p in self.writable:
            if p.decision.existing_id in onboarded:
                pairs |= {(p.decision.existing_id, s) for s in p.target_scheme_ids}
        return sorted(pairs)

    @property
    def affected_user_ids(self) -> list[int]:
        """Everyone whose mappings this run may change, officers and legacy
        holders alike.

        Wider than the officers in the file on purpose: a user the CSV never
        names whose mapping was retired has to have their dim rows rewritten
        too, or the warehouse keeps serving coverage the tenant no longer grants.
        """
        ids = {p.decision.existing_id for p in self.writable if p.decision.existing_id}
        ids |= self.reconciliation.affected_user_ids
        return sorted(ids)

    @property
    def resurrect_row_ids(self) -> list[int]:
        return self.reconciliation.to_resurrect

    @property
    def deactivate_row_ids(self) -> list[int]:
        return self.reconciliation.to_deactivate

    def as_user_plan(self) -> UserIngestPlan:
        """The engineer half, shaped for the user module's execute path."""
        return UserIngestPlan(
            decisions=[p.decision for p in self.writable],
            role_plans=self.role_plans,
            user_types=self.user_types,
            csv_issues=[],
            dup_phone={},
            dup_public_id={},
            with_state_user_id=self.with_state_user_id,
        )


def build_plan(
    rows: list[MappingRow],
    csv_issues: list[dict],
    db: DivisionDb,
    update_roles: bool = True,
    create_users_without_schemes: bool = False,
    replace: bool = True,
) -> MappingIngestPlan:
    LOG.info("Classifying %d CSV rows …", len(rows))
    divisions, _ = resolve_divisions(rows, db)
    for plan in divisions.values():
        if not plan.resolved:
            LOG.warning("Division %s %r did not resolve: %s",
                        plan.public_id or "(no id)", plan.title, plan.reason)

    engineers = build_engineer_plans(
        rows, divisions, db,
        update_roles=update_roles,
        create_users_without_schemes=create_users_without_schemes,
    )

    user_types = db.load_user_types()
    role_plans = build_role_plans([p.decision for p in engineers if p.will_write], user_types)
    for role_plan in role_plans:
        if role_plan.action == "create":
            LOG.warning("Role %s is not in user_type_master_table — %d engineer(s) need it",
                        role_plan.role, role_plan.csv_rows)

    roles = tuple(sorted(
        {p.decision.row.role for p in engineers if p.will_write and p.decision.row.role}
    )) or (DEFAULT_ROLE,)
    reconciliation = build_reconciliation(engineers, db, roles, prune=replace)
    holder_names = db.load_user_names(reconciliation.affected_user_ids)

    return MappingIngestPlan(
        engineers=engineers,
        divisions=divisions,
        role_plans=role_plans,
        user_types=user_types,
        csv_issues=csv_issues,
        replace=replace,
        with_state_dept_id=db.with_state_dept_id,
        with_state_user_id=db.with_state_user_id,
        roles=roles,
        reconciliation=reconciliation,
        holder_names=holder_names,
    )


# ─────────────────────────────────────────────────────────────────────────────
# Analysis workbook
# ─────────────────────────────────────────────────────────────────────────────

def _fmt_changes(changes: dict[str, tuple[Any, Any]]) -> str:
    return "; ".join(f"{f}: {old!r} -> {new!r}" for f, (old, new) in sorted(changes.items()))


def _fmt_withheld(withheld: dict[str, str]) -> str:
    return "; ".join(f"{f}: {why}" for f, why in sorted(withheld.items()))


def _fmt_ids(ids: Iterable[int], limit: int = 20) -> str:
    ordered = sorted(ids)
    shown = ", ".join(str(i) for i in ordered[:limit])
    return shown + (f", … (+{len(ordered) - limit} more)" if len(ordered) > limit else "")


def build_summary_frame(plan: MappingIngestPlan) -> pd.DataFrame:
    engineers = plan.engineers
    writable = plan.writable
    divisions = list(plan.divisions.values())
    resolved = [d for d in divisions if d.resolved]
    counts = plan.reconciliation.removals_by_reason()

    records = [
        {"metric": "CSV rows", "value": sum(len(p.csv_rows) for p in engineers)},
        {"metric": "distinct divisions named", "value": len(divisions)},
        {"metric": "  divisions resolved", "value": len(resolved)},
        {"metric": "  divisions not found",
         "value": len([d for d in divisions if d.category == DIV_NOT_FOUND])},
        {"metric": "  divisions ambiguous",
         "value": len([d for d in divisions if d.category == DIV_AMBIGUOUS])},
        {"metric": "  resolved divisions with no schemes",
         "value": len([d for d in resolved if not d.scheme_ids])},
        {"metric": "distinct executive engineers", "value": len(engineers)},
        {"metric": "  engineers inserted",
         "value": len([p for p in writable if p.decision.category == CAT_NEW])},
        {"metric": "  engineers updated",
         "value": len([p for p in writable
                       if p.decision.category == CAT_EXISTING and p.decision.changes])},
        {"metric": "  engineers already up to date",
         "value": len([p for p in writable
                       if p.decision.category == CAT_EXISTING and not p.decision.changes])},
        {"metric": "  engineers skipped",
         "value": len([p for p in engineers if not p.will_write])},
        {"metric": "scheme mappings to insert", "value": len(plan.reconciliation.to_insert)
         + sum(len(p.target_scheme_ids) for p in writable
               if p.decision.category == CAT_NEW)},
        {"metric": "scheme mappings to revive (a retired row reused)",
         "value": len(plan.reconciliation.to_resurrect)},
        {"metric": "scheme mappings already correct", "value": plan.reconciliation.unchanged},
        {"metric": "mappings to retire (coverage lost)",
         "value": len(plan.reconciliation.coverage_removals)},
        {"metric": "  because the CSV moved the scheme to another officer",
         "value": counts.get(REMOVAL_REASSIGNED, 0)},
        {"metric": "  because the CSV no longer gives the officer the scheme",
         "value": counts.get(REMOVAL_OUTSIDE_TARGET, 0)},
        {"metric": "  because the holder is not in the latest dataset",
         "value": counts.get(REMOVAL_ABSENT, 0)},
        {"metric": "  because the holder is named but could not be processed",
         "value": counts.get(REMOVAL_SKIPPED, 0)},
        {"metric": "users losing every mapping they hold",
         "value": len(plan.reconciliation.stripped_user_ids)},
        {"metric": "duplicate rows to collapse (no coverage lost)",
         "value": counts.get(REMOVAL_DUPLICATE, 0)},
        {"metric": "  pairs that held more than one live row",
         "value": len(plan.reconciliation.duplicates)},
        {"metric": "state_dept_id backfills",
         "value": len([d for d in resolved if d.state_dept_id_change is not None])},
        {"metric": "state_user_id backfills",
         "value": len([p for p in writable if FIELD_STATE_USER_ID in p.decision.changes])},
    ]
    return pd.DataFrame.from_records(records)


def build_division_frame(plan: MappingIngestPlan) -> pd.DataFrame:
    records = [
        {
            "csv_rows": ", ".join(str(r) for r in d.csv_rows),
            "division_public_id": d.public_id,
            "csv_division": d.title,
            "outcome": d.category,
            "matched_by": d.matched_by,
            "reason": d.reason,
            "department_node_id": d.node_id,
            "our_title": d.node_title,
            "our_state_dept_id": d.existing_state_dept_id or "",
            "subtree_nodes": len(d.subtree_ids),
            "schemes_under_division": len(d.scheme_ids),
            "state_dept_id_change": _fmt_changes(
                {"state_dept_id": d.state_dept_id_change} if d.state_dept_id_change else {}
            ),
            "withheld": _fmt_withheld(d.withheld),
            "other_titles_in_csv": ", ".join(d.conflicting_titles),
        }
        for d in sorted(plan.divisions.values(), key=lambda p: (p.category, p.public_id, p.title))
    ]
    return pd.DataFrame.from_records(records) if records else pd.DataFrame(
        columns=["csv_rows", "division_public_id", "csv_division", "outcome", "reason"]
    )


def build_engineer_frame(plan: MappingIngestPlan, include_pii: bool) -> pd.DataFrame:
    show = (lambda p: p) if include_pii else safe_mask
    records = []
    for p in plan.engineers:
        decision = p.decision
        records.append({
            "csv_rows": ", ".join(str(r) for r in p.csv_rows),
            "action": p.action,
            "category": decision.category,
            "reason": p.skip_reason or decision.reason,
            "public_id": decision.row.public_id,
            "csv_name": decision.row.name,
            "our_name": decision.existing_name or "",
            "phone": show(p.phone) if p.phone else show(decision.row.phone_raw),
            "csv_role": decision.row.role_raw,
            "canonical_role": decision.row.role,
            "our_role": decision.existing_role,
            "existing_user_id": decision.existing_id,
            "our_state_user_id": decision.existing_state_user_id or "",
            "divisions": ", ".join(
                f"{d.public_id or d.title}{'' if d.resolved else ' (unresolved)'}"
                for d in p.divisions
            ),
            "fields_to_change": _fmt_changes(decision.changes),
            "fields_withheld": _fmt_withheld(decision.withheld),
        })
    return pd.DataFrame.from_records(records) if records else pd.DataFrame(
        columns=["csv_rows", "action", "category", "reason"]
    )


def build_mapping_frame(plan: MappingIngestPlan, include_pii: bool) -> pd.DataFrame:
    show = (lambda p: p) if include_pii else safe_mask
    inserts = plan.reconciliation.inserts_by_user()
    revivals = plan.reconciliation.revivals_by_user()
    removals = plan.reconciliation.removals_by_user()
    records = []
    for p in plan.writable:
        decision = p.decision
        # A user this run onboards holds no rows yet, so their whole target is
        # an insert and the reconciler — which only saw existing users — has
        # nothing to say about them.
        fresh = (p.target_scheme_ids if decision.category == CAT_NEW
                 else inserts.get(decision.existing_id, set()))
        revived = revivals.get(decision.existing_id, set())
        retired = removals.get(decision.existing_id, set())
        records.append({
            "csv_rows": ", ".join(str(r) for r in p.csv_rows),
            "engineer": decision.row.name,
            "phone": show(p.phone),
            "existing_user_id": decision.existing_id,
            "divisions": ", ".join(
                f"{d.public_id or d.title} (node {d.node_id}, {len(d.scheme_ids)} schemes)"
                for d in p.resolved_divisions
            ),
            "schemes_in_divisions": len(p.target_scheme_ids),
            "already_mapped": len(p.target_scheme_ids & p.existing_scheme_ids),
            "mappings_to_insert": len(fresh),
            "mappings_to_revive": len(revived),
            "mappings_to_retire": len(retired),
            "mappings_outside_divisions_kept":
                0 if plan.replace else len(p.to_remove),
            "scheme_ids_to_insert": _fmt_ids(fresh),
            "scheme_ids_to_revive": _fmt_ids(revived),
            "scheme_ids_to_retire": _fmt_ids(retired),
        })
    return pd.DataFrame.from_records(records) if records else pd.DataFrame(
        columns=["csv_rows", "engineer", "schemes_in_divisions", "mappings_to_insert"]
    )


def build_removal_frame(plan: MappingIngestPlan) -> pd.DataFrame:
    """Every mapping an execute run would retire, in full.

    This is the destructive half of the run, so nothing here is elided: the
    operator signs off on the whole list or on none of it. Duplicate collapses
    are listed too, marked as costing no coverage, so that the sheet accounts
    for every row the run touches rather than only the alarming ones.
    """
    records = [
        {
            "user_id": r.user_id,
            "holder": plan.holder_names.get(r.user_id, ""),
            "scheme_id": r.scheme_id,
            "mapping_row_id": r.row_id,
            "reason": r.reason,
            "costs_coverage": "yes" if r.costs_coverage else "no",
            "detail": r.detail,
        }
        for r in sorted(plan.reconciliation.removals,
                        key=lambda r: (r.reason, r.user_id, r.scheme_id))
    ]
    return pd.DataFrame.from_records(records) if records else pd.DataFrame(
        columns=["user_id", "holder", "scheme_id", "mapping_row_id", "reason",
                 "costs_coverage", "detail"]
    )


def build_duplicate_frame(plan: MappingIngestPlan) -> pd.DataFrame:
    """Pairs that already carried more than one live row, before this run.

    Nothing in the schema prevents them — user_scheme_mapping_table has no
    uniqueness on (user_id, scheme_id) — so every additive run before this one
    could add another copy. Reported separately from the removals because
    collapsing them changes nobody's coverage.
    """
    records = [
        {
            "user_id": d.user_id,
            "holder": plan.holder_names.get(d.user_id, ""),
            "scheme_id": d.scheme_id,
            "live_rows_before": len(d.collapsed_row_ids) + (1 if d.kept_row_id else 0),
            "row_kept": d.kept_row_id if d.kept_row_id else "(none — pair retired)",
            "rows_collapsed": ", ".join(str(i) for i in d.collapsed_row_ids),
        }
        for d in sorted(plan.reconciliation.duplicates,
                        key=lambda d: (d.user_id, d.scheme_id))
    ]
    return pd.DataFrame.from_records(records) if records else pd.DataFrame(
        columns=["user_id", "holder", "scheme_id", "live_rows_before", "row_kept",
                 "rows_collapsed"]
    )


def build_conflict_frame(plan: MappingIngestPlan, include_pii: bool) -> pd.DataFrame:
    """Everything a human has to look at before executing."""
    show = (lambda p: p) if include_pii else safe_mask
    records: list[dict] = []

    engineers_by_division: dict[str, list[EngineerPlan]] = defaultdict(list)
    for p in plan.engineers:
        for division in p.divisions:
            engineers_by_division[division.key].append(p)

    for division in plan.divisions.values():
        if not division.resolved:
            records.append({
                "kind": f"DIVISION_{division.category}",
                "csv_rows": ", ".join(str(r) for r in division.csv_rows),
                "subject": f"{division.public_id or '(no id)'} {division.title}",
                "detail": division.reason,
            })
        elif not division.scheme_ids:
            records.append({
                "kind": "DIVISION_NO_SCHEMES",
                "csv_rows": ", ".join(str(r) for r in division.csv_rows),
                "subject": f"{division.public_id or '(no id)'} {division.title}",
                "detail": f"resolved to node id {division.node_id} but no live scheme is "
                          f"mapped to it or its {len(division.subtree_ids) - 1} descendant(s)",
            })
        if division.conflicting_titles:
            records.append({
                "kind": "DIVISION_TITLE_DISAGREEMENT",
                "csv_rows": ", ".join(str(r) for r in division.csv_rows),
                "subject": f"{division.public_id or '(no id)'} {division.title}",
                "detail": "the CSV also calls this division "
                          + ", ".join(repr(t) for t in division.conflicting_titles),
            })
        for field_name, why in sorted(division.withheld.items()):
            records.append({
                "kind": f"WITHHELD_{field_name.upper()}",
                "csv_rows": ", ".join(str(r) for r in division.csv_rows),
                "subject": f"{division.public_id or '(no id)'} {division.title}",
                "detail": why,
            })

        engineers = engineers_by_division.get(division.key, [])
        if len(engineers) > 1:
            records.append({
                "kind": "DIVISION_MULTIPLE_EE",
                "csv_rows": ", ".join(str(r) for r in division.csv_rows),
                "subject": f"{division.public_id or '(no id)'} {division.title}",
                "detail": f"{len(engineers)} executive engineers are listed for this "
                          f"division ({', '.join(e.decision.row.name for e in engineers)}) — "
                          f"all of them are mapped to its schemes",
            })

    for p in plan.engineers:
        subject = p.decision.row.name or show(p.decision.row.phone_raw)
        if not p.will_write:
            records.append({
                "kind": "ENGINEER_SKIPPED",
                "csv_rows": ", ".join(str(r) for r in p.csv_rows),
                "subject": subject,
                "detail": p.skip_reason or p.decision.reason,
            })
        for field_name, why in sorted(p.decision.withheld.items()):
            records.append({
                "kind": f"WITHHELD_{field_name.upper()}",
                "csv_rows": ", ".join(str(r) for r in p.csv_rows),
                "subject": subject,
                "detail": why,
            })
        for conflict in p.conflicts:
            records.append({
                "kind": "ENGINEER_ROW_DISAGREEMENT",
                "csv_rows": ", ".join(str(r) for r in p.csv_rows),
                "subject": subject,
                "detail": conflict,
            })
        if p.will_write and not plan.replace and p.to_remove:
            records.append({
                "kind": "MAPPINGS_OUTSIDE_DIVISION",
                "csv_rows": ", ".join(str(r) for r in p.csv_rows),
                "subject": subject,
                "detail": f"{len(p.to_remove)} existing mapping(s) are not under any "
                          f"division the CSV gives this engineer; --additive is set, "
                          f"so they are kept",
            })

    records.extend(legacy_holder_conflicts(plan))

    return pd.DataFrame.from_records(records) if records else pd.DataFrame(
        columns=["kind", "csv_rows", "subject", "detail"]
    )


def legacy_holder_conflicts(plan: MappingIngestPlan) -> list[dict]:
    """Name, one per row, every user this run strips of all their coverage.

    The strip is the widest thing either tool does, and the users it hits are by
    definition the ones the CSV cannot describe — so the report has to reach
    into the tenant for their names rather than the file. One row per user, not
    per mapping: the mappings themselves are in removal_detail.
    """
    by_user: dict[int, list[Removal]] = defaultdict(list)
    for removal in plan.reconciliation.removals:
        if removal.reason in (REMOVAL_ABSENT, REMOVAL_SKIPPED):
            by_user[removal.user_id].append(removal)

    records = []
    for user_id, removals in sorted(by_user.items()):
        absent = removals[0].reason == REMOVAL_ABSENT
        records.append({
            "kind": "LEGACY_HOLDER_STRIPPED" if absent else "SKIPPED_HOLDER_STRIPPED",
            "csv_rows": "",
            "subject": f"user id {user_id} {plan.holder_names.get(user_id, '') or '(unnamed)'}",
            "detail": (
                f"holds {len(removals)} live mapping(s) of role "
                f"{'/'.join(plan.roles)} and "
                + ("is not in the latest dataset at all"
                   if absent else f"could not be processed — {removals[0].detail}")
                + "; every one of those mappings is retired by this run"
            ),
        })
    return records


def build_role_frame(plan: MappingIngestPlan) -> pd.DataFrame:
    records = [
        {
            "canonical_role": p.role,
            "csv_role_values": ", ".join(p.csv_slugs),
            "engineers": p.csv_rows,
            "user_type_id": p.existing_id,
            "action": {
                "existing": "already in user_type_master_table",
                "create": "INSERT into user_type_master_table",
                "blocked_soft_deleted": "BLOCKED: the role exists but is soft-deleted",
            }[p.action],
        }
        for p in plan.role_plans
    ]
    return pd.DataFrame.from_records(records) if records else pd.DataFrame(
        columns=["canonical_role", "csv_role_values", "engineers", "user_type_id", "action"]
    )


def build_analytics_frame(plan: MappingIngestPlan) -> pd.DataFrame:
    writable = plan.writable
    touched = [p for p in writable if p.decision.existing_id or p.decision.category == CAT_NEW]
    return pd.DataFrame.from_records([
        {"metric": "dim_user_table rows upserted", "value": len(touched)},
        {"metric": "dim_user_scheme_mapping_table users replaced",
         "value": len([p for p in writable if p.to_insert or (plan.replace and p.to_remove)])},
        {"metric": "dim_user_scheme_mapping_table rows after replace",
         "value": sum(
             len(p.target_scheme_ids if plan.replace
                 else p.existing_scheme_ids | p.target_scheme_ids)
             for p in writable if p.to_insert or (plan.replace and p.to_remove)
         )},
    ])


def write_analysis_workbook(plan: MappingIngestPlan, path: str,
                            include_pii: bool, context: dict) -> None:
    run_info = pd.DataFrame.from_records(
        [{"setting": k, "value": str(v)} for k, v in context.items()]
    )
    issues = pd.DataFrame.from_records(plan.csv_issues) if plan.csv_issues else pd.DataFrame(
        columns=["row_no", "division_public_id", "division", "public_id", "name",
                 "issue_kind", "issue"]
    )

    with pd.ExcelWriter(path, engine="openpyxl") as writer:
        run_info.to_excel(writer, sheet_name="run_info", index=False)
        build_summary_frame(plan).to_excel(writer, sheet_name="summary", index=False)
        build_division_frame(plan).to_excel(writer, sheet_name="division_detail", index=False)
        build_mapping_frame(plan, include_pii).to_excel(
            writer, sheet_name="mapping_detail", index=False)
        build_engineer_frame(plan, include_pii).to_excel(
            writer, sheet_name="engineer_detail", index=False)
        build_removal_frame(plan).to_excel(writer, sheet_name="removal_detail", index=False)
        build_duplicate_frame(plan).to_excel(
            writer, sheet_name="duplicate_detail", index=False)
        build_role_frame(plan).to_excel(writer, sheet_name="role_summary", index=False)
        build_analytics_frame(plan).to_excel(writer, sheet_name="analytics_summary", index=False)
        build_conflict_frame(plan, include_pii).to_excel(
            writer, sheet_name="conflicts", index=False)
        issues.to_excel(writer, sheet_name="csv_issues", index=False)

    LOG.info("Analysis workbook written to %s", path)


# ─────────────────────────────────────────────────────────────────────────────
# Execute
# ─────────────────────────────────────────────────────────────────────────────

def execute_tenant(
    plan: MappingIngestPlan,
    user_writer: UserWriter,
    mapping_writer: MappingWriter,
    create_roles: bool,
) -> dict[str, int]:
    """Apply the whole tenant-side plan in one transaction.

    The engineer half goes through the user module's own execute path, so a
    person onboarded here is byte-identical to one onboarded by the user master
    ingest. It fills in existing_id on every inserted user, which is what the
    mapping insert then addresses.
    """
    stats = execute_user_tenant(plan.as_user_plan(), user_writer, create_roles)

    stats["state_dept_ids_backfilled"] = mapping_writer.backfill_state_dept_ids(
        plan.divisions.values()
    )

    missing_ids = [p for p in plan.writable if not p.decision.existing_id]
    if missing_ids:
        raise SystemExit(
            f"{len(missing_ids)} engineer(s) have no user id after the user writes — "
            f"refusing to write scheme mappings against an unknown user."
        )

    # Order matters. Retiring first frees every row the CSV contradicts,
    # including the duplicate copies of a pair that is about to be revived, so
    # the resurrect that follows can leave exactly one live row behind it.
    reconciliation = plan.reconciliation
    stats["scheme_mappings_retired"] = mapping_writer.deactivate_rows(
        plan.deactivate_row_ids
    )
    stats["scheme_mappings_revived"] = mapping_writer.resurrect_rows(
        plan.resurrect_row_ids
    )
    stats["scheme_mappings_inserted"] = mapping_writer.insert_mappings(plan.insert_pairs)
    stats["scheme_mappings_already_correct"] = reconciliation.unchanged
    stats["duplicate_rows_collapsed"] = len(reconciliation.duplicate_removals)
    stats["mappings_removed_costing_coverage"] = len(reconciliation.coverage_removals)
    return stats


def execute_analytics(
    plan: MappingIngestPlan, analytics: AnalyticsWriter, db: DivisionDb
) -> dict[str, int]:
    """Project the post-state of every engineer this run touched into the warehouse.

    The authoritative values are read back from the tenant DB rather than
    assembled from the CSV, so a withheld role, a withheld state_user_id or a
    mapping the tenant transaction did not actually apply cannot leak into the
    warehouse as if it had been.
    """
    touched = [p for p in plan.writable if p.decision.existing_id]
    ids = plan.affected_user_ids
    if not ids:
        return {"dim_user_rows_upserted": 0, "dim_user_scheme_mapping_rows": 0}

    written = [p.decision.existing_id for p in touched]
    snapshot: dict[int, tuple] = {}
    with db.conn.cursor() as cur:
        for start in range(0, len(written), 5000):
            cur.execute(f"""
                SELECT id, uuid, email, user_type, title, status
                FROM {db.schema}.user_table
                WHERE id = ANY(%s)
            """, (written[start:start + 5000],))
            for uid, uuid, email, user_type, title_enc, status in cur:
                snapshot[uid] = (uuid, email, user_type, db.pii.safe_decrypt(title_enc), status)

    users = []
    for engineer in touched:
        row = snapshot.get(engineer.decision.existing_id)
        if row is None:
            continue
        uuid, email, user_type, title, status = row
        users.append({
            "user_id": engineer.decision.existing_id,
            "uuid": uuid,
            "email": email,
            "user_type": user_type,
            # dim_user_table.title holds the plaintext name, exactly as the
            # user-service publishes it on a UserUpdated event.
            "title": title,
            "status": status,
        })

    stats = {"dim_user_rows_upserted": analytics.upsert_users(users)}

    # Replace each engineer's mappings with the tenant DB's post-state. Doing it
    # for everyone (not only those whose set changed) makes a re-run repair a
    # warehouse that drifted, which is the whole point of the delete-then-insert.
    post_state = db.load_user_scheme_mappings(ids)
    mappings = {uid: post_state.get(uid, set()) for uid in ids}
    stats["dim_user_scheme_mapping_rows"] = analytics.replace_user_scheme_mappings(mappings)

    mapped_schemes = set().union(*mappings.values()) if mappings else set()
    stats["mapped_schemes_missing_from_dim_scheme"] = count_missing_dim_schemes(
        analytics, mapped_schemes
    )
    return stats


def count_missing_dim_schemes(analytics: AnalyticsWriter, scheme_ids: set[int]) -> int:
    """Mappings whose scheme has no dim_scheme_table row for this tenant.

    dim_user_scheme_mapping_table carries no scheme foreign key (V21 says so
    explicitly), so such a row inserts happily and then joins to nothing. It is
    a warning about scheme sync, not a reason to refuse the mapping.
    """
    if not scheme_ids:
        return 0
    ids = sorted(scheme_ids)
    present: set[int] = set()
    with analytics.conn.cursor() as cur:
        for start in range(0, len(ids), 5000):
            cur.execute(
                "SELECT DISTINCT scheme_id FROM analytics_schema.dim_scheme_table "
                "WHERE tenant_id = %s AND scheme_id = ANY(%s)",
                (analytics.tenant_id, ids[start:start + 5000]),
            )
            present.update(scheme_id for (scheme_id,) in cur)
    return len(scheme_ids) - len(present)


# ─────────────────────────────────────────────────────────────────────────────
# CLI
# ─────────────────────────────────────────────────────────────────────────────

def parse_args(argv: Optional[list[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Reconcile the JJM division -> executive-engineer mapping CSV into a "
                    "Jal Soochak tenant + analytics warehouse.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__.split("Usage")[-1],
    )
    parser.add_argument("--csv", required=True, help="path to the division/EE mapping CSV")
    parser.add_argument("--header-row", type=int, default=2,
                        help="1-based row holding the column headers (default: 2)")
    parser.add_argument("--encoding", default="utf-8-sig",
                        help="CSV encoding (default: utf-8-sig)")
    parser.add_argument("--schema", default="tenant_as", help="tenant schema (default: tenant_as)")
    parser.add_argument("--tenant-id", type=int, default=None,
                        help="tenant id; resolved from the schema's state code when omitted")
    parser.add_argument("--actor-id", type=int, required=True,
                        help="user_table.id recorded as created_by/updated_by/deleted_by")
    parser.add_argument("--division-level", type=int, default=DIVISION_LEVEL,
                        help=f"location_config_master_table.level a division sits at "
                             f"(default: {DIVISION_LEVEL})")
    parser.add_argument("--out", default="jjm_division_ee_analysis.xlsx",
                        help="analysis workbook path (default: jjm_division_ee_analysis.xlsx)")
    parser.add_argument("--tenant-dsn", default=os.environ.get("TENANT_DSN"),
                        help="tenant DB DSN (default: $TENANT_DSN)")
    parser.add_argument("--analytics-dsn", default=os.environ.get("ANALYTICS_DSN"),
                        help="analytics DB DSN (default: $ANALYTICS_DSN)")
    parser.add_argument("--execute", action="store_true",
                        help="apply the plan; without this the run is read-only")
    parser.add_argument("--additive", action="store_true",
                        help="never retire anything: insert the missing mappings, revive the "
                             "retired ones the CSV asks for again, collapse duplicate rows, "
                             "and leave every other existing mapping alone. By default the "
                             "CSV is authoritative for the roles it names — a mapping it does "
                             "not state is retired, including one held by a user the file "
                             "does not carry at all")
    parser.add_argument("--replace", action="store_true",
                        help=argparse.SUPPRESS)   # pre-authoritative spelling; now the default
    parser.add_argument("--skip-analytics", action="store_true",
                        help="do not touch the analytics warehouse")
    parser.add_argument("--no-role-updates", action="store_true",
                        help="never change an existing user's role, even when the CSV "
                             "disagrees; the difference is still reported")
    parser.add_argument("--no-create-roles", action="store_true",
                        help="abort instead of inserting roles the CSV needs and "
                             "user_type_master_table does not hold")
    parser.add_argument("--create-users-without-schemes", action="store_true",
                        help="onboard an engineer whose divisions all failed to resolve; "
                             "by default they are skipped, because the account would have "
                             "no schemes and a re-run picks them up once the division is fixed")
    parser.add_argument("--with-state-dept-id", action="store_true",
                        help="also match divisions on, and reconcile the CSV's "
                             "division_public_id into, department_location_master_table."
                             "state_dept_id. Needs V37 to have been applied; without this "
                             "option the column is neither read nor written, so divisions "
                             "resolve on their title and the mapping can be ingested before "
                             "the migration lands")
    parser.add_argument("--with-state-user-id", action="store_true",
                        help="also reconcile the CSV's public_id into user_table."
                             "state_user_id. Needs V36 to have been applied")
    parser.add_argument("--include-pii", action="store_true",
                        help="write full phone numbers into the analysis workbook "
                             "(masked by default)")
    parser.add_argument("--limit", type=int, default=None,
                        help="process only the first N CSV rows (for rehearsals)")
    parser.add_argument("-v", "--verbose", action="store_true")
    return parser.parse_args(argv)


def main(argv: Optional[list[str]] = None) -> int:
    args = parse_args(argv)
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)-7s %(message)s",
        datefmt="%H:%M:%S",
    )

    if not args.tenant_dsn:
        return _fail("--tenant-dsn (or $TENANT_DSN) is required")
    if args.execute and not args.skip_analytics and not args.analytics_dsn:
        return _fail("--analytics-dsn (or $ANALYTICS_DSN) is required unless --skip-analytics")
    if args.limit is not None and args.limit < 1:
        return _fail("--limit must be a positive number of rows")
    if args.limit is not None and args.execute and not args.additive:
        # A truncated file is a truncated statement of who covers what: every
        # engineer past the cut reads as absent, and absence now retires
        # mappings rather than merely failing to add them.
        return _fail(
            "--limit cannot be combined with --execute unless --additive is also set: "
            "the run is authoritative, so cutting the CSV short would retire the "
            "mappings of everyone past the cut. Rehearse with --limit and no --execute."
        )
    if args.replace:
        LOG.warning(
            "--replace is the default now and does nothing; pass --additive for the "
            "old default of never removing anything."
        )

    try:
        pii = PiiCrypto(os.environ.get("PII_ENCRYPTION_KEY", ""), os.environ.get("PII_HMAC_KEY", ""))
    except ValueError as exc:
        return _fail(str(exc))

    LOG.info("Reading %s …", args.csv)
    rows, csv_issues = load_csv(args.csv, args.header_row, args.encoding)
    if args.limit:
        rows = rows[: args.limit]
        keep = {r.row_no for r in rows}
        csv_issues = [i for i in csv_issues if i["row_no"] in keep]
    LOG.info("  %d data rows", len(rows))

    tenant_conn = psycopg2.connect(args.tenant_dsn)
    tenant_conn.autocommit = False
    analytics_conn = None
    exit_code = 0

    try:
        db = DivisionDb(
            tenant_conn, args.schema, pii,
            with_state_user_id=args.with_state_user_id,
            with_state_dept_id=args.with_state_dept_id,
            division_level=args.division_level,
        )
        if args.with_state_user_id:
            db.assert_state_user_id_column()
        elif db.state_user_id_column_exists():
            LOG.warning(
                "state_user_id exists on %s.user_table but is out of scope for this run "
                "— pass --with-state-user-id to reconcile the CSV's public_id into it.",
                db.schema,
            )
        if args.with_state_dept_id:
            db.assert_state_dept_id_column()
        elif db.state_dept_id_column_exists():
            LOG.warning(
                "state_dept_id exists on %s.department_location_master_table but is out of "
                "scope for this run — pass --with-state-dept-id to match on it and to "
                "reconcile the CSV's division_public_id into it.",
                db.schema,
            )
        tenant_id = args.tenant_id or db.resolve_tenant_id()
        LOG.info("Tenant id %d, schema %s", tenant_id, db.schema)

        plan = build_plan(
            rows, csv_issues, db,
            update_roles=not args.no_role_updates,
            create_users_without_schemes=args.create_users_without_schemes,
            replace=not args.additive,
        )

        context = {
            "generated_at": datetime.now().isoformat(timespec="seconds"),
            "csv": args.csv,
            "csv_rows": len(rows),
            "tenant_schema": args.schema,
            "tenant_id": tenant_id,
            "actor_id": args.actor_id,
            "division_level": args.division_level,
            "mode": "EXECUTE" if args.execute else "ANALYZE (read-only)",
            "mapping_mode": "ADDITIVE (nothing is ever retired)" if args.additive else
            "AUTHORITATIVE (a mapping of these roles that the CSV does not state is "
            "retired, including one held by a user the CSV never names)",
            "roles_claimed": "/".join(plan.roles),
            "analytics": "skipped" if args.skip_analytics else "included",
            "phones_in_report": "full" if args.include_pii else "masked",
            "role_updates": "withheld" if args.no_role_updates else "applied",
            "new_roles": "blocked" if args.no_create_roles else "created",
            "engineers_without_schemes": "onboarded" if args.create_users_without_schemes
            else "skipped",
            "state_dept_id": "matched and reconciled (needs V37)" if args.with_state_dept_id
            else "OUT OF SCOPE — division_public_id is not written",
            "state_user_id": "reconciled (needs V36)" if args.with_state_user_id
            else "OUT OF SCOPE — public_id is not written",
        }
        write_analysis_workbook(plan, args.out, args.include_pii, context)
        _print_summary(plan)

        if plan.blocked_roles:
            return _fail(
                "These roles exist in user_type_master_table but are soft-deleted: "
                + ", ".join(p.role for p in plan.blocked_roles)
                + ". Restore or rename them before ingesting; c_name is UNIQUE, so they "
                  "can be neither reused nor re-inserted as they stand."
            )

        if not args.execute:
            LOG.info("Read-only run — nothing was written. Re-run with --execute to apply.")
            tenant_conn.rollback()
            return 0

        user_writer = UserWriter(db, tenant_id, args.actor_id)
        user_writer.assert_actor_is_tenant_user()
        mapping_writer = MappingWriter(db, args.actor_id)

        if not args.skip_analytics:
            analytics_conn = psycopg2.connect(args.analytics_dsn)
            analytics_conn.autocommit = False
            analytics = AnalyticsWriter(analytics_conn, tenant_id)
            analytics.assert_tenant_exists()

        LOG.info("Applying tenant changes …")
        for key, value in execute_tenant(
            plan, user_writer, mapping_writer, create_roles=not args.no_create_roles
        ).items():
            LOG.info("  %-38s %d", key, value)

        if analytics_conn is not None:
            LOG.info("Applying analytics changes …")
            for key, value in execute_analytics(plan, analytics, db).items():
                LOG.info("  %-38s %d", key, value)

        tenant_conn.commit()
        if analytics_conn is not None:
            analytics_conn.commit()
        LOG.info("Committed.")

    except SystemExit:
        tenant_conn.rollback()
        if analytics_conn is not None:
            analytics_conn.rollback()
        raise
    except Exception:
        tenant_conn.rollback()
        if analytics_conn is not None:
            analytics_conn.rollback()
        LOG.exception("Run failed — both transactions rolled back, nothing was written.")
        exit_code = 1
    finally:
        tenant_conn.close()
        if analytics_conn is not None:
            analytics_conn.close()

    return exit_code


def _fail(message: str) -> int:
    LOG.error(message)
    return 2


def _print_summary(plan: MappingIngestPlan) -> None:
    LOG.info("─" * 72)
    for record in build_summary_frame(plan).to_dict("records"):
        LOG.info("%-52s %8d", record["metric"], record["value"])
    LOG.info("─" * 72)
    for role_plan in plan.role_plans:
        LOG.info("%-52s %8d  %s", f"role / {role_plan.role}", role_plan.csv_rows,
                 role_plan.action)
    LOG.info("─" * 72)
    LOG.info("See the conflicts sheet of the workbook before executing.")


if __name__ == "__main__":
    sys.exit(main())
