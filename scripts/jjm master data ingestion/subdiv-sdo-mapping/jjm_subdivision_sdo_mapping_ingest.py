#!/usr/bin/env python3
"""
JJM sub-division -> sub-divisional-officer (SDO) mapping ingestion for a Jal
Soochak tenant (default: Assam / tenant_as).

Reads the state's sub-division/SDO mapping CSV (a title line, then headers on
row 2: subdivision_public_id, subdivision, pubic_id, sdo_name, sdo_phone) and
reconciles it against the tenant database and the analytics warehouse.

Two modes:

  analyze  (default)  read-only. Writes an Excel analysis workbook describing
                      exactly what an execute run would do, and why.
  execute  (--execute) applies the inserts/updates inside transactions.

What it touches
---------------
tenant DB (shared_db), schema tenant_<code>:
  user_table                        insert / update (title, user_type, state_user_id)
  department_location_master_table  update (state_dept_id) — OPT-IN, see below
  user_scheme_mapping_table         insert (and soft-delete with --replace)

analytics DB, schema analytics_schema:
  dim_user_table                    upsert (every SDO this run wrote)
  dim_user_scheme_mapping_table     replaced with the tenant's post-state, per user

common_schema.user_type_master_table is read but never written: SUB_DIVISIONAL_OFFICER
already exists, and this file names no other role, so a run that would have to
mint one is a sign the role was renamed — it aborts instead (see Roles below).

Relationship to the division/EE tool
------------------------------------
Resolving the departmental node, walking its subtree for schemes, reconciling
state_dept_id, matching the person on their phone and mapping them onto the
schemes is the same job one rung further down the hierarchy. That engine lives
in div-ee-mapping/jjm_division_ee_mapping_ingest.py and is imported rather than
copied, parameterised by a NodeKind that moves it from level 4 to level 5 and
changes the wording. Only what is genuinely different is written here: the CSV
shape, the fixed role, and the operator-facing workbook.

One consequence of that reuse is worth knowing when reading the code: the
engine's vocabulary is the division one it was written for, so a DivisionPlan
here is a sub-division and MappingRow.division_public_id holds an SDV-… code.
The workbook and the logs say sub-division throughout.

The three halves of a row
-------------------------
1. the SDO                     resolved and written exactly as the user master
                               ingest does — that module is reached through the
                               division tool, so the matching contract, the PII
                               crypto and the onboarding row stay identical.
2. the sub-division            resolved against department_location_master_table.
3. the schemes under it        every live scheme mapped, through
                               scheme_department_mapping_table, to the
                               sub-division node or any of its descendants. The
                               scheme ingest attaches schemes at exactly this
                               level, so in practice the node itself carries
                               them; the subtree walk costs nothing and covers a
                               tenant that nests further.

Sub-division matching contract
------------------------------
  state_dept_id equal to the CSV's subdivision_public_id  (--with-state-dept-id only)
  else normalised title equal, at the sub-division level
  else normalised title equal once a trailing 'Sub-division' is dropped from both
  more than one candidate, or none                        -> the row is skipped

Ambiguity is a live risk at this level in a way it is not one rung up: there are
far more sub-divisions than divisions and nothing stops two of them under
different divisions sharing a name. The CSV carries no division column to break
such a tie, so an ambiguous sub-division is reported — with each rival node's
parent chain, so a human can see which is meant — and skipped, never guessed.
The fix is a first run with --with-state-dept-id: once SDV-… codes are on the
nodes, later runs match on the code and the tie cannot recur.

Whichever way it resolved, the CSV's subdivision_public_id is then written back
to state_dept_id (with --with-state-dept-id), so the next run matches on the id.

Schemes are taken regardless of scheme_master_table.is_active: that flag tracks
recent flow readings, not whether the scheme is the SDO's responsibility.

SDO matching contract
---------------------
The phone number is the identity, as it is everywhere else in the platform, and
the diff applied to a matched user is the user master ingest's: name always,
role unless it is administrative (--no-role-updates withholds every role
change), state_user_id only with --with-state-user-id. Phone number, email,
password and status of an existing user are never touched.

A sub-division listed twice with two different officers maps both of them — a
user_scheme_mapping is many-to-many, so two SDOs covering one sub-division is a
fact the CSV is allowed to state, and this file states it 23 times. It is
reported in the conflicts sheet regardless. One officer listed against several
sub-divisions is mapped to the union of their schemes.

An officer whose sub-divisions all failed to resolve is not written at all: an
account with no schemes cannot do anything, and once the name is fixed a re-run
picks them up. --create-users-without-schemes onboards them anyway.

Roles
-----
Every row is a sub-divisional officer — the file has no role column, and the
role is not inferred per row. SUB_DIVISIONAL_OFFICER is expected to already be
in common_schema.user_type_master_table; if it is missing or soft-deleted the
run reports it and stops rather than minting a second role that would compete
with the real one for a UNIQUE c_name.

Scheme mapping semantics
------------------------
Additive by default: the sub-division's schemes the SDO is not mapped to yet are
inserted, and every mapping they already hold is left alone. --replace makes the
CSV authoritative instead — mappings outside the union of their sub-divisions'
schemes are soft-deleted (deleted_at/deleted_by, mirroring UserUploadRepository),
so the SDO ends up covering exactly what the CSV says.

Migrations
----------
Both external-id reconciliations are opt-in, so the whole tool — analysis and
execution both — runs against a database where neither migration has been
applied. The mapping itself never needs either column, and no migration beyond
those two is required: V37 already adds the column the sub-division public ids
go into.

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
  python3 "scripts/jjm master data ingestion/subdiv-sdo-mapping/jjm_subdivision_sdo_mapping_ingest.py" \
      --csv "scripts/jjm master data ingestion/subdiv-sdo-mapping/subdivision-sdo-mapping.csv" \
      --actor-id 21357 --out "scripts/jjm master data ingestion/subdiv-sdo-mapping/jjm_subdivision_sdo_analysis.xlsx"

  # apply (additive)
  python3 "scripts/jjm master data ingestion/subdiv-sdo-mapping/jjm_subdivision_sdo_mapping_ingest.py" \
      --csv "scripts/jjm master data ingestion/subdiv-sdo-mapping/subdivision-sdo-mapping.csv" \
      --actor-id 21357 --out jjm_subdivision_sdo_analysis.xlsx --execute

  # once V36/V37 are applied: reconcile both public ids, and make the CSV
  # authoritative for what each SDO covers
  python3 "scripts/jjm master data ingestion/subdiv-sdo-mapping/jjm_subdivision_sdo_mapping_ingest.py" \
      --csv "scripts/jjm master data ingestion/subdiv-sdo-mapping/subdivision-sdo-mapping.csv" \
      --actor-id 21357 --out jjm_subdivision_sdo_analysis.xlsx \
      --with-state-dept-id --with-state-user-id --replace --execute
"""

