#!/usr/bin/env python3
"""
JJM master-data ingestion for a Jal Soochak tenant (default: Assam / tenant_as).

Reads a JJM master snapshot — the full workbook (all_ascheme_exist.xlsx) or the
narrower CSV export (schemes-master-data.csv) — and reconciles it against the
tenant database and the analytics warehouse.

Two modes:

  analyze  (default)  read-only. Writes an Excel analysis workbook describing
                      exactly what an execute run would do, and why.
  execute  (--execute) applies the inserts/updates inside transactions.

What it touches
---------------
tenant DB (shared_db), schema tenant_<code>:
  scheme_master_table              insert / update / revive / retire
  user_table                       insert / update / revive (PUMP_OPERATOR, SECTION_OFFICER)
  user_scheme_mapping_table        insert / revive / retire
  scheme_lgd_mapping_table         insert / revive / retire (village)
  scheme_department_mapping_table  insert / revive / retire (sub-division)

analytics DB, schema analytics_schema:
  dim_scheme_table                 upsert (one row per scheme x village x sub-division)
                                   + attribute sync across *every* row of a scheme
  dim_user_table                   upsert
  dim_user_scheme_mapping_table    replace-per-user from the tenant DB's post-state

Which of those the run touches depends on what the source file actually carries
(see "Source shapes" below) — a file with no village column never prunes a
village mapping, because it has said nothing about villages.

Scheme matching contract (evaluated in this order)
--------------------------------------------------
1. Exactly one existing scheme matches BOTH imis_id and smt_id
        -> update it. This wins even when either id on its own also matches
           other schemes, because the *pair* is unique.
2. Otherwise fall back to single-id matching, where any multiplicity is
   unresolvable:
        centre matches exactly 1 (X), state id found nowhere   -> update X, adopt smt_id
        centre id found nowhere, state matches exactly 1 (Y)   -> update Y, adopt imis_id
        centre matches 1 (X), state matches a different scheme -> skip (conflict)
        centre or state matches >1 scheme                      -> skip (ambiguous)
3. No live scheme carries either id, but a SOFT-DELETED one does
        -> revive that row rather than inserting a second copy of the scheme.
           Without this a re-run after a retirement (or after any other soft
           delete) would silently duplicate every retired scheme.
4. Neither id found anywhere, live or retired                  -> insert

Idempotence
-----------
Every write path is keyed on something the previous run also saw, and every
lookup that decides "does this already exist?" reads soft-deleted rows too:

  schemes           matched on the id pair across live *and* retired rows
  users             matched on phone_number_hash across live *and* retired rows
  all three mapping tables  a retired row for the pair is revived, never re-inserted;
                    a pair that somehow holds several live rows is collapsed to one

So a second identical run writes nothing, and a run following a retirement
restores rather than duplicates.

Legacy data (--replace)
-----------------------
The snapshot is treated as the complete current truth for everything it speaks
for. With --replace:

  * a live scheme whose ids appear nowhere in the snapshot is retired
    (is_active = FALSE + deleted_at), together with its village, sub-division
    and user mappings, and its dim_scheme rows drop to operating_status = 0;
  * a scheme IS SPARED, loudly and in its own report sheet, when it still has a
    flow reading inside the last --reading-window-days (default 90). Data is
    arriving for it, so the snapshot is out of date, not the scheme;
  * mappings the snapshot contradicts are retired, scoped to the schemes the
    snapshot names and to the roles/columns the file actually carries.

Retirement is opt-in, but the analysis workbook reports the whole legacy
distribution on every run, --replace or not, so nothing goes unnoticed.

Note on is_active: scheme-service's SchemeActivitySyncScheduler recomputes that
column from recent flow readings, but only `WHERE deleted_at IS NULL`. Setting
deleted_at in the same statement is what makes the retirement stick.

Location mapping contract
-------------------------
A village / sub-division mapping is written ONLY when the name resolves to
exactly one location. When several locations share the name, the sheet's
hierarchy columns are used to disambiguate (village: panchayat > block >
district; sub-division: division > circle > zone). If that still leaves more
than one candidate, the mapping is left unwritten and reported. Nothing is
guessed.

Source shapes
-------------
Only scheme_name, imis_id, smt_id, work_status, operating_status,
planned_fhtc_imis, provided_fhtc_imis, latitude and longitude are required —
both known sources carry them. The rest are optional and their absence narrows
what the run claims authority over:

  all_ascheme_exist.xlsx    locations + users  -> full reconciliation
  schemes-master-data.csv   scheme columns only -> scheme attributes and legacy
                            schemes only; village, sub-division and user
                            mappings are neither written nor pruned

--skip-users forces the user half off even for a file that carries it.

PII
---
user_table.title and .phone_number are AES-256-GCM encrypted and looked up via
HMAC-SHA256 hashes, mirroring the services' PiiEncryptionService. This script
therefore needs the target environment's PII_ENCRYPTION_KEY and PII_HMAC_KEY.
Phone numbers are masked in the analysis workbook unless --include-pii is given.

Usage
-----
  export PII_ENCRYPTION_KEY=...  PII_HMAC_KEY=...
  export TENANT_DSN='postgresql://postgres:pw@localhost:5432/shared_db'
  export ANALYTICS_DSN='postgresql://postgres:pw@localhost:5432/analytics'

  export TENANT_DSN='host=localhost port=5432 dbname=shared_db user=postgres password=password@1123'
  export ANALYTICS_DSN='host=localhost port=5432 dbname=analytics user=postgres password=password@1123'


  # dry run -> analysis workbook only
  python3 jjm_scheme_master_ingest.py \
      --excel all_ascheme_exist.xlsx --actor-id 21357 --out jjm_scheme_analysis.xlsx

  # the CSV snapshot: schemes only, no users, no location mappings
  python3 jjm_scheme_master_ingest.py \
      --csv schemes-master-data.csv --actor-id 21357 --out jjm_scheme_analysis.xlsx

  # apply, retiring everything the snapshot has dropped
  python3 jjm_scheme_master_ingest.py \
      --excel all_ascheme_exist.xlsx --actor-id 21357 \
      --out jjm_scheme_analysis.xlsx --replace --execute
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import logging
import os
import re
import sys
import unicodedata
from collections import Counter, defaultdict
from dataclasses import dataclass, field, fields
from dataclasses import replace as dataclass_replace
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

try:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
except ImportError:  # pragma: no cover
    sys.exit("cryptography is required:  pip install cryptography")


LOG = logging.getLogger("jjm-ingest")

# ─────────────────────────────────────────────────────────────────────────────
# Domain constants — mirrored from the Java services. Keep in sync.
# ─────────────────────────────────────────────────────────────────────────────

# SchemeServiceImpl.WORK_STATUS_MAP / OPERATING_STATUS_MAP. The sheet hyphenates
# ("handed-over"), the app spaces ("handed over"); normalise_status folds both.
WORK_STATUS_MAP = {
    "1": 1, "ongoing": 1,
    "2": 2, "completed": 2,
    "3": 3, "not started": 3,
    "4": 4, "handed over": 4,
}
OPERATING_STATUS_MAP = {
    "0": 0, "non operative": 0,
    "1": 1, "operative": 1,
    "2": 2, "partially operative": 2,
}
# SchemeServiceImpl: a blank operating_status defaults to 1 (operative).
DEFAULT_OPERATING_STATUS = 1

# TenantUserStatus.ACTIVE
USER_STATUS_ACTIVE = 1
# user_scheme_mapping_table.status. Every service read path demands
# `deleted_at IS NULL AND status = 1` together (PersonSchemeRepository and
# friends), so a retired row drops both — leaving status at 1 would fail only
# one of the two guards it should.
MAPPING_STATUS_ACTIVE = 1
MAPPING_STATUS_INACTIVE = 0
# PumpOperatorUploadChunkProcessor writes this literal into user_table.password.
ONBOARD_PASSWORD = "CSV_ONBOARDED"
EMAIL_DOMAIN = "@pump-operator.local"
ROLE_PUMP_OPERATOR = "PUMP_OPERATOR"
ROLE_SECTION_OFFICER = "SECTION_OFFICER"
EMAIL_PREFIX = {ROLE_PUMP_OPERATOR: "po_", ROLE_SECTION_OFFICER: "so_"}

# scheme_master_table column types, for the bulk UPDATE ... FROM (VALUES ...).
# A VALUES list has no types of its own — Postgres infers each column from the
# literals in it, which is fine while every value is a non-NULL Python int/float/
# str but breaks as soon as it is not: a page whose values are all NULL types the
# column as text, and "column is of type integer but expression is of type text"
# aborts the whole transaction. Casting every column explicitly keeps that
# inference out of the picture. Doubles as the allow-list of columns
# update_schemes may interpolate into SQL.
SCHEME_UPDATE_COLUMN_TYPES = {
    "scheme_name": "varchar",
    "state_scheme_id": "varchar",
    "centre_scheme_id": "varchar",
    "state_scheme_code": "varchar",
    "fhtc_count": "integer",
    "planned_fhtc": "integer",
    "work_status": "integer",
    "operating_status": "integer",
    "latitude": "double precision",
    "longitude": "double precision",
}

# scheme_master_table.state_scheme_code — the state system's public scheme code
# ("SCH-034035"), added by V38. Distinct from state_scheme_id, which holds the
# numeric SMT id. Only the CSV export carries it (as `public_id`), and only
# databases past V38 have the column, so both the read and the write are
# conditional; see TenantDb.state_scheme_code_column_exists.
STATE_SCHEME_CODE_COLUMN = "state_scheme_code"

# location_config_master_table: region_type 1 = LGD, 2 = department.
LGD_LEVELS = {"state": 1, "district": 2, "block": 3, "panchayat": 4, "village": 5}
DEPT_LEVELS = {"state": 1, "zone": 2, "circle": 3, "division": 4, "sub_division": 5}
# scheme_lgd_mapping_table.parent_lgd_level / scheme_department_mapping_table.parent_department_level
LGD_MAPPING_LEVEL = "VILLAGE"
DEPT_MAPPING_LEVEL = "Sub-division"

INDIAN_MOBILE_RE = re.compile(r"^[6-9]\d{9}$")
SAFE_SCHEMA_RE = re.compile(r"^[a-z_][a-z0-9_]*$")

# A live scheme absent from the snapshot is only retired when nothing has been
# reported for it in this many days. Deliberately longer than scheme-service's
# activity window (SCHEME_INACTIVITY_DAYS, default 30): that column decides how
# a scheme is displayed, this decides whether it survives.
DEFAULT_READING_WINDOW_DAYS = 360

# Columns every known source carries; their absence is a fatal misread.
REQUIRED_COLUMNS = [
    "scheme_name", "imis_id", "smt_id", "work_status", "operating_status",
    "planned_fhtc_imis", "provided_fhtc_imis", "latitude", "longitude",
]
# Optional columns, grouped by the authority their presence confers. A file
# that omits a group says nothing about it, so that group is neither written
# nor pruned — see SourceShape.
VILLAGE_COLUMNS = ["village_name", "panchayat_name", "blocks", "district"]
SUB_DIVISION_COLUMNS = ["sub_divisions", "division", "circle", "zone"]
USER_COLUMNS = ["jalmitras", "jalmitra_phone", "so_name", "so_phone"]
# The CSV export's public scheme code, stored as scheme_master_table
# .state_scheme_code. The workbook does not carry it, which is exactly why it
# is optional rather than required.
PUBLIC_ID_COLUMN = "public_id"
OPTIONAL_COLUMNS = (
    [PUBLIC_ID_COLUMN] + VILLAGE_COLUMNS + SUB_DIVISION_COLUMNS + USER_COLUMNS
)
ALL_COLUMNS = REQUIRED_COLUMNS + OPTIONAL_COLUMNS

# Scheme classification outcomes.
CAT_BOTH_MATCH = "BOTH_IDS_MATCH_SAME_SCHEME"
CAT_CENTRE_ONLY_STATE_NEW = "CENTRE_MATCH_STATE_ID_UNKNOWN"
CAT_STATE_ONLY_CENTRE_NEW = "STATE_MATCH_CENTRE_ID_UNKNOWN"
CAT_REVIVED = "REVIVED_SOFT_DELETED_SCHEME"
CAT_CONFLICT = "CONFLICT_IDS_POINT_TO_DIFFERENT_SCHEMES"
CAT_NEW = "NEW_SCHEME"
CAT_AMBIGUOUS = "AMBIGUOUS_ID_MATCHES_MULTIPLE_SCHEMES"
CAT_INVALID = "INVALID_SHEET_ROW"

CATEGORY_ORDER = [
    CAT_BOTH_MATCH, CAT_CENTRE_ONLY_STATE_NEW, CAT_STATE_ONLY_CENTRE_NEW,
    CAT_REVIVED, CAT_NEW, CAT_CONFLICT, CAT_AMBIGUOUS, CAT_INVALID,
]
CATEGORY_ACTION = {
    CAT_BOTH_MATCH: "update",
    CAT_CENTRE_ONLY_STATE_NEW: "update (adopt smt_id)",
    CAT_STATE_ONLY_CENTRE_NEW: "update (adopt imis_id)",
    CAT_REVIVED: "revive + update",
    CAT_NEW: "insert",
    CAT_CONFLICT: "skip",
    CAT_AMBIGUOUS: "skip",
    CAT_INVALID: "skip",
}
CATEGORY_DESCRIPTION = {
    CAT_BOTH_MATCH: "Both imis_id and smt_id resolve to the same existing scheme",
    CAT_CENTRE_ONLY_STATE_NEW: "imis_id matches one scheme; smt_id is not in our system yet",
    CAT_STATE_ONLY_CENTRE_NEW: "smt_id matches one scheme; imis_id is not in our system yet",
    CAT_REVIVED: "Only a soft-deleted scheme carries these ids — brought back, not duplicated",
    CAT_NEW: "Neither id exists in our system, live or soft-deleted",
    CAT_CONFLICT: "imis_id and smt_id point at two different existing schemes",
    CAT_AMBIGUOUS: "An id matches several schemes and the pair does not resolve it",
    CAT_INVALID: "Sheet row cannot be used (blank name/ids or unusable work_status)",
}

# Why a mapping row is being retired. Kept apart from each other so the report
# can never blur "coverage genuinely went away" into "we tidied a duplicate".
REMOVAL_DUPLICATE = "duplicate_row_collapsed"
REMOVAL_NOT_IN_SNAPSHOT = "not_in_latest_snapshot"
REMOVAL_SCHEME_RETIRED = "scheme_retired"

# Legacy-scheme outcomes.
LEGACY_RETIRE = "retire"
LEGACY_KEEP_RECENT_READINGS = "keep_recent_readings"


# ─────────────────────────────────────────────────────────────────────────────
# PII crypto — mirrors org.arghyam.jalsoochak.*.PiiEncryptionService
# ─────────────────────────────────────────────────────────────────────────────

class PiiCrypto:
    """AES-256-GCM (12-byte IV prefixed, base64) + HMAC-SHA256 hex.

    Byte-for-byte compatible with the Java implementation: encrypt() trims the
    plaintext, hmac() trims before hashing, and the IV is prepended to the
    ciphertext+tag before base64 encoding.
    """

    IV_LEN = 12

    def __init__(self, aes_key_b64: str, hmac_key_b64: str) -> None:
        if not aes_key_b64 or not hmac_key_b64:
            raise ValueError(
                "PII_ENCRYPTION_KEY and PII_HMAC_KEY must both be set — the tenant "
                "user_table stores encrypted PII and is looked up by HMAC."
            )
        aes_key = base64.b64decode(aes_key_b64)
        hmac_key = base64.b64decode(hmac_key_b64)
        if len(aes_key) != 32:
            raise ValueError("PII_ENCRYPTION_KEY must decode to exactly 32 bytes")
        if len(hmac_key) != 32:
            raise ValueError("PII_HMAC_KEY must decode to exactly 32 bytes")
        self._aes = AESGCM(aes_key)
        self._hmac_key = hmac_key

    def encrypt(self, plaintext: Optional[str]) -> Optional[str]:
        if plaintext is None:
            return None
        data = plaintext.strip().encode("utf-8")
        iv = os.urandom(self.IV_LEN)
        return base64.b64encode(iv + self._aes.encrypt(iv, data, None)).decode("ascii")

    def decrypt(self, encoded: Optional[str]) -> Optional[str]:
        if encoded is None:
            return None
        raw = base64.b64decode(encoded)
        if len(raw) <= self.IV_LEN:
            raise ValueError("ciphertext too short")
        return self._aes.decrypt(raw[: self.IV_LEN], raw[self.IV_LEN:], None).decode("utf-8")

    def safe_decrypt(self, encoded: Optional[str]) -> Optional[str]:
        """Decrypt, tolerating legacy plaintext rows (mirrors safeDecrypt)."""
        if encoded is None:
            return None
        try:
            raw = base64.b64decode(encoded, validate=True)
        except Exception:
            return encoded
        if len(raw) < self.IV_LEN + 16:
            return encoded
        try:
            return self.decrypt(encoded)
        except Exception:
            return encoded

    def hmac(self, plaintext: Optional[str]) -> Optional[str]:
        if plaintext is None:
            return None
        return hmac.new(
            self._hmac_key, plaintext.strip().encode("utf-8"), hashlib.sha256
        ).hexdigest()

    def title_hash(self, title: Optional[str]) -> Optional[str]:
        """UserTenantRepository hashes the lower-cased, trimmed title."""
        return self.hmac((title or "").strip().lower())


# ─────────────────────────────────────────────────────────────────────────────
# Normalisation helpers
# ─────────────────────────────────────────────────────────────────────────────

def _is_blank(value: Any) -> bool:
    if value is None:
        return True
    try:
        if pd.isna(value):
            return True
    except (TypeError, ValueError):
        pass
    return str(value).strip() == "" or str(value).strip().lower() in {"nan", "none", "null"}


def cell_str(value: Any) -> str:
    """Excel cell -> clean string. Numeric ids arrive as floats (30210543.0)."""
    if _is_blank(value):
        return ""
    if isinstance(value, float) and value.is_integer():
        return str(int(value))
    if isinstance(value, int):
        return str(value)
    return str(value).strip()


def scheme_id_key(value: Any) -> str:
    """Canonical form for centre/state scheme ids used on both sides of the join.

    Ids live in VARCHAR columns but are numeric in practice; stripping a numeric
    value's leading zeros makes '0123' and '123' the same scheme, which is what
    the source systems mean. Non-numeric ids are compared case-insensitively.
    """
    text = cell_str(value)
    if not text:
        return ""
    text = text.strip()
    if re.fullmatch(r"\d+", text):
        return text.lstrip("0") or "0"
    if re.fullmatch(r"\d+\.0+", text):
        whole = text.split(".")[0]
        return whole.lstrip("0") or "0"
    return text.lower()


def normalise_status(value: Any) -> str:
    """'handed-over' / 'Handed Over' / 'partially_operative' -> 'handed over'."""
    text = cell_str(value).lower()
    text = re.sub(r"[-_/]+", " ", text)
    return re.sub(r"\s+", " ", text).strip()


def split_multi(value: Any) -> list[str]:
    """Split a comma-separated cell, dropping blanks and trimming each item."""
    text = cell_str(value)
    if not text:
        return []
    return [part.strip() for part in text.split(",") if part.strip()]


def normalise_phone(raw: str) -> Optional[str]:
    """Sheet phone -> the 91XXXXXXXXXX form the DB stores.

    Mirrors PhoneNumberUtil.normalizeIndianMobileForDb but additionally rejects
    anything that is not a valid Indian mobile, so junk never creates a user.
    """
    digits = re.sub(r"\D", "", raw or "")
    if len(digits) == 12 and digits.startswith("91"):
        digits = digits[2:]
    elif len(digits) == 13 and digits.startswith("091"):
        digits = digits[3:]
    elif len(digits) == 11 and digits.startswith("0"):
        digits = digits[1:]
    if not INDIAN_MOBILE_RE.match(digits):
        return None
    return "91" + digits


def mask_phone(phone: Optional[str]) -> str:
    """PII rule: never write full numbers to the analysis workbook by default."""
    if not phone:
        return ""
    return phone[:2] + "X" * (len(phone) - 4) + phone[-2:]


def norm_name(value: Any) -> str:
    """Location/person name key: NFKD-folded, punctuation-collapsed, lowercase."""
    text = cell_str(value)
    if not text:
        return ""
    text = unicodedata.normalize("NFKD", text)
    text = "".join(ch for ch in text if not unicodedata.combining(ch))
    text = text.lower()
    text = re.sub(r"[^a-z0-9]+", " ", text)
    return re.sub(r"\s+", " ", text).strip()


def parse_int(value: Any) -> Optional[int]:
    text = cell_str(value)
    if not text:
        return None
    try:
        return int(float(text))
    except (TypeError, ValueError):
        return None


def parse_float(value: Any) -> Optional[float]:
    text = cell_str(value)
    if not text:
        return None
    try:
        result = float(text)
    except (TypeError, ValueError):
        return None
    if result != result:  # NaN
        return None
    return result


def valid_latlon(lat: Optional[float], lon: Optional[float]) -> bool:
    """Reject 0/0 and out-of-range pairs; Assam sits well inside these bounds."""
    if lat is None or lon is None:
        return False
    if lat == 0 and lon == 0:
        return False
    return -90 <= lat <= 90 and -180 <= lon <= 180


def as_int_or_zero(value: Any) -> int:
    """Warehouse casts scheme ids to int, 0 when non-numeric (see README)."""
    parsed = parse_int(value)
    return parsed if parsed is not None else 0


# ─────────────────────────────────────────────────────────────────────────────
# Sheet model
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class PersonRef:
    name: str
    phone: str          # normalised 91XXXXXXXXXX
    role: str


@dataclass
class SheetRow:
    row_no: int                       # 1-based row number as shown in Excel's gutter
    scheme_name: str
    centre_id: str                    # raw, as written to the DB
    state_id: str
    centre_key: str                   # canonical, used for joins
    state_key: str
    work_status: Optional[int]
    operating_status: Optional[int]
    planned_fhtc: Optional[int]
    achieved_fhtc: Optional[int]
    latitude: Optional[float]
    longitude: Optional[float]
    zone: str
    circle: str
    division: str
    sub_division: str
    district: str
    block: str
    panchayat: str
    villages: list[str]
    # The state export's public code ("SCH-034035"), written to
    # scheme_master_table.state_scheme_code. Blank for sources that omit it.
    public_id: str = ""
    people: list[PersonRef] = field(default_factory=list)
    issues: list[str] = field(default_factory=list)

    @property
    def blocking_issues(self) -> list[str]:
        """Issues that stop the scheme row itself from being written."""
        return [i for i in self.issues if i.startswith("scheme:")]


@dataclass(frozen=True)
class SourceShape:
    """What the source file gives this run authority over.

    Presence of a column is the whole test. A file that never mentions villages
    has not dropped a village mapping — it has said nothing about villages — so
    `has_villages` gates writing *and* pruning together. Getting that wrong in
    the pruning direction would wipe every village mapping in the tenant the
    first time the CSV export was used.
    """
    columns: frozenset[str]
    skip_users: bool = False
    # Set from the database, not the file: the column only exists past V38.
    state_scheme_code_supported: bool = True

    @property
    def has_public_id(self) -> bool:
        return PUBLIC_ID_COLUMN in self.columns and self.state_scheme_code_supported

    @property
    def has_villages(self) -> bool:
        return "village_name" in self.columns

    @property
    def has_sub_divisions(self) -> bool:
        return "sub_divisions" in self.columns

    @property
    def has_operators(self) -> bool:
        return not self.skip_users and {"jalmitras", "jalmitra_phone"} <= self.columns

    @property
    def has_section_officers(self) -> bool:
        return not self.skip_users and {"so_name", "so_phone"} <= self.columns

    @property
    def has_users(self) -> bool:
        return self.has_operators or self.has_section_officers

    @property
    def roles(self) -> tuple[str, ...]:
        """The roles this file speaks for; nothing else's mapping is pruned."""
        found = []
        if self.has_operators:
            found.append(ROLE_PUMP_OPERATOR)
        if self.has_section_officers:
            found.append(ROLE_SECTION_OFFICER)
        return tuple(found)

    def describe(self) -> str:
        parts = [
            f"public_id={'yes' if self.has_public_id else 'no'}",
            f"villages={'yes' if self.has_villages else 'no'}",
            f"sub_divisions={'yes' if self.has_sub_divisions else 'no'}",
            f"users={'/'.join(self.roles) if self.roles else 'no'}",
        ]
        return ", ".join(parts)


