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
  user_scheme_mapping_table         insert, revive, and retire (unless --additive)

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
The CSV carries two scheme identifiers, and they are tried in that order of
authority:

  1. scheme_public_id (SCH-…) — the state portal's own handle, stored by V39 as
     scheme_master_table.state_scheme_code. A partial UNIQUE index holds it to
     at most one live scheme, so a claim it resolves is exact.
  2. imis_id — the centre's id, stored as centre_scheme_id. Used when the CSV
     gives no public id, when we do not hold the one it gives, or when the
     column itself is not there yet (before V39).

  public id resolves to one scheme                -> mapped
  public id unknown here, imis_id resolves to one -> mapped, fall-back reported
  the id that answered matches several schemes    -> refused, nothing mapped
  neither resolves                                -> reported, nothing mapped
  both blank (or the literal 'NULL')              -> reported, nothing mapped

The order is what removes this file's central ambiguity. Our scheme master is
unique on the (state_scheme_id, centre_scheme_id) pair rather than on the centre
id alone, so one centre id legitimately covers several schemes — and the state's
own file agrees: 4,003 of its imis_ids carry more than one scheme_public_id. The
public id is precisely what tells those apart, so every code we hold shrinks the
fan-out, and a fully backfilled state_scheme_code removes it entirely without
this tool changing.

Until then a fanned-out claim is refused rather than guessed at, because mapping
all of it is a statement the CSV never made: the officer gets every scheme behind
the id, which is right only when they happen to hold the lot and over-grants
whenever two officers split them. Refusing means the claim contributes nothing to
what the officer covers — and, since this run is authoritative for the role (see
Reconciliation), any live mapping they already held on those schemes is retired
along with everything else the CSV no longer states. That is the point: coverage
this run cannot vouch for does not survive it.

  --map-ambiguous-schemes  maps the officer to every scheme behind the id
                           instead. Use it only where the fan-outs are known to
                           be one officer's whole responsibility — the
                           conflicts sheet's SCHEME_CONTESTED_FANOUT rows are
                           the ones it would over-grant.

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
once, against the union of their schemes. An officer left with no scheme this
run can map — every id of theirs resolving to nothing, or to several things — is
not written at all: an account with no schemes cannot do anything, and once the
ids are fixed (or state_scheme_code is backfilled) a re-run picks them up.
--create-users-without-schemes onboards them anyway.

Reconciliation
--------------
The CSV is the whole truth about SECTION_OFFICER, tenant-wide, so by default:

    every live SECTION_OFFICER mapping in the tenant is made to match exactly
    what this file states — nothing more and nothing less

Four consequences worth being sure about before running it:

  * an officer the file has dropped keeps no schemes at all. Absence from the
    latest dataset is read as a statement about the person, not about the
    schemes they happen to hold, so even a scheme this CSV never mentions is
    taken off them;
  * an officer the file names but this run could not process — an unreadable
    phone number, a duplicate one, or scheme ids that resolved to nothing or to
    several things — is treated the same way and stripped too, because a run
    that cannot place them cannot vouch for their coverage either. They are
    reported apart from the genuinely absent, under SKIPPED_HOLDER_STRIPPED, so
    an operator can tell a dropped officer from a broken row at a glance;
  * an officer whose *other* claims did resolve is written, and then holds
    exactly those schemes: a mapping of theirs on a scheme only a refused claim
    named is retired like any other the CSV no longer states. Removal is scoped
    to the role and to the officer, never to the scheme, so there is no such
    thing as a scheme this sweep leaves alone;
  * a mapping held by a pump operator, jal sahayak, EE or SDO is never touched.
    user_scheme_mapping_table is shared by every role, and this file speaks only
    for section officers — that one exception is not negotiable and is the only
    thing outside the sweep.

The removal_detail sheet lists every row that goes, with the reason and whether
it costs anybody coverage; the conflicts sheet names each stripped user once.
Read both before executing.

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

--additive turns rule 2 off entirely: missing mappings are still inserted,
retired ones the CSV wants are still revived and duplicates are still collapsed,
but nothing is ever taken away.

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

