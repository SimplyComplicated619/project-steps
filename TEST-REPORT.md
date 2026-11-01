# Test report: Project Steps

Generated 2026-10-31 in a cloud build sandbox. **Only real command results are reported.**

## Summary

| Check | Result |
|---|---|
| Unit tests: core logic (76 tests) | **PASS** (76/76), compiled with kotlinc 2.2.20 and run on JDK 21 (see below) |
| Unit tests via Gradle (`./gradlew testDebugUnitTest`) | **NOT RUN**: Gradle plugins and libraries can't be downloaded here |
| Lint (`./gradlew lintDebug`) | **NOT RUN**: blocked (needs the Android Gradle Plugin and SDK) |
| Build (`./gradlew assembleDebug`) | **NOT RUN**: blocked. No APK was produced, so there is no APK path, size or SHA-256 |
| Android UI/data code | Syntax-checked with kotlinc only (no syntax errors). **Not compiled against the Android SDK** |
| Instrumented DB tests (`connectedDebugAndroidTest`) | **NOT RUN**: written (`app/src/androidTest`), need a device or emulator |
| Device install (Galaxy S23 Ultra) | **NOT AVAILABLE**: no phone connected (no ADB access from this sandbox) |
| Real `Project-Checklists.zip` import | **NOT AVAILABLE**: the ZIP was not provided |
| Reminder test on device | **NOT AVAILABLE**. Scheduling logic is unit-tested (create/edit/cancel/complete/snooze/recurrence/reboot/DST/time zones) |
| Export/restore on device | **NOT AVAILABLE**. Backup format round-trip and validation are unit-tested |
| Samsung battery behaviour (foreground / screen off / background / force-stopped) | **NOT OBSERVED**. Expected platform behaviour is documented in README, not claimed as tested |

## Why the build didn't run

The sandbox's network policy refused connections (HTTP 403 from the egress proxy) to
`dl.google.com`, `maven.google.com`, `repo.maven.apache.org`, `plugins.gradle.org` and
`services.gradle.org`. Without them there is no Android SDK, Android Gradle Plugin, AndroidX,
Compose or Room. JetBrains' GitHub releases were reachable, so the official Kotlin 2.2.20 compiler was
downloaded and verified against its published SHA-256
(`81f0264c9073b5cbbdb3ff8418cf2c5dac076879fc156fa1a6462f5a5acc4420`).

## How the core tests were run

The tests in `app/src/test` exercise the Android-free package `app.projectsteps.core` (import, ZIP/XML
security, classification, recurrence, quiet hours, reminder planning, dependencies, merge, next action,
backup). They are standard JUnit 4 tests. Because Maven Central (and so JUnit itself) was unreachable,
they were run with a tiny stand-in for the `org.junit.Test` / `org.junit.Assert` API (same signatures)
and a reflection runner. The test sources are unchanged and run under real JUnit with
`./gradlew testDebugUnitTest`.

```
kotlinc -jvm-target 17 -Werror -d build/core/main.jar app/src/main/java/app/projectsteps/core/**/*.kt
kotlinc -jvm-target 17 -Xfriend-paths=build/core/main.jar -cp build/core/main.jar:junit-shim -d build/core/test.jar app/src/test/java/**/*.kt
java -cp build/core/main.jar:build/core/test.jar:junit-shim:kotlin-stdlib.jar runner.RunTests <test classes>
```

Main code compiled with warnings-as-errors (`-Werror`): no warnings.

### Output