def read_source_frame(
    path: str, sheet_name: Optional[str], header_row: int, encoding: str
) -> pd.DataFrame:
    """Read a .csv or an Excel workbook into a frame with normalised headers.

    Both known sources put a title line above the real header, hence header_row
    defaulting to 2 for either format.
    """
    if path.lower().endswith(".csv"):
        frame = pd.read_csv(
            path, header=header_row - 1, dtype=object, encoding=encoding,
            keep_default_na=True,
        )
    else:
        frame = pd.read_excel(
            path,
            sheet_name=sheet_name if sheet_name else 0,
            header=header_row - 1,
            dtype=object,
        )
    frame.columns = [str(c).strip().lower().replace(" ", "_") for c in frame.columns]
    return frame


def load_source(
    path: str,
    sheet_name: Optional[str],
    header_row: int,
    encoding: str = "utf-8-sig",
    skip_users: bool = False,
) -> tuple[list[SheetRow], list[dict], SourceShape]:
    """Read the snapshot and normalise every row.

    Returns (rows, per-row issue records, the shape of what was supplied).
    Only REQUIRED_COLUMNS have to be there; a missing optional column reads as
    blank on every row and narrows the run's authority via SourceShape.
    """
    frame = read_source_frame(path, sheet_name, header_row, encoding)

    missing = [c for c in REQUIRED_COLUMNS if c not in frame.columns]
    if missing:
        raise SystemExit(
            f"Source is missing required column(s): {', '.join(missing)}\n"
            f"Found: {', '.join(frame.columns)}"
        )
    present = frozenset(c for c in ALL_COLUMNS if c in frame.columns)
    shape = SourceShape(columns=present, skip_users=skip_users)
    absent = [c for c in OPTIONAL_COLUMNS if c not in present]
    if absent:
        LOG.info(
            "Optional column(s) not in this source, so neither written nor pruned: %s",
            ", ".join(absent),
        )
    LOG.info("Source supplies: %s", shape.describe())

    # Columns that decide whether a row is blank. Optional ones that are not
    # there cannot vote, or every row would look blank.
    populated = [c for c in ALL_COLUMNS if c in present]

    rows: list[SheetRow] = []
    issue_records: list[dict] = []

    for offset, raw in enumerate(frame.to_dict("records")):
        # +1 for the header row itself, +1 to make it 1-based like Excel's gutter.
        row_no = header_row + 1 + offset
        if all(_is_blank(raw.get(c)) for c in populated):
            continue

        issues: list[str] = []

        scheme_name = cell_str(raw.get("scheme_name"))
        if not scheme_name:
            issues.append("scheme:blank scheme_name")

        centre_id = cell_str(raw.get("imis_id"))
        state_id = cell_str(raw.get("smt_id"))
        if not centre_id and not state_id:
            issues.append("scheme:both imis_id and smt_id are blank")

        work_raw = normalise_status(raw.get("work_status"))
        work_status = WORK_STATUS_MAP.get(work_raw)
        # A blank / unrecognised work_status leaves work_status = None. This is
        # deliberately NOT a "scheme:" blocking issue: a row that matches an
        # existing scheme is still updated on all its other columns, and
        # work_status is simply left as whatever the DB already holds. Only a
        # brand-new scheme cannot be written without it (scheme_master_table
        # .work_status is NOT NULL) — classify_scheme enforces that on the insert
        # branch, mirroring the blank imis_id / smt_id guards.
        if work_raw and work_status is None:
            issues.append(
                f"work_status:unrecognised work_status "
                f"'{cell_str(raw.get('work_status'))}' — left unchanged on update, blocks insert only"
            )
        elif not work_raw:
            issues.append("work_status:blank work_status — left unchanged on update, blocks insert only")

        op_raw = normalise_status(raw.get("operating_status"))
        if not op_raw:
            # Blank operating_status = None: leave the existing value untouched on
            # update (do NOT force it to operative), and fall back to the app's
            # default only when inserting a brand-new scheme.
            operating_status = None
        else:
            resolved = OPERATING_STATUS_MAP.get(op_raw)
            if resolved is None:
                issues.append(
                    f"operating_status:unrecognised operating_status "
                    f"'{cell_str(raw.get('operating_status'))}' — left unchanged on update"
                )
                operating_status = None
            else:
                operating_status = resolved

        lat = parse_float(raw.get("latitude"))
        lon = parse_float(raw.get("longitude"))
        if (lat is not None or lon is not None) and not valid_latlon(lat, lon):
            issues.append("location:latitude/longitude present but not a usable pair")
            lat = lon = None

        people, people_issues = _extract_people(raw, shape)
        issues.extend(people_issues)

        row = SheetRow(
            row_no=row_no,
            scheme_name=scheme_name,
            centre_id=centre_id,
            state_id=state_id,
            centre_key=scheme_id_key(centre_id),
            state_key=scheme_id_key(state_id),
            work_status=work_status,
            operating_status=operating_status,
            planned_fhtc=parse_int(raw.get("planned_fhtc_imis")),
            achieved_fhtc=parse_int(raw.get("provided_fhtc_imis")),
            latitude=lat,
            longitude=lon,
            zone=cell_str(raw.get("zone")),
            circle=cell_str(raw.get("circle")),
            division=cell_str(raw.get("division")),
            sub_division=cell_str(raw.get("sub_divisions")),
            district=cell_str(raw.get("district")),
            block=cell_str(raw.get("blocks")),
            panchayat=cell_str(raw.get("panchayat_name")),
            villages=split_multi(raw.get("village_name")),
            public_id=cell_str(raw.get(PUBLIC_ID_COLUMN)),
            people=people,
            issues=issues,
        )
        rows.append(row)

        for issue in issues:
            kind, _, detail = issue.partition(":")
            issue_records.append({
                "row_no": row_no,
                "scheme_name": scheme_name,
                "imis_id": centre_id,
                "smt_id": state_id,
                "issue_kind": kind,
                "issue": detail,
            })

    return rows, issue_records, shape


def _extract_people(raw: dict, shape: SourceShape) -> tuple[list[PersonRef], list[str]]:
    """Pair the comma-separated name/phone cells positionally, per role.

    A name/phone count mismatch is never guessed at — the whole cell is dropped
    and reported, because mispairing would attach a real person's name to
    someone else's phone number.

    Roles the source does not carry (or that --skip-users switched off) never
    produce a PersonRef, so the whole user half of the run falls away with them.
    """
    people: list[PersonRef] = []
    issues: list[str] = []

    wanted = set(shape.roles)
    for name_col, phone_col, role in (
        ("jalmitras", "jalmitra_phone", ROLE_PUMP_OPERATOR),
        ("so_name", "so_phone", ROLE_SECTION_OFFICER),
    ):
        if role not in wanted:
            continue
        names = split_multi(raw.get(name_col))
        phones = split_multi(raw.get(phone_col))
        if not names and not phones:
            continue
        if len(names) != len(phones):
            issues.append(
                f"user:{name_col}/{phone_col} count mismatch "
                f"({len(names)} names vs {len(phones)} phones) — all skipped for this row"
            )
            continue
        for name, phone_raw in zip(names, phones):
            normalised = normalise_phone(phone_raw)
            if not normalised:
                issues.append(f"user:invalid {phone_col} for '{name}' — skipped")
                continue
            if not name.strip():
                issues.append(f"user:blank name in {name_col} — skipped")
                continue
            people.append(PersonRef(name=name.strip(), phone=normalised, role=role))

    return people, issues


def find_sheet_duplicates(rows: list[SheetRow]) -> tuple[dict[str, list[int]], dict[str, list[int]]]:
    """Ids repeated *within the sheet* — these break the 1:1 matching contract."""
    by_centre: dict[str, list[int]] = defaultdict(list)
    by_state: dict[str, list[int]] = defaultdict(list)
    for row in rows:
        if row.centre_key:
            by_centre[row.centre_key].append(row.row_no)
        if row.state_key:
            by_state[row.state_key].append(row.row_no)
    return (
        {k: v for k, v in by_centre.items() if len(v) > 1},
        {k: v for k, v in by_state.items() if len(v) > 1},
    )


def find_public_id_duplicates(rows: list[SheetRow]) -> dict[str, list[int]]:
    """public_id values repeated inside the source.

    V38 puts a partial UNIQUE index on state_scheme_code, so two rows claiming
    one code cannot both be written; catching it here reports both rows instead
    of failing the transaction on whichever reached the index second.
    """
    by_code: dict[str, list[int]] = defaultdict(list)
    for row in rows:
        if row.public_id:
            by_code[row.public_id.strip().lower()].append(row.row_no)
    return {k: v for k, v in by_code.items() if len(v) > 1}


# ─────────────────────────────────────────────────────────────────────────────
# Tenant database access
# ─────────────────────────────────────────────────────────────────────────────

def check_schema(schema: str) -> str:
    """Schema names are interpolated into SQL (identifiers can't be bound)."""
    if not schema or not SAFE_SCHEMA_RE.match(schema):
        raise SystemExit(f"Invalid schema name: {schema!r}")
    return schema


@dataclass
class SchemeSnapshot:
    id: int
    state_scheme_id: str
    centre_scheme_id: str
    scheme_name: str
    planned_fhtc: Optional[int]
    fhtc_count: Optional[int]
    house_hold_count: Optional[int]
    latitude: Optional[float]
    longitude: Optional[float]
    work_status: Optional[int]
    operating_status: Optional[int]
    state_scheme_code: Optional[str] = None   # NULL before V38, or never set
    is_active: Optional[bool] = None
    live: bool = True           # deleted_at IS NULL


@dataclass
class SchemeIndex:
    """Live and soft-deleted schemes, indexed separately.

    They are kept apart rather than merged because the matching contract treats
    them differently: a live match is an update, and a match found only among
    the retired rows is a revival. Merging them would make a retired scheme
    compete with a live one for the same id.
    """
    by_centre: dict[str, list[int]]
    by_state: dict[str, list[int]]
    snapshots: dict[int, SchemeSnapshot]
    retired_by_centre: dict[str, list[int]] = field(default_factory=dict)
    retired_by_state: dict[str, list[int]] = field(default_factory=dict)
    # lower(state_scheme_code) -> the live scheme already holding it. V38 puts a
    # partial UNIQUE index on that column, so writing a code another live scheme
    # owns aborts the whole transaction; this is what lets it be caught first.
    code_owners: dict[str, int] = field(default_factory=dict)

    @property
    def live_ids(self) -> set[int]:
        return {s.id for s in self.snapshots.values() if s.live}


@dataclass(frozen=True)
class MappingRowState:
    """One physical mapping row, whatever state it is in.

    The reconciler has to see retired and duplicate rows, not just the live
    ones: reviving the row a previous run retired is what stops a re-run
    stacking a second row on the same pair, and seeing the duplicates an
    earlier additive run left behind is what lets them be collapsed.

    `status` is None for the two location mapping tables, which carry no such
    column — there, live and usable are the same thing.
    """
    id: int
    left_id: int            # scheme_id, or user_id for user_scheme_mapping
    right_id: int           # parent_lgd_id / parent_department_id / scheme_id
    status: Optional[int]
    live: bool              # deleted_at IS NULL

    @property
    def pair(self) -> tuple[int, int]:
        return (self.left_id, self.right_id)

    @property
    def usable(self) -> bool:
        """Live *and* active — the only state the services will read."""
        return self.live and (self.status is None or self.status == MAPPING_STATUS_ACTIVE)


@dataclass
class SchemeActivity:
    """What flow_reading_table knows about a scheme we are about to retire."""
    scheme_id: int
    recent_readings: int
    total_readings: int
    last_reading_date: Optional[Any]


@dataclass
class LocationNode:
    id: int
    title: str
    level: int
    parent_id: Optional[int]


def _prefer_user(current: dict, candidate_live: bool, candidate_id: int) -> bool:
    """Should `candidate` replace `current` as the match for a phone hash?

    Several rows sharing a hash means the data is already inconsistent. Pick
    deterministically: a live row beats a retired one, and among equals the
    lowest id wins, so two runs never disagree about who the person is.
    """
    if current["live"] != candidate_live:
        return candidate_live
    return candidate_id < current["id"]


