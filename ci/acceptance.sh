#!/usr/bin/env bash
# On-device acceptance run (emulator in CI, or a phone connected with `adb`).
# Installs the debug APK + test APK and runs the stages in order with a real force-stop between
# stages 1 and 2. Uses only clearly-labelled DEMO data. Writes evidence to acceptance-results/.
set -uo pipefail
PKG=app.projectsteps
RUNNER=app.projectsteps.test/androidx.test.runner.AndroidJUnitRunner
OUT=acceptance-results
mkdir -p "$OUT/screens"
FAILED=0
log() { echo "$*" | tee -a "$OUT/summary.txt"; }

adb wait-for-device
log "Device: $(adb shell getprop ro.product.model | tr -d '\r') | Android $(adb shell getprop ro.build.version.release | tr -d '\r') (SDK $(adb shell getprop ro.build.version.sdk | tr -d '\r'))"
adb logcat -c || true

if adb install -r app/build/outputs/apk/debug/app-debug.apk > "$OUT/install.txt" 2>&1 && grep -q Success "$OUT/install.txt"; then
  log "PASS install APK"
else
  log "FAIL install APK"; cat "$OUT/install.txt"; exit 1
fi
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk > "$OUT/install-test.txt" 2>&1 || { log "FAIL install test APK"; exit 1; }

adb shell am start -W -n "$PKG/.MainActivity" > "$OUT/launch.txt" 2>&1
sleep 5
if adb shell pidof "$PKG" > /dev/null; then log "PASS launch (process running)"; else log "FAIL launch"; FAILED=1; fi
adb exec-out screencap -p > "$OUT/screens/00-first-launch.png"
adb shell am force-stop "$PKG"

run_stage() {
  local cls="$1" name="$2"
  adb shell am instrument -w -e class "$cls" "$RUNNER" > "$OUT/$name.txt" 2>&1
  if grep -q "^OK (" "$OUT/$name.txt"; then
    log "PASS $name: $(grep '^OK (' "$OUT/$name.txt" | tr -d '\r')"
  else
    log "FAIL $name"; FAILED=1
    grep -E "Error|Exception|expected|FAILURES|Tests run" "$OUT/$name.txt" | head -20 | tee -a "$OUT/summary.txt"
  fi
}

run_stage app.projectsteps.acceptance.Stage1SetupTest stage1-setup-and-complete
adb shell am force-stop "$PKG"; log "INFO force-stopped app"
adb shell cmd uimode night yes
run_stage app.projectsteps.acceptance.Stage2PersistenceTest stage2-reopen-after-force-stop
adb shell cmd uimode night no

adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
adb shell appops set "$PKG" SCHEDULE_EXACT_ALARM allow
run_stage app.projectsteps.acceptance.Stage3RemindersTest stage3-reminders
run_stage app.projectsteps.acceptance.Stage4BackupRestoreTest stage4-export-restore
run_stage app.projectsteps.DatabaseTest database-tests

# Update install: reinstalling the same-signature APK over the existing one keeps data.
before=$(adb shell run-as "$PKG" ls databases | tr -d '\r' | grep -c project-steps.db)
adb install -r app/build/outputs/apk/debug/app-debug.apk > "$OUT/update.txt" 2>&1
after=$(adb shell run-as "$PKG" ls databases | tr -d '\r' | grep -c project-steps.db)
if grep -q Success "$OUT/update.txt" && [ "$before" -ge 1 ] && [ "$after" -ge 1 ]; then log "PASS update install keeps data"; else log "FAIL update install"; FAILED=1; fi
adb shell am start -W -n "$PKG/.MainActivity" > /dev/null 2>&1; sleep 4
adb exec-out screencap -p > "$OUT/screens/30-after-update.png"

# Evidence
for f in $(adb shell run-as "$PKG" ls files/screens 2>/dev/null | tr -d '\r'); do
  adb exec-out run-as "$PKG" cat "files/screens/$f" > "$OUT/screens/$f"
done
adb logcat -d > "$OUT/logcat.txt" 2>&1
if grep -E "FATAL EXCEPTION" -A3 "$OUT/logcat.txt" | grep -q "$PKG"; then log "FAIL app crash found in logcat"; FAILED=1; else log "PASS no app crashes in logcat"; fi
log "RESULT: $([ $FAILED -eq 0 ] && echo ALL PASSED || echo FAILURES)"
exit $FAILED
