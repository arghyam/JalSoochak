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
  user_scheme_mapping_table         insert (and soft-delete with --replace)

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
Additive by default: the division's schemes the EE is not mapped to yet are
inserted, and every mapping they already hold is left alone. --replace makes
the CSV authoritative instead — mappings outside the union of their divisions'
schemes are soft-deleted (deleted_at/deleted_by, mirroring
UserUploadRepository), so the EE ends up covering exactly what the CSV says.

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

  # apply (additive)
  python3 "scripts/jjm master data ingestion/div-ee-mapping/jjm_division_ee_mapping_ingest.py" \
      --csv "scripts/jjm master data ingestion/div-ee-mapping/divsion-executive-engineer-mapping.csv" \
      --actor-id 21357 --out jjm_division_ee_analysis.xlsx --execute

  # once V36/V37 are applied: reconcile both public ids, and make the CSV
  # authoritative for what each EE covers
  python3 "scripts/jjm master data ingestion/div-ee-mapping/jjm_division_ee_mapping_ingest.py" \
      --csv "scripts/jjm master data ingestion/div-ee-mapping/divsion-executive-engineer-mapping.csv" \
      --actor-id 21357 --out jjm_division_ee_analysis.xlsx \
      --with-state-dept-id --with-state-user-id --replace --execute
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
# and the scheme ingest both write it.
MAPPING_STATUS_ACTIVE = 1

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
        wanted = [c for c in dict.fromkeys(c.strip() for c in codes) if c]
        if not wanted:
            return {}
        owners: dict[str, int] = {}
        with self.conn.cursor() as cur:
            for start in range(0, len(wanted), 5000):
                cur.execute(f"""
                    SELECT lower(state_dept_id), id
                    FROM {self.schema}.department_location_master_table
                    WHERE deleted_at IS NULL AND state_dept_id = ANY(%s)
                """, (wanted[start:start + 5000],))
                for code, node_id in cur:
                    owners.setdefault(code, node_id)
        return owners


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
        """--replace only. Mirrors UserUploadRepository's soft delete exactly:
        the row stays, deleted_at/deleted_by record who dropped it and when."""
        if not pairs:
            return 0
        sql = f"""
            UPDATE {self.schema}.user_scheme_mapping_table AS t
            SET deleted_at = NOW(), deleted_by = v.actor,
                updated_by = v.actor, updated_at = NOW()
            FROM (VALUES %s) AS v (user_id, scheme_id, actor)
            WHERE t.user_id = v.user_id AND t.scheme_id = v.scheme_id
              AND t.deleted_at IS NULL
            RETURNING t.id
        """
        with self.conn.cursor() as cur:
            removed = psycopg2.extras.execute_values(
                cur, sql,
                [(u, s, self.actor_id) for u, s in pairs],
                template="(%s::integer, %s::integer, %s::integer)",
                page_size=1000, fetch=True,
            )
        return len(removed)


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

    @property
    def writable(self) -> list[EngineerPlan]:
        return [p for p in self.engineers if p.will_write]

    @property
    def blocked_roles(self) -> list[RolePlan]:
        return [p for p in self.role_plans if p.action == "blocked_soft_deleted"]

    @property
    def insert_pairs(self) -> list[tuple[int, int]]:
        """(user_id, scheme_id) to create. Only meaningful once ids exist."""
        return [
            (p.decision.existing_id, scheme_id)
            for p in self.writable if p.decision.existing_id
            for scheme_id in sorted(p.to_insert)
        ]

    @property
    def remove_pairs(self) -> list[tuple[int, int]]:
        if not self.replace:
            return []
        return [
            (p.decision.existing_id, scheme_id)
            for p in self.writable if p.decision.existing_id
            for scheme_id in sorted(p.to_remove)
        ]

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
    replace: bool = False,
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

    return MappingIngestPlan(
        engineers=engineers,
        divisions=divisions,
        role_plans=role_plans,
        user_types=user_types,
        csv_issues=csv_issues,
        replace=replace,
        with_state_dept_id=db.with_state_dept_id,
        with_state_user_id=db.with_state_user_id,
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
        {"metric": "scheme mappings to insert", "value": sum(len(p.to_insert) for p in writable)},
        {"metric": "scheme mappings to soft-delete (--replace)",
         "value": sum(len(p.to_remove) for p in writable) if plan.replace else 0},
        {"metric": "scheme mappings already correct",
         "value": sum(len(p.target_scheme_ids & p.existing_scheme_ids) for p in writable)},
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
    records = []
    for p in plan.writable:
        decision = p.decision
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
            "mappings_to_insert": len(p.to_insert),
            "mappings_to_soft_delete": len(p.to_remove) if plan.replace else 0,
            "mappings_outside_divisions_kept":
                0 if plan.replace else len(p.to_remove),
            "scheme_ids_to_insert": _fmt_ids(p.to_insert),
            "scheme_ids_to_soft_delete": _fmt_ids(p.to_remove) if plan.replace else "",
        })
    return pd.DataFrame.from_records(records) if records else pd.DataFrame(
        columns=["csv_rows", "engineer", "schemes_in_divisions", "mappings_to_insert"]
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
                          f"division the CSV gives this engineer; they are kept "
                          f"(pass --replace to soft-delete them)",
            })

    return pd.DataFrame.from_records(records) if records else pd.DataFrame(
        columns=["kind", "csv_rows", "subject", "detail"]
    )


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

    stats["scheme_mappings_inserted"] = mapping_writer.insert_mappings(plan.insert_pairs)
    stats["scheme_mappings_soft_deleted"] = mapping_writer.soft_delete_mappings(
        plan.remove_pairs
    )
    stats["scheme_mappings_already_correct"] = sum(
        len(p.target_scheme_ids & p.existing_scheme_ids) for p in plan.writable
    )
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
    if not touched:
        return {"dim_user_rows_upserted": 0, "dim_user_scheme_mapping_rows": 0}

    ids = [p.decision.existing_id for p in touched]
    snapshot: dict[int, tuple] = {}
    with db.conn.cursor() as cur:
        for start in range(0, len(ids), 5000):
            cur.execute(f"""
                SELECT id, uuid, email, user_type, title, status
                FROM {db.schema}.user_table
                WHERE id = ANY(%s)
            """, (ids[start:start + 5000],))
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
    parser.add_argument("--replace", action="store_true",
                        help="make the CSV authoritative for what each engineer covers: "
                             "soft-delete every mapping of theirs that is not under one of "
                             "their divisions. Additive (nothing removed) by default")
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
            replace=args.replace,
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
            "mapping_mode": "REPLACE (mappings outside the division are soft-deleted)"
            if args.replace else "ADDITIVE (existing mappings are never removed)",
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