class TenantDb:
    """Reads and writes tenant_<code>.* . All identifiers are validated first."""

    def __init__(self, conn, schema: str, pii: PiiCrypto) -> None:
        self.conn = conn
        self.schema = check_schema(schema)
        self.pii = pii
        self._has_state_scheme_code: Optional[bool] = None

    # ---- reads ----------------------------------------------------------

    def resolve_tenant_id(self) -> int:
        state_code = self.schema.removeprefix("tenant_").upper()
        with self.conn.cursor() as cur:
            cur.execute(
                "SELECT id FROM common_schema.tenant_master_table WHERE upper(state_code) = %s",
                (state_code,),
            )
            found = cur.fetchall()
        if len(found) != 1:
            raise SystemExit(
                f"Expected exactly one tenant with state_code {state_code!r}, found {len(found)}. "
                f"Pass --tenant-id explicitly."
            )
        return found[0][0]

    def resolve_user_type_ids(self) -> dict[str, int]:
        with self.conn.cursor() as cur:
            cur.execute(
                "SELECT upper(c_name), id FROM common_schema.user_type_master_table "
                "WHERE deleted_at IS NULL"
            )
            types = {name: type_id for name, type_id in cur.fetchall()}
        missing = [r for r in (ROLE_PUMP_OPERATOR, ROLE_SECTION_OFFICER) if r not in types]
        if missing:
            raise SystemExit(
                f"common_schema.user_type_master_table has no row for: {', '.join(missing)}. "
                f"Seed the role(s) before ingesting users."
            )
        return types

    def state_scheme_code_column_exists(self) -> bool:
        """V38 adds scheme_master_table.state_scheme_code; older DBs lack it.

        Checked once rather than assumed, so the whole tool still runs against a
        database that has not taken V38 yet — the public code is simply neither
        read nor written there.
        """
        if self._has_state_scheme_code is None:
            with self.conn.cursor() as cur:
                cur.execute("""
                    SELECT 1 FROM information_schema.columns
                    WHERE table_schema = %s AND table_name = 'scheme_master_table'
                      AND column_name = %s
                """, (self.schema, STATE_SCHEME_CODE_COLUMN))
                self._has_state_scheme_code = cur.fetchone() is not None
        return self._has_state_scheme_code

    def load_scheme_index(self) -> SchemeIndex:
        """Every scheme, live and soft-deleted, indexed by canonical id.

        Soft-deleted rows are read deliberately. They used to be filtered out,
        which meant a re-run after any retirement stopped recognising the
        scheme and inserted a second copy of it — the exact duplicate this tool
        exists to avoid. They live in their own index so a retired row can never
        outrank a live one for the same id.
        """
        by_centre: dict[str, list[int]] = defaultdict(list)
        by_state: dict[str, list[int]] = defaultdict(list)
        retired_by_centre: dict[str, list[int]] = defaultdict(list)
        retired_by_state: dict[str, list[int]] = defaultdict(list)
        code_owners: dict[str, int] = {}
        snapshots: dict[int, SchemeSnapshot] = {}

        # The column may not exist yet; a literal NULL keeps the row shape and
        # the positional SchemeSnapshot(*rec) construction identical either way.
        code_expr = (
            STATE_SCHEME_CODE_COLUMN if self.state_scheme_code_column_exists()
            else "NULL::varchar"
        )
        with self.conn.cursor(name="scheme_scan") as cur:
            cur.itersize = 5000
            cur.execute(f"""
                SELECT id, state_scheme_id, centre_scheme_id, scheme_name,
                       planned_fhtc, fhtc_count, house_hold_count,
                       latitude, longitude, work_status, operating_status,
                       {code_expr}, is_active, deleted_at IS NULL
                FROM {self.schema}.scheme_master_table
            """)
            for rec in cur:
                snap = SchemeSnapshot(*rec)
                snapshots[snap.id] = snap
                centre_index = by_centre if snap.live else retired_by_centre
                state_index = by_state if snap.live else retired_by_state
                centre_key = scheme_id_key(snap.centre_scheme_id)
                state_key = scheme_id_key(snap.state_scheme_id)
                if centre_key:
                    centre_index[centre_key].append(snap.id)
                if state_key:
                    state_index[state_key].append(snap.id)
                # The unique index only covers live rows, so only they can block.
                if snap.live and snap.state_scheme_code:
                    code_owners.setdefault(snap.state_scheme_code.strip().lower(), snap.id)

        return SchemeIndex(
            dict(by_centre), dict(by_state), snapshots,
            dict(retired_by_centre), dict(retired_by_state), code_owners,
        )

    def load_scheme_activity(
        self, scheme_ids: Iterable[int], window_days: int
    ) -> dict[int, SchemeActivity]:
        """Reading history for the schemes a --replace run would retire.

        One aggregate rather than a probe per scheme: the recent count decides
        whether the scheme survives at all, and the totals put that decision in
        context in the report.
        """
        ids = sorted(set(scheme_ids))
        if not ids:
            return {}
        activity: dict[int, SchemeActivity] = {}
        with self.conn.cursor() as cur:
            for start in range(0, len(ids), 5000):
                cur.execute(f"""
                    SELECT scheme_id,
                           count(*) FILTER (
                               WHERE reading_date >= CURRENT_DATE - CAST(%s AS INTEGER)),
                           count(*),
                           max(reading_date)
                    FROM {self.schema}.flow_reading_table
                    WHERE deleted_at IS NULL AND scheme_id = ANY(%s)
                    GROUP BY scheme_id
                """, (window_days, ids[start:start + 5000]))
                for scheme_id, recent, total, last in cur:
                    activity[scheme_id] = SchemeActivity(scheme_id, recent, total, last)
        # A scheme with no readings at all never reaches the GROUP BY.
        for scheme_id in ids:
            activity.setdefault(scheme_id, SchemeActivity(scheme_id, 0, 0, None))
        return activity

    def load_locations(self, region_type: int) -> "LocationIndex":
        """All locations for a region type, indexed by (level, normalised title)."""
        table = "lgd_location_master_table" if region_type == 1 else "department_location_master_table"
        config_col = "lgd_location_config_id" if region_type == 1 else "department_location_config_id"

        nodes: dict[int, LocationNode] = {}
        by_level_name: dict[tuple[int, str], list[int]] = defaultdict(list)

        with self.conn.cursor() as cur:
            cur.execute(f"""
                SELECT l.id, l.title, c.level, l.parent_id
                FROM {self.schema}.{table} l
                JOIN {self.schema}.location_config_master_table c
                  ON c.id = l.{config_col} AND c.region_type = %s
                WHERE l.deleted_at IS NULL
            """, (region_type,))
            for loc_id, title, level, parent_id in cur:
                nodes[loc_id] = LocationNode(loc_id, title, level, parent_id)
                by_level_name[(level, norm_name(title))].append(loc_id)

        return LocationIndex(nodes, dict(by_level_name))

    def _load_ledger(
        self, table: str, left_col: str, right_col: str, with_status: bool
    ) -> dict[tuple[int, int], list[MappingRowState]]:
        """Every physical row of a mapping table, oldest first per pair.

        Deliberately unfiltered on deleted_at. The retired rows are exactly what
        makes a re-run idempotent — the reconciler revives one instead of
        inserting a duplicate — and the surplus live rows are what lets an
        earlier run's duplicates be collapsed. Ordering by id is what makes
        "keep the earliest row" deterministic.

        The whole table is read rather than a filtered slice: the tenant holds
        tens of thousands of these rows, one sequential scan is cheaper than the
        id lists that would narrow it, and pruning needs to see rows belonging
        to schemes the snapshot has dropped anyway.
        """
        status_expr = "status" if with_status else "NULL::integer"
        rows: dict[tuple[int, int], list[MappingRowState]] = defaultdict(list)
        with self.conn.cursor(name=f"{table}_scan") as cur:
            cur.itersize = 10000
            cur.execute(f"""
                SELECT id, {left_col}, {right_col}, {status_expr}, deleted_at IS NULL
                FROM {self.schema}.{table}
                ORDER BY id
            """)
            for row_id, left, right, status, live in cur:
                rows[(left, right)].append(
                    MappingRowState(row_id, left, right, status, live)
                )
        return dict(rows)

    def load_lgd_mapping_ledger(self) -> dict[tuple[int, int], list[MappingRowState]]:
        return self._load_ledger(
            "scheme_lgd_mapping_table", "scheme_id", "parent_lgd_id", with_status=False
        )

    def load_dept_mapping_ledger(self) -> dict[tuple[int, int], list[MappingRowState]]:
        return self._load_ledger(
            "scheme_department_mapping_table", "scheme_id", "parent_department_id",
            with_status=False,
        )

    def load_user_mapping_ledger(self) -> dict[tuple[int, int], list[MappingRowState]]:
        """Keyed (user_id, scheme_id), matching user_scheme_mapping_table."""
        return self._load_ledger(
            "user_scheme_mapping_table", "user_id", "scheme_id", with_status=True
        )

    def load_users_by_phone_hash(self, phone_hashes: Iterable[str]) -> dict[str, dict]:
        """Look up users by HMAC of the normalised phone (the encrypted column
        cannot be searched). Returns hash -> user record incl. decrypted title.

        Soft-deleted users are returned too, flagged `live=False`: onboarding a
        second account for someone we already hold — retired or not — would
        split their history across two ids and two phone-number ciphertexts.
        A live row always wins over a retired one carrying the same hash.
        """
        hashes = [h for h in dict.fromkeys(phone_hashes) if h]
        if not hashes:
            return {}

        found: dict[str, dict] = {}
        with self.conn.cursor() as cur:
            for start in range(0, len(hashes), 5000):
                batch = hashes[start:start + 5000]
                cur.execute(f"""
                    SELECT u.id, u.uuid, u.phone_number_hash, u.title, u.user_type,
                           u.status, u.email, ut.c_name, u.deleted_at IS NULL
                    FROM {self.schema}.user_table u
                    LEFT JOIN common_schema.user_type_master_table ut ON ut.id = u.user_type
                    WHERE u.phone_number_hash = ANY(%s)
                """, (batch,))
                for uid, uuid, phash, title_enc, user_type, status, email, c_name, live in cur:
                    previous = found.get(phash)
                    if previous is not None and not _prefer_user(previous, live, uid):
                        continue
                    found[phash] = {
                        "id": uid,
                        "uuid": uuid,
                        "title": self.pii.safe_decrypt(title_enc),
                        "user_type": user_type,
                        "role": (c_name or "").upper(),
                        "status": status,
                        "email": email,
                        "live": live,
                    }
        return found

    def load_user_roles(self, user_ids: Iterable[int]) -> dict[int, str]:
        """id -> role name, for mapping holders the snapshot never names."""
        ids = sorted(set(user_ids))
        if not ids:
            return {}
        roles: dict[int, str] = {}
        with self.conn.cursor() as cur:
            for start in range(0, len(ids), 5000):
                cur.execute(f"""
                    SELECT u.id, upper(ut.c_name)
                    FROM {self.schema}.user_table u
                    LEFT JOIN common_schema.user_type_master_table ut ON ut.id = u.user_type
                    WHERE u.id = ANY(%s)
                """, (ids[start:start + 5000],))
                for user_id, role in cur:
                    roles[user_id] = role or ""
        return roles

    def load_user_names(self, user_ids: Iterable[int]) -> dict[int, str]:
        """id -> decrypted name, for holders of a mapping the snapshot never names.

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

    def load_user_scheme_mappings(self, user_ids: Iterable[int]) -> dict[int, set[int]]:
        """Live, active mappings only — this is the warehouse's view of truth."""
        ids = list(dict.fromkeys(user_ids))
        if not ids:
            return {}
        result: dict[int, set[int]] = defaultdict(set)
        with self.conn.cursor() as cur:
            for start in range(0, len(ids), 5000):
                cur.execute(f"""
                    SELECT user_id, scheme_id
                    FROM {self.schema}.user_scheme_mapping_table
                    WHERE deleted_at IS NULL AND status = %s AND user_id = ANY(%s)
                """, (MAPPING_STATUS_ACTIVE, ids[start:start + 5000]))
                for user_id, scheme_id in cur:
                    result[user_id].add(scheme_id)
        return dict(result)

    def emails_in_use(self, emails: Iterable[str]) -> set[str]:
        """Which of these addresses are already taken, lower-cased, in one sweep.

        Soft-deleted rows count: the uniqueness constraint on email does not
        exclude them, so a collision with one is still a collision.
        """
        wanted = [e.lower() for e in dict.fromkeys(emails)]
        if not wanted:
            return set()
        found: set[str] = set()
        with self.conn.cursor() as cur:
            for start in range(0, len(wanted), 5000):
                cur.execute(
                    f"SELECT lower(email) FROM {self.schema}.user_table "
                    f"WHERE lower(email) = ANY(%s)",
                    (wanted[start:start + 5000],),
                )
                found.update(email for (email,) in cur)
        return found


# ─────────────────────────────────────────────────────────────────────────────
# Scheme classification
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class SchemeDecision:
    row: SheetRow
    category: str
    scheme_id: Optional[int] = None          # resolved existing scheme, when any
    reason: str = ""
    conflict_centre_scheme_id: Optional[int] = None
    conflict_state_scheme_id: Optional[int] = None
    changes: dict[str, tuple[Any, Any]] = field(default_factory=dict)  # col -> (old, new)
    # Which of the two ids the match is missing and should take from the sheet.
    # Held separately from `category` so a revival can adopt an id too.
    adopt_state_id: bool = False
    adopt_centre_id: bool = False
    # The public code this row may claim, or None when it may not — because the
    # source has none, the database predates V38, the source repeats the code,
    # or another live scheme already owns it (V38's UNIQUE index would reject
    # it). Decided once, in resolve_public_ids, and read by both the update diff
    # and the insert. The rest of the row is written either way.
    public_id_to_write: Optional[str] = None
    public_id_blocked_by: str = ""

    @property
    def will_write(self) -> bool:
        return self.category in {
            CAT_BOTH_MATCH, CAT_CENTRE_ONLY_STATE_NEW, CAT_STATE_ONLY_CENTRE_NEW,
            CAT_REVIVED, CAT_NEW,
        }

    @property
    def revives(self) -> bool:
        return self.category == CAT_REVIVED


@dataclass
class IdMatch:
    """Outcome of running the id-matching rules against one index.

    outcome: match | ambiguous | conflict | none
    """
    outcome: str
    reason: str = ""
    scheme_id: Optional[int] = None
    adopt_state_id: bool = False
    adopt_centre_id: bool = False
    centre_scheme_id: Optional[int] = None
    state_scheme_id: Optional[int] = None


def match_scheme_ids(
    row: SheetRow,
    by_centre: dict[str, list[int]],
    by_state: dict[str, list[int]],
    label: str,
) -> IdMatch:
    """Rules 1 and 2 of the matching contract, over one index.

    Factored out so the live rows and the soft-deleted rows are judged by
    exactly the same rules; `label` only colours the reasons the report shows.
    """
    centre_hits = by_centre.get(row.centre_key, []) if row.centre_key else []
    state_hits = by_state.get(row.state_key, []) if row.state_key else []

    # Rule 1 — a unique pair match wins outright, even if one id alone is
    # non-unique in our system.
    pair_hits = sorted(set(centre_hits) & set(state_hits))
    if len(pair_hits) == 1:
        return IdMatch(
            "match", scheme_id=pair_hits[0],
            reason=f"imis_id and smt_id both resolve to {label}scheme id {pair_hits[0]}",
        )
    if len(pair_hits) > 1:
        return IdMatch(
            "ambiguous",
            reason=f"imis_id + smt_id together match {len(pair_hits)} {label}schemes {pair_hits}",
        )

    # Rule 2 — fall back to single-id matching; multiplicity is unresolvable here.
    if len(centre_hits) > 1:
        return IdMatch(
            "ambiguous",
            reason=f"imis_id matches {len(centre_hits)} {label}schemes {sorted(centre_hits)} "
                   f"and smt_id does not narrow it down",
        )
    if len(state_hits) > 1:
        return IdMatch(
            "ambiguous",
            reason=f"smt_id matches {len(state_hits)} {label}schemes {sorted(state_hits)} "
                   f"and imis_id does not narrow it down",
        )

    centre_match = centre_hits[0] if centre_hits else None
    state_match = state_hits[0] if state_hits else None

    if centre_match is not None and state_match is not None:
        return IdMatch(
            "conflict", centre_scheme_id=centre_match, state_scheme_id=state_match,
            reason=f"imis_id -> {label}scheme id {centre_match} but "
                   f"smt_id -> {label}scheme id {state_match}",
        )

    if centre_match is not None:
        if not row.state_id:
            return IdMatch(
                "match", scheme_id=centre_match,
                reason=f"imis_id -> {label}scheme id {centre_match}; "
                       f"sheet has no smt_id to adopt",
            )
        return IdMatch(
            "match", scheme_id=centre_match, adopt_state_id=True,
            reason=f"imis_id -> {label}scheme id {centre_match}; "
                   f"smt_id {row.state_id} is unused in our system",
        )

    if state_match is not None:
        if not row.centre_id:
            return IdMatch(
                "match", scheme_id=state_match,
                reason=f"smt_id -> {label}scheme id {state_match}; "
                       f"sheet has no imis_id to adopt",
            )
        return IdMatch(
            "match", scheme_id=state_match, adopt_centre_id=True,
            reason=f"smt_id -> {label}scheme id {state_match}; "
                   f"imis_id {row.centre_id} is unused in our system",
        )

    return IdMatch("none")


def classify_scheme(row: SheetRow, index: SchemeIndex, dup_centre: dict, dup_state: dict) -> SchemeDecision:
    """Apply the matching contract to a single sheet row."""
    if row.blocking_issues:
        return SchemeDecision(row, CAT_INVALID, reason="; ".join(row.blocking_issues))

    # An id repeated inside the sheet cannot be reconciled 1:1 with our system.
    if row.centre_key and row.centre_key in dup_centre:
        others = [r for r in dup_centre[row.centre_key] if r != row.row_no]
        return SchemeDecision(
            row, CAT_INVALID,
            reason=f"imis_id repeated within the sheet (also on row(s) {others})",
        )
    if row.state_key and row.state_key in dup_state:
        others = [r for r in dup_state[row.state_key] if r != row.row_no]
        return SchemeDecision(
            row, CAT_INVALID,
            reason=f"smt_id repeated within the sheet (also on row(s) {others})",
        )

    live = match_scheme_ids(row, index.by_centre, index.by_state, "")
    if live.outcome == "ambiguous":
        return SchemeDecision(row, CAT_AMBIGUOUS, reason=live.reason)
    if live.outcome == "conflict":
        return SchemeDecision(
            row, CAT_CONFLICT, reason=live.reason,
            conflict_centre_scheme_id=live.centre_scheme_id,
            conflict_state_scheme_id=live.state_scheme_id,
        )
    if live.outcome == "match":
        if live.adopt_state_id:
            category = CAT_CENTRE_ONLY_STATE_NEW
        elif live.adopt_centre_id:
            category = CAT_STATE_ONLY_CENTRE_NEW
        else:
            category = CAT_BOTH_MATCH
        return SchemeDecision(
            row, category, scheme_id=live.scheme_id, reason=live.reason,
            adopt_state_id=live.adopt_state_id, adopt_centre_id=live.adopt_centre_id,
        )

    # Rule 3 — no live scheme carries either id. Before inserting, look among
    # the soft-deleted rows: finding one there means this scheme was retired
    # (by an earlier --replace run, or by hand) and the snapshot has brought it
    # back. Reviving keeps its id, its readings and its mappings; inserting
    # would strand all three behind a second row carrying the same two ids.
    retired = match_scheme_ids(row, index.retired_by_centre, index.retired_by_state,
                               "soft-deleted ")
    if retired.outcome == "ambiguous":
        return SchemeDecision(row, CAT_AMBIGUOUS, reason=retired.reason)
    if retired.outcome == "conflict":
        return SchemeDecision(
            row, CAT_CONFLICT, reason=retired.reason,
            conflict_centre_scheme_id=retired.centre_scheme_id,
            conflict_state_scheme_id=retired.state_scheme_id,
        )
    if retired.outcome == "match":
        return SchemeDecision(
            row, CAT_REVIVED, scheme_id=retired.scheme_id, reason=retired.reason,
            adopt_state_id=retired.adopt_state_id,
            adopt_centre_id=retired.adopt_centre_id,
        )

    # Neither id known — insert, but only if both NOT NULL ids are actually present.
    if not row.centre_id:
        return SchemeDecision(
            row, CAT_INVALID,
            reason="new scheme cannot be inserted: imis_id is blank (centre_scheme_id is NOT NULL)",
        )
    if not row.state_id:
        return SchemeDecision(
            row, CAT_INVALID,
            reason="new scheme cannot be inserted: smt_id is blank (state_scheme_id is NOT NULL)",
        )
    if row.work_status is None:
        return SchemeDecision(
            row, CAT_INVALID,
            reason="new scheme cannot be inserted: work_status is blank or unrecognised "
                   "(work_status is NOT NULL)",
        )
    return SchemeDecision(row, CAT_NEW, reason="neither imis_id nor smt_id exists in our system")


def resolve_public_ids(
    decisions: list[SchemeDecision], index: SchemeIndex, shape: SourceShape
) -> dict[str, list[int]]:
    """Decide which rows may claim their public_id, and say why not when they may not.

    V38 puts a partial UNIQUE index on scheme_master_table.state_scheme_code, so
    a code claimed twice cannot be written twice. Deciding it here, once, means
    the analysis and the two write paths (update diff, insert) all agree, and a
    mislabelled row costs that one column rather than aborting the transaction.

    Returns the public_id values the source itself repeats, for reporting.
    """
    repeated = find_public_id_duplicates([d.row for d in decisions])
    if not shape.has_public_id:
        return repeated

    for decision in decisions:
        code = decision.row.public_id
        if not code or not decision.will_write:
            continue
        key = code.strip().lower()
        if key in repeated:
            decision.public_id_blocked_by = (
                f"public_id '{code}' appears on rows {repeated[key]} of this source"
            )
            continue
        owner = index.code_owners.get(key)
        if owner is not None and owner != decision.scheme_id:
            decision.public_id_blocked_by = (
                f"scheme id {owner} already holds public_id '{code}'"
            )
            continue
        decision.public_id_to_write = code
    return repeated


