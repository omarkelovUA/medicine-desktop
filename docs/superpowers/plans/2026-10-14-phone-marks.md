# Marking Doses from the Phone Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A dose is ticked/unticked by making its Google Calendar event green / not green on the phone; the app
reads the colours back on each sync.

**Architecture:** The gateway gets `listEvents`. After pushing, `CalendarSyncService` lists the person's events of
the last 7 days through today, decides per dose with the pure `RemoteMarks`, applies the result with guarded
`DoseRepository` writes that leave the dose `DIRTY`, and pushes again so the event takes the app's form.

**Tech Stack:** Java 21, JavaFX, SQLite (plain JDBC), Google Calendar API v3, JUnit 5 + AssertJ, Maven.

**Spec:** `docs/superpowers/specs/2026-10-14-phone-marks-design.md`

## Global Constraints

- Green = taken: `colorId` `"10"` (Basil) or `"2"` (Sage). Anything else, or `null`, = not taken.
- Own-day rule: the event's `updated` instant, in the clock's zone, must fall on the dose's `scheduledAt` date.
- Time of a phone tick = the event's `updated` instant in the clock's zone, as `LocalDateTime`.
- Pull window: `today.minusDays(7).atStartOfDay()` (inclusive) to `today.plusDays(1).atStartOfDay()` (exclusive).
- Only `CLEAN` doses are changed by a pull; every pull write is a single guarded SQL statement.
- Events are never created with attendees; no `sendUpdates` changes.
- Periodic sync in `AppJobs`: every 5 minutes (was 15).
- All UI text in Ukrainian, via the resource bundle (no new UI strings are expected; README is Ukrainian).

## Review Focus

- A dose ticked in the app between the pull's read and its write → the app's tick stays (Task 2 guard test).
- A phone tick made on the dose's day but seen the next morning, after the dose was marked missed → taken, missed
  cleared (Task 3 test `phoneTickSeenNextDayClearsMissed`).
- An edit at 23:59 vs 00:01 of the next day → accepted vs restored (Task 1 midnight tests).
- A `DELETE_PENDING` dose whose event is still listed → ignored, not revived (Task 2 finder test).
- More than one page of events from Google → all pages read (Task 3 Step 3, review the `nextPageToken` loop).

---

### Task 1: `RemoteEvent` and `RemoteMarks`

**Files:**
- Create: `src/main/java/ua/medicine/sync/RemoteEvent.java`
- Create: `src/main/java/ua/medicine/sync/RemoteMarks.java`
- Test: `src/test/java/ua/medicine/sync/RemoteMarksTest.java`

**Interfaces:**
- Produces:
  - `public record RemoteEvent(String id, String colorId, Instant updated)`
  - `final class RemoteMarks` with
    - `sealed interface Decision permits None, Take, Untake, Restore` — `record None()`, `record Take(LocalDateTime at)`,
      `record Untake()`, `record Restore()` (nested in `RemoteMarks`)
    - `static Decision decide(DoseOccurrence dose, RemoteEvent event, ZoneId zone)`
    - `static boolean isGreen(String colorId)`

- [ ] **Step 1: Write the failing tests** in `RemoteMarksTest` (dose scheduled `2026-10-13T08:00`, zone
  `ZoneId.systemDefault()`, helper `at(String isoLocal)` → `Instant`):
  - `dirtyDoseIsNeverChanged`: DIRTY, not taken, colour `"10"` edited `2026-10-13T09:00` → `None`
  - `notTakenAndNotGreenIsNone`: colour `null` and colour `"5"` → `None`
  - `takenAndGreenIsNone`: taken, colour `"10"` and `"2"` → `None`
  - `basilOnOwnDayTakes`: CLEAN, not taken, `"10"`, edited `2026-10-13T09:15` → `Take(2026-10-13T09:15)`
  - `sageOnOwnDayTakes`: same with `"2"` → `Take(...)`
  - `greenRemovedOnOwnDayUntakes`: taken, colour `null` edited `2026-10-13T10:00` → `Untake`; also with `"5"`
  - `greenOnLaterDayRestores`: not taken, `"10"`, edited `2026-10-14T07:00` → `Restore`
  - `greenRemovedOnLaterDayRestores`: taken, `null`, edited `2026-10-14T07:00` → `Restore`
  - `editAtOneMinuteToMidnightCounts`: edited `2026-10-13T23:59` → `Take(2026-10-13T23:59)`
  - `editOneMinuteAfterMidnightRestores`: edited `2026-10-14T00:01` → `Restore`

- [ ] **Step 2: Run** `mvn -q test -Dtest=RemoteMarksTest` — Expected: compilation failure (classes missing).

