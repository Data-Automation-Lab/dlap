#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
"""Turn DLAP zero-access fitness scan exports into a per-table report.

The customer runs the scan SQL (bigquery/last_read.sql, databricks/last_read.sql) in their own
console, saves each result as CSV, and runs this script on their own machine:

    python -I aggregate.py SCAN.csv [SCAN.csv ...] --schedule schedule.csv
        --classes classes.csv --out-dir report/

It reads only those files and writes three:

    tables.csv     one row per inventoried table: class, age, last read, readers, verdicts
    summary.json   counts and bytes by verdict and class, scan windows, coverage notes
    summary.md     the same summary as text for an evidence memo

Verdicts never use the wall clock: "now" is the end of the scan window that inventoried the
table. Unknown is never stale or fresh. The standard library is enough; Python 3.9 or later.
``--selftest`` runs the built-in checks (synthetic data only) and exits.
"""

from __future__ import annotations

import argparse
import contextlib
import csv
import datetime as dt
import fnmatch
import hashlib
import hmac
import io
import json
import re
import secrets
import sys
import tempfile
import unittest
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence, Tuple

TOOL = "dlap-scan-aggregate"
CONTRACT_VERSION = "1"

# The scan contract: every scan CSV carries these columns (README, "Scan contract").
REQUIRED_COLUMNS: Tuple[str, ...] = (
    "platform", "scan_scope", "window_start", "window_end", "earliest_event_at",
    "table_catalog", "table_schema", "table_name", "table_type", "in_inventory",
    "created_at", "last_modified_at", "last_modified_source", "size_bytes",
    "read_count", "system_read_count", "last_read_at", "last_system_read_at",
    "readers_human", "readers_service", "readers_system", "readers_unknown", "read_sources",
)
OPTIONAL_COLUMNS: Tuple[str, ...] = (
    "last_altered_at", "row_count", "expiration_at", "partition_expiration_days", "labels",
    "credential_read_count",
)
# Every column the scan SQL emits, in order.
CONTRACT_COLUMNS: Tuple[str, ...] = (
    "platform", "scan_scope", "window_start", "window_end", "earliest_event_at",
    "table_catalog", "table_schema", "table_name", "table_type", "in_inventory",
    "created_at", "last_modified_at", "last_modified_source", "last_altered_at",
    "size_bytes", "row_count", "expiration_at", "partition_expiration_days", "labels",
    "read_count", "credential_read_count", "system_read_count", "last_read_at",
    "last_system_read_at", "readers_human", "readers_service", "readers_system",
    "readers_unknown", "read_sources",
)

# Columns that would carry query text or principal identities. A scan file holding any of them is
# refused: query text and principal names never enter the report.
FORBIDDEN_COLUMNS = frozenset({
    "query", "query_text", "statement_text", "command_text", "commandtext", "sql", "sql_text",
    "view_definition", "user_email", "principal_subject", "principal", "principal_key",
    "created_by", "executed_by", "user_identity", "email",
})

READER_TYPES = ("human", "service", "system", "unknown")

USAGE_VERDICTS = ("ACTIVE", "STALE", "NEW", "UNKNOWN_WINDOW", "UNKNOWN_CREATED")
RETENTION_VERDICTS = (
    "OVER_RETENTION", "WITHIN", "MANAGED_BY_EXPIRY", "NOT_APPLICABLE", "EXTERNAL_STORAGE",
    "UNCLASSIFIED", "NO_RULE", "UNKNOWN_CREATED", "UNKNOWN_TYPE",
)

DAY = dt.timedelta(days=1)


class ScanError(Exception):
    """An input file breaks the scan contract; the message says which file and why."""


@dataclass(frozen=True)
class Platform:
    """What the scan can see on one platform and how its table types map to verdicts."""

    name: str
    data_types: frozenset
    logical_types: frozenset
    external_types: frozenset
    read_label: str
    gaps: Tuple[str, ...]


PLATFORMS: Dict[str, Platform] = {
    "bigquery": Platform(
        name="bigquery",
        data_types=frozenset({"BASE TABLE", "CLONE", "SNAPSHOT", "MATERIALIZED VIEW"}),
        logical_types=frozenset({"VIEW"}),
        external_types=frozenset({"EXTERNAL"}),
        read_label="query-job reads (INFORMATION_SCHEMA.JOBS_BY_PROJECT)",
        gaps=(
            "Reads that are not query jobs are not seen: the BigQuery Storage Read API (Spark, "
            "Dataflow, BI connectors), tabledata.list (console preview, bq head), and EXTRACT "
            "and COPY jobs (JOBS does not name their source table).",
            "Only jobs run in the scanned project are seen. A job run in another project that "
            "reads this project's table is seen only if that project is scanned too.",
            "History is 180 days at most, and less if the project moved into an organization "
            "within that time.",
            "A STALE table had no query-job read in the stale period; it is not proof of no "
            "access. Data Access audit logs (tableDataRead) cover the missing read paths.",
        ),
    ),
    "databricks": Platform(
        name="databricks",
        data_types=frozenset({"MANAGED", "MANAGED_SHALLOW_CLONE", "STREAMING_TABLE",
                              "MATERIALIZED_VIEW"}),
        logical_types=frozenset({"VIEW", "METRIC_VIEW"}),
        external_types=frozenset({"EXTERNAL", "EXTERNAL_SHALLOW_CLONE", "FOREIGN"}),
        read_label="lineage reads and READ credential grants (system.access)",
        gaps=(
            "Lineage is recorded only when it can be inferred, so it is a subset of reads. "
            "RDDs, global temp views, tables used inside UDF bodies and path-only reads that "
            "do not name a table are not attributed to a table.",
            "Lineage is kept for statements that later failed or rolled back, so a few reads "
            "may be over-counted.",
            "Reads through Delta Sharing (deltaSharingQueryTable) are not attributed to the "
            "shared table, and getTable metadata fetches are not reads.",
            "History is 365 days by default (system-table retention is configurable).",
            "Size and row counts are not in information_schema.tables, so size is unknown, and "
            "last write comes from lineage only.",
        ),
    ),
}


# ---------------------------------------------------------------------------------------------
# Parsing
# ---------------------------------------------------------------------------------------------

_TS_RE = re.compile(
    r"^(\d{4})-(\d{2})-(\d{2})"
    r"(?:[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d+))?)?)?"
    r"\s*(Z|UTC|[+-]\d{2}:?\d{2})?$",
    re.IGNORECASE,
)


def parse_ts(text: Optional[str]) -> Optional[dt.datetime]:
    """Parse a scan timestamp into an aware UTC datetime.

    Accepts ``2026-10-18T12:00:00Z`` (the contract), the BigQuery console form
    ``2026-10-18 12:00:00.123456 UTC``, ISO offsets, and a bare date or time without a zone,
    which is read as UTC. Empty and NULL return None.
    """
    if text is None:
        return None
    s = text.strip()
    if not s or s.upper() in ("NULL", "NONE", "NAN", "NAT"):
        return None
    m = _TS_RE.match(s)
    if not m:
        raise ValueError(f"not a timestamp: {text!r}")
    year, month, day, hour, minute, second, frac, zone = m.groups()
    micro = int((frac or "0")[:6].ljust(6, "0"))
    value = dt.datetime(int(year), int(month), int(day), int(hour or 0), int(minute or 0),
                        int(second or 0), micro, tzinfo=dt.timezone.utc)
    if zone and zone.upper() not in ("Z", "UTC"):
        sign = 1 if zone[0] == "+" else -1
        digits = zone[1:].replace(":", "")
        offset = dt.timedelta(hours=int(digits[:2]), minutes=int(digits[2:]))
        value = value - sign * offset
    return value


def format_ts(value: Optional[dt.datetime]) -> str:
    """Render a UTC timestamp in the contract form, or an empty string."""
    if value is None:
        return ""
    return value.astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_int(text: Optional[str]) -> Optional[int]:
    """Parse a count or byte size; empty and NULL return None."""
    if text is None or not text.strip() or text.strip().upper() == "NULL":
        return None
    return int(float(text.strip()))