def compute_scheme_changes(decision: SchemeDecision, index: SchemeIndex) -> None:
    """Fill decision.changes with the column-level diff an update would apply.

    Latitude/longitude are only ever filled in, never overwritten — the sheet is
    not authoritative for coordinates we already hold.
    """
    if decision.category == CAT_NEW or not decision.will_write:
        return
    snap = index.snapshots.get(decision.scheme_id)
    if snap is None:
        return

    row = decision.row
    changes: dict[str, tuple[Any, Any]] = {}

    if decision.public_id_to_write and snap.state_scheme_code != decision.public_id_to_write:
        changes[STATE_SCHEME_CODE_COLUMN] = (
            snap.state_scheme_code, decision.public_id_to_write
        )

    if row.scheme_name and row.scheme_name != snap.scheme_name:
        changes["scheme_name"] = (snap.scheme_name, row.scheme_name)
    if row.planned_fhtc is not None and row.planned_fhtc != snap.planned_fhtc:
        changes["planned_fhtc"] = (snap.planned_fhtc, row.planned_fhtc)
    if row.achieved_fhtc is not None and row.achieved_fhtc != snap.fhtc_count:
        changes["fhtc_count"] = (snap.fhtc_count, row.achieved_fhtc)
    if row.work_status is not None and row.work_status != snap.work_status:
        changes["work_status"] = (snap.work_status, row.work_status)
    if row.operating_status is not None and row.operating_status != snap.operating_status:
        changes["operating_status"] = (snap.operating_status, row.operating_status)

    # Only backfill coordinates that we are missing.
    if not valid_latlon(snap.latitude, snap.longitude) and valid_latlon(row.latitude, row.longitude):
        changes["latitude"] = (snap.latitude, row.latitude)
        changes["longitude"] = (snap.longitude, row.longitude)

    if decision.adopt_state_id and row.state_id:
        changes["state_scheme_id"] = (snap.state_scheme_id, row.state_id)
    if decision.adopt_centre_id and row.centre_id:
        changes["centre_scheme_id"] = (snap.centre_scheme_id, row.centre_id)

    decision.changes = changes


# ─────────────────────────────────────────────────────────────────────────────
# Location resolution (village -> LGD, sub-division -> department)
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class LocationMatch:
    name: str
    location_id: Optional[int]
    status: str          # resolved | resolved_by_hierarchy | not_found | ambiguous
    detail: str = ""


class LocationIndex:
    """Name -> location id lookups over one region type, with a cached ancestor
    chain so the 27k-row sweep does not re-walk parents for every candidate."""

    def __init__(
        self,
        nodes: dict[int, LocationNode],
        by_level_name: dict[tuple[int, str], list[int]],
    ) -> None:
        self.nodes = nodes
        self.by_level_name = by_level_name
        self._ancestors: dict[int, dict[int, str]] = {}

    def __len__(self) -> int:
        return len(self.nodes)

    def ancestor_titles(self, node_id: int) -> dict[int, str]:
        """level -> normalised title for a node and each of its ancestors."""
        cached = self._ancestors.get(node_id)
        if cached is not None:
            return cached
        titles: dict[int, str] = {}
        current = self.nodes.get(node_id)
        guard = 0
        while current is not None and guard < 12:
            titles[current.level] = norm_name(current.title)
            current = self.nodes.get(current.parent_id) if current.parent_id else None
            guard += 1
        self._ancestors[node_id] = titles
        return titles

    def resolve(
        self, name: str, level: int, hierarchy: list[tuple[int, str]]
    ) -> LocationMatch:
        """Resolve a name to exactly one location id, or report why it could not be.

        `hierarchy` is the sheet's context as (ancestor_level, ancestor_name)
        pairs, nearest parent first. It is consulted only when the name alone is
        ambiguous, and each filter is kept only if it leaves at least one
        candidate — so a wrong ancestor name in the sheet degrades to
        "ambiguous" instead of silently selecting the wrong location.
        """
        key = norm_name(name)
        if not key:
            return LocationMatch(name, None, "not_found", "blank name")

        candidates = self.by_level_name.get((level, key), [])
        if not candidates:
            return LocationMatch(name, None, "not_found", "no location with this name at this level")
        if len(candidates) == 1:
            return LocationMatch(name, candidates[0], "resolved", "unique by name")

        narrowed = list(candidates)
        used: list[str] = []
        for ancestor_level, ancestor_name in hierarchy:
            wanted = norm_name(ancestor_name)
            if not wanted:
                continue
            filtered = [
                cid for cid in narrowed
                if self.ancestor_titles(cid).get(ancestor_level) == wanted
            ]
            if filtered:
                narrowed = filtered
                used.append(ancestor_name)
            if len(narrowed) == 1:
                return LocationMatch(
                    name, narrowed[0], "resolved_by_hierarchy",
                    f"{len(candidates)} same-name locations narrowed to 1 using {' > '.join(used)}",
                )

        context = ", ".join(n for _, n in hierarchy if n) or "none given"
        return LocationMatch(
            name, None, "ambiguous",
            f"{len(candidates)} locations share this name; hierarchy ({context}) "
            f"narrowed to {len(narrowed)} — not written",
        )


def resolve_row_locations(
    row: SheetRow, lgd: LocationIndex, dept: LocationIndex
) -> tuple[list[LocationMatch], Optional[LocationMatch]]:
    """Resolve every village on the row plus its single sub-division."""
    village_hierarchy = [
        (LGD_LEVELS["panchayat"], row.panchayat),
        (LGD_LEVELS["block"], row.block),
        (LGD_LEVELS["district"], row.district),
    ]
    villages = [
        lgd.resolve(v, LGD_LEVELS["village"], village_hierarchy) for v in row.villages
    ]

    sub_division = None
    if row.sub_division:
        dept_hierarchy = [
            (DEPT_LEVELS["division"], row.division),
            (DEPT_LEVELS["circle"], row.circle),
            (DEPT_LEVELS["zone"], row.zone),
        ]
        sub_division = dept.resolve(
            row.sub_division, DEPT_LEVELS["sub_division"], dept_hierarchy
        )

    return villages, sub_division


# ─────────────────────────────────────────────────────────────────────────────
# User resolution
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class UserPlan:
    phone: str
    phone_hash: str
    role: str
    name: str
    existing_id: Optional[int] = None
    existing_uuid: Optional[str] = None
    existing_role: str = ""
    existing_name: Optional[str] = None
    existing_live: bool = True
    # insert | revive | update | unchanged | skip_role_conflict | skip_no_scheme
    action: str = "insert"
    reason: str = ""
    name_changed: bool = False
    rows: list[int] = field(default_factory=list)      # sheet rows referencing this person
    scheme_ids: set[int] = field(default_factory=set)  # schemes they should be mapped to
    new_scheme_ids: set[int] = field(default_factory=set)
    # True once any row that will actually be written mentions this person.
    referenced_by_written_scheme: bool = False


def build_user_plans(
    decisions: list[SchemeDecision],
    tenant: TenantDb,
    create_orphan_users: bool = False,
) -> tuple[dict[str, UserPlan], list[dict]]:
    """Collapse every person reference in the sheet into one plan per phone number.

    A phone number is the identity. When the same number appears with two
    different names the last one in sheet order wins (and it is reported);
    when it appears under two different roles the person is skipped, because
    guessing a role would silently change someone's permissions.

    People whose only sheet rows were skipped get no scheme mapping, so by
    default they are not created either — an account with no schemes cannot do
    anything, and once the underlying scheme problem is fixed a re-run picks
    them up. Pass create_orphan_users=True to onboard them anyway.
    """
    plans: dict[str, UserPlan] = {}
    conflicts: list[dict] = []

    for decision in decisions:
        for person in decision.row.people:
            plan = plans.get(person.phone)
            if plan is None:
                plan = UserPlan(
                    phone=person.phone,
                    phone_hash=tenant.pii.hmac(person.phone),
                    role=person.role,
                    name=person.name,
                )
                plans[person.phone] = plan
            else:
                if plan.role != person.role:
                    conflicts.append({
                        "row_no": decision.row.row_no,
                        "phone": person.phone,
                        "issue": f"same phone used as both {plan.role} and {person.role}",
                    })
                    plan.action = "skip_role_conflict"
                    plan.reason = f"sheet uses this number as both {plan.role} and {person.role}"
                elif norm_name(plan.name) != norm_name(person.name):
                    conflicts.append({
                        "row_no": decision.row.row_no,
                        "phone": person.phone,
                        "issue": f"same phone with different names: '{plan.name}' vs '{person.name}'",
                    })
                    plan.name = person.name
            plan.rows.append(decision.row.row_no)

            # Only map to schemes that will actually exist after this run.
            if decision.will_write:
                plan.referenced_by_written_scheme = True
                if decision.scheme_id is not None:
                    plan.scheme_ids.add(decision.scheme_id)
                else:
                    # New scheme: id is only known after insert; linked in execute phase.
                    plan.scheme_ids.add(-decision.row.row_no)

    # Resolve against the tenant DB in bulk.
    existing = tenant.load_users_by_phone_hash(p.phone_hash for p in plans.values())
    for plan in plans.values():
        if plan.action == "skip_role_conflict":
            continue
        if not plan.referenced_by_written_scheme and not create_orphan_users:
            plan.action = "skip_no_scheme"
            plan.reason = (
                "every sheet row naming this person was skipped, so there is no "
                "scheme to map them to"
            )
            continue
        match = existing.get(plan.phone_hash)
        if match is None:
            plan.action = "insert"
            plan.reason = "no user with this phone number"
            continue

        plan.existing_id = match["id"]
        plan.existing_uuid = match["uuid"]
        plan.existing_role = match["role"]
        plan.existing_name = match["title"]
        plan.existing_live = match["live"]

        if match["role"] and match["role"] != plan.role:
            # Mirrors PumpOperatorUploadChunkProcessor: never mutate a user who
            # already holds a different role.
            plan.action = "skip_role_conflict"
            plan.reason = (
                f"existing user id {match['id']} is a {match['role']}, sheet says {plan.role}"
            )
            conflicts.append({
                "row_no": plan.rows[0] if plan.rows else None,
                "phone": plan.phone,
                "issue": plan.reason,
            })
            continue

        plan.name_changed = norm_name(match["title"] or "") != norm_name(plan.name)
        if not match["live"]:
            # The snapshot names someone we hold as soft-deleted. Bring that row
            # back rather than onboarding a second account for the same phone:
            # a duplicate would split their readings across two user ids and
            # leave two rows sharing one phone_number_hash.
            plan.action = "revive"
            plan.reason = f"user id {match['id']} is soft-deleted — revived, not duplicated"
        else:
            plan.action = "update" if plan.name_changed else "unchanged"
            plan.reason = (
                "name differs from our record" if plan.name_changed else "already up to date"
            )

    return plans, conflicts


# ─────────────────────────────────────────────────────────────────────────────
# Mapping reconciliation
# ─────────────────────────────────────────────────────────────────────────────

@dataclass(frozen=True)
class Removal:
    row_id: int
    left_id: int
    right_id: int
    reason: str
    detail: str


@dataclass
class Reconciliation:
    """What one mapping table needs, diffed against every physical row it holds."""
    kind: str
    to_insert: list[tuple[int, int]] = field(default_factory=list)
    revivals: list[int] = field(default_factory=list)       # row ids to un-delete
    removals: list[Removal] = field(default_factory=list)   # row ids to retire
    unchanged: int = 0

    @property
    def removal_ids(self) -> list[int]:
        return [r.row_id for r in self.removals]

    def counts_by_reason(self) -> dict[str, int]:
        counts: Counter = Counter(r.reason for r in self.removals)
        return dict(counts)


def reconcile_pairs(
    kind: str,
    desired: set[tuple[int, int]],
    ledger: dict[tuple[int, int], list[MappingRowState]],
    prune_reason: Any = None,
) -> Reconciliation:
    """Diff what the snapshot states against every physical row we hold.

    Three rules, ordered by how much damage getting them wrong would do:

    1. a pair the snapshot states ends with exactly one live, usable row — a
       retired row is revived rather than duplicated, and the *earliest* row
       wins so the pair keeps its original created_at;
    2. a pair that already holds several live rows is collapsed to one. That
       changes no coverage and is reported under its own reason so it can never
       be read as one of the removals in rule 3;
    3. a live row the snapshot contradicts is retired — but only where
       `prune_reason(pair)` returns a (reason, detail) saying this run has the
       standing to judge that pair. Returning None leaves the row alone, which
       is how a source that omits a column keeps its hands off it.

    Rules 1 and 2 apply whether or not anything is being pruned: a run that
    declined to revive a retired row, or that left a pair holding two live
    rows, would be exactly the run that put the duplicates there.
    """
    result = Reconciliation(kind=kind)
    survivor: dict[tuple[int, int], int] = {}

    for pair in sorted(desired):
        rows = ledger.get(pair, [])
        if not rows:
            result.to_insert.append(pair)
            continue
        usable = [r for r in rows if r.usable]
        if usable:
            keep = usable[0]            # the ledger is ordered by id
            result.unchanged += 1
        else:
            keep = rows[0]
            result.revivals.append(keep.id)
        survivor[pair] = keep.id

    for pair, rows in sorted(ledger.items()):
        left_id, right_id = pair
        live = [r for r in rows if r.live]
        if not live:
            continue

        if pair in survivor:
            kept = survivor[pair]
            for row in live:
                if row.id == kept:
                    continue
                result.removals.append(Removal(
                    row.id, left_id, right_id, REMOVAL_DUPLICATE,
                    f"a second live row for a pair already held by row id {kept}; "
                    f"collapsed, coverage unchanged",
                ))
            continue

        verdict = prune_reason(pair) if prune_reason else None
        if verdict is None:
            continue
        reason, detail = verdict
        for row in live:
            result.removals.append(Removal(row.id, left_id, right_id, reason, detail))

    return result


# ─────────────────────────────────────────────────────────────────────────────
# Legacy schemes
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class LegacyScheme:
    """A live scheme whose ids the latest snapshot does not carry."""
    snapshot: SchemeSnapshot
    verdict: str                     # LEGACY_RETIRE | LEGACY_KEEP_RECENT_READINGS
    activity: SchemeActivity
    reason: str


def find_legacy_schemes(
    index: SchemeIndex,
    claimed_ids: set[int],
    activity: dict[int, SchemeActivity],
    window_days: int,
) -> list[LegacyScheme]:
    """Judge every live scheme the snapshot never claimed.

    A scheme is retired only when the snapshot has dropped it AND nothing has
    been reported for it inside the reading window. Recent readings mean data
    is still arriving, so the snapshot is behind, not the scheme — retiring it
    would break the operator's uploads and orphan facts already in the
    warehouse. Those are spared and listed separately, loudly, because they are
    the rows a human has to reconcile by hand.
    """
    legacy: list[LegacyScheme] = []
    for scheme_id in sorted(index.live_ids - claimed_ids):
        snap = index.snapshots[scheme_id]
        act = activity.get(scheme_id, SchemeActivity(scheme_id, 0, 0, None))
        if act.recent_readings > 0:
            legacy.append(LegacyScheme(
                snap, LEGACY_KEEP_RECENT_READINGS, act,
                f"absent from the snapshot but {act.recent_readings} reading(s) in the "
                f"last {window_days} days (latest {act.last_reading_date}) — NOT retired",
            ))
        else:
            last = f"last reading {act.last_reading_date}" if act.total_readings else "no readings ever"
            legacy.append(LegacyScheme(
                snap, LEGACY_RETIRE, act,
                f"absent from the snapshot and nothing in the last {window_days} days ({last})",
            ))
    return legacy


# ─────────────────────────────────────────────────────────────────────────────
# Tenant database writes
# ─────────────────────────────────────────────────────────────────────────────

# The only tables whose rows the lifecycle statements may address. Table names
# cannot be bound as parameters, so they are checked against this allow-list
# before being interpolated — same discipline as SCHEME_UPDATE_COLUMN_TYPES.
MAPPING_TABLES = {
    "user_scheme_mapping_table",
    "scheme_lgd_mapping_table",
    "scheme_department_mapping_table",
}


def _mapping_table(table: str) -> str:
    if table not in MAPPING_TABLES:
        raise ValueError(f"{table!r} is not one of the mapping tables this tool writes")
    return table