- [ ] **Step 3: Implement** `RemoteEvent` and `RemoteMarks` following the spec's table; "taken" is
  `dose.takenAt() != null`; edit time is `LocalDateTime.ofInstant(event.updated(), zone)`.

- [ ] **Step 4: Run** `mvn -q test -Dtest=RemoteMarksTest` — Expected: all pass.

- [ ] **Step 5: Commit** `feat: rules for dose marks made on the phone`

### Task 2: Guarded pull writes and the window finder in `DoseRepository`

**Files:**
- Modify: `src/main/java/ua/medicine/persistence/DoseRepository.java`
- Test: `src/test/java/ua/medicine/persistence/DoseRepositoryTest.java`

**Interfaces:**
- Produces (all `public`, each one SQL statement; the `boolean`s return "a row changed"):
  - `List<DoseOccurrence> findWithEventForPerson(long personId, LocalDateTime from, LocalDateTime toExclusive)` —
    visible (not `DELETE_PENDING`) doses of the person's medicines with `calendar_event_id IS NOT NULL` and
    `scheduled_at` in `[from, toExclusive)`
  - `boolean markTakenFromPhone(long id, LocalDateTime at)` — `taken_at=at, missed=0, sync_state='DIRTY'`
    `WHERE id=? AND taken_at IS NULL AND sync_state='CLEAN'`
  - `boolean unmarkTakenFromPhone(long id, boolean missed)` — `taken_at=NULL, missed=?, sync_state='DIRTY'`
    `WHERE id=? AND taken_at IS NOT NULL AND sync_state='CLEAN'`
  - `boolean markDirtyIfClean(long id)` — `sync_state='DIRTY' WHERE id=? AND sync_state='CLEAN'`
- `execute` returns the update count so these can return `> 0`.

