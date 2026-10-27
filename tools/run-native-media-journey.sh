#!/usr/bin/env bash
set -euo pipefail
build=android/instrumentation/build
app=expo.modules.callkittelecom.instrumentation
runner="$app.test/androidx.test.runner.AndroidJUnitRunner"
samples="$build/native-latency-samples"
logs="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/native-latency-instrumentation"
full_results_saved=false
mkdir -p "$build" "$samples" "$logs"
# Remove only this benchmark's owned results, including private logs from older runners.
rm -f "$samples"/livekit-*.json "$samples"/webrtc-*.json "$samples"/livekit-*.log "$samples"/webrtc-*.log

pull_result() {
  local name="$1" destination="$2"
  if timeout 15s adb exec-out run-as "$app" cat "files/$name.json" > "$destination.tmp"; then
    mv "$destination.tmp" "$destination"
  else
    rm -f "$destination.tmp"
    return 1
  fi
}

collect_media_results() {
  # Individual benchmarks reuse the app's result filenames. Never replace the
  # complete journey evidence with the last single-sample result on exit.
  if [[ "$full_results_saved" == false ]]; then
    for result in native-media-results native-webrtc-results; do
      pull_result "$result" "$build/$result.json" || true
    done
  fi
  pull_result answered-elsewhere-results "$build/answered-elsewhere-results.json" || true
}
trap collect_media_results EXIT
gradle -p android/instrumentation :connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true --stacktrace
pull_result journey-results "$build/journey-results.json"
python3 tools/check-android-journey.py "$build/journey-results.json"
pull_result answered-elsewhere-results "$build/answered-elsewhere-results.json"
python3 tools/check-answered-elsewhere.py "$build/answered-elsewhere-results.json"
pull_result native-media-results "$build/native-media-results.json"
pull_result native-webrtc-results "$build/native-webrtc-results.json"
full_results_saved=true
python3 tools/check-native-media.py "$build/native-media-results.json"
python3 tools/check-native-media.py "$build/native-webrtc-results.json"

run_sample() {
  local provider="$1" source="$2" index="$3" result=native-media-results status=0
  if [[ "$provider" == webrtc ]]; then result=native-webrtc-results; fi
  local destination="$samples/$provider-$source-$index.json"
  local private_log="$logs/$provider-$source-$index.log"
  timeout 15s adb shell am force-stop "$app"
  # A skipped/missing test cannot pass using an earlier invocation's JSON.
  timeout 15s adb shell run-as "$app" rm -f "files/$result.json"
  rm -f "$destination"
  if timeout --kill-after=5s 90s adb shell am instrument -r -w \
    -e class 'expo.modules.callkittelecom.conversations.NativeMediaJourneyTest#nativeAnswerLatencySample' \
    -e latencyProvider "$provider" -e latencySource "$source" -e latencySample "$index" "$runner" \
    > "$private_log" 2>&1; then
    status=0
  else
    status=$?
  fi
  # Retain failure evidence too, then fail on any unsuccessful attempt. No retry.
  pull_result "$result" "$destination" || return 1
  if [[ "$status" -ne 0 ]]; then
    echo "Latency attempt failed: $provider/$source/$index (instrumentation exit $status; private log $private_log)" >&2
    return "$status"
  fi
  python3 tools/aggregate-native-latency.py --check-instrumentation-log "$private_log"
  python3 tools/check-native-media.py "$destination"
}

for index in 1 2 3 4 5; do
  run_sample livekit push "$index"
  run_sample livekit fetcher "$index"
  run_sample webrtc fetcher "$index"
  run_sample webrtc livekitPush "$index"
done

python3 tools/aggregate-native-latency.py --input-dir "$samples" --output-dir "$build"
python3 tools/check-native-media.py "$build/native-latency-livekit.json"
python3 tools/check-native-media.py "$build/native-latency-webrtc.json"
python3 tools/summarize-native-latency.py --input "$build/native-latency-livekit.json" --input "$build/native-latency-webrtc.json" --output-json "$build/native-latency-summary.json" --output-markdown "$build/native-latency-summary.md"
