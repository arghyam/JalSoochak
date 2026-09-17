#!/usr/bin/env python3
"""
JJM user master-data ingestion for a Jal Soochak tenant (default: Assam / tenant_as).

Reads the state's user master CSV (a title line, then headers on row 2:
public_id, name, phone, role) and reconciles it against the tenant database
and the analytics warehouse.

Two modes:

  analyze  (default)  read-only. Writes an Excel analysis workbook describing
                      exactly what an execute run would do, and why.
  execute  (--execute) applies the inserts/updates inside transactions.

What it touches
---------------
common_schema:
  user_type_master_table           insert (roles the CSV uses that we lack)

tenant DB (shared_db), schema tenant_<code>:
  user_table                       insert / update (title, user_type, state_user_id)

analytics DB, schema analytics_schema:
  dim_user_table                   upsert (every user this run wrote)

Matching contract
-----------------
The phone number is the identity, exactly as it is everywhere else in the
platform: the CSV number is normalised to the 91XXXXXXXXXX form the DB stores,
HMAC-hashed, and looked up against user_table.phone_number_hash.

  role outside this ingestion's scope            -> skip the row (see Roles)
  no live user with this number                  -> insert
  exactly one live user                          -> update the fields that differ
  number repeated within the CSV                 -> skip every row for it
  number not a valid Indian mobile / blank name  -> skip the row

Nothing about a row is guessed at. A CSV row that cannot be used is reported in
the workbook rather than half-applied.

Fields updated on an existing user
----------------------------------
name           updated when it differs from ours (compared case/punctuation-
               insensitively, so 'PRATIJNA Mohan Bora' does not churn).
state_user_id  OPT-IN, --with-state-user-id. The CSV's public_id: filled in when
               missing, overwritten when it differs — the state master owns this
               identifier. Withheld (and reported) when another live user
               already carries that id, which the tenant's partial UNIQUE index
               would reject anyway.
               The option needs V36 to have been applied. Without it the column
               is neither read nor written and no statement mentions it, so the
               analysis — and an ingestion of names and roles — runs unchanged
               against a tenant that has not been migrated yet. A later run with
               the option on backfills every public_id.
role           the CSV master is authoritative, so a differing role is applied
               (--no-role-updates leaves every existing role alone). Two
               exceptions, always reported, never silent:

                 * an account holding an administrative role (PROTECTED_ROLES)
                   is never demoted by a spreadsheet;
                 * a promotion into a GATED_TARGET_ROLE — EXECUTIVE_ENGINEER,
                   most often from SECTION_OFFICER or SUB_DIVISIONAL_OFFICER —
                   needs --allow-officer-promotions. The gate is on the target
                   role, so no starting point slips past it, and it covers role
                   *changes* only: a new user the CSV already lists as an
                   EXECUTIVE_ENGINEER is onboarded as one.

               Either way the row's name and state_user_id still apply; only the
               role is held back, and the conflicts sheet lists every instance.

Phone number, email, password and status of an existing user are never touched.

Roles
-----
ROLE_ALIASES is an allow-list, and only four roles are in scope:

  jal-mitra           -> PUMP_OPERATOR
  section-officer     -> SECTION_OFFICER
  sdo                 -> SUB_DIVISIONAL_OFFICER
  executive-engineer  -> EXECUTIVE_ENGINEER

Every other role in the CSV — jal-sahayak and khalasi in the Assam master, and
anything a future export invents — is skipped as ROLE_NOT_INGESTED. Those rows
are counted in the workbook's role_summary sheet and listed one by one in
csv_issues, so what was left out is visible rather than merely absent. They are
never onboarded and never create a user type.

Of the four, only EXECUTIVE_ENGINEER is missing from a stock deployment; an
execute run inserts it into common_schema.user_type_master_table
(--no-create-roles aborts instead). Check role_summary before executing.

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
  python3 "scripts/jjm master data ingestion/users/jjm_user_master_ingest.py" \
      --csv "scripts/jjm master data ingestion/users/users-master.csv" \
      --actor-id 21357 --out "scripts/jjm master data ingestion/users/jjm_user_analysis.xlsx"

  # apply
  python3 "scripts/jjm master data ingestion/users/jjm_user_master_ingest.py" \
      --csv "scripts/jjm master data ingestion/users/users-master.csv" \
      --actor-id 21357 --out jjm_user_analysis.xlsx --execute

  # once V36 is applied, add the public_id -> state_user_id reconciliation
  python3 "scripts/jjm master data ingestion/users/jjm_user_master_ingest.py" \
      --csv "scripts/jjm master data ingestion/users/users-master.csv" \
      --actor-id 21357 --out jjm_user_analysis.xlsx --with-state-user-id --execute
"""

from __future__ import annotations

import argparse
import logging
import os
import re
import sys
import uuid as uuid_mod
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
    import psycopg2.extras
except ImportError:  # pragma: no cover
    sys.exit("psycopg2 is required:  pip install psycopg2-binary")

# The scheme tool next door already owns the PII crypto, the phone/name
# normalisation and the dim_user_table upsert, all of which must stay
# byte-identical to what the services do. Importing them keeps one copy of that
# contract instead of two that can drift apart.
_SCHEME_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, "scheme")
sys.path.insert(0, _SCHEME_DIR)
try:
    from jjm_scheme_master_ingest import (  # noqa: E402
        EMAIL_DOMAIN,
        ONBOARD_PASSWORD,
        USER_STATUS_ACTIVE,
        AnalyticsWriter,
        PiiCrypto,
        TenantDb,
        mask_phone,
        norm_name,
        normalise_phone,
    )
except ImportError as exc:  # pragma: no cover
    sys.exit(
        f"Cannot import the scheme ingestion module from {_SCHEME_DIR!r}: {exc}\n"
        f"Keep this script alongside its sibling 'scheme/jjm_scheme_master_ingest.py'."
    )


LOG = logging.getLogger("jjm-user-ingest")

# ─────────────────────────────────────────────────────────────────────────────
# Domain constants — mirrored from the Java services. Keep in sync.
# ─────────────────────────────────────────────────────────────────────────────

CSV_COLUMNS = ["public_id", "name", "phone", "role"]

# CSV role slug -> common_schema.user_type_master_table.c_name.
#
# This is an ALLOW-LIST, and the only roles this ingestion covers. A CSV row
# naming anything else — jal-sahayak, khalasi, or a role invented in some future
# export — is skipped as ROLE_NOT_INGESTED and reported, never guessed at and
# never turned into a new user type. Widening the scope means adding the slug
# here (plus an EMAIL_PREFIXES entry) and nothing else.
ROLE_ALIASES = {
    "jal-mitra": "PUMP_OPERATOR",
    "jalmitra": "PUMP_OPERATOR",
    "jal-mitras": "PUMP_OPERATOR",
    "pump-operator": "PUMP_OPERATOR",
    "section-officer": "SECTION_OFFICER",
    "so": "SECTION_OFFICER",
    "sdo": "SUB_DIVISIONAL_OFFICER",
    "sub-divisional-officer": "SUB_DIVISIONAL_OFFICER",
    "executive-engineer": "EXECUTIVE_ENGINEER",
    "ee": "EXECUTIVE_ENGINEER",
}

