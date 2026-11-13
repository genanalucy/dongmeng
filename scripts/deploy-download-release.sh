#!/usr/bin/env bash
# Publish a prepared dual-channel snapshot. Never rebuild/sign an APK here.
set -euo pipefail
if [[ $# -lt 1 || $# -gt 2 || ! -f "$1/SHA256SUMS" || ! -d "$1/files" || ! -d "$1/snapshot" ]]; then
  echo 'Usage: deploy-download-release.sh <prepared-bundle-directory>' >&2
  exit 2
fi
if [[ $# == 2 && $2 != --bootstrap ]]; then echo 'Unknown option' >&2; exit 2; fi
bootstrap=${2:-}
bundle=$(cd "$1" && pwd)
(cd "$bundle" && sha256sum --check SHA256SUMS)
# Fixed operator-controlled destination; SSH keys/config stay outside the repo.
remote=hzh@47.129.170.16
id="downloads-$(date -u +%Y%m%dT%H%M%SZ)-$$"
scp -o BatchMode=yes -o ConnectTimeout=10 -r "$bundle" "$remote:/tmp/$id"
ssh -o BatchMode=yes -o ConnectTimeout=10 "$remote" "sudo bash -s -- '$id'" <<'REMOTE'
set -euo pipefail
id=$1
[[ $id =~ ^downloads-[0-9TZ]+-[0-9]+$ ]]
stage="/tmp/$id"
exec 9>/var/lock/verba-download-release.lock
flock -x 9
cd "$stage"
sha256sum --check SHA256SUMS
python3 - "$stage" <<'PY'
import hashlib,json,sys
from pathlib import Path
p=Path(sys.argv[1]); m=json.loads((p/'snapshot/releases.json').read_text())
assert set(m['channels'])=={'production','debug'}
for channel,package in [('production','com.verba.interpretation'),('debug','com.verba.interpretation.debug')]:
 d=m['channels'][channel]; u=json.loads((p/'snapshot'/f'{channel}.json').read_text())
 assert d['package_name']==u['package_name']==package
 assert d['version_code']==u['version_code'] and d['sha256']==u['apk_sha256']
 assert u['apk_url']==d['download_url']
 assert Path(d['file_name']).name==d['file_name'] and d['file_name'].endswith('.apk')
 assert hashlib.sha256((p/'files'/d['file_name']).read_bytes()).hexdigest()==d['sha256']
PY
install -d -m 755 /opt/dngmeng/downloads/files /opt/dngmeng/downloads/snapshots
# Immutable artifacts: existing version URLs may never silently change content.
for source in "$stage"/files/*.apk; do
  target="/opt/dngmeng/downloads/files/$(basename "$source")"
  if [[ -e $target ]]; then cmp --silent "$source" "$target"; fi
done
python3 - "$stage" <<'PY'
import json,sys
from pathlib import Path
current=Path('/opt/dngmeng/downloads/current/releases.json')
if current.exists():
 old=json.loads(current.read_text()); new=json.loads((Path(sys.argv[1])/'snapshot/releases.json').read_text())
 for channel in ('production','debug'):
  a,b=old['channels'][channel],new['channels'][channel]
  assert b['version_code']>=a['version_code'], 'Implicit channel downgrade refused; use reviewed rollback'
  if b['version_code']==a['version_code']: assert b['sha256']==a['sha256'], 'Same-version replacement refused'
PY
for source in "$stage"/files/*.apk; do
  target="/opt/dngmeng/downloads/files/$(basename "$source")"
  if [[ ! -e $target ]]; then
    install -m 644 "$source" "$target.part"
    mv "$target.part" "$target"
  fi
done
snapshot="/opt/dngmeng/downloads/snapshots/$id"
install -d -m 755 "$snapshot"
install -m 644 "$stage"/snapshot/* "$snapshot/"
# All files first; one atomic pointer switches page and both OTA channels together.
previous=$(readlink /opt/dngmeng/downloads/current || true)
printf '%s\n' "$previous" > "$snapshot/previous-snapshot.txt"
ln -s "$snapshot" "/opt/dngmeng/downloads/current.next-$id"
mv -Tf "/opt/dngmeng/downloads/current.next-$id" /opt/dngmeng/downloads/current
printf 'Published snapshot: %s\nPrevious snapshot: %s\n' "$snapshot" "${previous:-none}"
REMOTE
if [[ $bootstrap == --bootstrap ]]; then
  echo 'Initial snapshot staged; install/validate Caddy routes and verify public endpoints before declaring rollout complete.'
  exit 0
fi
# Origin checks use the same current pointer; a public network/CF error is not a
# reason to silently remove a correctly published snapshot. Report it explicitly.
python3 - "$bundle" <<'PY'
import hashlib,json,subprocess,sys,tempfile
from pathlib import Path
base='https://47-129-170-16.sslip.io'
expected=json.loads((Path(sys.argv[1])/'snapshot/releases.json').read_text())
manifest=json.loads(subprocess.check_output(['curl','-fsS','--max-time','20',base+'/download/releases.json']))
assert manifest==expected, 'Public manifest does not match this bundle'
update=json.loads(subprocess.check_output(['curl','-fsS','--max-time','20',base+'/api/v1/app-update']))
assert update==json.loads((Path(sys.argv[1])/'snapshot/debug.json').read_text()), 'Debug OTA differs from bundle'
production=json.loads(subprocess.check_output(['ssh','-o','BatchMode=yes','-o','ConnectTimeout=10','hzh@47.129.170.16',
    'curl -fsS --resolve yszn.nlplayer.org:443:127.0.0.1 https://yszn.nlplayer.org/api/v1/app-update']))
assert production==json.loads((Path(sys.argv[1])/'snapshot/production.json').read_text()), 'Production origin OTA differs from bundle'
with tempfile.TemporaryDirectory() as tmp:
 for channel,item in expected['channels'].items():
  apk=Path(tmp)/(channel+'.apk')
  subprocess.run(['curl','-fLsS','--max-time','180',item['download_url'],'-o',str(apk)],check=True)
  assert apk.stat().st_size==item['size_bytes'] and hashlib.sha256(apk.read_bytes()).hexdigest()==item['sha256'], 'Public APK differs from bundle'
page=subprocess.check_output(['curl','-fsS','--max-time','20',base+'/download'],text=True)
assert page==(Path(sys.argv[1])/'snapshot/index.html').read_text(), 'Public page differs from bundle'
print('Both public APK hashes, page, manifest, Debug OTA and production-origin OTA match this bundle. Verify production public/Cloudflare path separately.')
PY
