# Medicine periods Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One medicine has many taking periods (start + duration); the dashboard shows them on one line, the history
shows one medicine with all its periods, and existing same-name duplicates are merged.

**Architecture:** New domain record `Period` and a `period` table; `Medicine` holds a sorted, non-overlapping
`List<Period>`. Dose generation, cards, timeline and history read periods; the dialog edits them. A Flyway SQL
migration moves the dates into `period`, a Flyway Java migration merges duplicates.

**Tech Stack:** Java 21, JavaFX, SQLite (sqlite-jdbc 3.46), Flyway 9.22, JUnit 5 + AssertJ, Maven.

**Spec:** `docs/superpowers/specs/2026-10-14-medicine-periods-design.md`

## Global Constraints

- All UI text in Ukrainian, in `src/main/resources/ua/medicine/i18n/messages_uk.properties` (never hard-coded).
- New message keys and exact texts:
  - `field.periods=Періоди прийому`
  - `btn.addPeriod=Додати період`
  - `btn.endToday=Завершити сьогодні`
  - `tooltip.endOngoingFirst=Спершу завершіть поточний період`
  - `error.periodsOverlap=Періоди не повинні перетинатися`
  - `error.ongoingNotLast=Лише останній період може бути постійним`
  - `confirm.addPeriodToExisting=«{0}» вже є. Додати новий період до наявних ліків?`
  - `card.nextPeriod=Наступний період з {0}`
  - `history.periods=Періоди: {0}`
  - `history.col.period=Період`
- Name matching (dialog prompt and merge): `name.strip().toLowerCase(Locale.ROOT)` within one person.
- Period range text (`Formats.periodRange`): `01.01–30.01.2026` (same year), `15.12.2025–14.01.2026` (different
  years), `01.08.2026–…` (ongoing); periods joined with ` · `.
- Each task ends with `mvn -q test` green and a commit ending in
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

- A medicine whose periods all lie outside the visible timeline range must not leave an empty row (Task 5 test
  `periodsOnlyAroundRangeHidden`).
- Editing an existing multi-period medicine and saving without touching periods must keep every period and every
  taken dose (Task 3 test `fromAndBackKeepsAllPeriods`, Task 4 test `updateKeepsTakenDosesOfEarlierPeriods`).
- Removing a future period must delete its untaken doses, and their Google events (`DELETE_PENDING`) (Task 4 test
  `removingFuturePeriodDropsItsDoses`).
- Merge with Cyrillic names that differ only in case/spaces («Вітамін A» vs « вітамін a ») (Task 8 test
  `mergesCyrillicCaseInsensitive`).
- «Так» on the same-name prompt when the new period overlaps an existing one must save nothing and show the overlap
  error (Task 4 test `appendOverlappingPeriodThrows`).

---

### Task 1: Periods in the domain and in storage

**Files:**
- Create: `src/main/java/ua/medicine/domain/Period.java`
- Create: `src/main/resources/db/migration/V2__periods.sql`
- Modify: `src/main/java/ua/medicine/domain/Medicine.java`
- Modify: `src/main/java/ua/medicine/persistence/MedicineRepository.java`
- Modify: `src/main/java/ua/medicine/schedule/ScheduleExpander.java`, `src/main/java/ua/medicine/schedule/CourseProgress.java`
- Modify (mechanical, see Step 7): `MedicineCard.java`, `TimelineRows.java`, `TimelineView.java`, `TimeLabels.java`, `MedicineForm.java`
- Test: `src/test/java/ua/medicine/domain/PeriodTest.java`, `MedicineTest.java` (new), `persistence/MedicineRepositoryTest.java`, `schedule/ScheduleExpanderTest.java`, `schedule/CourseProgressTest.java`, `testutil/T.java`