# The canonical names the allow-list can produce. Of these, only
# EXECUTIVE_ENGINEER is missing from a stock deployment, so it is the only role
# an execute run can ever create.
INGESTED_ROLES = frozenset(ROLE_ALIASES.values())

# Accounts holding one of these are never demoted by the CSV: a spreadsheet
# listing someone as a field role must not strip an administrator's access.
# Their name and state_user_id are still reconciled; only the role is withheld.
PROTECTED_ROLES = {"SUPER_USER", "STATE_ADMIN", "SUPER_STATE_ADMIN", "SUPPORT_ADMIN"}

# Moving an EXISTING user into one of these is a promotion into a senior
# departmental post — most often SECTION_OFFICER or SUB_DIVISIONAL_OFFICER ->
# EXECUTIVE_ENGINEER. It is withheld and reported unless
# --allow-officer-promotions is passed, so a widened access level is always a
# decision somebody made rather than a side effect of importing a sheet.
#
# Gating is on the TARGET role, so no starting point slips past it. It applies
# to role *changes* only: onboarding a brand-new user the CSV already lists as
# an EXECUTIVE_ENGINEER is not a promotion and is never gated.
GATED_TARGET_ROLES = {"EXECUTIVE_ENGINEER"}

# PumpOperatorUploadChunkProcessor.emailPrefix — the generated login address for
# an onboarded user is derived from their phone number and role.
EMAIL_PREFIXES = {
    "PUMP_OPERATOR": "po_",
    "SECTION_OFFICER": "so_",
    "SUB_DIVISIONAL_OFFICER": "sdo_",
    "EXECUTIVE_ENGINEER": "ee_",
}
# Unreachable while every allow-listed role has a prefix above; kept so that
# adding a role to ROLE_ALIASES cannot silently produce a "None…" address.
DEFAULT_EMAIL_PREFIX = "usr_"

# user_table columns update_users may touch, with the cast each one needs in the
# bulk UPDATE ... FROM (VALUES ...). A VALUES list has no types of its own, so a
# page whose values are all NULL would otherwise be typed as text and abort the
# transaction. Doubles as the allow-list of columns that may be interpolated.
USER_UPDATE_COLUMN_TYPES = {
    "title": "text",
    "title_hash": "text",
    "user_type": "integer",
    "state_user_id": "varchar",
}

# Fields shown in the workbook's per-row diff, in the order they are reported.
FIELD_NAME = "name"
FIELD_ROLE = "role"
FIELD_STATE_USER_ID = "state_user_id"

CAT_NEW = "NEW_USER"
CAT_EXISTING = "EXISTING_USER"
CAT_DUPLICATE = "DUPLICATE_WITHIN_CSV"
CAT_ROLE_NOT_INGESTED = "ROLE_NOT_INGESTED"
CAT_INVALID = "INVALID_CSV_ROW"

CATEGORY_ORDER = [CAT_NEW, CAT_EXISTING, CAT_DUPLICATE, CAT_ROLE_NOT_INGESTED, CAT_INVALID]
CATEGORY_ACTION = {
    CAT_NEW: "insert",
    CAT_EXISTING: "update (only the fields that differ)",
    CAT_DUPLICATE: "skip",
    CAT_ROLE_NOT_INGESTED: "skip",
    CAT_INVALID: "skip",
}
CATEGORY_DESCRIPTION = {
    CAT_NEW: "No live user holds this phone number",
    CAT_EXISTING: "One live user holds this phone number",
    CAT_DUPLICATE: "The phone number or public_id appears on more than one CSV row",
    CAT_ROLE_NOT_INGESTED: "The row's role is outside this ingestion's scope "
                           "(see ROLE_ALIASES) — deliberately not onboarded",
    CAT_INVALID: "CSV row cannot be used (blank name/role or unusable phone number)",
}


# ─────────────────────────────────────────────────────────────────────────────
# Normalisation helpers
# ─────────────────────────────────────────────────────────────────────────────

def clean(value: Any) -> str:
    """CSV cell -> clean string, tolerating the NaN pandas yields for gaps."""
    if value is None:
        return ""
    try:
        if pd.isna(value):
            return ""
    except (TypeError, ValueError):
        pass
    text = str(value).strip()
    return "" if text.lower() in {"nan", "none", "null"} else text


def role_slug(raw: Any) -> str:
    """'Jal Mitra' / 'JAL_MITRA' -> 'jal-mitra'. Punctuation-insensitive."""
    text = clean(raw).lower()
    if not text:
        return ""
    return re.sub(r"[^a-z0-9]+", "-", text).strip("-")


def canonical_role(raw: Any) -> str:
    """'sdo' -> 'SUB_DIVISIONAL_OFFICER'; anything off the allow-list -> ''.

    Returning '' is what marks a row as out of scope. Roles are deliberately not
    invented from the slug: creating a user type from a spreadsheet value would
    let one bad cell add a permission level nobody reviewed.
    """
    return ROLE_ALIASES.get(role_slug(raw), "")


def email_prefix(role: str) -> str:
    return EMAIL_PREFIXES.get(role, DEFAULT_EMAIL_PREFIX)


def safe_mask(value: str) -> str:
    """mask_phone, but never reveals a number too short for it to mask."""
    if not value:
        return ""
    if len(value) < 8:
        return "X" * len(value)
    return mask_phone(value)


# ─────────────────────────────────────────────────────────────────────────────
# CSV model
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class UserRow:
    row_no: int                 # 1-based row number as shown in the CSV
    public_id: str
    name: str
    phone_raw: str
    phone: str                  # normalised 91XXXXXXXXXX, '' when unusable
    role_raw: str
    role: str                   # canonical c_name, '' when off the allow-list
    issues: list[str] = field(default_factory=list)

    @property
    def blocking_issues(self) -> list[str]:
        """Issues that stop the row from being written at all."""
        return [i for i in self.issues if i.startswith("row:")]

    @property
    def role_not_ingested(self) -> bool:
        """A role was given, and it is one this ingestion does not cover.

        Distinct from a blocking issue: the row is fine, we are simply not
        onboarding that kind of person.
        """
        return bool(self.role_raw) and not self.role


