#!/usr/bin/env python3
"""
JJM departmental master ingestion for a Jal Soochak tenant (default: Assam /
tenant_as).

Reads the state's two departmental master CSVs and stamps the public id each
one carries onto the matching node in
``department_location_master_table.state_dept_id``:

    divisions-master.csv                 public_id, division_name
    subdivision-master-with-division.csv public_id, subdivision,
                                         division_public_id, division_name

Both files have a title line above the header, so the header sits on row 2.

Two modes:

  analyze  (default)   read-only. Writes an Excel analysis workbook describing
                       exactly what an execute run would do, and why.
  execute  (--execute)  applies the state_dept_id updates in one transaction.

What it touches
---------------
tenant DB (shared_db), schema tenant_<code>:
  department_location_master_table   UPDATE state_dept_id (and updated_by/at)

That is the whole write surface. No user, scheme, mapping or warehouse row is
read for writing or touched — unlike the three mapping tools next door, this one
only labels nodes we already have. It therefore needs no PII keys and no
analytics DSN.

Relationship to the mapping tools
---------------------------------
div-ee-mapping and subdiv-sdo-mapping already reconcile state_dept_id, but only
for the nodes their own CSV happens to name, and only as a side effect of
mapping an officer. This tool does the departmental half on its own, from the
state's authoritative master lists, so the ids land on every node the state
knows about rather than on whichever subset an officer file mentions.

The vocabulary, the normalisation and the title-suffix fallbacks are imported
from those tools rather than re-stated, so a node this tool matches is a node
they would have matched. What is *not* shared is the resolution itself: a
sub-division here is resolved inside its division (see below), which the mapping
tools cannot do because their CSVs carry no division column.

Matching contract
-----------------
Divisions — tenant-wide among live level-4 nodes:

    state_dept_id equal to the CSV's public_id       (skipped with --no-match-on-id)
    else normalised title equal
    else normalised title equal once a trailing 'Division' is dropped from both
    more than one candidate, or none                 -> unresolved, reported

Sub-divisions — hierarchy first, among live level-5 nodes:

    state_dept_id equal to the CSV's public_id       (skipped with --no-match-on-id)
    else normalised title equal, among the children of the division that the
         CSV's division_public_id/division_name resolved to
    else the same ignoring a trailing 'Sub-division'
    else normalised title equal tenant-wide, then the suffixed form tenant-wide
    more than one candidate, or none                 -> unresolved, reported

The tenant-wide fallback exists because our hierarchy and the state's need not
agree, but a match found only that way is treated as weaker evidence and split
in two:

  PARENT_UNRESOLVED   the CSV's division did not resolve at all, so there was no
                      subtree to search. Nothing contradicts the match, so the id
                      is written and the row is listed for review.
  PARENT_MISMATCH     the division *did* resolve, and this sub-division is not
                      under it. Our hierarchy actively disagrees with the state's,
                      so the id is withheld — pass --accept-parent-mismatch to
                      write it anyway once a human has read the sheet.

This split is what makes the duplicated names in the state file safe. Three
sub-division names appear twice under different divisions ('Hatisingimari',
'Kamalabari', 'Mahur'); a tenant-wide title match would be ambiguous and skipped,
while the parent-restricted match resolves each to the right node.

What is highlighted
-------------------
Beyond the plain unresolved rows, the workbook calls out both directions of
drift between the state's master list and ours:

  extra_in_csv    a division or sub-division the state names that no live node
                  matches — NOT_FOUND, i.e. missing from our hierarchy.
  extra_in_db     a live level-4/5 node that no CSV row resolved to — present
                  only in ours. Each is reported with its ancestry and with how
                  many schemes hang off it directly and across its subtree, so a
                  genuine gap in the state's list can be told from a stray node.
  ambiguous       a CSV row matching more than one node; never guessed.
  contested       two CSV rows resolving to one node — neither id is written,
                  because V37's partial UNIQUE index lets only one of them stick
                  and choosing between them would be a coin toss.
  id_conflict     the id the CSV assigns is already held by a different live
                  node. Withheld for the same reason.
  overwrite       the node already carries a *different* state_dept_id. Written
                  (the state's master list is authoritative) but always listed.
  stale           a node carrying a state_dept_id that appears nowhere in the
                  CSVs, and that this run did not reassign.

Idempotence
-----------
A node already carrying the id the CSV assigns is left alone, so re-running the
same pair of files writes nothing the second time.

Migration
---------
V37 adds department_location_master_table.state_dept_id and the partial UNIQUE
index this tool respects. It is required — the column is the only thing this
tool writes — and the run stops on the first query if it is missing rather than
halfway through.

  backend/database/V37__add_state_dept_id_to_department_location_table.sql

Usage
-----
  export TENANT_DSN='host=localhost port=5432 dbname=shared_db user=postgres password=postgres'

  # dry run -> analysis workbook only
  python3 "scripts/jjm master data ingestion/dept-master/jjm_dept_master_ingest.py" \
      --divisions-csv "scripts/jjm master data ingestion/dept-master/divisions-master.csv" \
      --subdivisions-csv "scripts/jjm master data ingestion/dept-master/subdivision-master-with-division.csv" \
      --actor-id 21357 \
      --out "scripts/jjm master data ingestion/dept-master/jjm_dept_master_analysis.xlsx"

  # apply
  python3 "scripts/jjm master data ingestion/dept-master/jjm_dept_master_ingest.py" \
      --divisions-csv .../divisions-master.csv \
      --subdivisions-csv .../subdivision-master-with-division.csv \
      --actor-id 21357 --out jjm_dept_master_analysis.xlsx --execute

  # divisions only (the sub-division file is optional)
  python3 ... --divisions-csv .../divisions-master.csv --actor-id 21357 --execute
"""

from __future__ import annotations

import argparse
import logging
import os
import re
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
    import psycopg2.extras
except ImportError:  # pragma: no cover
    sys.exit("psycopg2 is required:  pip install psycopg2-binary")

# The sibling mapping tools own the departmental vocabulary this one reuses:
# the level numbers, the name normalisation, the trailing words a state sheet
# appends to a division ('… Division') and to a sub-division ('… Sub-division'),
# and the outcome constants a reader of those workbooks already knows. Importing
# them is what makes "a node this tool matched" and "a node div-ee-mapping would
# have matched" the same statement, instead of two normalisers that can drift.
_BASE_DIR = os.path.dirname(os.path.abspath(__file__))
_DIV_EE_DIR = os.path.join(_BASE_DIR, os.pardir, "div-ee-mapping")
_SUBDIV_DIR = os.path.join(_BASE_DIR, os.pardir, "subdiv-sdo-mapping")
sys.path.insert(0, _DIV_EE_DIR)
sys.path.insert(0, _SUBDIV_DIR)
try:
    from jjm_division_ee_mapping_ingest import (  # noqa: E402
        BY_STATE_DEPT_ID,
        BY_TITLE,
        BY_TITLE_SUFFIXED,
        DEPT_LEVELS,
        DEPT_REGION_TYPE,
        DIV_AMBIGUOUS,
        DIV_MATCHED,
        DIV_NOT_FOUND,
        TITLE_SUFFIXES,
        DeptNode,
        build_children_index,
        clean,
        norm_name,
        subtree_ids,
        title_core,
    )
    from jjm_subdivision_sdo_mapping_ingest import (  # noqa: E402
        SUB_DIVISION_SUFFIXES,
    )
