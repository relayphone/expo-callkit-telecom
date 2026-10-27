#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 || ! "$1" =~ ^(platform|native)$ || ! "$2" =~ ^[A-Za-z0-9_-]{1,80}$ ]]; then
  echo 'Usage: run-android-dnd-study.sh platform|native RUN_ID' >&2
  exit 2
fi
lane="$1"
run_id="$2"
build=android/instrumentation/build
app=expo.modules.callkittelecom.instrumentation
runner="$app.test/androidx.test.runner.AndroidJUnitRunner"
helper=expo.modules.callkittelecom.dndpolicy
results="$build/dnd-study-results/$lane"
logs="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/android-dnd-study/$run_id/$lane"
mkdir -p "$results" "$logs"
# Only this lane's owned artifacts are removed; a skipped invocation cannot reuse one.
rm -f "$results"/dnd-study-"$lane"-*.json "$results"/dnd-study-"$lane"-*.json.tmp

restore_dnd() {
  local status=$?
  timeout 10s adb shell cmd notification set_dnd off >/dev/null 2>&1 || true
  return "$status"
}
trap restore_dnd EXIT

# The shared test application references this CA resource even when no media
# tests run. Generate only that build input; this study starts no media server.
fixture_dir="$logs/build-fixture"
mkdir -p "$fixture_dir" android/instrumentation/src/main/res/raw
timeout 20s openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
  -subj /CN=DndStudyFixtureCA -keyout "$fixture_dir/ca.key" \
  -out android/instrumentation/src/main/res/raw/media_ci_ca.pem > "$fixture_dir/openssl.log" 2>&1
chmod 600 "$fixture_dir/ca.key"

# Compile and install the selected test harness without running its other suites.
timeout --kill-after=10s 600s gradle -p android/instrumentation \
  :assembleDebug :assembleDebugAndroidTest :dnd-policy-helper:assembleDebug \
  :installDebug :installDebugAndroidTest --stacktrace
timeout 30s adb install -r -t android/instrumentation/policy-helper/build/outputs/apk/debug/dnd-policy-helper-debug.apk
timeout 15s adb shell cmd notification allow_dnd "$helper"
timeout 15s adb shell cmd notification allow_dnd "$app"
for permission in READ_CONTACTS WRITE_CONTACTS POST_NOTIFICATIONS RECORD_AUDIO; do
  timeout 15s adb shell pm grant "$app" "android.permission.$permission"
done
timeout 15s adb shell cmd notification allow_listener "$app/expo.modules.callkittelecom.conversations.DndObservationService"
timeout 15s adb shell am start -W -n "$app/expo.modules.callkittelecom.conversations.DndBootstrapActivity"

run_case() {
  local senders="$1" repeat="$2" caller="$3" sample="$4" status=0 pull_status=0
  local name="dnd-study-$lane-$senders-$repeat-$caller-$sample"
  local destination="$results/$name.json"
  local private_log="$logs/$name.log"
  timeout 15s adb shell run-as "$app" rm -f "files/$name.json" || return $?
  rm -f "$destination" "$destination.tmp" || return $?
  # The testcase verifies a fresh identity with a preflight, then writes and reads
  # back its target policy. Each invocation measures exactly one A/B call pair.
  if timeout --kill-after=5s 90s adb shell am instrument --no-restart -r -w \
    -e class 'expo.modules.callkittelecom.conversations.DndStudyCaseTest#typedCallerPolicyPair' \
    -e dndStudyLane "$lane" -e dndStudySenders "$senders" -e dndStudyRepeat "$repeat" \
    -e dndStudyCaller "$caller" -e dndStudySample "$sample" -e dndStudyRun "$run_id" \
    "$runner" > "$private_log" 2>&1; then
    status=0
  else
    status=$?
  fi
  # Preserve a testcase's failure JSON without publishing its private runner log.
  if timeout 15s adb exec-out run-as "$app" cat "files/$name.json" > "$destination.tmp"; then
    mv "$destination.tmp" "$destination" || return $?
  else
    pull_status=$?
    rm -f "$destination.tmp"
  fi
  if [[ "$status" -ne 0 ]]; then
    echo "DND study attempt failed: $lane/$senders/$repeat/$caller/$sample (exit $status; private log $private_log)" >&2
    return "$status"
  fi
  if [[ "$pull_status" -ne 0 ]]; then
    echo "DND study result missing: $name" >&2
    return 1
  fi
  python3 tools/aggregate-native-latency.py --check-instrumentation-log "$private_log" || return $?
  python3 tools/check-android-dnd-study.py --case-file "$destination" --lane "$lane" --run "$run_id"
}

failed_attempts=0
for sample in 1 2 3; do
  for senders in any contacts starred; do
    for repeat in false true; do
      for caller in starred contact noncontact; do
        if run_case "$senders" "$repeat" "$caller" "$sample"; then
          :
        else
          failed_attempts=$((failed_attempts + 1))
          name="dnd-study-$lane-$senders-$repeat-$caller-$sample"
          python3 tools/check-android-dnd-study.py --diagnostic-log "$logs/$name.log" \
            --result-file "$results/$name.json" --lane "$lane" --run "$run_id" || true
          # End a timed-out instrumentation process before the next unique case.
          # This is recovery between attempts, never a retry of the failed case.
          timeout 10s adb shell am force-stop "$app" >/dev/null 2>&1 || true
          timeout 15s adb shell am start -W -n "$app/expo.modules.callkittelecom.conversations.DndBootstrapActivity" >/dev/null 2>&1 || true
        fi
      done
    done
  done
done

if python3 tools/check-android-dnd-study.py --input-dir "$results" --lane "$lane" --run "$run_id"; then
  if [[ "$failed_attempts" -ne 0 ]]; then
    echo "DND study lane $lane had $failed_attempts failed instrumentation/check attempts; no retries were made" >&2
    exit 1
  fi
else
  echo "DND study lane $lane is incomplete or failed ($failed_attempts unsuccessful attempts)" >&2
  exit 1
fi
