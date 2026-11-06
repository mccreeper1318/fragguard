#!/usr/bin/env python3
"""Verify runtime service providers in FragGuard's shaded JAR."""

import sys
from collections import Counter
from zipfile import ZipFile

EXPECTED_SERVICES = {
    "META-INF/services/org.slf4j.spi.SLF4JServiceProvider": "org.slf4j.nop.NOPServiceProvider",
}

# Paper already bundles SQLite JDBC at runtime. Mixing a shaded copy with
# Paper's classes makes stacktrace provenance ambiguous and can load different
# Java/native driver versions.
FORBIDDEN_SQLITE_ENTRIES = (
    "org/sqlite/",
    "META-INF/maven/org.xerial/sqlite-jdbc/",
    "META-INF/native-image/org.xerial/sqlite-jdbc/",
)


def main(jar_path: str) -> None:
    with ZipFile(jar_path) as archive:
        entries = Counter(archive.namelist())
        forbidden = [
            entry
            for entry in entries
            if any(entry.startswith(prefix) for prefix in FORBIDDEN_SQLITE_ENTRIES)
            or entry == "META-INF/services/java.sql.Driver"
            or entry == "sqlite-jdbc.properties"
        ]
        if forbidden:
            raise SystemExit(
                "The FragGuard JAR must NOT bundle SQLite JDBC; Paper supplies it. "
                + f"Found forbidden entries (up to 5): {forbidden[:5]}"
            )
        print("Verified: no SQLite JDBC classes, native libraries, or service provider shaded")

        for descriptor, provider in EXPECTED_SERVICES.items():
            if entries[descriptor] != 1:
                raise SystemExit(f"Expected exactly one {descriptor} in {jar_path}; found {entries[descriptor]}")

            providers = {
                line.split("#", 1)[0].strip()
                for line in archive.read(descriptor).decode("utf-8").splitlines()
            }
            if provider not in providers:
                raise SystemExit(f"{descriptor} is missing provider {provider}")

            provider_class = provider.replace(".", "/") + ".class"
            if entries[provider_class] != 1:
                raise SystemExit(f"Expected exactly one provider class {provider_class}; found {entries[provider_class]}")

            print(f"Verified {descriptor}: {provider}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: verify_shaded_services.py <FragGuard.jar>")
    main(sys.argv[1])