def load_csv(path: str, header_row: int, encoding: str) -> tuple[list[UserRow], list[dict]]:
    """Read the CSV and normalise every row. Returns (rows, per-row issue records).

    The state's export puts a title line ('users(17)') above the header, hence
    the header_row argument; keep_default_na is off so a phone like '0091…'
    survives as text rather than becoming a float.
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

    rows: list[UserRow] = []
    issue_records: list[dict] = []

    for offset, raw in enumerate(frame.to_dict("records")):
        # +1 for the header row itself, +1 to make it 1-based like the file.
        row_no = header_row + 1 + offset
        if all(not clean(raw.get(c)) for c in CSV_COLUMNS):
            continue

        issues: list[str] = []

        public_id = clean(raw.get("public_id"))
        if not public_id:
            issues.append("state_user_id:blank public_id — user written without a state_user_id")

        name = clean(raw.get("name"))
        if not name:
            issues.append("row:blank name")

        phone_raw = clean(raw.get("phone"))
        phone = normalise_phone(phone_raw) or ""
        if not phone:
            # Never echo the number itself: the workbook carries a masked copy.
            issues.append("row:phone is not a valid Indian mobile number")

        role_raw = clean(raw.get("role"))
        role = canonical_role(role_raw)
        if not role_raw:
            issues.append("row:blank role")
        elif not role:
            # Not a "row:" issue — the row is legible, its role is simply
            # outside what this ingestion onboards.
            issues.append(
                f"role:'{role_raw}' is not a role this ingestion covers "
                f"(covered: {', '.join(sorted(INGESTED_ROLES))}) — row skipped"
            )

        row = UserRow(
            row_no=row_no,
            public_id=public_id,
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
                "public_id": public_id,
                "name": name,
                "issue_kind": kind,
                "issue": detail,
            })

    return rows, issue_records


def candidate_rows(rows: list[UserRow]) -> list[UserRow]:
    """The rows this run would actually write — everything else is already out.

    Duplicate detection runs over these alone, so a khalasi row cannot knock out
    the jal-mitra row that happens to share its phone number: a role we are not
    ingesting has no say over one we are.
    """
    return [r for r in rows if not r.blocking_issues and not r.role_not_ingested]


def find_csv_duplicates(rows: list[UserRow]) -> tuple[dict[str, list[int]], dict[str, list[int]]]:
    """Phones and public_ids repeated among the candidate rows.

    Both break the 1:1 contract: one person cannot be reconciled against two
    rows that disagree on name, role or public_id, and two rows sharing a
    public_id cannot both own it (the tenant's partial UNIQUE index says so).
    """
    by_phone: dict[str, list[int]] = defaultdict(list)
    by_public_id: dict[str, list[int]] = defaultdict(list)
    for row in candidate_rows(rows):
        if row.phone:
            by_phone[row.phone].append(row.row_no)
        if row.public_id:
            by_public_id[row.public_id.lower()].append(row.row_no)
    return (
        {k: v for k, v in by_phone.items() if len(v) > 1},
        {k: v for k, v in by_public_id.items() if len(v) > 1},
    )


# ─────────────────────────────────────────────────────────────────────────────
# Tenant database access
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class UserTypeRow:
    id: int
    c_name: str
    deleted: bool


class UserDb(TenantDb):
    """Reads tenant_<code>.user_table and common_schema.user_type_master_table.

    Extends the scheme tool's TenantDb (same connection, schema validation and
    PII crypto) with the state_user_id-aware lookups this ingestion needs.

    state_user_id is opt-in (--with-state-user-id). With it off, the column is
    neither read nor written and nothing here references it, so the whole tool —
    analysis and execution both — runs against a database where V36 has not been
    applied yet. Names and roles reconcile exactly the same either way.
    """

    def __init__(self, conn, schema: str, pii: PiiCrypto,
                 with_state_user_id: bool = False) -> None:
        super().__init__(conn, schema, pii)
        self.with_state_user_id = with_state_user_id

    def state_user_id_column_exists(self) -> bool:
        with self.conn.cursor() as cur:
            cur.execute("""
                SELECT 1 FROM information_schema.columns
                WHERE table_schema = %s AND table_name = 'user_table'
                  AND column_name = 'state_user_id'
            """, (self.schema,))
            return cur.fetchone() is not None

    def assert_state_user_id_column(self) -> None:
        """Fail on the first query, not halfway through the run — with the
        option on, every lookup below reads state_user_id, the column V36 adds."""
        if not self.state_user_id_column_exists():
            raise SystemExit(
                f"{self.schema}.user_table has no state_user_id column, which "
                f"--with-state-user-id needs. Apply "
                f"backend/database/V36__add_state_user_id_to_user_table.sql first, "
                f"or drop the option to reconcile names and roles only."
            )

    def load_user_types(self) -> dict[str, UserTypeRow]:
        """Every role we hold, including soft-deleted ones.

        Soft-deleted rows matter: c_name is UNIQUE, so a deleted 'KHALASI' still
        occupies the name and inserting it again would fail.
        """
        with self.conn.cursor() as cur:
            cur.execute(
                "SELECT id, c_name, deleted_at IS NOT NULL "
                "FROM common_schema.user_type_master_table"
            )
            return {
                c_name.strip().upper(): UserTypeRow(type_id, c_name, deleted)
                for type_id, c_name, deleted in cur
            }

    def load_users_for_phones(self, phone_hashes: Iterable[str]) -> dict[str, dict]:
        """Look up users by HMAC of the normalised phone (the encrypted column
        cannot be searched). Returns hash -> user record incl. decrypted title."""
        hashes = [h for h in dict.fromkeys(phone_hashes) if h]
        if not hashes:
            return {}

        # With the option off the column may not exist at all, so it is not
        # named; the placeholder keeps the row shape identical either way.
        state_user_id_expr = (
            "u.state_user_id" if self.with_state_user_id else "NULL::varchar"
        )

        found: dict[str, dict] = {}
        with self.conn.cursor() as cur:
            for start in range(0, len(hashes), 5000):
                batch = hashes[start:start + 5000]
                cur.execute(f"""
                    SELECT u.id, u.uuid, u.phone_number_hash, u.title, u.user_type,
                           u.status, u.email, {state_user_id_expr}, ut.c_name
                    FROM {self.schema}.user_table u
                    LEFT JOIN common_schema.user_type_master_table ut ON ut.id = u.user_type
                    WHERE u.deleted_at IS NULL
                      AND u.phone_number_hash = ANY(%s)
                """, (batch,))
                for (uid, uuid, phash, title_enc, user_type, status, email,
                     state_user_id, c_name) in cur:
                    # Several rows can share a hash only if data is already
                    # corrupt; keep the lowest id so behaviour stays deterministic.
                    if phash in found and found[phash]["id"] <= uid:
                        continue
                    found[phash] = {
                        "id": uid,
                        "uuid": uuid,
                        "title": self.pii.safe_decrypt(title_enc),
                        "user_type": user_type,
                        "role": (c_name or "").strip().upper(),
                        "status": status,
                        "email": email,
                        "state_user_id": state_user_id,
                    }
        return found

    def load_state_user_id_owners(self, codes: Iterable[str]) -> dict[str, int]:
        """lower(state_user_id) -> id of the live user already holding it."""
        if not self.with_state_user_id:
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
                    SELECT lower(state_user_id), id
                    FROM {self.schema}.user_table
                    WHERE deleted_at IS NULL AND lower(state_user_id) = ANY(%s)
                """, (wanted[start:start + 5000],))
                for code, uid in cur:
                    owners.setdefault(code, uid)
        return owners


