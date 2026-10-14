#!/usr/bin/env python3
"""Verify runtime service providers in FragGuard's shaded JAR."""

import sys
from collections import Counter
from zipfile import ZipFile

EXPECTED_SERVICES = {
    "META-INF/services/java.sql.Driver": "org.sqlite.JDBC",
    "META-INF/services/org.slf4j.spi.SLF4JServiceProvider": "org.slf4j.nop.NOPServiceProvider",
}


def main(jar_path: str) -> None:
    with ZipFile(jar_path) as archive:
        entries = Counter(archive.namelist())
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
