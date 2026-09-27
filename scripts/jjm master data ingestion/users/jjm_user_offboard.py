#!/usr/bin/env python3
"""
JJM user offboarding for a Jal Soochak tenant (default: Assam / tenant_as).

The mirror image of jjm_user_master_ingest.py. That tool answers "who does the
state's master sheet name that we do not hold?"; this one answers "who do we
hold, in these roles, that the state's sheets no longer name?" — and takes them
out of service: soft-deleted by default, or merely deactivated under
--deactivate-only.

Two modes:

  analyze  (default)  read-only. Writes an Excel analysis workbook naming every
                      user an execute run would act on, and every user it would
                      spare, with the reason for each.
  execute  (--execute) applies the plan inside transactions.

What it touches
---------------
tenant DB (shared_db), schema tenant_<code>:
  user_table                   soft delete (status = 0, deleted_at, deleted_by)
  user_scheme_mapping_table    soft delete of the deleted users' live mappings

analytics DB, schema analytics_schema:
  dim_user_table               status = 0 for every user deleted
  dim_user_scheme_mapping_table  the deleted users' rows removed

--deactivate-only writes none of the deletions. See "Deactivating instead".

Scope
-----
--roles selects which roles are considered, and only three are offboardable:

  so   / section-officer      -> SECTION_OFFICER
  sdo  / sub-divisional-officer -> SUB_DIVISIONAL_OFFICER
  ee   / executive-engineer   -> EXECUTIVE_ENGINEER

Anything else — pump operators, jal sahayaks, an administrative role, a slug a
future export invents — is skipped with a warning and reported in the workbook's
roles sheet, never guessed at. If nothing offboardable survives the filter the
run aborts rather than proceeding against an empty scope.

The role that counts is the one the TENANT holds today (user_table.user_type),
not what any sheet says: this tool decides who to remove, and the role a person
is being removed from has to be the role we currently have them in.

Matching contract
-----------------
The phone number is the identity, exactly as it is everywhere else in the
platform: every phone in every sheet is normalised to the 91XXXXXXXXXX form the
DB stores, HMAC-hashed, and compared against user_table.phone_number_hash.

A live user in one of the selected roles is SPARED when:

  their phone appears in the user master CSV       whatever role that row names,
                                                   including roles the ingestion
                                                   does not cover (jal-sahayak,
                                                   khalasi). Present in the
                                                   master is present, full stop.
  their phone appears in any protection CSV        the three mapping sheets are
                                                   read by default; see below.
  they hold a Keycloak-managed password            they have logged in at least
                                                   once, so they are a live
                                                   account a sheet may not
                                                   remove. Always reported.
  they have no usable phone_number_hash            the matching contract cannot
                                                   speak about them, so it does
                                                   not get to delete them.
  they are --actor-id                              the run's own author.

Everything else in scope is soft-deleted — or deactivated, under
--deactivate-only. Who is spared, and why, is identical either way.

Protection CSVs
---------------
The three mapping sheets next door are read by default, because a user named in
one of them is in active service whatever the user master happens to say:

  subdiv-sdo-mapping/subdivision-sdo-mapping.csv
  so-scheme-mapping/section-officer-scheme-mapping.csv
  div-ee-mapping/divsion-executive-engineer-mapping.csv

Every column whose name mentions a phone or a mobile is harvested from each, so
the three files' differing column names (sdo_phone, section_officer_phone,
executive_engineer_phone) need no per-file configuration. A default file that is
missing aborts the run: silently losing a protection source is exactly how a
person still in service gets deleted. Pass --no-default-protect-csvs to run
without them deliberately, and --protect-csv to add more.

What a soft delete writes
-------------------------
  status = 0, deleted_at = NOW(), deleted_by = --actor-id, updated_by, updated_at

status is set alongside deleted_at on purpose. The staff login path looks a user
up by phone hash WITHOUT filtering deleted_at (UserTenantRepository
.findByPhoneNumber) and gates on status alone (StaffAuthServiceImpl), so
deleted_at by itself would leave a "deleted" officer able to log in. This is the
same pair UserCommonRepository.deactivateAdminUser writes.

Nobody this tool deletes has a Keycloak account — an account with one holds a
managed password and is spared — so there is no Keycloak cleanup to do. Their
user_scheme_mapping_table rows are soft-deleted the way UserUploadRepository
does it, so no live mapping is left pointing at a deleted person.

Deactivating instead
--------------------
--deactivate-only writes status = 0 and nothing else, mirroring
UserTenantRepository.deactivateStaffUser — the same thing the staff screen's
deactivate button does. deleted_at is not set, so:

  * the user stays a live row: still listed, still countable, still theirs to
    reactivate through the existing endpoint;
  * their scheme mappings are left alone, because a reactivated officer who came
    back without their schemes would be worse than one who never left;
  * the warehouse gets the status change but keeps dim_user_scheme_mapping_table.

Everything else — the scope, who is spared and why, the ceiling, the workbook —
is identical. It is the same decision, applied with a reversible write, and it
is the right mode when the sheets are the first evidence of a departure rather
than the last.

Safety
------
--max-deletions is required to execute, in both modes — a reversible write is
still a write against every officer in scope. The run aborts before writing
anything if the plan exceeds it, so a truncated or mis-parsed sheet cannot
quietly empty a role. Analysis mode is uncapped: run it first and read the number
off the summary. A master CSV that yields no usable phone at all aborts either
way.

state_user_id (V36) is used for reporting only. A user whose state_user_id
appears in one of the sheets while their phone does not is still acted on — the
phone is the identity — but every such case is logged as a warning and flagged
in the workbook, because it usually means the sheet's phone changed rather than
that the person left.

PII
---
user_table.title and .phone_number are AES-256-GCM encrypted and looked up via
HMAC-SHA256 hashes, mirroring the services' PiiEncryptionService. This script
therefore needs the target environment's PII_ENCRYPTION_KEY and PII_HMAC_KEY.
Phone numbers are masked in the analysis workbook unless --include-pii is given,
and are never logged.

Usage
-----
  export PII_ENCRYPTION_KEY=...  PII_HMAC_KEY=...
  export TENANT_DSN='host=localhost port=5432 dbname=shared_db user=postgres password=password@1123'
  export ANALYTICS_DSN='host=localhost port=5432 dbname=analytics user=postgres password=password@1123'

  # dry run -> analysis workbook only
  python3 "scripts/jjm master data ingestion/users/jjm_user_offboard.py" \
      --csv "scripts/jjm master data ingestion/users/users-master.csv" \
      --roles so,sdo,ee --actor-id 21357 \
      --out "scripts/jjm master data ingestion/users/jjm_user_offboard_analysis.xlsx"

  # apply, refusing to delete more than 250 users
  python3 "scripts/jjm master data ingestion/users/jjm_user_offboard.py" \
      --csv "scripts/jjm master data ingestion/users/users-master.csv" \
      --roles so,sdo,ee --actor-id 21357 --max-deletions 250 --execute

  # same decision, reversible: status = 0 and nothing else
  python3 "scripts/jjm master data ingestion/users/jjm_user_offboard.py" \
      --csv "scripts/jjm master data ingestion/users/users-master.csv" \
      --roles so,sdo,ee --actor-id 21357 --max-deletions 250 \
      --deactivate-only --execute
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
except ImportError:  # pragma: no cover
    sys.exit("psycopg2 is required:  pip install psycopg2-binary")

# The ingestion tools next door already own the PII crypto, the phone
# normalisation, the tenant user lookups and the warehouse writer, all of which
# must stay byte-identical to what the services do. Importing them keeps one
# copy of that contract instead of a second one that can drift.
_BASE_DIR = os.path.dirname(os.path.abspath(__file__))
_SCHEME_DIR = os.path.join(_BASE_DIR, os.pardir, "scheme")
for _dir in (_BASE_DIR, _SCHEME_DIR):
    sys.path.insert(0, _dir)
try:
    from jjm_scheme_master_ingest import (  # noqa: E402
        AnalyticsWriter,
        PiiCrypto,
        normalise_phone,
    )
    from jjm_user_master_ingest import (  # noqa: E402
        UserDb,
        UserWriter,
        canonical_role,
        clean,
        load_csv,
        safe_mask,
    )
except ImportError as exc:  # pragma: no cover
    sys.exit(
        f"Cannot import the sibling ingestion modules from {_BASE_DIR!r} / "
        f"{_SCHEME_DIR!r}: {exc}\n"
        f"Keep this script alongside 'users/jjm_user_master_ingest.py' and "
        f"'scheme/jjm_scheme_master_ingest.py'."
    )


LOG = logging.getLogger("jjm-user-offboard")

# ─────────────────────────────────────────────────────────────────────────────
# Domain constants — mirrored from the Java services. Keep in sync.
# ─────────────────────────────────────────────────────────────────────────────

# The only roles --roles may select. Field staff (PUMP_OPERATOR) and every
# administrative role are deliberately absent: this tool exists to reconcile the
# departmental officer hierarchy against the state's sheets, and a sheet has no
# business removing anybody else.
OFFBOARDABLE_ROLES = ("SECTION_OFFICER", "SUB_DIVISIONAL_OFFICER", "EXECUTIVE_ENGINEER")

# TenantUserStatus.INACTIVE. Written alongside deleted_at because the staff login
# path gates on status and ignores deleted_at — see the module docstring.
USER_STATUS_INACTIVE = 0

# user_table.password values that mean "no Keycloak account was ever
# provisioned", copied from UserTenantRepository
# .updateKeycloakUuidAndPasswordIfUnmanaged. Anything else is an AES-GCM managed
# password, which means the person has logged in — and is out of this tool's
# reach.
PLACEHOLDER_PASSWORDS = {"CSV_ONBOARDED", "KEYCLOAK_MANAGED"}

# The mapping sheets read as protection sources unless --no-default-protect-csvs
# is passed, relative to this tool's parent directory.
DEFAULT_PROTECT_CSVS = (
    os.path.join("subdiv-sdo-mapping", "subdivision-sdo-mapping.csv"),
    os.path.join("so-scheme-mapping", "section-officer-scheme-mapping.csv"),
    os.path.join("div-ee-mapping", "divsion-executive-engineer-mapping.csv"),
)

# Protection CSVs are read generically: any column whose name mentions a phone
# carries an identity, whatever the sheet calls it (sdo_phone,
# section_officer_phone, executive_engineer_phone).
PHONE_COLUMN_RE = re.compile(r"phone|mobile")

# The officer's own state id is always an unprefixed public_id. Prefixed ones
# (scheme_public_id, division_public_id, subdivision_public_id) identify a
# scheme or a location, not a person, and must not be harvested. 'pubic_id' is
# the typo the subdivision sheet actually ships with.
PUBLIC_ID_COLUMNS = frozenset({"public_id", "pubic_id"})

MASTER_SOURCE = "users-master"

CAT_IN_MASTER = "IN_MASTER_CSV"
CAT_IN_PROTECT = "IN_MAPPING_CSV"
CAT_ACTOR = "ACTOR"
CAT_NO_PHONE = "NO_PHONE_HASH"
CAT_HAS_LOGIN = "HAS_KEYCLOAK_ACCOUNT"
# Named for the reason, like every other category — what happens to them is the
# run's mode, and the report's action column says which.
CAT_ABSENT = "NOT_IN_ANY_SHEET"

CATEGORY_ORDER = [
    CAT_ABSENT, CAT_IN_MASTER, CAT_IN_PROTECT, CAT_HAS_LOGIN, CAT_NO_PHONE, CAT_ACTOR,
]
CATEGORY_ACTION = {
    CAT_ABSENT: "soft delete (user + scheme mappings + warehouse)",
    CAT_IN_MASTER: "keep",
    CAT_IN_PROTECT: "keep",
    CAT_HAS_LOGIN: "keep",
    CAT_NO_PHONE: "keep",
    CAT_ACTOR: "keep",
}
# What CAT_ABSENT means under --deactivate-only. Only the write changes; who is
# in the category, and why, does not.
DEACTIVATE_ACTION = "deactivate (status = 0 only, mappings kept)"
CATEGORY_DESCRIPTION = {
    CAT_ABSENT: "In a selected role and named by none of the sheets",
    CAT_IN_MASTER: "The user master CSV lists this phone number",
    CAT_IN_PROTECT: "A protection CSV lists this phone number",
    CAT_HAS_LOGIN: "Holds a Keycloak-managed password — the account has been "
                   "logged into and is out of this tool's reach",
    CAT_NO_PHONE: "No usable phone_number_hash, so the matching contract cannot "
                  "speak about this user",
    CAT_ACTOR: "This run's --actor-id",
}


# ─────────────────────────────────────────────────────────────────────────────
# Role selection
# ─────────────────────────────────────────────────────────────────────────────

def parse_roles(values: Iterable[str]) -> tuple[list[str], list[dict]]:
    """--roles -> (canonical roles in scope, one record per value rejected).

    Accepts the same vocabulary as the ingestion's ROLE_ALIASES — 'so',
    'section-officer', 'SECTION_OFFICER' are one role — then keeps only what is
    offboardable. Everything else is reported rather than silently dropped, and
    rather than widened into: a typo must not become a deletion scope.
    """
    roles: list[str] = []
    rejected: list[dict] = []
    for value in values:
        for token in str(value).split(","):
            token = token.strip()
            if not token:
                continue
            role = canonical_role(token)
            if role in OFFBOARDABLE_ROLES:
                if role not in roles:
                    roles.append(role)
            elif role:
                rejected.append({
                    "value": token,
                    "resolved_role": role,
                    "reason": f"{role} is a real role but is not offboardable by this "
                              f"tool (offboardable: {', '.join(OFFBOARDABLE_ROLES)})",
                })
            else:
                rejected.append({
                    "value": token,
                    "resolved_role": "",
                    "reason": "not a role this tool recognises "
                              f"(expected one of: {', '.join(OFFBOARDABLE_ROLES)})",
                })
    return roles, rejected


# ─────────────────────────────────────────────────────────────────────────────
# The sheets: every identity the state vouches for
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class Roster:
    """Every phone number the state's sheets name, and which sheet named it.

    Sources are kept so the workbook can say WHY a user was spared — "the SDO
    mapping still lists them" is actionable, "some sheet did" is not.
    """
    phone_sources: dict[str, list[str]] = field(default_factory=dict)
    public_id_sources: dict[str, list[str]] = field(default_factory=dict)
    issues: list[dict] = field(default_factory=list)
    stats: list[dict] = field(default_factory=list)

    def add_phone(self, phone: str, source: str) -> None:
        sources = self.phone_sources.setdefault(phone, [])
        if source not in sources:
            sources.append(source)

    def add_public_id(self, public_id: str, source: str) -> None:
        sources = self.public_id_sources.setdefault(public_id.strip().lower(), [])
        if source not in sources:
            sources.append(source)

    @property
    def master_phones(self) -> set[str]:
        return {p for p, s in self.phone_sources.items() if MASTER_SOURCE in s}

    def hashes(self, pii: PiiCrypto) -> dict[str, str]:
        """HMAC -> phone. The encrypted column cannot be searched, so this is the
        only form in which a sheet and the database can be compared."""
        return {pii.hmac(phone): phone for phone in self.phone_sources}


def collect_master(rows: Iterable[Any], roster: Roster) -> None:
    """Every phone the user master names, whatever the row's role or condition.

    A row the ingestion would skip still names a person: a khalasi, a row whose
    name cell is blank, a role this platform never onboards. Presence in the
    master is presence, and the only rows that cannot vouch for anybody are the
    ones whose phone number is unusable.
    """
    total = usable = 0
    seen: set[str] = set()
    for row in rows:
        total += 1
        if row.phone:
            usable += 1
            seen.add(row.phone)
            roster.add_phone(row.phone, MASTER_SOURCE)
        else:
            # Never echo the number itself; the row number locates it.
            roster.issues.append({
                "source": MASTER_SOURCE,
                "row_no": row.row_no,
                "public_id": row.public_id,
                "issue": "phone is not a valid Indian mobile number — this row "
                         "cannot protect anybody from deletion",
            })
        if row.public_id:
            roster.add_public_id(row.public_id, MASTER_SOURCE)
    roster.stats.append({
        "source": MASTER_SOURCE, "rows": total,
        "rows_with_a_usable_phone": usable,
        "distinct_phones": len(seen),
        "phones_no_earlier_sheet_had": len(seen),
    })


def load_protect_csv(path: str, source: str, header_row: int, encoding: str,
                     roster: Roster) -> None:
    """Harvest identities from a mapping sheet into the roster.

    Nothing about the file's shape is assumed beyond the state's house layout (a
    title line, then headers): the phone columns are found by name, so the three
    mapping sheets' differing column names need no configuration here. A sheet
    with no phone column at all is refused rather than read as protecting
    nobody — that is indistinguishable from the file being wrong.
    """
    frame = pd.read_csv(
        path, header=header_row - 1, dtype=str, keep_default_na=False, encoding=encoding,
    )
    frame.columns = [
        str(c).strip().lower().replace(" ", "_").replace("-", "_") for c in frame.columns
    ]

    phone_columns = [c for c in frame.columns if PHONE_COLUMN_RE.search(c)]
    if not phone_columns:
        raise SystemExit(
            f"{path} has no phone column, so it cannot protect anybody. "
            f"Found: {', '.join(frame.columns)}"
        )
    id_columns = [c for c in frame.columns if c in PUBLIC_ID_COLUMNS]

    total = usable = 0
    seen: set[str] = set()
    before = len(roster.phone_sources)
    for offset, raw in enumerate(frame.to_dict("records")):
        row_no = header_row + 1 + offset
        values = [clean(raw.get(c)) for c in phone_columns]
        if not any(values) and not any(clean(raw.get(c)) for c in id_columns):
            continue
        total += 1
        for value in values:
            if not value:
                continue
            phone = normalise_phone(value)
            if phone:
                usable += 1
                seen.add(phone)
                roster.add_phone(phone, source)
            else:
                roster.issues.append({
                    "source": source,
                    "row_no": row_no,
                    "public_id": "; ".join(
                        clean(raw.get(c)) for c in id_columns if clean(raw.get(c))
                    ),
                    "issue": "phone is not a valid Indian mobile number — this row "
                             "cannot protect anybody from deletion",
                })
        for column in id_columns:
            public_id = clean(raw.get(column))
            if public_id:
                roster.add_public_id(public_id, source)

    roster.stats.append({
        "source": source, "rows": total,
        "rows_with_a_usable_phone": usable,
        "distinct_phones": len(seen),
        # Zero here is the healthy case: it means the master already names
        # everybody this mapping sheet does.
        "phones_no_earlier_sheet_had": len(roster.phone_sources) - before,
    })
    LOG.info("  %-34s %6d rows, %5d distinct phone(s), %4d not in an earlier sheet "
             "(column: %s)",
             source, total, len(seen), len(roster.phone_sources) - before,
             ", ".join(phone_columns))


def resolve_protect_csvs(explicit: Iterable[str], use_defaults: bool) -> list[tuple[str, str]]:
    """(path, source label) for every protection sheet this run will read.

    A default sheet that is not on disk aborts the run. Quietly dropping one
    would turn "in active service" into "not in any sheet" for everybody it
    names, which is the one failure mode this tool must not have.
    """
    resolved: list[tuple[str, str]] = []
    if use_defaults:
        parent = os.path.join(_BASE_DIR, os.pardir)
        for relative in DEFAULT_PROTECT_CSVS:
            path = os.path.normpath(os.path.join(parent, relative))
            if not os.path.isfile(path):
                raise SystemExit(
                    f"Default protection sheet {path} is missing. Users it names would "
                    f"read as absent from every sheet and be deleted. Restore it, pass "
                    f"--protect-csv with its location, or --no-default-protect-csvs to "
                    f"run without it deliberately."
                )
            resolved.append((path, os.path.splitext(os.path.basename(path))[0]))
    for path in explicit:
        if not os.path.isfile(path):
            raise SystemExit(f"--protect-csv {path} does not exist.")
        label = os.path.splitext(os.path.basename(path))[0]
        if any(label == existing for _, existing in resolved):
            continue
        resolved.append((path, label))
    return resolved


# ─────────────────────────────────────────────────────────────────────────────
# Tenant database access
# ─────────────────────────────────────────────────────────────────────────────

class OffboardDb(UserDb):
    """Reads the tenant's live users in the selected roles.

    Extends the ingestion's UserDb (same connection, schema validation and PII
    crypto) with the role-scoped scan this tool works from. state_user_id is
    read when the column exists (V36) and is used for reporting only.
    """

    def load_live_users_by_roles(self, roles: Iterable[str]) -> list[dict]:
        """Every live user the tenant currently holds in one of these roles.

        The role is the tenant's own (user_table.user_type), not a sheet's: this
        tool removes people from the role we have them in.
        """
        wanted = sorted({r.strip().upper() for r in roles if r and r.strip()})
        if not wanted:
            return []

        # With the column absent the placeholder keeps the row shape identical.
        state_user_id_expr = (
            "u.state_user_id" if self.with_state_user_id else "NULL::varchar"
        )
        users: list[dict] = []
        with self.conn.cursor(name="offboard_user_scan") as cur:
            cur.itersize = 5000
            cur.execute(f"""
                SELECT u.id, u.uuid, u.title, u.phone_number, u.phone_number_hash,
                       u.email, u.status, u.password, {state_user_id_expr}, ut.c_name
                FROM {self.schema}.user_table u
                JOIN common_schema.user_type_master_table ut ON ut.id = u.user_type
                WHERE u.deleted_at IS NULL AND upper(ut.c_name) = ANY(%s)
            """, (wanted,))
            for (uid, uuid, title_enc, phone_enc, phone_hash, email, status,
                 password, state_user_id, c_name) in cur:
                users.append({
                    "id": uid,
                    "uuid": uuid,
                    "name": self.pii.safe_decrypt(title_enc) or "",
                    "phone": self.pii.safe_decrypt(phone_enc) or "",
                    "phone_hash": (phone_hash or "").strip(),
                    "email": email,
                    "status": status,
                    # The value itself never leaves this dict — only the verdict.
                    "has_managed_password": has_managed_password(password),
                    "state_user_id": state_user_id,
                    "role": (c_name or "").strip().upper(),
                })
        return users

    def count_live_scheme_mappings(self, user_ids: Iterable[int]) -> dict[int, int]:
        """user id -> live user_scheme_mapping rows, for the report."""
        ids = sorted({int(i) for i in user_ids})
        if not ids:
            return {}
        counts: dict[int, int] = {}
        with self.conn.cursor() as cur:
            for start in range(0, len(ids), 5000):
                cur.execute(f"""
                    SELECT user_id, count(*)
                    FROM {self.schema}.user_scheme_mapping_table
                    WHERE deleted_at IS NULL AND user_id = ANY(%s)
                    GROUP BY user_id
                """, (ids[start:start + 5000],))
                counts.update({uid: total for uid, total in cur})
        return counts


def has_managed_password(password: Optional[str]) -> bool:
    """True when the row carries a real Keycloak-managed password.

    Mirrors UserTenantRepository.updateKeycloakUuidAndPasswordIfUnmanaged's
    predicate: NULL, empty and the two placeholders mean no Keycloak account was
    ever provisioned. Anything else is an AES-GCM managed secret, which is only
    ever written after a successful OTP login.
    """
    value = (password or "").strip()
    return bool(value) and value not in PLACEHOLDER_PASSWORDS


# ─────────────────────────────────────────────────────────────────────────────
# Classification
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class OffboardDecision:
    user: dict
    category: str
    reason: str = ""
    # Sheets that name this user's phone number, in the order they were read.
    matched_sources: list[str] = field(default_factory=list)
    # Sheets that name this user's state_user_id while naming a different phone.
    # Reported, never decisive — see the module docstring.
    public_id_sources: list[str] = field(default_factory=list)
    live_mappings: int = 0

    @property
    def will_delete(self) -> bool:
        """In the set this run acts on. The write itself depends on the mode."""
        return self.category == CAT_ABSENT


def classify_users(
    users: list[dict], roster: Roster, pii: PiiCrypto, actor_id: int,
) -> list[OffboardDecision]:
    """Decide each in-scope user's fate. Order of reasons is the order of proof.

    A user named by a sheet is spared whatever else is true of them, so that
    check comes first; the protections after it exist for users no sheet names,
    and each says something different about why deleting them would be wrong.
    """
    by_hash = roster.hashes(pii)
    decisions: list[OffboardDecision] = []

    for user in users:
        phone = by_hash.get(user["phone_hash"]) if user["phone_hash"] else None
        sources = roster.phone_sources.get(phone, []) if phone else []
        public_id = (user.get("state_user_id") or "").strip().lower()
        # Only interesting when the phone did NOT match: a sheet naming this
        # person's state id while naming a different number for them.
        id_sources = (
            roster.public_id_sources.get(public_id, []) if public_id and not sources else []
        )

        if sources:
            in_master = MASTER_SOURCE in sources
            decision = OffboardDecision(
                user,
                CAT_IN_MASTER if in_master else CAT_IN_PROTECT,
                reason=f"named by {', '.join(sources)}",
                matched_sources=list(sources),
            )
        elif user["id"] == actor_id:
            decision = OffboardDecision(
                user, CAT_ACTOR,
                reason="this run's --actor-id, recorded as deleted_by on everything "
                       "it writes",
            )
        elif not user["phone_hash"]:
            decision = OffboardDecision(
                user, CAT_NO_PHONE,
                reason="no phone_number_hash — no sheet can be checked against this "
                       "user, so none of them gets to delete them",
            )
        elif user["has_managed_password"]:
            decision = OffboardDecision(
                user, CAT_HAS_LOGIN,
                reason="holds a Keycloak-managed password: the account has been "
                       "logged into, and a sheet does not remove those",
                public_id_sources=list(id_sources),
            )
        else:
            decision = OffboardDecision(
                user, CAT_ABSENT,
                reason="no sheet names this phone number",
                public_id_sources=list(id_sources),
            )
        decisions.append(decision)

    return decisions


# ─────────────────────────────────────────────────────────────────────────────
# Tenant database writes
# ─────────────────────────────────────────────────────────────────────────────

class OffboardWriter(UserWriter):
    """Every write is parameterised; only the validated schema is interpolated.

    Inherits assert_actor_is_tenant_user from the ingestion's writer so
    created_by/updated_by/deleted_by mean the same thing in both tools.
    """

    def deactivate_users(self, user_ids: Iterable[int]) -> int:
        """--deactivate-only: status, and nothing else.

        Mirrors UserTenantRepository.deactivateStaffUser, down to skipping the
        rows already inactive, so the count is the number of people this run
        actually changed rather than the number it looked at. deleted_at is left
        alone on purpose: the row stays live and reactivatable, and its scheme
        mappings stay with it.
        """
        ids = sorted({int(i) for i in user_ids})
        if not ids:
            return 0
        deactivated = 0
        with self.conn.cursor() as cur:
            for start in range(0, len(ids), 5000):
                cur.execute(f"""
                    UPDATE {self.schema}.user_table
                    SET status = %s, updated_by = %s, updated_at = NOW()
                    WHERE id = ANY(%s) AND deleted_at IS NULL AND status != %s
                    RETURNING id
                """, (USER_STATUS_INACTIVE, self.actor_id, ids[start:start + 5000],
                      USER_STATUS_INACTIVE))
                deactivated += len(cur.fetchall())
        return deactivated

    def soft_delete_users(self, user_ids: Iterable[int]) -> int:
        """status = 0 AND deleted_at, the pair deactivateAdminUser writes.

        deleted_at alone would not do: the staff login path finds a user by
        phone hash without filtering it and gates on status, so a user carrying
        only deleted_at could still log in.
        """
        ids = sorted({int(i) for i in user_ids})
        if not ids:
            return 0
        deleted = 0
        with self.conn.cursor() as cur:
            for start in range(0, len(ids), 5000):
                cur.execute(f"""
                    UPDATE {self.schema}.user_table
                    SET status = %s, deleted_at = NOW(), deleted_by = %s,
                        updated_by = %s, updated_at = NOW()
                    WHERE id = ANY(%s) AND deleted_at IS NULL
                    RETURNING id
                """, (USER_STATUS_INACTIVE, self.actor_id, self.actor_id,
                      ids[start:start + 5000]))
                deleted += len(cur.fetchall())
        return deleted

    def soft_delete_scheme_mappings(self, user_ids: Iterable[int]) -> int:
        """Mirrors UserUploadRepository.markUserSchemeMappingsDeleted: the row
        stays, deleted_at/deleted_by record who dropped it and when."""
        ids = sorted({int(i) for i in user_ids})
        if not ids:
            return 0
        removed = 0
        with self.conn.cursor() as cur:
            for start in range(0, len(ids), 5000):
                cur.execute(f"""
                    UPDATE {self.schema}.user_scheme_mapping_table
                    SET deleted_at = NOW(), deleted_by = %s,
                        updated_by = %s, updated_at = NOW()
                    WHERE user_id = ANY(%s) AND deleted_at IS NULL
                    RETURNING id
                """, (self.actor_id, self.actor_id, ids[start:start + 5000]))
                removed += len(cur.fetchall())
        return removed


class OffboardAnalyticsWriter(AnalyticsWriter):
    """The warehouse half of a deletion.

    dim_user_table has no deleted flag — the user-service publishes a status
    change, not a tombstone — so status is the whole of what the warehouse can
    say about a removed user. The row itself stays: every fact table carries
    user ids that would otherwise stop resolving.
    """

    def deactivate_users(self, user_ids: Iterable[int]) -> int:
        ids = sorted({int(i) for i in user_ids})
        if not ids:
            return 0
        updated = 0
        with self.conn.cursor() as cur:
            for start in range(0, len(ids), 5000):
                cur.execute("""
                    UPDATE analytics_schema.dim_user_table
                    SET status = %s, updated_at = NOW()
                    WHERE tenant_id = %s AND user_id = ANY(%s)
                    RETURNING user_id
                """, (USER_STATUS_INACTIVE, self.tenant_id, ids[start:start + 5000]))
                updated += len(cur.fetchall())
        return updated

    def clear_user_scheme_mappings(self, user_ids: Iterable[int]) -> int:
        """replace_user_scheme_mappings with an empty post-state, which is what a
        deleted user's mappings become in the tenant."""
        ids = sorted({int(i) for i in user_ids})
        if not ids:
            return 0
        removed = 0
        with self.conn.cursor() as cur:
            for start in range(0, len(ids), 1000):
                cur.execute("""
                    DELETE FROM analytics_schema.dim_user_scheme_mapping_table
                    WHERE tenant_id = %s AND user_id = ANY(%s)
                    RETURNING id
                """, (self.tenant_id, ids[start:start + 1000]))
                removed += len(cur.fetchall())
        return removed