from __future__ import annotations

import argparse
import logging
import os
import sys
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Iterable, Optional

try:
    import pandas as pd
except ImportError:  # pragma: no cover
    sys.exit("pandas is required:  pip install pandas openpyxl")

try:
    import psycopg2
except ImportError:  # pragma: no cover
    sys.exit("psycopg2 is required:  pip install psycopg2-binary")

# The division/EE tool owns the whole engine this one drives one rung lower, and
# re-exports what it in turn takes from the scheme and user ingests. Importing it
# is what keeps the departmental resolution, the PII crypto, the user matching
# contract and the warehouse writer identical across all four tools instead of
# forking into a fourth copy that can drift.
_BASE_DIR = os.path.dirname(os.path.abspath(__file__))
_DIV_EE_DIR = os.path.join(_BASE_DIR, os.pardir, "div-ee-mapping")
sys.path.insert(0, _DIV_EE_DIR)
try:
    from jjm_division_ee_mapping_ingest import (  # noqa: E402
        CAT_EXISTING,
        CAT_NEW,
        DEPT_LEVELS,
        DIV_AMBIGUOUS,
        DIV_NOT_FOUND,
        FIELD_STATE_USER_ID,
        AnalyticsWriter,
        DeptNode,
        DivisionDb,
        DivisionPlan,
        EngineerPlan,
        MappingIngestPlan,
        MappingRow,
        MappingWriter,
        NodeKind,
        PiiCrypto,
        UserWriter,
        build_engineer_plans,
        build_role_plans,
        clean,
        execute_analytics,
        execute_tenant,
        normalise_phone,
        resolve_divisions,
        safe_mask,
    )
except ImportError as exc:  # pragma: no cover
    sys.exit(
        f"Cannot import the division/EE mapping module from {_DIV_EE_DIR!r}: {exc}\n"
        f"Keep this script alongside "
        f"'div-ee-mapping/jjm_division_ee_mapping_ingest.py', which itself needs "
        f"'scheme/jjm_scheme_master_ingest.py' and 'users/jjm_user_master_ingest.py'."
    )


LOG = logging.getLogger("jjm-subdivision-sdo-ingest")

