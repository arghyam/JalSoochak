#!/usr/bin/env python3
"""
JJM scheme -> section-officer (SO) mapping ingestion for a Jal Soochak tenant
(default: Assam / tenant_as).

Reads the state's scheme/SO mapping CSV (a title line, then headers on row 2:
scheme_public_id, imis_id, public_id, section_officer_name,
section_officer_phone) and reconciles it against the tenant database and the
analytics warehouse.

Two modes:

  analyze  (default)  read-only. Writes an Excel analysis workbook describing
                      exactly what an execute run would do, and why.
  execute  (--execute) applies the inserts/updates/soft-deletes in transactions.

What it touches
---------------
tenant DB (shared_db), schema tenant_<code>:
  user_table                        insert / update (title, user_type, state_user_id)
  user_scheme_mapping_table         insert, and soft-delete of stale SO mappings

analytics DB, schema analytics_schema:
  dim_user_table                    upsert (every SO this run wrote)
  dim_user_scheme_mapping_table     replaced with the tenant's post-state, for
                                    every user whose mappings this run changed —
                                    including a user the CSV never names whose
                                    stale mapping was removed

common_schema.user_type_master_table is read but never written: SECTION_OFFICER
already exists, and this file names no other role, so a run that would have to
mint one is a sign the role was renamed — it aborts instead (see Roles below).

How this differs from the division/EE and sub-division/SDO tools
----------------------------------------------------------------
Those two name a departmental node and derive the schemes from the hierarchy.
This file names the schemes themselves, one per row, so there is no node to
resolve and no subtree to walk — the scheme half is a direct id lookup instead.
What is shared with them is the other half of every row: the person. That is
the user master ingest's job and is imported rather than re-implemented, so the
matching contract, the PII crypto and the onboarding row stay identical. The
two mapping writes (the bulk insert and the soft delete) are likewise the
division tool's MappingWriter, unchanged.

Scheme matching contract
------------------------
The CSV carries two scheme identifiers. imis_id is the centre's id, which we
store as scheme_master_table.centre_scheme_id, and that is what a scheme is
resolved by. scheme_public_id (SCH-…) is the state portal's own handle: no
column holds it, so it is carried through the report for a human to read and is
never matched on.

  imis_id resolves to exactly one live scheme          -> mapped
  imis_id resolves to several live schemes             -> all of them are mapped
  imis_id resolves to none                             -> reported, nothing mapped
  imis_id blank or the literal 'NULL'                  -> reported, nothing mapped

The several-schemes case is normal here rather than exceptional: our scheme
master is unique on the (state_scheme_id, centre_scheme_id) pair, so one centre
id legitimately covers several schemes, and the state's own file says the same —
4,003 of its imis_ids carry more than one scheme_public_id. Nine in ten of those
belong to a single officer, in which case mapping the whole fan-out is exactly
right and the scheme_detail sheet records it. The 383 centre ids two officers
both claim are the ones that over-grant: with no state scheme id in the file
there is nothing to split them by, so both officers get all of it and the
conflicts sheet says so under SCHEME_CONTESTED_FANOUT. --skip-ambiguous-schemes
refuses every fanned-out imis_id instead.

Schemes are taken regardless of scheme_master_table.is_active: that flag tracks
recent flow readings, not whether the scheme is the officer's responsibility.

Section officer matching contract
---------------------------------
The phone number is the identity, as it is everywhere else in the platform, and
the diff applied to a matched user is the user master ingest's: name always,
role unless it is administrative (--no-role-updates withholds every role
change), state_user_id only with --with-state-user-id. Phone number, email,
password and status of an existing user are never touched.

An officer appears on one row per scheme — up to 166 of them in this file — so
the rows are collapsed onto the phone number first and the officer is resolved
once, against the union of their schemes. An officer none of whose schemes
resolved is not written at all: an account with no schemes cannot do anything,
and once the ids are fixed a re-run picks them up.
--create-users-without-schemes onboards them anyway.

Stale mappings
--------------
The CSV is authoritative for who covers the schemes it names, so by default:

    for every scheme this run resolved, its live SECTION_OFFICER mappings are
    made to match exactly the officers the CSV names for it

Mappings that fall outside that sentence are left alone, deliberately:

  * a scheme this run could not resolve is never touched — an unreadable
    imis_id must not cost an officer a scheme they really do cover;
  * a mapping held by a pump operator, jal sahayak, EE or SDO is never touched —
    user_scheme_mapping_table is shared by every role, and this file speaks only
    for section officers;
  * a scheme whose only claimants are officers this run had to skip keeps its
    mappings, rather than being stripped because we could not process a person.

Removal is a soft delete (deleted_at/deleted_by, mirroring UserUploadRepository).
--additive turns it off entirely: missing mappings are still inserted, nothing
is ever removed.

Roles
-----
Every row is a section officer — the file has no role column, and the role is
not inferred per row. SECTION_OFFICER is expected to already be in
common_schema.user_type_master_table; if it is missing or soft-deleted the run
reports it and stops rather than minting a second role that would compete with
the real one for a UNIQUE c_name.

Migrations
----------
The only external-id reconciliation here is the user one, and it is opt-in, so
the whole tool — analysis and execution both — runs against a database where V36
has not been applied. The mapping itself never needs the column. V37 is
irrelevant to this file: it touches no departmental node.

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

  # dry run -> analysis workbook only. Works before V36 is applied.
  python3 "scripts/jjm master data ingestion/so-scheme-mapping/jjm_section_officer_scheme_mapping_ingest.py" \
      --csv "scripts/jjm master data ingestion/so-scheme-mapping/section-officer-scheme-mapping.csv" \
      --actor-id 21357 \
      --out "scripts/jjm master data ingestion/so-scheme-mapping/jjm_section_officer_analysis.xlsx"

  # apply: insert the missing mappings and soft-delete the stale ones
  python3 "scripts/jjm master data ingestion/so-scheme-mapping/jjm_section_officer_scheme_mapping_ingest.py" \
      --csv "scripts/jjm master data ingestion/so-scheme-mapping/section-officer-scheme-mapping.csv" \
      --actor-id 21357 --out jjm_section_officer_analysis.xlsx --execute

  # apply without removing anything, and once V36 is applied also backfill the
  # officers' public ids
  python3 "scripts/jjm master data ingestion/so-scheme-mapping/jjm_section_officer_scheme_mapping_ingest.py" \
      --csv "scripts/jjm master data ingestion/so-scheme-mapping/section-officer-scheme-mapping.csv" \
      --actor-id 21357 --out jjm_section_officer_analysis.xlsx \
      --additive --with-state-user-id --execute
"""

from __future__ import annotations

import argparse
import logging
import os
import sys
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