# ─────────────────────────────────────────────────────────────────────────────
# Plan assembly
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class OffboardPlan:
    decisions: list[OffboardDecision]
    roles: list[str]
    rejected_roles: list[dict]
    roster: Roster
    with_state_user_id: bool = False
    # True = --deactivate-only. The plan is the same either way; only the write
    # at the end of it differs.
    deactivate_only: bool = False

    def by_category(self) -> dict[str, list[OffboardDecision]]:
        grouped: dict[str, list[OffboardDecision]] = defaultdict(list)
        for decision in self.decisions:
            grouped[decision.category].append(decision)
        return grouped

    @property
    def deletions(self) -> list[OffboardDecision]:
        """The users this run acts on: soft-deleted, or deactivated under
        --deactivate-only."""
        return [d for d in self.decisions if d.will_delete]

    @property
    def verb(self) -> str:
        """What this run does to them, for anything a human reads."""
        return "deactivate" if self.deactivate_only else "soft delete"

    def action_for(self, category: str) -> str:
        if category == CAT_ABSENT and self.deactivate_only:
            return DEACTIVATE_ACTION
        return CATEGORY_ACTION[category]

    @property
    def public_id_watchlist(self) -> list[OffboardDecision]:
        """Deletions a sheet arguably still names, under a different number."""
        return [d for d in self.deletions if d.public_id_sources]