# ─────────────────────────────────────────────────────────────────────────────
# Domain constants — mirrored from the Java services. Keep in sync.
# ─────────────────────────────────────────────────────────────────────────────

# The state's export misspells the officer's public id as 'pubic_id'. Both
# spellings are accepted so a corrected re-export keeps working; the typo is
# reported in run_info rather than silently absorbed.
PUBLIC_ID_COLUMNS = ("public_id", "pubic_id")

CSV_COLUMNS = ["subdivision_public_id", "subdivision", "sdo_name", "sdo_phone"]

# Every row of this file is a sub-divisional officer; there is no role column and
# no per-row inference. common_schema.user_type_master_table already holds this
# c_name, and a run that would have to create it stops instead.
SDO_ROLE = "SUB_DIVISIONAL_OFFICER"

SUB_DIVISION_LEVEL = DEPT_LEVELS["sub_division"]

# Trailing words a state sheet adds to a sub-division's name that our own title
# may not carry ('Amguri Sub-division' vs 'Amguri'). norm_name has already
# collapsed punctuation to spaces by the time these are matched, so 'Sub-Div.'
# arrives as 'sub div'. Longest first: the first match wins, and a bare
# 'division' tail must not win over 'sub division'.
SUB_DIVISION_SUFFIXES = (
    "sub division", "sub divn", "sub div", "subdivision", "subdiv", "sd",
)

SUB_DIVISION_KIND = NodeKind(
    level=SUB_DIVISION_LEVEL,
    label="sub-division",
    id_column="subdivision_public_id",
    title_suffixes=SUB_DIVISION_SUFFIXES,
    no_node_reason="no sub-division on any of this officer's rows resolved",
)

# How deep to render a node's ancestry when two of them collide by name.
PARENT_PATH_DEPTH = 3


# ─────────────────────────────────────────────────────────────────────────────
# CSV model
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class SdoMappingRow(MappingRow):
    """One CSV line: a sub-division on the left, an SDO on the right.

    The engine's row, unchanged — resolve_divisions and build_engineer_plans
    read division_public_id/division_title and must keep finding them — with
    aliases that let this module's own code and its tests use the vocabulary the
    CSV does. role_raw is always '' and role always SDO_ROLE, because the file
    has no role column: the role is what the file is.
    """

    @property
    def subdivision_public_id(self) -> str:
        """The CSV's subdivision_public_id, e.g. 'SDV-028'."""
        return self.division_public_id

    @property
    def subdivision_title(self) -> str:
        """The CSV's subdivision, e.g. 'Bihpuria'."""
        return self.division_title


def resolve_public_id_column(columns: Iterable[str]) -> Optional[str]:
    """Which spelling of the officer's public id this export used."""
    present = set(columns)
    for candidate in PUBLIC_ID_COLUMNS:
        if candidate in present:
            return candidate
    return None