class TenantWriter:
    """Every write is parameterised; only the validated schema is interpolated."""

    def __init__(
        self,
        tenant: TenantDb,
        tenant_id: int,
        actor_id: int,
        user_type_ids: dict[str, int],
    ) -> None:
        self.db = tenant
        self.conn = tenant.conn
        self.schema = tenant.schema
        self.pii = tenant.pii
        self.tenant_id = tenant_id
        self.actor_id = actor_id
        self.user_type_ids = user_type_ids

    def assert_actor_is_tenant_user(self) -> None:
        """scheme_lgd/department_mapping_table.created_by has a real FK to
        user_table(id), so a bad actor id fails only at write time otherwise."""
        with self.conn.cursor() as cur:
            cur.execute(
                f"SELECT 1 FROM {self.schema}.user_table WHERE id = %s AND deleted_at IS NULL",
                (self.actor_id,),
            )
            if cur.fetchone() is None:
                raise SystemExit(
                    f"--actor-id {self.actor_id} is not a live row in {self.schema}.user_table. "
                    f"scheme_lgd_mapping_table.created_by and "
                    f"scheme_department_mapping_table.created_by both carry a foreign key to it."
                )

    def insert_schemes(
        self, decisions: list[SchemeDecision], with_public_id: bool = False
    ) -> dict[int, int]:
        """Insert new schemes. Returns sheet row_no -> new scheme id.

        state_scheme_code is only named when the source carries a public_id and
        the database has V38's column, so the same statement works on a
        database that predates it.
        """
        if not decisions:
            return {}
        code_column = f", {STATE_SCHEME_CODE_COLUMN}" if with_public_id else ""
        code_placeholder = ",%s" if with_public_id else ""
        payload = [
            (
                d.row.state_id, d.row.centre_id, d.row.scheme_name,
                d.row.achieved_fhtc or 0, d.row.planned_fhtc or 0,
                d.row.latitude, d.row.longitude,
                d.row.work_status,
                d.row.operating_status if d.row.operating_status is not None
                else DEFAULT_OPERATING_STATUS,
                *((d.public_id_to_write,) if with_public_id else ()),
                self.actor_id, self.actor_id,
            )
            for d in decisions
        ]
        sql = f"""
            INSERT INTO {self.schema}.scheme_master_table
                (state_scheme_id, centre_scheme_id, scheme_name,
                 fhtc_count, planned_fhtc, latitude, longitude,
                 work_status, operating_status{code_column},
                 created_by, created_at, updated_by, updated_at)
            VALUES %s
            RETURNING id
        """
        with self.conn.cursor() as cur:
            ids = psycopg2.extras.execute_values(
                cur, sql, payload,
                template=f"(%s,%s,%s,%s,%s,%s,%s,%s,%s{code_placeholder},%s,NOW(),%s,NOW())",
                fetch=True,
            )
        # execute_values preserves input order in RETURNING for a single INSERT.
        return {d.row.row_no: row[0] for d, row in zip(decisions, ids)}

    def update_schemes(self, decisions: list[SchemeDecision]) -> int:
        """Apply per-row column diffs. Rows whose diff is empty are not touched.

        Each row changes its own set of columns, so the work is grouped by that
        set and every group goes out as one UPDATE ... FROM (VALUES ...) per
        page. A per-row loop costs one network round trip each, and against a
        remote database that is the whole cost of the run: the current sheet
        produces ~25k updates spanning only ~60 distinct column sets, so this
        turns ~25k round trips into ~110.

        Column names come from compute_scheme_changes, never from the sheet, and
        are checked against SCHEME_UPDATE_COLUMN_TYPES before being interpolated.
        """
        groups: dict[tuple[str, ...], list[SchemeDecision]] = defaultdict(list)
        for decision in decisions:
            if not decision.changes:
                continue
            unknown = set(decision.changes) - set(SCHEME_UPDATE_COLUMN_TYPES)
            if unknown:
                raise ValueError(
                    f"No column type registered for {sorted(unknown)} — add it to "
                    f"SCHEME_UPDATE_COLUMN_TYPES before updating that column."
                )
            groups[tuple(sorted(decision.changes))].append(decision)

        updated = 0
        with self.conn.cursor() as cur:
            for columns, batch in groups.items():
                assignments = ", ".join(f"{c} = v.{c}" for c in columns)
                sql = f"""
                    UPDATE {self.schema}.scheme_master_table AS t
                    SET {assignments}, updated_by = v.updated_by, updated_at = NOW()
                    FROM (VALUES %s) AS v (id, updated_by, {", ".join(columns)})
                    WHERE t.id = v.id AND t.deleted_at IS NULL
                    RETURNING t.id
                """
                template = "(%s::integer, %s::integer, " + ", ".join(
                    f"%s::{SCHEME_UPDATE_COLUMN_TYPES[c]}" for c in columns
                ) + ")"
                payload = [
                    (d.scheme_id, self.actor_id, *(d.changes[c][1] for c in columns))
                    for d in batch
                ]
                # cur.rowcount only reflects the last page, so count what came
                # back instead — same number the per-row loop used to report.
                touched = psycopg2.extras.execute_values(
                    cur, sql, payload, template=template, page_size=500, fetch=True,
                )
                updated += len(touched)
        return updated

    def revive_schemes(self, scheme_ids: Iterable[int]) -> int:
        """Bring soft-deleted schemes back, rather than inserting a second copy.

        Clearing deleted_by as well as deleted_at matters: a live row still
        carrying the id of whoever retired it misreports its own history to
        anyone reading the audit columns.

        is_active is deliberately left alone. scheme-service's
        SchemeActivitySyncScheduler owns that column and recomputes it from
        recent flow readings; a scheme only ever gets retired here when it had
        none, so FALSE is the right value until that job next runs.
        """
        ids = sorted(set(scheme_ids))
        if not ids:
            return 0
        with self.conn.cursor() as cur:
            cur.execute(f"""
                UPDATE {self.schema}.scheme_master_table
                SET deleted_at = NULL, deleted_by = NULL,
                    updated_by = %s, updated_at = NOW()
                WHERE id = ANY(%s) AND deleted_at IS NOT NULL
                RETURNING id
            """, (self.actor_id, ids))
            return len(cur.fetchall())

    def retire_schemes(self, scheme_ids: Iterable[int]) -> int:
        """Soft-delete schemes the latest snapshot has dropped.

        Both halves are needed. is_active = FALSE is what the application reads,
        but SchemeActivitySyncScheduler recomputes that column on a timer and
        would flip it back; it only skips rows `WHERE deleted_at IS NULL`, so
        setting deleted_at in the same statement is what makes the retirement
        stick.
        """
        ids = sorted(set(scheme_ids))
        if not ids:
            return 0
        with self.conn.cursor() as cur:
            cur.execute(f"""
                UPDATE {self.schema}.scheme_master_table
                SET deleted_at = NOW(), deleted_by = %s, is_active = FALSE,
                    updated_by = %s, updated_at = NOW()
                WHERE id = ANY(%s) AND deleted_at IS NULL
                RETURNING id
            """, (self.actor_id, self.actor_id, ids))
            return len(cur.fetchall())

    def insert_users(self, plans: list[UserPlan]) -> None:
        """Create users the same way PumpOperatorUploadChunkProcessor does.

        Collisions on the generated email are resolved against one bulk lookup
        rather than a query per person — the address is derived from the phone
        number, so two plans can never generate the same one and the only way it
        can be taken is by a row we did not create.
        """
        import uuid as uuid_mod

        if not plans:
            return

        candidates = [f"{EMAIL_PREFIX[p.role]}{p.phone}{EMAIL_DOMAIN}" for p in plans]
        taken = self.db.emails_in_use(candidates)

        payload = []
        for plan, email in zip(plans, candidates):
            if email.lower() in taken:
                email = f"{EMAIL_PREFIX[plan.role]}{plan.phone}_{uuid_mod.uuid4()}{EMAIL_DOMAIN}"
            taken.add(email.lower())
            plan.existing_uuid = str(uuid_mod.uuid4())
            payload.append((
                plan.existing_uuid, self.tenant_id,
                self.pii.encrypt(plan.name), self.pii.title_hash(plan.name),
                email, self.user_type_ids[plan.role],
                self.pii.encrypt(plan.phone), self.pii.hmac(plan.phone),
                ONBOARD_PASSWORD, USER_STATUS_ACTIVE,
                self.actor_id, self.actor_id,
            ))

        sql = f"""
            INSERT INTO {self.schema}.user_table
                (uuid, tenant_id, title, title_hash, email, user_type,
                 phone_number, phone_number_hash, password, status,
                 email_verification_status, phone_verification_status,
                 created_by, created_at, updated_by, updated_at)
            VALUES %s
            RETURNING id
        """
        with self.conn.cursor() as cur:
            ids = psycopg2.extras.execute_values(
                cur, sql, payload,
                template="(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,true,true,%s,NOW(),%s,NOW())",
                page_size=500, fetch=True,
            )
        # execute_values preserves input order in RETURNING, page by page.
        for plan, row in zip(plans, ids):
            plan.existing_id = row[0]

    def update_user_names(self, plans: list[UserPlan]) -> int:
        """Only the name changes here — the phone is the match key, so it is
        already identical and re-encrypting it would churn the ciphertext."""
        if not plans:
            return 0
        with self.conn.cursor() as cur:
            psycopg2.extras.execute_batch(
                cur,
                f"""
                UPDATE {self.schema}.user_table
                SET title = %s, title_hash = %s, updated_by = %s, updated_at = NOW()
                WHERE id = %s AND deleted_at IS NULL
                """,
                [
                    (self.pii.encrypt(p.name), self.pii.title_hash(p.name), self.actor_id, p.existing_id)
                    for p in plans
                ],
                page_size=500,
            )
        return len(plans)

    def revive_users(self, plans: list[UserPlan]) -> int:
        """Un-delete users the snapshot names again, and refresh their name.

        status goes back to ACTIVE alongside deleted_at: every service read path
        wants `deleted_at IS NULL AND status = 1`, so a revived row left at
        status 0 would still be invisible.
        """
        if not plans:
            return 0
        with self.conn.cursor() as cur:
            psycopg2.extras.execute_batch(
                cur,
                f"""
                UPDATE {self.schema}.user_table
                SET deleted_at = NULL, deleted_by = NULL, status = %s,
                    title = %s, title_hash = %s, updated_by = %s, updated_at = NOW()
                WHERE id = %s
                """,
                [
                    (USER_STATUS_ACTIVE, self.pii.encrypt(p.name),
                     self.pii.title_hash(p.name), self.actor_id, p.existing_id)
                    for p in plans
                ],
                page_size=500,
            )
        return len(plans)

    def insert_user_scheme_mappings(self, pairs: list[tuple[int, int]]) -> int:
        """Only pairs holding no row at all reach here — the reconciler revives
        the rest, so a re-run can never stack a second row on the same pair."""
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

    def insert_lgd_mappings(self, pairs: list[tuple[int, int]]) -> int:
        if not pairs:
            return 0
        sql = f"""
            INSERT INTO {self.schema}.scheme_lgd_mapping_table
                (scheme_id, parent_lgd_id, parent_lgd_level,
                 created_by, created_at, updated_by, updated_at)
            VALUES %s
            RETURNING id
        """
        with self.conn.cursor() as cur:
            inserted = psycopg2.extras.execute_values(
                cur, sql,
                [(s, l, LGD_MAPPING_LEVEL, self.actor_id, self.actor_id) for s, l in pairs],
                template="(%s,%s,%s,%s,NOW(),%s,NOW())",
                page_size=1000, fetch=True,
            )
            return len(inserted)

    def insert_dept_mappings(self, pairs: list[tuple[int, int]]) -> int:
        if not pairs:
            return 0
        sql = f"""
            INSERT INTO {self.schema}.scheme_department_mapping_table
                (scheme_id, parent_department_id, parent_department_level,
                 created_by, created_at, updated_by, updated_at)
            VALUES %s
            RETURNING id
        """
        with self.conn.cursor() as cur:
            inserted = psycopg2.extras.execute_values(
                cur, sql,
                [(s, d, DEPT_MAPPING_LEVEL, self.actor_id, self.actor_id) for s, d in pairs],
                template="(%s,%s,%s,%s,NOW(),%s,NOW())",
                page_size=1000, fetch=True,
            )
            return len(inserted)

    # ---- mapping row lifecycle ------------------------------------------
    #
    # The reconciler addresses rows by id rather than by pair, because a pair
    # may hold several of them: collapsing a duplicate has to retire one row and
    # spare another carrying the same two ids.

    def retire_mapping_rows(self, table: str, row_ids: Iterable[int]) -> int:
        ids = sorted(set(row_ids))
        if not ids:
            return 0
        status_clause = ", status = %s" if table == "user_scheme_mapping_table" else ""
        params: list[Any] = [self.actor_id]
        if status_clause:
            params.append(MAPPING_STATUS_INACTIVE)
        params.extend([self.actor_id, ids])
        with self.conn.cursor() as cur:
            cur.execute(f"""
                UPDATE {self.schema}.{_mapping_table(table)}
                SET deleted_at = NOW(), deleted_by = %s{status_clause},
                    updated_by = %s, updated_at = NOW()
                WHERE id = ANY(%s) AND deleted_at IS NULL
                RETURNING id
            """, params)
            return len(cur.fetchall())

    def revive_mapping_rows(self, table: str, row_ids: Iterable[int]) -> int:
        ids = sorted(set(row_ids))
        if not ids:
            return 0
        status_clause = ", status = %s" if table == "user_scheme_mapping_table" else ""
        params: list[Any] = []
        if status_clause:
            params.append(MAPPING_STATUS_ACTIVE)
        params.extend([self.actor_id, ids])
        with self.conn.cursor() as cur:
            cur.execute(f"""
                UPDATE {self.schema}.{_mapping_table(table)}
                SET deleted_at = NULL, deleted_by = NULL{status_clause},
                    updated_by = %s, updated_at = NOW()
                WHERE id = ANY(%s)
                RETURNING id
            """, params)
            return len(cur.fetchall())


# ─────────────────────────────────────────────────────────────────────────────
# Analytics warehouse sync
# ─────────────────────────────────────────────────────────────────────────────

def hierarchy_levels(node_id: Optional[int], nodes: dict[int, LocationNode]) -> list[Optional[int]]:
    """level_1..level_5 ids for a leaf, walking the parent chain upward.

    Positional, matching the existing ingestion: the leaf sits at its own level
    and each ancestor at its own. Levels with no node stay NULL.
    """
    levels: list[Optional[int]] = [None] * 5
    current = nodes.get(node_id) if node_id else None
    guard = 0
    while current is not None and guard < 12:
        if 1 <= current.level <= 5:
            levels[current.level - 1] = current.id
        current = nodes.get(current.parent_id) if current.parent_id else None
        guard += 1
    return levels


@dataclass
class DimSchemeRow:
    scheme_id: int
    scheme_name: str
    state_scheme_id: int
    centre_scheme_id: int
    latitude: Optional[float]
    longitude: Optional[float]
    parent_lgd_location_id: int
    lgd_levels: list[Optional[int]]
    parent_department_location_id: Optional[int]
    dept_levels: list[Optional[int]]
    operating_status: int
    work_status: Optional[int]
    fhtc_count: int
    planned_fhtc: int
    house_hold_count: int

    @property
    def attributes(self) -> "SchemeAttributes":
        """The half of the row that belongs to the scheme, not to the location."""
        return SchemeAttributes(
            scheme_id=self.scheme_id,
            scheme_name=self.scheme_name,
            state_scheme_id=self.state_scheme_id,
            centre_scheme_id=self.centre_scheme_id,
            latitude=self.latitude,
            longitude=self.longitude,
            operating_status=self.operating_status,
            work_status=self.work_status,
            fhtc_count=self.fhtc_count,
            planned_fhtc=self.planned_fhtc,
            house_hold_count=self.house_hold_count,
        )


@dataclass(frozen=True)
class SchemeAttributes:
    """Scheme-level columns of dim_scheme_table.

    dim_scheme_table holds one row per scheme x village x sub-division, so a
    scheme's attributes are physically repeated across all of its rows. These
    are the columns that must agree on every one of them.
    """
    scheme_id: int
    scheme_name: str
    state_scheme_id: int
    centre_scheme_id: int
    latitude: Optional[float]
    longitude: Optional[float]
    operating_status: int
    work_status: Optional[int]
    fhtc_count: int
    planned_fhtc: int
    house_hold_count: int

    def differs_from(self, other: "SchemeAttributes") -> list[str]:
        return [
            f.name for f in fields(SchemeAttributes)
            if f.name != "scheme_id"
            and getattr(self, f.name) != getattr(other, f.name)
        ]


# The scheme-level columns, in the order sync_scheme_attributes binds them.
SCHEME_ATTRIBUTE_COLUMNS: list[tuple[str, str]] = [
    ("scheme_name", "varchar"),
    ("state_scheme_id", "integer"),
    ("centre_scheme_id", "integer"),
    ("latitude", "double precision"),
    ("longitude", "double precision"),
    ("operating_status", "integer"),
    ("work_status", "integer"),
    ("fhtc_count", "integer"),
    ("planned_fhtc", "integer"),
    ("house_hold_count", "integer"),
]


@dataclass
class DimSchemeState:
    """One physical dim_scheme_table row as the warehouse currently holds it."""
    id: int
    scheme_id: int
    parent_lgd_location_id: Optional[int]
    parent_department_location_id: Optional[int]
    attributes: SchemeAttributes


class AnalyticsDb:
    """Read side of the warehouse, used in analyze mode as well as execute.

    The drift the analysis has to describe — a scheme's other dim rows carrying
    stale attributes — only exists in the warehouse, so a read-only run has to
    look at it too.
    """

    def __init__(self, conn, tenant_id: int) -> None:
        self.conn = conn
        self.tenant_id = tenant_id

    def load_dim_scheme_rows(self) -> dict[int, list[DimSchemeState]]:
        """scheme_id -> every dim row this tenant holds for it."""
        rows: dict[int, list[DimSchemeState]] = defaultdict(list)
        columns = ", ".join(name for name, _ in SCHEME_ATTRIBUTE_COLUMNS)
        with self.conn.cursor(name="dim_scheme_scan") as cur:
            cur.itersize = 10000
            cur.execute(f"""
                SELECT id, scheme_id, parent_lgd_location_id,
                       parent_department_location_id, {columns}
                FROM analytics_schema.dim_scheme_table
                WHERE tenant_id = %s
                ORDER BY id
            """, (self.tenant_id,))
            for row_id, scheme_id, lgd, dept, *attrs in cur:
                rows[scheme_id].append(DimSchemeState(
                    id=row_id, scheme_id=scheme_id,
                    parent_lgd_location_id=lgd,
                    parent_department_location_id=dept,
                    attributes=SchemeAttributes(scheme_id, *attrs),
                ))
        return dict(rows)


class AnalyticsWriter:
    """Mirrors DimensionServiceImpl's upsert semantics for the three dim tables."""

    def __init__(self, conn, tenant_id: int) -> None:
        self.conn = conn
        self.tenant_id = tenant_id

    def assert_tenant_exists(self) -> None:
        with self.conn.cursor() as cur:
            cur.execute(
                "SELECT 1 FROM analytics_schema.dim_tenant_table WHERE tenant_id = %s",
                (self.tenant_id,),
            )
            if cur.fetchone() is None:
                raise SystemExit(
                    f"analytics_schema.dim_tenant_table has no tenant_id {self.tenant_id} — "
                    f"dim_scheme_table rows would violate its foreign key. Aborting."
                )

    def upsert_schemes(self, rows: list[DimSchemeRow]) -> int:
        """Upsert on (tenant_id, scheme_id, parent_lgd, parent_dept).

        The arbiter is named rather than inferred, matching step2_analytics.sql
        and rollover_test/04_dev_import_dim_scheme.sql. V24 creates it as a table
        constraint (UNIQUE NULLS NOT DISTINCT on the four plain columns), and an
        inferred arbiter written as COALESCE(col, -1) does not match that — it
        only matches a bare expression index, which is not what is deployed.
        Naming the constraint removes the guesswork.
        """
        if not rows:
            return 0
        payload = [
            (
                r.scheme_id, self.tenant_id, r.scheme_name,
                r.state_scheme_id, r.centre_scheme_id,
                r.longitude, r.latitude,
                r.parent_lgd_location_id, *r.lgd_levels,
                r.parent_department_location_id, *r.dept_levels,
                r.operating_status, r.work_status,
                r.fhtc_count, r.planned_fhtc, r.house_hold_count,
            )
            for r in rows
        ]
        sql = """
            INSERT INTO analytics_schema.dim_scheme_table (
                scheme_id, tenant_id, scheme_name, state_scheme_id, centre_scheme_id,
                longitude, latitude,
                parent_lgd_location_id,
                level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id,
                level_6_lgd_id,
                parent_department_location_id,
                level_1_dept_id, level_2_dept_id, level_3_dept_id, level_4_dept_id, level_5_dept_id,
                level_6_dept_id,
                operating_status, work_status,
                fhtc_count, planned_fhtc, house_hold_count,
                created_at, updated_at
            )
            VALUES %s
            ON CONFLICT ON CONSTRAINT uq_dim_scheme_tenant_scheme_parent_lgd_dept
            DO UPDATE SET
                scheme_name      = EXCLUDED.scheme_name,
                state_scheme_id  = EXCLUDED.state_scheme_id,
                centre_scheme_id = EXCLUDED.centre_scheme_id,
                longitude        = EXCLUDED.longitude,
                latitude         = EXCLUDED.latitude,
                level_1_lgd_id   = EXCLUDED.level_1_lgd_id,
                level_2_lgd_id   = EXCLUDED.level_2_lgd_id,
                level_3_lgd_id   = EXCLUDED.level_3_lgd_id,
                level_4_lgd_id   = EXCLUDED.level_4_lgd_id,
                level_5_lgd_id   = EXCLUDED.level_5_lgd_id,
                level_1_dept_id  = EXCLUDED.level_1_dept_id,
                level_2_dept_id  = EXCLUDED.level_2_dept_id,
                level_3_dept_id  = EXCLUDED.level_3_dept_id,
                level_4_dept_id  = EXCLUDED.level_4_dept_id,
                level_5_dept_id  = EXCLUDED.level_5_dept_id,
                operating_status = EXCLUDED.operating_status,
                work_status      = EXCLUDED.work_status,
                fhtc_count       = EXCLUDED.fhtc_count,
                planned_fhtc     = EXCLUDED.planned_fhtc,
                house_hold_count = EXCLUDED.house_hold_count,
                updated_at       = NOW()
        """
        template = (
            "(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,NULL,"
            "%s,%s,%s,%s,%s,%s,NULL,%s,%s,%s,%s,%s,NOW(),NOW())"
        )
        with self.conn.cursor() as cur:
            psycopg2.extras.execute_values(cur, sql, payload, template=template, page_size=500)
            return len(payload)

    def sync_scheme_attributes(self, attributes: list[SchemeAttributes]) -> list[int]:
        """Push a scheme's attributes onto *every* dim row it has.

        upsert_schemes only reaches the (scheme, village, sub-division) rows the
        snapshot produced. A scheme normally has more than that — one row per
        village it covers, plus rows for villages this source never mentioned,
        plus rows written before a village stopped resolving — and every one of
        them carries its own copy of the scheme's name, ids, coordinates,
        statuses and FHTC counts. Left alone they keep whatever they were last
        given, so the same scheme reports different names and different FHTC
        counts depending on which row a query happens to group by. This
        statement is what makes those columns agree across the whole scheme.

        Location columns are pointedly not touched: those genuinely differ per
        row, and overwriting them from one row's location is what would destroy
        the fan-out.

        The IS DISTINCT FROM guard means the returned ids are the rows that were
        actually carrying drift, so the count reported is a real finding rather
        than a row count.
        """
        if not attributes:
            return []
        assignments = ", ".join(f"{name} = v.{name}" for name, _ in SCHEME_ATTRIBUTE_COLUMNS)
        drifted = " OR ".join(
            f"t.{name} IS DISTINCT FROM v.{name}" for name, _ in SCHEME_ATTRIBUTE_COLUMNS
        )
        columns = ", ".join(name for name, _ in SCHEME_ATTRIBUTE_COLUMNS)
        sql = f"""
            UPDATE analytics_schema.dim_scheme_table AS t
            SET {assignments}, updated_at = NOW()
            FROM (VALUES %s) AS v (tenant_id, scheme_id, {columns})
            WHERE t.tenant_id = v.tenant_id AND t.scheme_id = v.scheme_id
              AND ({drifted})
            RETURNING t.id
        """
        template = "(%s::integer, %s::integer, " + ", ".join(
            f"%s::{sql_type}" for _, sql_type in SCHEME_ATTRIBUTE_COLUMNS
        ) + ")"
        payload = [
            (self.tenant_id, a.scheme_id, *(getattr(a, name) for name, _ in SCHEME_ATTRIBUTE_COLUMNS))
            for a in attributes
        ]
        with self.conn.cursor() as cur:
            touched = psycopg2.extras.execute_values(
                cur, sql, payload, template=template, page_size=500, fetch=True,
            )
        return [row[0] for row in touched]

    def deactivate_schemes(self, scheme_ids: Iterable[int]) -> int:
        """Mark a retired scheme's dim rows inactive.

        The rows themselves cannot go: fact_water_quantity_table,
        fact_meter_reading_table, fact_scheme_performance_table and
        dim_operator_attendance_table all carry a foreign key to
        dim_scheme_table (tenant_id, scheme_id), and a scheme retired for having
        no *recent* readings can still have years of older facts behind it.
        Deleting the dimension row would either fail on the constraint or take
        the history with it. operating_status = 0 is the warehouse's own
        INACTIVE marker (see V39), which is what the reports read.
        """
        ids = sorted(set(scheme_ids))
        if not ids:
            return 0
        with self.conn.cursor() as cur:
            cur.execute("""
                UPDATE analytics_schema.dim_scheme_table
                SET operating_status = 0, updated_at = NOW()
                WHERE tenant_id = %s AND scheme_id = ANY(%s) AND operating_status <> 0
                RETURNING id
            """, (self.tenant_id, ids))
            return len(cur.fetchall())

    def delete_scheme_user_mappings(self, scheme_ids: Iterable[int]) -> int:
        """Drop warehouse coverage for retired schemes.

        Unlike the dim row, these carry no history worth keeping — they only say
        who is responsible today, and after the retirement nobody is.
        """
        ids = sorted(set(scheme_ids))
        if not ids:
            return 0
        with self.conn.cursor() as cur:
            cur.execute("""
                DELETE FROM analytics_schema.dim_user_scheme_mapping_table
                WHERE tenant_id = %s AND scheme_id = ANY(%s)
            """, (self.tenant_id, ids))
            return cur.rowcount

    def upsert_users(self, users: list[dict]) -> int:
        """users: {user_id, uuid, email, title, user_type, status}."""
        if not users:
            return 0
        sql = """
            INSERT INTO analytics_schema.dim_user_table
                (user_id, tenant_id, email, user_type, title, uuid, status, created_at, updated_at)
            VALUES %s
            ON CONFLICT (tenant_id, user_id) DO UPDATE SET
                email      = EXCLUDED.email,
                user_type  = EXCLUDED.user_type,
                title      = EXCLUDED.title,
                uuid       = EXCLUDED.uuid,
                status     = EXCLUDED.status,
                updated_at = NOW()
        """
        payload = [
            (
                u["user_id"], self.tenant_id, u["email"], u["user_type"],
                u["title"], u["uuid"], u["status"],
            )
            for u in users
        ]
        with self.conn.cursor() as cur:
            psycopg2.extras.execute_values(
                cur, sql, payload, template="(%s,%s,%s,%s,%s,%s,%s,NOW(),NOW())", page_size=500
            )
            return len(payload)

    def replace_user_scheme_mappings(self, mappings: dict[int, set[int]]) -> int:
        """Delete-then-insert per user, exactly as DimensionServiceImpl does, so
        the warehouse ends up matching the tenant DB's post-state."""
        if not mappings:
            return 0
        import uuid as uuid_mod

        user_ids = list(mappings)
        inserted = 0
        with self.conn.cursor() as cur:
            for start in range(0, len(user_ids), 1000):
                batch = user_ids[start:start + 1000]
                cur.execute(
                    "DELETE FROM analytics_schema.dim_user_scheme_mapping_table "
                    "WHERE tenant_id = %s AND user_id = ANY(%s)",
                    (self.tenant_id, batch),
                )
            payload = [
                (str(uuid_mod.uuid4()), user_id, scheme_id, 1, self.tenant_id)
                for user_id in user_ids
                for scheme_id in sorted(mappings[user_id])
            ]
            if payload:
                psycopg2.extras.execute_values(
                    cur,
                    """
                    INSERT INTO analytics_schema.dim_user_scheme_mapping_table
                        (uuid, user_id, scheme_id, status, tenant_id, created_at, updated_at)
                    VALUES %s
                    """,
                    payload,
                    template="(%s,%s,%s,%s,%s,NOW(),NOW())",
                    page_size=1000,
                )
                inserted = len(payload)
        return inserted


