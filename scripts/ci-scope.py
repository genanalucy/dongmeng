#!/usr/bin/env python3
"""Read NUL-separated Git paths on stdin; emit the CI check selection as JSON."""
import json
import sys
import argparse

ORDER = ["agent", "officialast", "cloud", "contract", "admin-web", "web", "download", "navigation", "tooling"]
PROVIDERS = {"agent", "officialast", "cloud", "contract", "admin-web", "web"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--all", action="store_true", help="manual run or unavailable comparison base")
    args = parser.parse_args()
    paths = [] if args.all else sys.stdin.buffer.read().decode("utf-8", errors="surrogateescape").split("\0")
    targets = {"navigation"}
    consumers = android = False
    if args.all:
        targets.update(ORDER)
        consumers = android = True
    for path in paths:
        if not path or path.startswith(("docs/", "archive/")) or path.endswith((".md", ".rst", ".txt")):
            continue
        if path.startswith("android/"):
            android = True
        elif path.startswith("cloud-api/admin-web/"):
            targets.add("admin-web")
        elif path.startswith("web/"):
            targets.add("web")
        elif path.startswith(("agent/", "cloud-api/", "migrations/")) or path in ("docker-compose.yml", "scripts/prepare-official-ast.sh"):
            targets.update(PROVIDERS)
            consumers = True
        elif path.startswith("site/"):
            targets.add("download")
        elif path.startswith("scripts/") and path not in ("scripts/check.sh", "scripts/ci-scope.py"):
            targets.update(("download", "tooling"))
        elif path.startswith(("branding/", "ui/")):
            continue
        elif path.startswith((".pi/", "openspec/")) or path in (".gitignore", ".gitattributes"):
            targets.add("tooling")
        else:
            # CI/check infrastructure and unrecognised active source must not silently miss consumers.
            targets.update(ORDER)
            consumers = True
    scope = {"targets": [target for target in ORDER if target in targets],
             "db": consumers, "android": consumers or android, "motion": consumers or android}
    print(json.dumps(scope))


if __name__ == "__main__":
    main()
