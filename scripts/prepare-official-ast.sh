#!/usr/bin/env bash
set -euo pipefail

readonly ARCHIVE_URL='https://p9-arcosite.byteimg.com/tos-cn-i-goo7wpa0wc/fc0621afc7a34c7e83739e9cbff21360~tplv-goo7wpa0wc-image.image'
readonly ARCHIVE_SHA256='fb9c9059135acd4a49ceab6ee8c29032b79254f89f7341fb0668ecd0b7589eac'
readonly SOURCE_PREFIX='ast_go/protogen/'
readonly OLD_IMPORT='code.byted.org/data-speech/wsclientsdk/protogen'
readonly NEW_IMPORT='translator-agent/internal/officialastproto'

root_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
destination="$root_dir/agent/internal/officialastproto"
tmp_dir=$(mktemp -d "${TMPDIR:-/tmp}/translator-official-ast.XXXXXX")
trap 'rm -rf "$tmp_dir"' EXIT HUP INT TERM

archive="$tmp_dir/ast_go_client.zip"
staging="$tmp_dir/officialastproto"

curl --fail --location --silent --show-error --retry 2 --output "$archive" "$ARCHIVE_URL"

python3 - "$archive" "$ARCHIVE_SHA256" "$staging" "$SOURCE_PREFIX" "$OLD_IMPORT" "$NEW_IMPORT" <<'PY'
import hashlib
from pathlib import Path, PurePosixPath
import sys
import zipfile

archive = Path(sys.argv[1])
expected_sha256 = sys.argv[2]
staging = Path(sys.argv[3])
source_prefix = sys.argv[4]
old_import = sys.argv[5]
new_import = sys.argv[6]

actual_sha256 = hashlib.sha256(archive.read_bytes()).hexdigest()
if actual_sha256 != expected_sha256:
    raise SystemExit(
        f"official AST archive SHA256 mismatch: expected {expected_sha256}, got {actual_sha256}"
    )

expected_files = {
    "common/event/events.pb.go",
    "common/rpcmeta/rpcmeta.pb.go",
    "products/understanding/ast/ast_service.pb.go",
    "products/understanding/base/au_base.pb.go",
}
extracted_files = set()

with zipfile.ZipFile(archive) as bundle:
    for member in bundle.infolist():
        if member.is_dir() or not member.filename.startswith(source_prefix):
            continue
        relative = member.filename[len(source_prefix):]
        if not relative.endswith(".pb.go") or relative.endswith("_grpc.pb.go"):
            continue
        if relative not in expected_files:
            raise SystemExit(f"unexpected official protobuf file: {relative}")

        relative_path = PurePosixPath(relative)
        if relative_path.is_absolute() or ".." in relative_path.parts:
            raise SystemExit(f"unsafe archive path: {member.filename}")

        source = bundle.read(member).decode("utf-8")
        rewritten = source.replace(old_import, new_import)
        if old_import in rewritten:
            raise SystemExit(f"failed to rewrite imports in {relative}")

        output = staging.joinpath(*relative_path.parts)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(rewritten, encoding="utf-8")
        extracted_files.add(relative)

missing = expected_files - extracted_files
if missing:
    raise SystemExit(f"official archive is missing protobuf files: {', '.join(sorted(missing))}")
PY

rm -rf "$destination"
mkdir -p "$(dirname "$destination")"
mv "$staging" "$destination"
printf 'Prepared official AST protobuf files in %s\n' "$destination"