```
PASS  BackupTest.demoDataIsClearlyLabelled
PASS  BackupTest.jsonEdgeCases
PASS  BackupTest.markdownExport
PASS  BackupTest.rejectsDanglingReferences
PASS  BackupTest.rejectsNewerSchemaForeignFilesAndDamage
PASS  BackupTest.roundTripPreservesEverything
PASS  DocxImportTest.boldOrCapsShortLinesAreUncertainSections
PASS  DocxImportTest.bulletsAreReviewableNotesWhenDocumentUsesCheckboxes
PASS  DocxImportTest.bulletsAreTasksWhenDocumentHasNoCheckboxes
PASS  DocxImportTest.checkboxBulletGlyphsFromNumbering
PASS  DocxImportTest.checklistTableRows
PASS  DocxImportTest.codeParagraphsAreNotesNeverTasks
PASS  DocxImportTest.doctypeInDocumentIsRejected
PASS  DocxImportTest.flagsAreDetected
PASS  DocxImportTest.headingsBecomeSectionsAndTitleBecomesDescription
PASS  DocxImportTest.hyperlinksTrackedDeletionsAndFieldInstructions
PASS  DocxImportTest.legacyDocAndGarbageAreRejectedWithMessages
PASS  DocxImportTest.macrosAndImagesProduceWarningsNotContent
PASS  DocxImportTest.numberedStepsAndNestedSubsteps
PASS  DocxImportTest.plainParagraphsAreNotesAndProseNeverCompletesATask
PASS  DocxImportTest.projectNamesFromFileNames
PASS  DocxImportTest.singleTopHeadingIsTreatedAsDocumentTitle
PASS  DocxImportTest.sourceTraceabilityIsPreserved
PASS  DocxImportTest.textCheckboxMarkers
PASS  DocxImportTest.trailingCheckMarkIsCompletedButFlaggedForReview
PASS  DocxImportTest.unsafeLinkSchemesAreDropped
PASS  DocxImportTest.wordCheckboxContentControlsAndLegacyFormFields
PASS  MarkdownImportTest.binaryDataIsRejected
PASS  MarkdownImportTest.inlineMarkupAndLinksAreStripped
PASS  MarkdownImportTest.parsesHeadingsTaskListsNumberedNestedCodeAndTables
PASS  MergeAndDependencyTest.cyclesArePrevented
PASS  MergeAndDependencyTest.editedAddedRemovedAndToggledItems
PASS  MergeAndDependencyTest.identicalReimportMatchesEverythingExactly
PASS  MergeAndDependencyTest.mergeNeverOverwritesUserEditedTitleOrUncompletesWork
PASS  MergeAndDependencyTest.mergePreservesCompletionNotesSchedulesAndIdentity
PASS  MergeAndDependencyTest.nextActionOrdering
PASS  MergeAndDependencyTest.renamedHeadingKeepsChildrenViaMovedMatch
PASS  MergeAndDependencyTest.sameTitleInDifferentSectionsIsNotCrossMatched
PASS  MergeAndDependencyTest.titleAloneNeverMatches
PASS  MergeAndDependencyTest.unfinishedPrerequisitesBlock
PASS  SchedulingTest.completeOneTimeStopsReminder
PASS  SchedulingTest.createEditCancel
PASS  SchedulingTest.creatingARecurringReminderAfterTodaysTimeDoesNotFireImmediately
PASS  SchedulingTest.customRecurrenceAndEndDate
PASS  SchedulingTest.dailyReminderKeepsWallClockTimeAcrossDst
PASS  SchedulingTest.dailyWeeklyWeekdaysMonthly
PASS  SchedulingTest.dstSpringForwardGapAndFallBackOverlap
PASS  SchedulingTest.missedRecurringOccurrenceWithinWindowShowsNowOtherwiseSkips
PASS  SchedulingTest.quietHoursDelayReminders
PASS  SchedulingTest.quietHoursWrapMidnightAndSameDayWindows
PASS  SchedulingTest.rebootReconstructionIsIdempotentAndCatchesUpMissedReminders
PASS  SchedulingTest.recurringCompletionCompletesOnlyCurrentOccurrence
PASS  SchedulingTest.repeatUntilCompletedNagsThenStops
PASS  SchedulingTest.snoozeThenFireReturnsToSchedule
PASS  SchedulingTest.timezoneChangeLocalVersusFixed
PASS  SchedulingTest.weekdayMaskRoundTrip
PASS  XmlSecurityTest.billionLaughsIsRejected
PASS  XmlSecurityTest.depthAndNodeLimits
PASS  XmlSecurityTest.externalEntitiesAreRejected
PASS  XmlSecurityTest.malformedXmlIsRejected
PASS  XmlSecurityTest.namespacesResolveByUriNotPrefix
PASS  XmlSecurityTest.predefinedAndNumericEntitiesDecode
PASS  XmlSecurityTest.undeclaredEntitiesAreMalformed
PASS  ZipSecurityTest.archiveListsUnsupportedNestedAndSystemFiles
PASS  ZipSecurityTest.crcMismatchIsDetected
PASS  ZipSecurityTest.duplicateEntryNamesAreRejected
PASS  ZipSecurityTest.encryptedEntriesAreRejected
PASS  ZipSecurityTest.entryLargerThanDeclaredSizeIsRejected
PASS  ZipSecurityTest.excessiveFolderNestingIsRejected
PASS  ZipSecurityTest.extractionLimitsAreEnforced
PASS  ZipSecurityTest.malformedArchivesAreRejected
PASS  ZipSecurityTest.onlySelectedDocumentsAreParsedAndBadOnesDontStopOthers
PASS  ZipSecurityTest.pathTraversalIsRejected
PASS  ZipSecurityTest.singleDocxAndMarkdownFilesAreAccepted
PASS  ZipSecurityTest.unsupportedCompressionIsReportedPerFile
PASS  ZipSecurityTest.zipBombByCompressionRatioIsRejected
TOTAL 76  PASSED 76  FAILED 0
```