# ─────────────────────────────────────────────────────────────────────────────
# Classification
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class UserDecision:
    row: UserRow
    category: str
    reason: str = ""
    existing_id: Optional[int] = None
    existing_uuid: Optional[str] = None
    existing_name: Optional[str] = None
    existing_role: str = ""
    existing_state_user_id: Optional[str] = None
    existing_status: Optional[int] = None
    # logical field -> (old, new); see FIELD_* constants.
    changes: dict[str, tuple[Any, Any]] = field(default_factory=dict)
    # Fields deliberately left alone, with the reason. Always reported.
    withheld: dict[str, str] = field(default_factory=dict)
    # True when the withheld role was a promotion into a GATED_TARGET_ROLE —
    # counted separately so the operator can see how many are waiting on
    # --allow-officer-promotions rather than on a data fix.
    gated_promotion: bool = False
    # Set on insert, so the analytics projection can address the new row.
    email: str = ""

    @property
    def will_write(self) -> bool:
        return self.category in {CAT_NEW, CAT_EXISTING}

    @property
    def action(self) -> str:
        if self.category == CAT_NEW:
            return "insert"
        if self.category == CAT_EXISTING:
            return "update" if self.changes else "unchanged"
        return "skip"

    def db_updates(self, pii: PiiCrypto, user_type_ids: dict[str, int]) -> dict[str, Any]:
        """The logical diff expressed as user_table columns and their values.

        The name is stored twice — encrypted in title, HMAC'd in title_hash —
        exactly as PiiEncryptionService and UserTenantRepository expect; keeping
        that pairing here is why the update path takes a logical diff and not a
        column diff.
        """
        updates: dict[str, Any] = {}
        if FIELD_NAME in self.changes:
            new_name = self.changes[FIELD_NAME][1]
            updates["title"] = pii.encrypt(new_name)
            updates["title_hash"] = pii.title_hash(new_name)
        if FIELD_ROLE in self.changes:
            updates["user_type"] = user_type_ids[self.changes[FIELD_ROLE][1]]
        if FIELD_STATE_USER_ID in self.changes:
            updates["state_user_id"] = self.changes[FIELD_STATE_USER_ID][1]
        return updates


def classify_users(
    rows: list[UserRow],
    db: UserDb,
    update_roles: bool = True,
    allow_promotions: bool = False,
) -> list[UserDecision]:
    """Resolve every CSV row against the tenant DB in bulk."""
    dup_phone, dup_public_id = find_csv_duplicates(rows)

    decisions: list[UserDecision] = []
    resolvable: list[UserDecision] = []

    for row in rows:
        if row.blocking_issues:
            decisions.append(UserDecision(row, CAT_INVALID, reason="; ".join(row.blocking_issues)))
            continue
        if row.role_not_ingested:
            decisions.append(UserDecision(
                row, CAT_ROLE_NOT_INGESTED,
                reason=f"role '{row.role_raw}' is outside this ingestion's scope",
            ))
            continue
        if row.phone in dup_phone:
            others = [r for r in dup_phone[row.phone] if r != row.row_no]
            decisions.append(UserDecision(
                row, CAT_DUPLICATE,
                reason=f"phone number repeated within the CSV (also on row(s) {others})",
            ))
            continue
        if row.public_id and row.public_id.lower() in dup_public_id:
            others = [r for r in dup_public_id[row.public_id.lower()] if r != row.row_no]
            decisions.append(UserDecision(
                row, CAT_DUPLICATE,
                reason=f"public_id repeated within the CSV (also on row(s) {others})",
            ))
            continue

        decision = UserDecision(row, CAT_NEW)
        decisions.append(decision)
        resolvable.append(decision)

    LOG.info("  %d usable rows, resolving them against %s …", len(resolvable), db.schema)
    hash_by_phone = {d.row.phone: db.pii.hmac(d.row.phone) for d in resolvable}
    existing = db.load_users_for_phones(hash_by_phone.values())
    # A public_id may already sit on somebody else; the partial UNIQUE index
    # would reject a second owner, so find out before writing rather than after.
    owners = db.load_state_user_id_owners(d.row.public_id for d in resolvable if d.row.public_id)

    for decision in resolvable:
        match = existing.get(hash_by_phone[decision.row.phone])
        if match is None:
            decision.category = CAT_NEW
            decision.reason = "no live user holds this phone number"
            if db.with_state_user_id:
                _plan_new_state_user_id(decision, owners)
            continue

        decision.category = CAT_EXISTING
        decision.reason = f"phone number resolves to user id {match['id']}"
        decision.existing_id = match["id"]
        decision.existing_uuid = match["uuid"]
        decision.existing_name = match["title"]
        decision.existing_role = match["role"]
        decision.existing_state_user_id = match["state_user_id"]
        decision.existing_status = match["status"]
        _compute_changes(
            decision, owners, update_roles, db.with_state_user_id, allow_promotions
        )

    return decisions


def _plan_new_state_user_id(decision: UserDecision, owners: dict[str, int]) -> None:
    """A brand-new user still has to clear the state_user_id uniqueness rule."""
    public_id = decision.row.public_id
    if not public_id:
        return
    owner = owners.get(public_id.lower())
    if owner is not None:
        decision.withheld[FIELD_STATE_USER_ID] = (
            f"public_id {public_id} already belongs to user id {owner} — "
            f"left unset on the new user"
        )


def _compute_changes(
    decision: UserDecision,
    owners: dict[str, int],
    update_roles: bool,
    with_state_user_id: bool,
    allow_promotions: bool,
) -> None:
    """Fill decision.changes / .withheld with what an update would apply."""
    row = decision.row

    if norm_name(decision.existing_name or "") != norm_name(row.name):
        decision.changes[FIELD_NAME] = (decision.existing_name, row.name)

    if with_state_user_id and row.public_id and (
            decision.existing_state_user_id or "") != row.public_id:
        owner = owners.get(row.public_id.lower())
        if owner is not None and owner != decision.existing_id:
            decision.withheld[FIELD_STATE_USER_ID] = (
                f"public_id {row.public_id} already belongs to user id {owner}"
            )
        else:
            decision.changes[FIELD_STATE_USER_ID] = (
                decision.existing_state_user_id, row.public_id
            )

    if row.role and decision.existing_role != row.role:
        if not update_roles:
            decision.withheld[FIELD_ROLE] = (
                f"we hold {decision.existing_role or 'no role'}, CSV says {row.role} — "
                f"--no-role-updates is set"
            )
        elif decision.existing_role in PROTECTED_ROLES:
            decision.withheld[FIELD_ROLE] = (
                f"user id {decision.existing_id} holds the administrative role "
                f"{decision.existing_role}; the CSV's {row.role} is not applied"
            )
        elif row.role in GATED_TARGET_ROLES and not allow_promotions:
            decision.gated_promotion = True
            decision.withheld[FIELD_ROLE] = (
                f"promotion {decision.existing_role or 'no role'} -> {row.role} "
                f"withheld — pass --allow-officer-promotions to apply it"
            )
        else:
            decision.changes[FIELD_ROLE] = (decision.existing_role or None, row.role)


