"""Tests for offline migration manifest and release-gate comparison."""
from __future__ import annotations

import importlib.util
import json
import os
import shutil
import sqlite3
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("validate_offline_migration.py")
SPEC = importlib.util.spec_from_file_location("offline_validation", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
validator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(validator)


class OfflineMigrationManifestTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name)
        self.before = self.path / "before-v7.db"
        self.after = self.path / "after-v9.db"
        self._seed(self.before)
        shutil.copyfile(self.before, self.after)
        self.index_definitions = {
            "idx_fg_spatial_time":
                "CREATE INDEX idx_fg_spatial_time ON block_changes(world_uuid, chunk_x, chunk_z, happened_at)",
            "idx_fg_actor_time":
                "CREATE INDEX idx_fg_actor_time ON block_changes(actor_uuid, happened_at)",
            "idx_fg_actor_world_time":
                "CREATE INDEX idx_fg_actor_world_time ON block_changes(actor_uuid, world_uuid, happened_at)",
            "idx_fg_actor_name_uuid":
                "CREATE INDEX idx_fg_actor_name_uuid ON block_changes(actor_name, actor_uuid)",
            "idx_fg_causal_actor_time":
                "CREATE INDEX idx_fg_causal_actor_time ON block_changes(causal_player_uuid, happened_at)",
            "idx_fg_causal_actor_world_time":
                "CREATE INDEX idx_fg_causal_actor_world_time ON block_changes(causal_player_uuid, world_uuid, happened_at)",
            "idx_fg_causal_name_uuid":
                "CREATE INDEX idx_fg_causal_name_uuid ON block_changes(causal_player_name, causal_player_uuid)",
            "idx_fg_job_chunk_overlap":
                "CREATE INDEX idx_fg_job_chunk_overlap ON rollback_job_chunks(job_id, world_uuid)",
        }
        self.assertEqual(set(validator.REQUIRED_V9_INDEXES), set(self.index_definitions))
        with sqlite3.connect(self.after) as db:
            db.execute("ALTER TABLE rollback_jobs ADD COLUMN scope_type TEXT DEFAULT 'RADIUS'")
            db.execute("ALTER TABLE rollback_job_changes ADD COLUMN world_uuid TEXT")
            db.execute("CREATE TABLE rollback_job_chunks (job_id INTEGER, world_uuid TEXT)")
            for index in self.index_definitions.values():
                db.execute(index)
            db.execute("PRAGMA user_version=9")

    @staticmethod
    def _seed(path):
        with sqlite3.connect(path) as db:
            for table, fields in validator.TABLES.items():
                types = {
                    "before_entity_data": "BLOB", "after_entity_data": "BLOB",
                    "target_entity_data": "BLOB", "expected_entity_data": "BLOB",
                    "applied_entity_data": "BLOB",
                }
                definitions = []
                for field in fields:
                    field_type = types.get(field, "TEXT")
                    if field == "id":
                        definitions.append("id INTEGER PRIMARY KEY")
                    else:
                        definitions.append(f'"{field}" {field_type}')
                if table == "block_changes":
                    definitions.extend(
                        f'"{field}" TEXT' for field in validator.CAUSAL_FIELDS[1:])
                db.execute(f"CREATE TABLE {table} ({', '.join(definitions)})")
                row = []
                for field in fields:
                    if field == "id":
                        value = 11
                    elif field == "job_id":
                        value = 7
                    elif field == "sequence":
                        value = 0
                    elif field.endswith("_entity_data"):
                        value = b"\x00\x11\xff"
                    elif field in ("world_uuid", "actor_uuid", "actor_name", "world"):
                        value = "dummy-world"
                    else:
                        value = "baseline"
                    row.append(value)
                placeholders = ",".join("?" for _ in fields)
                db.execute(f"INSERT INTO {table} ({','.join(fields)}) VALUES ({placeholders})", row)
                if table == "block_changes":
                    db.execute("UPDATE block_changes SET causal_player_uuid='dummy-player', "
                               "causal_player_name='Player', causal_chain_id='chain', "
                               "causal_cause_type='TNT_ENTITY'")
            db.execute("PRAGMA user_version=7")

    def test_read_only_full_history_hash_is_equal_after_v7_to_v9(self):
        before = validator.inspect(self.before)
        after = validator.inspect(self.after)
        self.assertEqual(7, before["schema_version"])
        self.assertEqual(9, after["schema_version"])
        self.assertEqual(1, before["tables"]["block_changes"]["rows"])
        self.assertEqual(1, before["tables"]["rollback_jobs"]["rows"])
        self.assertEqual(1, before["tables"]["rollback_job_changes"]["rows"])
        self.assertEqual("ok", before["integrity_check"])
        self.assertEqual("ok", after["integrity_check"])
        self.assertIn("causal", before["tables"]["block_changes"]["sha256"])
        self.assertTrue(validator.compare(before, after)["passed"])
        with sqlite3.connect(self.before) as db:
            self.assertEqual(7, db.execute("PRAGMA user_version").fetchone()[0])

    def test_changed_snapshot_is_detected_even_when_counts_match(self):
        before = validator.inspect(self.before)
        with sqlite3.connect(self.after) as db:
            db.execute("UPDATE rollback_job_changes SET target_entity_data=?",
                       (b"\x01\x02\x03",))
        after = validator.inspect(self.after)
        checks = validator.compare(before, after)
        self.assertFalse(checks["passed"])
        self.assertIn("rollback_job_changes-core-full-history-hash-preserved",
                      [item["check"] for item in checks["checks"] if not item["passed"]])

    def test_changed_causal_actor_is_detected(self):
        before = validator.inspect(self.before)
        with sqlite3.connect(self.after) as db:
            db.execute("UPDATE block_changes SET causal_player_uuid='different'")
        self.assertFalse(validator.compare(before, validator.inspect(self.after))["passed"])

    def test_every_required_v9_index_is_individually_a_release_gate(self):
        before = validator.inspect(self.before)
        for name, create_sql in self.index_definitions.items():
            with self.subTest(missing_index=name):
                with sqlite3.connect(self.after) as db:
                    db.execute(f"DROP INDEX {name}")
                result = validator.compare(before, validator.inspect(self.after))
                self.assertFalse(result["passed"], f"Missing {name} must fail")
                self.assertIn("v9-index-present-" + name,
                              [item["check"] for item in result["checks"]
                               if not item["passed"]])
                with sqlite3.connect(self.after) as db:
                    db.execute(create_sql)
        self.assertTrue(validator.compare(before, validator.inspect(self.after))["passed"])

    def test_no_approval_flag_refuses_inspection(self):
        report = self.path / "manifest.json"
        exit_code = validator.main(["inspect", "--db", str(self.before),
                                    "--output", str(report)])
        self.assertNotEqual(0, exit_code)
        self.assertFalse(report.exists())

    def test_never_overwrites_source_database_with_manifest(self):
        before_bytes = self.before.read_bytes()
        code = validator.main([
            "inspect", "--db", str(self.before), "--output", str(self.before),
            "--i-confirm-isolated-copy"
        ])
        self.assertNotEqual(0, code)
        self.assertEqual(before_bytes, self.before.read_bytes())

    def test_manifest_hard_link_to_db_is_rejected_and_source_kept_intact(self):
        report = self.path / "hardlinked-report.json"
        os.link(self.before, report)
        initial = self.before.read_bytes()
        self.assertEqual(2, validator.main([
            "inspect", "--db", str(self.before), "--output", str(report),
            "--i-confirm-isolated-copy",
        ]))
        self.assertEqual(initial, self.before.read_bytes())
        self.assertEqual(initial, report.read_bytes())
        with sqlite3.connect(self.before) as db:
            self.assertEqual(7, db.execute("PRAGMA user_version").fetchone()[0])

    def test_manifest_hard_link_to_matching_sqlite_sidecars_is_rejected(self):
        for sidecar_name in ("-wal", "-shm"):
            with self.subTest(sidecar=sidecar_name):
                sidecar = Path(str(self.before) + sidecar_name)
                sidecar.write_bytes(b"protected-sidecar-bytes")
                report = self.path / "sidecar-report.json"
                os.link(sidecar, report)
                try:
                    self.assertEqual(2, validator.main([
                        "inspect", "--db", str(self.before), "--output", str(report),
                        "--i-confirm-isolated-copy",
                    ]))
                    self.assertEqual(b"protected-sidecar-bytes", sidecar.read_bytes())
                    self.assertEqual(b"protected-sidecar-bytes", report.read_bytes())
                finally:
                    report.unlink()
                    sidecar.unlink()

    def test_compare_hard_link_to_input_manifest_is_rejected(self):
        before = self.path / "baseline.json"
        after = self.path / "upgraded.json"
        self.assertEqual(0, validator.main([
            "inspect", "--db", str(self.before), "--output", str(before),
            "--i-confirm-isolated-copy",
        ]))
        self.assertEqual(0, validator.main([
            "inspect", "--db", str(self.after), "--output", str(after),
            "--i-confirm-isolated-copy",
        ]))
        for target in (before, after):
            with self.subTest(source_manifest=target.name):
                output = self.path / "hardlinked-comparison.json"
                os.link(target, output)
                saved = target.read_bytes()
                try:
                    self.assertEqual(2, validator.main([
                        "compare", "--before", str(before), "--after", str(after),
                        "--output", str(output),
                    ]))
                    self.assertEqual(saved, target.read_bytes())
                    self.assertEqual(saved, output.read_bytes())
                finally:
                    output.unlink()

    def test_manifest_symlink_to_db_is_rejected(self):
        report = self.path / "report-symlink.json"
        report.symlink_to(self.before)
        original = self.before.read_bytes()
        self.assertEqual(2, validator.main([
            "inspect", "--db", str(self.before), "--output", str(report),
            "--i-confirm-isolated-copy",
        ]))
        self.assertEqual(original, self.before.read_bytes())

    def test_manifest_cli_contains_no_database_path_or_raw_actor_values(self):
        report = self.path / "baseline.json"
        rc = validator.main(["inspect", "--db", str(self.before),
                             "--output", str(report), "--i-confirm-isolated-copy"])
        self.assertEqual(0, rc)
        body = report.read_text()
        self.assertNotIn(str(self.path), body)
        self.assertNotIn("dummy-player", body)
        self.assertNotIn("Player", body)
        self.assertEqual(7, json.loads(body)["schema_version"])

    def test_compare_cli_does_not_overwrite_input_and_fails_mismatched_hash(self):
        before = self.path / "baseline.json"
        after = self.path / "upgraded.json"
        validator.main(["inspect", "--db", str(self.before),
                        "--output", str(before), "--i-confirm-isolated-copy"])
        validator.main(["inspect", "--db", str(self.after),
                        "--output", str(after), "--i-confirm-isolated-copy"])
        self.assertNotEqual(0, validator.main([
            "compare", "--before", str(before), "--after", str(after),
            "--output", str(before)]))
        with sqlite3.connect(self.after) as db:
            db.execute("UPDATE block_changes SET actor_name='tampered'")
        validator.main(["inspect", "--db", str(self.after),
                        "--output", str(after), "--i-confirm-isolated-copy"])
        result = self.path / "comparison.json"
        self.assertEqual(2, validator.main([
            "compare", "--before", str(before), "--after", str(after),
            "--output", str(result)]))
        self.assertFalse(json.loads(result.read_text())["passed"])


if __name__ == "__main__":
    unittest.main()