def build_plan(
    db: OffboardDb, roster: Roster, roles: list[str], rejected_roles: list[dict],
    actor_id: int, deactivate_only: bool = False,
) -> OffboardPlan:
    LOG.info("Loading live %s from %s …", "/".join(roles), db.schema)
    users = db.load_live_users_by_roles(roles)
    LOG.info("  %d live user(s) in scope", len(users))

    decisions = classify_users(users, roster, db.pii, actor_id)

    counts = db.count_live_scheme_mappings(d.user["id"] for d in decisions if d.will_delete)
    for decision in decisions:
        decision.live_mappings = counts.get(decision.user["id"], 0)

    plan = OffboardPlan(
        decisions=decisions,
        roles=roles,
        rejected_roles=rejected_roles,
        roster=roster,
        with_state_user_id=db.with_state_user_id,
        deactivate_only=deactivate_only,
    )
    if plan.public_id_watchlist:
        LOG.warning(
            "%d user(s) queued to %s carry a state_user_id that a sheet still names "
            "under a different phone number — see the workbook",
            len(plan.public_id_watchlist), plan.verb,
        )
    return plan


# ─────────────────────────────────────────────────────────────────────────────
# Analysis workbook
# ─────────────────────────────────────────────────────────────────────────────

def build_summary_frame(plan: OffboardPlan) -> pd.DataFrame:
    grouped = plan.by_category()
    records = [
        {
            "category": category,
            "what it means": CATEGORY_DESCRIPTION[category],
            "action": plan.action_for(category),
            "users": len(grouped.get(category, [])),
        }
        for category in CATEGORY_ORDER
    ]
    records.append({
        "category": "TOTAL (live users in the selected roles)",
        "what it means": "", "action": "", "users": len(plan.decisions),
    })
    records.append({
        "category": f"scheme mappings the {plan.verb}d users hold",
        "what it means": "left untouched — a reactivated officer keeps their schemes"
        if plan.deactivate_only
        else "live user_scheme_mapping rows soft-deleted alongside their owner",
        "action": "no write" if plan.deactivate_only else "soft delete",
        "users": sum(d.live_mappings for d in plan.deletions),
    })
    records.append({
        "category": f"{plan.verb}s a sheet names by state_user_id",
        "what it means": "a sheet carries this person's state id against a different "
                         "phone number — usually a changed number, not a departure"
        if plan.with_state_user_id
        else "not checked — this tenant has no state_user_id column (V36)",
        "action": f"reported only — still {plan.verb}d",
        "users": len(plan.public_id_watchlist),
    })
    return pd.DataFrame.from_records(records)


