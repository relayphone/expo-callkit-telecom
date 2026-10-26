#!/usr/bin/env bash
set -euo pipefail
mkdir -p android/instrumentation/build
collect_media_results() {
  for result in native-media-results native-webrtc-results; do
    adb exec-out run-as expo.modules.callkittelecom.instrumentation cat "files/$result.json" > "android/instrumentation/build/$result.json" || true
  done
}
trap collect_media_results EXIT
gradle -p android/instrumentation connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true --stacktrace
adb exec-out run-as expo.modules.callkittelecom.instrumentation cat files/journey-results.json > android/instrumentation/build/journey-results.json
python3 tools/check-android-journey.py android/instrumentation/build/journey-results.json
adb exec-out run-as expo.modules.callkittelecom.instrumentation cat files/native-media-results.json > android/instrumentation/build/native-media-results.json
python3 tools/check-native-media.py android/instrumentation/build/native-media-results.json
adb exec-out run-as expo.modules.callkittelecom.instrumentation cat files/native-webrtc-results.json > android/instrumentation/build/native-webrtc-results.json
python3 tools/check-native-media.py android/instrumentation/build/native-webrtc-results.json