**Interfaces:**
- Produces:
  - `record Period(LocalDate start, Course course)`: `Optional<LocalDate> endDate()`, `boolean contains(LocalDate)`, `boolean isFinished(LocalDate today)`, `Period withCourse(Course)`.
  - `record Medicine(Long id, long personId, String name, String dosage, String color, List<Period> periods, Schedule schedule, boolean active)`; the canonical constructor copies `periods` with `List.copyOf` and validates (messages: `"at least one period is required"`, `"periods must be sorted and must not overlap"`, `"only the last period may be ongoing"`).
  - Convenience constructor `Medicine(Long id, long personId, String name, String dosage, String color, LocalDate start, Course course, Schedule schedule, boolean active)` = one period. Keeps existing callers and tests compiling.
  - `LocalDate firstStart()`, `Period lastPeriod()`, `Optional<LocalDate> endDate()` (last period's), `boolean isFinished(LocalDate)` (last period's), `Optional<Period> periodAt(LocalDate)`, `Period relevantPeriod(LocalDate today)` (containing today, else first starting after today, else last), `Medicine withPeriods(List<Period>)`. `startDate()` and `course()` are removed.
  - `static Medicine.checkPeriods(List<Period>)` throwing the `IllegalArgumentException`s above; reused by the form (Task 3).
  - `CourseProgress.of(Period period, LocalDate today)` (was `of(Medicine, …)`).

- [ ] **Step 1: Write failing tests**

`PeriodTest`: `fixedEndDate` (start 2026-01-01, `Fixed(30, DAYS)` → end 2026-01-30), `ongoingHasNoEnd`, `containsIsInclusive` (both ends true, day after false, day before false), `isFinishedAfterEnd`.

`MedicineTest` (periods P1 = 2026-01-01 + 30 days, P2 = 2026-08-01 + 30 days, P3 = 2026-09-15 ongoing):
- `rejectsEmptyPeriods`, `rejectsUnsorted` (P2, P1), `rejectsOverlap` (P1 and 2026-01-30 + 5 days), `rejectsOngoingNotLast` (P3 at 2026-01-01 ongoing, then P2) — each `assertThatThrownBy(...).isInstanceOf(IllegalArgumentException.class)`.
- `adjacentPeriodsAllowed` (P1 then 2026-01-31 + 5 days).
- `relevantPeriod`: on 2026-01-10 → P1; on 2026-05-01 (rest) → P2; on 2026-12-01 → P2 (last).
- `isFinishedUsesLastPeriod`: on 2026-05-01 false, on 2026-09-01 true.
- `periodAt`: 2026-05-01 → empty; 2026-08-05 → P2.

`ScheduleExpanderTest`: `noDosesDuringRest` — daily 08:00, periods P1 and P2, expand 2026-01-29..2026-08-02 → exactly `2026-01-29T08:00, 2026-01-30T08:00, 2026-08-01T08:00, 2026-08-02T08:00`.

`CourseProgressTest`: switch the existing tests to `CourseProgress.of(period, today)`, values unchanged.

`MedicineRepositoryTest`: `periodsRoundTrip` (save with P1, P2 → `findById` equals the saved medicine); `saveReplacesPeriods` (save with P1, P2, then `withPeriods(List.of(P2))` saved → reloaded has only P2).

- [ ] **Step 2: Run** `mvn -q test` — expected: compilation errors for `Period`, `CourseProgress.of(Period, …)`.

- [ ] **Step 3: Migration `V2__periods.sql`**

```sql
CREATE TABLE period (
    id            INTEGER PRIMARY KEY,
    medicine_id   INTEGER NOT NULL REFERENCES medicine (id),
    start_date    TEXT    NOT NULL,
    course_amount INTEGER,
    course_unit   TEXT
);
CREATE INDEX period_medicine ON period (medicine_id);
INSERT INTO period (medicine_id, start_date, course_amount, course_unit)
SELECT id, start_date, course_amount, course_unit FROM medicine;
ALTER TABLE medicine DROP COLUMN start_date;
ALTER TABLE medicine DROP COLUMN course_amount;
ALTER TABLE medicine DROP COLUMN course_unit;
```

- [ ] **Step 4: Implement `Period` and the `Medicine` changes** per the Interfaces block.

- [ ] **Step 5: Repository.** Remove the three columns from INSERT/UPDATE/`bind`/`map`. `save` (inside the same `db.tx`) deletes the medicine's `period` rows and inserts its periods (amount/unit via `MedicineCodec.courseAmount/courseUnit`). Every query loads periods: after mapping medicine rows, run `SELECT * FROM period WHERE medicine_id=? ORDER BY start_date` per medicine (row lists are small). `deleteInactiveByPerson` also deletes their `period` rows before the medicines.

- [ ] **Step 6: `ScheduleExpander.expand`** iterates `m.periods()`, clipping each to `[from, to]`; schedule matching unchanged. `CourseProgress.of(Period, today)` is the old logic on the period.