# ─────────────────────────────────────────────────────────────────────────────
# Role reconciliation
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class RolePlan:
    role: str
    csv_slugs: list[str]
    csv_rows: int
    existing_id: Optional[int]
    action: str          # existing | create | blocked_soft_deleted | not_ingested


def build_role_plans(
    decisions: list[UserDecision], user_types: dict[str, UserTypeRow]
) -> list[RolePlan]:
    """Every role the CSV names: the ones we ingest, and the ones we skip.

    The skipped ones are listed too, with their row counts — a role silently
    missing from this report would be indistinguishable from a role absent from
    the CSV, and the whole point is that the operator can see what was left out.
    """
    slugs: dict[str, set[str]] = defaultdict(set)
    counts: Counter = Counter()
    skipped_counts: Counter = Counter()

    for decision in decisions:
        row = decision.row
        if decision.category == CAT_ROLE_NOT_INGESTED:
            skipped_counts[role_slug(row.role_raw)] += 1
            continue
        if not decision.will_write or not row.role:
            continue
        counts[row.role] += 1
        slugs[row.role].add(row.role_raw.lower())

    plans: list[RolePlan] = []
    for role in sorted(counts):
        held = user_types.get(role)
        if held is None:
            action, existing_id = "create", None
        elif held.deleted:
            action, existing_id = "blocked_soft_deleted", held.id
        else:
            action, existing_id = "existing", held.id
        plans.append(RolePlan(
            role=role,
            csv_slugs=sorted(slugs[role]),
            csv_rows=counts[role],
            existing_id=existing_id,
            action=action,
        ))

    # No canonical name exists for these — they are shown by their CSV slug.
    for slug in sorted(skipped_counts):
        plans.append(RolePlan(
            role=slug,
            csv_slugs=[slug],
            csv_rows=skipped_counts[slug],
            existing_id=None,
            action="not_ingested",
        ))
    return plans


# ─────────────────────────────────────────────────────────────────────────────
# Tenant database writes
# ─────────────────────────────────────────────────────────────────────────────

class UserWriter:
    """Every write is parameterised; only the validated schema is interpolated."""

    def __init__(self, db: UserDb, tenant_id: int, actor_id: int) -> None:
        self.db = db
        self.conn = db.conn
        self.schema = db.schema
        self.pii = db.pii
        self.tenant_id = tenant_id
        self.actor_id = actor_id

    def assert_actor_is_tenant_user(self) -> None:
        """created_by / updated_by should point at a real person in this tenant;
        a typo'd id is otherwise invisible in the audit trail forever."""
        with self.conn.cursor() as cur:
            cur.execute(
                f"SELECT 1 FROM {self.schema}.user_table WHERE id = %s AND deleted_at IS NULL",
                (self.actor_id,),
            )
            if cur.fetchone() is None:
                raise SystemExit(
                    f"--actor-id {self.actor_id} is not a live row in {self.schema}.user_table. "
                    f"It is recorded as created_by/updated_by on everything this run writes."
                )

    def create_roles(self, plans: list[RolePlan]) -> dict[str, int]:
        """Insert the roles the CSV needs and we do not hold. Returns name -> id.

        created_by / updated_by are left NULL on purpose: they carry a foreign
        key to common_schema.tenant_admin_user_master_table, and --actor-id is a
        tenant user_table id, which is a different table entirely.
        """
        to_create = [p.role for p in plans if p.action == "create"]
        if not to_create:
            return {}
        with self.conn.cursor() as cur:
            created = psycopg2.extras.execute_values(
                cur,
                """
                INSERT INTO common_schema.user_type_master_table (c_name, created_at, updated_at)
                VALUES %s
                RETURNING c_name, id
                """,
                [(role,) for role in to_create],
                template="(%s,NOW(),NOW())",
                fetch=True,
            )
        return {name.strip().upper(): type_id for name, type_id in created}

    def insert_users(self, decisions: list[UserDecision], user_type_ids: dict[str, int]) -> None:
        """Create users the same way PumpOperatorUploadChunkProcessor does.

        Collisions on the generated email are resolved against one bulk lookup
        rather than a query per person — the address is derived from the phone
        number, so two rows can never generate the same one and the only way it
        can be taken is by a row we did not create (a soft-deleted user counts:
        the uniqueness constraint on email does not exclude them).
        """
        if not decisions:
            return

        with_state_user_id = self.db.with_state_user_id
        candidates = [
            f"{email_prefix(d.row.role)}{d.row.phone}{EMAIL_DOMAIN}" for d in decisions
        ]
        taken = self.db.emails_in_use(candidates)

        payload = []
        for decision, email in zip(decisions, candidates):
            if email.lower() in taken:
                email = (
                    f"{email_prefix(decision.row.role)}{decision.row.phone}"
                    f"_{uuid_mod.uuid4()}{EMAIL_DOMAIN}"
                )
            taken.add(email.lower())
            decision.email = email
            decision.existing_uuid = str(uuid_mod.uuid4())
            values = [
                decision.existing_uuid, self.tenant_id,
                self.pii.encrypt(decision.row.name), self.pii.title_hash(decision.row.name),
                email, user_type_ids[decision.row.role],
                self.pii.encrypt(decision.row.phone), self.pii.hmac(decision.row.phone),
            ]
            if with_state_user_id:
                # Withheld public_ids stay NULL rather than colliding.
                values.append(
                    None if FIELD_STATE_USER_ID in decision.withheld
                    else (decision.row.public_id or None)
                )
            values += [ONBOARD_PASSWORD, USER_STATUS_ACTIVE, self.actor_id, self.actor_id]
            payload.append(tuple(values))

        # The column is left out of the statement entirely when the option is
        # off, so the insert works on a tenant that has not had V36 applied.
        state_user_id_column = "state_user_id, " if with_state_user_id else ""
        state_user_id_value = "%s," if with_state_user_id else ""
        sql = f"""
            INSERT INTO {self.schema}.user_table
                (uuid, tenant_id, title, title_hash, email, user_type,
                 phone_number, phone_number_hash, {state_user_id_column}password, status,
                 email_verification_status, phone_verification_status,
                 created_by, created_at, updated_by, updated_at)
            VALUES %s
            RETURNING id
        """
        with self.conn.cursor() as cur:
            ids = psycopg2.extras.execute_values(
                cur, sql, payload,
                template=f"(%s,%s,%s,%s,%s,%s,%s,%s,{state_user_id_value}%s,%s,"
                         f"true,true,%s,NOW(),%s,NOW())",
                page_size=500, fetch=True,
            )
        # execute_values preserves input order in RETURNING, page by page.
        for decision, row in zip(decisions, ids):
            decision.existing_id = row[0]

    def update_users(self, decisions: list[UserDecision], user_type_ids: dict[str, int]) -> int:
        """Apply per-row column diffs. Rows whose diff is empty are not touched.

        Each row changes its own set of columns, so the work is grouped by that
        set and every group goes out as one UPDATE ... FROM (VALUES ...) per
        page — the 27k-row master spans only a handful of distinct column sets,
        which turns thousands of round trips into a few dozen.

        Column names come from db_updates, never from the CSV, and are checked
        against USER_UPDATE_COLUMN_TYPES before being interpolated.
        """
        groups: dict[tuple[str, ...], list[tuple[UserDecision, dict[str, Any]]]] = defaultdict(list)
        for decision in decisions:
            updates = decision.db_updates(self.pii, user_type_ids)
            if not updates:
                continue
            unknown = set(updates) - set(USER_UPDATE_COLUMN_TYPES)
            if unknown:
                raise ValueError(
                    f"No column type registered for {sorted(unknown)} — add it to "
                    f"USER_UPDATE_COLUMN_TYPES before updating that column."
                )
            groups[tuple(sorted(updates))].append((decision, updates))

        updated = 0
        with self.conn.cursor() as cur:
            for columns, batch in groups.items():
                assignments = ", ".join(f"{c} = v.{c}" for c in columns)
                sql = f"""
                    UPDATE {self.schema}.user_table AS t
                    SET {assignments}, updated_by = v.updated_by, updated_at = NOW()
                    FROM (VALUES %s) AS v (id, updated_by, {", ".join(columns)})
                    WHERE t.id = v.id AND t.deleted_at IS NULL
                    RETURNING t.id
                """
                template = "(%s::integer, %s::integer, " + ", ".join(
                    f"%s::{USER_UPDATE_COLUMN_TYPES[c]}" for c in columns
                ) + ")"
                payload = [
                    (decision.existing_id, self.actor_id, *(updates[c] for c in columns))
                    for decision, updates in batch
                ]
                # cur.rowcount only reflects the last page, so count what came back.
                touched = psycopg2.extras.execute_values(
                    cur, sql, payload, template=template, page_size=500, fetch=True,
                )
                updated += len(touched)
        return updated