def build_role_frame(plan: OffboardPlan) -> pd.DataFrame:
    """Per role, so a role about to lose most of its people is impossible to miss."""
    grouped: dict[str, dict[str, int]] = {
        role: {category: 0 for category in CATEGORY_ORDER} for role in plan.roles
    }
    for decision in plan.decisions:
        bucket = grouped.setdefault(
            decision.user["role"], {category: 0 for category in CATEGORY_ORDER}
        )
        bucket[decision.category] += 1

    records = []
    for role, counts in grouped.items():
        live = sum(counts.values())
        affected = counts[CAT_ABSENT]
        records.append({
            "role": role,
            "live_users": live,
            # Named for what it is in either mode; the action column says which.
            "action": plan.verb,
            "affected": affected,
            "share_affected": f"{(affected / live * 100):.1f}%" if live else "n/a",
            **{f"kept_{c.lower()}": counts[c] for c in CATEGORY_ORDER if c != CAT_ABSENT},
        })
    return pd.DataFrame.from_records(records) if records else pd.DataFrame(
        columns=["role", "live_users", "action", "affected", "share_affected"]
    )


def _detail_record(decision: OffboardDecision, show, verb: str) -> dict:
    user = decision.user
    return {
        "user_id": user["id"],
        "category": decision.category,
        "action": verb if decision.will_delete else "keep",
        "reason": decision.reason,
        "role": user["role"],
        "name": user["name"],
        "phone": show(user["phone"]),
        "email": user["email"],
        "state_user_id": user.get("state_user_id") or "",
        "status": user["status"],
        "has_logged_in": user["has_managed_password"],
        "live_scheme_mappings": decision.live_mappings,
        "named_by": ", ".join(decision.matched_sources),
        "state_user_id_named_by": ", ".join(decision.public_id_sources),
    }