# The three sibling tools already own everything this one has in common with
# them: the scheme tool the PII crypto, the scheme index and the warehouse
# writer; the user tool the whole officer half — matching on phone, the
# field-level diff and the onboarding row; the division tool the two
# user_scheme_mapping writes and the dim_scheme sanity check. All of that has to
# stay identical to what the services do, so it is imported rather than copied
# into a fourth place that can drift.
_BASE_DIR = os.path.dirname(os.path.abspath(__file__))
_SCHEME_DIR = os.path.join(_BASE_DIR, os.pardir, "scheme")
_USERS_DIR = os.path.join(_BASE_DIR, os.pardir, "users")
_DIV_EE_DIR = os.path.join(_BASE_DIR, os.pardir, "div-ee-mapping")
for _dir in (_SCHEME_DIR, _USERS_DIR, _DIV_EE_DIR):
    sys.path.insert(0, _dir)
try:
    from jjm_scheme_master_ingest import (  # noqa: E402
        ROLE_SECTION_OFFICER,
        AnalyticsWriter,
        PiiCrypto,
        norm_name,
        normalise_phone,
        scheme_id_key,
    )
    from jjm_user_master_ingest import (  # noqa: E402
        CAT_DUPLICATE,
        CAT_EXISTING,
        CAT_INVALID,
        CAT_NEW,
        FIELD_STATE_USER_ID,
        UserDb,
        UserDecision,
        UserRow,
        UserWriter,
        build_role_plans,
        clean,
        classify_users,
        execute_tenant as execute_user_tenant,
        safe_mask,
    )
    from jjm_division_ee_mapping_ingest import (  # noqa: E402
        MappingIngestPlan,
        MappingWriter,
        count_missing_dim_schemes,
    )
except ImportError as exc:  # pragma: no cover
    sys.exit(
        f"Cannot import the sibling ingestion modules from {_SCHEME_DIR!r} / "
        f"{_USERS_DIR!r} / {_DIV_EE_DIR!r}: {exc}\n"
        f"Keep this script alongside 'scheme/jjm_scheme_master_ingest.py', "
        f"'users/jjm_user_master_ingest.py' and "
        f"'div-ee-mapping/jjm_division_ee_mapping_ingest.py'."
    )


LOG = logging.getLogger("jjm-so-scheme-ingest")

# ─────────────────────────────────────────────────────────────────────────────
# Domain constants — mirrored from the Java services. Keep in sync.
# ─────────────────────────────────────────────────────────────────────────────

CSV_COLUMNS = [
    "scheme_public_id", "imis_id", "public_id",
    "section_officer_name", "section_officer_phone",
]

# Every row of this file is a section officer; there is no role column and no
# per-row inference. common_schema.user_type_master_table already holds this
# c_name, and a run that would have to create it stops instead.
SO_ROLE = ROLE_SECTION_OFFICER

# Scheme resolution outcomes. SCHEME_NO_ID doubles as the key of the one pseudo
# plan that collects every row with no centre id at all: scheme_id_key lowercases
# anything non-numeric, so an upper-case key can never collide with a real one.
SCHEME_MATCHED = "MATCHED"
SCHEME_FANOUT = "MATCHED_SEVERAL"
SCHEME_NOT_FOUND = "NOT_FOUND"
SCHEME_NO_ID = "NO_IMIS_ID"

# Person-level outcome that is this tool's own, on top of the user module's
# CAT_* categories.
SKIP_NO_SCHEME = "no scheme on any of this officer's rows resolved"

# How many rival ids to name inline before eliding.
REPORT_LIMIT = 20


# ─────────────────────────────────────────────────────────────────────────────
# CSV model
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class SoMappingRow:
    """One CSV line: a scheme on the left, a section officer on the right."""
    row_no: int                 # 1-based row number as shown in the CSV
    scheme_public_id: str
    imis_id: str
    imis_key: str               # canonical centre id, '' when unusable
    user_public_id: str
    name: str
    phone_raw: str
    phone: str                  # normalised 91XXXXXXXXXX, '' when unusable
    issues: list[str] = field(default_factory=list)

    # The file is the section-officer mapping by definition.
    role: str = SO_ROLE
    role_raw: str = ""

    @property
    def blocking_issues(self) -> list[str]:
        """Issues that stop the officer from being written at all."""
        return [i for i in self.issues if i.startswith("row:")]

    @property
    def scheme_issues(self) -> list[str]:
        """Issues that drop this row's scheme claim but not the officer.

        An officer is spread over one row per scheme, so an unusable id costs
        them that scheme and nothing else — the other 165 rows still stand.
        """
        return [i for i in self.issues if i.startswith("scheme:")]

    @property
    def officer_key(self) -> str:
        """What identifies this officer across rows: their phone number.

        Rows with no usable number are kept apart from each other — two people
        we cannot identify are not the same person.
        """
        return self.phone or f"row:{self.row_no}"


def load_csv(path: str, header_row: int, encoding: str) -> tuple[list[SoMappingRow], list[dict]]:
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

    rows: list[SoMappingRow] = []
    issue_records: list[dict] = []

    for offset, raw in enumerate(frame.to_dict("records")):
        # +1 for the header row itself, +1 to make it 1-based like the file.
        row_no = header_row + 1 + offset
        if all(not clean(raw.get(c)) for c in CSV_COLUMNS):
            continue

        issues: list[str] = []

        scheme_public_id = clean(raw.get("scheme_public_id"))
        # clean() folds the literal 'NULL' this export writes into '' — 131 rows
        # of it — so a scheme with no centre id and one that says NULL are the
        # same case, and neither is guessed at.
        imis_id = clean(raw.get("imis_id"))
        imis_key = scheme_id_key(imis_id)
        if not imis_key:
            issues.append(
                "scheme:blank imis_id — the scheme cannot be resolved and this row "
                "is not mapped"
            )

        user_public_id = clean(raw.get("public_id"))
        if not user_public_id:
            issues.append("state_user_id:blank public_id — user written without a state_user_id")

        name = clean(raw.get("section_officer_name"))
        if not name:
            issues.append("row:blank section_officer_name")

        phone_raw = clean(raw.get("section_officer_phone"))
        phone = normalise_phone(phone_raw) or ""
        if not phone:
            # Never echo the number itself: the workbook carries a masked copy.
            issues.append("row:section_officer_phone is not a valid Indian mobile number")

        rows.append(SoMappingRow(
            row_no=row_no,
            scheme_public_id=scheme_public_id,
            imis_id=imis_id,
            imis_key=imis_key,
            user_public_id=user_public_id,
            name=name,
            phone_raw=phone_raw,
            phone=phone,
            issues=issues,
        ))

        for issue in issues:
            kind, _, detail = issue.partition(":")
            issue_records.append({
                "row_no": row_no,
                "scheme_public_id": scheme_public_id,
                "imis_id": imis_id,
                "public_id": user_public_id,
                "section_officer_name": name,
                "issue_kind": kind,
                "issue": detail,
            })

    return rows, issue_records