# ─────────────────────────────────────────────────────────────────────────────
# Plan assembly
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class IngestPlan:
    decisions: list[UserDecision]
    role_plans: list[RolePlan]
    user_types: dict[str, UserTypeRow]
    csv_issues: list[dict]
    dup_phone: dict[str, list[int]]
    dup_public_id: dict[str, list[int]]
    # False = the CSV's public_id is reported but never written (V36 not needed).
    with_state_user_id: bool = False
    # False = promotions into a GATED_TARGET_ROLE are reported, not applied.
    allow_promotions: bool = False

    def by_category(self) -> dict[str, list[UserDecision]]:
        grouped: dict[str, list[UserDecision]] = defaultdict(list)
        for decision in self.decisions:
            grouped[decision.category].append(decision)
        return grouped

    @property
    def blocked_roles(self) -> list[RolePlan]:
        return [p for p in self.role_plans if p.action == "blocked_soft_deleted"]

    @property
    def gated_promotions(self) -> list[UserDecision]:
        return [d for d in self.decisions if d.gated_promotion]


def build_plan(
    rows: list[UserRow],
    csv_issues: list[dict],
    db: UserDb,
    update_roles: bool = True,
    allow_promotions: bool = False,
) -> IngestPlan:
    LOG.info("Classifying %d CSV rows …", len(rows))
    dup_phone, dup_public_id = find_csv_duplicates(rows)
    if dup_phone or dup_public_id:
        LOG.warning(
            "CSV repeats %d phone number(s) and %d public_id(s) — those rows are skipped",
            len(dup_phone), len(dup_public_id),
        )

    decisions = classify_users(rows, db, update_roles, allow_promotions)

    user_types = db.load_user_types()
    role_plans = build_role_plans(decisions, user_types)
    for plan in role_plans:
        if plan.action == "create":
            LOG.warning("Role %s is not in user_type_master_table — %d CSV rows need it",
                        plan.role, plan.csv_rows)
        elif plan.action == "not_ingested":
            LOG.warning("Role '%s' is outside this ingestion's scope — %d CSV rows skipped",
                        plan.role, plan.csv_rows)

    return IngestPlan(
        decisions=decisions,
        role_plans=role_plans,
        user_types=user_types,
        csv_issues=csv_issues,
        dup_phone=dup_phone,
        dup_public_id=dup_public_id,
        with_state_user_id=db.with_state_user_id,
        allow_promotions=allow_promotions,
    )


# ─────────────────────────────────────────────────────────────────────────────
# Analysis workbook
# ─────────────────────────────────────────────────────────────────────────────

def _fmt_changes(changes: dict[str, tuple[Any, Any]]) -> str:
    return "; ".join(f"{f}: {old!r} -> {new!r}" for f, (old, new) in sorted(changes.items()))


def _fmt_withheld(withheld: dict[str, str]) -> str:
    return "; ".join(f"{f}: {why}" for f, why in sorted(withheld.items()))


def build_summary_frame(plan: IngestPlan) -> pd.DataFrame:
    grouped = plan.by_category()
    records = [
        {
            "category": category,
            "what it means": CATEGORY_DESCRIPTION[category],
            "action": CATEGORY_ACTION[category],
            "csv rows": len(grouped.get(category, [])),
        }
        for category in CATEGORY_ORDER
    ]
    records.append({
        "category": "TOTAL", "what it means": "", "action": "",
        "csv rows": len(plan.decisions),
    })

    existing = grouped.get(CAT_EXISTING, [])
    # A row whose only difference was withheld is NOT up to date — it is waiting
    # on a decision, and is counted on the withheld line below instead.
    no_op = [d for d in existing if not d.changes and not d.withheld]
    records.append({
        "category": "(of the matched rows) already up to date",
        "what it means": "matched a live user, nothing differs, nothing withheld",
        "action": "no write",
        "csv rows": len(no_op),
    })
    for field_name in (FIELD_NAME, FIELD_ROLE, FIELD_STATE_USER_ID):
        out_of_scope = field_name == FIELD_STATE_USER_ID and not plan.with_state_user_id
        records.append({
            "category": f"(of the matched rows) {field_name} updated",
            "what it means": "not in scope for this run — --with-state-user-id is off, "
                             "so the CSV's public_id is neither read nor written"
            if out_of_scope
            else f"{field_name} differs from ours and is overwritten",
            "action": "not applicable" if out_of_scope else "update",
            "csv rows": len([d for d in existing if field_name in d.changes]),
        })
    records.append({
        "category": "fields withheld (reported, never written)",
        "what it means": "protected role, gated promotion, or a public_id another "
                         "user already owns",
        "action": "no write",
        "csv rows": len([d for d in plan.decisions if d.withheld]),
    })
    records.append({
        "category": "…of those, promotions to "
                    f"{'/'.join(sorted(GATED_TARGET_ROLES))}",
        "what it means": "an existing user the CSV promotes; applied only under "
                         "--allow-officer-promotions"
        if not plan.allow_promotions
        else "already applied — --allow-officer-promotions is set",
        "action": "no write" if not plan.allow_promotions else "update",
        "csv rows": len(plan.gated_promotions),
    })
    return pd.DataFrame.from_records(records)