- [ ] **Step 1: Write the failing tests** in `DoseRepositoryTest`:
  - `findWithEventForPersonSkipsUnpushedDeletedAndOtherPersons`: doses with/without event id, one
    `DELETE_PENDING` with event id, one of a second person, one outside the window → only the pushed, visible,
    own, in-window doses returned
  - `markTakenFromPhoneOnCleanDose`: CLEAN, missed → taken at the given time, `missed=false`, DIRTY, returns `true`
  - `markTakenFromPhoneLeavesDirtyDose`: DIRTY row → unchanged, returns `false` (the app's tick wins)
  - `markTakenFromPhoneLeavesTakenDose`: already taken → `taken_at` unchanged, returns `false`
  - `unmarkTakenFromPhoneSetsMissed`: CLEAN taken, `missed=true` → `taken_at` null, missed, DIRTY, `true`;
    on a DIRTY row → unchanged, `false`
  - `markDirtyIfCleanOnlyTouchesClean`: CLEAN → DIRTY `true`; `DELETE_PENDING` → unchanged `false`

- [ ] **Step 2: Run** `mvn -q test -Dtest=DoseRepositoryTest` — Expected: compilation failure.

- [ ] **Step 3: Implement** the four methods in the style of `recordPushed` / `findNeedingSync`.

- [ ] **Step 4: Run** `mvn -q test -Dtest=DoseRepositoryTest` — Expected: all pass.

- [ ] **Step 5: Commit** `feat: guarded dose writes for marks pulled from the phone`

### Task 3: Pull in `CalendarSyncService`

**Files:**
- Modify: `src/main/java/ua/medicine/sync/CalendarGateway.java`, `GoogleCalendarGateway.java`,
  `CalendarSyncService.java`, `SyncResult.java`
- Modify: `src/main/java/ua/medicine/ui/DashboardController.java` (`onSyncResult`)
- Modify: `src/test/java/ua/medicine/sync/FakeCalendarGateway.java`, `src/test/java/ua/medicine/ui/SyncStatusTest.java`
- Test: `src/test/java/ua/medicine/sync/CalendarSyncServiceTest.java`

**Interfaces:**
- Consumes: `RemoteEvent`, `RemoteMarks.decide` (Task 1); `findWithEventForPerson`, `markTakenFromPhone`,
  `unmarkTakenFromPhone`, `markDirtyIfClean` (Task 2).
- Produces:
  - `CalendarGateway.listEvents(String calendarId, LocalDateTime from, LocalDateTime toExclusive): List<RemoteEvent>`
    (javadoc as in the spec; `@throws CalendarNotFoundException` if the calendar is gone)
  - `record SyncResult(int pushed, int pulled, int deleted, int failed, Set<Long> authFailedPersonIds, String lastError)`
  - `FakeCalendarGateway`: keeps `Map<String, String> colors` and `Map<String, Instant> updated` per event id;
    insert/patch set the colour to `"10"` when `event.taken()` else `null` and `updated` to `now` (a settable
    `Instant now` field, default `Instant.EPOCH`); `void phoneEdit(String eventId, String colorId, Instant at)`
    changes only colour and `updated`; `listEvents` returns events whose `start` is in the range; `failList`
    flag throws `UncheckedIOException` from `listEvents` only.

- [ ] **Step 1: Write the failing tests** in `CalendarSyncServiceTest` (sync once first so doses are CLEAN; edit via
  `fake.phoneEdit`; instants via `dt(...).atZone(ZoneId.systemDefault()).toInstant()`):
  - `phoneTickTakesDoseAndPushesItBack`: Basil on the 08:00 event at 09:10 → dose `takenAt` 09:10, CLEAN after the
    run, event title starts with `✅`, `r.pulled()==1`
  - `sageTickBecomesBasil`: Sage → after the run `fake.colors` for the event is `"10"`
  - `phoneUntickUntakesDose`: dose ticked in the app and synced, then colour `null` on the same day → not taken,
    title starts with `💊`
  - `phoneTickSeenNextDayClearsMissed`: Basil at `2026-10-13T09:10`, clock moved to `2026-10-14T08:00`,
    `doseService.resolveMissed()` was run → dose taken at 09:10, `missed()==false`
  - `phoneTickOnLaterDayIsRestored`: Basil edited `2026-10-14T07:00`, clock `2026-10-14T08:00` → dose not taken,
    event colour back to `null`, `r.pulled()==0`
  - `appTickBeforePullWins`: app ticks the 20:00 dose (DIRTY) and the phone sets that event to colour `"5"` → after
    the run the dose is taken and the event colour is `"10"`
  - `listFailureCountsAsFailedAndChangesNothing`: `fake.failList = true` → `r.failed()==1`, doses unchanged

- [ ] **Step 2: Run** `mvn -q test -Dtest=CalendarSyncServiceTest` — Expected: compilation failure.

- [ ] **Step 3: Implement**
  - `GoogleCalendarGateway.listEvents`: `client.events().list(calendarId).setTimeMin(...).setTimeMax(...)
    .setShowDeleted(false).setSingleEvents(true)`, loop on `getNextPageToken()`; map each to
    `new RemoteEvent(e.getId(), e.getColorId(), Instant.ofEpochMilli(e.getUpdated().getValue()))`; wrap in
    `call(null, calendarId, ...)` so 404 → `CalendarNotFoundException`.
  - `SyncResult`: add `pulled`; update every `new SyncResult(...)` (service and `SyncStatusTest`).
  - `CalendarSyncService.syncPerson`: after the push loop, `pull(...)`; if it changed any dose (take, untake or
    restore), run the push loop once more. `pull`: list the window, index doses from `findWithEventForPerson` by
    event id, `RemoteMarks.decide(dose, event, clock.getZone())`, apply:
    `Take` → `markTakenFromPhone`, `Untake` → `unmarkTakenFromPhone(id, dose.scheduledAt().toLocalDate().isBefore(today))`,
    `Restore` → `markDirtyIfClean`. Count `run.pulled` for successful `Take`/`Untake`. `UncheckedIOException`
    from `listEvents` is caught like a push failure (`run.failed++`, `run.lastError`, log).
  - `DashboardController.onSyncResult`: call `refresh()` also when `result.pulled() > 0`.

- [ ] **Step 4: Run** `mvn -q test` — Expected: whole suite passes.

- [ ] **Step 5: Commit** `feat: read dose marks back from Google Calendar`

### Task 4: Sync every 5 minutes and README

**Files:**
- Modify: `src/main/java/ua/medicine/AppJobs.java` (class javadoc and the `scheduleAtFixedRate` for sync)
- Modify: `README.md`

- [ ] **Step 1:** In `AppJobs.start()` change the sync period from `15, 15, TimeUnit.MINUTES` to `5, 5, TimeUnit.MINUTES`;
  update the javadoc ("sync every 5 min").

- [ ] **Step 2:** README: add «- Позначки «прийнято» можна ставити й з телефона — у Google Календарі.» to the
  feature list, and a section after «Налаштування Google Календаря»:

```markdown
## Позначка з телефона

Дозу можна позначити прийнятою прямо в Google Календарі на телефоні:

1. Відкрийте подію дози в календарі «Ліки» → ✏️ Редагувати.
2. Виберіть колір **Базилік** або **Шавлія** (зелений) → Зберегти.

Щоб зняти позначку, поверніть стандартний колір події. Змінювати колір потрібно в день прийому — зміни, зроблені
пізніше, застосунок скасує. Застосунок підхоплює позначки під час синхронізації: при запуску і кожні 5 хвилин, поки
він відкритий; після цього подія отримує «✅» у назві.
```

- [ ] **Step 3: Run** `mvn -q test` — Expected: passes.

- [ ] **Step 4: Commit** `docs: marking doses from the phone; sync every 5 minutes`