V39 (scheme_master_table.state_scheme_code) needs no option and is checked for
rather than assumed. Without it, or before it is backfilled, the public id
cannot be looked up and every claim falls back to the centre id. What that costs
is reported: the summary counts the fall-backs, and the conflicts sheet says how
many of them ended up ambiguous and therefore unmapped. Backfilling the column
is what turns those back into coverage.

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

  # apply in full: insert what is missing, revive what is coming back, and
  # retire every SECTION_OFFICER mapping the CSV no longer states — which
  # includes the schemes behind an id that matched several of them
  python3 "scripts/jjm master data ingestion/so-scheme-mapping/jjm_section_officer_scheme_mapping_ingest.py" \
      --csv "scripts/jjm master data ingestion/so-scheme-mapping/section-officer-scheme-mapping.csv" \
      --actor-id 21357 --out jjm_section_officer_analysis.xlsx --execute

  # the same, but map an officer to every scheme behind an id that matches
  # several instead of refusing the claim (the pre-V39 behaviour)
  python3 "scripts/jjm master data ingestion/so-scheme-mapping/jjm_section_officer_scheme_mapping_ingest.py" \
      --csv "scripts/jjm master data ingestion/so-scheme-mapping/section-officer-scheme-mapping.csv" \
      --actor-id 21357 --out jjm_section_officer_analysis.xlsx \
      --map-ambiguous-schemes --execute

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
from collections import Counter, defaultdict
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
        STATE_SCHEME_CODE_COLUMN,
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
        REMOVAL_ABSENT,
        REMOVAL_DUPLICATE,
        REMOVAL_OUTSIDE_TARGET,
        REMOVAL_REASSIGNED,
        REMOVAL_SKIPPED,
        DivisionDb,
        MappingIngestPlan,
        MappingWriter,
        build_duplicate_frame,
        build_reconciliation,
        build_removal_frame,
        count_missing_dim_schemes,
        legacy_holder_conflicts,
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
# plan that collects every row carrying neither id: claim_key is built out of
# scheme_id_key, which lowercases anything non-numeric, so an upper-case key can
# never collide with a real one.
SCHEME_MATCHED = "MATCHED"
SCHEME_FANOUT = "MATCHED_SEVERAL"
SCHEME_NOT_FOUND = "NOT_FOUND"
SCHEME_NO_ID = "NO_SCHEME_ID"

# Which of the two identifiers actually resolved a claim. Named after the column
# each one is matched against, because that is what an operator has to go and
# look at when a claim went somewhere they did not expect.
MATCH_PUBLIC_ID = STATE_SCHEME_CODE_COLUMN
MATCH_CENTRE_ID = "centre_scheme_id"

# Person-level outcome that is this tool's own, on top of the user module's
# CAT_* categories.
SKIP_NO_SCHEME = (
    "no scheme this run can map came out of any of this officer's rows — every one "
    "of their scheme ids either resolved to nothing or to several schemes"
)

# How many rival ids to name inline before eliding.
REPORT_LIMIT = 20


# ─────────────────────────────────────────────────────────────────────────────
# CSV model
# ─────────────────────────────────────────────────────────────────────────────

def claim_key(scheme_public_id: Any, imis_id: Any) -> str:
    """What tells one scheme claim from another: both of the ids it names.

    The public id is what resolves the claim and the centre id is what the run
    falls back to when we do not hold that public id, so a claim is identified
    by the pair rather than by whichever of the two happened to win. Keying on
    the public id alone would merge two rows that fall back to different centre
    ids; keying on the centre id alone would merge the rows a public id is there
    to tell apart — which is the whole point of preferring it.
    """
    return f"{scheme_id_key(scheme_public_id) or '-'}/{scheme_id_key(imis_id) or '-'}"


