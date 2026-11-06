"""Regression tests for the release JAR's SQLite classpath guard."""

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

SCRIPT = Path(__file__).with_name("verify_shaded_services.py")
PROVIDER = "META-INF/services/org.slf4j.spi.SLF4JServiceProvider"
PROVIDER_CLASS = "org/slf4j/nop/NOPServiceProvider.class"


class ShadedServiceVerifierTest(unittest.TestCase):
    def check_jar(self, extra_files=None):
        with tempfile.TemporaryDirectory() as tmp:
            jar = Path(tmp) / "FragGuard-test.jar"
            with ZipFile(jar, "w") as archive:
                archive.writestr(PROVIDER, "org.slf4j.nop.NOPServiceProvider\n")
                archive.writestr(PROVIDER_CLASS, b"placeholder")
                for name in extra_files or []:
                    archive.writestr(name, b"placeholder")
            return subprocess.run(
                [sys.executable, str(SCRIPT), str(jar)],
                text=True,
                capture_output=True,
                check=False,
            )

    def test_accepts_plugin_without_bundled_sqlite(self):
        result = self.check_jar()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("no SQLite JDBC classes", result.stdout)

    def test_rejects_shaded_sqlite_driver_class(self):
        result = self.check_jar(["org/sqlite/JDBC.class"])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("must NOT bundle SQLite JDBC", result.stderr + result.stdout)

    def test_rejects_shaded_sqlite_native_library(self):
        result = self.check_jar(["org/sqlite/native/Linux/x86_64/libsqlitejdbc.so"])
        self.assertNotEqual(result.returncode, 0)

    def test_rejects_sqlite_service_provider(self):
        result = self.check_jar(["META-INF/services/java.sql.Driver"])
        self.assertNotEqual(result.returncode, 0)

    def test_rejects_sqlite_maven_metadata(self):
        result = self.check_jar(["META-INF/maven/org.xerial/sqlite-jdbc/pom.properties"])
        self.assertNotEqual(result.returncode, 0)


if __name__ == "__main__":
    unittest.main()
