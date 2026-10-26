#!/usr/bin/env bash
set -euo pipefail
app=expo.modules.callkittelecom.instrumentation
runner="$app.test/androidx.test.runner.AndroidJUnitRunner"
helper=expo.modules.callkittelecom.dndpolicy
results=android/instrumentation/build/dnd-results
mkdir -p "$results"
trap 'adb shell cmd notification set_dnd off >/dev/null' EXIT
gradle -p android/instrumentation :dnd-policy-helper:assembleDebug --stacktrace
adb install -r -t android/instrumentation/policy-helper/build/outputs/apk/debug/dnd-policy-helper-debug.apk
adb shell cmd notification allow_dnd "$helper"
adb shell pm grant "$app" android.permission.READ_CONTACTS
adb shell pm grant "$app" android.permission.WRITE_CONTACTS
adb shell cmd notification allow_listener "$app/expo.modules.callkittelecom.conversations.DndObservationService"
adb shell am start -W -n "$app/expo.modules.callkittelecom.conversations.DndBootstrapActivity"

run_case() {
  local name="$1" mode="$2" senders="$3" calls="$4" repeat="$5" caller="$6" allowed="$7" filter="$8"
  adb shell am broadcast -a expo.callkittelecom.SET_DND_POLICY -n "$helper/.PolicyReceiver" \
    --es senders "$senders" --ez calls "$calls" --ez repeat "$repeat"
  adb shell cmd notification set_dnd "$mode"
  adb shell am instrument --no-restart -w -e class expo.modules.callkittelecom.conversations.DndMatrixTest \
    -e dndCase "$name" -e allowed "$allowed" -e caller "$caller" -e mode "$filter" "$runner"
  adb exec-out run-as "$app" cat "files/dnd-$name.json" > "$results/$name.json"
  python3 -c 'import json,sys; result=json.load(open(sys.argv[1])); assert result["passed"], result' "$results/$name.json"
}
run_case off-any off any true false unknown true 1
run_case on-any on any true false unknown false 3
run_case priority-any priority any true false unknown true 2
run_case priority-none priority any false false unknown false 2
run_case priority-contacts-unknown priority contacts true false unknown false 2
run_case priority-contacts-opaque priority contacts true false opaque false 2
run_case priority-contacts-contact priority contacts true false contact true 2
run_case priority-starred-contact priority starred true false contact false 2
run_case priority-starred-starred priority starred true false starred true 2
run_case priority-repeat-first priority any false true repeat false 2
run_case priority-repeat-second priority any false true repeat true 2
run_case off-restored off any true false unknown true 1
adb shell am instrument --no-restart -w -e class expo.modules.callkittelecom.conversations.DndWarmCleanupTest -e dndWarm true "$runner"
adb exec-out run-as "$app" cat files/dnd-warm-cleanup.json > "$results/warm-cleanup.json"
python3 tools/check-android-dnd.py "$results"