# ─────────────────────────────────────────────────────────────────────────────
# Scheme resolution
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class SchemePlan:
    """One centre id as the CSV names it, and the live schemes it resolved to."""
    key: str                                 # canonical imis id
    imis_id: str                             # raw, as the CSV wrote it
    public_ids: list[str] = field(default_factory=list)   # SCH-… ids on these rows
    csv_rows: list[int] = field(default_factory=list)
    category: str = SCHEME_NOT_FOUND
    reason: str = ""
    scheme_ids: set[int] = field(default_factory=set)
    scheme_names: list[str] = field(default_factory=list)
    # Every officer that named this centre id, resolvable or not. The two are
    # kept apart because a scheme claimed only by officers we had to skip must
    # keep its mappings rather than be stripped of them.
    claimants: list["OfficerPlan"] = field(default_factory=list)

    @property
    def resolved(self) -> bool:
        return bool(self.scheme_ids)

    @property
    def writable_claimants(self) -> list["OfficerPlan"]:
        return [p for p in self.claimants if p.will_write]

    @property
    def removable(self) -> bool:
        """Whether this run may soft-delete section-officer mappings on it.

        A scheme nobody usable claims is left exactly as it is: removing its
        officers because we could not read a phone number would take coverage
        away that the CSV never asked us to take away.
        """
        return self.resolved and bool(self.writable_claimants)

    @property
    def subject(self) -> str:
        shown = ", ".join(self.public_ids[:3])
        if len(self.public_ids) > 3:
            shown += f", … (+{len(self.public_ids) - 3} more)"
        return f"imis {self.imis_id or '(blank)'} [{shown}]"


class SoDb(UserDb):
    """Reads the schemes and the section-officer mappings hanging off them.

    Extends the user tool's UserDb (same connection, schema validation, PII
    crypto, officer lookups and, from its own base, load_user_scheme_mappings)
    so one object serves both halves of a CSV row.
    """

    def load_schemes_by_centre_id(self) -> dict[str, list[tuple[int, str]]]:
        """canonical centre id -> [(scheme id, name)] for every live scheme.

        Not the scheme tool's load_scheme_index: that reads the eleven columns a
        scheme *ingest* diffs against and indexes them by state id as well, none
        of which this file has an opinion about. What must not drift is how a
        centre id is canonicalised on both sides of the join, and that is
        scheme_id_key — shared, not re-implemented.
        """
        by_centre: dict[str, list[tuple[int, str]]] = {}
        with self.conn.cursor(name="so_scheme_scan") as cur:
            cur.itersize = 5000
            cur.execute(f"""
                SELECT id, centre_scheme_id, scheme_name
                FROM {self.schema}.scheme_master_table
                WHERE deleted_at IS NULL
            """)
            for scheme_id, centre_scheme_id, scheme_name in cur:
                key = scheme_id_key(centre_scheme_id)
                if key:
                    by_centre.setdefault(key, []).append((scheme_id, scheme_name))
        return by_centre

    def load_section_officer_mappings(self, scheme_ids: Iterable[int]) -> set[tuple[int, int]]:
        """Live (user_id, scheme_id) pairs on these schemes held by section officers.

        Restricted to the one role this file speaks for. user_scheme_mapping_table
        is shared with pump operators, jal sahayaks, EEs and SDOs, and none of
        their mappings is this CSV's business.
        """
        ids = sorted(set(scheme_ids))
        if not ids:
            return set()
        pairs: set[tuple[int, int]] = set()
        with self.conn.cursor() as cur:
            for start in range(0, len(ids), 5000):
                cur.execute(f"""
                    SELECT m.user_id, m.scheme_id
                    FROM {self.schema}.user_scheme_mapping_table m
                    JOIN {self.schema}.user_table u
                      ON u.id = m.user_id AND u.deleted_at IS NULL
                    JOIN common_schema.user_type_master_table ut
                      ON ut.id = u.user_type AND upper(ut.c_name) = %s
                    WHERE m.deleted_at IS NULL AND m.scheme_id = ANY(%s)
                """, (SO_ROLE, ids[start:start + 5000]))
                pairs.update((user_id, scheme_id) for user_id, scheme_id in cur)
        return pairs

    def load_user_names(self, user_ids: Iterable[int]) -> dict[int, str]:
        """id -> decrypted name, for holders of a stale mapping the CSV never names."""
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


def resolve_schemes(
    rows: list[SoMappingRow], db: SoDb, skip_ambiguous: bool = False
) -> dict[str, SchemePlan]:
    """One plan per centre id the CSV names, resolved against scheme_master_table.

    Rows whose officer is unusable still register their scheme: the claim is
    what protects that scheme from having its mappings removed on the strength
    of a file we could only half read.
    """
    plans: dict[str, SchemePlan] = {}
    unresolvable: list[SoMappingRow] = []
    for row in rows:
        if row.scheme_issues:
            unresolvable.append(row)
            continue
        plan = plans.get(row.imis_key)
        if plan is None:
            plan = SchemePlan(key=row.imis_key, imis_id=row.imis_id)
            plans[row.imis_key] = plan
        plan.csv_rows.append(row.row_no)
        if row.scheme_public_id and row.scheme_public_id not in plan.public_ids:
            plan.public_ids.append(row.scheme_public_id)

    if unresolvable:
        # One plan for the whole unusable set: they have no id to tell apart.
        plans[SCHEME_NO_ID] = SchemePlan(
            key=SCHEME_NO_ID,
            imis_id="",
            public_ids=[r.scheme_public_id for r in unresolvable if r.scheme_public_id],
            csv_rows=[r.row_no for r in unresolvable],
            category=SCHEME_NO_ID,
            reason="blank imis_id — nothing to resolve the scheme by",
        )

    resolvable = [p for p in plans.values() if p.category != SCHEME_NO_ID]
    if not resolvable:
        return plans

    LOG.info("  resolving %d centre id(s) against %s …", len(resolvable), db.schema)
    by_centre = db.load_schemes_by_centre_id()
    LOG.info("  %d live centre id(s) in the tenant", len(by_centre))

    for plan in resolvable:
        _resolve_one_scheme(plan, by_centre, skip_ambiguous)
    return plans


def _resolve_one_scheme(
    plan: SchemePlan, by_centre: dict[str, list[tuple[int, str]]], skip_ambiguous: bool
) -> None:
    candidates = sorted(by_centre.get(plan.key, []))
    if not candidates:
        plan.category = SCHEME_NOT_FOUND
        plan.reason = f"no live scheme has centre_scheme_id {plan.imis_id!r}"
        return

    plan.scheme_names = [name for _, name in candidates]
    if len(candidates) == 1:
        plan.category = SCHEME_MATCHED
        plan.scheme_ids = {candidates[0][0]}
        plan.reason = "matched on centre_scheme_id"
        return

    plan.category = SCHEME_FANOUT
    if skip_ambiguous:
        plan.reason = (
            f"centre_scheme_id {plan.imis_id!r} matches {len(candidates)} live schemes "
            f"and --skip-ambiguous-schemes is set — none of them is mapped"
        )
        return
    plan.scheme_ids = {scheme_id for scheme_id, _ in candidates}
    plan.reason = (
        f"centre_scheme_id {plan.imis_id!r} matches {len(candidates)} live schemes "
        f"(our master is unique on the state/centre id pair, not on the centre id "
        f"alone) — all of them are mapped"
    )


