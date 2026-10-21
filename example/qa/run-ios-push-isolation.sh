#!/bin/bash
# Native/JS integration regression against a checksummed runtime release.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
RUNTIME_DIST=$(cd "${1:?pass an extracted callkit-simulator runtime release}" && pwd)
if [[ -n "${OUT:-}" ]]; then mkdir "$OUT"; else OUT=$(mktemp -d "${TMPDIR:-/tmp}/callkit-push-qa.XXXXXX"); fi
OUT=$(cd "$OUT" && pwd)
BUILD=$(mktemp -d "${TMPDIR:-/tmp}/callkit-push-build.XXXXXX")
DERIVED=${QA_DERIVED_DATA:-$BUILD/DerivedData}
UDID=""
PHASE=preflight
limit() { perl -e 'alarm shift; exec @ARGV' "$@"; }
run_stage() {
  PHASE=$1
  local budget=$2
  shift 2
  local started status
  started=$(date +%s)
  if limit "$budget" "$@" > "$OUT/$PHASE.log" 2>&1; then status=0; else status=$?; fi
  printf 'phase=%s elapsedSeconds=%s exitStatus=%s\n' "$PHASE" "$(( $(date +%s) - started ))" "$status" >> "$OUT/stages.txt" || true
  return "$status"
}
cleanup() {
  local scenario_status=$?
  local status=$scenario_status
  trap - EXIT
  if [[ "$status" -ne 0 ]]; then
    printf 'phase=%s scenarioExitStatus=%s\n' "$PHASE" "$scenario_status" >&2 || true
    # This log ends before app launch. Select compiler/linker diagnostics only;
    # never print the app test log, which can contain its PushKit token.
    if [[ "$PHASE" == native-build ]]; then
      python3 - "$OUT/build.log" "$OUT/build-failures.txt" <<'PYDIAGNOSTICS' || true
import pathlib, re, sys
source, target = map(pathlib.Path, sys.argv[1:])
pattern = re.compile(r"(?:^|\s)(?:fatal )?error:|^ld:|^Undefined symbols|^\*\* .*FAILED \*\*")
lines = [line for line in source.read_text(errors="replace").splitlines() if pattern.search(line)]
target.write_text("\n".join(lines[-100:]) + "\n")
print(target.read_text(), file=sys.stderr)
PYDIAGNOSTICS
    fi
  fi
  if [[ -n "$UDID" ]]; then
    limit 20 xcrun simctl shutdown "$UDID" >> "$OUT/cleanup.log" 2>&1 || true
    if ! limit 20 xcrun simctl delete "$UDID" >> "$OUT/cleanup.log" 2>&1; then
      [[ "$status" -ne 0 ]] || status=1
    fi
  fi
  if ! rm -rf "$BUILD"; then
    [[ "$status" -ne 0 ]] || status=1
  fi
  printf 'phase=%s scenarioExitStatus=%s exitStatus=%s\n' "$PHASE" "$scenario_status" "$status" > "$OUT/completion.txt" || true
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
echo "Private QA evidence: $OUT"
[[ -z "$(git -C "$ROOT" status --porcelain -- ios src plugin example/client example/qa package.json bun.lock app.plugin.js expo-module.config.json)" ]] || { echo "Commit library source changes first" >&2; exit 1; }
{ git -C "$ROOT" rev-parse HEAD; shasum -a 256 "$RUNTIME_DIST/CKSimRuntime.dylib"; xcodebuild -version; node --version; bun --version; pod --version; ruby --version; xcrun -sdk iphonesimulator --show-sdk-version; } > "$OUT/environment.txt"
SDK=$(xcrun -sdk iphonesimulator --show-sdk-path)
FORCE_LEGACY=${QA_FORCE_LEGACY_PUSH:-0}
[[ "$FORCE_LEGACY" == 0 || "$FORCE_LEGACY" == 1 ]] || { echo "QA_FORCE_LEGACY_PUSH must be 0 or 1" >&2; exit 1; }
echo "forceLegacyPush=$FORCE_LEGACY" >> "$OUT/environment.txt"
METADATA=0
PUSH_API=legacy
[[ ! -f "$SDK/System/Library/Frameworks/PushKit.framework/Headers/PKVoIPPushMetadata.h" ]] || METADATA=1
[[ "$FORCE_LEGACY" -ne 1 ]] || METADATA=0
[[ "$METADATA" -ne 1 ]] || PUSH_API=metadata
EXPECTED_PUSH_API=${QA_EXPECT_PUSH_API:-auto}
[[ "$EXPECTED_PUSH_API" == auto || "$EXPECTED_PUSH_API" == metadata || "$EXPECTED_PUSH_API" == legacy ]] || { echo "QA_EXPECT_PUSH_API must be metadata or legacy" >&2; exit 1; }
echo "pushAPI=$PUSH_API expectedPushAPI=$EXPECTED_PUSH_API" >> "$OUT/environment.txt"
[[ "$EXPECTED_PUSH_API" == auto || "$EXPECTED_PUSH_API" == "$PUSH_API" ]] || { echo "Expected $EXPECTED_PUSH_API callback, SDK/config selected $PUSH_API" >&2; exit 1; }
PHASE=runtime-validation
[[ -f "$RUNTIME_DIST/profiles/ios-27.0.json" && -f "$RUNTIME_DIST/CKSimRuntime.dylib" ]] || { echo "Incomplete runtime release" >&2; exit 1; }
codesign --verify --strict "$RUNTIME_DIST/CKSimRuntime.dylib"
RUNTIME=$(xcrun simctl list runtimes -j | python3 -c 'import json,sys; r=[x for x in json.load(sys.stdin)["runtimes"] if x.get("platform")=="iOS" and x["isAvailable"]]; print(sorted(r,key=lambda x:[int(p) for p in x["version"].split(".")])[-1]["identifier"])')
echo "simulatorRuntime=$RUNTIME pushAPI=$PUSH_API" >> "$OUT/environment.txt"
UDID=$(xcrun simctl create "callkit-library-qa-$$" com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro "$RUNTIME")
echo "runOwnedSimulator=$UDID" >> "$OUT/environment.txt"
PHASE=simulator-boot
limit 30 xcrun simctl boot "$UDID"
limit 300 xcrun simctl bootstatus "$UDID" -b >> "$OUT/build.log" 2>&1
INHERITED=${SIMCTL_CHILD_DYLD_INSERT_LIBRARIES-$(xcrun simctl spawn "$UDID" launchctl getenv DYLD_INSERT_LIBRARIES 2>/dev/null || true)}
LIBS="${INHERITED:+$INHERITED:}$RUNTIME_DIST/CKSimRuntime.dylib"
PHASE=target-generation
ruby "$ROOT/example/qa/add-ui-tests.rb" "$ROOT/example/client/ios/CallKitTel.xcodeproj" "$LIBS" "$PUSH_API" "$OUT/aut-container.txt"
cd "$ROOT/example/client/ios"
COMMON=(-workspace CallKitTel.xcworkspace -scheme CallKitTel -configuration Release -sdk iphonesimulator -derivedDataPath "$DERIVED" CODE_SIGNING_ALLOWED=NO ARCHS="$(uname -m)" ONLY_ACTIVE_ARCH=YES)
if [[ "$FORCE_LEGACY" == 1 ]]; then
  COMMON+=('OTHER_CFLAGS=$(inherited) -DCALLKIT_TELECOM_LEGACY_PUSH_ONLY=1')
fi
PHASE=native-build
limit 900 xcodebuild "${COMMON[@]}" -destination 'generic/platform=iOS Simulator' build-for-testing >> "$OUT/build.log" 2>&1
run_stage app-install 180 xcrun simctl install "$UDID" "$DERIVED/Build/Products/Release-iphonesimulator/CallKitTel.app"
run_stage permissions 60 xcrun simctl privacy "$UDID" grant microphone com.example.expocallkittelecom
xcrun simctl get_app_container "$UDID" com.example.expocallkittelecom data > "$OUT/aut-container.txt"
TESTS=()
EXPECTED_TESTS=1
if [[ "$PUSH_API" == metadata ]]; then
  TESTS+=(-only-testing:PushIsolationUITests/PushIsolationUITests/testOptionalMalformedPushPreservesConnectedCall)
  TESTS+=(-only-testing:PushIsolationUITests/PushIsolationUITests/testRequiredMalformedPushPreservesConnectedCall)
  EXPECTED_TESTS=2
else
  TESTS+=(-only-testing:PushIsolationUITests/PushIsolationUITests/testLegacyMalformedPushPreservesConnectedCall)
fi
PHASE=ui-test
limit 180 xcodebuild "${COMMON[@]}" -destination "id=$UDID" -parallel-testing-enabled NO -collect-test-diagnostics never \
  "${TESTS[@]}" -resultBundlePath "$OUT/PushIsolation.xcresult" test-without-building > "$OUT/test.log" 2>&1
xcrun xcresulttool get test-results summary --path "$OUT/PushIsolation.xcresult" > "$OUT/test-summary.json"
python3 - "$OUT/test-summary.json" "$EXPECTED_TESTS" <<'PYRESULT'
import json,sys
r=json.load(open(sys.argv[1])); expected=int(sys.argv[2])
assert r.get('passedTests')==expected and r.get('failedTests')==0 and r.get('skippedTests')==0, 'Exact XCTest pass/skip gate failed'
PYRESULT
PHASE=passed
echo "XCTest passed; private result bundle: $OUT/PushIsolation.xcresult"
