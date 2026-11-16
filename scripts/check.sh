#!/usr/bin/env bash
# Public verification entry. Never sources local credentials or deploys services.
set -uo pipefail
root_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root_dir" || exit 2
usage() {
  printf '%s\n' 'Usage: bash scripts/check.sh TARGET...' \
    'Targets: all android agent officialast cloud contract admin-web web download navigation tooling db manual' \
    'Exit: 0=selected checks passed (explicit SKIP allowed), 1=FAIL, 2=MISSING_ENV/invalid input.'
}
if [[ $# == 0 || ${1:-} == --help ]]; then usage; exit 0; fi
for target in "$@"; do
  case "$target" in
    all|android|agent|officialast|cloud|contract|admin-web|web|download|navigation|tooling|db|manual) ;;
    *) printf 'Unknown target: %s\n' "$target" >&2; usage >&2; exit 2 ;;
  esac
done
pass_count=0 fail_count=0 missing_count=0 skip_count=0
report() {
  local state=$1 name=$2 detail=$3
  printf '%s %s: %s\n' "$state" "$name" "$detail"
  case "$state" in
    PASS) pass_count=$((pass_count + 1));;
    FAIL) fail_count=$((fail_count + 1));;
    MISSING_ENV) missing_count=$((missing_count + 1));;
    SKIP) skip_count=$((skip_count + 1));;
  esac
}
need() {
  local tool
  for tool in "$@"; do
    if ! command -v "$tool" >/dev/null 2>&1; then
      report MISSING_ENV "$target" "required executable: $tool"
      return 1
    fi
  done
}
go_check() (
  cd "$1" || exit 1
  shift
  local package_output package buildable
  local packages=() build_packages=()
  package_output=$(go list -f '{{.ImportPath}}|{{if or .GoFiles .CgoFiles}}build{{end}}' "$@" ./...) || exit 1
  while IFS='|' read -r package buildable; do
    case "/$package/" in
      */node_modules/*) ;; # Frontend dependencies may include unrelated Go examples.
      *) if [[ -n $package ]]; then
           packages+=("$package")
           [[ -z $buildable ]] || build_packages+=("$package")
         fi ;;
    esac
  done <<< "$package_output"
  if [[ ${#packages[@]} == 0 ]]; then
    printf 'No current Go packages discovered.\n' >&2
    exit 1
  fi
  go test "$@" "${packages[@]}" &&
  go vet "$@" "${packages[@]}" &&
  { [[ ${#build_packages[@]} == 0 ]] || go build "$@" "${build_packages[@]}"; }
)
run_target() {
  target=$1
  case "$target" in
    all)
      local selected
      for selected in android agent officialast cloud contract admin-web web download navigation tooling; do
        run_target "$selected"
      done
      if [[ -n ${CLOUD_API_TEST_DATABASE_URL:-} ]]; then
        run_target db
      else
        report SKIP db 'optional dedicated PostgreSQL environment absent; explicit db target requires it'
      fi
      run_target manual ;;
    android)
      need java || return 0
      if [[ ! -f android/local.properties && ! -d ${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/nonexistent}}/platforms ]]; then
        report MISSING_ENV android 'Android SDK absent; set ANDROID_HOME or android/local.properties (see docs/development/workspace.md)'
        return 0
      fi
      if (cd android && bash ./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug); then
        report PASS android 'JVM unit tests / lint / Debug APK build; no device tests'
      else
        report FAIL android 'Gradle checks failed'
      fi ;;
    download)
      need python3 bash || return 0
      if python3 -m unittest discover -s scripts -p test_download_release.py && bash -n scripts/deploy-download-release.sh; then
        report PASS download 'local manifest/routes/snapshot unit tests and deploy script syntax; no deployment'
      else
        report FAIL download 'download tool checks failed'
      fi ;;
    navigation)
      need python3 || return 0
      if python3 scripts/check-navigation.py; then
        report PASS navigation 'current Markdown navigation local destinations exist; no URL/heading validation'
      else
        report FAIL navigation 'broken current local navigation destinations'
      fi ;;
    tooling)
      need python3 bash || return 0
      if (for script in scripts/*.sh; do bash -n "$script" || exit 1; done
          python3 -m unittest discover -s scripts -p test_checks.py &&
          python3 -m unittest discover -s scripts -p test_navigation.py &&
          python3 -m unittest discover -s scripts -p test_ci_scope.py); then
        report PASS tooling 'Bash syntax and verification CLI/navigation/CI selection behavioral tests'
      else
        report FAIL tooling 'Bash syntax or verification regression tests failed'
      fi ;;
    web|admin-web)
      need npm node || return 0
      local frontend=web
      [[ $target == admin-web ]] && frontend=cloud-api/admin-web
      if [[ ! -d $frontend/node_modules ]]; then
        report MISSING_ENV "$target" "missing $frontend/node_modules; run npm --prefix $frontend ci"
        return 0
      fi
      if (for step in test typecheck lint build; do npm --prefix "$frontend" run "$step" || exit 1; done); then
        report PASS "$target" 'npm test / typecheck / lint / build'
      else
        report FAIL "$target" 'frontend checks failed'
      fi ;;
    manual)
      report SKIP manual 'device/provider/audio/cross-device acceptance: docs/testing/manual-acceptance.md' ;;
    officialast)
      local generated
      for generated in common/event/events.pb.go common/rpcmeta/rpcmeta.pb.go products/understanding/ast/ast_service.pb.go products/understanding/base/au_base.pb.go; do
        if [[ ! -f agent/internal/officialastproto/$generated ]]; then
          report MISSING_ENV officialast 'generated protobuf dependency absent; run bash scripts/prepare-official-ast.sh (pinned network download)'
          return 0
        fi
      done
      need go || return 0
      if go_check agent -tags officialast; then
        report PASS officialast 'officialast-tagged go test / vet / build; no live provider request'
      else
        report FAIL officialast 'officialast-tagged go test / vet / build failed'
      fi ;;
    db)
      if [[ -z ${CLOUD_API_TEST_DATABASE_URL:-} ]]; then
        report MISSING_ENV db 'set CLOUD_API_TEST_DATABASE_URL to the dedicated 127.0.0.1:15432 test database'
        return 0
      fi
      need go python3 || return 0
      local log_file db_exit=0
      if ! log_file=$(mktemp "${TMPDIR:-/tmp}/dngmeng-db-check.XXXXXX"); then
        report FAIL db 'cannot create temporary integration report'
        return 0
      fi
      (cd cloud-api && go test -json -tags=integration -count=1 ./integration) >"$log_file" 2>&1 || db_exit=$?
      if ! python3 - "$log_file" <<'PYREPORT'
import json
from pathlib import Path
import sys
passed = skipped = 0
invalid = False
for line in Path(sys.argv[1]).read_text().splitlines():
    try:
        event = json.loads(line)
    except ValueError:
        print(line)
        invalid = True
        continue
    if event.get("Output"):
        print(event["Output"], end="")
    if event.get("Test") and event.get("Action") == "pass":
        passed += 1
    if event.get("Action") == "skip":
        skipped += 1
print(f"Database report: passed tests={passed}, skips={skipped}")
sys.exit(1 if invalid or skipped or not passed else 0)
PYREPORT
      then db_exit=1; fi
      rm -f "$log_file"
      if ((db_exit == 0)); then
        report PASS db 'dedicated PostgreSQL integration suite, including Cloud/Agent process harness; no skips'
      else
        report FAIL db 'integration suite failed, skipped, or produced no completed test evidence'
      fi ;;
    cloud|contract|agent)
      need go || return 0
      local module=$target
      [[ $target == cloud ]] && module=cloud-api
      [[ $target == contract ]] && module=cloud-api/contracttest
      if go_check "$module"; then
        report PASS "$target" 'go list ./... (excluding node_modules), then go test / vet / build all current packages'
      else
        report FAIL "$target" 'go test / vet / build failed (see output above)'
      fi ;;
  esac
}
for target in "$@"; do run_target "$target"; done
printf 'SUMMARY PASS=%d FAIL=%d MISSING_ENV=%d SKIP=%d\n' "$pass_count" "$fail_count" "$missing_count" "$skip_count"
((fail_count == 0)) || exit 1
((missing_count == 0)) || exit 2