- [ ] **Step 7: Mechanical callers** (behaviour for single-period medicines unchanged; Tasks 3, 5, 6 replace these):
  - `MedicineCard`: `CourseProgress.of(medicine.relevantPeriod(today), today)`; `card.ongoing` uses `medicine.relevantPeriod(today).start()`.
  - `TimelineRows.overlaps` and `ORDER`, `TimelineView.medicineRow`, `TimeLabels.tooltip`: use `firstStart()` / `endDate()`.
  - `MedicineForm.from`: read `lastPeriod()`; `toMedicine` keeps building one period. (Editing a multi-period medicine collapses it until Task 3 — none exist until Task 8.)
  - `T.med(...)`: unchanged (convenience constructor).

- [ ] **Step 8: Run** `mvn -q test` — expected: all pass.

- [ ] **Step 9: Commit** — `feat: medicine periods in domain and storage`

---

### Task 2: Period range formatting

**Files:**
- Modify: `src/main/java/ua/medicine/i18n/Formats.java`
- Test: `src/test/java/ua/medicine/i18n/FormatsTest.java`

**Interfaces:**
- Produces: `static String Formats.periodRange(Period p)`, `static String Formats.periodRanges(List<Period> periods)` (joined with ` · `).

- [ ] **Step 1: Failing tests** — `periodRangeSameYear` (2026-01-01 + 30 days → `01.01–30.01.2026`), `periodRangeAcrossYears` (2025-12-15 + 31 days → `15.12.2025–14.01.2026`), `periodRangeOngoing` (2026-08-01 ongoing → `01.08.2026–…`), `periodRangesJoined` (both first and third → `01.01–30.01.2026 · 01.08.2026–…`).
- [ ] **Step 2: Run** `mvn -q test -Dtest=FormatsTest` — expected: FAIL (no such method).
- [ ] **Step 3: Implement** with a `dd.MM` formatter plus the existing `DATE` (`dd.MM.yyyy`).
- [ ] **Step 4: Run** `mvn -q test` — expected: all pass.
- [ ] **Step 5: Commit** — `feat: period range formatting`

---

### Task 3: Periods in `MedicineForm`

**Files:**
- Modify: `src/main/java/ua/medicine/ui/MedicineForm.java`
- Test: `src/test/java/ua/medicine/ui/MedicineFormTest.java`

**Interfaces:**
- Consumes: `Period`, `Medicine.checkPeriods` (Task 1).
- Produces:
  - `record MedicineForm.PeriodRow(LocalDate start, boolean ongoing, Integer amount, PeriodUnit unit)` with `Period toPeriod()`.
  - `MedicineForm(Long personId, String name, String dosage, String color, List<PeriodRow> periods, String frequency, Set<DayOfWeek> weekdays, Integer dayOfMonth, List<LocalTime> times)` — replaces `start, ongoing, amount, unit`.
  - `validate()` keys: existing `name`, `weekdays`, `times`; `amount` → `error.amount` if any fixed row has `amount == null || < 1`; new field `periods` → `error.ongoingNotLast` or `error.periodsOverlap` (rows sorted by start first; ongoing check first).
  - `toMedicine(Long id, boolean active)` builds periods sorted by start.
  - `static PeriodRow nextPeriod(List<PeriodRow> rows, LocalDate today)`: starts the day after the last row's end, same amount/unit; with no rows: `today`, 10 days. Precondition: last row not ongoing.
  - `static PeriodRow endToday(PeriodRow ongoing, LocalDate today)`: fixed, `DAYS`, amount = days from start through today inclusive (min 1).

- [ ] **Step 1: Failing tests** (adapt `valid()` / `with(...)` to one `PeriodRow(d("2026-10-13"), false, 3, MONTHS)`):
  - `fromAndBackKeepsAllPeriods`: medicine with P1, P2 → `MedicineForm.from(m).toMedicine(m.id(), true)` equals `m`.
  - `overlapError`: rows 2026-01-01 + 30 days and 2026-01-20 + 5 days → `containsEntry("periods", "error.periodsOverlap")`.
  - `unsortedRowsAreSortedNotErrors`: rows P2 then P1 → no errors; `toMedicine` periods = [P1, P2].
  - `ongoingNotLastError`: ongoing 2026-01-01, then 2026-08-01 + 30 days → `error.ongoingNotLast`.
  - `nextPeriodStartsAfterLastEnd`: after 2026-01-01 + 30 days → `PeriodRow(2026-01-31, false, 30, DAYS)`.
  - `nextPeriodWithNoRows`: today 2026-10-14 → `PeriodRow(2026-10-14, false, 10, DAYS)`.
  - `endTodayMakesFixedDays`: ongoing from 2026-10-01, today 2026-10-14 → `PeriodRow(2026-10-01, false, 14, DAYS)`.
