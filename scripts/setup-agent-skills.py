#!/usr/bin/env python3
"""Restore the repository's pinned, external Pi skills without overwriting caches."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parent.parent


def git(*args):
    return subprocess.run(["git", *map(str, args)], check=True, text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout.strip()


def verify_links(cache, destination):
    links = sorted((ROOT / ".pi/skills").glob("mattpocock-*"))
    if not links:
        raise ValueError("No mattpocock skill links found")
    for link in links:
        if not link.is_symlink():
            raise ValueError(f"Expected skill symlink: {link.name}")
        target = Path(os.path.abspath(link.parent / os.readlink(link)))
        relative = target.relative_to(destination)
        if not (cache / relative / "SKILL.md").is_file():
            raise ValueError(f"Missing SKILL.md for {link.name}")
    return len(links)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Verify installation without network or writes")
    parser.add_argument("--source", help="Clone a local Git repository or mirror instead of the upstream URL")
    args = parser.parse_args()
    config = json.loads((ROOT / ".pi/skill-sources.json").read_text())
    destination = ROOT / config["destination"]
    revision = config["revision"]
    if destination.exists():
        if git("-C", destination, "rev-parse", "HEAD") != revision:
            raise ValueError("Existing cache has a different revision; preserve it elsewhere before reinstalling")
        if git("-C", destination, "status", "--porcelain"):
            raise ValueError("Existing cache has local changes; preserve it elsewhere before reinstalling")
        count = verify_links(destination, destination)
    elif args.check:
        raise ValueError("Skill cache missing; run python3 scripts/setup-agent-skills.py")
    else:
        destination.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix=".skills-install-", dir=destination.parent) as temp:
            checkout = Path(temp) / "checkout"
            git("clone", "--no-hardlinks", "--no-checkout", "--", args.source or config["repository"], checkout)
            git("-C", checkout, "checkout", "--detach", revision)
            count = verify_links(checkout, destination)
            checkout.rename(destination)
    print(f"Verified {count} skill links at {revision}")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Skill setup failed: {error}", file=sys.stderr)
        sys.exit(1)
