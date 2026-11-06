#!/usr/bin/env python3
"""Non-mutating, full-history manifest and migration comparison for offline FG copies.

This script NEVER upgrades a database. It opens an explicitly acknowledged
isolated copy in SQLite read-only/query-only mode and emits only counts, hashes,
schema checks, and file-size metrics. Use matching stopped-server WAL/SHM files.
"""
from __future__ import annotations

import argparse
from contextlib import closing
import hashlib
import json
import os
import sqlite3
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

TABLES: dict[str, tuple[str, ...]] = {
    "block_changes": (
        "id", "happened_at", "actor_uuid", "actor_name", "world", "x", "y", "z",
        "action", "before_data", "after_data", "world_uuid", "chunk_x", "chunk_z",
        "coalesce_session", "server_tick", "before_entity_data",
        "after_entity_data", "rollback_pending",
    ),
    "rollback_jobs": (
        "id", "created_at", "updated_at", "actor_uuid", "actor_name",
        "world_uuid", "world", "center_x", "center_z", "radius",
        "target_timestamp", "snapshot_timestamp", "force", "status",
        "total_blocks", "processed_blocks", "applied_blocks", "conflict_blocks",
        "undo_processed_blocks", "last_error",
    ),
    "rollback_job_changes": (
        "job_id", "sequence", "world", "x", "y", "z", "before_data",
        "target_data", "expected_data", "processed", "applied", "conflicted",
        "undone", "before_entity_data", "target_entity_data",
        "expected_entity_data", "applied_data", "applied_entity_data",
        "pending_audit_id", "pending_audit_undo",
    ),
}
SORT_KEYS: dict[str, tuple[str, ...]] = {
    "block_changes": ("id",),
    "rollback_jobs": ("id",),
    "rollback_job_changes": ("job_id", "sequence"),
}
CAUSAL_FIELDS = ("id", "causal_player_uuid", "causal_player_name",
                 "causal_chain_id", "causal_cause_type")
MAX_ROWS_BATCH = 512

# All indexes required by the production schema-v9 release gate. In
# particular, historical name and world-scoped queries on large datasets
# must not silently pass with their migration-created indexes missing.
REQUIRED_V9_INDEXES = frozenset((
    "idx_fg_spatial_time",
    "idx_fg_actor_time",
    "idx_fg_actor_world_time",
    "idx_fg_actor_name_uuid",
    "idx_fg_causal_actor_time",
    "idx_fg_causal_actor_world_time",
    "idx_fg_causal_name_uuid",
    "idx_fg_job_chunk_overlap",
))


def _sql_ident(name: str) -> str:
    # All identifiers come from fixed internal tuples, never user input.
    if not name.replace("_", "").isalnum():
        raise ValueError("Invalid internal SQL column")
    return '"' + name + '"'


def _scalar(connection: sqlite3.Connection, statement: str) -> Any:
    row = connection.execute(statement).fetchone()
    if row is None:
        raise ValueError("SQLite did not return a required result")
    return row[0]


def _table_columns(connection: sqlite3.Connection, table: str) -> set[str]:
    return {r[1] for r in connection.execute(f"PRAGMA table_info({_sql_ident(table)})")}


def _digest_rows(connection: sqlite3.Connection, table: str,
                 columns: tuple[str, ...]) -> str:
    column_sql = ", ".join(map(_sql_ident, columns))
    order_sql = ", ".join(map(_sql_ident, SORT_KEYS[table]))
    stream = connection.execute(
        f"SELECT {column_sql} FROM {_sql_ident(table)} ORDER BY {order_sql}")
    digest = hashlib.sha256()
    digest.update(("FG-OFFLINE-MANIFEST-v1:" + table + ":" + ",".join(columns)).encode())
    while True:
        rows = stream.fetchmany(MAX_ROWS_BATCH)
        if not rows:
            break
        for row in rows:
            for value in row:
                if value is None:
                    digest.update(b"N")
                elif isinstance(value, int):
                    payload = str(value).encode("ascii")
                    digest.update(b"I" + len(payload).to_bytes(8, "big") + payload)
                elif isinstance(value, float):
                    payload = value.hex().encode("ascii")
                    digest.update(b"F" + len(payload).to_bytes(8, "big") + payload)
                else:
                    binary = isinstance(value, bytes)
                    payload = value if binary else str(value).encode("utf-8")
                    digest.update((b"B" if binary else b"T")
                                  + len(payload).to_bytes(8, "big") + payload)
            digest.update(b"\n")
    return digest.hexdigest()