- [ ] **Step 2: Run** `mvn -q test -Dtest=MedicineFormTest` — expected: compilation failure.
- [ ] **Step 3: Implement**; update `MedicineDialogController.form()` / its `form != null` branch minimally to build/read a single `PeriodRow` from the existing fields so the build stays green (Task 4 replaces the UI).
- [ ] **Step 4: Run** `mvn -q test` — expected: all pass.
- [ ] **Step 5: Commit** — `feat: medicine form with periods`

---

### Task 4: Service for same-name medicines, and the periods dialog

**Files:**
- Modify: `src/main/java/ua/medicine/service/MedicineService.java`, `src/main/java/ua/medicine/persistence/MedicineRepository.java`
- Modify: `src/main/java/ua/medicine/ui/MedicineDialogController.java`, `messages_uk.properties`, `src/main/resources/ua/medicine/ui/theme.css` (only if the period rows need spacing), `README.md`
- Test: `src/test/java/ua/medicine/service/MedicineServiceTest.java`

**Interfaces:**
- Consumes: `MedicineForm.PeriodRow`, `nextPeriod`, `endToday` (Task 3).
- Produces:
  - `MedicineRepository.findAllByPerson(long personId)` (active and inactive).
  - `Optional<Medicine> MedicineService.findSameName(long personId, String name)` — active preferred, else latest `lastPeriod().start()`; match per Global Constraints.
  - `Medicine MedicineService.appendPeriods(long existingId, Medicine fromForm)` — existing periods + `fromForm.periods()`, sorted; dosage, colour, schedule from `fromForm`; `active = true`; goes through `update`. Overlap → `IllegalArgumentException` from `Medicine`, nothing saved.

- [ ] **Step 1: Failing service tests** (follow the existing `MedicineServiceTest` setup with `MutableClock`, today 2026-10-14):
  - `addingPeriodGeneratesDosesOnlyInside`: medicine with P1 (2026-10-01 + 5 days, finished) → `update(withPeriods(P1, 2026-10-20 + 3 days))` → future doses dated only 2026-10-20..22.
  - `updateKeepsTakenDosesOfEarlierPeriods`: take a dose on 2026-10-02, add a period → the taken dose is still there with `takenAt`.
  - `removingFuturePeriodDropsItsDoses`: periods P1 (current) and 2026-10-20 + 3 days; give one 2026-10-20 dose a `calendarEventId`; update with P1 only → that dose is `DELETE_PENDING`, others of 20–22 gone.
  - `findSameNameIgnoresCaseAndSpaces`: saved «Вітамін A» → `findSameName(1, " вітамін a ")` present; other person → empty.
  - `appendPeriodsReactivates`: deleted medicine with P1 → `appendPeriods` with 2026-10-20 + 3 days → active, two periods, form's dosage.
  - `appendOverlappingPeriodThrows`: `assertThatThrownBy(...)`, and `findById` still has one period.