def build_detail_frame(plan: OffboardPlan, include_pii: bool) -> pd.DataFrame:
    show = (lambda p: p) if include_pii else safe_mask
    records = [_detail_record(d, show, plan.verb) for d in plan.decisions]
    return pd.DataFrame.from_records(records) if records else pd.DataFrame(
        columns=["user_id", "category", "action", "reason"]
    )


def build_deletion_frame(plan: OffboardPlan, include_pii: bool) -> pd.DataFrame:
    """Every user this run acts on, deleted or deactivated."""
    show = (lambda p: p) if include_pii else safe_mask
    records = [_detail_record(d, show, plan.verb) for d in plan.deletions]
    return pd.DataFrame.from_records(records) if records else pd.DataFrame(
        columns=["user_id", "category", "action", "reason"]
    )


def build_kept_frame(plan: OffboardPlan, include_pii: bool) -> pd.DataFrame:
    """The users spared for a reason other than being in the master sheet.

    Listing the master matches here would bury the handful of cases somebody
    actually has to look at under every officer who is simply still employed.
    """
    show = (lambda p: p) if include_pii else safe_mask
    records = [
        _detail_record(d, show, plan.verb)
        for d in plan.decisions
        if d.category not in (CAT_ABSENT, CAT_IN_MASTER)
    ]
    return pd.DataFrame.from_records(records) if records else pd.DataFrame(
        columns=["user_id", "category", "action", "reason"]
    )