def parse_float(text: Optional[str]) -> Optional[float]:
    """Parse a number of days; empty and NULL return None."""
    if text is None or not text.strip() or text.strip().upper() == "NULL":
        return None
    return float(text.strip())


def parse_bool(text: Optional[str]) -> bool:
    """Parse a CSV boolean as BigQuery and Databricks print it."""
    value = (text or "").strip().lower()
    if value in ("true", "t", "1", "yes", "y"):
        return True
    if value in ("false", "f", "0", "no", "n", ""):
        return False
    raise ValueError(f"not a boolean: {text!r}")


_BQ_LABEL_RE = re.compile(r'STRUCT\(\s*"((?:[^"\\]|\\.)*)"\s*,\s*"((?:[^"\\]|\\.)*)"\s*\)')


def parse_labels(raw: Optional[str]) -> Dict[str, str]:
    """Parse labels or tags: BigQuery ``[STRUCT("k", "v"), ...]`` or ``k=v;k2=v2``."""
    if raw is None or not raw.strip():
        return {}
    pairs = _BQ_LABEL_RE.findall(raw)
    if pairs:
        return {k: v for k, v in pairs}
    labels: Dict[str, str] = {}
    for part in raw.split(";"):
        part = part.strip()
        if not part:
            continue
        key, _, value = part.partition("=")
        labels[key.strip()] = value.strip()
    return labels


def _days(delta: dt.timedelta) -> float:
    return round(delta.total_seconds() / 86400.0, 1)


# ---------------------------------------------------------------------------------------------
# Model
# ---------------------------------------------------------------------------------------------

@dataclass
class Scan:
    """One scan result file: one platform, one scope, one window."""

    source: str
    platform: Platform
    scope: str
    window_start: dt.datetime
    window_end: dt.datetime
    earliest_event_at: Optional[dt.datetime]
    read_sources: str
    rows: List["ScanRow"] = field(default_factory=list)

    def key(self) -> Tuple[str, str, str]:
        """Identity of the scan run: platform, scope and window end."""
        return (self.platform.name, self.scope, format_ts(self.window_end))

    def effective_start(self, mode: str) -> dt.datetime:
        """Start of the history the verdicts may rely on.

        ``observed`` (the default) is the later of the window start and the earliest event the
        history actually holds, so a short or empty history never yields STALE. ``nominal``
        trusts the window start.
        """
        if mode == "nominal":
            return self.window_start
        if self.earliest_event_at is None:
            return self.window_end
        return max(self.window_start, self.earliest_event_at)


@dataclass
class ScanRow:
    """One object in one scan."""

    catalog: str
    schema: str
    name: str
    table_type: Optional[str]
    in_inventory: bool
    created_at: Optional[dt.datetime]
    last_modified_at: Optional[dt.datetime]
    last_modified_source: Optional[str]
    last_altered_at: Optional[dt.datetime]
    size_bytes: Optional[int]
    row_count: Optional[int]
    expiration_at: Optional[dt.datetime]
    partition_expiration_days: Optional[float]
    labels: Dict[str, str]
    read_count: int
    credential_read_count: int
    system_read_count: int
    last_read_at: Optional[dt.datetime]
    last_system_read_at: Optional[dt.datetime]
    readers: Dict[str, int]

    def has_reads(self) -> bool:
        """Whether this row reports any read at all."""
        return (self.read_count + self.credential_read_count + self.system_read_count) > 0


def asset_uri(platform: str, scope: str, catalog: str, schema: str, name: str) -> str:
    """Canonical asset URI (ADR-0047 forms): bq://p/d/t keeps case; uc://m/c/s/t is lower case."""
    if platform == "bigquery":
        return f"bq://{catalog}/{schema}/{name}"
    metastore = (scope or "_").lower()
    return f"uc://{metastore}/{catalog.lower()}/{schema.lower()}/{name.lower()}"


@dataclass
class Table:
    """One asset after merging every scan that mentions it."""

    uri: str
    platform: Platform
    full_name: str
    inventory: Optional[ScanRow] = None
    inventory_scan: Optional[Scan] = None
    read_count: int = 0
    credential_read_count: int = 0
    system_read_count: int = 0
    last_read_at: Optional[dt.datetime] = None
    last_system_read_at: Optional[dt.datetime] = None
    readers: Dict[str, int] = field(default_factory=lambda: {t: 0 for t in READER_TYPES})
    reading_scans: int = 0

    def add(self, row: ScanRow, scan: Scan) -> None:
        """Fold one scan row into the asset."""
        if row.in_inventory:
            if self.inventory is not None:
                raise ScanError(
                    f"{self.uri} is in the inventory of two scans ({self.inventory_scan.source} "
                    f"and {scan.source}); pass each project or metastore and region once")
            self.inventory = row
            self.inventory_scan = scan
        if not row.has_reads():
            return
        self.reading_scans += 1
        self.read_count += row.read_count
        self.credential_read_count += row.credential_read_count
        self.system_read_count += row.system_read_count
        self.last_read_at = _max_ts(self.last_read_at, row.last_read_at)
        self.last_system_read_at = _max_ts(self.last_system_read_at, row.last_system_read_at)
        for kind in READER_TYPES:
            self.readers[kind] = max(self.readers[kind], row.readers.get(kind, 0))

    @property
    def readers_lower_bound(self) -> bool:
        """Distinct readers from several scans cannot be added up, so the count is a minimum."""
        return self.reading_scans > 1


def _max_ts(a: Optional[dt.datetime], b: Optional[dt.datetime]) -> Optional[dt.datetime]:
    if a is None:
        return b
    if b is None:
        return a
    return max(a, b)


# ---------------------------------------------------------------------------------------------
# Loading
# ---------------------------------------------------------------------------------------------

def _read_csv(path: Path) -> Tuple[List[str], List[Dict[str, str]]]:
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames is None:
            return [], []
        header = [h.strip().lower() for h in reader.fieldnames]
        rows = []
        for raw in reader:
            rows.append({h.strip().lower(): (v if v is not None else "")
                         for h, v in raw.items() if h is not None})
        return header, rows


def load_scan(path: Path) -> Optional[Scan]:
    """Load one scan CSV and check it against the contract. Returns None for an empty result."""
    header, rows = _read_csv(path)
    forbidden = sorted(set(header) & FORBIDDEN_COLUMNS)
    if forbidden:
        raise ScanError(f"{path}: refused, it holds query text or principal columns {forbidden}; "
                        "use the scan SQL as shipped")
    missing = [c for c in REQUIRED_COLUMNS if c not in header]
    if missing:
        raise ScanError(f"{path}: not a scan result, missing columns {missing}")
    if not rows:
        return None
    first = rows[0]
    platform = PLATFORMS.get(first["platform"].strip().lower())
    if platform is None:
        raise ScanError(f"{path}: unknown platform {first['platform']!r}")
    try:
        scan = Scan(
            source=str(path),
            platform=platform,
            scope=first["scan_scope"].strip(),
            window_start=_required_ts(first, "window_start"),
            window_end=_required_ts(first, "window_end"),
            earliest_event_at=parse_ts(first["earliest_event_at"]),
            read_sources=first["read_sources"].strip(),
        )
    except ValueError as exc:
        raise ScanError(f"{path}: line 2: {exc}") from exc
    for line, raw in enumerate(rows, start=2):
        same = (raw["platform"].strip().lower() == platform.name
                and raw["scan_scope"].strip() == scan.scope
                and raw["window_end"].strip() == first["window_end"].strip())
        if not same:
            raise ScanError(f"{path}: line {line}: a scan file holds one scan; this row belongs "
                            "to another platform, scope or window")
        try:
            scan.rows.append(_row(raw))
        except ValueError as exc:
            raise ScanError(f"{path}: line {line}: {exc}") from exc
    return scan