except ImportError as exc:  # pragma: no cover
    sys.exit(
        f"Cannot import the sibling ingestion modules from {_DIV_EE_DIR!r} / "
        f"{_SUBDIV_DIR!r}: {exc}\n"
        f"Keep this script alongside 'div-ee-mapping/jjm_division_ee_mapping_ingest.py' "
        f"and 'subdiv-sdo-mapping/jjm_subdivision_sdo_mapping_ingest.py', which "
        f"themselves need 'scheme/jjm_scheme_master_ingest.py' and "
        f"'users/jjm_user_master_ingest.py'."
    )


LOG = logging.getLogger("jjm-dept-master-ingest")

# ─────────────────────────────────────────────────────────────────────────────
# Domain constants — mirrored from the Java services. Keep in sync.
# ─────────────────────────────────────────────────────────────────────────────

DIVISION_LEVEL = DEPT_LEVELS["division"]
SUB_DIVISION_LEVEL = DEPT_LEVELS["sub_division"]

DIVISION_CSV_COLUMNS = ["public_id", "division_name"]
SUB_DIVISION_CSV_COLUMNS = [
    "public_id", "subdivision", "division_public_id", "division_name",
]

# Only the schema name is ever interpolated into SQL; everything else is bound.
SAFE_SCHEMA_RE = re.compile(r"^[a-z_][a-z0-9_]*$")

# How a node was matched, on top of the three the mapping tools already report.
BY_TITLE_IN_PARENT = "title (within the division)"
BY_TITLE_SUFFIXED_IN_PARENT = "title within the division (ignoring the 'Sub-division' suffix)"

# How much the hierarchy corroborated a sub-division match. Divisions are always
# PARENT_NA: nothing above a division is named by these files.
PARENT_NA = ""
PARENT_CONFIRMED = "CONFIRMED"          # matched among the division's own children
PARENT_UNRESOLVED = "PARENT_UNRESOLVED"  # the division itself did not resolve
PARENT_MISMATCH = "PARENT_MISMATCH"      # matched elsewhere in the tree

# Why a resolved node's id is not being written.
WITHHELD_CONTESTED = "contested"
WITHHELD_ID_CONFLICT = "id_conflict"
WITHHELD_PARENT_MISMATCH = "parent_mismatch"

# How the write to a resolved node is classified in the summary.
CHANGE_NEW = "SET"          # the node carried no state_dept_id
CHANGE_OVERWRITE = "OVERWRITE"  # it carried a different one
CHANGE_NONE = "ALREADY_SET"  # it already carries exactly this one

# How deep to render a node's ancestry in the reports.
PARENT_PATH_DEPTH = 4


# ─────────────────────────────────────────────────────────────────────────────
# CSV model
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class DeptCsvRow:
    """One line of either master file: a node and the public id the state gives it.

    ``parent_public_id``/``parent_title`` are the division a sub-division sits
    under, and are empty on a division row — a division's own parent (a circle)
    is named by neither file.
    """
    row_no: int                 # 1-based row number as shown in the CSV
    level: int
    public_id: str
    title: str
    parent_public_id: str = ""
    parent_title: str = ""
    issues: list[str] = field(default_factory=list)

    @property
    def blocking_issues(self) -> list[str]:
        """Issues that stop the row being resolved at all."""
        return [i for i in self.issues if i.startswith("row:")]

    @property
    def key(self) -> str:
        """What identifies this node across the file.

        The public id when the state gave one — it is the whole point of these
        files — and the normalised title plus its parent otherwise, so that two
        same-named sub-divisions under different divisions stay two rows.
        """
        if self.public_id:
            return self.public_id.lower()
        return f"{norm_name(self.parent_title)}/{norm_name(self.title)}"

    @property
    def parent_key(self) -> str:
        """The key the parent division's own row would have produced."""
        if self.parent_public_id:
            return self.parent_public_id.lower()
        return f"/{norm_name(self.parent_title)}"