@dataclass
class SoMappingRow:
    """One CSV line: a scheme on the left, a section officer on the right."""
    row_no: int                 # 1-based row number as shown in the CSV
    scheme_public_id: str
    code_key: str               # canonical state_scheme_code, '' when unusable
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
    def claim_key(self) -> str:
        """The scheme claim this row makes; see the module-level claim_key."""
        return claim_key(self.scheme_public_id, self.imis_id)

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
        code_key = scheme_id_key(scheme_public_id)
        # clean() folds the literal 'NULL' this export writes into '' — 131 rows
        # of it in imis_id — so an id that is blank and one that says NULL are
        # the same case, and neither is guessed at.
        imis_id = clean(raw.get("imis_id"))
        imis_key = scheme_id_key(imis_id)
        # Only a row naming neither id is unusable on its face. A row that gives
        # just one of them still has something to resolve by; whether it does
        # resolve is the tenant's answer, not the file's, and is settled in
        # resolve_schemes.
        if not code_key and not imis_key:
            issues.append(
                "scheme:neither scheme_public_id nor imis_id — the scheme cannot be "
                "resolved and this row is not mapped"
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
            code_key=code_key,
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
    """One scheme claim as the CSV makes it, and the live schemes it resolved to."""
    key: str                                 # claim_key(public id, imis id)
    imis_id: str                             # raw, as the CSV wrote it
    code_key: str = ""                       # canonical state_scheme_code
    imis_key: str = ""                       # canonical centre_scheme_id
    public_ids: list[str] = field(default_factory=list)   # SCH-… ids on these rows
    csv_rows: list[int] = field(default_factory=list)
    category: str = SCHEME_NOT_FOUND
    reason: str = ""
    # Which identifier produced the candidates — MATCH_PUBLIC_ID or
    # MATCH_CENTRE_ID, '' while nothing has resolved.
    matched_on: str = ""
    # Set when the CSV named a public id that did not resolve, saying why. It is
    # what separates "the file gave us nothing better" from "we do not hold what
    # the file gave us", which is the difference between a data problem upstream
    # and a backfill we still owe.
    fallback_reason: str = ""
    scheme_ids: set[int] = field(default_factory=set)
    # Every scheme the winning identifier found, id -> name, whether or not it
    # was mapped: a refused fan-out leaves scheme_ids empty but the report still
    # has to say what it refused.
    scheme_titles: dict[int, str] = field(default_factory=dict)
    # Every officer that made this claim, resolvable or not. The two are
    # kept apart because a scheme claimed only by officers we had to skip must
    # keep its mappings rather than be stripped of them.
    claimants: list["OfficerPlan"] = field(default_factory=list)

    @property
    def resolved(self) -> bool:
        return bool(self.scheme_ids)

    @property
    def public_id(self) -> str:
        """The public code this claim is resolved by, as the CSV wrote it."""
        return self.public_ids[0] if self.public_ids else self.code_key

    @property
    def scheme_names(self) -> list[str]:
        return [name for _, name in sorted(self.scheme_titles.items())]

    @property
    def writable_claimants(self) -> list["OfficerPlan"]:
        return [p for p in self.claimants if p.will_write]

    @property
    def mapped(self) -> bool:
        """Whether this claim actually puts an officer on a scheme.

        Deliberately not a statement about removal. Removal follows the officer,
        not the scheme — reconcile_mappings retires every live mapping of every
        section officer that the CSV does not restate — so no claim, resolved or
        refused, protects a scheme from the sweep. Saying otherwise here is what
        the report used to do, and it was wrong in exactly the case that matters:
        an officer whose claim this run could not use still loses the mappings
        they held under it.
        """
        return self.resolved and bool(self.writable_claimants)

    @property
    def subject(self) -> str:
        shown = ", ".join(self.public_ids[:3]) or "no scheme_public_id"
        if len(self.public_ids) > 3:
            shown += f", … (+{len(self.public_ids) - 3} more)"
        return f"imis {self.imis_id or '(blank)'} [{shown}]"


@dataclass
class SchemeLookup:
    """The live schemes, indexed by each of the two ids a claim can name.

    Both indexes map to a *list* of (id, name). state_scheme_code carries a
    partial UNIQUE index over live rows, so its lists should always hold one
    entry — the list is what lets a tenant that somehow holds two say so instead
    of the resolver picking one silently.
    """
    by_code: dict[str, list[tuple[int, str]]]
    by_centre: dict[str, list[tuple[int, str]]]
    # False on a tenant that has not taken V39: the public id cannot be looked
    # up at all there, which is a different thing from not holding it.
    code_column_present: bool


class SoDb(DivisionDb):
    """Reads the schemes and the section-officer mappings hanging off them.

    Extends the division tool's DivisionDb — which is itself the user tool's
    UserDb — so one object serves both halves of a CSV row and, more to the
    point, so the reconciliation loaders (load_mapping_rows,
    load_role_holder_ids, load_user_names) are the same code here as they are
    one and two rungs up the hierarchy. The departmental half of that base is
    simply never called: this file names its schemes outright and has no node to
    resolve. state_scheme_code_column_exists comes from the same base, so
    whether V39 has landed is asked exactly once and in exactly one way.
    """

    def load_scheme_lookup(self) -> SchemeLookup:
        """Every live scheme, indexed by public code and by centre id.

        Not the scheme tool's load_scheme_index: that reads the eleven columns a
        scheme *ingest* diffs against and indexes them by state id as well, none
        of which this file has an opinion about. What must not drift is how an
        id is canonicalised on both sides of the join, and that is scheme_id_key
        — shared, not re-implemented, and applied to the public code as well so
        that case and stray whitespace cannot hide a match.

        One scan builds both indexes: it is the same table either way, and the
        centre id is needed even for the rows whose public code resolves, since
        the report says which of the two answered.
        """
        by_code: dict[str, list[tuple[int, str]]] = {}
        by_centre: dict[str, list[tuple[int, str]]] = {}
        has_code = self.state_scheme_code_column_exists()
        # A literal NULL keeps the row shape identical on a pre-V39 tenant.
        code_expr = STATE_SCHEME_CODE_COLUMN if has_code else "NULL::varchar"
        with self.conn.cursor(name="so_scheme_scan") as cur:
            cur.itersize = 5000
            cur.execute(f"""
                SELECT id, {code_expr}, centre_scheme_id, scheme_name
                FROM {self.schema}.scheme_master_table
                WHERE deleted_at IS NULL
            """)
            for scheme_id, state_scheme_code, centre_scheme_id, scheme_name in cur:
                code_key = scheme_id_key(state_scheme_code)
                if code_key:
                    by_code.setdefault(code_key, []).append((scheme_id, scheme_name))
                centre_key = scheme_id_key(centre_scheme_id)
                if centre_key:
                    by_centre.setdefault(centre_key, []).append((scheme_id, scheme_name))
        return SchemeLookup(by_code, by_centre, has_code)


def resolve_schemes(
    rows: list[SoMappingRow], db: SoDb, map_ambiguous: bool = False
) -> dict[str, SchemePlan]:
    """One plan per scheme claim the CSV makes, resolved against scheme_master_table.

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
        plan = plans.get(row.claim_key)
        if plan is None:
            plan = SchemePlan(
                key=row.claim_key,
                imis_id=row.imis_id,
                code_key=row.code_key,
                imis_key=row.imis_key,
            )
            plans[row.claim_key] = plan
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
            reason="neither scheme_public_id nor imis_id — nothing to resolve the "
                   "scheme by",
        )

    resolvable = [p for p in plans.values() if p.category != SCHEME_NO_ID]
    if not resolvable:
        return plans

    LOG.info("  resolving %d scheme claim(s) against %s …", len(resolvable), db.schema)
    lookup = db.load_scheme_lookup()
    LOG.info("  %d live %s(s) and %d live centre id(s) in the tenant",
             len(lookup.by_code), STATE_SCHEME_CODE_COLUMN, len(lookup.by_centre))
    if not lookup.code_column_present:
        LOG.warning(
            "%s.scheme_master_table has no %s column — every claim is resolved by "
            "centre_scheme_id alone. Apply "
            "V39__add_state_scheme_code_to_scheme_master_table.sql to let the CSV's "
            "scheme_public_id resolve the schemes one centre id covers several of.",
            db.schema, STATE_SCHEME_CODE_COLUMN,
        )

    for plan in resolvable:
        _resolve_one_scheme(plan, lookup, map_ambiguous)

    refused = [p for p in resolvable if p.category == SCHEME_FANOUT and not p.resolved]
    if refused:
        LOG.warning(
            "%d claim(s) match several live schemes each and are not mapped; any "
            "section-officer mapping already held on those schemes is retired with "
            "everything else the CSV does not state. Pass --map-ambiguous-schemes to "
            "map every scheme behind such an id instead.",
            len(refused),
        )

    fell_back = [p for p in resolvable if p.fallback_reason]
    if fell_back and lookup.code_column_present:
        LOG.warning(
            "%d claim(s) name a scheme_public_id we do not hold and fell back to "
            "centre_scheme_id; %d of those fanned out over several schemes",
            len(fell_back),
            len([p for p in fell_back if p.category == SCHEME_FANOUT]),
        )
    return plans


def _resolve_one_scheme(
    plan: SchemePlan, lookup: SchemeLookup, map_ambiguous: bool
) -> None:
    """Public id first, centre id second — see the matching contract up top."""
    if plan.code_key:
        if not lookup.code_column_present:
            plan.fallback_reason = (
                f"this tenant has no {STATE_SCHEME_CODE_COLUMN} column (V39), so "
                f"scheme_public_id {plan.public_id!r} could not be looked up"
            )
        else:
            candidates = sorted(lookup.by_code.get(plan.code_key, []))
            if candidates:
                _apply_candidates(plan, candidates, MATCH_PUBLIC_ID, map_ambiguous)
                return
            plan.fallback_reason = (
                f"no live scheme carries {STATE_SCHEME_CODE_COLUMN} "
                f"{plan.public_id!r}"
            )

    candidates = sorted(lookup.by_centre.get(plan.imis_key, [])) if plan.imis_key else []
    if not candidates:
        plan.category = SCHEME_NOT_FOUND
        plan.reason = _with_fallback(
            plan,
            f"no live scheme has centre_scheme_id {plan.imis_id!r}" if plan.imis_key
            else "the row gives no imis_id to fall back to",
        )
        return
    _apply_candidates(plan, candidates, MATCH_CENTRE_ID, map_ambiguous)


def _apply_candidates(
    plan: SchemePlan,
    candidates: list[tuple[int, str]],
    matched_on: str,
    map_ambiguous: bool,
) -> None:
    """Turn the schemes an identifier found into the claim's outcome.

    Shared by both identifiers so that one scheme, several schemes and
    --map-ambiguous-schemes mean the same thing whichever of the two answered.
    """
    plan.matched_on = matched_on
    plan.scheme_titles = dict(candidates)
    if matched_on == MATCH_PUBLIC_ID:
        identifier = f"{STATE_SCHEME_CODE_COLUMN} {plan.public_id!r}"
        why_several = (
            "which the partial UNIQUE index V39 creates should make impossible — "
            "the tenant's data needs looking at"
        )
    else:
        identifier = f"centre_scheme_id {plan.imis_id!r}"
        why_several = (
            "our master is unique on the state/centre id pair, not on the centre id "
            "alone"
        )

    if len(candidates) == 1:
        plan.category = SCHEME_MATCHED
        plan.scheme_ids = {candidates[0][0]}
        plan.reason = _with_fallback(plan, f"matched on {identifier}")
        return

    plan.category = SCHEME_FANOUT
    if not map_ambiguous:
        plan.reason = _with_fallback(plan, (
            f"{identifier} matches {len(candidates)} live schemes ({why_several}) — "
            f"none of them is mapped, because the CSV does not say which one it "
            f"means; pass --map-ambiguous-schemes to map all of them"
        ))
        return
    plan.scheme_ids = {scheme_id for scheme_id, _ in candidates}
    plan.reason = _with_fallback(plan, (
        f"{identifier} matches {len(candidates)} live schemes ({why_several}) — "
        f"--map-ambiguous-schemes is set, so all of them are mapped"
    ))


def _with_fallback(plan: SchemePlan, reason: str) -> str:
    """Append why the public id did not settle it, when it was tried and failed."""
    return f"{reason}; {plan.fallback_reason}" if plan.fallback_reason else reason


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
            scheme = schemes.get(member.claim_key) if not member.scheme_issues else None
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


@dataclass
class SchemeContention:
    """One scheme this run would map to more than one section officer."""
    scheme_id: int
    title: str
    officers: list[OfficerPlan]
    # True when at least one of those officers reached it through a claim that
    # covered several schemes behind one id.
    via_fanout: bool
    csv_rows: int


def contested_schemes(plan: "SoIngestPlan") -> list[SchemeContention]:
    """The schemes several officers end up on, worked out scheme by scheme.

    Deliberately not per claim. Two claims that name different public ids can
    still fall back to one centre id and land on the same schemes, so asking
    "who is on this scheme?" is the only question that catches every shape of
    contention — a fan-out shared by two officers included, which is the one
    that over-grants.

    Only officers this run would actually map count. An officer it had to skip
    takes no coverage from anyone, and their own row in the report says why.
    """
    by_scheme: dict[int, list[OfficerPlan]] = defaultdict(list)
    titles: dict[int, str] = {}
    via_fanout: set[int] = set()
    csv_rows: Counter = Counter()

    for claim in plan.schemes.values():
        if not claim.resolved:
            continue
        titles.update(claim.scheme_titles)
        for scheme_id in claim.scheme_ids:
            csv_rows[scheme_id] += len(claim.csv_rows)
            if claim.category == SCHEME_FANOUT:
                via_fanout.add(scheme_id)
            holders = by_scheme[scheme_id]
            for officer in claim.writable_claimants:
                # Identity, not equality: one officer can reach the same scheme
                # through two claims, and OfficerPlan is not hashable.
                if not any(held is officer for held in holders):
                    holders.append(officer)

    return [
        SchemeContention(
            scheme_id=scheme_id,
            title=titles.get(scheme_id, ""),
            officers=holders,
            via_fanout=scheme_id in via_fanout,
            csv_rows=csv_rows[scheme_id],
        )
        for scheme_id, holders in sorted(by_scheme.items())
        if len(holders) > 1
    ]


def attribute_removals(officers: list[OfficerPlan],
                       reconciliation: "MappingReconciliation") -> None:
    """Hand each officer back the schemes the reconciler is taking from them.

    The reconciler works in row ids across the whole tenant; the per-officer
    sheets speak in scheme ids. This is the one translation between the two, and
    it is why mapping_detail can still say what a named officer loses without
    re-deriving it from a second, drifting copy of the rule.
    """
    by_user = {p.decision.existing_id: p for p in officers if p.decision.existing_id}
    for removal in reconciliation.coverage_removals:
        officer = by_user.get(removal.user_id)
        if officer is not None:
            officer.to_remove.add(removal.scheme_id)


# ─────────────────────────────────────────────────────────────────────────────
# Plan assembly
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class SoIngestPlan(MappingIngestPlan):
    """The engine's plan with the schemes resolved directly instead of via a node.

    MappingIngestPlan.divisions stays empty on purpose: this CSV names its
    schemes outright, so there is no departmental node between the officer and
    their schemes. What is inherited and does matter is the writable/blocked-role
    bookkeeping, the user-plan projection, the insert pairing and the whole
    reconciliation — which is now the same code that settles the two tools one
    and two rungs up the hierarchy.
    """
    schemes: dict[str, SchemePlan] = field(default_factory=dict)
    # Whether the tenant has taken V39. False turns every claim into a
    # centre_scheme_id one, which the report has to be able to say outright
    # rather than leave as tens of thousands of identical fall-backs.
    code_column_present: bool = True

    @property
    def officers(self) -> list[OfficerPlan]:
        """The engine calls these engineers; in this file they are section officers."""
        return self.engineers

    @property
    def named_schemes(self) -> list[SchemePlan]:
        """The claims the CSV actually made, without the no-id pseudo plan."""
        return [s for s in self.schemes.values() if s.category != SCHEME_NO_ID]

    @property
    def resolved_schemes(self) -> list[SchemePlan]:
        return [s for s in self.schemes.values() if s.resolved]

    @property
    def scheme_ids_covered(self) -> set[int]:
        return set().union(*(s.scheme_ids for s in self.resolved_schemes)) \
            if self.resolved_schemes else set()

def build_plan(
    rows: list[SoMappingRow],
    csv_issues: list[dict],
    db: SoDb,
    update_roles: bool = True,
    create_users_without_schemes: bool = False,
    replace: bool = True,
    map_ambiguous_schemes: bool = False,
) -> SoIngestPlan:
    LOG.info("Classifying %d CSV rows …", len(rows))
    schemes = resolve_schemes(rows, db, map_ambiguous=map_ambiguous_schemes)
    unresolved = [s for s in schemes.values()
                  if not s.resolved and s.category != SCHEME_NO_ID]
    if unresolved:
        LOG.warning("%d scheme claim(s) did not resolve to a live scheme", len(unresolved))
    no_id = schemes.get(SCHEME_NO_ID)
    if no_id is not None:
        LOG.warning("%d row(s) carry neither scheme id and are not mapped",
                    len(no_id.csv_rows))

    officers = build_officer_plans(
        rows, schemes, db,
        update_roles=update_roles,
        create_users_without_schemes=create_users_without_schemes,
    )

    # Every row of this file is a section officer, so the CSV claims authority
    # over exactly that role: a pump operator or SDO mapped to one of these
    # schemes is somebody else's statement and is never read, let alone retired.
    reconciliation = build_reconciliation(officers, db, (SO_ROLE,), prune=replace)
    attribute_removals(officers, reconciliation)
    if reconciliation.coverage_removals:
        LOG.warning("%d live section-officer mapping(s) are no longer stated by the CSV",
                    len(reconciliation.coverage_removals))

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
        roles=(SO_ROLE,),
        reconciliation=reconciliation,
        holder_names=db.load_user_names(reconciliation.affected_user_ids),
        schemes=schemes,
        code_column_present=db.state_scheme_code_column_exists(),
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
    contested = contested_schemes(plan)
    counts = plan.reconciliation.removals_by_reason()

    records = [
        {"metric": "CSV rows", "value": sum(len(p.csv_rows) for p in officers)},
        {"metric": "  rows with neither scheme id",
         "value": len(no_id.csv_rows) if no_id else 0},
        {"metric": "distinct scheme claims named", "value": len(schemes)},
        {"metric": "  claims resolved", "value": len(resolved)},
        {"metric": f"    by {STATE_SCHEME_CODE_COLUMN} (scheme_public_id)",
         "value": len([s for s in resolved if s.matched_on == MATCH_PUBLIC_ID])},
        {"metric": "    by centre_scheme_id (imis_id)",
         "value": len([s for s in resolved if s.matched_on == MATCH_CENTRE_ID])},
        {"metric": "  claims whose public id we do not hold (fell back to imis_id)",
         "value": len([s for s in schemes if s.fallback_reason])},
        {"metric": "  claims matching one scheme",
         "value": len([s for s in resolved if s.category == SCHEME_MATCHED])},
        {"metric": "  claims matching several schemes",
         "value": len([s for s in schemes if s.category == SCHEME_FANOUT])},
        {"metric": "    of those, refused (nothing mapped, existing mappings retired)",
         "value": len([s for s in schemes
                       if s.category == SCHEME_FANOUT and not s.resolved])},
        {"metric": "    of those, mapped in full (--map-ambiguous-schemes)",
         "value": len([s for s in schemes
                       if s.category == SCHEME_FANOUT and s.resolved])},
        {"metric": "    of those, ones a public id would have resolved",
         "value": len([s for s in schemes
                       if s.category == SCHEME_FANOUT and s.fallback_reason])},
        {"metric": "  claims not found",
         "value": len([s for s in schemes if s.category == SCHEME_NOT_FOUND])},
        {"metric": "schemes mapped to several officers", "value": len(contested)},
        {"metric": "  of those, ones reached through a fan-out",
         "value": len([c for c in contested if c.via_fanout])},
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
        {"metric": "scheme mappings to insert",
         "value": len(plan.reconciliation.to_insert)
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
        {"metric": "officers losing every mapping they hold",
         "value": len(plan.reconciliation.stripped_user_ids)},
        {"metric": "duplicate rows to collapse (no coverage lost)",
         "value": counts.get(REMOVAL_DUPLICATE, 0)},
        {"metric": "  pairs that held more than one live row",
         "value": len(plan.reconciliation.duplicates)},
        {"metric": "state_user_id backfills",
         "value": len([p for p in writable if FIELD_STATE_USER_ID in p.decision.changes])},
    ]
    return pd.DataFrame.from_records(records)


def build_scheme_frame(plan: SoIngestPlan, full: bool) -> pd.DataFrame:
    """One row per scheme claim. Clean matches are elided unless --full-scheme-sheet.

    31,000 rows of 'MATCHED' bury the handful an operator actually has to look
    at, so by default only the claims that did something unusual are written.
    A fall-back to the centre id that still matched one scheme is not unusual —
    it is every row until state_scheme_code is backfilled — so it is counted in
    the summary rather than listed here.
    """
    schemes = sorted(
        plan.schemes.values(), key=lambda s: (s.category, s.imis_id, s.key)
    )
    if not full:
        schemes = [
            s for s in schemes
            if s.category != SCHEME_MATCHED or len(s.claimants) > 1 or not s.mapped
        ]

    records = [
        {
            "scheme_public_ids": _fmt_ids(s.public_ids, limit=5),
            "imis_id": s.imis_id,
            "outcome": s.category,
            "matched_on": s.matched_on,
            "reason": s.reason,
            "csv_rows": len(s.csv_rows),
            "our_scheme_ids": _fmt_ids(s.scheme_ids),
            "our_scheme_names": _fmt_ids(s.scheme_names, limit=5),
            "officers_claiming_it": ", ".join(
                p.decision.row.name or "(unnamed)" for p in s.claimants
            ),
            "usable_officers": len(s.writable_claimants),
            # What this claim does to coverage. It never says a scheme is safe:
            # a mapping on it is retired if the CSV does not restate it, whatever
            # became of the claim — see SchemePlan.mapped.
            "anyone_mapped_from_it": "yes" if s.mapped else "no",
        }
        for s in schemes
    ]
    return pd.DataFrame.from_records(records) if records else pd.DataFrame(
        columns=["scheme_public_ids", "imis_id", "outcome", "matched_on", "reason",
                 "our_scheme_ids"]
    )


def build_mapping_frame(plan: SoIngestPlan, include_pii: bool) -> pd.DataFrame:
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
            "csv_rows": len(p.csv_rows),
            "section_officer": decision.row.name,
            "phone": show(p.phone),
            "existing_user_id": decision.existing_id,
            "scheme_claims_in_csv": len(p.schemes),
            "scheme_claims_resolved": len(p.resolved_schemes),
            "schemes_claimed": len(p.target_scheme_ids),
            "already_mapped": len(p.target_scheme_ids & p.existing_scheme_ids),
            "mappings_to_insert": len(fresh),
            "mappings_to_revive": len(revived),
            "mappings_to_retire": len(retired),
            "scheme_ids_to_insert": _fmt_ids(fresh),
            "scheme_ids_to_revive": _fmt_ids(revived),
            "scheme_ids_to_retire": _fmt_ids(retired),
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
        {"metric": "  of them only because a legacy mapping was retired",
         "value": len(plan.reconciliation.stripped_user_ids)},
    ])


def build_conflict_frame(plan: SoIngestPlan, include_pii: bool) -> pd.DataFrame:
    """Everything a human has to look at before executing."""
    show = (lambda p: p) if include_pii else safe_mask
    records: list[dict] = []

    for scheme in plan.schemes.values():
        if scheme.category == SCHEME_NO_ID:
            records.append({
                "kind": "SCHEME_NO_ID_AT_ALL",
                "csv_rows": len(scheme.csv_rows),
                "subject": f"{len(scheme.csv_rows)} row(s)",
                "detail": f"{scheme.reason}; scheme_public_id(s): "
                          f"{_fmt_ids(scheme.public_ids, limit=10)}",
            })
            continue
        if not scheme.resolved:
            records.append({
                # A fan-out is refused rather than unfindable, and reads as
                # "MATCHED_SEVERAL" otherwise — which is the opposite of what
                # happened to it.
                "kind": "SCHEME_AMBIGUOUS_NOT_MAPPED"
                if scheme.category == SCHEME_FANOUT else f"SCHEME_{scheme.category}",
                "csv_rows": len(scheme.csv_rows),
                "subject": scheme.subject,
                "detail": scheme.reason,
            })
        if scheme.resolved and not scheme.writable_claimants:
            records.append({
                "kind": "SCHEME_CLAIMANTS_ALL_SKIPPED",
                "csv_rows": len(scheme.csv_rows),
                "subject": scheme.subject,
                "detail": "every officer listed against this claim was skipped, so "
                          "nothing is mapped from it — and because removal follows the "
                          "officer rather than the scheme, the mappings those officers "
                          "held on it are retired all the same unless --additive",
            })

    # The public ids we could not use, as one record rather than tens of
    # thousands. Every claim falls back until state_scheme_code is backfilled,
    # so a row per claim would bury everything else on the sheet; what an
    # operator needs from here is the size of the gap and what it cost, and
    # scheme_detail carries the individual fan-outs it left behind.
    fell_back = [s for s in plan.named_schemes if s.fallback_reason]
    if fell_back:
        fanned = [s for s in fell_back if s.category == SCHEME_FANOUT]
        refused = [s for s in fanned if not s.resolved]
        records.append({
            "kind": "SCHEME_PUBLIC_ID_UNKNOWN_HERE",
            "csv_rows": sum(len(s.csv_rows) for s in fell_back),
            "subject": f"{len(fell_back)} claim(s)",
            "detail": (
                (f"this tenant has no {STATE_SCHEME_CODE_COLUMN} column (V39), so every "
                 f"claim was resolved by centre_scheme_id alone"
                 if not plan.code_column_present else
                 f"{len(fell_back)} claim(s) name a scheme_public_id no live scheme "
                 f"carries, so they were resolved by centre_scheme_id instead")
                + (f"; {len(fanned)} of those match several schemes each "
                   f"(e.g. {_fmt_ids([s.public_id for s in fanned], limit=5)}) and "
                   + ("are mapped to none of them"
                      if refused else "are mapped to all of them")
                   + f" — backfilling {STATE_SCHEME_CODE_COLUMN} would resolve each of "
                   f"them to exactly one scheme"
                   if fanned else
                   "; none of them matched several schemes, so each still resolved to one")
            ),
        })

    # Contention is a fact about a scheme, not about a claim: two officers can
    # arrive at one scheme down two different claims, and a fan-out puts them
    # there without the file ever saying so. A scheme several officers genuinely
    # share is legal — the mapping table is many-to-many — so the two are
    # reported apart rather than blurred together.
    for contested in contested_schemes(plan):
        names = ", ".join(p.decision.row.name or "(unnamed)" for p in contested.officers)
        records.append({
            "kind": "SCHEME_CONTESTED_FANOUT" if contested.via_fanout
            else "SCHEME_MULTIPLE_OFFICERS",
            "csv_rows": contested.csv_rows,
            "subject": f"scheme {contested.scheme_id} ({contested.title})",
            "detail": (
                f"{len(contested.officers)} officers ({names}) are mapped to this "
                f"scheme, at least one of them through an id that covers several "
                f"schemes and --map-ambiguous-schemes — we hold nothing that tells "
                f"those schemes apart, so this is coverage the CSV never stated"
                if contested.via_fanout else
                f"{len(contested.officers)} officers ({names}) are listed against this "
                f"scheme — all of them are mapped to it"
            ),
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
                "detail": f"{len(unresolved)} of this officer's {len(p.schemes)} scheme "
                          f"claim(s) resolved to nothing this run can map — nothing is "
                          f"mapped from them, and any mapping this officer already held "
                          f"under them is retired unless --additive",
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

    records.extend(legacy_holder_conflicts(plan))

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
    parser.add_argument("--map-ambiguous-schemes", action="store_true",
                        help="map the officer to every live scheme a single id matches. "
                             "By default such a claim is refused, so the officer covers "
                             "only what the CSV states unambiguously and any mapping "
                             "they held on the rest is retired with everything else the "
                             "CSV no longer states. Only the fall-back to imis_id can "
                             f"fan out in practice; a {STATE_SCHEME_CODE_COLUMN} is "
                             f"unique per live scheme")
    parser.add_argument("--skip-analytics", action="store_true",
                        help="do not touch the analytics warehouse")
    parser.add_argument("--no-role-updates", action="store_true",
                        help=f"never change an existing user's role to {SO_ROLE}, even when "
                             f"the CSV disagrees; the difference is still reported")
    parser.add_argument("--create-users-without-schemes", action="store_true",
                        help="onboard an officer whose scheme claims all failed to resolve; "
                             "by default they are skipped, because the account would have "
                             "no schemes and a re-run picks them up once the ids are fixed")
    parser.add_argument("--with-state-user-id", action="store_true",
                        help="also reconcile the CSV's public_id into user_table."
                             "state_user_id. Needs V36 to have been applied; without this "
                             "option the column is neither read nor written, so the mapping "
                             "can be ingested before the migration lands")
    parser.add_argument("--full-scheme-sheet", action="store_true",
                        help="write every scheme claim to the scheme_detail sheet; by "
                             "default only the ones that need a human eye are listed")
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
            "the run is authoritative and the CSV lists one row per scheme, so cutting "
            "it short would retire every mapping past the cut. Rehearse with --limit "
            "and no --execute."
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
            map_ambiguous_schemes=args.map_ambiguous_schemes,
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
            "scheme_matching": f"scheme_public_id -> {STATE_SCHEME_CODE_COLUMN} first, "
                               f"then imis_id -> centre_scheme_id"
                               if db.state_scheme_code_column_exists() else
                               f"imis_id -> centre_scheme_id only ({STATE_SCHEME_CODE_COLUMN} "
                               f"is not in this tenant; apply V39)",
            "ambiguous_schemes": "every scheme sharing the id that resolved is mapped"
            if args.map_ambiguous_schemes else
            "refused — nothing is mapped from a claim matching several schemes, and "
            "existing mappings on them are retired like any other the CSV omits",
            "analytics": "skipped" if args.skip_analytics else "included",
            "phones_in_report": "full" if args.include_pii else "masked",
            "scheme_sheet": "every scheme claim" if args.full_scheme_sheet
            else "only the claims that need a human eye",
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