def _opt_text(text: Optional[str]) -> Optional[str]:
    """A text cell, or None when it is empty or a printed NULL."""
    value = (text or "").strip()
    return None if not value or value.upper() == "NULL" else value


def _required_ts(raw: Dict[str, str], column: str) -> dt.datetime:
    value = parse_ts(raw.get(column))
    if value is None:
        raise ValueError(f"{column} is empty")
    return value


def _row(raw: Dict[str, str]) -> ScanRow:
    name = raw["table_name"].strip()
    if not name:
        raise ValueError("table_name is empty")
    return ScanRow(
        catalog=raw["table_catalog"].strip(),
        schema=raw["table_schema"].strip(),
        name=name,
        table_type=_opt_text(raw["table_type"]),
        in_inventory=parse_bool(raw["in_inventory"]),
        created_at=parse_ts(raw["created_at"]),
        last_modified_at=parse_ts(raw["last_modified_at"]),
        last_modified_source=(_opt_text(raw["last_modified_source"]) or "").lower() or None,
        last_altered_at=parse_ts(raw.get("last_altered_at")),
        size_bytes=parse_int(raw["size_bytes"]),
        row_count=parse_int(raw.get("row_count")),
        expiration_at=parse_ts(raw.get("expiration_at")),
        partition_expiration_days=parse_float(raw.get("partition_expiration_days")),
        labels=parse_labels(_opt_text(raw.get("labels"))),
        read_count=parse_int(raw["read_count"]) or 0,
        credential_read_count=parse_int(raw.get("credential_read_count")) or 0,
        system_read_count=parse_int(raw["system_read_count"]) or 0,
        last_read_at=parse_ts(raw["last_read_at"]),
        last_system_read_at=parse_ts(raw["last_system_read_at"]),
        readers={kind: parse_int(raw[f"readers_{kind}"]) or 0 for kind in READER_TYPES},
    )


@dataclass(frozen=True)
class Rule:
    """One class in the customer's retention schedule."""

    cls: str
    retention_days: Optional[float]
    stale_days: Optional[float]


def load_schedule(path: Optional[Path]) -> Dict[str, Rule]:
    """Read schedule.csv: class, retention_days, stale_days (either may be blank)."""
    if path is None:
        return {}
    header, rows = _read_csv(path)
    if "class" not in header:
        raise ScanError(f"{path}: a schedule needs a 'class' column")
    rules: Dict[str, Rule] = {}
    for line, raw in enumerate(rows, start=2):
        cls = raw.get("class", "").strip()
        if not cls:
            continue
        if cls in rules:
            raise ScanError(f"{path}: line {line}: class {cls!r} appears twice")
        try:
            rules[cls] = Rule(cls, parse_float(raw.get("retention_days")),
                              parse_float(raw.get("stale_days")))
        except ValueError as exc:
            raise ScanError(f"{path}: line {line}: {exc}") from exc
    return rules


@dataclass(frozen=True)
class ClassRule:
    """One line of classes.csv. The first matching line gives the table its class."""

    match: str
    pattern: str
    cls: str

    def apply(self, table: Table) -> Optional[str]:
        """Return the class this line gives the table, or None when it does not match."""
        lower = table.platform.name == "databricks"
        labels = table.inventory.labels if table.inventory else {}
        if self.match == "name":
            subject = table.uri if "://" in self.pattern else table.full_name
            pattern = self.pattern
            if lower:
                subject, pattern = subject.lower(), pattern.lower()
            return self.cls if fnmatch.fnmatchcase(subject, pattern) else None
        if self.match == "label":
            key, _, wanted = self.pattern.partition("=")
            if key not in labels:
                return None
            return self.cls if fnmatch.fnmatchcase(labels[key], wanted or "*") else None
        if self.match == "label_value":
            value = labels.get(self.pattern, "").strip()
            return value or None
        if self.match == "type":
            return self.cls if (table.inventory and table.inventory.table_type
                                and fnmatch.fnmatchcase(table.inventory.table_type,
                                                        self.pattern)) else None
        return None


CLASS_MATCHES = ("name", "label", "label_value", "type")


def load_classes(path: Optional[Path]) -> List[ClassRule]:
    """Read classes.csv: match (name, label, label_value, type), pattern, class."""
    if path is None:
        return []
    header, rows = _read_csv(path)
    for column in ("match", "pattern"):
        if column not in header:
            raise ScanError(f"{path}: class rules need a {column!r} column")
    rules = []
    for line, raw in enumerate(rows, start=2):
        match = raw.get("match", "").strip().lower()
        pattern = raw.get("pattern", "").strip()
        cls = raw.get("class", "").strip()
        if not match and not pattern:
            continue
        if match not in CLASS_MATCHES:
            raise ScanError(f"{path}: line {line}: match must be one of {CLASS_MATCHES}")
        if not pattern or (match != "label_value" and not cls):
            raise ScanError(f"{path}: line {line}: needs a pattern and a class")
        rules.append(ClassRule(match, pattern, cls))
    return rules


# ---------------------------------------------------------------------------------------------
# Merge and evaluate
# ---------------------------------------------------------------------------------------------

def merge(scans: Sequence[Scan]) -> Dict[str, Table]:
    """Merge scans into one asset map, refusing a scan given twice."""
    seen: Dict[Tuple[str, str, str], str] = {}
    for scan in scans:
        if scan.key() in seen:
            raise ScanError(f"{scan.source} repeats the scan in {seen[scan.key()]} "
                            f"({scan.key()}); pass each scan once")
        seen[scan.key()] = scan.source
    tables: Dict[str, Table] = {}
    for scan in scans:
        in_this_scan = set()
        for row in scan.rows:
            uri = asset_uri(scan.platform.name, scan.scope, row.catalog, row.schema, row.name)
            if uri in in_this_scan:
                raise ScanError(f"{scan.source}: {uri} appears twice in one scan")
            in_this_scan.add(uri)
            table = tables.get(uri)
            if table is None:
                full = ".".join(p for p in (row.catalog, row.schema, row.name) if p)
                table = Table(uri=uri, platform=scan.platform, full_name=full)
                tables[uri] = table
            table.add(row, scan)
    return tables


@dataclass
class Evaluation:
    """The class and verdicts for one inventoried table."""

    table: Table
    cls: Optional[str]
    rule: Optional[Rule]
    as_of: dt.datetime
    effective_start: dt.datetime
    stale_days: float
    usage: str
    retention: str
    all_rows_past_retention: Optional[bool]


def classify(table: Table, rules: Sequence[ClassRule], default_class: Optional[str]) -> Optional[str]:
    """First matching class rule wins; otherwise the default class, if any."""
    for rule in rules:
        cls = rule.apply(table)
        if cls:
            return cls
    return default_class


def evaluate(table: Table, cls: Optional[str], schedule: Dict[str, Rule],
             default_stale_days: float, window_mode: str) -> Evaluation:
    """Apply the usage and retention rules (README, "Verdicts") to one inventoried table."""
    inv = table.inventory
    scan = table.inventory_scan
    assert inv is not None and scan is not None
    as_of = scan.window_end
    start = scan.effective_start(window_mode)
    rule = schedule.get(cls) if cls else None
    stale_days = (rule.stale_days if rule and rule.stale_days is not None
                  else default_stale_days)

    threshold = as_of - stale_days * DAY
    if table.last_read_at is not None and table.last_read_at >= threshold:
        usage = "ACTIVE"
    elif threshold < start:
        usage = "UNKNOWN_WINDOW"
    elif inv.created_at is None:
        usage = "UNKNOWN_CREATED"
    elif inv.created_at > threshold:
        usage = "NEW"
    else:
        usage = "STALE"

    platform = table.platform
    kind = (inv.table_type or "").upper()
    all_rows: Optional[bool] = None
    if kind in platform.logical_types:
        retention = "NOT_APPLICABLE"
    elif kind in platform.external_types:
        retention = "EXTERNAL_STORAGE"
    elif kind not in platform.data_types:
        retention = "UNKNOWN_TYPE"
    elif cls is None:
        retention = "UNCLASSIFIED"
    elif rule is None or rule.retention_days is None:
        retention = "NO_RULE"
    elif inv.created_at is None:
        retention = "UNKNOWN_CREATED"
    else:
        keep = rule.retention_days * DAY
        if ((inv.partition_expiration_days is not None
             and inv.partition_expiration_days <= rule.retention_days)
                or (inv.expiration_at is not None and inv.expiration_at <= inv.created_at + keep)):
            retention = "MANAGED_BY_EXPIRY"
        elif as_of - inv.created_at > keep:
            retention = "OVER_RETENTION"
            if inv.last_modified_source == "storage" and inv.last_modified_at is not None:
                all_rows = inv.last_modified_at < as_of - keep
        else:
            retention = "WITHIN"
    return Evaluation(table, cls, rule, as_of, start, stale_days, usage, retention, all_rows)