# ─────────────────────────────────────────────────────────────────────────────
# Plan assembly
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class RowLocations:
    villages: list[LocationMatch] = field(default_factory=list)
    sub_division: Optional[LocationMatch] = None

    @property
    def village_ids(self) -> list[int]:
        return [m.location_id for m in self.villages if m.location_id]

    @property
    def dept_id(self) -> Optional[int]:
        return self.sub_division.location_id if self.sub_division else None


@dataclass
class IngestPlan:
    decisions: list[SchemeDecision]
    locations: dict[int, RowLocations]          # row_no -> resolved locations
    user_plans: dict[str, UserPlan]             # phone -> plan
    user_conflicts: list[dict]
    sheet_issues: list[dict]
    dup_centre: dict[str, list[int]]
    dup_state: dict[str, list[int]]
    scheme_index: SchemeIndex
    lgd: "LocationIndex"
    dept: "LocationIndex"
    shape: SourceShape
    replace: bool = False
    window_days: int = DEFAULT_READING_WINDOW_DAYS
    dup_public_id: dict[str, list[int]] = field(default_factory=dict)
    legacy: list[LegacyScheme] = field(default_factory=list)
    lgd_reconciliation: Reconciliation = field(
        default_factory=lambda: Reconciliation("scheme_lgd_mapping_table"))
    dept_reconciliation: Reconciliation = field(
        default_factory=lambda: Reconciliation("scheme_department_mapping_table"))
    user_reconciliation: Reconciliation = field(
        default_factory=lambda: Reconciliation("user_scheme_mapping_table"))
    # Decrypted names for mapping holders the snapshot never names, so the
    # removal report can say whose coverage is going.
    holder_names: dict[int, str] = field(default_factory=dict)
    # scheme_id -> its dim rows as the warehouse holds them today; empty when
    # the warehouse was not reachable (--skip-analytics or no DSN in analyze).
    dim_rows: dict[int, list[DimSchemeState]] = field(default_factory=dict)
    dim_read: bool = False

    def by_category(self) -> dict[str, list[SchemeDecision]]:
        grouped: dict[str, list[SchemeDecision]] = defaultdict(list)
        for decision in self.decisions:
            grouped[decision.category].append(decision)
        return grouped

    @property
    def retirement_candidates(self) -> set[int]:
        """Schemes the legacy check has judged retirable — a verdict, not an act.

        Reported on every run. Kept apart from schemes_to_retire so that reading
        the analysis and taking the action can never be confused for each other.
        """
        return {l.snapshot.id for l in self.legacy if l.verdict == LEGACY_RETIRE}

    @property
    def schemes_to_retire(self) -> set[int]:
        """The candidates this run will actually retire — empty without --replace."""
        return self.retirement_candidates if self.replace else set()

    @property
    def spared_scheme_ids(self) -> set[int]:
        return {l.snapshot.id for l in self.legacy if l.verdict == LEGACY_KEEP_RECENT_READINGS}

    @property
    def reconciliations(self) -> list[Reconciliation]:
        return [self.lgd_reconciliation, self.dept_reconciliation, self.user_reconciliation]


def build_plan(
    rows: list[SheetRow],
    sheet_issues: list[dict],
    tenant: TenantDb,
    shape: SourceShape,
    create_orphan_users: bool = False,
    replace: bool = False,
    window_days: int = DEFAULT_READING_WINDOW_DAYS,
    analytics_db: Optional[AnalyticsDb] = None,
) -> IngestPlan:
    LOG.info("Loading existing schemes from %s …", tenant.schema)
    # The public code is only usable when both sides carry it: the source needs
    # a public_id column and the database needs V38's state_scheme_code.
    if shape.state_scheme_code_supported and not tenant.state_scheme_code_column_exists():
        shape = dataclass_replace(shape, state_scheme_code_supported=False)
        if PUBLIC_ID_COLUMN in shape.columns:
            LOG.warning(
                "%s.scheme_master_table has no %s column — the source's public_id is "
                "ignored. Apply backend/database/"
                "V38__add_state_scheme_code_to_scheme_master_table.sql to store it.",
                tenant.schema, STATE_SCHEME_CODE_COLUMN,
            )
    scheme_index = tenant.load_scheme_index()
    LOG.info(
        "  %d live schemes, %d soft-deleted",
        len(scheme_index.live_ids), len(scheme_index.snapshots) - len(scheme_index.live_ids),
    )

    dup_centre, dup_state = find_sheet_duplicates(rows)
    if dup_centre or dup_state:
        LOG.warning(
            "Sheet has %d repeated imis_id and %d repeated smt_id value(s) — those rows are skipped",
            len(dup_centre), len(dup_state),
        )
    LOG.info("Classifying %d sheet rows …", len(rows))
    decisions = [classify_scheme(row, scheme_index, dup_centre, dup_state) for row in rows]
    dup_public_id = resolve_public_ids(decisions, scheme_index, shape)
    if dup_public_id and shape.has_public_id:
        LOG.warning(
            "Source repeats %d public_id value(s); state_scheme_code is left unwritten "
            "for those rows (it is UNIQUE per live scheme)", len(dup_public_id),
        )
    for decision in decisions:
        compute_scheme_changes(decision, scheme_index)

    LOG.info("Loading location hierarchies …")
    lgd = tenant.load_locations(region_type=1)
    dept = tenant.load_locations(region_type=2)
    LOG.info("  %d LGD nodes, %d department nodes", len(lgd), len(dept))

    locations: dict[int, RowLocations] = {}
    if shape.has_villages or shape.has_sub_divisions:
        LOG.info("Resolving villages and sub-divisions …")
        for decision in decisions:
            if not decision.will_write:
                continue
            villages, sub_division = resolve_row_locations(decision.row, lgd, dept)
            locations[decision.row.row_no] = RowLocations(villages, sub_division)
    else:
        LOG.info("Source carries no village or sub-division column — location mappings untouched")

    user_plans: dict[str, UserPlan] = {}
    user_conflicts: list[dict] = []
    if shape.has_users:
        LOG.info("Resolving users …")
        user_plans, user_conflicts = build_user_plans(decisions, tenant, create_orphan_users)
        LOG.info("  %d distinct people referenced", len(user_plans))
    else:
        LOG.info("Source carries no user column (or --skip-users) — users untouched")

    plan = IngestPlan(
        decisions=decisions,
        locations=locations,
        user_plans=user_plans,
        user_conflicts=user_conflicts,
        sheet_issues=sheet_issues,
        dup_centre=dup_centre,
        dup_state=dup_state,
        scheme_index=scheme_index,
        lgd=lgd,
        dept=dept,
        shape=shape,
        replace=replace,
        window_days=window_days,
        dup_public_id=dup_public_id,
    )

    plan.legacy = _judge_legacy_schemes(plan, tenant, window_days)
    _reconcile_all_mappings(plan, tenant)

    if analytics_db is not None:
        LOG.info("Reading dim_scheme_table …")
        plan.dim_rows = analytics_db.load_dim_scheme_rows()
        plan.dim_read = True
        LOG.info(
            "  %d schemes / %d dim rows in the warehouse",
            len(plan.dim_rows), sum(len(v) for v in plan.dim_rows.values()),
        )

    return plan


def _judge_legacy_schemes(
    plan: IngestPlan, tenant: TenantDb, window_days: int
) -> list[LegacyScheme]:
    """Which live schemes the snapshot has dropped, and what to do about each.

    Computed on every run, not only under --replace, because the analysis is
    supposed to surface the drift whether or not this run acts on it.
    """
    claimed = {d.scheme_id for d in plan.decisions if d.will_write and d.scheme_id}
    absent = plan.scheme_index.live_ids - claimed
    if not absent:
        return []

    LOG.info(
        "Checking %d scheme(s) absent from the snapshot for readings in the last %d days …",
        len(absent), window_days,
    )
    activity = tenant.load_scheme_activity(absent, window_days)
    legacy = find_legacy_schemes(plan.scheme_index, claimed, activity, window_days)

    retiring = sum(1 for l in legacy if l.verdict == LEGACY_RETIRE)
    spared = len(legacy) - retiring
    LOG.warning(
        "LEGACY: %d live scheme(s) are absent from the latest snapshot — "
        "%d have no reading in the last %d days (%s), %d still have recent readings "
        "and are NOT retired (see the legacy_schemes sheet)",
        len(legacy), retiring, window_days,
        "retiring" if plan.replace else "would be retired with --replace", spared,
    )
    return legacy


def _reconcile_all_mappings(plan: IngestPlan, tenant: TenantDb) -> None:
    """Diff all three mapping tables against the snapshot.

    Runs whatever the mode: the revive-and-collapse half of the reconciler is
    what keeps a re-run from duplicating rows, and that is not something
    --replace should have to be passed to get.
    """
    retiring = plan.schemes_to_retire
    # Schemes this snapshot actually spoke about; nothing outside them is judged
    # except through the retirement cascade above.
    claimed = {d.scheme_id for d in plan.decisions if d.will_write and d.scheme_id}

    LOG.info("Reconciling scheme -> village mappings …")
    desired_lgd: set[tuple[int, int]] = set()
    stated_villages: set[int] = set()
    for decision in plan.decisions:
        if not decision.will_write or not decision.scheme_id:
            continue
        loc = plan.locations.get(decision.row.row_no)
        if loc is None:
            continue
        if loc.village_ids:
            stated_villages.add(decision.scheme_id)
        for village_id in loc.village_ids:
            desired_lgd.add((decision.scheme_id, village_id))

    def lgd_prune(pair: tuple[int, int]) -> Optional[tuple[str, str]]:
        scheme_id = pair[0]
        if scheme_id in retiring:
            return (REMOVAL_SCHEME_RETIRED,
                    "the scheme itself is being retired by this run")
        if not plan.replace or not plan.shape.has_villages:
            return None
        # Only prune where the snapshot gave this scheme a village we could
        # resolve. Otherwise a village name we simply failed to look up would
        # read as "the snapshot dropped it" and take the mapping with it.
        if scheme_id in stated_villages:
            return (REMOVAL_NOT_IN_SNAPSHOT,
                    "the snapshot no longer places this scheme in this village")
        return None

    plan.lgd_reconciliation = reconcile_pairs(
        "scheme_lgd_mapping_table", desired_lgd, tenant.load_lgd_mapping_ledger(), lgd_prune,
    )

    LOG.info("Reconciling scheme -> sub-division mappings …")
    desired_dept: set[tuple[int, int]] = set()
    stated_depts: set[int] = set()
    for decision in plan.decisions:
        if not decision.will_write or not decision.scheme_id:
            continue
        loc = plan.locations.get(decision.row.row_no)
        if loc is None or not loc.dept_id:
            continue
        stated_depts.add(decision.scheme_id)
        desired_dept.add((decision.scheme_id, loc.dept_id))

    def dept_prune(pair: tuple[int, int]) -> Optional[tuple[str, str]]:
        scheme_id = pair[0]
        if scheme_id in retiring:
            return (REMOVAL_SCHEME_RETIRED,
                    "the scheme itself is being retired by this run")
        if not plan.replace or not plan.shape.has_sub_divisions:
            return None
        if scheme_id in stated_depts:
            return (REMOVAL_NOT_IN_SNAPSHOT,
                    "the snapshot no longer places this scheme in this sub-division")
        return None

    plan.dept_reconciliation = reconcile_pairs(
        "scheme_department_mapping_table", desired_dept,
        tenant.load_dept_mapping_ledger(), dept_prune,
    )

    LOG.info("Reconciling user -> scheme mappings …")
    desired_users: set[tuple[int, int]] = set()
    for user in plan.user_plans.values():
        if user.action.startswith("skip_") or not user.existing_id:
            continue
        # Negative ids are placeholders for schemes this run has yet to insert;
        # no ledger row can exist for them, so they are pure inserts and are
        # added once the insert has minted a real id.
        for scheme_id in user.scheme_ids:
            if scheme_id > 0:
                desired_users.add((user.existing_id, scheme_id))

    # A scheme whose own person could not be written must keep whoever covers
    # it today: retiring that mapping would leave the scheme with nobody at all.
    unwritable_people_schemes = {
        scheme_id
        for user in plan.user_plans.values() if user.action.startswith("skip_")
        for scheme_id in user.scheme_ids if scheme_id > 0
    }
    ledger = tenant.load_user_mapping_ledger()
    holder_ids = {left for left, _ in ledger}
    holder_roles = tenant.load_user_roles(holder_ids) if plan.shape.has_users else {}
    prunable_roles = set(plan.shape.roles)

    def user_prune(pair: tuple[int, int]) -> Optional[tuple[str, str]]:
        user_id, scheme_id = pair
        if scheme_id in retiring:
            return (REMOVAL_SCHEME_RETIRED,
                    "the scheme itself is being retired by this run")
        if not plan.replace or not plan.shape.has_users:
            return None
        if scheme_id not in claimed or scheme_id in unwritable_people_schemes:
            return None
        # Scoped to the roles this source speaks for, so ingesting a jalmitra
        # sheet can never retire a section officer's coverage.
        if holder_roles.get(user_id) not in prunable_roles:
            return None
        return (REMOVAL_NOT_IN_SNAPSHOT,
                "the snapshot no longer gives this person this scheme")

    plan.user_reconciliation = reconcile_pairs(
        "user_scheme_mapping_table", desired_users, ledger, user_prune,
    )
    plan.holder_names = tenant.load_user_names(
        {r.left_id for r in plan.user_reconciliation.removals}
    )

    # Push the reconciler's verdict back onto each plan, so the report and the
    # execute phase read the same number. A person we are about to onboard has
    # no id yet and therefore no ledger rows: every scheme they cover is an
    # insert. Placeholders survive either way and are resolved after the scheme
    # inserts have minted real ids.
    inserts_by_user: dict[int, set[int]] = defaultdict(set)
    for user_id, scheme_id in plan.user_reconciliation.to_insert:
        inserts_by_user[user_id].add(scheme_id)
    for user in plan.user_plans.values():
        if user.action.startswith("skip_"):
            user.new_scheme_ids = set()
            continue
        placeholders = {s for s in user.scheme_ids if s < 0}
        if user.existing_id:
            known = inserts_by_user.get(user.existing_id, set())
        else:
            known = {s for s in user.scheme_ids if s > 0}
        user.new_scheme_ids = known | placeholders