def _file_size(path: Path) -> int:
    try:
        return path.stat().st_size
    except FileNotFoundError:
        return 0


def inspect(db: Path) -> dict[str, Any]:
    if not db.is_file():
        raise ValueError("Database path is not an existing file")
    # No file writes, checkpoints, auto-migrations, or PRAGMA user_version edits.
    uri = db.absolute().as_uri() + "?mode=ro"
    with closing(sqlite3.connect(uri, uri=True, timeout=5.0)) as connection:
        connection.execute("PRAGMA query_only=ON")
        schema = int(_scalar(connection, "PRAGMA user_version"))
        if schema not in (4, 7, 8, 9):
            raise ValueError(f"Schema {schema} is not part of the v4/v7/v8/v9 validation plan")
        integrity = str(_scalar(connection, "PRAGMA integrity_check(1)"))
        if integrity != "ok":
            raise ValueError("PRAGMA integrity_check did not return ok; stop staging")
        fk_violations = sum(1 for _ in connection.execute("PRAGMA foreign_key_check"))
        if fk_violations:
            raise ValueError("Foreign-key violations found; stop staging")
        table_reports: dict[str, Any] = {}
        for table, columns in TABLES.items():
            available = _table_columns(connection, table)
            missing = set(columns) - available
            if missing:
                raise ValueError(f"{table} is missing required historical fields")
            count = int(_scalar(connection, f"SELECT COUNT(*) FROM {_sql_ident(table)}"))
            groups = {"core": _digest_rows(connection, table, columns)}
            if table == "block_changes" and set(CAUSAL_FIELDS) <= available:
                groups["causal"] = _digest_rows(connection, table, CAUSAL_FIELDS)
            table_reports[table] = {"rows": count, "sha256": groups}
        indices = sorted(r[0] for r in connection.execute(
            "SELECT name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_autoindex_%'"))
        return {
            "format": 1,
            "recorded_utc": datetime.now(timezone.utc).isoformat(),
            "schema_version": schema,
            "integrity_check": integrity,
            "foreign_key_violations": fk_violations,
            "tables": table_reports,
            "indexes": indices,
            "file_bytes": {
                "main_db": _file_size(db),
                "wal_at_inspection": _file_size(Path(str(db) + "-wal")),
                "shm_at_inspection": _file_size(Path(str(db) + "-shm")),
            },
            "limitations": (
                "Read-only offline copy; counts and full-row SHA-256 cover common historical fields. "
                "No actual server quota/native SQLite temp, hot-chunk timing, or peak WAL measurement."
            ),
        }


def compare(before: dict[str, Any], after: dict[str, Any]) -> dict[str, Any]:
    checks: list[dict[str, Any]] = []

    def check(name: str, passed: bool) -> None:
        checks.append({"check": name, "passed": bool(passed)})

    check("matching-manifest-format", before.get("format") == after.get("format") == 1)
    check("recognized-source-schema", before.get("schema_version") in (4, 7, 8))
    check("target-schema-v9", after.get("schema_version") == 9)
    for label, report in (("before", before), ("after", after)):
        check(label + "-integrity-check", report.get("integrity_check") == "ok")
        check(label + "-foreign-key-check", report.get("foreign_key_violations") == 0)

    for table in TABLES:
        original = before.get("tables", {}).get(table, {})
        migrated = after.get("tables", {}).get(table, {})
        check(table + "-row-count-preserved",
              isinstance(original.get("rows"), int)
              and original["rows"] == migrated.get("rows"))
        baseline = original.get("sha256", {})
        target = migrated.get("sha256", {})
        check(table + "-core-full-history-hash-preserved",
              isinstance(baseline.get("core"), str)
              and len(baseline["core"]) == 64
              and baseline["core"] == target.get("core"))
        if table == "block_changes" and before.get("schema_version") in (7, 8):
            check(table + "-causal-history-hash-preserved",
                  isinstance(baseline.get("causal"), str)
                  and len(baseline["causal"]) == 64
                  and baseline["causal"] == target.get("causal"))
    indexed = set(after.get("indexes", []))
    for name in sorted(REQUIRED_V9_INDEXES):
        check("v9-index-present-" + name, name in indexed)
    return {
        "format": 1,
        "passed": all(item["passed"] for item in checks),
        "checks": checks,
        "limitations": (
            "A passing offline comparison proves matching historical fields/counts "
            "on the two inspected copies only; independent storage quota, operational "
            "rollback functionality and Paper staging still need verification."
        ),
    }


