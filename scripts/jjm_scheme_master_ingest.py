#!/usr/bin/env python3
"""
JJM master-data ingestion for a Jal Soochak tenant (default: Assam / tenant_as).

Reads the JJM master sheet (headers on row 2) and reconciles it against the
tenant database and the analytics warehouse.

Two modes:

  analyze  (default)  read-only. Writes an Excel analysis workbook describing
                      exactly what an execute run would do, and why.
  execute  (--execute) applies the inserts/updates inside transactions.

What it touches
---------------
tenant DB (shared_db), schema tenant_<code>:
  scheme_master_table              insert / update
  user_table                       insert / update  (PUMP_OPERATOR, SECTION_OFFICER)
  user_scheme_mapping_table        insert (additive; never removes)
  scheme_lgd_mapping_table         insert (village, only when unambiguously resolved)
  scheme_department_mapping_table  insert (sub-division, only when unambiguously resolved)

analytics DB, schema analytics_schema:
  dim_scheme_table                 upsert (one row per scheme x village x sub-division)
  dim_user_table                   upsert
  dim_user_scheme_mapping_table    replace-per-user from the tenant DB's post-state

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
        neither id found anywhere                              -> insert

Location mapping contract
-------------------------
A village / sub-division mapping is written ONLY when the name resolves to
exactly one location. When several locations share the name, the sheet's
hierarchy columns are used to disambiguate (village: panchayat > block >
district; sub-division: division > circle > zone). If that still leaves more
than one candidate, the mapping is left unwritten and reported. Nothing is
guessed.

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
  python3 scripts/jjm_scheme_master_ingest.py \
      --excel "scripts/jjm master data/all_ascheme_exist.xlsx" \
      --actor-id 21357 --out "scripts/jjm master data/jjm_analysis.xlsx"

  # apply
  python3 scripts/jjm_scheme_master_ingest.py \
      --excel "scripts/jjm master data/all_ascheme_exist.xlsx" \
      --actor-id 21357 --out jjm_analysis.xlsx --execute
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
    "fhtc_count": "integer",
    "planned_fhtc": "integer",
    "work_status": "integer",
    "operating_status": "integer",
    "latitude": "double precision",
    "longitude": "double precision",
}

# location_config_master_table: region_type 1 = LGD, 2 = department.
LGD_LEVELS = {"state": 1, "district": 2, "block": 3, "panchayat": 4, "village": 5}
DEPT_LEVELS = {"state": 1, "zone": 2, "circle": 3, "division": 4, "sub_division": 5}
# scheme_lgd_mapping_table.parent_lgd_level / scheme_department_mapping_table.parent_department_level
LGD_MAPPING_LEVEL = "VILLAGE"
DEPT_MAPPING_LEVEL = "Sub-division"

INDIAN_MOBILE_RE = re.compile(r"^[6-9]\d{9}$")
SAFE_SCHEMA_RE = re.compile(r"^[a-z_][a-z0-9_]*$")

SHEET_COLUMNS = [
    "zone", "circle", "district", "division", "sub_divisions", "scheme_name",
    "imis_id", "smt_id", "work_status", "operating_status", "blocks",
    "panchayat_name", "village_name", "so_name", "so_phone",
    "planned_fhtc_imis", "provided_fhtc_imis", "latitude", "longitude",
    "jalmitras", "jalmitra_phone",
]

# Scheme classification outcomes.
CAT_BOTH_MATCH = "BOTH_IDS_MATCH_SAME_SCHEME"
CAT_CENTRE_ONLY_STATE_NEW = "CENTRE_MATCH_STATE_ID_UNKNOWN"
CAT_STATE_ONLY_CENTRE_NEW = "STATE_MATCH_CENTRE_ID_UNKNOWN"
CAT_CONFLICT = "CONFLICT_IDS_POINT_TO_DIFFERENT_SCHEMES"
CAT_NEW = "NEW_SCHEME"
CAT_AMBIGUOUS = "AMBIGUOUS_ID_MATCHES_MULTIPLE_SCHEMES"
CAT_INVALID = "INVALID_SHEET_ROW"

CATEGORY_ORDER = [
    CAT_BOTH_MATCH, CAT_CENTRE_ONLY_STATE_NEW, CAT_STATE_ONLY_CENTRE_NEW,
    CAT_NEW, CAT_CONFLICT, CAT_AMBIGUOUS, CAT_INVALID,
]
CATEGORY_ACTION = {
    CAT_BOTH_MATCH: "update",
    CAT_CENTRE_ONLY_STATE_NEW: "update (adopt smt_id)",
    CAT_STATE_ONLY_CENTRE_NEW: "update (adopt imis_id)",
    CAT_NEW: "insert",
    CAT_CONFLICT: "skip",
    CAT_AMBIGUOUS: "skip",
    CAT_INVALID: "skip",
}
CATEGORY_DESCRIPTION = {
    CAT_BOTH_MATCH: "Both imis_id and smt_id resolve to the same existing scheme",
    CAT_CENTRE_ONLY_STATE_NEW: "imis_id matches one scheme; smt_id is not in our system yet",
    CAT_STATE_ONLY_CENTRE_NEW: "smt_id matches one scheme; imis_id is not in our system yet",
    CAT_NEW: "Neither id exists in our system",
    CAT_CONFLICT: "imis_id and smt_id point at two different existing schemes",
    CAT_AMBIGUOUS: "An id matches several schemes and the pair does not resolve it",
    CAT_INVALID: "Sheet row cannot be used (blank name/ids or unusable work_status)",
}


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
    people: list[PersonRef] = field(default_factory=list)
    issues: list[str] = field(default_factory=list)

    @property
    def blocking_issues(self) -> list[str]:
        """Issues that stop the scheme row itself from being written."""
        return [i for i in self.issues if i.startswith("scheme:")]


def load_sheet(path: str, sheet_name: Optional[str], header_row: int) -> tuple[list[SheetRow], list[dict]]:
    """Read the workbook and normalise every row. Returns (rows, per-row issue records)."""
    frame = pd.read_excel(
        path,
        sheet_name=sheet_name if sheet_name else 0,
        header=header_row - 1,
        dtype=object,
    )
    frame.columns = [str(c).strip().lower().replace(" ", "_") for c in frame.columns]

    missing = [c for c in SHEET_COLUMNS if c not in frame.columns]
    if missing:
        raise SystemExit(
            f"Sheet is missing expected column(s): {', '.join(missing)}\n"
            f"Found: {', '.join(frame.columns)}"
        )

    rows: list[SheetRow] = []
    issue_records: list[dict] = []

    for offset, raw in enumerate(frame.to_dict("records")):
        # +1 for the header row itself, +1 to make it 1-based like Excel's gutter.
        row_no = header_row + 1 + offset
        if all(_is_blank(raw.get(c)) for c in SHEET_COLUMNS):
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

        people, people_issues = _extract_people(raw)
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

    return rows, issue_records


def _extract_people(raw: dict) -> tuple[list[PersonRef], list[str]]:
    """Pair the comma-separated name/phone cells positionally, per role.

    A name/phone count mismatch is never guessed at — the whole cell is dropped
    and reported, because mispairing would attach a real person's name to
    someone else's phone number.
    """
    people: list[PersonRef] = []
    issues: list[str] = []

    for name_col, phone_col, role in (
        ("jalmitras", "jalmitra_phone", ROLE_PUMP_OPERATOR),
        ("so_name", "so_phone", ROLE_SECTION_OFFICER),
    ):
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


@dataclass
class SchemeIndex:
    by_centre: dict[str, list[int]]
    by_state: dict[str, list[int]]
    snapshots: dict[int, SchemeSnapshot]


@dataclass
class LocationNode:
    id: int
    title: str
    level: int
    parent_id: Optional[int]


class TenantDb:
    """Reads and writes tenant_<code>.* . All identifiers are validated first."""

    def __init__(self, conn, schema: str, pii: PiiCrypto) -> None:
        self.conn = conn
        self.schema = check_schema(schema)
        self.pii = pii

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

    def load_scheme_index(self) -> SchemeIndex:
        """Every live scheme, indexed by canonical centre and state id."""
        by_centre: dict[str, list[int]] = defaultdict(list)
        by_state: dict[str, list[int]] = defaultdict(list)
        snapshots: dict[int, SchemeSnapshot] = {}

        with self.conn.cursor(name="scheme_scan") as cur:
            cur.itersize = 5000
            cur.execute(f"""
                SELECT id, state_scheme_id, centre_scheme_id, scheme_name,
                       planned_fhtc, fhtc_count, house_hold_count,
                       latitude, longitude, work_status, operating_status
                FROM {self.schema}.scheme_master_table
                WHERE deleted_at IS NULL
            """)
            for rec in cur:
                snap = SchemeSnapshot(*rec)
                snapshots[snap.id] = snap
                centre_key = scheme_id_key(snap.centre_scheme_id)
                state_key = scheme_id_key(snap.state_scheme_id)
                if centre_key:
                    by_centre[centre_key].append(snap.id)
                if state_key:
                    by_state[state_key].append(snap.id)

        return SchemeIndex(dict(by_centre), dict(by_state), snapshots)

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

    def load_existing_scheme_lgd_mappings(self) -> set[tuple[int, int]]:
        with self.conn.cursor() as cur:
            cur.execute(f"""
                SELECT scheme_id, parent_lgd_id
                FROM {self.schema}.scheme_lgd_mapping_table
                WHERE deleted_at IS NULL
            """)
            return {(s, p) for s, p in cur}

    def load_existing_scheme_dept_mappings(self) -> set[tuple[int, int]]:
        with self.conn.cursor() as cur:
            cur.execute(f"""
                SELECT scheme_id, parent_department_id
                FROM {self.schema}.scheme_department_mapping_table
                WHERE deleted_at IS NULL
            """)
            return {(s, p) for s, p in cur}

    def load_users_by_phone_hash(self, phone_hashes: Iterable[str]) -> dict[str, dict]:
        """Look up users by HMAC of the normalised phone (the encrypted column
        cannot be searched). Returns hash -> user record incl. decrypted title."""
        hashes = [h for h in dict.fromkeys(phone_hashes) if h]
        if not hashes:
            return {}

        found: dict[str, dict] = {}
        with self.conn.cursor() as cur:
            for start in range(0, len(hashes), 5000):
                batch = hashes[start:start + 5000]
                cur.execute(f"""
                    SELECT u.id, u.uuid, u.phone_number_hash, u.title, u.user_type,
                           u.status, u.email, ut.c_name
                    FROM {self.schema}.user_table u
                    LEFT JOIN common_schema.user_type_master_table ut ON ut.id = u.user_type
                    WHERE u.deleted_at IS NULL
                      AND u.phone_number_hash = ANY(%s)
                """, (batch,))
                for uid, uuid, phash, title_enc, user_type, status, email, c_name in cur:
                    # Several rows can share a hash only if data is already corrupt;
                    # keep the lowest id so behaviour stays deterministic.
                    if phash in found and found[phash]["id"] <= uid:
                        continue
                    found[phash] = {
                        "id": uid,
                        "uuid": uuid,
                        "title": self.pii.safe_decrypt(title_enc),
                        "user_type": user_type,
                        "role": (c_name or "").upper(),
                        "status": status,
                        "email": email,
                    }
        return found

    def load_user_scheme_mappings(self, user_ids: Iterable[int]) -> dict[int, set[int]]:
        ids = list(dict.fromkeys(user_ids))
        if not ids:
            return {}
        result: dict[int, set[int]] = defaultdict(set)
        with self.conn.cursor() as cur:
            for start in range(0, len(ids), 5000):
                cur.execute(f"""
                    SELECT user_id, scheme_id
                    FROM {self.schema}.user_scheme_mapping_table
                    WHERE deleted_at IS NULL AND user_id = ANY(%s)
                """, (ids[start:start + 5000],))
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

    @property
    def will_write(self) -> bool:
        return self.category in {
            CAT_BOTH_MATCH, CAT_CENTRE_ONLY_STATE_NEW, CAT_STATE_ONLY_CENTRE_NEW, CAT_NEW
        }


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

    centre_hits = index.by_centre.get(row.centre_key, []) if row.centre_key else []
    state_hits = index.by_state.get(row.state_key, []) if row.state_key else []

    # Rule 1 — a unique pair match wins outright, even if one id alone is
    # non-unique in our system.
    pair_hits = sorted(set(centre_hits) & set(state_hits))
    if len(pair_hits) == 1:
        return SchemeDecision(
            row, CAT_BOTH_MATCH, scheme_id=pair_hits[0],
            reason="imis_id and smt_id both resolve to scheme id %d" % pair_hits[0],
        )
    if len(pair_hits) > 1:
        return SchemeDecision(
            row, CAT_AMBIGUOUS,
            reason=f"imis_id + smt_id together match {len(pair_hits)} schemes {pair_hits}",
        )

    # Rule 2 — fall back to single-id matching; multiplicity is unresolvable here.
    if len(centre_hits) > 1:
        return SchemeDecision(
            row, CAT_AMBIGUOUS,
            reason=f"imis_id matches {len(centre_hits)} schemes {sorted(centre_hits)} "
                   f"and smt_id does not narrow it down",
        )
    if len(state_hits) > 1:
        return SchemeDecision(
            row, CAT_AMBIGUOUS,
            reason=f"smt_id matches {len(state_hits)} schemes {sorted(state_hits)} "
                   f"and imis_id does not narrow it down",
        )

    centre_id = centre_hits[0] if centre_hits else None
    state_id = state_hits[0] if state_hits else None

    if centre_id is not None and state_id is not None:
        return SchemeDecision(
            row, CAT_CONFLICT, conflict_centre_scheme_id=centre_id,
            conflict_state_scheme_id=state_id,
            reason=f"imis_id -> scheme id {centre_id} but smt_id -> scheme id {state_id}",
        )

    if centre_id is not None:
        if not row.state_id:
            return SchemeDecision(
                row, CAT_BOTH_MATCH, scheme_id=centre_id,
                reason=f"imis_id -> scheme id {centre_id}; sheet has no smt_id to adopt",
            )
        return SchemeDecision(
            row, CAT_CENTRE_ONLY_STATE_NEW, scheme_id=centre_id,
            reason=f"imis_id -> scheme id {centre_id}; smt_id {row.state_id} is unused in our system",
        )

    if state_id is not None:
        if not row.centre_id:
            return SchemeDecision(
                row, CAT_BOTH_MATCH, scheme_id=state_id,
                reason=f"smt_id -> scheme id {state_id}; sheet has no imis_id to adopt",
            )
        return SchemeDecision(
            row, CAT_STATE_ONLY_CENTRE_NEW, scheme_id=state_id,
            reason=f"smt_id -> scheme id {state_id}; imis_id {row.centre_id} is unused in our system",
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

    if decision.category == CAT_CENTRE_ONLY_STATE_NEW and row.state_id:
        changes["state_scheme_id"] = (snap.state_scheme_id, row.state_id)
    if decision.category == CAT_STATE_ONLY_CENTRE_NEW and row.centre_id:
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
    # insert | update | unchanged | skip_role_conflict | skip_no_scheme
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
        plan.action = "update" if plan.name_changed else "unchanged"
        plan.reason = "name differs from our record" if plan.name_changed else "already up to date"

    # Which scheme mappings are missing today?
    known_ids = [p.existing_id for p in plans.values() if p.existing_id]
    current = tenant.load_user_scheme_mappings(known_ids)
    for plan in plans.values():
        if plan.action.startswith("skip_"):
            continue
        have = current.get(plan.existing_id, set()) if plan.existing_id else set()
        plan.new_scheme_ids = {s for s in plan.scheme_ids if s not in have}

    return plans, conflicts


# ─────────────────────────────────────────────────────────────────────────────
# Tenant database writes
# ─────────────────────────────────────────────────────────────────────────────

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

    def insert_schemes(self, decisions: list[SchemeDecision]) -> dict[int, int]:
        """Insert new schemes. Returns sheet row_no -> new scheme id."""
        if not decisions:
            return {}
        payload = [
            (
                d.row.state_id, d.row.centre_id, d.row.scheme_name,
                d.row.achieved_fhtc or 0, d.row.planned_fhtc or 0,
                d.row.latitude, d.row.longitude,
                d.row.work_status,
                d.row.operating_status if d.row.operating_status is not None
                else DEFAULT_OPERATING_STATUS,
                self.actor_id, self.actor_id,
            )
            for d in decisions
        ]
        sql = f"""
            INSERT INTO {self.schema}.scheme_master_table
                (state_scheme_id, centre_scheme_id, scheme_name,
                 fhtc_count, planned_fhtc, latitude, longitude,
                 work_status, operating_status,
                 created_by, created_at, updated_by, updated_at)
            VALUES %s
            RETURNING id
        """
        with self.conn.cursor() as cur:
            ids = psycopg2.extras.execute_values(
                cur, sql, payload,
                template="(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,NOW(),%s,NOW())",
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

    def insert_user_scheme_mappings(self, pairs: list[tuple[int, int]]) -> int:
        """Additive only — existing mappings are left in place."""
        if not pairs:
            return 0
        sql = f"""
            INSERT INTO {self.schema}.user_scheme_mapping_table
                (user_id, scheme_id, status, created_by, created_at, updated_by, updated_at)
            VALUES %s
        """
        with self.conn.cursor() as cur:
            psycopg2.extras.execute_values(
                cur, sql,
                [(u, s, 1, self.actor_id, self.actor_id) for u, s in pairs],
                template="(%s,%s,%s,%s,NOW(),%s,NOW())",
                page_size=1000,
            )
            return cur.rowcount

    def insert_lgd_mappings(self, pairs: list[tuple[int, int]]) -> int:
        if not pairs:
            return 0
        sql = f"""
            INSERT INTO {self.schema}.scheme_lgd_mapping_table
                (scheme_id, parent_lgd_id, parent_lgd_level,
                 created_by, created_at, updated_by, updated_at)
            VALUES %s
        """
        with self.conn.cursor() as cur:
            psycopg2.extras.execute_values(
                cur, sql,
                [(s, l, LGD_MAPPING_LEVEL, self.actor_id, self.actor_id) for s, l in pairs],
                template="(%s,%s,%s,%s,NOW(),%s,NOW())",
                page_size=1000,
            )
            return cur.rowcount

    def insert_dept_mappings(self, pairs: list[tuple[int, int]]) -> int:
        if not pairs:
            return 0
        sql = f"""
            INSERT INTO {self.schema}.scheme_department_mapping_table
                (scheme_id, parent_department_id, parent_department_level,
                 created_by, created_at, updated_by, updated_at)
            VALUES %s
        """
        with self.conn.cursor() as cur:
            psycopg2.extras.execute_values(
                cur, sql,
                [(s, d, DEPT_MAPPING_LEVEL, self.actor_id, self.actor_id) for s, d in pairs],
                template="(%s,%s,%s,%s,NOW(),%s,NOW())",
                page_size=1000,
            )
            return cur.rowcount


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
    existing_lgd_mappings: set[tuple[int, int]]
    existing_dept_mappings: set[tuple[int, int]]
    lgd: "LocationIndex"
    dept: "LocationIndex"

    def by_category(self) -> dict[str, list[SchemeDecision]]:
        grouped: dict[str, list[SchemeDecision]] = defaultdict(list)
        for decision in self.decisions:
            grouped[decision.category].append(decision)
        return grouped


def build_plan(
    rows: list[SheetRow],
    sheet_issues: list[dict],
    tenant: TenantDb,
    create_orphan_users: bool = False,
) -> IngestPlan:
    LOG.info("Loading existing schemes from %s …", tenant.schema)
    scheme_index = tenant.load_scheme_index()
    LOG.info("  %d live schemes", len(scheme_index.snapshots))

    dup_centre, dup_state = find_sheet_duplicates(rows)
    if dup_centre or dup_state:
        LOG.warning(
            "Sheet has %d repeated imis_id and %d repeated smt_id value(s) — those rows are skipped",
            len(dup_centre), len(dup_state),
        )

    LOG.info("Classifying %d sheet rows …", len(rows))
    decisions = [classify_scheme(row, scheme_index, dup_centre, dup_state) for row in rows]
    for decision in decisions:
        compute_scheme_changes(decision, scheme_index)

    LOG.info("Loading location hierarchies …")
    lgd = tenant.load_locations(region_type=1)
    dept = tenant.load_locations(region_type=2)
    LOG.info("  %d LGD nodes, %d department nodes", len(lgd), len(dept))

    LOG.info("Resolving villages and sub-divisions …")
    locations: dict[int, RowLocations] = {}
    for decision in decisions:
        if not decision.will_write:
            continue
        villages, sub_division = resolve_row_locations(decision.row, lgd, dept)
        locations[decision.row.row_no] = RowLocations(villages, sub_division)

    LOG.info("Resolving users …")
    user_plans, user_conflicts = build_user_plans(decisions, tenant, create_orphan_users)
    LOG.info("  %d distinct people referenced", len(user_plans))

    return IngestPlan(
        decisions=decisions,
        locations=locations,
        user_plans=user_plans,
        user_conflicts=user_conflicts,
        sheet_issues=sheet_issues,
        dup_centre=dup_centre,
        dup_state=dup_state,
        scheme_index=scheme_index,
        existing_lgd_mappings=tenant.load_existing_scheme_lgd_mappings(),
        existing_dept_mappings=tenant.load_existing_scheme_dept_mappings(),
        lgd=lgd,
        dept=dept,
    )


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
    return pd.DataFrame.from_records(records)


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
    new_mappings = sum(len(u.new_scheme_ids) for u in plan.user_plans.values()
                       if not u.action.startswith("skip_"))
    summary = pd.concat([summary, pd.DataFrame.from_records([
        {"role / action": "TOTAL distinct people", "people": len(plan.user_plans)},
        {"role / action": "new user_scheme_mapping rows", "people": new_mappings},
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


def build_analytics_frame(plan: IngestPlan) -> pd.DataFrame:
    """dim_scheme_table.parent_lgd_location_id is NOT NULL, so a scheme with no
    resolvable village cannot be represented in the warehouse at all."""
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

    return pd.DataFrame.from_records([
        {"metric": "schemes written to tenant DB", "value": len(writable)},
        {"metric": "…with >=1 resolved village (eligible for dim_scheme_table)", "value": with_village},
        {"metric": "…with no resolved village (SKIPPED in analytics: parent_lgd_location_id is NOT NULL)",
         "value": without_village},
        {"metric": "dim_scheme_table rows (one per scheme x village)", "value": dim_rows},
        {"metric": "dim_user_table rows upserted",
         "value": len([u for u in plan.user_plans.values() if not u.action.startswith("skip_")])},
    ])


def write_analysis_workbook(plan: IngestPlan, path: str, include_pii: bool, context: dict) -> None:
    summary = build_summary_frame(plan)
    scheme_detail = build_scheme_detail_frame(plan)
    conflicts = build_conflict_frame(plan)
    loc_summary, loc_detail = build_location_frames(plan)
    user_summary, user_detail = build_user_frames(plan, include_pii)
    analytics = build_analytics_frame(plan)

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
        analytics.to_excel(writer, sheet_name="analytics_summary", index=False)
        user_summary.to_excel(writer, sheet_name="user_summary", index=False)
        loc_summary.to_excel(writer, sheet_name="location_summary", index=False)
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
    """Apply the whole tenant-side plan in one transaction."""
    grouped = plan.by_category()
    stats: dict[str, int] = {}

    inserts = grouped.get(CAT_NEW, [])
    new_ids = writer.insert_schemes(inserts)
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

    to_insert = [u for u in plan.user_plans.values() if u.action == "insert"]
    writer.insert_users(to_insert)
    stats["users_inserted"] = len(to_insert)

    to_update = [u for u in plan.user_plans.values() if u.action == "update" and u.existing_id]
    stats["users_updated"] = writer.update_user_names(to_update)

    mapping_pairs = sorted({
        (u.existing_id, scheme_id)
        for u in plan.user_plans.values()
        if not u.action.startswith("skip_") and u.existing_id
        for scheme_id in u.new_scheme_ids
    })
    stats["user_scheme_mappings_inserted"] = writer.insert_user_scheme_mappings(mapping_pairs)

    lgd_pairs: set[tuple[int, int]] = set()
    dept_pairs: set[tuple[int, int]] = set()
    for decision in plan.decisions:
        if not decision.will_write or not decision.scheme_id:
            continue
        loc = plan.locations.get(decision.row.row_no)
        if loc is None:
            continue
        for village_id in loc.village_ids:
            pair = (decision.scheme_id, village_id)
            if pair not in plan.existing_lgd_mappings:
                lgd_pairs.add(pair)
        if loc.dept_id:
            pair = (decision.scheme_id, loc.dept_id)
            if pair not in plan.existing_dept_mappings:
                dept_pairs.add(pair)

    stats["scheme_lgd_mappings_inserted"] = writer.insert_lgd_mappings(sorted(lgd_pairs))
    stats["scheme_department_mappings_inserted"] = writer.insert_dept_mappings(sorted(dept_pairs))
    return stats


def execute_analytics(
    plan: IngestPlan,
    analytics: AnalyticsWriter,
    tenant: TenantDb,
    user_type_ids: dict[str, int],
) -> dict[str, int]:
    """Project the post-state into the warehouse.

    dim_scheme_table needs one row per (scheme, village, sub-division) and its
    parent_lgd_location_id is NOT NULL, so schemes whose village never resolved
    are reported rather than written.
    """
    stats: dict[str, int] = {}
    dim_rows: list[DimSchemeRow] = []
    skipped_no_village = 0

    for decision in plan.decisions:
        if not decision.will_write or not decision.scheme_id:
            continue
        loc = plan.locations.get(decision.row.row_no)
        village_ids = loc.village_ids if loc else []
        if not village_ids:
            skipped_no_village += 1
            continue

        row = decision.row
        snap = plan.scheme_index.snapshots.get(decision.scheme_id)
        dept_id = loc.dept_id if loc else None
        dept_levels = hierarchy_levels(dept_id, plan.dept.nodes) if dept_id else [None] * 5

        # Fall back to what we already hold when the sheet is silent.
        latitude = row.latitude if valid_latlon(row.latitude, row.longitude) else (
            snap.latitude if snap else None
        )
        longitude = row.longitude if valid_latlon(row.latitude, row.longitude) else (
            snap.longitude if snap else None
        )

        for village_id in village_ids:
            dim_rows.append(DimSchemeRow(
                scheme_id=decision.scheme_id,
                scheme_name=row.scheme_name,
                state_scheme_id=as_int_or_zero(row.state_id or (snap.state_scheme_id if snap else "")),
                centre_scheme_id=as_int_or_zero(row.centre_id or (snap.centre_scheme_id if snap else "")),
                latitude=latitude,
                longitude=longitude,
                parent_lgd_location_id=village_id,
                lgd_levels=hierarchy_levels(village_id, plan.lgd.nodes),
                parent_department_location_id=dept_id,
                dept_levels=dept_levels,
                # Mirror the tenant post-state: keep the existing value when the
                # sheet is silent (matched scheme), default only for a new insert.
                operating_status=row.operating_status if row.operating_status is not None
                else (snap.operating_status if snap and snap.operating_status is not None
                      else DEFAULT_OPERATING_STATUS),
                work_status=row.work_status,
                fhtc_count=row.achieved_fhtc if row.achieved_fhtc is not None else (
                    snap.fhtc_count if snap else 0
                ),
                planned_fhtc=row.planned_fhtc if row.planned_fhtc is not None else (
                    snap.planned_fhtc if snap else 0
                ),
                house_hold_count=(snap.house_hold_count if snap and snap.house_hold_count else 0),
            ))

    stats["dim_scheme_rows_upserted"] = analytics.upsert_schemes(dim_rows)
    stats["dim_scheme_skipped_no_village"] = skipped_no_village

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
    parser.add_argument("--excel", required=True, help="path to the master data workbook")
    parser.add_argument("--sheet", default=None, help="worksheet name (default: first sheet)")
    parser.add_argument("--header-row", type=int, default=2,
                        help="1-based row holding the column headers (default: 2)")
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

    try:
        pii = PiiCrypto(os.environ.get("PII_ENCRYPTION_KEY", ""), os.environ.get("PII_HMAC_KEY", ""))
    except ValueError as exc:
        return _fail(str(exc))

    LOG.info("Reading %s …", args.excel)
    rows, sheet_issues = load_sheet(args.excel, args.sheet, args.header_row)
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

        plan = build_plan(rows, sheet_issues, tenant, args.create_orphan_users)

        context = {
            "generated_at": datetime.now().isoformat(timespec="seconds"),
            "excel": args.excel,
            "sheet_rows": len(rows),
            "tenant_schema": args.schema,
            "tenant_id": tenant_id,
            "actor_id": args.actor_id,
            "mode": "EXECUTE" if args.execute else "ANALYZE (read-only)",
            "analytics": "skipped" if args.skip_analytics else "included",
            "phones_in_report": "full" if args.include_pii else "masked",
            "orphan_users": "created" if args.create_orphan_users else "skipped",
            "existing_schemes_in_system": len(plan.scheme_index.snapshots),
        }
        write_analysis_workbook(plan, args.out, args.include_pii, context)
        _print_summary(plan)

        if not args.execute:
            LOG.info("Read-only run — nothing was written. Re-run with --execute to apply.")
            tenant_conn.rollback()
            return 0

        writer = TenantWriter(tenant, tenant_id, args.actor_id, user_type_ids)
        writer.assert_actor_is_tenant_user()

        if not args.skip_analytics:
            analytics_conn = psycopg2.connect(args.analytics_dsn)
            analytics_conn.autocommit = False
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


if __name__ == "__main__":
    sys.exit(main())