def scheme_attribute_targets(plan: IngestPlan) -> list[SchemeAttributes]:
    """The scheme-level dim columns each written scheme should end up with.

    One entry per scheme, not per sheet row and not per village: these are
    exactly the columns that must agree across all of a scheme's dim rows.
    Where the sheet is silent the tenant's existing value is carried over, so
    the result is the post-state of scheme_master_table rather than a partial
    view of the sheet.

    Called from the analysis (to show which warehouse rows have drifted) and
    from the execute leg (to fix them), so both describe the same target.
    """
    targets: dict[int, SchemeAttributes] = {}
    for decision in plan.decisions:
        if not decision.will_write or not decision.scheme_id:
            continue
        row = decision.row
        snap = plan.scheme_index.snapshots.get(decision.scheme_id)
        if valid_latlon(row.latitude, row.longitude):
            latitude, longitude = row.latitude, row.longitude
        else:
            latitude = snap.latitude if snap else None
            longitude = snap.longitude if snap else None
        targets[decision.scheme_id] = SchemeAttributes(
            scheme_id=decision.scheme_id,
            scheme_name=row.scheme_name,
            state_scheme_id=as_int_or_zero(row.state_id or (snap.state_scheme_id if snap else "")),
            centre_scheme_id=as_int_or_zero(row.centre_id or (snap.centre_scheme_id if snap else "")),
            latitude=latitude,
            longitude=longitude,
            # Mirror the tenant post-state: keep the existing value when the
            # sheet is silent (matched scheme), default only for a new insert.
            operating_status=row.operating_status if row.operating_status is not None
            else (snap.operating_status if snap and snap.operating_status is not None
                  else DEFAULT_OPERATING_STATUS),
            work_status=row.work_status if row.work_status is not None
            else (snap.work_status if snap else None),
            fhtc_count=row.achieved_fhtc if row.achieved_fhtc is not None else (
                (snap.fhtc_count or 0) if snap else 0
            ),
            planned_fhtc=row.planned_fhtc if row.planned_fhtc is not None else (
                (snap.planned_fhtc or 0) if snap else 0
            ),
            house_hold_count=(snap.house_hold_count if snap and snap.house_hold_count else 0),
        )
    return list(targets.values())


# ─────────────────────────────────────────────────────────────────────────────
# Analysis workbook
# ─────────────────────────────────────────────────────────────────────────────

def _fmt_changes(changes: dict[str, tuple[Any, Any]]) -> str:
    return "; ".join(f"{col}: {old!r} -> {new!r}" for col, (old, new) in sorted(changes.items()))


def build_summary_frame(plan: IngestPlan) -> pd.DataFrame:
    """The headline the analysis was asked for: schemes per match category."""
    grouped = plan.by_category()
    records = []
    for category in CATEGORY_ORDER:
        decisions = grouped.get(category, [])
        records.append({
            "category": category,
            "what it means": CATEGORY_DESCRIPTION[category],
            "action": CATEGORY_ACTION[category],
            "sheet rows": len(decisions),
        })
    total = len(plan.decisions)
    records.append({
        "category": "TOTAL", "what it means": "", "action": "",
        "sheet rows": total,
    })

    # A matched scheme whose columns already agree needs no UPDATE at all.
    updates = [d for d in plan.decisions if d.will_write and d.category != CAT_NEW]
    no_op = [d for d in updates if not d.changes]
    records.append({
        "category": "(of the matched rows) already up to date",
        "what it means": "matched an existing scheme but no column differs",
        "action": "no write",
        "sheet rows": len(no_op),
    })

    if plan.shape.has_public_id:
        writable = [d for d in plan.decisions if d.will_write]
        blocked = [d for d in writable if d.public_id_blocked_by]
        records.append({
            "category": "public_id -> state_scheme_code",
            "what it means": "rows whose public code this run stores on the scheme",
            "action": "write",
            "sheet rows": len([d for d in writable if d.public_id_to_write]),
        })
        records.append({
            "category": "public_id NOT stored (already claimed / repeated)",
            "what it means": "state_scheme_code is UNIQUE per live scheme; "
                             "see public_id_blocked in scheme_detail",
            "action": "column left unwritten, rest of the row still written",
            "sheet rows": len(blocked),
        })
    elif PUBLIC_ID_COLUMN in plan.shape.columns:
        records.append({
            "category": "public_id -> state_scheme_code",
            "what it means": "source carries public_id but the database has no "
                             "state_scheme_code column (apply V38)",
            "action": "ignored",
            "sheet rows": 0,
        })

    retiring = len(plan.retirement_candidates)
    spared = len(plan.spared_scheme_ids)
    records.append({
        "category": "LEGACY: live schemes absent from this snapshot",
        "what it means": f"we hold them, the snapshot does not name them "
                         f"(reading window {plan.window_days} days)",
        "action": "see legacy_schemes",
        "sheet rows": len(plan.legacy),
    })
    records.append({
        "category": "LEGACY: … retired (no recent readings)",
        "what it means": "is_active = FALSE + soft delete, mappings retired with them",
        "action": "retire" if plan.replace else "reported only — pass --replace to apply",
        "sheet rows": retiring,
    })
    records.append({
        "category": "LEGACY: … SPARED (still receiving readings)",
        "what it means": f"absent from the snapshot but a reading arrived in the last "
                         f"{plan.window_days} days — never retired, needs a human",
        "action": "keep + investigate",
        "sheet rows": spared,
    })
    return pd.DataFrame.from_records(records)


def build_legacy_frames(plan: IngestPlan) -> tuple[pd.DataFrame, pd.DataFrame]:
    """(distribution of legacy verdicts, one row per legacy scheme).

    The distribution is bucketed by reading recency rather than by verdict
    alone, because "absent from the snapshot but reported yesterday" and
    "absent and silent for two years" are two entirely different problems and
    the second is the only one safe to act on.
    """
    buckets: Counter = Counter()
    detail: list[dict] = []
    for entry in plan.legacy:
        act = entry.activity
        if entry.verdict == LEGACY_KEEP_RECENT_READINGS:
            bucket = f"SPARED — reading within {plan.window_days} days"
        elif act.total_readings == 0:
            bucket = "retire — no reading ever recorded"
        else:
            bucket = f"retire — last reading older than {plan.window_days} days"
        buckets[bucket] += 1
        detail.append({
            "scheme_id": entry.snapshot.id,
            "verdict": entry.verdict,
            "scheme_name": entry.snapshot.scheme_name,
            "imis_id": entry.snapshot.centre_scheme_id,
            "smt_id": entry.snapshot.state_scheme_id,
            "state_scheme_code": entry.snapshot.state_scheme_code,
            "is_active": entry.snapshot.is_active,
            "work_status": entry.snapshot.work_status,
            "operating_status": entry.snapshot.operating_status,
            f"readings_last_{plan.window_days}d": act.recent_readings,
            "readings_total": act.total_readings,
            "last_reading_date": act.last_reading_date,
            "dim_rows_held": len(plan.dim_rows.get(entry.snapshot.id, [])),
            "reason": entry.reason,
        })

    summary_records = [
        {"bucket": bucket, "schemes": count} for bucket, count in sorted(buckets.items())
    ]
    summary_records.append({
        "bucket": "TOTAL live schemes absent from the snapshot", "schemes": len(plan.legacy),
    })
    summary_records.append({
        "bucket": "applied by this run",
        "schemes": len(plan.schemes_to_retire),
    })
    summary = pd.DataFrame.from_records(summary_records)

    frame = pd.DataFrame.from_records(
        sorted(detail, key=lambda d: (d["verdict"], -d[f"readings_last_{plan.window_days}d"]))
    ) if detail else pd.DataFrame(columns=["scheme_id", "verdict", "scheme_name", "reason"])
    return summary, frame


def build_removal_frames(plan: IngestPlan) -> tuple[pd.DataFrame, pd.DataFrame]:
    """(what each mapping table gains and loses, every retired row with a reason)."""
    summary_records: list[dict] = []
    detail: list[dict] = []

    for rec in plan.reconciliations:
        by_reason = rec.counts_by_reason()
        summary_records.append({
            "table": rec.kind,
            "insert": len(rec.to_insert),
            "revive (was soft-deleted)": len(rec.revivals),
            "unchanged": rec.unchanged,
            "retire: not in snapshot": by_reason.get(REMOVAL_NOT_IN_SNAPSHOT, 0),
            "retire: scheme retired": by_reason.get(REMOVAL_SCHEME_RETIRED, 0),
            "retire: duplicate collapsed": by_reason.get(REMOVAL_DUPLICATE, 0),
        })
        is_user_table = rec.kind == "user_scheme_mapping_table"
        for removal in rec.removals:
            detail.append({
                "table": rec.kind,
                "row_id": removal.row_id,
                "user_id": removal.left_id if is_user_table else None,
                "user_name": plan.holder_names.get(removal.left_id, "") if is_user_table else "",
                "scheme_id": removal.right_id if is_user_table else removal.left_id,
                "location_id": None if is_user_table else removal.right_id,
                "reason": removal.reason,
                "detail": removal.detail,
            })

    summary = pd.DataFrame.from_records(summary_records)
    frame = pd.DataFrame.from_records(detail) if detail else pd.DataFrame(
        columns=["table", "row_id", "scheme_id", "reason", "detail"]
    )
    return summary, frame


def build_dim_drift_frame(plan: IngestPlan) -> pd.DataFrame:
    """dim rows of a scheme that disagree with the post-state this run computes.

    dim_scheme_table repeats every scheme-level column on each of a scheme's
    rows, one per village x sub-division. Only the combinations the snapshot
    reproduces get upserted, so any other row keeps whatever it was last given
    and the same scheme starts reporting two different names or two different
    FHTC counts. These are the rows the attribute sync will realign.
    """
    if not plan.dim_read:
        return pd.DataFrame(columns=["scheme_id", "note"])

    wanted = {a.scheme_id: a for a in scheme_attribute_targets(plan)}
    records: list[dict] = []
    for scheme_id, target in wanted.items():
        for state in plan.dim_rows.get(scheme_id, []):
            differing = target.differs_from(state.attributes)
            if not differing:
                continue
            records.append({
                "scheme_id": scheme_id,
                "dim_row_id": state.id,
                "parent_lgd_location_id": state.parent_lgd_location_id,
                "parent_department_location_id": state.parent_department_location_id,
                "columns_out_of_sync": ", ".join(differing),
                "current_scheme_name": state.attributes.scheme_name,
                "corrected_scheme_name": target.scheme_name,
                "current_fhtc_count": state.attributes.fhtc_count,
                "corrected_fhtc_count": target.fhtc_count,
            })
    return pd.DataFrame.from_records(records) if records else pd.DataFrame(
        columns=["scheme_id", "dim_row_id", "columns_out_of_sync"]
    )


def build_scheme_detail_frame(plan: IngestPlan) -> pd.DataFrame:
    records = []
    for decision in plan.decisions:
        row = decision.row
        loc = plan.locations.get(row.row_no)
        records.append({
            "row_no": row.row_no,
            "category": decision.category,
            "action": CATEGORY_ACTION[decision.category],
            "reason": decision.reason,
            "scheme_name": row.scheme_name,
            "imis_id": row.centre_id,
            "smt_id": row.state_id,
            "public_id": row.public_id,
            "public_id_blocked": decision.public_id_blocked_by,
            "matched_scheme_id": decision.scheme_id,
            "columns_to_change": _fmt_changes(decision.changes),
            "villages_in_sheet": len(row.villages),
            "villages_resolved": len(loc.village_ids) if loc else 0,
            "sub_division_resolved": bool(loc and loc.dept_id) if loc else False,
            "people_in_row": len(row.people),
            "district": row.district,
            "sub_division": row.sub_division,
        })
    return pd.DataFrame.from_records(records)


def build_conflict_frame(plan: IngestPlan) -> pd.DataFrame:
    """Rows skipped because the two ids disagree — the list to hand back for correction."""
    records = []
    for decision in plan.decisions:
        if decision.category not in (CAT_CONFLICT, CAT_AMBIGUOUS):
            continue
        centre_snap = plan.scheme_index.snapshots.get(decision.conflict_centre_scheme_id)
        state_snap = plan.scheme_index.snapshots.get(decision.conflict_state_scheme_id)
        records.append({
            "row_no": decision.row.row_no,
            "category": decision.category,
            "sheet_scheme_name": decision.row.scheme_name,
            "sheet_imis_id": decision.row.centre_id,
            "sheet_smt_id": decision.row.state_id,
            "our_scheme_id_via_imis": decision.conflict_centre_scheme_id,
            "our_name_via_imis": centre_snap.scheme_name if centre_snap else "",
            "our_smt_id_via_imis": centre_snap.state_scheme_id if centre_snap else "",
            "our_scheme_id_via_smt": decision.conflict_state_scheme_id,
            "our_name_via_smt": state_snap.scheme_name if state_snap else "",
            "our_imis_id_via_smt": state_snap.centre_scheme_id if state_snap else "",
            "reason": decision.reason,
        })
    return pd.DataFrame.from_records(records) if records else pd.DataFrame(
        columns=["row_no", "category", "sheet_scheme_name", "reason"]
    )


def build_location_frames(plan: IngestPlan) -> tuple[pd.DataFrame, pd.DataFrame]:
    """(summary of resolution outcomes, every unresolved name with its reason)."""
    village_counts: Counter = Counter()
    dept_counts: Counter = Counter()
    unresolved: list[dict] = []

    for decision in plan.decisions:
        loc = plan.locations.get(decision.row.row_no)
        if loc is None:
            continue
        for match in loc.villages:
            village_counts[match.status] += 1
            if match.location_id is None:
                unresolved.append({
                    "row_no": decision.row.row_no,
                    "kind": "village",
                    "name": match.name,
                    "status": match.status,
                    "detail": match.detail,
                    "district": decision.row.district,
                    "block": decision.row.block,
                    "panchayat": decision.row.panchayat,
                })
        if loc.sub_division is not None:
            dept_counts[loc.sub_division.status] += 1
            if loc.sub_division.location_id is None:
                unresolved.append({
                    "row_no": decision.row.row_no,
                    "kind": "sub_division",
                    "name": loc.sub_division.name,
                    "status": loc.sub_division.status,
                    "detail": loc.sub_division.detail,
                    "district": decision.row.division,
                    "block": decision.row.circle,
                    "panchayat": decision.row.zone,
                })

    summary = pd.DataFrame.from_records(
        [{"kind": "village", "status": s, "count": c} for s, c in sorted(village_counts.items())]
        + [{"kind": "sub_division", "status": s, "count": c} for s, c in sorted(dept_counts.items())]
    )
    if summary.empty:
        summary = pd.DataFrame(columns=["kind", "status", "count"])
    detail = pd.DataFrame.from_records(unresolved) if unresolved else pd.DataFrame(
        columns=["row_no", "kind", "name", "status", "detail"]
    )
    return summary, detail


def build_user_frames(plan: IngestPlan, include_pii: bool) -> tuple[pd.DataFrame, pd.DataFrame]:
    show = (lambda p: p) if include_pii else mask_phone

    counts: Counter = Counter()
    for user in plan.user_plans.values():
        counts[f"{user.role} / {user.action}"] += 1
    summary = pd.DataFrame.from_records(
        [{"role / action": k, "people": v} for k, v in sorted(counts.items())]
    )
    if summary.empty:
        summary = pd.DataFrame(columns=["role / action", "people"])
    rec = plan.user_reconciliation
    summary = pd.concat([summary, pd.DataFrame.from_records([
        {"role / action": "TOTAL distinct people", "people": len(plan.user_plans)},
        {"role / action": "new user_scheme_mapping rows",
         "people": sum(len(u.new_scheme_ids) for u in plan.user_plans.values()
                       if not u.action.startswith("skip_"))},
        {"role / action": "user_scheme_mapping rows revived from soft delete",
         "people": len(rec.revivals)},
        {"role / action": "user_scheme_mapping rows retired", "people": len(rec.removals)},
    ])], ignore_index=True)

    detail = pd.DataFrame.from_records([
        {
            "phone": show(user.phone),
            "role": user.role,
            "action": user.action,
            "reason": user.reason,
            "sheet_name": user.name,
            "our_name": user.existing_name or "",
            "name_changes": user.name_changed,
            "existing_user_id": user.existing_id,
            "existing_role": user.existing_role,
            "schemes_referenced": len(user.scheme_ids),
            "new_mappings": len(user.new_scheme_ids),
            "sheet_rows": ", ".join(str(r) for r in user.rows[:20]),
        }
        for user in sorted(plan.user_plans.values(), key=lambda u: (u.role, u.action, u.phone))
    ]) if plan.user_plans else pd.DataFrame(columns=["phone", "role", "action"])

    return summary, detail


def build_analytics_frame(plan: IngestPlan, drift: pd.DataFrame) -> pd.DataFrame:
    """dim_scheme_table.parent_lgd_location_id is NOT NULL, so a scheme with no
    resolvable village cannot get a *new* row in the warehouse. It can still
    have rows from an earlier run, and those are kept in step by the attribute
    sync rather than left to drift."""
    writable = [d for d in plan.decisions if d.will_write]
    with_village = 0
    without_village = 0
    dim_rows = 0
    for decision in writable:
        loc = plan.locations.get(decision.row.row_no)
        village_ids = loc.village_ids if loc else []
        if village_ids:
            with_village += 1
            dim_rows += len(village_ids)
        else:
            without_village += 1

    targets = scheme_attribute_targets(plan)
    held = sum(len(plan.dim_rows.get(a.scheme_id, [])) for a in targets)
    fanned_out = sum(1 for a in targets if len(plan.dim_rows.get(a.scheme_id, [])) > 1)

    records = [
        {"metric": "schemes written to tenant DB", "value": len(writable)},
        {"metric": "…with >=1 resolved village (eligible for a new dim_scheme_table row)",
         "value": with_village},
        {"metric": "…with no resolved village (no new dim row: parent_lgd_location_id is NOT NULL)",
         "value": without_village},
        {"metric": "dim_scheme_table rows upserted (one per scheme x village)", "value": dim_rows},
        {"metric": "dim_user_table rows upserted",
         "value": len([u for u in plan.user_plans.values() if not u.action.startswith("skip_")])},
    ]
    if plan.dim_read:
        records.extend([
            {"metric": "dim_scheme_table rows the warehouse holds for these schemes",
             "value": held},
            {"metric": "…schemes holding more than one row (the fan-out that can drift)",
             "value": fanned_out},
            {"metric": "…rows currently out of sync with the post-state (realigned by this run)",
             "value": len(drift)},
            {"metric": "dim rows dropped to operating_status = 0 (retired schemes)",
             "value": sum(len(plan.dim_rows.get(s, [])) for s in plan.schemes_to_retire)},
        ])
    else:
        records.append({
            "metric": "dim_scheme_table drift",
            "value": "not checked — no analytics DSN, or --skip-analytics",
        })
    return pd.DataFrame.from_records(records)


def write_analysis_workbook(plan: IngestPlan, path: str, include_pii: bool, context: dict) -> None:
    summary = build_summary_frame(plan)
    scheme_detail = build_scheme_detail_frame(plan)
    conflicts = build_conflict_frame(plan)
    loc_summary, loc_detail = build_location_frames(plan)
    user_summary, user_detail = build_user_frames(plan, include_pii)
    legacy_summary, legacy_detail = build_legacy_frames(plan)
    removal_summary, removal_detail = build_removal_frames(plan)
    drift = build_dim_drift_frame(plan)
    analytics = build_analytics_frame(plan, drift)

    issues = pd.DataFrame.from_records(plan.sheet_issues) if plan.sheet_issues else pd.DataFrame(
        columns=["row_no", "scheme_name", "issue_kind", "issue"]
    )
    user_conflicts = pd.DataFrame.from_records([
        {**c, "phone": (c["phone"] if include_pii else mask_phone(c["phone"]))}
        for c in plan.user_conflicts
    ]) if plan.user_conflicts else pd.DataFrame(columns=["row_no", "phone", "issue"])

    run_info = pd.DataFrame.from_records(
        [{"setting": k, "value": str(v)} for k, v in context.items()]
    )

    with pd.ExcelWriter(path, engine="openpyxl") as writer:
        run_info.to_excel(writer, sheet_name="run_info", index=False)
        summary.to_excel(writer, sheet_name="scheme_summary", index=False)
        legacy_summary.to_excel(writer, sheet_name="legacy_summary", index=False)
        removal_summary.to_excel(writer, sheet_name="mapping_summary", index=False)
        analytics.to_excel(writer, sheet_name="analytics_summary", index=False)
        user_summary.to_excel(writer, sheet_name="user_summary", index=False)
        loc_summary.to_excel(writer, sheet_name="location_summary", index=False)
        legacy_detail.to_excel(writer, sheet_name="legacy_schemes", index=False)
        removal_detail.to_excel(writer, sheet_name="mapping_removals", index=False)
        drift.to_excel(writer, sheet_name="dim_scheme_drift", index=False)
        conflicts.to_excel(writer, sheet_name="scheme_conflicts", index=False)
        issues.to_excel(writer, sheet_name="sheet_issues", index=False)
        loc_detail.to_excel(writer, sheet_name="location_unresolved", index=False)
        user_conflicts.to_excel(writer, sheet_name="user_conflicts", index=False)
        user_detail.to_excel(writer, sheet_name="user_detail", index=False)
        scheme_detail.to_excel(writer, sheet_name="scheme_detail", index=False)

    LOG.info("Analysis workbook written to %s", path)


