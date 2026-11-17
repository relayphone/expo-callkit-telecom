#!/usr/bin/env bash
set -euo pipefail
serial=${1:?An explicit emulator serial is required}
output=${2:-android/instrumentation/build/cold-push-results}
apk=${3:-android/instrumentation/build/outputs/apk/debug/ConversationsInstrumentation-debug.apk}
[[ "$serial" =~ ^emulator-[0-9]+$ ]]
[[ $(adb -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r\n') == 1 ]]
case $(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r\n') in
  34|35|36) ;;
  *) exit 1 ;;
esac
mkdir -p "$output"
cleanup() {
  result=$?
  trap - EXIT
  adb -s "$serial" logcat -d -v threadtime > "$output/logcat.txt" 2>&1 || true
  exit "$result"
}
trap cleanup EXIT
adb -s "$serial" install -r "$apk"
timeout 240s adb -s "$serial" shell cmd package compile -m speed -f expo.modules.callkittelecom.instrumentation > "$output/package-compile.txt"
timeout 60s node tools/android-push-faults.ts "$serial" '{"kind":"reset"}' > "$output/push-fault-events.json"
timeout 60s node tools/android-push-faults.ts "$serial" cold-start "$output/push-fault-events.json" > "$output/cold-start-result.json"
timeout 60s node tools/check-android-journey-state.ts "$serial" > "$output/cleanup-state.json"