# ---------------------------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------------------------

TABLE_COLUMNS = (
    "asset_uri", "platform", "full_name", "table_type", "class", "created_at", "age_days",
    "last_modified_at", "last_modified_source", "last_read_at", "days_since_read",
    "read_count", "credential_read_count", "system_read_count", "last_system_read_at",
    "readers_human", "readers_service", "readers_system", "readers_unknown",
    "readers_lower_bound", "size_bytes", "row_count", "expiration_at",
    "partition_expiration_days", "retention_days", "stale_days", "usage_verdict",
    "retention_verdict", "all_rows_past_retention", "as_of", "history_start",
)


class Redactor:
    """Replaces asset names with stable keyed tokens, so a report can leave without names."""

    def __init__(self, key: Optional[bytes]):
        self.enabled = key is not None
        self.key = key or b""
        self.mapping: Dict[str, str] = {}

    def token(self, uri: str, prefix: str = "asset") -> str:
        """Token for an asset URI (or a scan scope): the same key always gives the same token."""
        if not self.enabled:
            return uri
        digest = hmac.new(self.key, f"{prefix}:{uri}".encode("utf-8"),
                          hashlib.sha256).hexdigest()[:16]
        value = f"{prefix}-{digest}"
        self.mapping[value] = uri
        return value


def _blank(value) -> str:
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, float):
        return f"{value:g}"
    return str(value)


def table_record(ev: Evaluation, redactor: Redactor) -> Dict[str, str]:
    """One tables.csv row."""
    t = ev.table
    inv = t.inventory
    assert inv is not None
    age = _days(ev.as_of - inv.created_at) if inv.created_at else None
    since = _days(ev.as_of - t.last_read_at) if t.last_read_at else None
    if since is not None and since < 0:
        since = 0.0
    name = redactor.token(t.uri)
    record = {
        "asset_uri": name,
        "platform": t.platform.name,
        "full_name": name if redactor.enabled else t.full_name,
        "table_type": inv.table_type,
        "class": ev.cls,
        "created_at": format_ts(inv.created_at),
        "age_days": age,
        "last_modified_at": format_ts(inv.last_modified_at),
        "last_modified_source": inv.last_modified_source,
        "last_read_at": format_ts(t.last_read_at),
        "days_since_read": since,
        "read_count": t.read_count,
        "credential_read_count": t.credential_read_count,
        "system_read_count": t.system_read_count,
        "last_system_read_at": format_ts(t.last_system_read_at),
        "readers_human": t.readers["human"],
        "readers_service": t.readers["service"],
        "readers_system": t.readers["system"],
        "readers_unknown": t.readers["unknown"],
        "readers_lower_bound": t.readers_lower_bound,
        "size_bytes": inv.size_bytes,
        "row_count": inv.row_count,
        "expiration_at": format_ts(inv.expiration_at),
        "partition_expiration_days": inv.partition_expiration_days,
        "retention_days": ev.rule.retention_days if ev.rule else None,
        "stale_days": ev.stale_days,
        "usage_verdict": ev.usage,
        "retention_verdict": ev.retention,
        "all_rows_past_retention": ev.all_rows_past_retention,
        "as_of": format_ts(ev.as_of),
        "history_start": format_ts(ev.effective_start),
    }
    return {k: _blank(v) for k, v in record.items()}


def _bucket() -> Dict[str, int]:
    return {"tables": 0, "size_bytes": 0, "size_unknown": 0}


def _count(bucket: Dict[str, int], size: Optional[int]) -> None:
    bucket["tables"] += 1
    if size is None:
        bucket["size_unknown"] += 1
    else:
        bucket["size_bytes"] += size


def summarize(scans: Sequence[Scan], tables: Dict[str, Table], evaluations: Sequence[Evaluation],
              settings: Dict[str, object], redactor: Redactor) -> Dict[str, object]:
    """Counts and bytes only: no table names, no principals (and no scopes when redacted)."""
    usage = {v: _bucket() for v in USAGE_VERDICTS}
    retention = {v: _bucket() for v in RETENTION_VERDICTS}
    by_class: Dict[str, Dict[str, object]] = {}
    totals = _bucket()
    findings = {
        "over_retention_and_stale": _bucket(),
        "over_retention_all_rows_past": _bucket(),
        "never_read_in_history": _bucket(),
    }
    readers = {"tables_read_by_humans": 0, "tables_read_only_by_services": 0,
               "tables_read_only_by_system": 0, "tables_with_unknown_readers": 0}
    for ev in evaluations:
        inv = ev.table.inventory
        size = inv.size_bytes if inv else None
        _count(totals, size)
        _count(usage[ev.usage], size)
        _count(retention[ev.retention], size)
        name = ev.cls or "(unclassified)"
        entry = by_class.setdefault(name, {"tables": 0, "size_bytes": 0, "size_unknown": 0,
                                           "usage": {}, "retention": {}})
        entry["tables"] += 1
        if size is None:
            entry["size_unknown"] += 1
        else:
            entry["size_bytes"] += size
        entry["usage"][ev.usage] = entry["usage"].get(ev.usage, 0) + 1
        entry["retention"][ev.retention] = entry["retention"].get(ev.retention, 0) + 1
        if ev.retention == "OVER_RETENTION" and ev.usage == "STALE":
            _count(findings["over_retention_and_stale"], size)
        if ev.all_rows_past_retention:
            _count(findings["over_retention_all_rows_past"], size)
        t = ev.table
        if t.read_count + t.credential_read_count == 0 and ev.usage != "UNKNOWN_WINDOW":
            _count(findings["never_read_in_history"], size)
        r = t.readers
        if r["human"] > 0:
            readers["tables_read_by_humans"] += 1
        elif r["service"] > 0 and r["unknown"] == 0:
            readers["tables_read_only_by_services"] += 1
        if r["system"] > 0 and (r["human"] + r["service"] + r["unknown"]) == 0:
            readers["tables_read_only_by_system"] += 1
        if r["unknown"] > 0:
            readers["tables_with_unknown_readers"] += 1
    not_inventoried = [t for t in tables.values() if t.inventory is None]
    platforms = sorted({s.platform.name for s in scans})
    return {
        "tool": TOOL,
        "contract_version": CONTRACT_VERSION,
        "settings": settings,
        "scans": [
            {
                "platform": s.platform.name,
                "scope": redactor.token(s.scope, "scope"),
                "window_start": format_ts(s.window_start),
                "window_end": format_ts(s.window_end),
                "earliest_event_at": format_ts(s.earliest_event_at),
                "history_start": format_ts(s.effective_start(str(settings["window_mode"]))),
                "read_sources": s.read_sources,
                "rows": len(s.rows),
                "inventoried": sum(1 for r in s.rows if r.in_inventory),
            }
            for s in sorted(scans, key=lambda s: s.key())
        ],
        "totals": totals,
        "usage": usage,
        "retention": retention,
        "by_class": {k: by_class[k] for k in sorted(by_class)},
        "findings": findings,
        "readers": readers,
        "referenced_not_inventoried": {
            "objects": len(not_inventoried),
            "read_count": sum(t.read_count + t.credential_read_count for t in not_inventoried),
        },
        "coverage": {p: {"reads": PLATFORMS[p].read_label, "gaps": list(PLATFORMS[p].gaps)}
                     for p in platforms},
    }


