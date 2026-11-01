# Test report: Project Steps

Updated 2026-11-01. **Only real command results are reported.** CI: GitHub Actions run #8 on commit
`3d4b1c9` (repo `SimplyComplicated619/project-steps`).

## Summary

| Check | Result | Where |
|---|---|---|
| Build (`./gradlew assembleDebug`) | **PASS** | GitHub Actions, ubuntu-24.04, JDK 17, AGP 8.13.2 |
| Unit tests (`./gradlew testDebugUnitTest`) | **PASS**: 76 run, 0 failures, 0 errors, 0 skipped | GitHub Actions (real JUnit 4.13.2) |
| Lint (`./gradlew lintDebug`) | **PASS**: 0 errors (warnings remain, none blocking) | GitHub Actions |
| Instrumented DB tests (`DatabaseTest`, 7 tests incl. migration validation) | **PASS** | Android 16 emulator |
| Emulator acceptance (install, setup, complete, force-stop + reopen, reminders, export/restore, update install, crash check) | **PASS**: all 11 checks | Android 16 (SDK 36) x86_64 emulator, Pixel 7 Pro profile |
| Device install on Galaxy S23 Ultra | **NOT AVAILABLE**: phone not connected to any build machine | |
| Real `Project-Checklists.zip` import | **NOT AVAILABLE**: ZIP not provided; demo data used for acceptance | |
| Samsung One UI battery behaviour | **NOT OBSERVED**: needs the real phone (emulator is stock Android) | |

## APK

| | |
|---|---|
| File | `ProjectSteps-build-8-debug.apk` (GitHub Release `build-8`) |
| Package / version | `app.projectsteps` 1.0.0 (versionCode 1), minSdk 29, targetSdk 36 |
| Size | 32,463,321 bytes |
| SHA-256 | `70208ec612a6286b7503420fb9f21d3a5a6b18f8fc1554118aa6d5415b262d0b` |
| Signing | APK Signature Scheme v2, project debug key `keystore/debug.keystore`, cert SHA-256 `39:76:00:70:79:EB:AA:BE:26:51:C0:D4:66:C4:CF:6C:97:B8:77:C1:4F:A2:0C:24:FD:32:25:C3:08:C6:5A:92` |
| Permissions | POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED, SCHEDULE_EXACT_ALARM (app); WAKE_LOCK, FOREGROUND_SERVICE, ACCESS_NETWORK_STATE (added by WorkManager). **No INTERNET.** |

The checksum and certificate were re-verified after downloading the release asset.

## Emulator acceptance (ci/acceptance.sh, release `acceptance-8`)

```
Device: sdk_gphone64_x86_64 | Android 16 (SDK 36)
PASS install APK
PASS launch (process running)
PASS stage1-setup-and-complete: OK (1 test)
INFO force-stopped app
PASS stage2-reopen-after-force-stop: OK (1 test)
PASS stage3-reminders: OK (3 tests)
PASS stage4-export-restore: OK (1 test)
PASS database-tests: OK (7 tests)
PASS update install keeps data
PASS no app crashes in logcat
RESULT: ALL PASSED
```

What each stage exercised (all with clearly-labelled DEMO data, never real documents):

1. **Install / launch:** `adb install -r`, launch, process running.
2. **Stage 1:** fresh-install setup wizard (welcome → import → editable review → reminders → backup →
   finish → Today). Opens the project ("1 / 8 completed · 12%"), taps a checkbox ("2 / 8 · 25%"),
   opens a task, goes back, recreates the activity, and the state is kept.
3. **Force-stop**, then **Stage 2** in dark mode: the app opens straight to Today (setup not
   repeated) and the completion persisted. Also opens Next Action and Settings › Reminder Reliability.
4. **Stage 3 reminders** (notification permission and exact-alarm access granted by the script):
   * one-time reminder → WorkManager job enqueued → **real notification appeared** with Complete
     and "Snooze 15 min"; the notification's **Snooze** button snoozed it ~15 min; its **Complete**
     button completed the task, stopped the reminder and cancelled the job;
   * daily reminder: completing the task completed only the current occurrence, the task stayed
     open, the next occurrence stayed scheduled, history recorded; deleting cancelled the job;
   * **precise** reminder used an exact alarm (no WorkManager job) and its notification appeared.
5. **Stage 4 export/restore:** JSON, ZIP and Markdown export; read JSON and ZIP back; merge kept
   the newer local change and deleted nothing; replace restored the backup state and wrote a safety copy.
6. **Database tests:** completion persists across DB close/reopen, undo, ordering/move, dependency
   cycle refused, soft delete + undo, recurring completion, schema v1 migration validation.
7. **Update install:** reinstalling over the existing app keeps its database.
8. **Crash check:** no FATAL EXCEPTION from the app in logcat.

Screenshots from the run (setup, review, Today, project, task, dark mode, Next Action, Reliability,
notification shade, after update) are in `acceptance-results.zip` on the `acceptance-8` release.

## Unit tests (76, JVM)

DOCX/Markdown parsing (headings, checkbox markers, Word checkbox controls, numbered/bulleted/nested
lists, notes, tables, explicit completion only), ZIP security (traversal, nesting, malformed,
bombs, limits, encryption, unsupported compression, duplicates, CRC), XML security (XXE, entity
expansion, limits), recurrence and DST, time-zone changes, quiet hours, reminder planning
(create/edit/cancel/complete/snooze/recurrence/reboot reconstruction), dependency cycles,
re-import merge (identity, completion, notes, schedules, edited titles), next action, backup
format. They were also run with kotlinc 2.2.20 in the original sandbox (76/76).

## Fixed during CI

* Lint `ContextCastToActivity` → use `LocalActivity`.
* Lint `ByteOrderMark` → escape `\uFEFF` instead of a literal character.
* Test-only: suspend calls in test helpers, non-Unit test methods, a scroll action, and a
  kotlinx-serialization version conflict that broke Room's `MigrationTestHelper`.
* UI polish found in the emulator screenshots (Markdown checkbox label, waiting-on ellipsis,
  heading indentation, settings heading indent, plurals).

No app logic needed changing for any acceptance test to pass.

## Still open

* **On the Galaxy S23 Ultra itself:** install, real ZIP import, reminder delivery under One UI
  battery management, Force Stop behaviour. Run `ci/acceptance.sh` with the phone connected
  over `adb` (it uses demo data), or follow the manual checklist in README.
* Import of your real `Project-Checklists.zip` (not yet provided).