def _json_read(file: Path) -> dict[str, Any]:
    data = json.loads(file.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError("Manifest is not a JSON object")
    return data


def _reject_alias_to_input(output: Path, protected: tuple[Path, ...]) -> None:
    """Fail closed for equal paths, symlinks, and existing hard links.

    resolve() alone cannot detect a different filename for the same inode.
    samefile() compares actual filesystem identity when both paths exist.
    An unexpected stat error aborts instead of risking any input file.
    """
    resolved_output = output.resolve()
    for source in protected:
        if resolved_output == source.resolve():
            raise ValueError("Output must not overwrite source database/sidecar/manifest")
        try:
            if output.samefile(source):
                raise ValueError("Output hard link points to a protected source file")
        except FileNotFoundError:
            # A new report or an absent WAL/SHM sidecar has no inode yet.
            continue


def _json_write(file: Path, data: dict[str, Any],
                protected: tuple[Path, ...]) -> None:
    # Recheck before writing, even if the CLI checked earlier. Write to a
    # freshly created temporary file, then atomically replace the report
    # path: no existing hard-linked or symlinked inode is ever truncated.
    _reject_alias_to_input(file, protected)
    file.parent.mkdir(parents=True, exist_ok=True)
    staged: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w", encoding="utf-8", dir=file.parent,
            prefix="." + file.name + ".", suffix=".tmp", delete=False
        ) as stream:
            staged = Path(stream.name)
            json.dump(data, stream, indent=2, sort_keys=True)
            stream.write("\n")
        _reject_alias_to_input(file, protected)
        os.replace(staged, file)
    finally:
        if staged is not None:
            staged.unlink(missing_ok=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subcommands = parser.add_subparsers(dest="command", required=True)
    inspect_parser = subcommands.add_parser("inspect", help="Manifest an isolated SQLite copy")
    inspect_parser.add_argument("--db", type=Path, required=True)
    inspect_parser.add_argument("--output", type=Path, required=True)
    inspect_parser.add_argument("--i-confirm-isolated-copy", action="store_true")
    compare_parser = subcommands.add_parser("compare", help="Compare two offline manifests")
    compare_parser.add_argument("--before", type=Path, required=True)
    compare_parser.add_argument("--after", type=Path, required=True)
    compare_parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "inspect":
            protected = (args.db, Path(str(args.db) + "-wal"),
                         Path(str(args.db) + "-shm"))
            _reject_alias_to_input(args.output, protected)
            if not args.i_confirm_isolated_copy:
                raise ValueError("Refusing inspection without explicit isolated-copy acknowledgement")
            report = inspect(args.db)
            _json_write(args.output, report, protected)
            print(f"Schema v{report['schema_version']} manifest written; "
                  "source copy opened read-only; no history values logged.")
        else:
            protected = (args.before, args.after)
            _reject_alias_to_input(args.output, protected)
            report = compare(_json_read(args.before), _json_read(args.after))
            _json_write(args.output, report, protected)
            print(f"Offline migration comparison: {'PASS' if report['passed'] else 'FAIL'}; "
                  f"{len(report['checks'])} checks.")
            if not report["passed"]:
                for item in report["checks"]:
                    if not item["passed"]:
                        print("FAILED: " + item["check"])
                return 2
        return 0
    except (ValueError, OSError, sqlite3.DatabaseError, json.JSONDecodeError) as exc:
        # Do not echo raw exceptions; sqlite messages can contain private DB paths.
        print("Offline validation failed; keep original snapshot unchanged. "
              f"Reason type: {type(exc).__name__}.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