def _size(n: int) -> str:
    value = float(n)
    for unit in ("B", "KB", "MB", "GB", "TB", "PB"):
        if value < 1024 or unit == "PB":
            return f"{value:.0f} {unit}" if unit == "B" else f"{value:.1f} {unit}"
        value /= 1024
    return f"{n} B"


def _size_cell(bucket: Dict[str, int]) -> str:
    if bucket["size_unknown"] == bucket["tables"]:
        return "unknown"
    text = _size(bucket["size_bytes"])
    if bucket["size_unknown"]:
        text += f" (+{bucket['size_unknown']} without a size)"
    return text


def render_markdown(summary: Dict[str, object], evaluations: Sequence[Evaluation],
                    redactor: Redactor, top: int = 10) -> str:
    """The summary as text for an evidence memo."""
    out = io.StringIO()
    w = out.write
    settings = summary["settings"]
    w("# Data fitness scan summary\n\n")
    w(f"Generated by {TOOL} (contract {CONTRACT_VERSION}). Stale after "
      f"{_blank(settings['stale_days_default'])} days unless the schedule says otherwise; history "
      f"window mode: {settings['window_mode']}.\n\n")
    w("## Scans\n\n| Platform | Scope | Window | History used from | Reads seen |\n")
    w("|---|---|---|---|---|\n")
    for s in summary["scans"]:
        w(f"| {s['platform']} | {s['scope']} | {s['window_start']} to {s['window_end']} | "
          f"{s['history_start']} | {s['read_sources']} |\n")
    totals = summary["totals"]
    w(f"\n{totals['tables']} tables and views; {_size(totals['size_bytes'])} of known size"
      f" ({totals['size_unknown']} without a size).\n\n")
    w("## Usage\n\n| Verdict | Tables | Size |\n|---|---|---|\n")
    for verdict, bucket in summary["usage"].items():
        if bucket["tables"]:
            w(f"| {verdict} | {bucket['tables']} | {_size_cell(bucket)} |\n")
    w("\n## Retention against the schedule\n\n| Verdict | Tables | Size |\n|---|---|---|\n")
    for verdict, bucket in summary["retention"].items():
        if bucket["tables"]:
            w(f"| {verdict} | {bucket['tables']} | {_size_cell(bucket)} |\n")
    f = summary["findings"]
    w("\n## Findings\n\n")
    w(f"- Over retention and stale: {f['over_retention_and_stale']['tables']} tables, "
      f"{_size(f['over_retention_and_stale']['size_bytes'])}.\n")
    w(f"- Over retention with every row past it (last write older than the retention period): "
      f"{f['over_retention_all_rows_past']['tables']} tables, "
      f"{_size(f['over_retention_all_rows_past']['size_bytes'])}.\n")
    w(f"- Never read in the history used: {f['never_read_in_history']['tables']} tables, "
      f"{_size(f['never_read_in_history']['size_bytes'])}.\n")
    r = summary["readers"]
    w(f"- Read by people: {r['tables_read_by_humans']} tables; only by service identities: "
      f"{r['tables_read_only_by_services']}; only by platform or monitoring identities: "
      f"{r['tables_read_only_by_system']}.\n")
    n = summary["referenced_not_inventoried"]
    w(f"- Objects read but not in any scanned inventory (other projects, deleted tables): "
      f"{n['objects']}.\n")
    flagged = [ev for ev in evaluations if ev.retention == "OVER_RETENTION"]
    flagged.sort(key=lambda ev: (-(ev.table.inventory.size_bytes or 0), ev.table.uri))
    if flagged:
        w(f"\n## Largest over-retention tables (top {min(top, len(flagged))})\n\n")
        w("| Asset | Class | Age (days) | Last read | Usage | Size |\n|---|---|---|---|---|---|\n")
        for ev in flagged[:top]:
            inv = ev.table.inventory
            age = _blank(_days(ev.as_of - inv.created_at)) if inv.created_at else ""
            size = _size(inv.size_bytes) if inv.size_bytes is not None else "unknown"
            w(f"| {redactor.token(ev.table.uri)} | {ev.cls} | {age} | "
              f"{format_ts(ev.table.last_read_at) or 'none seen'} | {ev.usage} | {size} |\n")
    w("\n## What the scan cannot see\n\n")
    for platform, cov in summary["coverage"].items():
        w(f"{platform}: {cov['reads']}.\n\n")
        for gap in cov["gaps"]:
            w(f"- {gap}\n")
        w("\n")
    return out.getvalue()


def write_report(out_dir: Path, scans: Sequence[Scan], tables: Dict[str, Table],
                 evaluations: Sequence[Evaluation], settings: Dict[str, object],
                 redactor: Redactor) -> Dict[str, object]:
    """Write tables.csv, summary.json and summary.md (and the redaction map, if any)."""
    out_dir.mkdir(parents=True, exist_ok=True)
    ordered = sorted(evaluations, key=lambda ev: ev.table.uri)
    with (out_dir / "tables.csv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=TABLE_COLUMNS, lineterminator="\n")
        writer.writeheader()
        for ev in ordered:
            writer.writerow(table_record(ev, redactor))
    summary = summarize(scans, tables, ordered, settings, redactor)
    (out_dir / "summary.json").write_text(json.dumps(summary, indent=2, sort_keys=False) + "\n",
                                          encoding="utf-8")
    (out_dir / "summary.md").write_text(render_markdown(summary, ordered, redactor),
                                        encoding="utf-8")
    if redactor.enabled:
        with (out_dir / "redaction_map.KEEP_LOCAL.csv").open("w", encoding="utf-8",
                                                             newline="") as handle:
            writer = csv.writer(handle, lineterminator="\n")
            writer.writerow(["token", "asset_uri"])
            for token in sorted(redactor.mapping):
                writer.writerow([token, redactor.mapping[token]])
    return summary


def run(scan_paths: Sequence[Path], out_dir: Path, schedule_path: Optional[Path] = None,
        classes_path: Optional[Path] = None, stale_days: float = 90.0,
        window_mode: str = "observed", default_class: Optional[str] = None,
        redact_key: Optional[bytes] = None) -> Dict[str, object]:
    """Load, merge, evaluate and write. Returns the summary."""
    if stale_days <= 0:
        raise ScanError("--stale-days must be positive")
    scans = [s for s in (load_scan(p) for p in scan_paths) if s is not None]
    if not scans:
        raise ScanError("no scan rows in the input files")
    schedule = load_schedule(schedule_path)
    class_rules = load_classes(classes_path)
    tables = merge(scans)
    evaluations = []
    for table in tables.values():
        if table.inventory is None:
            continue
        cls = classify(table, class_rules, default_class)
        evaluations.append(evaluate(table, cls, schedule, stale_days, window_mode))
    settings = {"stale_days_default": stale_days, "window_mode": window_mode,
                "default_class": default_class, "redacted": redact_key is not None,
                "classes_without_rule": sorted({ev.cls for ev in evaluations
                                                if ev.cls and ev.cls not in schedule})}
    return write_report(out_dir, scans, tables, evaluations, settings, Redactor(redact_key))


def main(argv: Optional[Sequence[str]] = None) -> int:
    """Command line entry point."""
    parser = argparse.ArgumentParser(
        description="Aggregate DLAP zero-access fitness scan CSVs into a per-table report.")
    parser.add_argument("scans", nargs="*", type=Path, help="scan result CSV files")
    parser.add_argument("--schedule", type=Path, help="schedule.csv: class,retention_days,stale_days")
    parser.add_argument("--classes", type=Path, help="classes.csv: match,pattern,class")
    parser.add_argument("--out-dir", type=Path, help="directory for tables.csv and summaries")
    parser.add_argument("--stale-days", type=float, default=90.0,
                        help="days without a read before a table is STALE (default 90)")
    parser.add_argument("--window", choices=("observed", "nominal"), default="observed",
                        help="observed: trust history only from the earliest event it holds "
                             "(default); nominal: trust the full window")
    parser.add_argument("--default-class", help="class for tables no rule matches")
    parser.add_argument("--redact", action="store_true",
                        help="replace asset names with keyed tokens in tables.csv and summary.md")
    parser.add_argument("--redact-key-file", type=Path,
                        help="key for --redact, so tokens are stable across runs")
    parser.add_argument("--selftest", action="store_true", help="run the built-in checks and exit")
    args = parser.parse_args(argv)

    if args.selftest:
        return selftest()
    if not args.scans or args.out_dir is None:
        parser.error("give at least one scan CSV and --out-dir (or --selftest)")
    key: Optional[bytes] = None
    if args.redact or args.redact_key_file:
        key = (args.redact_key_file.read_bytes().strip() if args.redact_key_file
               else secrets.token_bytes(32))
        if not key:
            parser.error("--redact-key-file is empty")
    try:
        summary = run(args.scans, args.out_dir, args.schedule, args.classes, args.stale_days,
                      args.window, args.default_class, key)
    except ScanError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    totals = summary["totals"]
    print(f"{totals['tables']} tables evaluated; report in {args.out_dir}")
    return 0


# ---------------------------------------------------------------------------------------------
# Self-test: synthetic rows only, no vendor samples.
# ---------------------------------------------------------------------------------------------

KIT_DIR = Path(__file__).resolve().parent

_BQ_END = "2026-10-18T00:00:00Z"
_BQ_START = "2026-04-21T00:00:00Z"


def _bq_row(name: str, **values: object) -> Dict[str, object]:
    row: Dict[str, object] = {c: "" for c in CONTRACT_COLUMNS}
    row.update({
        "platform": "bigquery", "scan_scope": "acme-prod", "window_start": _BQ_START,
        "window_end": _BQ_END, "earliest_event_at": "2026-04-21T03:00:00Z",
        "table_catalog": "acme-prod", "table_schema": "sales", "table_name": name,
        "table_type": "BASE TABLE", "in_inventory": "true",
        "created_at": "2023-01-01T00:00:00Z", "last_modified_at": "2026-10-01T00:00:00Z",
        "last_modified_source": "storage", "size_bytes": "1000", "row_count": "10",
        "read_count": "0", "system_read_count": "0", "readers_human": "0",
        "readers_service": "0", "readers_system": "0", "readers_unknown": "0",
        "read_sources": "bq_jobs",
    })
    row.update({k: str(v) for k, v in values.items()})
    return row


def _write_csv(path: Path, rows: Iterable[Dict[str, object]],
               columns: Sequence[str] = CONTRACT_COLUMNS) -> Path:
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(columns), lineterminator="\n")
        writer.writeheader()
        for row in rows:
            writer.writerow(row)
    return path