# ─────────────────────────────────────────────────────────────────────────────
# Section officers, and the mapping between the two halves
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class OfficerPlan:
    """One person, the schemes they were listed against, and their mappings."""
    phone: str
    decision: UserDecision
    csv_rows: list[int] = field(default_factory=list)
    schemes: list[SchemePlan] = field(default_factory=list)
    target_scheme_ids: set[int] = field(default_factory=set)
    existing_scheme_ids: set[int] = field(default_factory=set)
    to_insert: set[int] = field(default_factory=set)
    to_remove: set[int] = field(default_factory=set)
    skip_reason: str = ""
    # Names the CSV gave this same number on different rows.
    conflicts: list[str] = field(default_factory=list)

    @property
    def will_write(self) -> bool:
        return not self.skip_reason and self.decision.will_write

    @property
    def resolved_schemes(self) -> list[SchemePlan]:
        return [s for s in self.schemes if s.resolved]

    @property
    def action(self) -> str:
        if self.skip_reason:
            return "skip"
        return self.decision.action


@dataclass
class StaleMapping:
    """A live section-officer mapping the CSV does not stand behind any more."""
    user_id: int
    scheme_id: int
    holder_name: str = ""
    # Set when the holder is an officer the CSV names, so the report can say
    # 'moved to another officer' rather than 'no longer a section officer here'.
    in_csv: bool = False


def collapse_officers(
    rows: list[SoMappingRow],
) -> tuple[list[UserRow], dict[str, list[SoMappingRow]]]:
    """One UserRow per phone number, so the user module sees a 1:1 CSV.

    Not the division tool's collapse: an officer here is spread over one row per
    scheme — 166 of them at the widest — so their name and public_id have to be
    taken across the whole group and their blocking issues recomputed from what
    the group actually yields, rather than read off whichever row came first.
    """
    grouped: dict[str, list[SoMappingRow]] = {}
    order: list[str] = []
    for row in rows:
        if row.officer_key not in grouped:
            grouped[row.officer_key] = []
            order.append(row.officer_key)
        grouped[row.officer_key].append(row)

    user_rows: list[UserRow] = []
    for key in order:
        members = grouped[key]
        first = members[0]
        # Later rows win on name and public_id, matching the scheme ingest; any
        # disagreement is reported by build_officer_plans either way.
        name = next((m.name for m in reversed(members) if m.name), "")
        public_id = next((m.user_public_id for m in reversed(members) if m.user_public_id), "")

        issues: list[str] = []
        if not name:
            issues.append("row:blank section_officer_name")
        if not first.phone:
            issues.append("row:section_officer_phone is not a valid Indian mobile number")
        if not public_id:
            issues.append("state_user_id:blank public_id — user written without a state_user_id")

        user_rows.append(UserRow(
            row_no=first.row_no,
            public_id=public_id,
            name=name,
            phone_raw=first.phone_raw,
            phone=first.phone,
            role_raw="",
            role=SO_ROLE,
            issues=issues,
        ))
    return user_rows, grouped


def build_officer_plans(
    rows: list[SoMappingRow],
    schemes: dict[str, SchemePlan],
    db: SoDb,
    update_roles: bool = True,
    create_users_without_schemes: bool = False,
) -> list[OfficerPlan]:
    """Resolve every officer against the tenant DB and work out their mappings."""
    user_rows, grouped = collapse_officers(rows)
    LOG.info("  %d CSV rows collapse onto %d officer(s)", len(rows), len(user_rows))
    decisions = classify_users(user_rows, db, update_roles)

    plans: list[OfficerPlan] = []
    for user_row, decision in zip(user_rows, decisions):
        members = grouped[user_row.phone or f"row:{user_row.row_no}"]
        plan = OfficerPlan(
            phone=user_row.phone,
            decision=decision,
            csv_rows=[r.row_no for r in members],
        )
        seen_keys: set[str] = set()
        seen_names: set[str] = set()
        for member in members:
            scheme = schemes.get(member.imis_key) if not member.scheme_issues else None
            if scheme is not None and scheme.key not in seen_keys:
                seen_keys.add(scheme.key)
                plan.schemes.append(scheme)
            if member.name and norm_name(member.name) != norm_name(user_row.name):
                if member.name not in seen_names:
                    seen_names.add(member.name)
                    plan.conflicts.append(
                        f"row {member.row_no} names this number {member.name!r}, "
                        f"the officer is written as {user_row.name!r}"
                    )
        plans.append(plan)

    for plan in plans:
        for scheme in plan.schemes:
            scheme.claimants.append(plan)

    for plan in plans:
        if plan.decision.category in (CAT_DUPLICATE, CAT_INVALID):
            plan.skip_reason = plan.decision.reason
            continue
        plan.target_scheme_ids = set().union(
            *(s.scheme_ids for s in plan.resolved_schemes)
        ) if plan.resolved_schemes else set()
        if not plan.resolved_schemes and not create_users_without_schemes:
            plan.skip_reason = SKIP_NO_SCHEME

    # Which mappings do the matched officers already hold?
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

    return plans


def find_stale_mappings(
    officers: list[OfficerPlan], schemes: dict[str, SchemePlan], db: SoDb
) -> list[StaleMapping]:
    """Live section-officer mappings on covered schemes that the CSV contradicts.

    The covered universe is exactly the schemes this run resolved and that at
    least one usable officer claims; everything outside it — an unresolved
    scheme, another role's mapping, a scheme nobody usable claimed — is not this
    file's to remove, so it is never even read.
    """
    covered: set[int] = set()
    for scheme in schemes.values():
        if scheme.removable:
            covered |= scheme.scheme_ids
    if not covered:
        return []

    held = db.load_section_officer_mappings(covered)
    claimed = {
        (p.decision.existing_id, scheme_id)
        for p in officers if p.will_write and p.decision.existing_id
        for scheme_id in p.target_scheme_ids
    }
    stale_pairs = sorted(held - claimed)
    if not stale_pairs:
        return []

    known = {p.decision.existing_id: p for p in officers if p.decision.existing_id}
    unknown_names = db.load_user_names(
        user_id for user_id, _ in stale_pairs if user_id not in known
    )

    stale: list[StaleMapping] = []
    for user_id, scheme_id in stale_pairs:
        officer = known.get(user_id)
        if officer is not None:
            officer.to_remove.add(scheme_id)
        stale.append(StaleMapping(
            user_id=user_id,
            scheme_id=scheme_id,
            holder_name=(officer.decision.row.name if officer is not None
                         else unknown_names.get(user_id, "")),
            in_csv=officer is not None,
        ))
    return stale