def load_csv(path: str, header_row: int,
             encoding: str) -> tuple[list[SdoMappingRow], list[dict], str]:
    """Read the CSV and normalise every row.

    Returns (rows, per-row issue records, the public-id spelling this file used)
    — the last of these so run_info can say which one was read.

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

    public_id_column = resolve_public_id_column(frame.columns)
    missing = [c for c in CSV_COLUMNS if c not in frame.columns]
    if public_id_column is None:
        missing.append(" or ".join(PUBLIC_ID_COLUMNS))
    if missing:
        raise SystemExit(
            f"CSV is missing expected column(s): {', '.join(missing)}\n"
            f"Found: {', '.join(frame.columns)}"
        )

    read_columns = CSV_COLUMNS + [public_id_column]
    rows: list[SdoMappingRow] = []
    issue_records: list[dict] = []

    for offset, raw in enumerate(frame.to_dict("records")):
        # +1 for the header row itself, +1 to make it 1-based like the file.
        row_no = header_row + 1 + offset
        if all(not clean(raw.get(c)) for c in read_columns):
            continue

        issues: list[str] = []

        subdivision_public_id = clean(raw.get("subdivision_public_id"))
        subdivision_title = clean(raw.get("subdivision"))
        if not subdivision_public_id:
            issues.append(
                "state_dept_id:blank subdivision_public_id — the sub-division is "
                "matched on its title and no state_dept_id is written"
            )
        if not subdivision_title and not subdivision_public_id:
            issues.append("row:blank subdivision — nothing to resolve the sub-division by")

        user_public_id = clean(raw.get(public_id_column))
        if not user_public_id:
            issues.append("state_user_id:blank public_id — user written without a state_user_id")

        name = clean(raw.get("sdo_name"))
        if not name:
            issues.append("row:blank sdo_name")

        phone_raw = clean(raw.get("sdo_phone"))
        phone = normalise_phone(phone_raw) or ""
        if not phone:
            # Never echo the number itself: the workbook carries a masked copy.
            issues.append("row:sdo_phone is not a valid Indian mobile number")

        row = SdoMappingRow(
            row_no=row_no,
            division_public_id=subdivision_public_id,
            division_title=subdivision_title,
            user_public_id=user_public_id,
            name=name,
            phone_raw=phone_raw,
            phone=phone,
            role_raw="",
            role=SDO_ROLE,
            issues=issues,
        )
        rows.append(row)

        for issue in issues:
            kind, _, detail = issue.partition(":")
            issue_records.append({
                "row_no": row_no,
                "subdivision_public_id": subdivision_public_id,
                "subdivision": subdivision_title,
                "public_id": user_public_id,
                "sdo_name": name,
                "issue_kind": kind,
                "issue": detail,
            })

    return rows, issue_records, public_id_column


# ─────────────────────────────────────────────────────────────────────────────
# Plan assembly
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class SdoIngestPlan(MappingIngestPlan):
    """The engine's plan plus the node index, which the ambiguity report needs.

    resolve_divisions hands back every departmental node it loaded; keeping it
    is what lets the workbook print the parent chain of two same-named
    sub-divisions instead of two bare ids.
    """
    nodes: dict[int, DeptNode] = field(default_factory=dict)

    @property
    def officers(self) -> list[EngineerPlan]:
        """The engine calls these engineers; in this file they are SDOs."""
        return self.engineers

    @property
    def subdivisions(self) -> dict[str, DivisionPlan]:
        return self.divisions


def node_path(node_id: Optional[int], nodes: dict[int, DeptNode],
              depth: int = PARENT_PATH_DEPTH) -> str:
    """'Tezpur < Tezpur Division < Central Circle' — the ancestry that tells two
    identically-named sub-divisions apart.

    Walks parent_id upwards, stopping at the root, at `depth` ancestors, or on a
    cycle, so a corrupt hierarchy cannot hang the report.
    """
    if node_id is None or node_id not in nodes:
        return ""
    titles: list[str] = []
    seen: set[int] = set()
    current: Optional[int] = node_id
    while current is not None and current in nodes and current not in seen:
        seen.add(current)
        titles.append(nodes[current].title)
        current = nodes[current].parent_id
        # Only elide when there is genuinely more chain above.
        if len(titles) > depth and current is not None and current in nodes:
            titles.append("…")
            break
    return " < ".join(titles)


def build_plan(
    rows: list[SdoMappingRow],
    csv_issues: list[dict],
    db: DivisionDb,
    update_roles: bool = True,
    create_users_without_schemes: bool = False,
    replace: bool = False,
) -> SdoIngestPlan:
    LOG.info("Classifying %d CSV rows …", len(rows))
    subdivisions, nodes = resolve_divisions(rows, db)
    for plan in subdivisions.values():
        if not plan.resolved:
            LOG.warning("Sub-division %s %r did not resolve: %s",
                        plan.public_id or "(no id)", plan.title, plan.reason)

    officers = build_engineer_plans(
        rows, subdivisions, db,
        update_roles=update_roles,
        create_users_without_schemes=create_users_without_schemes,
    )

    user_types = db.load_user_types()
    role_plans = build_role_plans([p.decision for p in officers if p.will_write], user_types)

    return SdoIngestPlan(
        engineers=officers,
        divisions=subdivisions,
        role_plans=role_plans,
        user_types=user_types,
        csv_issues=csv_issues,
        replace=replace,
        with_state_dept_id=db.with_state_dept_id,
        with_state_user_id=db.with_state_user_id,
        nodes=nodes,
    )


def unusable_roles(plan: SdoIngestPlan) -> list[str]:
    """Roles the CSV needs that this tool refuses to mint.

    Only SUB_DIVISIONAL_OFFICER can appear, and it is expected to be there
    already. Missing means the seed data was renamed; soft-deleted means the
    UNIQUE c_name is occupied by a dead row. Either way a human has to look,
    because inserting a second SUB_DIVISIONAL_OFFICER would split the role in
    two and silently strand half the officers.
    """
    problems = []
    for role_plan in plan.role_plans:
        if role_plan.action == "create":
            problems.append(
                f"{role_plan.role} is not in common_schema.user_type_master_table "
                f"({role_plan.csv_rows} officer(s) need it) — seed it before ingesting"
            )
        elif role_plan.action == "blocked_soft_deleted":
            problems.append(
                f"{role_plan.role} exists as id {role_plan.existing_id} but is "
                f"soft-deleted — restore or rename it before ingesting; c_name is "
                f"UNIQUE, so it can be neither reused nor re-inserted as it stands"
            )
    return problems


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


def _fmt_rows(row_nos: Iterable[int]) -> str:
    return ", ".join(str(r) for r in row_nos)


def _subject(subdivision: DivisionPlan) -> str:
    return f"{subdivision.public_id or '(no id)'} {subdivision.title}"


def build_summary_frame(plan: SdoIngestPlan) -> pd.DataFrame:
    officers = plan.officers
    writable = plan.writable
    subdivisions = list(plan.subdivisions.values())
    resolved = [d for d in subdivisions if d.resolved]

    records = [
        {"metric": "CSV rows", "value": sum(len(p.csv_rows) for p in officers)},
        {"metric": "distinct sub-divisions named", "value": len(subdivisions)},
        {"metric": "  sub-divisions resolved", "value": len(resolved)},
        {"metric": "  sub-divisions not found",
         "value": len([d for d in subdivisions if d.category == DIV_NOT_FOUND])},
        {"metric": "  sub-divisions ambiguous",
         "value": len([d for d in subdivisions if d.category == DIV_AMBIGUOUS])},
        {"metric": "  resolved sub-divisions with no schemes",
         "value": len([d for d in resolved if not d.scheme_ids])},
        {"metric": "distinct SDOs", "value": len(officers)},
        {"metric": "  SDOs inserted",
         "value": len([p for p in writable if p.decision.category == CAT_NEW])},
        {"metric": "  SDOs updated",
         "value": len([p for p in writable
                       if p.decision.category == CAT_EXISTING and p.decision.changes])},
        {"metric": "  SDOs already up to date",
         "value": len([p for p in writable
                       if p.decision.category == CAT_EXISTING and not p.decision.changes])},
        {"metric": "  SDOs skipped",
         "value": len([p for p in officers if not p.will_write])},
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


def build_subdivision_frame(plan: SdoIngestPlan) -> pd.DataFrame:
    records = [
        {
            "csv_rows": _fmt_rows(d.csv_rows),
            "subdivision_public_id": d.public_id,
            "csv_subdivision": d.title,
            "outcome": d.category,
            "matched_by": d.matched_by,
            "reason": d.reason,
            "department_node_id": d.node_id,
            "our_title": d.node_title,
            "our_hierarchy": node_path(d.node_id, plan.nodes),
            "our_state_dept_id": d.existing_state_dept_id or "",
            # Only interesting when the outcome is AMBIGUOUS: the rival nodes,
            # each with the chain that distinguishes it.
            "candidates": "" if d.resolved else " | ".join(
                f"id {c}: {node_path(c, plan.nodes)}" for c in d.candidate_ids
            ),
            "subtree_nodes": len(d.subtree_ids),
            "schemes_under_subdivision": len(d.scheme_ids),
            "state_dept_id_change": _fmt_changes(
                {"state_dept_id": d.state_dept_id_change} if d.state_dept_id_change else {}
            ),
            "withheld": _fmt_withheld(d.withheld),
            "other_titles_in_csv": ", ".join(d.conflicting_titles),
        }
        for d in sorted(plan.subdivisions.values(),
                        key=lambda p: (p.category, p.public_id, p.title))
    ]
    return pd.DataFrame.from_records(records) if records else pd.DataFrame(
        columns=["csv_rows", "subdivision_public_id", "csv_subdivision", "outcome", "reason"]
    )


def build_mapping_frame(plan: SdoIngestPlan, include_pii: bool) -> pd.DataFrame:
    show = (lambda p: p) if include_pii else safe_mask
    records = []
    for p in plan.writable:
        decision = p.decision
        records.append({
            "csv_rows": _fmt_rows(p.csv_rows),
            "sdo": decision.row.name,
            "phone": show(p.phone),
            "existing_user_id": decision.existing_id,
            "subdivisions": ", ".join(
                f"{d.public_id or d.title} (node {d.node_id}, {len(d.scheme_ids)} schemes)"
                for d in p.resolved_divisions
            ),
            "schemes_in_subdivisions": len(p.target_scheme_ids),
            "already_mapped": len(p.target_scheme_ids & p.existing_scheme_ids),
            "mappings_to_insert": len(p.to_insert),
            "mappings_to_soft_delete": len(p.to_remove) if plan.replace else 0,
            "mappings_outside_subdivisions_kept": 0 if plan.replace else len(p.to_remove),
            "scheme_ids_to_insert": _fmt_ids(p.to_insert),
            "scheme_ids_to_soft_delete": _fmt_ids(p.to_remove) if plan.replace else "",
        })
    return pd.DataFrame.from_records(records) if records else pd.DataFrame(
        columns=["csv_rows", "sdo", "schemes_in_subdivisions", "mappings_to_insert"]
    )


def build_officer_frame(plan: SdoIngestPlan, include_pii: bool) -> pd.DataFrame:
    show = (lambda p: p) if include_pii else safe_mask
    records = []
    for p in plan.officers:
        decision = p.decision
        records.append({
            "csv_rows": _fmt_rows(p.csv_rows),
            "action": p.action,
            "category": decision.category,
            "reason": p.skip_reason or decision.reason,
            "public_id": decision.row.public_id,
            "csv_name": decision.row.name,
            "our_name": decision.existing_name or "",
            "phone": show(p.phone) if p.phone else show(decision.row.phone_raw),
            "role_to_apply": decision.row.role,
            "our_role": decision.existing_role,
            "existing_user_id": decision.existing_id,
            "our_state_user_id": decision.existing_state_user_id or "",
            "subdivisions": ", ".join(
                f"{d.public_id or d.title}{'' if d.resolved else ' (unresolved)'}"
                for d in p.divisions
            ),
            "fields_to_change": _fmt_changes(decision.changes),
            "fields_withheld": _fmt_withheld(decision.withheld),
        })
    return pd.DataFrame.from_records(records) if records else pd.DataFrame(
        columns=["csv_rows", "action", "category", "reason"]
    )


def build_role_frame(plan: SdoIngestPlan) -> pd.DataFrame:
    """One row, normally: SUB_DIVISIONAL_OFFICER, already present.

    The role is not read from the file — it is what the file is — so the CSV
    slug column the user tool reports has nothing to say here.
    """
    records = [
        {
            "canonical_role": p.role,
            "source": "implied by the file (there is no role column)",
            "sdos": p.csv_rows,
            "user_type_id": p.existing_id,
            "action": {
                "existing": "already in user_type_master_table — nothing to write",
                "create": "BLOCKED: not in user_type_master_table; this tool never creates roles",
                "blocked_soft_deleted": "BLOCKED: the role exists but is soft-deleted",
            }[p.action],
        }
        for p in plan.role_plans
    ]
    return pd.DataFrame.from_records(records) if records else pd.DataFrame(
        columns=["canonical_role", "source", "sdos", "user_type_id", "action"]
    )


def build_analytics_frame(plan: SdoIngestPlan) -> pd.DataFrame:
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


def build_conflict_frame(plan: SdoIngestPlan, include_pii: bool) -> pd.DataFrame:
    """Everything a human has to look at before executing."""
    show = (lambda p: p) if include_pii else safe_mask
    records: list[dict] = []

    officers_by_subdivision: dict[str, list[EngineerPlan]] = defaultdict(list)
    for p in plan.officers:
        for subdivision in p.divisions:
            officers_by_subdivision[subdivision.key].append(p)

    for subdivision in plan.subdivisions.values():
        if not subdivision.resolved:
            detail = subdivision.reason
            if subdivision.category == DIV_AMBIGUOUS and subdivision.candidate_ids:
                detail += ". Candidates: " + " | ".join(
                    f"id {c}: {node_path(c, plan.nodes)}"
                    for c in subdivision.candidate_ids
                )
            records.append({
                "kind": f"SUBDIVISION_{subdivision.category}",
                "csv_rows": _fmt_rows(subdivision.csv_rows),
                "subject": _subject(subdivision),
                "detail": detail,
            })
        elif not subdivision.scheme_ids:
            records.append({
                "kind": "SUBDIVISION_NO_SCHEMES",
                "csv_rows": _fmt_rows(subdivision.csv_rows),
                "subject": _subject(subdivision),
                "detail": f"resolved to node id {subdivision.node_id} but no live scheme "
                          f"is mapped to it or its "
                          f"{len(subdivision.subtree_ids) - 1} descendant(s)",
            })
        if subdivision.conflicting_titles:
            records.append({
                "kind": "SUBDIVISION_TITLE_DISAGREEMENT",
                "csv_rows": _fmt_rows(subdivision.csv_rows),
                "subject": _subject(subdivision),
                "detail": "the CSV also calls this sub-division "
                          + ", ".join(repr(t) for t in subdivision.conflicting_titles),
            })
        for field_name, why in sorted(subdivision.withheld.items()):
            records.append({
                "kind": f"WITHHELD_{field_name.upper()}",
                "csv_rows": _fmt_rows(subdivision.csv_rows),
                "subject": _subject(subdivision),
                "detail": why,
            })

        officers = officers_by_subdivision.get(subdivision.key, [])
        if len(officers) > 1:
            records.append({
                "kind": "SUBDIVISION_MULTIPLE_SDO",
                "csv_rows": _fmt_rows(subdivision.csv_rows),
                "subject": _subject(subdivision),
                "detail": f"{len(officers)} SDOs are listed for this sub-division "
                          f"({', '.join(o.decision.row.name for o in officers)}) — "
                          f"all of them are mapped to its schemes",
            })

    for p in plan.officers:
        subject = p.decision.row.name or show(p.decision.row.phone_raw)
        if not p.will_write:
            records.append({
                "kind": "SDO_SKIPPED",
                "csv_rows": _fmt_rows(p.csv_rows),
                "subject": subject,
                "detail": p.skip_reason or p.decision.reason,
            })
        for field_name, why in sorted(p.decision.withheld.items()):
            records.append({
                "kind": f"WITHHELD_{field_name.upper()}",
                "csv_rows": _fmt_rows(p.csv_rows),
                "subject": subject,
                "detail": why,
            })
        for conflict in p.conflicts:
            records.append({
                "kind": "SDO_ROW_DISAGREEMENT",
                "csv_rows": _fmt_rows(p.csv_rows),
                "subject": subject,
                "detail": conflict,
            })
        if p.will_write and not plan.replace and p.to_remove:
            records.append({
                "kind": "MAPPINGS_OUTSIDE_SUBDIVISION",
                "csv_rows": _fmt_rows(p.csv_rows),
                "subject": subject,
                "detail": f"{len(p.to_remove)} existing mapping(s) are not under any "
                          f"sub-division the CSV gives this SDO; they are kept "
                          f"(pass --replace to soft-delete them)",
            })

    for problem in unusable_roles(plan):
        records.append({
            "kind": "ROLE_BLOCKED",
            "csv_rows": "",
            "subject": SDO_ROLE,
            "detail": problem,
        })

    return pd.DataFrame.from_records(records) if records else pd.DataFrame(
        columns=["kind", "csv_rows", "subject", "detail"]
    )


def write_analysis_workbook(plan: SdoIngestPlan, path: str,
                            include_pii: bool, context: dict) -> None:
    run_info = pd.DataFrame.from_records(
        [{"setting": k, "value": str(v)} for k, v in context.items()]
    )
    issues = pd.DataFrame.from_records(plan.csv_issues) if plan.csv_issues else pd.DataFrame(
        columns=["row_no", "subdivision_public_id", "subdivision", "public_id", "sdo_name",
                 "issue_kind", "issue"]
    )

    with pd.ExcelWriter(path, engine="openpyxl") as writer:
        run_info.to_excel(writer, sheet_name="run_info", index=False)
        build_summary_frame(plan).to_excel(writer, sheet_name="summary", index=False)
        build_subdivision_frame(plan).to_excel(
            writer, sheet_name="subdivision_detail", index=False)
        build_mapping_frame(plan, include_pii).to_excel(
            writer, sheet_name="mapping_detail", index=False)
        build_officer_frame(plan, include_pii).to_excel(
            writer, sheet_name="sdo_detail", index=False)
        build_role_frame(plan).to_excel(writer, sheet_name="role_summary", index=False)
        build_analytics_frame(plan).to_excel(writer, sheet_name="analytics_summary", index=False)
        build_conflict_frame(plan, include_pii).to_excel(
            writer, sheet_name="conflicts", index=False)
        issues.to_excel(writer, sheet_name="csv_issues", index=False)

    LOG.info("Analysis workbook written to %s", path)


# ─────────────────────────────────────────────────────────────────────────────
# CLI
# ─────────────────────────────────────────────────────────────────────────────

def parse_args(argv: Optional[list[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Reconcile the JJM sub-division -> SDO mapping CSV into a "
                    "Jal Soochak tenant + analytics warehouse.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__.split("Usage")[-1],
    )
    parser.add_argument("--csv", required=True, help="path to the sub-division/SDO mapping CSV")
    parser.add_argument("--header-row", type=int, default=2,
                        help="1-based row holding the column headers (default: 2)")
    parser.add_argument("--encoding", default="utf-8-sig",
                        help="CSV encoding (default: utf-8-sig)")
    parser.add_argument("--schema", default="tenant_as", help="tenant schema (default: tenant_as)")
    parser.add_argument("--tenant-id", type=int, default=None,
                        help="tenant id; resolved from the schema's state code when omitted")
    parser.add_argument("--actor-id", type=int, required=True,
                        help="user_table.id recorded as created_by/updated_by/deleted_by")
    parser.add_argument("--sub-division-level", type=int, default=SUB_DIVISION_LEVEL,
                        help=f"location_config_master_table.level a sub-division sits at "
                             f"(default: {SUB_DIVISION_LEVEL})")
    parser.add_argument("--out", default="jjm_subdivision_sdo_analysis.xlsx",
                        help="analysis workbook path (default: jjm_subdivision_sdo_analysis.xlsx)")
    parser.add_argument("--tenant-dsn", default=os.environ.get("TENANT_DSN"),
                        help="tenant DB DSN (default: $TENANT_DSN)")
    parser.add_argument("--analytics-dsn", default=os.environ.get("ANALYTICS_DSN"),
                        help="analytics DB DSN (default: $ANALYTICS_DSN)")
    parser.add_argument("--execute", action="store_true",
                        help="apply the plan; without this the run is read-only")
    parser.add_argument("--replace", action="store_true",
                        help="make the CSV authoritative for what each SDO covers: "
                             "soft-delete every mapping of theirs that is not under one of "
                             "their sub-divisions. Additive (nothing removed) by default")
    parser.add_argument("--skip-analytics", action="store_true",
                        help="do not touch the analytics warehouse")
    parser.add_argument("--no-role-updates", action="store_true",
                        help=f"never change an existing user's role to {SDO_ROLE}, even when "
                             f"the CSV disagrees; the difference is still reported")
    parser.add_argument("--create-users-without-schemes", action="store_true",
                        help="onboard an SDO whose sub-divisions all failed to resolve; "
                             "by default they are skipped, because the account would have "
                             "no schemes and a re-run picks them up once the name is fixed")
    parser.add_argument("--with-state-dept-id", action="store_true",
                        help="also match sub-divisions on, and reconcile the CSV's "
                             "subdivision_public_id into, department_location_master_table."
                             "state_dept_id. Needs V37 to have been applied; without this "
                             "option the column is neither read nor written, so sub-divisions "
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

    try:
        pii = PiiCrypto(os.environ.get("PII_ENCRYPTION_KEY", ""), os.environ.get("PII_HMAC_KEY", ""))
    except ValueError as exc:
        return _fail(str(exc))

    LOG.info("Reading %s …", args.csv)
    rows, csv_issues, public_id_column = load_csv(args.csv, args.header_row, args.encoding)
    if args.limit is not None:
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
            division_level=args.sub_division_level,
            node_kind=SUB_DIVISION_KIND,
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
                "reconcile the CSV's subdivision_public_id into it.",
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
            "sub_division_level": args.sub_division_level,
            "mode": "EXECUTE" if args.execute else "ANALYZE (read-only)",
            "mapping_mode": "REPLACE (mappings outside the sub-division are soft-deleted)"
            if args.replace else "ADDITIVE (existing mappings are never removed)",
            "analytics": "skipped" if args.skip_analytics else "included",
            "phones_in_report": "full" if args.include_pii else "masked",
            "role": f"{SDO_ROLE} for every row; never created by this tool",
            "officer_public_id_column": public_id_column + (
                " (the state export's misspelling of 'public_id')"
                if public_id_column == "pubic_id" else ""
            ),
            "role_updates": "withheld" if args.no_role_updates else "applied",
            "sdos_without_schemes": "onboarded" if args.create_users_without_schemes
            else "skipped",
            "state_dept_id": "matched and reconciled (needs V37)" if args.with_state_dept_id
            else "OUT OF SCOPE — subdivision_public_id is not written",
            "state_user_id": "reconciled (needs V36)" if args.with_state_user_id
            else "OUT OF SCOPE — public_id is not written",
        }
        write_analysis_workbook(plan, args.out, args.include_pii, context)
        _print_summary(plan)

        problems = unusable_roles(plan)
        if problems:
            return _fail(
                f"{SDO_ROLE} cannot be used as it stands: " + "; ".join(problems)
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
        # create_roles=False: unusable_roles has already established that
        # SUB_DIVISIONAL_OFFICER is present and live, so there is nothing to mint
        # and an attempt to would mean the plan changed underneath us.
        for key, value in execute_tenant(
            plan, user_writer, mapping_writer, create_roles=False
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


def _print_summary(plan: SdoIngestPlan) -> None:
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