# ─────────────────────────────────────────────────────────────────────────────
# Execute
# ─────────────────────────────────────────────────────────────────────────────

def execute_tenant(plan: IngestPlan, writer: TenantWriter) -> dict[str, int]:
    """Apply the whole tenant-side plan in one transaction.

    Order matters in two places. Revivals happen before anything is written to
    a scheme, because an update statement filters on `deleted_at IS NULL` and
    would silently skip a row still marked deleted. Retirements happen last, so
    a scheme is only ever retired after every mapping hanging off it has been.
    """
    grouped = plan.by_category()
    stats: dict[str, int] = {}

    revivals = grouped.get(CAT_REVIVED, [])
    stats["schemes_revived"] = writer.revive_schemes(
        d.scheme_id for d in revivals if d.scheme_id
    )

    inserts = grouped.get(CAT_NEW, [])
    new_ids = writer.insert_schemes(inserts, with_public_id=plan.shape.has_public_id)
    for decision in inserts:
        decision.scheme_id = new_ids.get(decision.row.row_no)
    stats["schemes_inserted"] = len(new_ids)

    updates = [
        d for d in plan.decisions
        if d.will_write and d.category != CAT_NEW and d.changes
    ]
    stats["schemes_updated"] = writer.update_schemes(updates)
    stats["schemes_unchanged"] = len(
        [d for d in plan.decisions if d.will_write and d.category != CAT_NEW and not d.changes]
    )

    # New schemes were held as negative placeholders (-row_no) until their real
    # ids existed; swap them in now. A placeholder with no real id means the
    # insert did not happen, so that mapping is dropped.
    placeholder_to_real = {-d.row.row_no: d.scheme_id for d in inserts if d.scheme_id}

    def resolve_ids(scheme_ids: set[int]) -> set[int]:
        resolved = set()
        for scheme_id in scheme_ids:
            real = placeholder_to_real.get(scheme_id) if scheme_id < 0 else scheme_id
            if real and real > 0:
                resolved.add(real)
        return resolved

    for user in plan.user_plans.values():
        user.scheme_ids = resolve_ids(user.scheme_ids)
        user.new_scheme_ids = resolve_ids(user.new_scheme_ids)

    to_revive = [u for u in plan.user_plans.values() if u.action == "revive" and u.existing_id]
    stats["users_revived"] = writer.revive_users(to_revive)

    to_insert = [u for u in plan.user_plans.values() if u.action == "insert"]
    writer.insert_users(to_insert)
    stats["users_inserted"] = len(to_insert)

    to_update = [u for u in plan.user_plans.values() if u.action == "update" and u.existing_id]
    stats["users_updated"] = writer.update_user_names(to_update)

    user_rec = plan.user_reconciliation
    stats["user_scheme_mappings_revived"] = writer.revive_mapping_rows(
        "user_scheme_mapping_table", user_rec.revivals
    )
    # The reconciler could only see pairs whose ids both already existed. Users
    # and schemes minted a moment ago add the rest, and cannot collide with a
    # ledger row by construction.
    mapping_pairs = sorted({
        (u.existing_id, scheme_id)
        for u in plan.user_plans.values()
        if not u.action.startswith("skip_") and u.existing_id
        for scheme_id in u.new_scheme_ids
    })
    stats["user_scheme_mappings_inserted"] = writer.insert_user_scheme_mappings(mapping_pairs)

    lgd_rec = plan.lgd_reconciliation
    dept_rec = plan.dept_reconciliation
    stats["scheme_lgd_mappings_revived"] = writer.revive_mapping_rows(
        "scheme_lgd_mapping_table", lgd_rec.revivals
    )
    stats["scheme_lgd_mappings_inserted"] = writer.insert_lgd_mappings(
        sorted(_with_new_scheme_ids(lgd_rec.to_insert, plan, placeholder_to_real, "villages"))
    )
    stats["scheme_department_mappings_revived"] = writer.revive_mapping_rows(
        "scheme_department_mapping_table", dept_rec.revivals
    )
    stats["scheme_department_mappings_inserted"] = writer.insert_dept_mappings(
        sorted(_with_new_scheme_ids(dept_rec.to_insert, plan, placeholder_to_real, "dept"))
    )

    stats["scheme_lgd_mappings_retired"] = writer.retire_mapping_rows(
        "scheme_lgd_mapping_table", lgd_rec.removal_ids
    )
    stats["scheme_department_mappings_retired"] = writer.retire_mapping_rows(
        "scheme_department_mapping_table", dept_rec.removal_ids
    )
    stats["user_scheme_mappings_retired"] = writer.retire_mapping_rows(
        "user_scheme_mapping_table", user_rec.removal_ids
    )

    if plan.replace:
        stats["schemes_retired"] = writer.retire_schemes(plan.schemes_to_retire)
        stats["schemes_spared_recent_readings"] = len(plan.spared_scheme_ids)
    return stats


def _with_new_scheme_ids(
    pairs: list[tuple[int, int]],
    plan: IngestPlan,
    placeholder_to_real: dict[int, int],
    kind: str,
) -> set[tuple[int, int]]:
    """The reconciler's inserts, plus the ones only a freshly inserted scheme has.

    A scheme created by this run had no id when the ledger was diffed, so its
    location mappings never reached the reconciler. It also holds no rows in
    that ledger — it did not exist — so adding them here cannot duplicate
    anything.
    """
    result = set(pairs)
    for decision in plan.decisions:
        if decision.category != CAT_NEW:
            continue
        scheme_id = placeholder_to_real.get(-decision.row.row_no)
        if not scheme_id:
            continue
        loc = plan.locations.get(decision.row.row_no)
        if loc is None:
            continue
        if kind == "villages":
            result.update((scheme_id, village_id) for village_id in loc.village_ids)
        elif loc.dept_id:
            result.add((scheme_id, loc.dept_id))
    return result


def execute_analytics(
    plan: IngestPlan,
    analytics: AnalyticsWriter,
    tenant: TenantDb,
    user_type_ids: dict[str, int],
) -> dict[str, int]:
    """Project the post-state into the warehouse.

    dim_scheme_table needs one row per (scheme, village, sub-division) and its
    parent_lgd_location_id is NOT NULL, so a scheme whose village never
    resolved gets no *new* row. It can still have rows from earlier runs, and
    the attribute sync below reaches those, which is the whole point of doing
    the sync separately from the upsert.
    """
    stats: dict[str, int] = {}
    dim_rows: list[DimSchemeRow] = []
    skipped_no_village = 0

    targets = {a.scheme_id: a for a in scheme_attribute_targets(plan)}

    for decision in plan.decisions:
        if not decision.will_write or not decision.scheme_id:
            continue
        loc = plan.locations.get(decision.row.row_no)
        village_ids = loc.village_ids if loc else []
        if not village_ids:
            skipped_no_village += 1
            continue

        attrs = targets[decision.scheme_id]
        dept_id = loc.dept_id if loc else None
        dept_levels = hierarchy_levels(dept_id, plan.dept.nodes) if dept_id else [None] * 5

        for village_id in village_ids:
            dim_rows.append(DimSchemeRow(
                scheme_id=attrs.scheme_id,
                scheme_name=attrs.scheme_name,
                state_scheme_id=attrs.state_scheme_id,
                centre_scheme_id=attrs.centre_scheme_id,
                latitude=attrs.latitude,
                longitude=attrs.longitude,
                parent_lgd_location_id=village_id,
                lgd_levels=hierarchy_levels(village_id, plan.lgd.nodes),
                parent_department_location_id=dept_id,
                dept_levels=dept_levels,
                operating_status=attrs.operating_status,
                work_status=attrs.work_status,
                fhtc_count=attrs.fhtc_count,
                planned_fhtc=attrs.planned_fhtc,
                house_hold_count=attrs.house_hold_count,
            ))

    stats["dim_scheme_rows_upserted"] = analytics.upsert_schemes(dim_rows)
    stats["dim_scheme_skipped_no_village"] = skipped_no_village

    # Every *other* row the scheme has — a village this source did not mention,
    # a combination written by an earlier run — carries its own copy of the
    # scheme-level columns and would otherwise keep drifting away from the ones
    # just upserted. Run after the upsert, so the count reported is exactly the
    # rows that had drifted.
    realigned = analytics.sync_scheme_attributes(list(targets.values()))
    stats["dim_scheme_rows_realigned"] = len(realigned)

    if plan.schemes_to_retire:
        stats["dim_scheme_rows_deactivated"] = analytics.deactivate_schemes(
            plan.schemes_to_retire
        )
        stats["dim_user_scheme_mappings_deleted"] = analytics.delete_scheme_user_mappings(
            plan.schemes_to_retire
        )

    users = [
        {
            "user_id": u.existing_id,
            "uuid": u.existing_uuid,
            "email": None,
            "user_type": user_type_ids[u.role],
            "title": u.name,
            "status": USER_STATUS_ACTIVE,
        }
        for u in plan.user_plans.values()
        if not u.action.startswith("skip_") and u.existing_id
    ]
    # dim_user_table.email mirrors user_table.email; read the authoritative values back.
    emails = {}
    ids = [u["user_id"] for u in users]
    if ids:
        with tenant.conn.cursor() as cur:
            for start in range(0, len(ids), 5000):
                cur.execute(
                    f"SELECT id, email, uuid FROM {tenant.schema}.user_table WHERE id = ANY(%s)",
                    (ids[start:start + 5000],),
                )
                for uid, email, uuid in cur:
                    emails[uid] = (email, uuid)
    for user in users:
        email, uuid = emails.get(user["user_id"], (None, user["uuid"]))
        user["email"] = email
        user["uuid"] = uuid

    stats["dim_user_rows_upserted"] = analytics.upsert_users(users)

    # Replace each touched user's mappings with the tenant DB's post-state.
    touched = [u["user_id"] for u in users]
    post_state = tenant.load_user_scheme_mappings(touched)
    mappings = {uid: post_state.get(uid, set()) for uid in touched}
    stats["dim_user_scheme_mapping_rows"] = analytics.replace_user_scheme_mappings(mappings)
    return stats


# ─────────────────────────────────────────────────────────────────────────────
# CLI
# ─────────────────────────────────────────────────────────────────────────────

def parse_args(argv: Optional[list[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Reconcile the JJM master sheet into a Jal Soochak tenant + analytics warehouse.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__.split("Usage")[-1],
    )
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--excel", help="path to the master data workbook (all_ascheme_exist.xlsx)")
    source.add_argument("--csv", help="path to the CSV snapshot (schemes-master-data.csv); "
                                      "it carries no village, sub-division or user column, so "
                                      "those mappings are neither written nor pruned")
    parser.add_argument("--sheet", default=None, help="worksheet name (default: first sheet)")
    parser.add_argument("--header-row", type=int, default=2,
                        help="1-based row holding the column headers (default: 2)")
    parser.add_argument("--encoding", default="utf-8-sig",
                        help="CSV encoding (default: utf-8-sig)")
    parser.add_argument("--schema", default="tenant_as", help="tenant schema (default: tenant_as)")
    parser.add_argument("--tenant-id", type=int, default=None,
                        help="tenant id; resolved from the schema's state code when omitted")
    parser.add_argument("--actor-id", type=int, required=True,
                        help="user_table.id recorded as created_by/updated_by; the LGD and "
                             "department mapping tables have a foreign key to it")
    parser.add_argument("--out", default="jjm_master_analysis.xlsx",
                        help="analysis workbook path (default: jjm_master_analysis.xlsx)")
    parser.add_argument("--tenant-dsn", default=os.environ.get("TENANT_DSN"),
                        help="tenant DB DSN (default: $TENANT_DSN)")
    parser.add_argument("--analytics-dsn", default=os.environ.get("ANALYTICS_DSN"),
                        help="analytics DB DSN (default: $ANALYTICS_DSN)")
    parser.add_argument("--execute", action="store_true",
                        help="apply the plan; without this the run is read-only")
    parser.add_argument("--replace", action="store_true",
                        help="treat the snapshot as the complete current truth: retire live "
                             "schemes it does not name (unless they still have recent "
                             "readings) and the mappings it contradicts. Without it the "
                             "legacy distribution is still reported, just not applied")
    parser.add_argument("--reading-window-days", type=int, default=DEFAULT_READING_WINDOW_DAYS,
                        help=f"a scheme absent from the snapshot is spared if it has a flow "
                             f"reading inside this many days "
                             f"(default: {DEFAULT_READING_WINDOW_DAYS})")
    parser.add_argument("--skip-users", action="store_true",
                        help="ignore the user columns entirely: no user is created, updated "
                             "or mapped, and no user mapping is pruned")
    parser.add_argument("--skip-analytics", action="store_true",
                        help="do not touch the analytics warehouse")
    parser.add_argument("--create-orphan-users", action="store_true",
                        help="also onboard people whose every sheet row was skipped; by "
                             "default they are reported but not created, since they would "
                             "have no scheme mapping")
    parser.add_argument("--include-pii", action="store_true",
                        help="write full phone numbers into the analysis workbook "
                             "(masked by default)")
    parser.add_argument("--limit", type=int, default=None,
                        help="process only the first N sheet rows (for rehearsals)")
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
    if args.replace and args.limit:
        # A slice is not a snapshot: everything the slice happens not to reach
        # would look like a scheme the state has dropped.
        return _fail(
            "--replace cannot be combined with --limit — retirement decides what to delete "
            "from what the file does NOT contain, so it needs the whole file."
        )
    if args.reading_window_days < 1:
        return _fail("--reading-window-days must be at least 1")

    try:
        pii = PiiCrypto(os.environ.get("PII_ENCRYPTION_KEY", ""), os.environ.get("PII_HMAC_KEY", ""))
    except ValueError as exc:
        return _fail(str(exc))

    source_path = args.excel or args.csv
    LOG.info("Reading %s …", source_path)
    rows, sheet_issues, shape = load_source(
        source_path, args.sheet, args.header_row, args.encoding, args.skip_users
    )
    if args.limit:
        rows = rows[: args.limit]
        keep = {r.row_no for r in rows}
        sheet_issues = [i for i in sheet_issues if i["row_no"] in keep]
    LOG.info("  %d data rows", len(rows))

    tenant_conn = psycopg2.connect(args.tenant_dsn)
    tenant_conn.autocommit = False
    analytics_conn = None
    exit_code = 0

    try:
        tenant = TenantDb(tenant_conn, args.schema, pii)
        tenant_id = args.tenant_id or tenant.resolve_tenant_id()
        user_type_ids = tenant.resolve_user_type_ids()
        LOG.info("Tenant id %d, schema %s", tenant_id, tenant.schema)

        # The warehouse is read in analyze mode too: the dim_scheme drift the
        # report has to describe only exists there.
        analytics_db = None
        if not args.skip_analytics and args.analytics_dsn:
            analytics_conn = psycopg2.connect(args.analytics_dsn)
            analytics_conn.autocommit = False
            analytics_db = AnalyticsDb(analytics_conn, tenant_id)
        elif not args.skip_analytics:
            LOG.warning(
                "No --analytics-dsn: the analysis cannot report dim_scheme_table drift"
            )

        plan = build_plan(
            rows, sheet_issues, tenant, shape,
            create_orphan_users=args.create_orphan_users,
            replace=args.replace,
            window_days=args.reading_window_days,
            analytics_db=analytics_db,
        )

        context = {
            "generated_at": datetime.now().isoformat(timespec="seconds"),
            "source": source_path,
            "source_supplies": shape.describe(),
            "sheet_rows": len(rows),
            "tenant_schema": args.schema,
            "tenant_id": tenant_id,
            "actor_id": args.actor_id,
            "mode": "EXECUTE" if args.execute else "ANALYZE (read-only)",
            "legacy_handling": "RETIRE (--replace)" if args.replace else "report only",
            "reading_window_days": args.reading_window_days,
            "analytics": "skipped" if args.skip_analytics else "included",
            "phones_in_report": "full" if args.include_pii else "masked",
            "orphan_users": "created" if args.create_orphan_users else "skipped",
            "existing_schemes_in_system": len(plan.scheme_index.live_ids),
            "soft_deleted_schemes_in_system":
                len(plan.scheme_index.snapshots) - len(plan.scheme_index.live_ids),
        }
        write_analysis_workbook(plan, args.out, args.include_pii, context)
        _print_summary(plan)

        if not args.execute:
            LOG.info("Read-only run — nothing was written. Re-run with --execute to apply.")
            tenant_conn.rollback()
            if analytics_conn is not None:
                analytics_conn.rollback()
            return 0

        writer = TenantWriter(tenant, tenant_id, args.actor_id, user_type_ids)
        writer.assert_actor_is_tenant_user()

        if analytics_conn is not None:
            analytics = AnalyticsWriter(analytics_conn, tenant_id)
            analytics.assert_tenant_exists()

        LOG.info("Applying tenant changes …")
        tenant_stats = execute_tenant(plan, writer)
        for key, value in tenant_stats.items():
            LOG.info("  %-38s %d", key, value)

        analytics_stats: dict[str, int] = {}
        if analytics_conn is not None:
            LOG.info("Applying analytics changes …")
            analytics_stats = execute_analytics(plan, analytics, tenant, user_type_ids)
            for key, value in analytics_stats.items():
                LOG.info("  %-38s %d", key, value)

        tenant_conn.commit()
        if analytics_conn is not None:
            analytics_conn.commit()
        LOG.info("Committed.")

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
    actions = Counter(u.action for u in plan.user_plans.values())
    for action, count in sorted(actions.items()):
        LOG.info("%-46s %8d", f"users / {action}", count)
    LOG.info("%-46s %8d", "new user_scheme_mapping rows",
             sum(len(u.new_scheme_ids) for u in plan.user_plans.values()
                 if not u.action.startswith("skip_")))
    LOG.info("─" * 72)

    for rec in plan.reconciliations:
        by_reason = rec.counts_by_reason()
        LOG.info(
            "%-46s +%-6d ~%-6d -%d", rec.kind,
            len(rec.to_insert), len(rec.revivals), len(rec.removals),
        )
        for reason, count in sorted(by_reason.items()):
            LOG.info("%-46s %8d", f"    retired: {reason}", count)
    LOG.info("─" * 72)

    if plan.legacy:
        spared = len(plan.spared_scheme_ids)
        retiring = len(plan.retirement_candidates)
        LOG.warning("%-46s %8d", "LEGACY schemes absent from the snapshot", len(plan.legacy))
        LOG.warning(
            "%-46s %8d", f"  retire (silent >{plan.window_days}d)"
            if plan.replace else "  WOULD retire (needs --replace)", retiring,
        )
        if spared:
            LOG.warning(
                "%-46s %8d", f"  SPARED — reading within {plan.window_days}d", spared,
            )
            LOG.warning(
                "  Those %d scheme(s) are receiving data but are missing from the "
                "snapshot. They are never retired automatically — reconcile them by "
                "hand from the legacy_schemes sheet.", spared,
            )
        if not plan.replace:
            LOG.warning(
                "  Nothing is retired without --replace; this run only reports them."
            )
        LOG.info("─" * 72)


if __name__ == "__main__":
    sys.exit(main())