- [ ] **Step 2: Run** `mvn -q test -Dtest=MedicineServiceTest` — expected: FAIL (missing methods; the first three may already pass — that is fine, they pin behaviour).
- [ ] **Step 3: Implement the service and repository methods.**
- [ ] **Step 4: Dialog.** Replace the start/duration fields with a «Періоди прийому» section (`field.periods`): one row per `PeriodRow` — `DatePicker`, «Тривалість»/«Постійно» radios, amount spinner, unit combo, end label (reuse the current widgets' setup, per row), «✕» (disabled when one row left), and «Завершити сьогодні» on an ongoing row whose start ≤ today. Below: «Додати період» (disabled + `tooltip.endOngoingFirst` while the last row is ongoing). One `errors` label `periods` under the section; `amount` error stays. In `trySave`, for a new medicine: `findSameName` → if present, `Dialogs.confirm(window, Messages.get("confirm.addPeriodToExisting", existing.name()))`; yes → `appendPeriods` (on `IllegalArgumentException` show `error.periodsOverlap` in the `periods` label and keep the dialog open); no → `create`.
- [ ] **Step 5: Messages** — add all keys from Global Constraints used here.
- [ ] **Step 6: README** — under the feature list add «Ліки можна приймати кількома періодами з перервами: у вікні ліків «Додати період».»
- [ ] **Step 7: Run** `mvn -q test` — expected: all pass.
- [ ] **Step 8: Visual check** (classpath and command as in `docs/superpowers/plans/2026-10-13-timeline-dashboard.md` Task "Visual check"): `SnapshotMain <fresh dir> <out> seed addMedicine` — the dialog shows the periods section with one row and «Додати період»; readable in light and dark themes.
- [ ] **Step 9: Commit** — `feat: add periods in the medicine dialog`

---

### Task 5: Timeline — several bars per row

**Files:**
- Modify: `src/main/java/ua/medicine/ui/TimelineRows.java`, `TimelineView.java`, `TimeLabels.java`
- Test: `src/test/java/ua/medicine/ui/TimelineRowsTest.java`, `TimeLabelsTest.java`

**Interfaces:**
- Produces: `static List<Period> TimelineRows.visiblePeriods(Medicine m, TimeScale scale)`; `TimeLabels.tooltip(Medicine m, Period p)`.

- [ ] **Step 1: Failing tests** (range = `OCTOBER`, 2026-10-01..31):
  - `visiblePeriodsClipsToRange`: periods 2026-09-01 + 10 days, 2026-10-05 + 3 days, 2026-10-20 + 30 days → the last two.
  - `periodsOnlyAroundRangeHidden`: periods 2026-09-01 + 10 days and 2026-12-01 + 5 days → `build(...)` empty.
  - `orderByFirstVisibleStart`: medicine A (2026-01-01 + 10 days, 2026-10-20 + 5 days) and B (2026-10-10 + 5 days) → `B, A`.
  - `TimeLabelsTest.tooltipPerPeriod`: same expected strings as the existing tooltip tests, built from `(m, period)`.
- [ ] **Step 2: Run** `mvn -q test -Dtest='TimelineRowsTest,TimeLabelsTest'` — expected: FAIL.
- [ ] **Step 3: Implement.** `overlaps` and `ORDER` use `visiblePeriods`. `TimelineView.medicineRow` loops over `visiblePeriods(m, scale)`, placing one bar + label per period with the existing cut/arrow/radius logic and that period's tooltip; click opens the medicine.
- [ ] **Step 4: Run** `mvn -q test` — expected: all pass.
- [ ] **Step 5: Visual check** — add to `SnapshotMain.seed()` a medicine «Вітамін A» with periods today−200 + 30 days and today−20 + 2 months; snapshot `timeline:MONTHS` and `timeline:YEARS`: one row, two bars with a gap.
- [ ] **Step 6: Commit** — `feat: timeline shows every period of a medicine`

---

### Task 6: Cards during a rest

**Files:**
- Modify: `src/main/java/ua/medicine/ui/MedicineCard.java`, `messages_uk.properties`

**Interfaces:**
- Consumes: `Medicine.relevantPeriod`, `Medicine.periodAt` (Task 1).

- [ ] **Step 1: Implement.** If `medicine.periodAt(today)` is empty and `relevantPeriod(today).start()` is after today: hide the progress bar, text = `card.nextPeriod` with `Formats.dayMonth(start)`. Otherwise the current behaviour on `relevantPeriod(today)`. (Pure logic is already covered by `MedicineTest.relevantPeriod`; this is wiring.)
- [ ] **Step 2: Run** `mvn -q test` — expected: all pass.
- [ ] **Step 3: Visual check** — seed a medicine in a rest (periods today−60 + 10 days, today+10 + 10 days); cards snapshot shows «Наступний період з …».
- [ ] **Step 4: Commit** — `feat: medicine card shows the next period during a rest`

---

### Task 7: History — period list and «Період» column

**Files:**
- Modify: `src/main/java/ua/medicine/service/HistoryRow.java`, `HistoryService.java`, `src/main/java/ua/medicine/ui/HistoryController.java`, `messages_uk.properties`
- Test: `src/test/java/ua/medicine/service/HistoryServiceTest.java`

**Interfaces:**
- Consumes: `Formats.periodRange`, `Formats.periodRanges` (Task 2).
- Produces: `HistoryRow` gains a final component `String period` (`""` when the dose is outside every period); `Optional<String> HistoryService.periodsLine(Long medicineId)` → `history.periods` text, empty when `medicineId` is null.

- [ ] **Step 1: Failing tests:** `rowCarriesPeriodRange` (dose on 2026-01-05 of P1 → `period` = `01.01–30.01.2026`); `doseOutsidePeriodsHasEmptyPeriod`; `periodsLineListsAll` (P1 + ongoing 2026-08-01 → `Періоди: 01.01–30.01.2026 · 01.08.2026–…`); `periodsLineEmptyForAllMedicines`.
- [ ] **Step 2: Run** `mvn -q test -Dtest=HistoryServiceTest` — expected: FAIL.
- [ ] **Step 3: Implement**; in `HistoryController` add a muted `Label` between filters and tiles, set from `periodsLine` in `reload()` (hidden when empty), and a `history.col.period` column after «Ліки».
- [ ] **Step 4: Run** `mvn -q test` — expected: all pass.
- [ ] **Step 5: Visual check** — `SnapshotMain <dir> <out> seed history` after selecting «Вітамін A» (add a `history:<medicine name>` step to `Steps` that selects that combo item).
- [ ] **Step 6: Commit** — `feat: history shows the periods of a medicine`

---

### Task 8: Merge existing duplicates

**Files:**
- Create: `src/main/java/db/migration/V3__merge_duplicate_medicines.java`
- Test: `src/test/java/ua/medicine/persistence/MergeDuplicatesMigrationTest.java`

**Interfaces:**
- Produces: `public class V3__merge_duplicate_medicines extends org.flywaydb.core.api.migration.BaseJavaMigration` (Flyway finds it under `classpath:db/migration`). Pure core `static List<Merge> plan(List<Row> medicines, Map<Long, List<PeriodRowData>> periods, Map<Long, Set<String>> doseTimes)` with `record Merge(long survivorId, List<Long> absorbedIds, boolean active)` — kept package-private-testable; `migrate(Context)` reads tables, calls `plan`, then for each merge: `UPDATE period SET medicine_id=survivor WHERE medicine_id IN absorbed`, same for `dose`, `UPDATE medicine SET active=?`, `DELETE FROM medicine WHERE id IN absorbed`.

- [ ] **Step 1: Failing tests.** Build a database migrated only to version 2 (`Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration").target("2").load().migrate()`), insert rows with plain SQL, then migrate fully:
  - `mergesCyrillicCaseInsensitive`: «Вітамін A» (P1, inactive) and « вітамін a » (P2, active, other dosage) of person 1 → one medicine, id of the second, periods [P1, P2], its dosage, active; doses of both now point to it with `calendar_event_id` unchanged.
  - `overlappingPeriodsNotMerged`: two «Магній» with overlapping periods → both remain.
  - `clashingDoseTimesNotMerged`: non-overlapping periods but both have a dose at `2026-01-05T08:00` → both remain.
  - `otherPersonsNotMerged`: same name, persons 1 and 2 → both remain.
  - `allInactiveStaysInactive`: two deleted duplicates → merged, inactive, survivor = latest last-period start.
- [ ] **Step 2: Run** `mvn -q test -Dtest=MergeDuplicatesMigrationTest` — expected: FAIL (class missing / rows unmerged).
- [ ] **Step 3: Implement** per spec «Merging duplicates»: survivor = active with latest last-period start (ties: higher id), else latest overall; `active` = any active.
- [ ] **Step 4: Run** `mvn -q test` — expected: all pass.
- [ ] **Step 5: Commit** — `feat: merge same-name medicines into periods`

---

### Task 9: Final verification

- [ ] **Step 1:** `mvn -q test` — all pass.
- [ ] **Step 2:** Copy the real database to a scratch dir (`cp ~/.medicine/medicine.db <scratch>/data/`), run `SnapshotMain <scratch>/data <out>` (no seed) and check the dashboard and the timeline render; the original `~/.medicine` is not touched.
- [ ] **Step 3:** README «Шкала часу» section: replace «Кожні ліки — окремий рядок; смуга …» with «Кожні ліки — окремий рядок; кожен період прийому — окрема смуга, перерви між ними порожні.» Commit — `docs: README on medicine periods`.
