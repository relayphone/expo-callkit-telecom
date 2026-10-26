#!/usr/bin/env bash
set -euo pipefail
app_id=com.example.expocallkittelecom
result_path=/data/user/0/$app_id/files/conversation-journey.json
# The google_apis test image permits root ADB; the app itself remains a normal release build.
adb root
adb wait-for-device
adb install -r example-apk/app-release.apk
adb shell pm grant "$app_id" android.permission.RECORD_AUDIO
adb shell pm grant "$app_id" android.permission.POST_NOTIFICATIONS
adb shell am start -W -a android.intent.action.VIEW -d conversations-example://journey "$app_id"
for attempt in {1..60}; do
  if adb shell test -s "$result_path"; then
    adb exec-out cat "$result_path" > example-journey.json
    python3 tools/check-example-journey.py example-journey.json
    adb shell am force-stop "$app_id"
    exit 0
  fi
  sleep 2
done
adb logcat -d -s AndroidRuntime
echo 'The example did not produce its journey result within 120 seconds' >&2
exit 1
