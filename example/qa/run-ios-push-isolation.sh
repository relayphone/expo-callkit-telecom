#!/bin/bash
# Native/JS integration regression; uses the discovery spike until runtime #2 ships.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SIM_RUNTIME=$(cd "${1:?pass a callkit-simulator checkout}" && pwd)
if [[ -n "${OUT:-}" ]]; then mkdir "$OUT"; else OUT=$(mktemp -d "${TMPDIR:-/tmp}/callkit-push-qa.XXXXXX"); fi
OUT=$(cd "$OUT" && pwd)
BUILD=$(mktemp -d "${TMPDIR:-/tmp}/callkit-push-build.XXXXXX")
DERIVED=${QA_DERIVED_DATA:-$BUILD/DerivedData}
UDID=""
limit() { perl -e 'alarm shift; exec @ARGV' "$@"; }
cleanup() {
  local status=$?
  trap - EXIT
  if [[ -n "$UDID" ]]; then
    limit 20 xcrun simctl shutdown "$UDID" >> "$OUT/cleanup.log" 2>&1 || true
    if ! limit 20 xcrun simctl delete "$UDID" >> "$OUT/cleanup.log" 2>&1; then
      [[ "$status" -ne 0 ]] || status=1
    fi
  fi
  rm -rf "$BUILD"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
echo "Private QA evidence: $OUT"
[[ -z "$(git -C "$SIM_RUNTIME" status --porcelain -- discovery)" ]] || { echo "Commit runtime fixture changes first" >&2; exit 1; }
[[ -z "$(git -C "$ROOT" status --porcelain -- ios src plugin example/client example/qa package.json bun.lock app.plugin.js expo-module.config.json)" ]] || { echo "Commit library source changes first" >&2; exit 1; }
{ git -C "$ROOT" rev-parse HEAD; git -C "$SIM_RUNTIME" rev-parse HEAD; xcodebuild -version; node --version; bun --version; pod --version; ruby --version; xcrun -sdk iphonesimulator --show-sdk-version; } > "$OUT/environment.txt"
SDK=$(xcrun -sdk iphonesimulator --show-sdk-path)
METADATA=0
PUSH_API=legacy
[[ ! -f "$SDK/System/Library/Frameworks/PushKit.framework/Headers/PKVoIPPushMetadata.h" ]] || METADATA=1
[[ "$METADATA" -ne 1 ]] || PUSH_API=metadata
for lib in CKSimRuntime CKSimPush; do
  xcrun -sdk iphonesimulator clang -target "$(uname -m)-apple-ios18.0-simulator" -DCKSIM_HAS_VOIP_METADATA="$METADATA" \
    -fobjc-arc -dynamiclib -framework Foundation -framework CallKit -framework AVFoundation -framework PushKit \
    -o "$BUILD/$lib.dylib" "$SIM_RUNTIME/discovery/spike/$lib.m" >> "$OUT/build.log" 2>&1
  codesign -s - --force "$BUILD/$lib.dylib" >> "$OUT/build.log" 2>&1
done
RUNTIME=$(xcrun simctl list runtimes -j | python3 -c 'import json,sys; r=[x for x in json.load(sys.stdin)["runtimes"] if x.get("platform")=="iOS" and x["isAvailable"]]; print(sorted(r,key=lambda x:[int(p) for p in x["version"].split(".")])[-1]["identifier"])')
echo "simulatorRuntime=$RUNTIME pushAPI=$PUSH_API" >> "$OUT/environment.txt"
UDID=$(xcrun simctl create "callkit-library-qa-$$" com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro "$RUNTIME")
echo "runOwnedSimulator=$UDID" >> "$OUT/environment.txt"
limit 30 xcrun simctl boot "$UDID"
limit 180 xcrun simctl bootstatus "$UDID" -b >> "$OUT/build.log" 2>&1
INHERITED=${SIMCTL_CHILD_DYLD_INSERT_LIBRARIES-$(xcrun simctl spawn "$UDID" launchctl getenv DYLD_INSERT_LIBRARIES 2>/dev/null || true)}
LIBS="${INHERITED:+$INHERITED:}$BUILD/CKSimRuntime.dylib:$BUILD/CKSimPush.dylib"
ruby "$ROOT/example/qa/add-ui-tests.rb" "$ROOT/example/client/ios/CallKitTel.xcodeproj" "$LIBS" "$PUSH_API"
cd "$ROOT/example/client/ios"
COMMON=(-workspace CallKitTel.xcworkspace -scheme CallKitTel -configuration Release -sdk iphonesimulator -derivedDataPath "$DERIVED" CODE_SIGNING_ALLOWED=NO ARCHS="$(uname -m)" ONLY_ACTIVE_ARCH=YES)
limit 900 xcodebuild "${COMMON[@]}" -destination 'generic/platform=iOS Simulator' build-for-testing >> "$OUT/build.log" 2>&1
limit 60 xcrun simctl install "$UDID" "$DERIVED/Build/Products/Release-iphonesimulator/CallKitTel.app"
limit 20 xcrun simctl privacy "$UDID" grant microphone com.example.expocallkittelecom
limit 180 xcodebuild "${COMMON[@]}" -destination "id=$UDID" -parallel-testing-enabled NO \
  -only-testing:PushIsolationUITests -resultBundlePath "$OUT/PushIsolation.xcresult" test-without-building > "$OUT/test.log" 2>&1
echo "XCTest passed; private result bundle: $OUT/PushIsolation.xcresult"