### What the tests cover (mapped to the requested list)

* **DOCX:** headings → sections, Title/lone H1 → description; checkbox text markers `[ ] [x] [X] ☐ ☑ ☒ ✓`;
  Word checkbox content controls and legacy form checkboxes; numbered steps and nested sub-steps;
  bullets (task vs. reviewable note); numbering glyph checkboxes incl. Wingdings; notes; explicit
  completed markers vs. prose ("done" never completes); trailing ✓ flagged for review; bold/caps
  headings; code paragraphs; flags; checklist tables; hyperlinks, tracked deletions, field codes;
  unsafe link schemes; source traceability; DOCTYPE rejection; legacy .doc; macros/images warnings.
* **Markdown:** headings, task lists, numbered, nested, code fences, tables, inline markup, binary rejection.
* **ZIP:** path traversal (`../`, `..\\`, absolute, drive letter); excessive nesting; malformed and truncated
  archives; compression-ratio bomb; entry larger than declared size; entry/total/count/input-size limits;
  encrypted entries; unsupported compression reported per file; duplicate names; CRC mismatch;
  unsupported, nested-archive and system files; selecting only some documents; broken document doesn't
  stop the others.
* **XML:** external entities, billion laughs, undeclared entities, malformed XML, depth and node limits, namespaces.
* **Database-level logic (pure):** dependency cycle prevention, blocked-by-prerequisite; next-action ordering.
  Room-specific tests (completion persistence across reopen, undo, ordering/move, dependency refusal,
  soft delete, recurring completion, migration validation) are in `app/src/androidTest` (**not run**).
* **Import/merge:** duplicate detection (identical re-import), same title in different sections not
  cross-matched, title alone never matches, edited/added/removed/toggled items, renamed heading, merge
  preserving completion, notes, schedules (identity kept so reminders stay attached) and user-edited titles.
* **Reminders:** create, edit, cancel, complete (one-time), snooze then fire, recurring completion completes
  only the current occurrence, repeat-until-completed, reboot reconstruction is idempotent with catch-up,
  missed occurrences inside/outside the window, no immediate fire when created after today's time, quiet hours.
* **Time:** DST spring-forward gap and fall-back overlap, daily wall-clock across DST, local vs. fixed
  time zone when the device zone changes.
* **Backup:** JSON round-trip, newer schema / foreign / damaged files rejected, dangling references rejected,
  Markdown export, JSON edge cases, demo data labelled.

## Files imported from Project-Checklists.zip

**NOT AVAILABLE.** The ZIP was not provided, so no real documents were inspected or imported and
no unclassifiable content can be listed. When you import it, the preview lists every file found and
flags every uncertain item with **REVIEW** and a reason.

## Next steps to complete the Definition of Done

1. Build with network access: `./gradlew assembleDebug testDebugUnitTest lintDebug`.
2. Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`; record the APK path, size and `sha256sum`.
3. Run `./gradlew connectedDebugAndroidTest` with the phone connected.
4. Follow the on-device test script: setup → import the real ZIP → preview → save → open Apple TV → complete a
   disposable test task → force-stop → reopen → confirm → reminder 2–5 min ahead → Snooze → Complete →
   export → restore (merge) → verify.
