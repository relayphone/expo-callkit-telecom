#!/usr/bin/env bash
set -euo pipefail
mkdir -p android/instrumentation/build
trap 'adb exec-out run-as expo.modules.callkittelecom.instrumentation cat files/native-media-results.json > android/instrumentation/build/native-media-results.json || true' EXIT
gradle -p android/instrumentation connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true --stacktrace
adb exec-out run-as expo.modules.callkittelecom.instrumentation cat files/journey-results.json > android/instrumentation/build/journey-results.json
python3 tools/check-android-journey.py android/instrumentation/build/journey-results.json
adb exec-out run-as expo.modules.callkittelecom.instrumentation cat files/native-media-results.json > android/instrumentation/build/native-media-results.json
python3 tools/check-native-media.py android/instrumentation/build/native-media-results.json