def build_sources_frame(plan: OffboardPlan) -> pd.DataFrame:
    return pd.DataFrame.from_records(plan.roster.stats) if plan.roster.stats else (
        pd.DataFrame(columns=["source", "rows", "rows_with_a_usable_phone",
                              "distinct_phones", "phones_no_earlier_sheet_had"])
    )


def build_roles_frame(plan: OffboardPlan) -> pd.DataFrame:
    records = [
        {"value": role, "resolved_role": role, "in_scope": True, "reason": ""}
        for role in plan.roles
    ]
    records += [
        {"value": r["value"], "resolved_role": r["resolved_role"],
         "in_scope": False, "reason": r["reason"]}
        for r in plan.rejected_roles
    ]
    return pd.DataFrame.from_records(records)


def write_analysis_workbook(plan: OffboardPlan, path: str, include_pii: bool,
                            context: dict) -> None:
    run_info = pd.DataFrame.from_records(
        [{"setting": k, "value": str(v)} for k, v in context.items()]
    )
    issues = pd.DataFrame.from_records(plan.roster.issues) if plan.roster.issues else (
        pd.DataFrame(columns=["source", "row_no", "public_id", "issue"])
    )

    with pd.ExcelWriter(path, engine="openpyxl") as writer:
        run_info.to_excel(writer, sheet_name="run_info", index=False)
        build_summary_frame(plan).to_excel(writer, sheet_name="summary", index=False)
        build_role_frame(plan).to_excel(writer, sheet_name="role_summary", index=False)
        build_roles_frame(plan).to_excel(writer, sheet_name="roles_requested", index=False)
        build_sources_frame(plan).to_excel(writer, sheet_name="sheets_read", index=False)
        build_deletion_frame(plan, include_pii).to_excel(
            writer,
            sheet_name="deactivations" if plan.deactivate_only else "deletions",
            index=False)
        build_kept_frame(plan, include_pii).to_excel(
            writer, sheet_name="kept_for_review", index=False)
        issues.to_excel(writer, sheet_name="sheet_issues", index=False)
        build_detail_frame(plan, include_pii).to_excel(
            writer, sheet_name="user_detail", index=False)

    LOG.info("Analysis workbook written to %s", path)