# ─────────────────────────────────────────────────────────────────────────────
# Plan assembly
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class SoIngestPlan(MappingIngestPlan):
    """The engine's plan with the schemes resolved directly instead of via a node.

    MappingIngestPlan.divisions stays empty on purpose: this CSV names its
    schemes outright, so there is no departmental node between the officer and
    their schemes. What is inherited and does matter is the writable/blocked-role
    bookkeeping, the user-plan projection and the insert pairing.
    """
    schemes: dict[str, SchemePlan] = field(default_factory=dict)
    stale: list[StaleMapping] = field(default_factory=list)

    @property
    def officers(self) -> list[OfficerPlan]:
        """The engine calls these engineers; in this file they are section officers."""
        return self.engineers

    @property
    def remove_pairs(self) -> list[tuple[int, int]]:
        """(user_id, scheme_id) to soft-delete. `replace` is on unless --additive."""
        if not self.replace:
            return []
        return [(s.user_id, s.scheme_id) for s in self.stale]

    @property
    def named_schemes(self) -> list[SchemePlan]:
        """The centre ids the CSV actually gave, without the no-id pseudo plan."""
        return [s for s in self.schemes.values() if s.category != SCHEME_NO_ID]

    @property
    def resolved_schemes(self) -> list[SchemePlan]:
        return [s for s in self.schemes.values() if s.resolved]

    @property
    def scheme_ids_covered(self) -> set[int]:
        return set().union(*(s.scheme_ids for s in self.resolved_schemes)) \
            if self.resolved_schemes else set()

    @property
    def affected_user_ids(self) -> list[int]:
        """Everyone whose mappings this run may change, officers and holders alike."""
        ids = {p.decision.existing_id for p in self.writable if p.decision.existing_id}
        if self.replace:
            ids |= {s.user_id for s in self.stale}
        return sorted(ids)


def build_plan(
    rows: list[SoMappingRow],
    csv_issues: list[dict],
    db: SoDb,
    update_roles: bool = True,
    create_users_without_schemes: bool = False,
    replace: bool = True,
    skip_ambiguous_schemes: bool = False,
) -> SoIngestPlan:
    LOG.info("Classifying %d CSV rows …", len(rows))
    schemes = resolve_schemes(rows, db, skip_ambiguous=skip_ambiguous_schemes)
    unresolved = [s for s in schemes.values()
                  if not s.resolved and s.category != SCHEME_NO_ID]
    if unresolved:
        LOG.warning("%d centre id(s) did not resolve to a live scheme", len(unresolved))
    no_id = schemes.get(SCHEME_NO_ID)
    if no_id is not None:
        LOG.warning("%d row(s) carry no usable imis_id and are not mapped",
                    len(no_id.csv_rows))

    officers = build_officer_plans(
        rows, schemes, db,
        update_roles=update_roles,
        create_users_without_schemes=create_users_without_schemes,
    )
    stale = find_stale_mappings(officers, schemes, db) if replace else []
    if stale:
        LOG.warning("%d live section-officer mapping(s) are no longer stated by the CSV",
                    len(stale))

    user_types = db.load_user_types()
    role_plans = build_role_plans([p.decision for p in officers if p.will_write], user_types)

    return SoIngestPlan(
        engineers=officers,
        divisions={},
        role_plans=role_plans,
        user_types=user_types,
        csv_issues=csv_issues,
        replace=replace,
        with_state_dept_id=False,
        with_state_user_id=db.with_state_user_id,
        schemes=schemes,
        stale=stale,
    )