def _strip_sql(text: str) -> str:
    """SQL with comments and string literals removed, for the lint checks."""
    text = re.sub(r"--[^\n]*", "", text)
    text = re.sub(r"(?i)(?:\br)?'(?:[^'\\]|\\.)*'", "''", text)
    text = re.sub(r'(?i)(?:\br)?"(?:[^"\\]|\\.)*"', '""', text)
    return text


class SelfTest(unittest.TestCase):
    """Checks for the parser, the merge, the verdict rules and the SQL files."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = Path(self._tmp.name)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def _run(self, scans: Sequence[Path], schedule: Optional[Path] = None,
             classes: Optional[Path] = None, **kwargs) -> Tuple[Dict[str, object],
                                                                 Dict[str, Dict[str, str]]]:
        out = self.tmp / "out"
        summary = run(scans, out, schedule, classes, **kwargs)
        with (out / "tables.csv").open(encoding="utf-8", newline="") as handle:
            rows = {r["asset_uri"]: r for r in csv.DictReader(handle)}
        return summary, rows

    def _schedule(self) -> Path:
        path = self.tmp / "schedule.csv"
        path.write_text("class,retention_days,stale_days\n"
                        "customer_pii,365,\n"
                        "finance,2555,180\n"
                        "scratch,,30\n", encoding="utf-8")
        return path

    def _classes(self) -> Path:
        path = self.tmp / "classes.csv"
        path.write_text("match,pattern,class\n"
                        "label,data_class=pii,customer_pii\n"
                        "name,acme-prod.finance.*,finance\n"
                        "label_value,dlap_class,\n"
                        "name,*.sales.*,customer_pii\n", encoding="utf-8")
        return path

    # Parsing -----------------------------------------------------------------------------

    def test_timestamps(self) -> None:
        utc = dt.timezone.utc
        self.assertEqual(parse_ts("2026-10-18T12:00:00Z"), dt.datetime(2026, 10, 18, 12, tzinfo=utc))
        self.assertEqual(parse_ts("2026-10-18 12:00:00.123456 UTC"),
                         dt.datetime(2026, 10, 18, 12, 0, 0, 123456, tzinfo=utc))
        self.assertEqual(parse_ts("2026-10-18T14:00:00.5+02:00"),
                         dt.datetime(2026, 10, 18, 12, 0, 0, 500000, tzinfo=utc))
        self.assertEqual(parse_ts("2026-10-18 12:00:00"), dt.datetime(2026, 10, 18, 12, tzinfo=utc))
        self.assertEqual(parse_ts("2026-10-18"), dt.datetime(2026, 10, 18, tzinfo=utc))
        self.assertIsNone(parse_ts(""))
        self.assertIsNone(parse_ts("NULL"))
        with self.assertRaises(ValueError):
            parse_ts("yesterday")

    def test_labels(self) -> None:
        self.assertEqual(parse_labels('[STRUCT("data_class", "pii"), STRUCT("team", "growth")]'),
                         {"data_class": "pii", "team": "growth"})
        self.assertEqual(parse_labels("dlap_class=finance;owner=ops"),
                         {"dlap_class": "finance", "owner": "ops"})
        self.assertEqual(parse_labels(""), {})

    # Verdicts ----------------------------------------------------------------------------

    def test_bigquery_verdicts(self) -> None:
        rows = [
            # Old, never read, last write long ago, labelled pii: STALE and every row past 365d.
            _bq_row("old_pii", labels='[STRUCT("data_class", "pii")]',
                    last_modified_at="2024-06-01T00:00:00Z", size_bytes="5000"),
            # Read 10 days before the window end by one person.
            _bq_row("active", read_count="4", last_read_at="2026-10-08T00:00:00Z",
                    readers_human="1"),
            # Created 30 days before the window end, never read: too new to call stale.
            _bq_row("new_table", created_at="2026-09-18T00:00:00Z"),
            # A view: retention does not apply; read through, so ACTIVE.
            _bq_row("v_sales", table_type="VIEW", size_bytes="", last_modified_source="",
                    last_modified_at="", read_count="2", last_read_at="2026-10-17T00:00:00Z",
                    readers_service="1"),
            # Partition expiry shorter than the class retention.
            _bq_row("events", partition_expiration_days="30"),
            # Table expiry set before created + retention.
            _bq_row("expiring", created_at="2026-01-01T00:00:00Z",
                    expiration_at="2026-04-01T00:00:00Z"),
            # External data: retention belongs to the bucket.
            _bq_row("ext_logs", table_type="EXTERNAL"),
            # Only a service agent read it: system reads never make a table ACTIVE.
            _bq_row("profiled", system_read_count="12", readers_system="1",
                    last_system_read_at="2026-10-17T00:00:00Z"),
            # Unknown creation time.
            _bq_row("mystery", created_at=""),
            # Finance by name, 7-year retention, stale after 180 days, read 100 days ago.
            _bq_row("ledger", table_schema="finance", read_count="1",
                    last_read_at="2026-07-10T00:00:00Z", readers_human="1"),
            # Unclassified schema.
            _bq_row("tmp", table_schema="scratchpad"),
            # Read by this project's jobs but not in the inventory.
            _bq_row("partner_feed", table_catalog="partner-proj", table_schema="share",
                    in_inventory="false", table_type="", created_at="", size_bytes="",
                    last_modified_at="", last_modified_source="", read_count="3",
                    last_read_at="2026-10-01T00:00:00Z", readers_service="1"),
        ]
        scan = _write_csv(self.tmp / "bq.csv", rows)
        summary, out = self._run([scan], self._schedule(), self._classes())

        def v(name: str, schema: str = "sales") -> Dict[str, str]:
            return out[f"bq://acme-prod/{schema}/{name}"]

        self.assertEqual(v("old_pii")["class"], "customer_pii")
        self.assertEqual(v("old_pii")["usage_verdict"], "STALE")
        self.assertEqual(v("old_pii")["retention_verdict"], "OVER_RETENTION")
        self.assertEqual(v("old_pii")["all_rows_past_retention"], "true")
        self.assertEqual(v("old_pii")["age_days"], "1386")
        self.assertEqual(v("active")["usage_verdict"], "ACTIVE")
        self.assertEqual(v("active")["days_since_read"], "10")
        self.assertEqual(v("active")["all_rows_past_retention"], "false")
        self.assertEqual(v("new_table")["usage_verdict"], "NEW")
        self.assertEqual(v("new_table")["retention_verdict"], "WITHIN")
        self.assertEqual(v("v_sales")["retention_verdict"], "NOT_APPLICABLE")
        self.assertEqual(v("v_sales")["usage_verdict"], "ACTIVE")
        self.assertEqual(v("events")["retention_verdict"], "MANAGED_BY_EXPIRY")
        self.assertEqual(v("expiring")["retention_verdict"], "MANAGED_BY_EXPIRY")
        self.assertEqual(v("ext_logs")["retention_verdict"], "EXTERNAL_STORAGE")
        self.assertEqual(v("profiled")["usage_verdict"], "STALE")
        self.assertEqual(v("profiled")["last_read_at"], "")
        self.assertEqual(v("profiled")["system_read_count"], "12")
        self.assertEqual(v("mystery")["usage_verdict"], "UNKNOWN_CREATED")
        self.assertEqual(v("mystery")["retention_verdict"], "UNKNOWN_CREATED")
        self.assertEqual(v("ledger", "finance")["class"], "finance")
        self.assertEqual(v("ledger", "finance")["usage_verdict"], "ACTIVE")
        self.assertEqual(v("ledger", "finance")["stale_days"], "180")
        self.assertEqual(v("ledger", "finance")["retention_verdict"], "WITHIN")
        self.assertEqual(v("tmp", "scratchpad")["retention_verdict"], "UNCLASSIFIED")
        self.assertNotIn("bq://partner-proj/share/partner_feed", out)
        self.assertEqual(summary["referenced_not_inventoried"], {"objects": 1, "read_count": 3})
        self.assertEqual(summary["findings"]["over_retention_and_stale"]["tables"], 2)
        self.assertEqual(summary["findings"]["over_retention_all_rows_past"]["size_bytes"], 5000)
        self.assertEqual(summary["readers"]["tables_read_only_by_system"], 1)
        self.assertEqual(summary["totals"]["tables"], 11)

    def test_history_window(self) -> None:
        # History reaches back only 60 days, less than the 90-day stale threshold.
        rows = [_bq_row("quiet", earliest_event_at="2026-08-19T00:00:00Z")]
        scan = _write_csv(self.tmp / "bq.csv", rows)
        _, out = self._run([scan])
        self.assertEqual(out["bq://acme-prod/sales/quiet"]["usage_verdict"], "UNKNOWN_WINDOW")
        _, out = self._run([scan], window_mode="nominal")
        self.assertEqual(out["bq://acme-prod/sales/quiet"]["usage_verdict"], "STALE")
        # No events at all: nothing can be called stale.
        rows = [_bq_row("quiet", earliest_event_at="")]
        scan = _write_csv(self.tmp / "bq2.csv", rows)
        _, out = self._run([scan])
        self.assertEqual(out["bq://acme-prod/sales/quiet"]["usage_verdict"], "UNKNOWN_WINDOW")

    def test_stale_threshold_boundary(self) -> None:
        rows = [_bq_row("edge", last_read_at="2026-07-20T00:00:00Z", read_count="1",
                        readers_human="1"),
                _bq_row("past", last_read_at="2026-07-19T23:59:59Z", read_count="1",
                        readers_human="1")]
        _, out = self._run([_write_csv(self.tmp / "bq.csv", rows)])
        self.assertEqual(out["bq://acme-prod/sales/edge"]["usage_verdict"], "ACTIVE")
        self.assertEqual(out["bq://acme-prod/sales/past"]["usage_verdict"], "STALE")

    def test_databricks(self) -> None:
        def dbx(name: str, **values: object) -> Dict[str, object]:
            row = _bq_row(name, **values)
            row.update({"platform": "databricks", "scan_scope": "A1B2-meta",
                        "table_catalog": "Main", "table_schema": "Crm",
                        "table_type": values.get("table_type", "MANAGED"),
                        "size_bytes": "", "row_count": "",
                        "last_modified_source": "lineage", "read_sources": "uc_lineage,uc_credentials",
                        "window_start": "2025-10-18T00:00:00Z",
                        "earliest_event_at": "2025-10-20T00:00:00Z"})
            row.update({k: str(v) for k, v in values.items()})
            return row

        rows = [
            dbx("Contacts", labels="dlap_class=customer_pii;owner=crm",
                last_modified_at="2024-01-01T00:00:00Z", credential_read_count="2",
                last_read_at="2026-01-01T00:00:00Z", readers_unknown="1"),
            dbx("ext_files", table_type="EXTERNAL"),
            dbx("v_contacts", table_type="VIEW"),
        ]
        _, out = self._run([_write_csv(self.tmp / "dbx.csv", rows)], self._schedule(),
                           self._classes())
        contacts = out["uc://a1b2-meta/main/crm/contacts"]
        self.assertEqual(contacts["class"], "customer_pii")
        self.assertEqual(contacts["usage_verdict"], "STALE")
        self.assertEqual(contacts["retention_verdict"], "OVER_RETENTION")
        # Lineage is not an authoritative last write, so "every row past" stays unknown.
        self.assertEqual(contacts["all_rows_past_retention"], "")
        self.assertEqual(contacts["size_bytes"], "")
        self.assertEqual(contacts["credential_read_count"], "2")
        self.assertEqual(out["uc://a1b2-meta/main/crm/ext_files"]["retention_verdict"],
                         "EXTERNAL_STORAGE")
        self.assertEqual(out["uc://a1b2-meta/main/crm/v_contacts"]["retention_verdict"],
                         "NOT_APPLICABLE")

    # Merge -------------------------------------------------------------------------------

    def test_cross_project_merge(self) -> None:
        a = _write_csv(self.tmp / "a.csv", [
            _bq_row("orders", read_count="2", last_read_at="2026-06-01T00:00:00Z",
                    readers_human="2"),
        ])
        b_rows = [
            _bq_row("orders", scan_scope="acme-bi", window_end="2026-10-17T00:00:00Z",
                    in_inventory="false", table_type="", created_at="", size_bytes="",
                    read_count="3", last_read_at="2026-10-10T00:00:00Z", readers_human="1",
                    readers_service="1"),
            _bq_row("dash", table_catalog="acme-bi", table_schema="bi",
                    scan_scope="acme-bi", window_end="2026-10-17T00:00:00Z"),
        ]
        b = _write_csv(self.tmp / "b.csv", b_rows)
        summary, out = self._run([a, b])
        orders = out["bq://acme-prod/sales/orders"]
        self.assertEqual(orders["read_count"], "5")
        self.assertEqual(orders["last_read_at"], "2026-10-10T00:00:00Z")
        self.assertEqual(orders["readers_human"], "2")
        self.assertEqual(orders["readers_service"], "1")
        self.assertEqual(orders["readers_lower_bound"], "true")
        self.assertEqual(orders["usage_verdict"], "ACTIVE")
        self.assertEqual(orders["as_of"], _BQ_END)
        self.assertEqual(len(summary["scans"]), 2)

    def test_same_scan_twice_is_refused(self) -> None:
        path = _write_csv(self.tmp / "a.csv", [_bq_row("orders")])
        copy = self.tmp / "copy.csv"
        copy.write_bytes(path.read_bytes())
        with self.assertRaisesRegex(ScanError, "repeats the scan"):
            self._run([path, copy])

    def test_double_inventory_is_refused(self) -> None:
        a = _write_csv(self.tmp / "a.csv", [_bq_row("orders")])
        b = _write_csv(self.tmp / "b.csv", [_bq_row("orders", window_end="2026-10-19T00:00:00Z")])
        with self.assertRaisesRegex(ScanError, "inventory of two scans"):
            self._run([a, b])

    def test_mixed_scan_file_is_refused(self) -> None:
        path = _write_csv(self.tmp / "a.csv", [_bq_row("orders"),
                                               _bq_row("other", scan_scope="elsewhere")])
        with self.assertRaisesRegex(ScanError, "holds one scan"):
            self._run([path])

    # Contract guards ---------------------------------------------------------------------

    def test_query_text_column_is_refused(self) -> None:
        for column in ("query", "statement_text", "user_email", "created_by"):
            row = _bq_row("orders")
            row[column] = "x"
            path = _write_csv(self.tmp / f"{column}.csv", [row], CONTRACT_COLUMNS + (column,))
            with self.assertRaisesRegex(ScanError, "refused"):
                load_scan(path)

    def test_missing_column_is_refused(self) -> None:
        columns = tuple(c for c in CONTRACT_COLUMNS if c != "last_read_at")
        row = {k: v for k, v in _bq_row("orders").items() if k != "last_read_at"}
        with self.assertRaisesRegex(ScanError, "missing columns"):
            load_scan(_write_csv(self.tmp / "m.csv", [row], columns))

    def test_optional_columns_may_be_absent(self) -> None:
        columns = tuple(c for c in CONTRACT_COLUMNS if c not in OPTIONAL_COLUMNS)
        row = {k: v for k, v in _bq_row("orders").items() if k in columns}
        scan = load_scan(_write_csv(self.tmp / "o.csv", [row], columns))
        self.assertIsNotNone(scan)
        self.assertEqual(scan.rows[0].labels, {})

    def test_redaction(self) -> None:
        path = _write_csv(self.tmp / "a.csv", [_bq_row("orders"), _bq_row("customers")])
        _, first = self._run([path], redact_key=b"k1")
        _, second = self._run([path], redact_key=b"k1")
        self.assertEqual(sorted(first), sorted(second))
        text = (self.tmp / "out" / "tables.csv").read_text(encoding="utf-8")
        text += (self.tmp / "out" / "summary.md").read_text(encoding="utf-8")
        self.assertNotIn("orders", text)
        self.assertNotIn("acme-prod", text)
        summary = (self.tmp / "out" / "summary.json").read_text(encoding="utf-8")
        self.assertNotIn("acme-prod", summary)
        mapping = (self.tmp / "out" / "redaction_map.KEEP_LOCAL.csv").read_text(encoding="utf-8")
        self.assertIn("bq://acme-prod/sales/orders", mapping)

    def test_summary_has_no_names(self) -> None:
        path = _write_csv(self.tmp / "a.csv", [_bq_row("orders_secret_name")])
        self._run([path])
        summary = (self.tmp / "out" / "summary.json").read_text(encoding="utf-8")
        self.assertNotIn("orders_secret_name", summary)

    def test_cli(self) -> None:
        path = _write_csv(self.tmp / "a.csv", [_bq_row("orders")])
        out = self.tmp / "cli"
        captured = io.StringIO()
        with contextlib.redirect_stdout(captured), contextlib.redirect_stderr(captured):
            self.assertEqual(main([str(path), "--out-dir", str(out)]), 0)
            bad = self.tmp / "bad.csv"
            bad.write_text("platform,query\nbigquery,x\n", encoding="utf-8")
            self.assertEqual(main([str(bad), "--out-dir", str(out)]), 2)
        for name in ("tables.csv", "summary.json", "summary.md"):
            self.assertTrue((out / name).is_file(), name)
        self.assertIn("refused", captured.getvalue())

    # SQL lint ----------------------------------------------------------------------------

    def _sql(self, platform: str) -> str:
        path = KIT_DIR / platform / "last_read.sql"
        if not path.is_file():
            self.skipTest(f"{path} not next to this script")
        return path.read_text(encoding="utf-8")

    def test_sql_emits_the_contract(self) -> None:
        for platform in ("bigquery", "databricks"):
            code = _strip_sql(self._sql(platform))
            final = code[code.rindex("SELECT\n  '"):].split("\nFROM ")[0]
            aliases = re.findall(r"\bAS\s+([a-z_]+)\s*,?\s*$", final, flags=re.M)
            self.assertEqual(tuple(aliases), CONTRACT_COLUMNS, platform)

    def test_bigquery_sql_semantics(self) -> None:
        raw = self._sql("bigquery")
        code = _strip_sql(raw)
        regions = re.findall(r"`(region-[a-z0-9-]+)`", code)
        self.assertEqual(len(regions), 4)
        self.assertEqual(len(set(regions)), 1, "all region qualifiers must match")
        for needle in ("INFORMATION_SCHEMA.JOBS_BY_PROJECT", "INFORMATION_SCHEMA.TABLES",
                       "INFORMATION_SCHEMA.TABLE_STORAGE", "INTERVAL 180 DAY",
                       "cache_hit IS NOT TRUE", "pj.job_id IS NULL", "j.state =",
                       "NOT j.failed", "referenced_tables", "referenced_logical_views"):
            self.assertIn(needle, code)
        self.assertIn("'SCRIPT'", raw)
        self.assertIsNone(re.search(r"(?i)\bquery\b", code), "the JOBS query column is never read")
        self.assertIsNone(re.search(r"(?i)SELECT\s+\*|\.\*", code), "no wildcard selects")

    def test_databricks_sql_semantics(self) -> None:
        raw = self._sql("databricks")
        code = _strip_sql(raw)
        self.assertFalse("statement_text" in raw.lower(), "statement_text must never appear")
        self.assertIsNone(re.search(r"(?i)\bsystem\.query\b", code), "system.query is never read")
        for needle in ("system.access.table_lineage", "system.information_schema.tables",
                       "system.access.audit", "generateTemporaryTableCredential",
                       "'VIEW', 'METRIC_VIEW'", "response.status_code = 200"):
            self.assertIn(needle, raw)
        keys = set(re.findall(r"request_params\['([^']+)'\]", raw))
        self.assertEqual(keys, {"table_full_name", "operation"})
        self.assertEqual(len(re.findall(r"request_params(?!\[)", code)), 0,
                         "the request_params map is never read whole")
        self.assertIsNone(re.search(r"(?i)SELECT\s+\*|\.\*", code), "no wildcard selects")

    def test_kit_is_ascii(self) -> None:
        files = [KIT_DIR / "aggregate.py", KIT_DIR / "README.md",
                 KIT_DIR / "bigquery" / "last_read.sql", KIT_DIR / "databricks" / "last_read.sql"]
        present = [f for f in files if f.is_file()]
        if len(present) < len(files):
            self.skipTest("kit files not next to this script")
        for path in present:
            data = path.read_bytes()
            self.assertTrue(all(b < 128 for b in data), f"{path.name} has non-ASCII bytes")


def selftest() -> int:
    """Run the self-test; 0 when every check passes."""
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(SelfTest)
    result = unittest.TextTestRunner(stream=sys.stdout, verbosity=2).run(suite)
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    sys.exit(main())
