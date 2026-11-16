#!/usr/bin/env python3
"""Check local Markdown destinations in current navigation (or explicit files).

This is a file/link check, not a Markdown renderer, heading validator, code
reference scanner, or network crawler. Historical prose is deliberately absent
from the default entry list. No file-content allowlist hides broken links.
"""
from pathlib import Path
import re
import sys
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]


def destinations(text):
    fence = None
    for line in text.splitlines():
        marker = re.match(r'^\s*(`{3,}|~{3,})', line)
        if marker:
            if fence is None:
                fence = marker[1][0]
            elif marker[1][0] == fence:
                fence = None
            continue
        if fence:
            continue
        line = re.sub(r'`[^`]*`', '', line)
        # Inline images/links and reference-style link definitions.
        for match in re.finditer(r'!?\[[^\]]*\]\(\s*(<[^>]+>|[^\s)]+)', line):
            yield match[1].strip('<>')
        reference = re.match(r'^\s*\[[^\]]+\]:\s*(<[^>]+>|\S+)', line)
        if reference:
            yield reference[1].strip('<>')


def main():
    if sys.argv[1:]:
        pages = [Path(arg).resolve() for arg in sys.argv[1:]]
    else:
        pages = [ROOT / name for name in ('README.md', 'AGENTS.md')]
        pages += [ROOT / name for name in (
            'CONTEXT.md', 'docs/README.md', 'docs/development/workspace.md',
            'archive/README.md') if (ROOT / name).exists()]
        for directory in ('docs/testing', 'docs/architecture'):
            pages += sorted((ROOT / directory).glob('*.md'))
        pages += sorted((ROOT / 'archive').glob('*/README.md'))
    errors = checked = 0
    for page in dict.fromkeys(pages):
        if not page.is_file():
            print(f'BROKEN {page}: document not found')
            errors += 1
            continue
        for destination in destinations(page.read_text(encoding='utf-8')):
            parts = urlsplit(destination)
            if parts.scheme or parts.netloc or not parts.path:
                continue
            checked += 1
            path = unquote(parts.path)
            target = ROOT / path.lstrip('/') if path.startswith('/') else page.parent / path
            if not target.exists():
                print(f'BROKEN {page}: {destination}')
                errors += 1
    print(f'Navigation: documents={len(set(pages))}, local destinations={checked}, broken={errors}')
    return 1 if errors else 0


if __name__ == '__main__':
    sys.exit(main())