# ─────────────────────────────────────────────────────────────────────────────
# Execute
# ─────────────────────────────────────────────────────────────────────────────

def ceiling_breach(plan: OffboardPlan, max_deletions: int) -> Optional[str]:
    """Why this plan may not execute, or None when it may.

    The ceiling is a ceiling: deleting exactly the number the operator authorised
    is what they authorised. Only more than that is refused.
    """
    if len(plan.deletions) <= max_deletions:
        return None
    return (
        f"The plan would {plan.verb} {len(plan.deletions)} user(s), over the "
        f"--max-deletions ceiling of {max_deletions}. Nothing was written. Read the "
        f"workbook, then raise the ceiling deliberately if the number is right."
    )


def execute_tenant(plan: OffboardPlan, writer: OffboardWriter) -> dict[str, int]:
    """Apply the whole tenant-side plan in one transaction."""
    user_ids = [d.user["id"] for d in plan.deletions]
    if plan.deactivate_only:
        # No deletion, and the mappings stay with their owner.
        return {"users_deactivated": writer.deactivate_users(user_ids)}
    return {
        "users_soft_deleted": writer.soft_delete_users(user_ids),
        "scheme_mappings_soft_deleted": writer.soft_delete_scheme_mappings(user_ids),
    }


def execute_analytics(plan: OffboardPlan, analytics: OffboardAnalyticsWriter) -> dict[str, int]:
    """Project the run into the warehouse: status off, and — unless this was a
    deactivation — the mappings gone with it."""
    user_ids = [d.user["id"] for d in plan.deletions]
    stats = {"dim_user_rows_deactivated": analytics.deactivate_users(user_ids)}
    if not plan.deactivate_only:
        stats["dim_user_scheme_mapping_rows_removed"] = (
            analytics.clear_user_scheme_mappings(user_ids)
        )
    return stats


# ─────────────────────────────────────────────────────────────────────────────
# CLI
# ─────────────────────────────────────────────────────────────────────────────

