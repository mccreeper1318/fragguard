#!/usr/bin/env python3
"""Strict FragGuard release-tag classification, shared by CI and release packaging.

Emits TSV: base_version, release_version, is_prerelease.
Only a positive -h.N suffix represents a stable hotfix. Beta/RC tags remain
prereleases; numeric/ambiguous hotfix suffixes are never silently accepted.
"""
import re
import sys

BASE = r"([0-9]+\.[0-9]+-[0-9]+\.[0-9]+\.[0-9]+)"
TAG = re.compile(rf"^v?{BASE}(?:-([0-9A-Za-z]+(?:\.[0-9A-Za-z]+)*))?$")
HOTFIX = re.compile(r"h\.[1-9][0-9]*")


def parse_tag(raw_tag: str) -> tuple[str, str, bool]:
    match = TAG.fullmatch(raw_tag)
    if match is None:
        raise ValueError(f"Invalid FragGuard release tag: {raw_tag!r}")
    base, suffix = match.groups()
    version = base + (f"-{suffix}" if suffix is not None else "")
    if suffix is None or HOTFIX.fullmatch(suffix):
        return base, version, False
    if suffix == "h" or suffix.startswith("h."):
        raise ValueError("Hotfix suffix must be -h.1, -h.2, ... (positive integer)")
    return base, version, True


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("Usage: release_tag_policy.py <tag>")
    try:
        base, version, prerelease = parse_tag(sys.argv[1])
    except ValueError as error:
        sys.exit(str(error))
    print(f"{base}\t{version}\t{str(prerelease).lower()}")