def build_role_frame(plan: IngestPlan) -> pd.DataFrame:
    records = [
        {
            "canonical_role": p.role,
            "csv_role_values": ", ".join(p.csv_slugs),
            "csv_rows": p.csv_rows,
            "user_type_id": p.existing_id,
            "action": {
                "existing": "already in user_type_master_table",
                "create": "INSERT into user_type_master_table",
                "blocked_soft_deleted": "BLOCKED: the role exists but is soft-deleted",
                "not_ingested": "NOT INGESTED: outside this ingestion's scope, "
                                "every row with this role is skipped",
            }[p.action],
        }
        for p in plan.role_plans
    ]
    return pd.DataFrame.from_records(records) if records else pd.DataFrame(
        columns=["canonical_role", "csv_role_values", "csv_rows", "user_type_id", "action"]
    )


def build_user_detail_frame(plan: IngestPlan, include_pii: bool) -> pd.DataFrame:
    show = (lambda p: p) if include_pii else safe_mask
    records = [
        {
            "row_no": d.row.row_no,
            "category": d.category,
            "action": d.action,
            "reason": d.reason,
            "public_id": d.row.public_id,
            "csv_name": d.row.name,
            "our_name": d.existing_name or "",
            "phone": show(d.row.phone) if d.row.phone else show(d.row.phone_raw),
            "csv_role": d.row.role_raw,
            "canonical_role": d.row.role,
            "our_role": d.existing_role,
            "existing_user_id": d.existing_id,
            "our_state_user_id": d.existing_state_user_id or "",
            "our_status": d.existing_status,
            "fields_to_change": _fmt_changes(d.changes),
            "fields_withheld": _fmt_withheld(d.withheld),
        }
        for d in plan.decisions
    ]
    return pd.DataFrame.from_records(records) if records else pd.DataFrame(
        columns=["row_no", "category", "action", "reason"]
    )


def build_conflict_frame(plan: IngestPlan, include_pii: bool) -> pd.DataFrame:
    """Everything a human has to look at: skipped rows and withheld fields."""
    show = (lambda p: p) if include_pii else safe_mask
    records = []
    for decision in plan.decisions:
        # Out-of-scope roles are not conflicts — they are counted in
        # role_summary and listed row by row in csv_issues, and putting several
        # hundred of them here would bury the rows that do need a decision.
        if decision.category in (CAT_DUPLICATE, CAT_INVALID):
            records.append({
                "row_no": decision.row.row_no,
                "kind": decision.category,
                "public_id": decision.row.public_id,
                "csv_name": decision.row.name,
                "phone": show(decision.row.phone) if decision.row.phone
                else show(decision.row.phone_raw),
                "detail": decision.reason,
            })
        for field_name, why in sorted(decision.withheld.items()):
            records.append({
                "row_no": decision.row.row_no,
                "kind": f"WITHHELD_{field_name.upper()}",
                "public_id": decision.row.public_id,
                "csv_name": decision.row.name,
                "phone": show(decision.row.phone) if decision.row.phone
                else show(decision.row.phone_raw),
                "detail": why,
            })
    return pd.DataFrame.from_records(records) if records else pd.DataFrame(
        columns=["row_no", "kind", "public_id", "csv_name", "phone", "detail"]
    )


def build_analytics_frame(plan: IngestPlan) -> pd.DataFrame:
    grouped = plan.by_category()
    written = [d for d in plan.decisions if d.will_write and (d.category == CAT_NEW or d.changes)]
    return pd.DataFrame.from_records([
        {"metric": "users inserted into user_table", "value": len(grouped.get(CAT_NEW, []))},
        {"metric": "users updated in user_table",
         "value": len([d for d in grouped.get(CAT_EXISTING, []) if d.changes])},
        {"metric": "user_type_master_table rows created",
         "value": len([p for p in plan.role_plans if p.action == "create"])},
        {"metric": "dim_user_table rows upserted (inserted + updated)", "value": len(written)},
    ])


def write_analysis_workbook(plan: IngestPlan, path: str, include_pii: bool, context: dict) -> None:
    run_info = pd.DataFrame.from_records(
        [{"setting": k, "value": str(v)} for k, v in context.items()]
    )
    issues = pd.DataFrame.from_records(plan.csv_issues) if plan.csv_issues else pd.DataFrame(
        columns=["row_no", "public_id", "name", "issue_kind", "issue"]
    )

    with pd.ExcelWriter(path, engine="openpyxl") as writer:
        run_info.to_excel(writer, sheet_name="run_info", index=False)
        build_summary_frame(plan).to_excel(writer, sheet_name="user_summary", index=False)
        build_role_frame(plan).to_excel(writer, sheet_name="role_summary", index=False)
        build_analytics_frame(plan).to_excel(writer, sheet_name="analytics_summary", index=False)
        build_conflict_frame(plan, include_pii).to_excel(
            writer, sheet_name="conflicts", index=False)
        issues.to_excel(writer, sheet_name="csv_issues", index=False)
        build_user_detail_frame(plan, include_pii).to_excel(
            writer, sheet_name="user_detail", index=False)

    LOG.info("Analysis workbook written to %s", path)


# ─────────────────────────────────────────────────────────────────────────────
# Execute
# ─────────────────────────────────────────────────────────────────────────────

def execute_tenant(plan: IngestPlan, writer: UserWriter, create_roles: bool) -> dict[str, int]:
    """Apply the whole tenant-side plan in one transaction."""
    stats: dict[str, int] = {}

    to_create = [p for p in plan.role_plans if p.action == "create"]
    if to_create and not create_roles:
        raise SystemExit(
            "The CSV needs role(s) we do not hold: "
            + ", ".join(p.role for p in to_create)
            + ". Re-run without --no-create-roles, or seed them first."
        )
    created = writer.create_roles(plan.role_plans)
    stats["user_types_created"] = len(created)

    user_type_ids = {
        name: row.id for name, row in plan.user_types.items() if not row.deleted
    }
    user_type_ids.update(created)

    grouped = plan.by_category()
    inserts = grouped.get(CAT_NEW, [])
    writer.insert_users(inserts, user_type_ids)
    stats["users_inserted"] = len(inserts)

    updates = [d for d in grouped.get(CAT_EXISTING, []) if d.changes]
    stats["users_updated"] = writer.update_users(updates, user_type_ids)
    stats["users_unchanged"] = len(grouped.get(CAT_EXISTING, [])) - len(updates)
    return stats