def parse_args(argv: Optional[list[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Soft-delete tenant users in the given officer roles that the "
                    "state's sheets no longer name.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__.split("Usage")[-1],
    )
    parser.add_argument("--csv", required=True, help="path to the user master CSV")
    parser.add_argument("--roles", required=True, action="append",
                        help="roles to offboard, comma-separated or repeated. Only "
                             f"{', '.join(OFFBOARDABLE_ROLES)} (so / sdo / ee) are "
                             "accepted; anything else is skipped with a warning")
    parser.add_argument("--protect-csv", action="append", default=[],
                        help="additional sheet whose phone columns protect a user "
                             "from deletion; repeatable")
    parser.add_argument("--no-default-protect-csvs", action="store_true",
                        help="do not read the three mapping sheets "
                             "(subdivision-sdo, section-officer-scheme, "
                             "division-executive-engineer) as protection sources")
    parser.add_argument("--header-row", type=int, default=2,
                        help="1-based row holding the column headers, in the master "
                             "and every protection sheet alike — the state's exports "
                             "all put a title line above them (default: 2)")
    parser.add_argument("--encoding", default="utf-8-sig",
                        help="CSV encoding (default: utf-8-sig)")
    parser.add_argument("--schema", default="tenant_as", help="tenant schema (default: tenant_as)")
    parser.add_argument("--tenant-id", type=int, default=None,
                        help="tenant id; resolved from the schema's state code when omitted")
    parser.add_argument("--actor-id", type=int, required=True,
                        help="user_table.id recorded as deleted_by/updated_by")
    parser.add_argument("--out", default="jjm_user_offboard_analysis.xlsx",
                        help="analysis workbook path (default: jjm_user_offboard_analysis.xlsx)")
    parser.add_argument("--tenant-dsn", default=os.environ.get("TENANT_DSN"),
                        help="tenant DB DSN (default: $TENANT_DSN)")
    parser.add_argument("--analytics-dsn", default=os.environ.get("ANALYTICS_DSN"),
                        help="analytics DB DSN (default: $ANALYTICS_DSN)")
    parser.add_argument("--execute", action="store_true",
                        help="apply the plan; without this the run is read-only")
    parser.add_argument("--deactivate-only", action="store_true",
                        help="write status = 0 and no deletion at all: the users stay "
                             "live rows, keep their scheme mappings, and can be put "
                             "back through the existing activate endpoint. Same scope, "
                             "same protections, same ceiling — only the write differs")
    parser.add_argument("--max-deletions", type=int, default=None,
                        help="refuse to execute if the plan would act on more than "
                             "this many users — deleted, or deactivated under "
                             "--deactivate-only. Required with --execute")
    parser.add_argument("--skip-analytics", action="store_true",
                        help="do not touch the analytics warehouse")
    parser.add_argument("--include-pii", action="store_true",
                        help="write full phone numbers into the analysis workbook "
                             "(masked by default)")
    parser.add_argument("-v", "--verbose", action="store_true")
    return parser.parse_args(argv)


def main(argv: Optional[list[str]] = None) -> int:
    args = parse_args(argv)
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)-7s %(message)s",
        datefmt="%H:%M:%S",
    )

    roles, rejected_roles = parse_roles(args.roles)
    for rejected in rejected_roles:
        LOG.warning("--roles %s skipped: %s", rejected["value"], rejected["reason"])
    if not roles:
        return _fail(
            "No offboardable role was given. --roles accepts "
            f"{', '.join(OFFBOARDABLE_ROLES)} (so / sdo / ee) and nothing else."
        )
    LOG.info("Offboarding scope: %s", ", ".join(roles))

    if not args.tenant_dsn:
        return _fail("--tenant-dsn (or $TENANT_DSN) is required")
    if args.execute and args.max_deletions is None:
        return _fail(
            "--max-deletions is required with --execute. Run the analysis first and "
            "take the number off its summary sheet."
        )
    if args.max_deletions is not None and args.max_deletions < 0:
        return _fail("--max-deletions cannot be negative")
    if args.execute and not args.skip_analytics and not args.analytics_dsn:
        return _fail("--analytics-dsn (or $ANALYTICS_DSN) is required unless --skip-analytics")

    try:
        pii = PiiCrypto(os.environ.get("PII_ENCRYPTION_KEY", ""), os.environ.get("PII_HMAC_KEY", ""))
    except ValueError as exc:
        return _fail(str(exc))

    roster = Roster()
    LOG.info("Reading %s …", args.csv)
    rows, _ = load_csv(args.csv, args.header_row, args.encoding)
    collect_master(rows, roster)
    LOG.info("  %d row(s), %d distinct usable phone number(s)",
             len(rows), len(roster.master_phones))
    if not roster.master_phones:
        return _fail(
            f"{args.csv} yielded no usable phone number, so every user in scope would "
            f"read as absent from it. Check --header-row and the file itself."
        )

    protect_csvs = resolve_protect_csvs(args.protect_csv, not args.no_default_protect_csvs)
    if protect_csvs:
        LOG.info("Reading %d protection sheet(s) …", len(protect_csvs))
        for path, label in protect_csvs:
            load_protect_csv(path, label, args.header_row, args.encoding, roster)
    else:
        LOG.warning(
            "No protection sheet is being read — only the user master can spare a user."
        )
    LOG.info("%d distinct phone number(s) across every sheet", len(roster.phone_sources))

    tenant_conn = psycopg2.connect(args.tenant_dsn)
    tenant_conn.autocommit = False
    analytics_conn = None
    exit_code = 0

    try:
        db = OffboardDb(tenant_conn, args.schema, pii)
        db.with_state_user_id = db.state_user_id_column_exists()
        if not db.with_state_user_id:
            LOG.info(
                "%s.user_table has no state_user_id column (V36) — the state-id "
                "cross-check is skipped; matching is unaffected.", db.schema,
            )
        tenant_id = args.tenant_id or db.resolve_tenant_id()
        LOG.info("Tenant id %d, schema %s", tenant_id, db.schema)

        plan = build_plan(db, roster, roles, rejected_roles, args.actor_id,
                          deactivate_only=args.deactivate_only)

        context = {
            "generated_at": datetime.now().isoformat(timespec="seconds"),
            "master_csv": args.csv,
            "protection_sheets": ", ".join(label for _, label in protect_csvs) or "none",
            "roles_in_scope": ", ".join(roles),
            "roles_skipped": ", ".join(r["value"] for r in rejected_roles) or "none",
            "tenant_schema": args.schema,
            "tenant_id": tenant_id,
            "actor_id": args.actor_id,
            "mode": "EXECUTE" if args.execute else "ANALYZE (read-only)",
            "write": "DEACTIVATE — status = 0 only, no deletion, mappings kept"
            if args.deactivate_only
            else "SOFT DELETE — status = 0 + deleted_at, mappings dropped",
            "max_users_affected": args.max_deletions if args.max_deletions is not None
            else "n/a (analysis)",
            "analytics": "skipped" if args.skip_analytics else "included",
            "phones_in_report": "full" if args.include_pii else "masked",
            "state_user_id": "read (reporting only)" if plan.with_state_user_id
            else "absent — V36 not applied to this tenant",
        }
        write_analysis_workbook(plan, args.out, args.include_pii, context)
        _print_summary(plan)

        if not args.execute:
            LOG.info("Read-only run — nothing was written. Re-run with --execute "
                     "--max-deletions N to apply.")
            tenant_conn.rollback()
            return 0

        breach = ceiling_breach(plan, args.max_deletions)
        if breach:
            return _fail(breach)
        if not plan.deletions:
            LOG.info("Nothing to %s — every user in scope is named by a sheet or "
                     "protected.", plan.verb)
            tenant_conn.rollback()
            return 0

        writer = OffboardWriter(db, tenant_id, args.actor_id)
        writer.assert_actor_is_tenant_user()

        if not args.skip_analytics:
            analytics_conn = psycopg2.connect(args.analytics_dsn)
            analytics_conn.autocommit = False
            analytics = OffboardAnalyticsWriter(analytics_conn, tenant_id)
            analytics.assert_tenant_exists()

        LOG.info("Applying tenant changes …")
        for key, value in execute_tenant(plan, writer).items():
            LOG.info("  %-38s %d", key, value)

        if analytics_conn is not None:
            LOG.info("Applying analytics changes …")
            for key, value in execute_analytics(plan, analytics).items():
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


def _print_summary(plan: OffboardPlan) -> None:
    grouped = plan.by_category()
    LOG.info("─" * 78)
    LOG.info("%-46s %8s  %s", "CATEGORY", "USERS", "ACTION")
    for category in CATEGORY_ORDER:
        LOG.info("%-46s %8d  %s", category, len(grouped.get(category, [])),
                 plan.action_for(category))
    LOG.info("%-46s %8d", "TOTAL in scope", len(plan.decisions))
    LOG.info("─" * 78)
    for record in build_role_frame(plan).to_dict("records"):
        LOG.info("%-46s %8d  of %d live (%s)",
                 f"role / {record['role']} to {plan.verb}", record["affected"],
                 record["live_users"], record["share_affected"])
    LOG.info("%-46s %8d",
             "scheme mappings kept with them" if plan.deactivate_only
             else "scheme mappings going with them",
             sum(d.live_mappings for d in plan.deletions))
    if plan.public_id_watchlist:
        LOG.warning("%-46s %8d  see the workbook",
                    f"{plan.verb}s a sheet still names by state id",
                    len(plan.public_id_watchlist))
    LOG.info("─" * 78)


if __name__ == "__main__":
    sys.exit(main())
