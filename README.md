# Project Steps

Project Steps is an offline Android app for project checklists and reminders. It imports your
checklist documents (`Project-Checklists.zip`, single `.docx` files or Markdown), turns them into
interactive projects, and schedules reminders for projects and individual steps.

It's designed and tuned for the **Samsung Galaxy S23 Ultra (SM-S918U, Android 16 / One UI 8)**
and supports **Android 10 and newer** (minSdk 29, targetSdk 36).

* Kotlin, Jetpack Compose, Material 3, Room, ViewModel + StateFlow, coroutines, WorkManager
* Fully offline. The app has **no INTERNET permission**, no account, no analytics, no ads and no AI services.
* Documents are treated as data. Nothing inside them is ever executed.

> **Status:** source complete. This repository was written in a sandbox that could not
> download the Android SDK or Google's Maven libraries, so the APK has **not been built or run
> yet**. The pure-Kotlin core (import, security, scheduling, merge, backup) compiles and its 76 unit
> tests pass. See `TEST-REPORT.md`.

---

## Building from source

Requirements: JDK 17 or newer (Android Studio's bundled JDK 21 works), the Android SDK with
platform 36, and internet access for the first build (Gradle downloads its dependencies).

```bash
# Android Studio: File › Open › select this folder, then Build › Build APK(s).
# Or from a terminal (with ANDROID_HOME set, or sdk.dir in local.properties):
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk

./gradlew testDebugUnitTest      # JVM unit tests (import, ZIP/XML security, recurrence, DST, merge, backup)
./gradlew lintDebug              # Android lint
./gradlew connectedDebugAndroidTest   # Room/database tests on a connected phone or emulator
```

Pinned toolchain (see `gradle/libs.versions.toml`): Gradle 8.14.3 wrapper, Android Gradle Plugin
8.13.2, Kotlin 2.2.20 (with the Compose compiler plugin), KSP 2.2.20-2.0.4, Compose BOM 2025.10.01,
Room 2.8.3, WorkManager 2.10.1.

## Installing on the Galaxy S23 Ultra

1. On the phone: Settings › About phone › Software information › tap **Build number** 7 times,
   then Settings › Developer options › **USB debugging** on.
2. Connect by USB and accept the "Allow USB debugging" prompt.
3. Check the connection and install only the debug APK:

```bash
adb devices
adb shell getprop ro.product.model          # SM-S918U
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p app.projectsteps -c android.intent.category.LAUNCHER 1   # launch
```

Nothing else on the phone is changed.

### Updates and signing (important)

Android only installs an update over an existing app if both are signed with the **same key**.
Debug builds are signed with the debug keystore on the computer that built them
(`~/.android/debug.keystore`). Build future updates on the same machine or copy that keystore. If
the key changes you must uninstall first, which deletes the app's data, so export a backup
before doing that. No production signing key was created. Make one deliberately when you move beyond
development, keep it safe, and never regenerate it.

---

## First-time setup

Setup runs once. When it finishes, `setup_completed = true` is stored and every later launch opens **Today**.

1. **Welcome:** what the app does.
2. **Import projects:** *Import Project-Checklists.zip*, *Import another ZIP*, *Import DOCX*,
   *Import Markdown*, or *Skip and use demo data* (always labelled **DEMO**, never presented as yours).
3. **Review import:** an editable preview: projects, sections, tasks, notes, items already
   completed in the source, and items that need your review. Nothing is saved until you tap **Save**.
4. **Reminder preferences:** notifications, default reminder time, quiet hours, default snooze.
   The Android 13+ notification permission is requested only here (or when you first save a
   reminder), after a short explanation.
5. **Backup:** explains manual backups (Google Drive via the system file picker; no Google sign-in).
6. **Finish**

Every choice can be changed later in **Settings**. *Settings › Run setup again* repeats the wizard
without resetting any data.

## Import

Files are opened through Android's document picker (Storage Access Framework). The app has no
broad storage permission.

**ZIP:** the archive is validated, its files are listed, you choose which documents to import, they
are parsed, and you review an editable preview before anything is saved. Unsupported files, nested
archives and system files (e.g. `__MACOSX`) are listed with the reason they're skipped.

**What the parser recognises**

| In the document | Becomes |
|---|---|
| Word *Title* style, or a single top-level heading | Project description (name comes from the file name, e.g. `Apple-TV.docx` → "Apple TV") |
| Headings (Heading 1–9, or outline level) | Sections (nested by level) |
| `[ ]` `☐` `□` Word checkbox content controls, legacy form checkboxes, ☐ list bullets (incl. Wingdings) | Task, not done |
| `[x]` `[X]` `☑` `☒` `✓` `✔` `✅`, ticked Word checkboxes | Task, **done** (the only way something is imported as done) |
| Numbered list items, `1.`, `1)`, `Step 3:` | Task |
| Nested list items | Subtasks |
| Bullets | Tasks, or notes marked for review if the document also uses checkboxes |
| Plain paragraphs, `Note:` / `Tip:` / `Warning:` paragraphs, quotes | Notes |
| Code / monospace paragraphs | Notes (commands are shown as text, never run) |
| Tables with a checkbox or *Step/Task* column | One task per row; other columns become details |
| Short bold or ALL-CAPS lines | Sections, marked **REVIEW** |
| "NEEDS APPROVAL", "destructive / wipe / factory reset / erase…", "Waiting on: …", "blocked" | Visible flags |

Anything uncertain is shown with a **REVIEW** label and a reason. You can change any item's type
(Section / Task / Note), text, done state or inclusion. Prose never marks a task done; text such as
"(done)" only flags it for review.

**Traceability:** every item keeps its source file, archive, section path, paragraph number,
original text and import time (shown read-only on the task screen). Source files are never modified.

**Import security:** imported files are untrusted data.
* ZIP: rejects path traversal (`../`, absolute paths, drive letters), folders nested more than 8
  deep, encrypted entries, split/ZIP64 archives, duplicate names, overlapping entries (ZIP-bomb
  technique), compression ratios over 200:1, and entries that inflate beyond their declared size.
  CRC-32 is verified. Nothing is extracted to disk. Archives inside archives are not opened.
* Limits: 25 MB per picked file, 500 entries, 25 MB per entry, 150 MB total expanded, 16 MB per XML
  part, XML depth 256, 1.5 M XML nodes, 20,000 paragraphs per document, 100 documents per import.
* XML: a purpose-built parser that rejects any DOCTYPE/ENTITY declaration (no XXE, no entity
  expansion, no external resource loading) and enforces size/depth/node limits.
* Legacy `.doc`, password-protected Word files and macro-enabled `.docm` are refused with a clear
  message. Macros, images and embedded objects are never run or imported.

**Re-importing:** when a document was imported before, choose **Merge changes**, **Skip** or
**Create copy**. Merge matches items by source metadata plus stable fingerprints, never by title
alone. It keeps completion state, personal notes, reminders, dependencies and any titles you edited.
It adds new items, and labels items that disappeared from the document **NOT IN LATEST IMPORT**
instead of deleting them. If the document ticks something you haven't, that is reported, not applied.

## Checklists

* **Today:** Next Action, Overdue (red **and** the word OVERDUE), Due today, Blocked/waiting,
  recently active projects.
* **Upcoming:** the next 30 days of due dates and reminders.
* **Projects:** progress per project (e.g. `13 / 22 completed · 59%`), archive, reorder.
* **Project:** collapsible sections; each row shows a checkbox, title, status, priority, due time,
  next reminder and flags (⚠ NEEDS APPROVAL, ⚠ DESTRUCTIVE ACTION, WAITING ON, BLOCKED).
* **Next Action:** large, glanceable view of the next useful unfinished task (overdue → due today →
  in progress → most important/recent project, in document order). Excludes done, archived and
  blocked tasks unless you choose otherwise. Optional *Keep screen on*.
* **Search** across projects, sections, tasks and notes.
* Every action is in each row's **⋮** menu (also opened by long-press), so nothing depends on gestures.
* **Undo** after complete, archive, delete and reorder. Deletes go to a 30-day trash.
* **Dependencies:** "Task B depends on Task A". Loops are refused; B shows **BLOCKED BY STEP** until A is done.
* Flags are informational. Project Steps never runs shell, ADB, server, device or network commands.

## Reminders

Reminders can be attached to tasks or projects: one time, daily, weekly, selected weekdays, monthly,
custom (every N days/weeks/months, optional weekdays), with an optional end date and
**repeat until completed** (re-notify every N minutes until done). Snooze from the notification
(default length) or from the task (10 min / 1 h / 3 h / 1 day / custom).

* **Time zones:** each reminder either follows the phone's current zone or is pinned to a fixed IANA
  zone (stored as a zone ID, not an offset).
* **Recurring tasks:** completing one completes the current occurrence only (recorded in history);
  future occurrences stay scheduled.
* **Daylight saving:** times are computed from wall-clock time in the zone. A time that doesn't
  exist (e.g. 02:30 on a spring-forward night) moves forward by the gap; an ambiguous time uses the
  earlier offset.
* **Quiet hours:** reminders due during quiet hours appear when quiet hours end.
* **Rebuild after reboot / app update / manual clock change / time-zone change** and whenever the
  app opens. Each reminder has exactly one scheduled job, so rebuilding never duplicates. A
  reminder missed while the phone was off is shown once (recurring ones only if missed within 24 h).
* **Notifications:** title = project, text = task, "Due now" or "OVERDUE since …", with
  **Complete** and **Snooze** buttons. Tapping opens that exact task.
* **Lock-screen privacy:** by default the lock screen shows only "Project Steps: You have a
  scheduled project task." Turn on *Show task details on lock screen* to see names.

**Scheduling:** normal reminders use WorkManager. *Precise reminder* (per reminder, opt-in) uses
`AlarmManager.setExactAndAllowWhileIdle`. It needs the *Alarms & reminders* permission, which the app
explains and lets you open in system settings, but never requests by default. Without it, precise
reminders fall back to normal scheduling.

## Samsung notification behaviour

One UI may delay or block background work for battery reasons. The app **never changes these
settings itself**. *Settings › Reminder Reliability* shows:

* whether notifications are allowed (with a button to the app's notification settings),
* whether exact alarms are allowed (button to *Alarms & reminders*),
* whether battery optimization or background restriction applies (buttons to app info and the
  battery-optimization list),
* a One UI checklist: App info › Battery › **Unrestricted**; keep Project Steps out of *Sleeping
  apps* / *Deep sleeping apps* (Settings › Battery › Background usage limits); optionally add it to
  *Never sleeping apps*,
* a *Show a test notification now* button.

Expected behaviour (platform rules, **not yet observed on your phone**):

| Situation | Reminders |
|---|---|
| App in foreground | Delivered |
| App in background / screen off | Delivered. Normal ones may be a few minutes late in Doze; precise ones on time if exact alarms are allowed. |
| Battery "Optimized" / app put to sleep by One UI | May be delayed significantly, or held until the app is opened |
| **Force stop** | Android cancels **all** of the app's alarms and jobs and no app can prevent that. Reminders come back the next time you open Project Steps (missed ones from the last 24 h are shown then). |
| Reboot | Rebuilt automatically after the phone unlocks |

No reminder delivery is guaranteed; the phone's power management has the final say.

## Backup, export and restore

*Settings › Backup & Restore* (**MANUAL BACKUP**: you choose where the file goes; it is not
automatic sync):

* **JSON:** complete backup (schema version, projects, sections, tasks, notes, dependencies,
  reminders with recurrence and time-zone data, completion history and timestamps, project-related
  settings). This is the format to restore from.
* **Markdown:** a readable checklist of all projects.
* **ZIP:** `backup.json` plus `projects/<Project-Name>.md` for each project.

Pick Google Drive in the save dialog to store it there. No Google API, OAuth or account access is involved.

**Restore** a `.json` or `.zip`: the app shows what's in the backup, then offers
* **Merge:** add what's missing; for items in both, keep the most recently changed. Nothing is deleted.
* **Replace:** asks for confirmation (with an option to save your own copy first) and always writes
  a safety copy of the current data inside the app before replacing everything.

Android's automatic cloud backup and device-to-device transfer are disabled for app data
(`allowBackup=false`, data-extraction rules), so nothing leaves the phone unless you export it.

## Accessibility and display

Material 3 with system / light / dark themes, an AMOLED-black dark mode that keeps cards and
sections visibly raised, optional wallpaper colours (Material You), edge-to-edge drawing with proper
status-bar, cutout and gesture-bar insets, large (≥48 dp) touch targets that work with the S Pen,
system font scaling, TalkBack labels (checkbox state, actions, progress), portrait and landscape
layouts, and colour never used alone (OVERDUE, DONE, BLOCKED are always written out).

## Known limitations

* **Not built or device-tested yet.** See `TEST-REPORT.md`. Expect a short round of compile
  fixes on first build. The Android UI layer has only been syntax-checked.
* **Your real `Project-Checklists.zip` has not been inspected.** The parser handles the common
  Word and Markdown checklist conventions listed above. Unusual layouts will show up as **REVIEW**
  items in the preview rather than being silently misread.
* Images, drawings, comments, footnotes, headers/footers and embedded objects are not imported.
  Hidden text and tracked deletions are ignored.
* `.doc`, `.docm`, password-protected Word files, ZIP64 and encrypted archives are not supported.
* Notification snooze uses the default snooze length; custom snooze lengths are on the task screen.
* Reordering uses Move up / Move down (no drag handles in V1).
* Room schema version 1; the migration test is in place for future schema changes.
* Reminder delivery depends on Android/One UI power management (see above); Force Stop disables
  reminders until the app is opened again.