def _load_frame(path: str, header_row: int, encoding: str,
                expected: list[str]) -> list[dict]:
    """Read one master CSV into records, with the columns normalised.

    keep_default_na is off so a public id is never turned into a float or a NaN;
    every cell arrives as text and ``clean`` decides what is blank.
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
    missing = [c for c in expected if c not in frame.columns]
    if missing:
        raise SystemExit(
            f"{path}: missing expected column(s): {', '.join(missing)}\n"
            f"Found: {', '.join(frame.columns)}"
        )
    return frame.to_dict("records")


def load_division_csv(path: str, header_row: int,
                      encoding: str) -> tuple[list[DeptCsvRow], list[dict]]:
    """divisions-master.csv -> one row per division."""
    rows: list[DeptCsvRow] = []
    issues: list[dict] = []

    for offset, raw in enumerate(_load_frame(path, header_row, encoding,
                                             DIVISION_CSV_COLUMNS)):
        # +1 for the header row itself, +1 to make it 1-based like the file.
        row_no = header_row + 1 + offset
        if all(not clean(raw.get(c)) for c in DIVISION_CSV_COLUMNS):
            continue

        row_issues: list[str] = []
        public_id = clean(raw.get("public_id"))
        title = clean(raw.get("division_name"))
        if not public_id:
            row_issues.append("row:blank public_id — there is no id to write")
        if not title:
            row_issues.append("row:blank division_name — nothing to resolve the division by")

        row = DeptCsvRow(
            row_no=row_no, level=DIVISION_LEVEL,
            public_id=public_id, title=title, issues=row_issues,
        )
        rows.append(row)
        issues.extend(_issue_records(row, path))

    return rows, issues


def load_subdivision_csv(path: str, header_row: int,
                         encoding: str) -> tuple[list[DeptCsvRow], list[dict]]:
    """subdivision-master-with-division.csv -> one row per sub-division.

    A blank division is an issue but not a blocking one: the sub-division can
    still be resolved tenant-wide, it simply loses the hierarchy check that
    makes a duplicated name decidable.
    """
    rows: list[DeptCsvRow] = []
    issues: list[dict] = []

    for offset, raw in enumerate(_load_frame(path, header_row, encoding,
                                             SUB_DIVISION_CSV_COLUMNS)):
        row_no = header_row + 1 + offset
        if all(not clean(raw.get(c)) for c in SUB_DIVISION_CSV_COLUMNS):
            continue

        row_issues: list[str] = []
        public_id = clean(raw.get("public_id"))
        title = clean(raw.get("subdivision"))
        parent_public_id = clean(raw.get("division_public_id"))
        parent_title = clean(raw.get("division_name"))
        if not public_id:
            row_issues.append("row:blank public_id — there is no id to write")
        if not title:
            row_issues.append("row:blank subdivision — nothing to resolve the sub-division by")
        if not parent_public_id and not parent_title:
            row_issues.append(
                "hierarchy:blank division — the sub-division is resolved tenant-wide, "
                "so a name shared with another division's sub-division cannot be decided"
            )

        row = DeptCsvRow(
            row_no=row_no, level=SUB_DIVISION_LEVEL,
            public_id=public_id, title=title,
            parent_public_id=parent_public_id, parent_title=parent_title,
            issues=row_issues,
        )
        rows.append(row)
        issues.extend(_issue_records(row, path))

    return rows, issues


def _issue_records(row: DeptCsvRow, path: str) -> list[dict]:
    records = []
    for issue in row.issues:
        kind, _, detail = issue.partition(":")
        records.append({
            "file": os.path.basename(path),
            "row_no": row.row_no,
            "level": _level_label(row.level),
            "public_id": row.public_id,
            "title": row.title,
            "division_public_id": row.parent_public_id,
            "division": row.parent_title,
            "issue_kind": kind,
            "issue": detail,
        })
    return records


def dedupe_rows(rows: list[DeptCsvRow]) -> tuple[list[DeptCsvRow], list[dict]]:
    """Collapse rows the file states twice, keeping the first.

    A public id repeated in one master file is a defect in the file, not a
    second node: the id is unique by construction. Reported, then dropped, so
    the duplicate cannot masquerade as a contested node later on.
    """
    seen: dict[str, DeptCsvRow] = {}
    kept: list[DeptCsvRow] = []
    duplicates: list[dict] = []
    for row in rows:
        if not row.key:
            continue
        first = seen.get(row.key)
        if first is None:
            seen[row.key] = row
            kept.append(row)
            continue
        duplicates.append({
            "level": _level_label(row.level),
            "public_id": row.public_id,
            "title": row.title,
            "row_no": row.row_no,
            "kept_row_no": first.row_no,
            "kept_title": first.title,
            "detail": (
                f"row {row.row_no} repeats {row.public_id or row.key!r}, already stated on "
                f"row {first.row_no} as {first.title!r} — the later row is ignored"
            ),
        })
    return kept, duplicates


def _level_label(level: int) -> str:
    return "sub-division" if level == SUB_DIVISION_LEVEL else "division"


# ─────────────────────────────────────────────────────────────────────────────
# Database
# ─────────────────────────────────────────────────────────────────────────────

class DeptMasterDb:
    """Reads the departmental hierarchy and the schemes hanging off it.

    Deliberately not the mapping tools' DivisionDb: that one extends UserDb and
    so demands the tenant's PII keys to construct, which this tool has no use
    for — it never reads or writes a person. The two queries they do share are
    the same statements, against the same two tables.
    """

    def __init__(self, conn, schema: str) -> None:
        if not SAFE_SCHEMA_RE.match(schema):
            raise ValueError(f"unsafe schema name: {schema!r}")
        self.conn = conn
        self.schema = schema

    def assert_schema_exists(self) -> None:
        with self.conn.cursor() as cur:
            cur.execute(
                "SELECT 1 FROM information_schema.schemata WHERE schema_name = %s",
                (self.schema,),
            )
            if cur.fetchone() is None:
                raise SystemExit(f"schema {self.schema} does not exist in this database")

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
                f"column, which is the only thing this tool writes. Apply "
                f"backend/database/V37__add_state_dept_id_to_department_location_table.sql "
                f"first."
            )

    def load_dept_nodes(self) -> dict[int, DeptNode]:
        """Every live departmental node, with the level its config gives it."""
        nodes: dict[int, DeptNode] = {}
        with self.conn.cursor() as cur:
            cur.execute(f"""
                SELECT d.id, d.title, c.level, d.parent_id, d.state_dept_id
                FROM {self.schema}.department_location_master_table d
                JOIN {self.schema}.location_config_master_table c
                  ON c.id = d.department_location_config_id AND c.region_type = %s
                WHERE d.deleted_at IS NULL
            """, (DEPT_REGION_TYPE,))
            for node_id, title, level, parent_id, state_dept_id in cur:
                nodes[node_id] = DeptNode(node_id, title, level, parent_id, state_dept_id)
        return nodes

    def load_scheme_counts(self) -> dict[int, int]:
        """parent_department_id -> how many live schemes are mapped to it.

        Used only to describe a node the CSVs do not mention: an extra node with
        schemes on it is a gap in the state's master list, one with none is more
        likely a stray. is_active is not filtered — it tracks recent readings,
        not whether the scheme exists.
        """
        counts: dict[int, int] = {}
        with self.conn.cursor() as cur:
            cur.execute(f"""
                SELECT sdm.parent_department_id, COUNT(DISTINCT sdm.scheme_id)
                FROM {self.schema}.scheme_department_mapping_table sdm
                JOIN {self.schema}.scheme_master_table s
                  ON s.id = sdm.scheme_id AND s.deleted_at IS NULL
                WHERE sdm.deleted_at IS NULL
                GROUP BY sdm.parent_department_id
            """)
            for dept_id, count in cur:
                counts[dept_id] = count
        return counts


class DeptMasterWriter:
    """The single write this tool performs.

    Mirrors MappingWriter.backfill_state_dept_ids in div-ee-mapping — same
    statement, same updated_by/updated_at bookkeeping, same `deleted_at IS NULL`
    guard so a node soft-deleted between the analysis and the execute is skipped
    rather than resurrected in effect.
    """

    def __init__(self, db: DeptMasterDb, actor_id: int) -> None:
        self.db = db
        self.conn = db.conn
        self.schema = db.schema
        self.actor_id = actor_id

    def write_state_dept_ids(self, plans: Iterable[NodePlan]) -> int:
        payload = [
            (p.node_id, p.state_dept_id_change[1])
            for p in plans
            if p.writable
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


# ─────────────────────────────────────────────────────────────────────────────
# Resolution
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class NodePlan:
    """One CSV row, what it resolved to, and what would be written."""
    key: str
    level: int
    public_id: str
    title: str
    parent_public_id: str = ""
    parent_title: str = ""
    row_no: int = 0
    category: str = DIV_NOT_FOUND
    matched_by: str = ""
    # Whether our hierarchy corroborates the match. Set from the resolved node's
    # own ancestry, never from which key happened to win, so it says what is
    # true rather than what the search assumed.
    parent_status: str = PARENT_NA
    reason: str = ""
    node_id: Optional[int] = None
    node_title: str = ""
    node_parent_id: Optional[int] = None
    # The division node this row's division_public_id/division_name resolved to,
    # None when it did not resolve. Always set for a sub-division row, even an
    # unresolved one, because "we could not find the sub-division and we could
    # not find its division either" is a different finding from "we have the
    # division but not this child of it".
    parent_node_id: Optional[int] = None
    # Every node the winning key matched: one on a clean match, several on an
    # AMBIGUOUS one, which is what lets the report name the rivals.
    candidate_ids: list[int] = field(default_factory=list)
    existing_state_dept_id: Optional[str] = None
    # (old, new) when the CSV's public_id should be written to state_dept_id.
    state_dept_id_change: Optional[tuple[Optional[str], str]] = None
    withheld: dict[str, str] = field(default_factory=dict)

    @property
    def resolved(self) -> bool:
        return self.category == DIV_MATCHED and self.node_id is not None

    @property
    def writable(self) -> bool:
        return self.resolved and self.state_dept_id_change is not None

    @property
    def change_kind(self) -> str:
        """SET / OVERWRITE / ALREADY_SET, for the summary."""
        if not self.resolved:
            return ""
        existing = self.existing_state_dept_id or ""
        if existing == self.public_id:
            return CHANGE_NONE
        return CHANGE_OVERWRITE if existing else CHANGE_NEW

    @property
    def level_label(self) -> str:
        return _level_label(self.level)


@dataclass
class TitleIndex:
    """Live nodes at one level, indexed by the two title keys we match on."""
    by_title: dict[str, list[int]] = field(default_factory=lambda: defaultdict(list))
    by_core: dict[str, list[int]] = field(default_factory=lambda: defaultdict(list))
    ids: list[int] = field(default_factory=list)


def build_title_index(nodes: dict[int, DeptNode], level: int,
                      suffixes: Iterable[str],
                      only: Optional[Iterable[int]] = None) -> TitleIndex:
    """Index the level's nodes — optionally only those in ``only`` — by title.

    ``only`` is how a sub-division is searched inside its division: the same
    index code serves the tenant-wide pass and the subtree-restricted one.
    """
    scope = set(only) if only is not None else None
    index = TitleIndex()
    for node in nodes.values():
        if node.level != level:
            continue
        if scope is not None and node.id not in scope:
            continue
        index.ids.append(node.id)
        index.by_title[norm_name(node.title)].append(node.id)
        index.by_core[title_core(node.title, suffixes)].append(node.id)
    return index


def build_state_id_index(nodes: dict[int, DeptNode], level: int) -> dict[str, list[int]]:
    """lower(state_dept_id) -> live nodes at this level already carrying it."""
    index: dict[str, list[int]] = defaultdict(list)
    for node in nodes.values():
        if node.level == level and node.state_dept_id:
            index[node.state_dept_id.strip().lower()].append(node.id)
    return index


def _apply_attempt(plan: NodePlan, nodes: dict[int, DeptNode],
                   matched_by: str, candidates: list[int], label: str) -> bool:
    """Record one resolution attempt. Returns True when the search should stop.

    An ambiguous hit stops the search rather than falling through to a weaker
    key that might look decisive but is not — the same rule the mapping tools
    apply, and the reason a duplicated sub-division name is skipped instead of
    being pinned on whichever node sorts first.
    """
    if not candidates:
        return False
    plan.candidate_ids = sorted(candidates)
    if len(candidates) > 1:
        plan.category = DIV_AMBIGUOUS
        plan.matched_by = matched_by
        plan.reason = (
            f"{matched_by} matches {len(candidates)} departmental nodes "
            f"({', '.join(f'id {c} {nodes[c].title!r}' for c in sorted(candidates)[:5])}"
            f"{'…' if len(candidates) > 5 else ''}) — cannot tell which {label} is meant"
        )
        return True
    plan.category = DIV_MATCHED
    plan.matched_by = matched_by
    plan.node_id = candidates[0]
    plan.reason = f"matched on {matched_by}"
    return True


def hierarchy_status(node_id: int, parent_node_id: Optional[int],
                     nodes: dict[int, DeptNode]) -> str:
    """Does our tree actually put ``node_id`` beneath ``parent_node_id``?

    Answered from the node's own ancestry rather than from which key won the
    search, so a match on state_dept_id is held to the same standard as one on a
    title. Walks the whole chain, not just the immediate parent, so a tenant
    that nests an extra rung between division and sub-division still reads as
    confirmed. Cycle-guarded: parent_id is a plain self-FK.
    """
    if parent_node_id is None:
        return PARENT_UNRESOLVED
    seen: set[int] = set()
    current: Optional[int] = node_id
    while current is not None and current in nodes and current not in seen:
        if current == parent_node_id:
            return PARENT_CONFIRMED
        seen.add(current)
        current = nodes[current].parent_id
    return PARENT_MISMATCH


def resolve_divisions(rows: list[DeptCsvRow], nodes: dict[int, DeptNode],
                      match_on_id: bool) -> dict[str, NodePlan]:
    """One plan per division the master file names."""
    by_state_id = build_state_id_index(nodes, DIVISION_LEVEL)
    index = build_title_index(nodes, DIVISION_LEVEL, TITLE_SUFFIXES)

    plans: dict[str, NodePlan] = {}
    for row in rows:
        plan = _new_plan(row)
        plans[plan.key] = plan
        if row.blocking_issues:
            plan.reason = "; ".join(i.partition(":")[2] for i in row.blocking_issues)
            continue

        attempts: list[tuple[str, list[int]]] = []
        if match_on_id and row.public_id:
            attempts.append((BY_STATE_DEPT_ID, by_state_id.get(row.public_id.lower(), [])))
        if row.title:
            attempts.append((BY_TITLE, index.by_title.get(norm_name(row.title), [])))
            attempts.append((
                BY_TITLE_SUFFIXED,
                index.by_core.get(title_core(row.title, TITLE_SUFFIXES), []),
            ))

        for matched_by, candidates in attempts:
            if _apply_attempt(plan, nodes, matched_by, candidates, "division"):
                break
        else:
            plan.reason = (
                f"no live node at department level {DIVISION_LEVEL} matches {row.title!r}"
                + (f" or state_dept_id {row.public_id}" if match_on_id and row.public_id else "")
            )

    return plans


def resolve_subdivisions(rows: list[DeptCsvRow], nodes: dict[int, DeptNode],
                         division_plans: dict[str, NodePlan],
                         match_on_id: bool) -> dict[str, NodePlan]:
    """One plan per sub-division, searched inside its division before tenant-wide.

    The division is taken from the division master file's result where the id
    matches, so the two files are resolved as one dataset rather than twice
    over; a sub-division naming a division that file does not carry falls back
    to resolving that division by name here.
    """
    by_state_id = build_state_id_index(nodes, SUB_DIVISION_LEVEL)
    tenant_wide = build_title_index(nodes, SUB_DIVISION_LEVEL, SUB_DIVISION_SUFFIXES)
    children = build_children_index(nodes)
    division_titles = build_title_index(nodes, DIVISION_LEVEL, TITLE_SUFFIXES)

    # One index per division node, built once and shared by its sub-divisions.
    subtree_cache: dict[int, TitleIndex] = {}

    plans: dict[str, NodePlan] = {}
    for row in rows:
        plan = _new_plan(row)
        plans[plan.key] = plan
        if row.blocking_issues:
            plan.reason = "; ".join(i.partition(":")[2] for i in row.blocking_issues)
            continue

        parent_node_id = _resolve_parent_division(
            row, division_plans, division_titles
        )
        plan.parent_node_id = parent_node_id
        attempts: list[tuple[str, list[int]]] = []

        if match_on_id and row.public_id:
            attempts.append((BY_STATE_DEPT_ID, by_state_id.get(row.public_id.lower(), [])))

        if row.title and parent_node_id is not None:
            index = subtree_cache.get(parent_node_id)
            if index is None:
                index = build_title_index(
                    nodes, SUB_DIVISION_LEVEL, SUB_DIVISION_SUFFIXES,
                    only=subtree_ids(parent_node_id, children),
                )
                subtree_cache[parent_node_id] = index
            attempts.append((
                BY_TITLE_IN_PARENT, index.by_title.get(norm_name(row.title), []),
            ))
            attempts.append((
                BY_TITLE_SUFFIXED_IN_PARENT,
                index.by_core.get(title_core(row.title, SUB_DIVISION_SUFFIXES), []),
            ))

        if row.title:
            # Our hierarchy and the state's need not agree, so the search widens
            # to the whole tenant. Whether that match is trustworthy is decided
            # afterwards, from the node's real ancestry.
            attempts.append((
                BY_TITLE, tenant_wide.by_title.get(norm_name(row.title), []),
            ))
            attempts.append((
                BY_TITLE_SUFFIXED,
                tenant_wide.by_core.get(title_core(row.title, SUB_DIVISION_SUFFIXES), []),
            ))

        for matched_by, candidates in attempts:
            if _apply_attempt(plan, nodes, matched_by, candidates, "sub-division"):
                break
        else:
            plan.reason = (
                f"no live node at department level {SUB_DIVISION_LEVEL} matches "
                f"{row.title!r}"
                + (f" under division {row.parent_title!r}"
                   if parent_node_id is not None else
                   f" (and division {row.parent_title!r} did not resolve either)")
                + (f", nor state_dept_id {row.public_id}"
                   if match_on_id and row.public_id else "")
            )

        if plan.resolved:
            plan.parent_status = hierarchy_status(plan.node_id, parent_node_id, nodes)
            if plan.parent_status == PARENT_MISMATCH:
                actual = nodes[plan.node_id].parent_id
                actual_title = nodes[actual].title if actual in nodes else "?"
                plan.reason += (
                    f"; our hierarchy puts it under {actual_title!r} (node id {actual}), "
                    f"not under {row.parent_title!r} (node id {parent_node_id})"
                )

    return plans


def _new_plan(row: DeptCsvRow) -> NodePlan:
    return NodePlan(
        key=row.key, level=row.level, public_id=row.public_id, title=row.title,
        parent_public_id=row.parent_public_id, parent_title=row.parent_title,
        row_no=row.row_no,
    )


def _resolve_parent_division(row: DeptCsvRow, division_plans: dict[str, NodePlan],
                             division_titles: TitleIndex) -> Optional[int]:
    """Which node is the division this sub-division row names, if any.

    Prefers the division master file's own result — the two files agree on ids,
    so this is nearly always the answer — and only resolves the division by name
    when that file did not carry it.
    """
    plan = division_plans.get(row.parent_key)
    if plan is not None and plan.resolved:
        return plan.node_id
    if not row.parent_title:
        return None
    for candidates in (
        division_titles.by_title.get(norm_name(row.parent_title), []),
        division_titles.by_core.get(title_core(row.parent_title, TITLE_SUFFIXES), []),
    ):
        if len(candidates) == 1:
            return candidates[0]
        if candidates:
            return None      # ambiguous division: no usable subtree
    return None


# ─────────────────────────────────────────────────────────────────────────────
# What gets written
# ─────────────────────────────────────────────────────────────────────────────

def plan_writes(plans: Iterable[NodePlan], nodes: dict[int, DeptNode],
                accept_parent_mismatch: bool) -> None:
    """Decide, for every resolved plan, whether its id is written or withheld.

    Three things can stop a write, and all three are reported rather than
    aborting the run:

      * two CSV rows resolved to one node — V37's partial UNIQUE index lets only
        one id stick, and picking between them would be arbitrary;
      * the id already belongs to a different live node, which that index would
        reject outright;
      * the match rests on a tenant-wide title while our hierarchy places the
        node under a different division.
    """
    plans = list(plans)

    # lower(code) -> the live node already holding it, across every level: the
    # unique index V37 creates is not scoped to a level either.
    owners: dict[str, int] = {}
    for node in nodes.values():
        if node.state_dept_id:
            owners.setdefault(node.state_dept_id.strip().lower(), node.id)

    by_node: dict[int, list[NodePlan]] = defaultdict(list)
    for plan in plans:
        if plan.resolved:
            by_node[plan.node_id].append(plan)

    for plan in plans:
        if not plan.resolved:
            continue
        node = nodes[plan.node_id]
        plan.node_title = node.title
        plan.node_parent_id = node.parent_id
        plan.existing_state_dept_id = node.state_dept_id

        if not plan.public_id:
            continue
        if (node.state_dept_id or "") == plan.public_id:
            continue        # already correct — a re-run writes nothing

        contenders = by_node[plan.node_id]
        codes = {p.public_id for p in contenders if p.public_id}
        if len(contenders) > 1 and len(codes) > 1:
            plan.withheld[WITHHELD_CONTESTED] = (
                f"the CSVs give departmental node id {plan.node_id} more than one "
                f"public_id ({', '.join(sorted(codes))}) — none is written"
            )
            continue

        owner = owners.get(plan.public_id.strip().lower())
        if owner is not None and owner != plan.node_id:
            plan.withheld[WITHHELD_ID_CONFLICT] = (
                f"public_id {plan.public_id} already belongs to departmental node "
                f"id {owner} {nodes[owner].title!r}"
            )
            continue

        if plan.parent_status == PARENT_MISMATCH and not accept_parent_mismatch:
            plan.withheld[WITHHELD_PARENT_MISMATCH] = (
                "matched only on a tenant-wide title while our hierarchy places the "
                "node under another division — pass --accept-parent-mismatch to "
                "write it anyway"
            )
            continue

        plan.state_dept_id_change = (node.state_dept_id, plan.public_id)


@dataclass
class DeptMasterPlan:
    """Everything one run decided, in the order the workbook reports it."""
    schema: str
    division_plans: dict[str, NodePlan]
    subdivision_plans: dict[str, NodePlan]
    nodes: dict[int, DeptNode]
    scheme_counts: dict[int, int]
    csv_issues: list[dict] = field(default_factory=list)
    csv_duplicates: list[dict] = field(default_factory=list)
    executed: dict[str, int] = field(default_factory=dict)

    @property
    def all_plans(self) -> list[NodePlan]:
        return list(self.division_plans.values()) + list(self.subdivision_plans.values())

    @property
    def writable(self) -> list[NodePlan]:
        return [p for p in self.all_plans if p.writable]

    @property
    def unresolved(self) -> list[NodePlan]:
        return [p for p in self.all_plans if not p.resolved]

    @property
    def matched_node_ids(self) -> set[int]:
        return {p.node_id for p in self.all_plans if p.resolved}

    def extra_db_nodes(self) -> list[DeptNode]:
        """Live level-4/5 nodes no CSV row resolved to, deepest level first."""
        matched = self.matched_node_ids
        extras = [
            n for n in self.nodes.values()
            if n.level in (DIVISION_LEVEL, SUB_DIVISION_LEVEL) and n.id not in matched
        ]
        return sorted(extras, key=lambda n: (-n.level, norm_name(n.title), n.id))

    def stale_state_dept_ids(self) -> list[DeptNode]:
        """Nodes carrying an id that appears in neither master file.

        A node this run is about to relabel is not stale — it is being corrected
        — so the ids this run writes are excluded as well as the ones the CSVs
        name.
        """
        csv_codes = {p.public_id.lower() for p in self.all_plans if p.public_id}
        rewritten = {p.node_id for p in self.writable}
        return sorted(
            (
                n for n in self.nodes.values()
                if n.state_dept_id
                and n.state_dept_id.strip().lower() not in csv_codes
                and n.id not in rewritten
            ),
            key=lambda n: (-n.level, n.state_dept_id or "", n.id),
        )

    def subtree_scheme_count(self, node_id: int) -> int:
        children = build_children_index(self.nodes)
        return sum(self.scheme_counts.get(n, 0) for n in subtree_ids(node_id, children))


def build_plan(division_rows: list[DeptCsvRow], subdivision_rows: list[DeptCsvRow],
               db: DeptMasterDb, match_on_id: bool,
               accept_parent_mismatch: bool) -> DeptMasterPlan:
    LOG.info("  loading the departmental hierarchy from %s …", db.schema)
    nodes = db.load_dept_nodes()
    LOG.info(
        "    %d live node(s): %d division(s), %d sub-division(s)",
        len(nodes),
        sum(1 for n in nodes.values() if n.level == DIVISION_LEVEL),
        sum(1 for n in nodes.values() if n.level == SUB_DIVISION_LEVEL),
    )

    LOG.info("  resolving %d division(s) …", len(division_rows))
    division_plans = resolve_divisions(division_rows, nodes, match_on_id)
    LOG.info("  resolving %d sub-division(s) …", len(subdivision_rows))
    subdivision_plans = resolve_subdivisions(
        subdivision_rows, nodes, division_plans, match_on_id
    )

    plan = DeptMasterPlan(
        schema=db.schema,
        division_plans=division_plans,
        subdivision_plans=subdivision_plans,
        nodes=nodes,
        scheme_counts=db.load_scheme_counts(),
    )
    plan_writes(plan.all_plans, nodes, accept_parent_mismatch)
    return plan


# ─────────────────────────────────────────────────────────────────────────────
# Reporting
# ─────────────────────────────────────────────────────────────────────────────

def node_path(node_id: Optional[int], nodes: dict[int, DeptNode],
              depth: int = PARENT_PATH_DEPTH) -> str:
    """'Zone > Circle > Division > Sub-division', as far up as depth allows.

    Cycle-guarded: a parent chain that loops would otherwise never terminate,
    and parent_id is a plain self-FK with nothing forbidding one.
    """
    if node_id is None or node_id not in nodes:
        return ""
    chain: list[str] = []
    seen: set[int] = set()
    current: Optional[int] = node_id
    while current is not None and current in nodes and len(chain) < depth:
        if current in seen:
            chain.append("…(cycle)")
            break
        seen.add(current)
        chain.append(nodes[current].title)
        current = nodes[current].parent_id
    return " > ".join(reversed(chain))


def _fmt_withheld(withheld: dict[str, str]) -> str:
    return "; ".join(f"{k}: {v}" for k, v in sorted(withheld.items()))


def _candidates(plan: NodePlan, nodes: dict[int, DeptNode]) -> str:
    """Every node a row matched, with ancestry — how a human breaks the tie."""
    return " | ".join(
        f"id {c} {nodes[c].title!r} ({node_path(c, nodes)})"
        for c in plan.candidate_ids if c in nodes
    )


def build_summary_frame(plan: DeptMasterPlan) -> pd.DataFrame:
    rows: list[dict] = []

    def add(section: str, metric: str, value: Any, note: str = "") -> None:
        rows.append({"section": section, "metric": metric, "value": value, "note": note})

    for level, plans, label in (
        (DIVISION_LEVEL, plan.division_plans.values(), "divisions"),
        (SUB_DIVISION_LEVEL, plan.subdivision_plans.values(), "sub-divisions"),
    ):
        plans = list(plans)
        db_total = sum(1 for n in plan.nodes.values() if n.level == level)
        add(label, "rows in the CSV", len(plans))
        add(label, "live nodes in the tenant", db_total)
        add(label, "resolved", sum(1 for p in plans if p.resolved))
        add(label, "unresolved: NOT_FOUND", sum(1 for p in plans if p.category == DIV_NOT_FOUND),
            "named by the state, missing from our hierarchy — see extra_in_csv")
        add(label, "unresolved: AMBIGUOUS", sum(1 for p in plans if p.category == DIV_AMBIGUOUS),
            "matched several nodes; never guessed — see ambiguous")
        add(label, "state_dept_id to SET",
            sum(1 for p in plans if p.writable and p.change_kind == CHANGE_NEW))
        add(label, "state_dept_id to OVERWRITE",
            sum(1 for p in plans if p.writable and p.change_kind == CHANGE_OVERWRITE),
            "the node already carried a different id — see overwrites")
        add(label, "already correct",
            sum(1 for p in plans if p.resolved and p.change_kind == CHANGE_NONE),
            "left alone; a re-run of the same file writes nothing")
        add(label, "withheld", sum(1 for p in plans if p.resolved and p.withheld),
            "resolved but not written — see conflicts")
        add(label, "extra in our DB",
            sum(1 for n in plan.extra_db_nodes() if n.level == level),
            "live nodes no CSV row resolved to — see extra_in_db")

    subs = list(plan.subdivision_plans.values())
    add("hierarchy", "sub-divisions confirmed under their division",
        sum(1 for p in subs if p.resolved and p.parent_status == PARENT_CONFIRMED))
    add("hierarchy", "sub-divisions matched with the division unresolved",
        sum(1 for p in subs if p.resolved and p.parent_status == PARENT_UNRESOLVED),
        "written, but the hierarchy could not corroborate it — see hierarchy_review")
    add("hierarchy", "sub-divisions our hierarchy places elsewhere",
        sum(1 for p in subs if p.resolved and p.parent_status == PARENT_MISMATCH),
        "withheld unless --accept-parent-mismatch — see hierarchy_review")

    add("totals", "rows to write", len(plan.writable))
    add("totals", "stale state_dept_ids in the tenant", len(plan.stale_state_dept_ids()),
        "ids on our nodes that neither master file names — see stale_ids")
    add("totals", "CSV rows with issues", len(plan.csv_issues))
    add("totals", "duplicate CSV rows dropped", len(plan.csv_duplicates))
    for action, count in sorted(plan.executed.items()):
        add("executed", action, count)

    return pd.DataFrame.from_records(rows)


def build_node_frame(plans: Iterable[NodePlan], nodes: dict[int, DeptNode]) -> pd.DataFrame:
    records = [{
        "row_no": p.row_no,
        "level": p.level_label,
        "public_id": p.public_id,
        "csv_title": p.title,
        "csv_division": p.parent_title,
        "outcome": p.category,
        "matched_by": p.matched_by,
        "hierarchy": p.parent_status,
        "node_id": p.node_id,
        "node_title": p.node_title,
        "node_path": node_path(p.node_id, nodes),
        "existing_state_dept_id": p.existing_state_dept_id,
        "change": p.change_kind,
        "will_write": "yes" if p.writable else "no",
        "withheld": _fmt_withheld(p.withheld),
        "candidates": _candidates(p, nodes) if p.category == DIV_AMBIGUOUS else "",
        "reason": p.reason,
    } for p in plans]
    columns = [
        "row_no", "level", "public_id", "csv_title", "csv_division", "outcome",
        "matched_by", "hierarchy", "node_id", "node_title", "node_path",
        "existing_state_dept_id", "change", "will_write", "withheld",
        "candidates", "reason",
    ]
    frame = pd.DataFrame.from_records(records, columns=columns)
    return frame.sort_values(["level", "row_no"]) if not frame.empty else frame


def build_extra_in_csv_frame(plan: DeptMasterPlan) -> pd.DataFrame:
    """What the state names and we do not have."""
    records = [{
        "row_no": p.row_no,
        "level": p.level_label,
        "public_id": p.public_id,
        "csv_title": p.title,
        "csv_division": p.parent_title,
        "division_resolved": (
            "" if p.level == DIVISION_LEVEL
            else ("yes" if p.parent_node_id is not None else "no")
        ),
        "reason": p.reason,
    } for p in plan.all_plans if p.category == DIV_NOT_FOUND]
    return pd.DataFrame.from_records(records, columns=[
        "row_no", "level", "public_id", "csv_title", "csv_division",
        "division_resolved", "reason",
    ])


def build_extra_in_db_frame(plan: DeptMasterPlan) -> pd.DataFrame:
    """What we have and the state does not name.

    The scheme counts are the point of this sheet: a sub-division carrying forty
    schemes and no CSV row is a hole in the state's master list worth chasing,
    whereas one carrying none is more likely a node somebody created by hand.
    """
    children = build_children_index(plan.nodes)
    records = []
    for node in plan.extra_db_nodes():
        direct = plan.scheme_counts.get(node.id, 0)
        subtree = sum(plan.scheme_counts.get(n, 0) for n in subtree_ids(node.id, children))
        records.append({
            "node_id": node.id,
            "level": _level_label(node.level),
            "title": node.title,
            "node_path": node_path(node.id, plan.nodes),
            "parent_id": node.parent_id,
            "existing_state_dept_id": node.state_dept_id,
            "schemes_direct": direct,
            "schemes_subtree": subtree,
            "child_nodes": len(subtree_ids(node.id, children)) - 1,
            "note": (
                "carries schemes — likely missing from the state's master list"
                if subtree else "no schemes anywhere beneath it"
            ),
        })
    return pd.DataFrame.from_records(records, columns=[
        "node_id", "level", "title", "node_path", "parent_id",
        "existing_state_dept_id", "schemes_direct", "schemes_subtree",
        "child_nodes", "note",
    ])


def build_ambiguous_frame(plan: DeptMasterPlan) -> pd.DataFrame:
    records = [{
        "row_no": p.row_no,
        "level": p.level_label,
        "public_id": p.public_id,
        "csv_title": p.title,
        "csv_division": p.parent_title,
        "matched_by": p.matched_by,
        "candidate_count": len(p.candidate_ids),
        "candidates": _candidates(p, plan.nodes),
        "reason": p.reason,
    } for p in plan.all_plans if p.category == DIV_AMBIGUOUS]
    return pd.DataFrame.from_records(records, columns=[
        "row_no", "level", "public_id", "csv_title", "csv_division", "matched_by",
        "candidate_count", "candidates", "reason",
    ])


def build_conflict_frame(plan: DeptMasterPlan) -> pd.DataFrame:
    """Every resolved row whose id is not being written, and why."""
    records = [{
        "row_no": p.row_no,
        "level": p.level_label,
        "public_id": p.public_id,
        "csv_title": p.title,
        "csv_division": p.parent_title,
        "node_id": p.node_id,
        "node_title": p.node_title,
        "node_path": node_path(p.node_id, plan.nodes),
        "existing_state_dept_id": p.existing_state_dept_id,
        "kind": ", ".join(sorted(p.withheld)),
        "detail": _fmt_withheld(p.withheld),
    } for p in plan.all_plans if p.resolved and p.withheld]
    return pd.DataFrame.from_records(records, columns=[
        "row_no", "level", "public_id", "csv_title", "csv_division", "node_id",
        "node_title", "node_path", "existing_state_dept_id", "kind", "detail",
    ])


def build_overwrite_frame(plan: DeptMasterPlan) -> pd.DataFrame:
    """Nodes whose existing id this run replaces. Always read before executing."""
    records = [{
        "row_no": p.row_no,
        "level": p.level_label,
        "node_id": p.node_id,
        "node_title": p.node_title,
        "node_path": node_path(p.node_id, plan.nodes),
        "old_state_dept_id": p.state_dept_id_change[0],
        "new_state_dept_id": p.state_dept_id_change[1],
        "matched_by": p.matched_by,
        "hierarchy": p.parent_status,
    } for p in plan.writable if p.change_kind == CHANGE_OVERWRITE]
    return pd.DataFrame.from_records(records, columns=[
        "row_no", "level", "node_id", "node_title", "node_path",
        "old_state_dept_id", "new_state_dept_id", "matched_by", "hierarchy",
    ])


def build_hierarchy_review_frame(plan: DeptMasterPlan) -> pd.DataFrame:
    """Sub-divisions the hierarchy could not confirm, both flavours."""
    records = []
    for p in plan.subdivision_plans.values():
        if not p.resolved or p.parent_status in (PARENT_NA, PARENT_CONFIRMED):
            continue
        actual_parent = plan.nodes[p.node_id].parent_id if p.node_id in plan.nodes else None
        records.append({
            "row_no": p.row_no,
            "public_id": p.public_id,
            "csv_title": p.title,
            "csv_division": p.parent_title,
            "csv_division_public_id": p.parent_public_id,
            "hierarchy": p.parent_status,
            "node_id": p.node_id,
            "node_title": p.node_title,
            "our_division": (
                plan.nodes[actual_parent].title if actual_parent in plan.nodes else ""
            ),
            "node_path": node_path(p.node_id, plan.nodes),
            "will_write": "yes" if p.writable else "no",
            "reason": p.reason,
        })
    return pd.DataFrame.from_records(records, columns=[
        "row_no", "public_id", "csv_title", "csv_division", "csv_division_public_id",
        "hierarchy", "node_id", "node_title", "our_division", "node_path",
        "will_write", "reason",
    ])


def build_stale_frame(plan: DeptMasterPlan) -> pd.DataFrame:
    records = [{
        "node_id": n.id,
        "level": _level_label(n.level) if n.level in (DIVISION_LEVEL, SUB_DIVISION_LEVEL)
        else f"level {n.level}",
        "title": n.title,
        "node_path": node_path(n.id, plan.nodes),
        "state_dept_id": n.state_dept_id,
        "note": "neither master file names this id; this run does not change it",
    } for n in plan.stale_state_dept_ids()]
    return pd.DataFrame.from_records(records, columns=[
        "node_id", "level", "title", "node_path", "state_dept_id", "note",
    ])


def write_analysis_workbook(plan: DeptMasterPlan, path: str, context: dict) -> None:
    run_info = pd.DataFrame.from_records(
        [{"setting": k, "value": str(v)} for k, v in context.items()]
    )
    issues = pd.DataFrame.from_records(plan.csv_issues) if plan.csv_issues else pd.DataFrame(
        columns=["file", "row_no", "level", "public_id", "title",
                 "division_public_id", "division", "issue_kind", "issue"]
    )
    duplicates = pd.DataFrame.from_records(plan.csv_duplicates) if plan.csv_duplicates \
        else pd.DataFrame(columns=["level", "public_id", "title", "row_no",
                                   "kept_row_no", "kept_title", "detail"])

    with pd.ExcelWriter(path, engine="openpyxl") as writer:
        run_info.to_excel(writer, sheet_name="run_info", index=False)
        build_summary_frame(plan).to_excel(writer, sheet_name="summary", index=False)
        build_node_frame(plan.division_plans.values(), plan.nodes).to_excel(
            writer, sheet_name="division_detail", index=False)
        build_node_frame(plan.subdivision_plans.values(), plan.nodes).to_excel(
            writer, sheet_name="subdivision_detail", index=False)
        build_extra_in_csv_frame(plan).to_excel(
            writer, sheet_name="extra_in_csv", index=False)
        build_extra_in_db_frame(plan).to_excel(
            writer, sheet_name="extra_in_db", index=False)
        build_ambiguous_frame(plan).to_excel(writer, sheet_name="ambiguous", index=False)
        build_hierarchy_review_frame(plan).to_excel(
            writer, sheet_name="hierarchy_review", index=False)
        build_conflict_frame(plan).to_excel(writer, sheet_name="conflicts", index=False)
        build_overwrite_frame(plan).to_excel(writer, sheet_name="overwrites", index=False)
        build_stale_frame(plan).to_excel(writer, sheet_name="stale_ids", index=False)
        duplicates.to_excel(writer, sheet_name="csv_duplicates", index=False)
        issues.to_excel(writer, sheet_name="csv_issues", index=False)

    LOG.info("Analysis workbook written to %s", path)


# ─────────────────────────────────────────────────────────────────────────────
# Execute
# ─────────────────────────────────────────────────────────────────────────────

def execute_tenant(plan: DeptMasterPlan, writer: DeptMasterWriter) -> dict[str, int]:
    """Apply every state_dept_id in one transaction.

    One statement, so the transaction is a formality — but it is the boundary
    that makes a partially applied master list impossible if the connection
    drops mid-write.
    """
    counts = {"state_dept_id_written": writer.write_state_dept_ids(plan.writable)}
    writer.conn.commit()
    plan.executed = counts
    return counts


# ─────────────────────────────────────────────────────────────────────────────
# CLI
# ─────────────────────────────────────────────────────────────────────────────

def parse_args(argv: Optional[list[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Stamp the state's departmental public ids onto "
                    "department_location_master_table.state_dept_id for a Jal Soochak tenant.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__.split("Usage")[-1],
    )
    parser.add_argument("--divisions-csv", required=True,
                        help="path to divisions-master.csv")
    parser.add_argument("--subdivisions-csv", default=None,
                        help="path to subdivision-master-with-division.csv; omit to "
                             "reconcile divisions only")
    parser.add_argument("--header-row", type=int, default=2,
                        help="1-based row holding the column headers in both files "
                             "(default: 2 — the state's export puts a title line above)")
    parser.add_argument("--encoding", default="utf-8-sig",
                        help="CSV encoding (default: utf-8-sig)")
    parser.add_argument("--schema", default="tenant_as",
                        help="tenant schema (default: tenant_as)")
    parser.add_argument("--actor-id", type=int, required=True,
                        help="user_table.id recorded as updated_by")
    parser.add_argument("--out", default="jjm_dept_master_analysis.xlsx",
                        help="analysis workbook path (default: jjm_dept_master_analysis.xlsx)")
    parser.add_argument("--tenant-dsn", default=os.environ.get("TENANT_DSN"),
                        help="tenant DB DSN (default: $TENANT_DSN)")
    parser.add_argument("--execute", action="store_true",
                        help="apply the plan; without this the run is read-only")
    parser.add_argument("--accept-parent-mismatch", action="store_true",
                        help="also write the id of a sub-division that matched only on a "
                             "tenant-wide title while our hierarchy places it under a "
                             "different division. Withheld by default — read the "
                             "hierarchy_review sheet first")
    parser.add_argument("--no-match-on-id", action="store_true",
                        help="ignore state_dept_id when resolving, matching on titles "
                             "only. Use to re-derive the whole mapping from names after "
                             "a bad run")
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
    if not SAFE_SCHEMA_RE.match(args.schema):
        return _fail(f"--schema {args.schema!r} is not a valid schema name")

    LOG.info("Reading %s …", args.divisions_csv)
    division_rows, csv_issues = load_division_csv(
        args.divisions_csv, args.header_row, args.encoding
    )
    division_rows, csv_duplicates = dedupe_rows(division_rows)
    LOG.info("  %d division(s)", len(division_rows))

    subdivision_rows: list[DeptCsvRow] = []
    if args.subdivisions_csv:
        LOG.info("Reading %s …", args.subdivisions_csv)
        subdivision_rows, sub_issues = load_subdivision_csv(
            args.subdivisions_csv, args.header_row, args.encoding
        )
        subdivision_rows, sub_duplicates = dedupe_rows(subdivision_rows)
        csv_issues.extend(sub_issues)
        csv_duplicates.extend(sub_duplicates)
        LOG.info("  %d sub-division(s)", len(subdivision_rows))
    else:
        LOG.warning(
            "No --subdivisions-csv given: only divisions are reconciled, and every "
            "live sub-division node will be listed as extra_in_db."
        )

    conn = psycopg2.connect(args.tenant_dsn)
    conn.autocommit = False
    try:
        db = DeptMasterDb(conn, args.schema)
        db.assert_schema_exists()
        db.assert_state_dept_id_column()

        plan = build_plan(
            division_rows, subdivision_rows, db,
            match_on_id=not args.no_match_on_id,
            accept_parent_mismatch=args.accept_parent_mismatch,
        )
        plan.csv_issues = csv_issues
        plan.csv_duplicates = csv_duplicates

        if args.execute:
            LOG.info("Executing: %d state_dept_id(s) to write …", len(plan.writable))
            counts = execute_tenant(plan, DeptMasterWriter(db, args.actor_id))
            LOG.info("  %s", ", ".join(f"{k}={v}" for k, v in sorted(counts.items())))
        else:
            # Nothing above wrote, but a rollback makes that explicit rather
            # than leaving an idle transaction open on the connection.
            conn.rollback()
            LOG.info("Analyze only — no changes made. Re-run with --execute to apply.")

        write_analysis_workbook(plan, args.out, {
            "generated_at": datetime.now().isoformat(timespec="seconds"),
            "divisions_csv": args.divisions_csv,
            "subdivisions_csv": args.subdivisions_csv or "(none)",
            "schema": args.schema,
            "actor_id": args.actor_id,
            "mode": "execute" if args.execute else "analyze",
            "match_on_state_dept_id": not args.no_match_on_id,
            "accept_parent_mismatch": args.accept_parent_mismatch,
            "division_rows": len(division_rows),
            "subdivision_rows": len(subdivision_rows),
        })
        _print_summary(plan)
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()

    return 0


def _fail(message: str) -> int:
    LOG.error(message)
    return 2


def _print_summary(plan: DeptMasterPlan) -> None:
    for label, plans in (
        ("divisions", plan.division_plans.values()),
        ("sub-divisions", plan.subdivision_plans.values()),
    ):
        plans = list(plans)
        if not plans:
            continue
        LOG.info(
            "%-14s %d row(s): %d resolved, %d not found, %d ambiguous, %d to write",
            label + ":", len(plans),
            sum(1 for p in plans if p.resolved),
            sum(1 for p in plans if p.category == DIV_NOT_FOUND),
            sum(1 for p in plans if p.category == DIV_AMBIGUOUS),
            sum(1 for p in plans if p.writable),
        )
    extras = plan.extra_db_nodes()
    if extras:
        LOG.warning(
            "%d live node(s) in %s are named by neither master file "
            "(%d division(s), %d sub-division(s)) — see the extra_in_db sheet",
            len(extras), plan.schema,
            sum(1 for n in extras if n.level == DIVISION_LEVEL),
            sum(1 for n in extras if n.level == SUB_DIVISION_LEVEL),
        )
    withheld = [p for p in plan.all_plans if p.resolved and p.withheld]
    if withheld:
        LOG.warning(
            "%d resolved row(s) are not written — see the conflicts sheet", len(withheld)
        )
    stale = plan.stale_state_dept_ids()
    if stale:
        LOG.warning(
            "%d node(s) carry a state_dept_id neither master file names — "
            "see the stale_ids sheet", len(stale)
        )


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