def execute_analytics(
    plan: IngestPlan, analytics: AnalyticsWriter, db: UserDb
) -> dict[str, int]:
    """Project the post-state of every user this run wrote into the warehouse.

    The authoritative values are read back from the tenant DB rather than
    assembled from the CSV, so a withheld role or state_user_id cannot leak into
    dim_user_table as if it had been applied.
    """
    written = [
        d for d in plan.decisions
        if d.will_write and d.existing_id and (d.category == CAT_NEW or d.changes)
    ]
    if not written:
        return {"dim_user_rows_upserted": 0}

    ids = [d.existing_id for d in written]
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
    for decision in written:
        row = snapshot.get(decision.existing_id)
        if row is None:
            continue
        uuid, email, user_type, title, status = row
        users.append({
            "user_id": decision.existing_id,
            "uuid": uuid,
            "email": email,
            "user_type": user_type,
            # dim_user_table.title holds the plaintext name, exactly as the
            # user-service publishes it on a UserUpdated event.
            "title": title,
            "status": status,
        })

    return {"dim_user_rows_upserted": analytics.upsert_users(users)}


# ─────────────────────────────────────────────────────────────────────────────
# CLI
# ─────────────────────────────────────────────────────────────────────────────

def parse_args(argv: Optional[list[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Reconcile the JJM user master CSV into a Jal Soochak tenant "
                    "+ analytics warehouse.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__.split("Usage")[-1],
    )
    parser.add_argument("--csv", required=True, help="path to the user master CSV")
    parser.add_argument("--header-row", type=int, default=2,
                        help="1-based row holding the column headers (default: 2)")
    parser.add_argument("--encoding", default="utf-8-sig",
                        help="CSV encoding (default: utf-8-sig)")
    parser.add_argument("--schema", default="tenant_as", help="tenant schema (default: tenant_as)")
    parser.add_argument("--tenant-id", type=int, default=None,
                        help="tenant id; resolved from the schema's state code when omitted")
    parser.add_argument("--actor-id", type=int, required=True,
                        help="user_table.id recorded as created_by/updated_by")
    parser.add_argument("--out", default="jjm_user_analysis.xlsx",
                        help="analysis workbook path (default: jjm_user_analysis.xlsx)")
    parser.add_argument("--tenant-dsn", default=os.environ.get("TENANT_DSN"),
                        help="tenant DB DSN (default: $TENANT_DSN)")
    parser.add_argument("--analytics-dsn", default=os.environ.get("ANALYTICS_DSN"),
                        help="analytics DB DSN (default: $ANALYTICS_DSN)")
    parser.add_argument("--execute", action="store_true",
                        help="apply the plan; without this the run is read-only")
    parser.add_argument("--skip-analytics", action="store_true",
                        help="do not touch the analytics warehouse")
    parser.add_argument("--no-role-updates", action="store_true",
                        help="never change an existing user's role, even when the CSV "
                             "disagrees; the difference is still reported")
    parser.add_argument("--allow-officer-promotions", action="store_true",
                        help="apply role changes that promote an existing user into "
                             f"{', '.join(sorted(GATED_TARGET_ROLES))}. Withheld and "
                             "reported by default, whatever role the user holds today; "
                             "onboarding a new user the CSV already lists in that role "
                             "is unaffected. Has no effect under --no-role-updates")
    parser.add_argument("--no-create-roles", action="store_true",
                        help="abort instead of inserting an in-scope role that "
                             "user_type_master_table does not hold (EXECUTIVE_ENGINEER "
                             "on a stock deployment)")
    parser.add_argument("--with-state-user-id", action="store_true",
                        help="also reconcile the CSV's public_id into "
                             "user_table.state_user_id. Needs V36 to have been applied; "
                             "without this option the column is neither read nor written, "
                             "so names and roles can be analysed (and ingested) before "
                             "the migration lands")
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
        db = UserDb(tenant_conn, args.schema, pii, args.with_state_user_id)
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
            allow_promotions=args.allow_officer_promotions,
        )

        context = {
            "generated_at": datetime.now().isoformat(timespec="seconds"),
            "csv": args.csv,
            "csv_rows": len(rows),
            "tenant_schema": args.schema,
            "tenant_id": tenant_id,
            "actor_id": args.actor_id,
            "mode": "EXECUTE" if args.execute else "ANALYZE (read-only)",
            "analytics": "skipped" if args.skip_analytics else "included",
            "phones_in_report": "full" if args.include_pii else "masked",
            "role_updates": "withheld" if args.no_role_updates else "applied",
            "promotions_into_" + "_".join(sorted(GATED_TARGET_ROLES)):
                "applied" if args.allow_officer_promotions else "WITHHELD (reported only)",
            "new_roles": "blocked" if args.no_create_roles else "created",
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

        writer = UserWriter(db, tenant_id, args.actor_id)
        writer.assert_actor_is_tenant_user()

        if not args.skip_analytics:
            analytics_conn = psycopg2.connect(args.analytics_dsn)
            analytics_conn.autocommit = False
            analytics = AnalyticsWriter(analytics_conn, tenant_id)
            analytics.assert_tenant_exists()

        LOG.info("Applying tenant changes …")
        tenant_stats = execute_tenant(plan, writer, create_roles=not args.no_create_roles)
        for key, value in tenant_stats.items():
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


def _print_summary(plan: IngestPlan) -> None:
    grouped = plan.by_category()
    LOG.info("─" * 72)
    LOG.info("%-46s %8s  %s", "CATEGORY", "ROWS", "ACTION")
    for category in CATEGORY_ORDER:
        LOG.info("%-46s %8d  %s", category, len(grouped.get(category, [])),
                 CATEGORY_ACTION[category])
    LOG.info("%-46s %8d", "TOTAL", len(plan.decisions))
    LOG.info("─" * 72)
    existing = grouped.get(CAT_EXISTING, [])
    for field_name in (FIELD_NAME, FIELD_ROLE, FIELD_STATE_USER_ID):
        if field_name == FIELD_STATE_USER_ID and not plan.with_state_user_id:
            LOG.info("%-46s %8s", "existing users / state_user_id updated",
                     "n/a (--with-state-user-id off)")
            continue
        LOG.info("%-46s %8d", f"existing users / {field_name} updated",
                 len([d for d in existing if field_name in d.changes]))
    LOG.info("%-46s %8d", "existing users / unchanged",
             len([d for d in existing if not d.changes and not d.withheld]))
    LOG.info("%-46s %8d", "fields withheld (see conflicts sheet)",
             len([d for d in plan.decisions if d.withheld]))
    if plan.gated_promotions:
        LOG.warning(
            "%-46s %8d  pass --allow-officer-promotions to apply",
            f"promotions to {'/'.join(sorted(GATED_TARGET_ROLES))} WITHHELD",
            len(plan.gated_promotions),
        )
    LOG.info("─" * 72)
    for role_plan in plan.role_plans:
        LOG.info("%-46s %8d  %s", f"role / {role_plan.role}", role_plan.csv_rows,
                 role_plan.action)
    LOG.info("─" * 72)


if __name__ == "__main__":
    sys.exit(main())
