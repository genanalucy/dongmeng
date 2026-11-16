#!/usr/bin/env python3
"""Validate both published APKs and build one website/OTA snapshot (no credentials)."""
import argparse
import hashlib
import html
import json
import re
import shutil
import string
import subprocess
from pathlib import Path
from urllib.parse import urlsplit

PACKAGES = {"production": "com.verba.interpretation", "debug": "com.verba.interpretation.debug"}
ROOT = Path(__file__).resolve().parent.parent


def validate_manifest(manifest, base_url):
    url = urlsplit(base_url)
    if url.scheme != "https" or not url.netloc or url.username or url.password or url.query or url.fragment or url.path not in ("", "/"):
        raise ValueError("base URL must be a credential-free HTTPS origin")
    if set(manifest["channels"]) != set(PACKAGES):
        raise ValueError("both production and debug channels are required")
    for channel, package in PACKAGES.items():
        item = manifest["channels"][channel]
        if item["package_name"] != package or type(item["version_code"]) is not int or item["version_code"] <= 0:
            raise ValueError("wrong package/channel or version code")
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*\.apk", item["file_name"]):
            raise ValueError("unsafe APK filename")
        for key in ("sha256", "certificate_sha256"):
            if not re.fullmatch(r"[0-9a-f]{64}", item[key]):
                raise ValueError("invalid digest")
    names = [item["file_name"] for item in manifest["channels"].values()]
    if len(set(names)) != len(names):
        raise ValueError("channels must use different immutable files")


def verify_apk(path, item, tools):
    if hashlib.sha256(path.read_bytes()).hexdigest() != item["sha256"]:
        raise ValueError("APK SHA-256 mismatch")
    result = subprocess.run([str(tools / "apksigner"), "verify", "--print-certs", str(path)], capture_output=True, text=True, check=True)
    certificates = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)", result.stdout)
    if certificates != [item["certificate_sha256"]]:
        raise ValueError("APK signing certificate mismatch")
    badging = subprocess.run([str(tools / "aapt"), "dump", "badging", str(path)], capture_output=True, text=True, check=True).stdout
    match = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
    if not match or match.groups() != (item["package_name"], str(item["version_code"]), item["version_name"]):
        raise ValueError("APK package/version mismatch")


def build_snapshot(manifest, apk_dir, output, base_url, tools, template):
    validate_manifest(manifest, base_url)
    if output.exists():
        raise ValueError("output directory must not exist")
    # Validate every input before creating a snapshot that could be deployed.
    for channel, item in manifest["channels"].items():
        verify_apk(apk_dir / (channel + ".apk"), item, tools)
    (output / "files").mkdir(parents=True)
    (output / "snapshot").mkdir()
    values = {"updated_at": html.escape(manifest["updated_at"])}
    verified = json.loads(json.dumps(manifest))
    for channel, item in verified["channels"].items():
        source = apk_dir / (channel + ".apk")
        shutil.copyfile(source, output / "files" / item["file_name"])
        item["download_url"] = base_url.rstrip("/") + "/downloads/" + item["file_name"]
        item["size_bytes"] = source.stat().st_size
        for key in ("version_code", "version_name", "sha256", "notes"):
            values[channel + "_" + key] = html.escape(str(item[key]))
        values[channel + "_url"] = html.escape(item["download_url"], quote=True)
        values[channel + "_size"] = f"{item['size_bytes'] / 1024 / 1024:.1f} MB"
        update = {"available": True, "package_name": item["package_name"], "version_code": item["version_code"],
                  "version_name": item["version_name"], "apk_url": item["download_url"], "apk_sha256": item["sha256"],
                  "release_notes": item["notes"], "force_update": False}
        (output / "snapshot" / (channel + ".json")).write_text(json.dumps(update, ensure_ascii=False, indent=2) + "\n")
    page = string.Template(template.read_text()).substitute(values)
    (output / "snapshot" / "index.html").write_text(page)
    (output / "snapshot" / "releases.json").write_text(json.dumps(verified, ensure_ascii=False, indent=2) + "\n")
    (output / "SHA256SUMS").write_text("".join(f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.relative_to(output)}\n" for p in sorted(output.rglob("*")) if p.is_file()))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=ROOT / "site/download/releases.json")
    parser.add_argument("--apk-dir", type=Path, required=True, help="verified production.apk and debug.apk inputs")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--build-tools", type=Path, required=True)
    args = parser.parse_args()
    build_snapshot(json.loads(args.manifest.read_text()), args.apk_dir, args.output, args.base_url, args.build_tools,
                   ROOT / "site/download/index.template.html")
    print("Validated both APK identities, versions, signatures and hashes; website/OTA snapshot prepared.")


if __name__ == "__main__":
    main()