def unusable_roles(plan: SoIngestPlan) -> list[str]:
    """Roles the CSV needs that this tool refuses to mint.

    Only SECTION_OFFICER can appear, and it is expected to be there already.
    Missing means the seed data was renamed; soft-deleted means the UNIQUE
    c_name is occupied by a dead row. Either way a human has to look, because
    inserting a second SECTION_OFFICER would split the role in two and silently
    strand half the officers — and would make the stale-mapping query blind to
    every mapping held under the other id.
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


def _fmt_ids(ids: Iterable[Any], limit: int = REPORT_LIMIT) -> str:
    ordered = sorted(ids)
    shown = ", ".join(str(i) for i in ordered[:limit])
    return shown + (f", … (+{len(ordered) - limit} more)" if len(ordered) > limit else "")


def build_summary_frame(plan: SoIngestPlan) -> pd.DataFrame:
    officers = plan.officers
    writable = plan.writable
    schemes = plan.named_schemes
    resolved = plan.resolved_schemes
    no_id = plan.schemes.get(SCHEME_NO_ID)

    records = [
        {"metric": "CSV rows", "value": sum(len(p.csv_rows) for p in officers)},
        {"metric": "  rows with no imis_id", "value": len(no_id.csv_rows) if no_id else 0},
        {"metric": "distinct centre ids named", "value": len(schemes)},
        {"metric": "  centre ids resolved", "value": len(resolved)},
        {"metric": "  centre ids matching one scheme",
         "value": len([s for s in resolved if s.category == SCHEME_MATCHED])},
        {"metric": "  centre ids matching several schemes",
         "value": len([s for s in schemes if s.category == SCHEME_FANOUT])},
        {"metric": "  centre ids not found",
         "value": len([s for s in schemes if s.category == SCHEME_NOT_FOUND])},
        {"metric": "  centre ids claimed by several officers",
         "value": len([s for s in schemes if len(s.claimants) > 1])},
        {"metric": "    of those, ones covering several schemes",
         "value": len([s for s in schemes
                       if len(s.claimants) > 1 and s.category == SCHEME_FANOUT
                       and s.resolved])},
        {"metric": "schemes covered by the CSV", "value": len(plan.scheme_ids_covered)},
        {"metric": "distinct section officers", "value": len(officers)},
        {"metric": "  officers inserted",
         "value": len([p for p in writable if p.decision.category == CAT_NEW])},
        {"metric": "  officers updated",
         "value": len([p for p in writable
                       if p.decision.category == CAT_EXISTING and p.decision.changes])},
        {"metric": "  officers already up to date",
         "value": len([p for p in writable
                       if p.decision.category == CAT_EXISTING and not p.decision.changes])},
        {"metric": "  officers skipped",
         "value": len([p for p in officers if not p.will_write])},
        {"metric": "scheme mappings to insert", "value": sum(len(p.to_insert) for p in writable)},
        {"metric": "scheme mappings already correct",
         "value": sum(len(p.target_scheme_ids & p.existing_scheme_ids) for p in writable)},
        {"metric": "stale mappings to soft-delete",
         "value": len(plan.remove_pairs)},
        {"metric": "  of them held by an officer this CSV names",
         "value": len([s for s in plan.stale if s.in_csv]) if plan.replace else 0},
        {"metric": "  of them held by someone the CSV never names",
         "value": len([s for s in plan.stale if not s.in_csv]) if plan.replace else 0},
        {"metric": "state_user_id backfills",
         "value": len([p for p in writable if FIELD_STATE_USER_ID in p.decision.changes])},
    ]
    return pd.DataFrame.from_records(records)


def build_scheme_frame(plan: SoIngestPlan, full: bool) -> pd.DataFrame:
    """One row per centre id. Cleanly matched ids are elided unless --full-scheme-sheet.

    27,000 rows of 'MATCHED' bury the handful an operator actually has to look
    at, so by default only the ids that did something unusual are written.
    """
    schemes = sorted(
        plan.schemes.values(), key=lambda s: (s.category, s.imis_id, s.key)
    )
    if not full:
        schemes = [
            s for s in schemes
            if s.category != SCHEME_MATCHED or len(s.claimants) > 1 or not s.removable
        ]

    records = [
        {
            "imis_id": s.imis_id,
            "scheme_public_ids": _fmt_ids(s.public_ids, limit=5),
            "outcome": s.category,
            "reason": s.reason,
            "csv_rows": len(s.csv_rows),
            "our_scheme_ids": _fmt_ids(s.scheme_ids),
            "our_scheme_names": _fmt_ids(s.scheme_names, limit=5),
            "officers_claiming_it": ", ".join(
                p.decision.row.name or "(unnamed)" for p in s.claimants
            ),
            "usable_officers": len(s.writable_claimants),
            "stale_mappings_removable": "yes" if s.removable else "no",
        }
        for s in schemes
    ]
    return pd.DataFrame.from_records(records) if records else pd.DataFrame(
        columns=["imis_id", "scheme_public_ids", "outcome", "reason", "our_scheme_ids"]
    )


def build_mapping_frame(plan: SoIngestPlan, include_pii: bool) -> pd.DataFrame:
    show = (lambda p: p) if include_pii else safe_mask
    records = []
    for p in plan.writable:
        decision = p.decision
        records.append({
            "csv_rows": len(p.csv_rows),
            "section_officer": decision.row.name,
            "phone": show(p.phone),
            "existing_user_id": decision.existing_id,
            "centre_ids_in_csv": len(p.schemes),
            "centre_ids_resolved": len(p.resolved_schemes),
            "schemes_claimed": len(p.target_scheme_ids),
            "already_mapped": len(p.target_scheme_ids & p.existing_scheme_ids),
            "mappings_to_insert": len(p.to_insert),
            "mappings_to_soft_delete": len(p.to_remove) if plan.replace else 0,
            "scheme_ids_to_insert": _fmt_ids(p.to_insert),
            "scheme_ids_to_soft_delete": _fmt_ids(p.to_remove) if plan.replace else "",
        })
    return pd.DataFrame.from_records(records) if records else pd.DataFrame(
        columns=["csv_rows", "section_officer", "schemes_claimed", "mappings_to_insert"]
    )


def build_officer_frame(plan: SoIngestPlan, include_pii: bool) -> pd.DataFrame:
    show = (lambda p: p) if include_pii else safe_mask
    records = []
    for p in plan.officers:
        decision = p.decision
        records.append({
            "first_csv_row": p.csv_rows[0] if p.csv_rows else None,
            "csv_rows": len(p.csv_rows),
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
            "schemes_claimed": len(p.target_scheme_ids),
            "fields_to_change": _fmt_changes(decision.changes),
            "fields_withheld": _fmt_withheld(decision.withheld),
        })
    return pd.DataFrame.from_records(records) if records else pd.DataFrame(
        columns=["first_csv_row", "action", "category", "reason"]
    )


def build_removal_frame(plan: SoIngestPlan) -> pd.DataFrame:
    """Every mapping an execute run would soft-delete, in full.

    This is the destructive half of the run, so nothing here is elided: the
    operator signs off on the whole list or on none of it.
    """
    records = [
        {
            "user_id": s.user_id,
            "holder": s.holder_name,
            "holder_is_in_the_csv": "yes" if s.in_csv else "no",
            "scheme_id": s.scheme_id,
            "reason": "the CSV gives this scheme to another section officer"
            if s.in_csv else
            "this section officer is not the one the CSV names for this scheme",
        }
        for s in plan.stale
    ] if plan.replace else []
    return pd.DataFrame.from_records(records) if records else pd.DataFrame(
        columns=["user_id", "holder", "holder_is_in_the_csv", "scheme_id", "reason"]
    )


def build_role_frame(plan: SoIngestPlan) -> pd.DataFrame:
    """One row, normally: SECTION_OFFICER, already present."""
    records = [
        {
            "canonical_role": p.role,
            "source": "implied by the file (there is no role column)",
            "officers": p.csv_rows,
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
        columns=["canonical_role", "source", "officers", "user_type_id", "action"]
    )


def build_analytics_frame(plan: SoIngestPlan) -> pd.DataFrame:
    writable = plan.writable
    touched = [p for p in writable if p.decision.existing_id or p.decision.category == CAT_NEW]
    affected = plan.affected_user_ids
    return pd.DataFrame.from_records([
        {"metric": "dim_user_table rows upserted", "value": len(touched)},
        {"metric": "dim_user_scheme_mapping_table users replaced", "value": len(affected)},
        {"metric": "  of them only because a stale mapping was removed",
         "value": len({s.user_id for s in plan.stale if not s.in_csv}) if plan.replace else 0},
    ])


def build_conflict_frame(plan: SoIngestPlan, include_pii: bool) -> pd.DataFrame:
    """Everything a human has to look at before executing."""
    show = (lambda p: p) if include_pii else safe_mask
    records: list[dict] = []

    for scheme in plan.schemes.values():
        if scheme.category == SCHEME_NO_ID:
            records.append({
                "kind": "SCHEME_NO_IMIS_ID",
                "csv_rows": len(scheme.csv_rows),
                "subject": f"{len(scheme.csv_rows)} row(s)",
                "detail": f"{scheme.reason}; scheme_public_id(s): "
                          f"{_fmt_ids(scheme.public_ids, limit=10)}",
            })
            continue
        if not scheme.resolved:
            records.append({
                "kind": f"SCHEME_{scheme.category}",
                "csv_rows": len(scheme.csv_rows),
                "subject": scheme.subject,
                "detail": scheme.reason,
            })
        # A fan-out one officer owns outright is not a conflict — they cover all
        # of it either way — so it is left to the scheme_detail sheet. What lands
        # here is the combination that actually over-grants: several schemes
        # behind one centre id, and several officers claiming it.
        if len(scheme.claimants) > 1:
            names = ", ".join(p.decision.row.name or "(unnamed)" for p in scheme.claimants)
            fanout = scheme.category == SCHEME_FANOUT and scheme.resolved
            records.append({
                "kind": "SCHEME_CONTESTED_FANOUT" if fanout else "SCHEME_MULTIPLE_OFFICERS",
                "csv_rows": len(scheme.csv_rows),
                "subject": scheme.subject,
                "detail": (
                    f"{len(scheme.claimants)} officers ({names}) are listed against this "
                    f"centre id, and it covers {len(scheme.scheme_ids)} schemes "
                    f"({_fmt_ids(scheme.scheme_ids)}) — every one of those officers is "
                    f"mapped to every one of those schemes, because the CSV gives no "
                    f"state scheme id to tell them apart"
                    if fanout else
                    f"{len(scheme.claimants)} officers ({names}) are listed against this "
                    f"centre id — all of them are mapped to it"
                ),
            })
        if scheme.resolved and not scheme.writable_claimants:
            records.append({
                "kind": "SCHEME_CLAIMANTS_ALL_SKIPPED",
                "csv_rows": len(scheme.csv_rows),
                "subject": scheme.subject,
                "detail": "every officer listed against this centre id was skipped, so "
                          "its existing mappings are left exactly as they are",
            })

    for p in plan.officers:
        subject = p.decision.row.name or show(p.decision.row.phone_raw)
        if not p.will_write:
            records.append({
                "kind": "OFFICER_SKIPPED",
                "csv_rows": len(p.csv_rows),
                "subject": subject,
                "detail": p.skip_reason or p.decision.reason,
            })
        for field_name, why in sorted(p.decision.withheld.items()):
            records.append({
                "kind": f"WITHHELD_{field_name.upper()}",
                "csv_rows": len(p.csv_rows),
                "subject": subject,
                "detail": why,
            })
        for conflict in p.conflicts:
            records.append({
                "kind": "OFFICER_ROW_DISAGREEMENT",
                "csv_rows": len(p.csv_rows),
                "subject": subject,
                "detail": conflict,
            })
        unresolved = [s for s in p.schemes if not s.resolved]
        if p.will_write and unresolved:
            records.append({
                "kind": "OFFICER_SCHEMES_UNRESOLVED",
                "csv_rows": len(p.csv_rows),
                "subject": subject,
                "detail": f"{len(unresolved)} of this officer's {len(p.schemes)} centre "
                          f"id(s) did not resolve; those schemes are neither mapped nor "
                          f"removed from anyone",
            })
        if p.will_write and not plan.replace and p.existing_scheme_ids - p.target_scheme_ids:
            records.append({
                "kind": "MAPPINGS_OUTSIDE_THE_CSV",
                "csv_rows": len(p.csv_rows),
                "subject": subject,
                "detail": f"{len(p.existing_scheme_ids - p.target_scheme_ids)} existing "
                          f"mapping(s) are not stated by the CSV; --additive is set, so "
                          f"they are kept",
            })

    holders = {s.user_id for s in plan.stale if not s.in_csv} if plan.replace else set()
    if holders:
        records.append({
            "kind": "STALE_HOLDERS_OUTSIDE_THE_CSV",
            "csv_rows": 0,
            "subject": f"{len(holders)} user(s)",
            "detail": "section officers the CSV never names hold mappings on schemes it "
                      "does name; those mappings are soft-deleted — see removal_detail",
        })

    for problem in unusable_roles(plan):
        records.append({
            "kind": "ROLE_BLOCKED",
            "csv_rows": 0,
            "subject": SO_ROLE,
            "detail": problem,
        })

    return pd.DataFrame.from_records(records) if records else pd.DataFrame(
        columns=["kind", "csv_rows", "subject", "detail"]
    )


def write_analysis_workbook(plan: SoIngestPlan, path: str, include_pii: bool,
                            context: dict, full_scheme_sheet: bool = False) -> None:
    run_info = pd.DataFrame.from_records(
        [{"setting": k, "value": str(v)} for k, v in context.items()]
    )
    issues = pd.DataFrame.from_records(plan.csv_issues) if plan.csv_issues else pd.DataFrame(
        columns=["row_no", "scheme_public_id", "imis_id", "public_id",
                 "section_officer_name", "issue_kind", "issue"]
    )

    with pd.ExcelWriter(path, engine="openpyxl") as writer:
        run_info.to_excel(writer, sheet_name="run_info", index=False)
        build_summary_frame(plan).to_excel(writer, sheet_name="summary", index=False)
        build_scheme_frame(plan, full_scheme_sheet).to_excel(
            writer, sheet_name="scheme_detail", index=False)
        build_mapping_frame(plan, include_pii).to_excel(
            writer, sheet_name="mapping_detail", index=False)
        build_officer_frame(plan, include_pii).to_excel(
            writer, sheet_name="so_detail", index=False)
        build_removal_frame(plan).to_excel(writer, sheet_name="removal_detail", index=False)
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
    plan: SoIngestPlan, user_writer: UserWriter, mapping_writer: MappingWriter
) -> dict[str, int]:
    """Apply the whole tenant-side plan in one transaction.

    The officer half goes through the user module's own execute path, so a
    person onboarded here is identical to one onboarded by the user master
    ingest. It fills in existing_id on every inserted user, which is what the
    mapping insert then addresses.

    create_roles is not offered: unusable_roles has already established that
    SECTION_OFFICER is present and live, so there is nothing to mint and an
    attempt to would mean the plan changed underneath us.
    """
    stats = execute_user_tenant(plan.as_user_plan(), user_writer, create_roles=False)

    missing_ids = [p for p in plan.writable if not p.decision.existing_id]
    if missing_ids:
        raise SystemExit(
            f"{len(missing_ids)} officer(s) have no user id after the user writes — "
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
    plan: SoIngestPlan, analytics: AnalyticsWriter, db: SoDb
) -> dict[str, int]:
    """Project the post-state of everyone this run touched into the warehouse.

    The authoritative values are read back from the tenant DB rather than
    assembled from the CSV, so a withheld role, a withheld state_user_id or a
    mapping the tenant transaction did not actually apply cannot leak into the
    warehouse as if it had been.

    'Everyone' is wider than the officers in the file: a user the CSV never
    names whose stale mapping was removed has to have their dim rows rewritten
    too, or the warehouse keeps serving coverage the tenant no longer grants.
    """
    ids = plan.affected_user_ids
    if not ids:
        return {"dim_user_rows_upserted": 0, "dim_user_scheme_mapping_rows": 0}

    written = [p.decision.existing_id for p in plan.writable if p.decision.existing_id]
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
    for user_id in written:
        row = snapshot.get(user_id)
        if row is None:
            continue
        uuid, email, user_type, title, status = row
        users.append({
            "user_id": user_id,
            "uuid": uuid,
            "email": email,
            "user_type": user_type,
            # dim_user_table.title holds the plaintext name, exactly as the
            # user-service publishes it on a UserUpdated event.
            "title": title,
            "status": status,
        })

    stats = {"dim_user_rows_upserted": analytics.upsert_users(users)}

    # Replace each affected user's mappings with the tenant DB's post-state.
    # Doing it for everyone (not only those whose set changed) makes a re-run
    # repair a warehouse that drifted, which is the point of delete-then-insert.
    post_state = db.load_user_scheme_mappings(ids)
    mappings = {uid: post_state.get(uid, set()) for uid in ids}
    stats["dim_user_scheme_mapping_rows"] = analytics.replace_user_scheme_mappings(mappings)

    mapped_schemes = set().union(*mappings.values()) if mappings else set()
    stats["mapped_schemes_missing_from_dim_scheme"] = count_missing_dim_schemes(
        analytics, mapped_schemes
    )
    return stats


# ─────────────────────────────────────────────────────────────────────────────
# CLI
# ─────────────────────────────────────────────────────────────────────────────

def parse_args(argv: Optional[list[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Reconcile the JJM scheme -> section-officer mapping CSV into a "
                    "Jal Soochak tenant + analytics warehouse.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__.split("Usage")[-1],
    )
    parser.add_argument("--csv", required=True, help="path to the scheme/SO mapping CSV")
    parser.add_argument("--header-row", type=int, default=2,
                        help="1-based row holding the column headers (default: 2)")
    parser.add_argument("--encoding", default="utf-8-sig",
                        help="CSV encoding (default: utf-8-sig)")
    parser.add_argument("--schema", default="tenant_as", help="tenant schema (default: tenant_as)")
    parser.add_argument("--tenant-id", type=int, default=None,
                        help="tenant id; resolved from the schema's state code when omitted")
    parser.add_argument("--actor-id", type=int, required=True,
                        help="user_table.id recorded as created_by/updated_by/deleted_by")
    parser.add_argument("--out", default="jjm_section_officer_analysis.xlsx",
                        help="analysis workbook path "
                             "(default: jjm_section_officer_analysis.xlsx)")
    parser.add_argument("--tenant-dsn", default=os.environ.get("TENANT_DSN"),
                        help="tenant DB DSN (default: $TENANT_DSN)")
    parser.add_argument("--analytics-dsn", default=os.environ.get("ANALYTICS_DSN"),
                        help="analytics DB DSN (default: $ANALYTICS_DSN)")
    parser.add_argument("--execute", action="store_true",
                        help="apply the plan; without this the run is read-only")
    parser.add_argument("--additive", action="store_true",
                        help="never remove anything: insert the missing mappings and leave "
                             "every existing one alone. By default the CSV is authoritative "
                             "for the schemes it names, so a section-officer mapping on one "
                             "of them that the CSV does not state is soft-deleted")
    parser.add_argument("--skip-ambiguous-schemes", action="store_true",
                        help="refuse an imis_id that matches more than one live scheme "
                             "instead of mapping the officer to all of them")
    parser.add_argument("--skip-analytics", action="store_true",
                        help="do not touch the analytics warehouse")
    parser.add_argument("--no-role-updates", action="store_true",
                        help=f"never change an existing user's role to {SO_ROLE}, even when "
                             f"the CSV disagrees; the difference is still reported")
    parser.add_argument("--create-users-without-schemes", action="store_true",
                        help="onboard an officer whose centre ids all failed to resolve; "
                             "by default they are skipped, because the account would have "
                             "no schemes and a re-run picks them up once the ids are fixed")
    parser.add_argument("--with-state-user-id", action="store_true",
                        help="also reconcile the CSV's public_id into user_table."
                             "state_user_id. Needs V36 to have been applied; without this "
                             "option the column is neither read nor written, so the mapping "
                             "can be ingested before the migration lands")
    parser.add_argument("--full-scheme-sheet", action="store_true",
                        help="write every centre id to the scheme_detail sheet; by default "
                             "only the ones that need a human eye are listed")
    parser.add_argument("--include-pii", action="store_true",
                        help="write full phone numbers into the analysis workbook "
                             "(masked by default)")
    parser.add_argument("--limit", type=int, default=None,
                        help="process only the first N CSV rows (for rehearsals). Refused "
                             "with --execute unless --additive: a truncated file would "
                             "soft-delete every mapping listed past the cut")
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
    if args.limit is not None and args.limit <= 0:
        return _fail("--limit must be a positive number of rows")
    if args.limit is not None and args.execute and not args.additive:
        # A truncated file is a truncated statement of who covers what: the
        # officers it does reach lose every scheme listed past the cut, because
        # the removal rule reads their absence as the CSV taking them away.
        return _fail(
            "--limit cannot be combined with --execute unless --additive is also set: "
            "the CSV lists one row per scheme, so cutting it short would soft-delete "
            "every mapping past the cut. Rehearse with --limit and no --execute."
        )

    try:
        pii = PiiCrypto(os.environ.get("PII_ENCRYPTION_KEY", ""), os.environ.get("PII_HMAC_KEY", ""))
    except ValueError as exc:
        return _fail(str(exc))

    LOG.info("Reading %s …", args.csv)
    rows, csv_issues = load_csv(args.csv, args.header_row, args.encoding)
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
        db = SoDb(tenant_conn, args.schema, pii, with_state_user_id=args.with_state_user_id)
        if args.with_state_user_id:
            db.assert_state_user_id_column()
        elif db.state_user_id_column_exists():
            LOG.warning(
                "state_user_id exists on %s.user_table but is out of scope for this run "
                "— pass --with-state-user-id to reconcile the CSV's public_id into it.",
                db.schema,
            )
        tenant_id = args.tenant_id or db.resolve_tenant_id()
        LOG.info("Tenant id %d, schema %s", tenant_id, db.schema)

        plan = build_plan(
            rows, csv_issues, db,
            update_roles=not args.no_role_updates,
            create_users_without_schemes=args.create_users_without_schemes,
            replace=not args.additive,
            skip_ambiguous_schemes=args.skip_ambiguous_schemes,
        )

        context = {
            "generated_at": datetime.now().isoformat(timespec="seconds"),
            "csv": args.csv,
            "csv_rows": len(rows),
            "tenant_schema": args.schema,
            "tenant_id": tenant_id,
            "actor_id": args.actor_id,
            "mode": "EXECUTE" if args.execute else "ANALYZE (read-only)",
            "mapping_mode": "ADDITIVE (nothing is ever removed)" if args.additive else
            "AUTHORITATIVE (a section-officer mapping on a scheme the CSV names, "
            "that the CSV does not state, is soft-deleted)",
            "ambiguous_schemes": "skipped" if args.skip_ambiguous_schemes
            else "every scheme sharing the imis_id is mapped",
            "analytics": "skipped" if args.skip_analytics else "included",
            "phones_in_report": "full" if args.include_pii else "masked",
            "scheme_sheet": "every centre id" if args.full_scheme_sheet
            else "only the centre ids that need a human eye",
            "role": f"{SO_ROLE} for every row; never created by this tool",
            "role_updates": "withheld" if args.no_role_updates else "applied",
            "officers_without_schemes": "onboarded" if args.create_users_without_schemes
            else "skipped",
            "state_user_id": "reconciled (needs V36)" if args.with_state_user_id
            else "OUT OF SCOPE — public_id is not written",
        }
        write_analysis_workbook(plan, args.out, args.include_pii, context,
                                full_scheme_sheet=args.full_scheme_sheet)
        _print_summary(plan)

        problems = unusable_roles(plan)
        if problems:
            return _fail(f"{SO_ROLE} cannot be used as it stands: " + "; ".join(problems))

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
        for key, value in execute_tenant(plan, user_writer, mapping_writer).items():
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


def _print_summary(plan: SoIngestPlan) -> None:
    LOG.info("─" * 72)
    for record in build_summary_frame(plan).to_dict("records"):
        LOG.info("%-52s %8d", record["metric"], record["value"])
    LOG.info("─" * 72)
    for role_plan in plan.role_plans:
        LOG.info("%-52s %8d  %s", f"role / {role_plan.role}", role_plan.csv_rows,
                 role_plan.action)
    LOG.info("─" * 72)
    LOG.info("See the conflicts and removal_detail sheets before executing.")


if __name__ == "__main__":
    sys.exit(main())
