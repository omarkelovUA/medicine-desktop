# Timeline Dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Gantt-style timeline view (one bar per medicine course, zoomable days → years) next to the existing cards dashboard, switchable on every tab.

**Architecture:** Pure date maths in `TimeScale` (schedule package) and pure row selection in `TimelineRows`; Ukrainian labels in `TimeLabels`; a JavaFX `TimelineView` that positions bars by `TimeScale.fraction(...)` in `layoutChildren()`; `DashboardController` swaps between the cards pane and the timeline. View and zoom persist in `Settings`.

**Tech Stack:** Java 21, JavaFX 21 (FXML + CSS), JUnit 5 + AssertJ, Maven.

**Spec:** `docs/superpowers/specs/2026-10-13-timeline-dashboard-design.md`

## Global Constraints

- All UI text in Ukrainian, from `messages_uk.properties`; bundle values must not contain ASCII apostrophes (`MessagesTest`).
- `Messages.get` uses `MessageFormat`: pass years as `String.valueOf(year)` (an int would format as `2,026`).
- All colours via theme tokens in `theme.css` / `dark.css`; the only inline colour is a bar's medicine colour.
- No database changes. Course end date is `Medicine.endDate()` (inclusive last day, empty = ongoing).
- Run tests with `mvn -q test`; the full suite must stay green after every task.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

- A medicine with an empty dosage → bar label and tooltip show just the name, no dangling ` · ` / ` — ` (test in Task 2).
- A very short course at coarse zoom (Амоксицилін, 10 days, at years zoom ≈ 0.5 % of the width) → bar still visible (min width 4 px) with its label outside the bar (visual check, Task 5).
- A course that starts before the range and ends after it, or an ongoing one started years ago → bar fills from the left edge; ongoing shows `→` (fraction clamping test in Task 1, visual in Task 5).
- Overlap boundaries → a course whose last day is the range's first day is shown; one starting on `endExclusive` is not (test in Task 2).
- Week columns and range labels across a year boundary → `29 груд – 4 січ`, `28 груд 2026 – 28 бер 2027` (test in Task 2).

---

### Task 1: `TimeScale`

**Files:**
- Create: `src/main/java/ua/medicine/schedule/TimeScale.java`
- Test: `src/test/java/ua/medicine/schedule/TimeScaleTest.java`

**Interfaces:**
- Produces:
  - `public final class TimeScale` (immutable)
  - `public enum Zoom { DAYS, WEEKS, MONTHS, QUARTERS, YEARS }` nested in `TimeScale`
  - `public record Column(LocalDate start, LocalDate endExclusive)` nested in `TimeScale`
  - `static TimeScale around(Zoom zoom, LocalDate anchor)`
  - `TimeScale previous()`, `TimeScale next()`
  - `Zoom zoom()`, `LocalDate start()`, `LocalDate endExclusive()`
  - `List<Column> columns()`
  - `double fraction(LocalDate date)`: days(start→date) / days(start→endExclusive), clamped to [0, 1]
  - `boolean contains(LocalDate date)`: start ≤ date < endExclusive

Range rules (spec table): DAYS = 1st of month, span 1 month; WEEKS = Monday of the week − 2 weeks, span 13 weeks; MONTHS = 1st of month − 2 months, span 12 months; QUARTERS = 1st day of quarter − 1 quarter, span 8 quarters; YEARS = 1 Jan − 1 year, span 5 years. `next()`/`previous()` shift `start` by one span (in the zoom's unit, e.g. `plusMonths(12)`), so DAYS columns follow each month's length.

- [ ] **Step 1: Write the failing tests** (anchor `LocalDate.of(2026, 10, 13)`, a Tuesday)

```java
@Test void daysShowsTheAnchorMonth() {
    var s = TimeScale.around(Zoom.DAYS, ANCHOR);
    assertThat(s.start()).isEqualTo(LocalDate.of(2026, 10, 1));
    assertThat(s.endExclusive()).isEqualTo(LocalDate.of(2026, 11, 1));
    assertThat(s.columns()).hasSize(31);
    assertThat(s.next().columns()).hasSize(30);              // November
}
@Test void weeksStartOnMondayTwoWeeksBack() {
    var s = TimeScale.around(Zoom.WEEKS, ANCHOR);
    assertThat(s.start()).isEqualTo(LocalDate.of(2026, 9, 28));
    assertThat(s.endExclusive()).isEqualTo(LocalDate.of(2026, 12, 28));
    assertThat(s.columns()).hasSize(13)
            .first().isEqualTo(new Column(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 5)));
}
@Test void monthsQuartersYears() {
    assertThat(TimeScale.around(Zoom.MONTHS, ANCHOR).start()).isEqualTo(LocalDate.of(2026, 8, 1));
    assertThat(TimeScale.around(Zoom.MONTHS, ANCHOR).columns()).hasSize(12);
    assertThat(TimeScale.around(Zoom.QUARTERS, ANCHOR).start()).isEqualTo(LocalDate.of(2026, 7, 1));
    assertThat(TimeScale.around(Zoom.QUARTERS, ANCHOR).endExclusive()).isEqualTo(LocalDate.of(2028, 7, 1));
    assertThat(TimeScale.around(Zoom.YEARS, ANCHOR).start()).isEqualTo(LocalDate.of(2025, 1, 1));
    assertThat(TimeScale.around(Zoom.YEARS, ANCHOR).columns()).hasSize(5);
}
@Test void quarterOfJanuaryAnchorGoesToPreviousYear() {
    assertThat(TimeScale.around(Zoom.QUARTERS, LocalDate.of(2026, 1, 15)).start())
            .isEqualTo(LocalDate.of(2025, 10, 1));
}
@Test void nextAndPreviousRoundTrip() {
    for (Zoom z : Zoom.values()) {
        var s = TimeScale.around(z, ANCHOR);
        assertThat(s.next().start()).isEqualTo(s.endExclusive());
        assertThat(s.next().previous().start()).isEqualTo(s.start());
    }
}
@Test void fractionIsProportionalToDaysAndClamped() {
    var s = TimeScale.around(Zoom.DAYS, ANCHOR);              // 31 days
    assertThat(s.fraction(LocalDate.of(2026, 10, 1))).isZero();
    assertThat(s.fraction(LocalDate.of(2026, 11, 1))).isEqualTo(1.0);
    assertThat(s.fraction(LocalDate.of(2026, 10, 2))).isCloseTo(1 / 31.0, within(1e-9));
    assertThat(s.fraction(LocalDate.of(2020, 1, 1))).isZero();
    assertThat(s.fraction(LocalDate.of(2030, 1, 1))).isEqualTo(1.0);
}
@Test void containsIsHalfOpen() {
    var s = TimeScale.around(Zoom.DAYS, ANCHOR);
    assertThat(s.contains(LocalDate.of(2026, 10, 1))).isTrue();
    assertThat(s.contains(LocalDate.of(2026, 11, 1))).isFalse();
}
```

- [ ] **Step 2: Run** `mvn -q test -Dtest=TimeScaleTest` — expected: compilation failure (no `TimeScale`).
- [ ] **Step 3: Implement `TimeScale`** per the Interfaces block and range rules.
- [ ] **Step 4: Run** `mvn -q test -Dtest=TimeScaleTest` — expected: all pass.
- [ ] **Step 5: Commit** — `feat: TimeScale for timeline zoom levels`

---

### Task 2: `TimeLabels`, `TimelineRows` and strings

**Files:**
- Create: `src/main/java/ua/medicine/ui/TimeLabels.java`, `src/main/java/ua/medicine/ui/TimelineRows.java`
- Modify: `src/main/resources/ua/medicine/i18n/messages_uk.properties` (append)
- Test: `src/test/java/ua/medicine/ui/TimeLabelsTest.java`, `src/test/java/ua/medicine/ui/TimelineRowsTest.java`

**Interfaces:**
- Consumes: `TimeScale`, `TimeScale.Column`, `TimeScale.Zoom` (Task 1).
- Produces:
  - `final class TimeLabels` (package-private, static only):
    - `static String column(Zoom zoom, Column c)` — main header text
    - `static String columnSub(Zoom zoom, Column c, boolean first)` — second header line: weekday for DAYS (`пн`), year for MONTHS when `first` or January (`2026`), else `""`
    - `static String range(TimeScale s)` — toolbar label
    - `static String barLabel(Medicine m)` — `Назва · дозування`, or `Назва` when dosage is blank
    - `static String tooltip(Medicine m)`
  - `final class TimelineRows` (package-private):
    - `sealed interface Row permits PersonHeader, MedicineRow`; `record PersonHeader(Person person)`; `record MedicineRow(Medicine medicine)`
    - `static List<Row> build(List<Person> persons, List<Medicine> medicines, boolean grouped, TimeScale scale)`:
      keeps medicines overlapping the scale (`start < endExclusive` and (ongoing or `end ≥ scale.start()`)),
      sorted by start date then name; when `grouped`, emits a `PersonHeader` before each person's medicines
      in `persons` order and skips persons with no visible medicine.

Strings to append (exact):

```properties
view.cards=Картки
view.timeline=Шкала часу
zoom.DAYS=Дні
zoom.WEEKS=Тижні
zoom.MONTHS=Місяці
zoom.QUARTERS=Квартали
zoom.YEARS=Роки
timeline.today=Сьогодні
timeline.prev=‹
timeline.next=›
month.short=січ,лют,бер,квіт,трав,черв,лип,серп,вер,жовт,лист,груд
month.full=січень,лютий,березень,квітень,травень,червень,липень,серпень,вересень,жовтень,листопад,грудень
weekday.short=пн,вт,ср,чт,пт,сб,нд
timeline.quarter={0} кв. {1}
timeline.tooltip.fixed={0}\n{1} – {2}
timeline.tooltip.ongoing={0}\nз {1} · Постійно
empty.noMedicinesInRange=Немає ліків у цьому періоді
```

Label formats: DAYS column `13`; WEEKS `6–12 жовт`, across months `28 вер – 4 жовт`, across years `29 груд – 4 січ`; MONTHS `жовт`; QUARTERS `IV кв. 2026` (Roman I–IV); YEARS `2026`. Ranges: DAYS `жовтень 2026`; WEEKS `28 вер – 27 груд 2026`, or with both years when they differ `28 груд 2026 – 28 бер 2027`; MONTHS `серп 2026 – лип 2027`; QUARTERS `III кв. 2026 – II кв. 2028`; YEARS `2025 – 2029`. Range end is the last included day (`endExclusive - 1`). Tooltip first line is `barLabel` with ` · ` replaced by ` — `; dates via `Formats.date`.

- [ ] **Step 1: Write the failing tests**

`TimeLabelsTest` (scales from `TimeScale.around(..., LocalDate.of(2026, 10, 13))`):
- `column(DAYS, …13 Oct)` = `"13"`, `columnSub(DAYS, …13 Oct, false)` = `"вт"`
- `column(WEEKS, 6–13 Oct)` = `"6–12 жовт"`; `(28 Sep–5 Oct)` = `"28 вер – 4 жовт"`; `(29 Dec 2025–5 Jan 2026)` = `"29 груд – 4 січ"`
- `column(MONTHS, Oct)` = `"жовт"`; `columnSub(MONTHS, Jan 2027, false)` = `"2027"`; `columnSub(MONTHS, Nov, false)` = `""`; `columnSub(MONTHS, Aug, true)` = `"2026"`
- `column(QUARTERS, Oct–Jan)` = `"IV кв. 2026"`; `column(YEARS, 2026)` = `"2026"`
- `range` for each zoom equals the strings above; WEEKS around `2026-12-29` = `"28 груд 2026 – 28 бер 2027"`
- `barLabel` of a medicine with dosage `"500 мг"` = `"Амоксицилін · 500 мг"`; with dosage `""` = `"Амоксицилін"`
- `tooltip` of a 10-day course from 2026-10-13 = `"Амоксицилін — 500 мг\n13.10.2026 – 22.10.2026"`; of an ongoing one = `"Вітамін D — 2000 МО\nз 13.10.2026 · Постійно"`; blank dosage → first line `"Амоксицилін"`

`TimelineRowsTest` (DAYS scale for Oct 2026; two persons; helper builds `Medicine` with `Course.Fixed`/`Course.Ongoing`):
- `endsOnRangeStartIsShown`: course ending 2026-10-01 is included
- `startsOnEndExclusiveIsHidden`: course starting 2026-11-01 is excluded
- `ongoingStartedYearsAgoIsShown`
- `sortedByStartThenName`
- `groupedEmitsHeaderPerPersonAndSkipsEmptyPersons`: persons A (2 visible), B (none), C (1) → `[Header A, A1, A2, Header C, C1]`
- `ungroupedHasNoHeaders`

- [ ] **Step 2: Run** `mvn -q test -Dtest='TimeLabelsTest,TimelineRowsTest'` — expected: compilation failure.
- [ ] **Step 3: Append the strings; implement `TimeLabels`** (month/weekday names read by splitting the comma lists once) **and `TimelineRows`**.
- [ ] **Step 4: Run** `mvn -q test` — expected: all pass, including `MessagesTest`.
- [ ] **Step 5: Commit** — `feat: timeline labels and row selection`

---

### Task 3: Settings for view and zoom

**Files:**
- Create: `src/main/java/ua/medicine/ui/DashboardView.java`
- Modify: `src/main/java/ua/medicine/Settings.java`
- Test: `src/test/java/ua/medicine/SettingsTest.java`

**Interfaces:**
- Consumes: `TimeScale.Zoom` (Task 1).
- Produces:
  - `public enum DashboardView { CARDS, TIMELINE }`
  - `Settings.dashboardView()` / `setDashboardView(DashboardView)`: key `dashboard.view`, default `CARDS`
  - `Settings.timelineZoom()` / `setTimelineZoom(TimeScale.Zoom)`: key `timeline.zoom`, default `MONTHS`
  - Refactor `themeMode()` to share one private `<E extends Enum<E>> E get(String key, E fallback)` that returns the fallback on unknown values.

- [ ] **Step 1: Add tests** `viewAndZoomDefault` (`CARDS`, `MONTHS`), `viewAndZoomSurviveRestart` (`TIMELINE`, `QUARTERS`) and `unknownViewAndZoomFallBack` (file `dashboard.view=GRID\ntimeline.zoom=DECADES\n`), in the style of the existing theme tests.
- [ ] **Step 2: Run** `mvn -q test -Dtest=SettingsTest` — expected: compilation failure.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `mvn -q test -Dtest=SettingsTest` — expected: all pass.
- [ ] **Step 5: Commit** — `feat: remember dashboard view and timeline zoom`

---

### Task 4: `TimelineView` and styles

**Files:**
- Create: `src/main/java/ua/medicine/ui/TimelineView.java`
- Modify: `src/main/resources/ua/medicine/ui/theme.css`, `src/main/resources/ua/medicine/ui/dark.css`

**Interfaces:**
- Consumes: `TimeScale` (1), `TimeLabels`, `TimelineRows` (2), `Settings.timelineZoom/setTimelineZoom` (3), `Avatar(Person, double)`.
- Produces: `final class TimelineView extends VBox`
  - `TimelineView(Settings settings, Consumer<Medicine> onOpen)`: the scale starts at `TimeScale.around(settings.timelineZoom(), LocalDate.now())`
  - `void show(List<Person> persons, List<Medicine> medicines, boolean grouped, LocalDate today)`: stores the inputs and renders; zoom and navigation re-render from the stored inputs
  - `void resetToToday(LocalDate today)`

Layout:
- Toolbar (`.timeline-toolbar`, `HBox`, spacing 8): zoom `ToggleButton`s in a `ToggleGroup` labelled `zoom.<NAME>`, so a click on the selected one keeps it selected (consume a deselect); a spacer; `‹`, `Сьогодні`, `›` buttons; range `Label` (`.timeline-range`).
- Body: a `ScrollPane` (`fitToWidth`, vertical only, `.cards-scroll`) holding a `VBox` of rows: a header row (2 lines, height 40), then per `Row` a 34 px row (`PersonHeader` → `.timeline-group`, with avatar r=9 and name, spanning the full width).
- A row is `HBox(nameCell(width 180, .timeline-name), track)`. `Track extends Pane` overrides `layoutChildren()`: for each child it reads `from`/`to` fractions stored in `getProperties()` and positions it at `x = from·w`, width `max(4, (to-from)·w)`, height = row height − 10, centred.
- Each track holds: one `.timeline-column` region per column (border-left line; `.timeline-weekend` added for Sat/Sun in DAYS zoom), the bar, and the today line (`.timeline-today`, 2 px, only when `scale.contains(today)`, centred in today: x = midpoint of `fraction(today)` and `fraction(today + 1 day)`).
- Bar: `Region` `.timeline-bar` with `-fx-background-color: <medicine colour>` inline, from `fraction(start)` to `fraction(end + 1 day)` (ongoing → 1.0). Its label (`.timeline-bar-label`) goes inside when the bar is wide enough for the label's pref width + 12, else to the right of the bar with `.timeline-bar-label-outside`. Ongoing bars that reach the right edge append ` →` to the label. Tooltip `TimeLabels.tooltip`; mouse click → `onOpen.accept(medicine)`; cursor hand.
- Empty rows list → the body shows a `.empty-state` label `empty.noMedicinesInRange` (toolbar stays).

CSS (theme.css; new token `-weekend: #f0f2f5` on `.root`; dark.css `-weekend: #23272d`):
`.timeline-toolbar` padding `8 16`; `.timeline-header` background `-surface`, bottom border `-border`; `.timeline-row` bottom border `-border`; `.timeline-name` padding `0 8`; `.timeline-group` background `-hover`, bold; `.timeline-column` `-fx-border-color: transparent transparent transparent -border`; `.timeline-weekend` background `-weekend`; `.timeline-today` background `-danger`; `.timeline-bar` radius 6; `.timeline-bar-label` white, bold, 12 px; `.timeline-bar-label-outside` text `-muted`, normal weight; `.timeline-range` bold.

- [ ] **Step 1: Implement `TimelineView` and the CSS.**
- [ ] **Step 2: Run** `mvn -q test` — expected: all pass (no behaviour wired yet; this checks compilation and keeps the suite green).
- [ ] **Step 3: Commit** — `feat: TimelineView`

---

### Task 5: Wire into the dashboard

**Files:**
- Modify: `src/main/resources/ua/medicine/ui/dashboard.fxml`, `src/main/java/ua/medicine/ui/DashboardController.java`, `src/main/resources/ua/medicine/ui/theme.css`, `src/test/java/ua/medicine/Steps.java`

**Interfaces:**
- Consumes: `TimelineView` (4), `DashboardView`, `Settings.dashboardView/setDashboardView` (3), `ctx.settings()`.

Changes:
- fxml: in `personBar`, before the `Region` spacer, add `<HBox fx:id="viewSwitch" styleClass="view-switch"/>`; wrap the center `ScrollPane` in `<StackPane fx:id="center">` and give the ScrollPane `fx:id="cardsScroll"`.
- Controller: in `start()`, build two `ToggleButton`s (`view.cards`, `view.timeline`) in a `ToggleGroup` (one always selected), plus `timeline = new TimelineView(ctx.settings(), this::editMedicine)` added to `center`. The selected view = `ctx.settings().dashboardView()`; switching saves it and calls `refresh()`.
- `refresh()`: toggles visible/managed of `cardsScroll` vs `timeline` and of `showFinished` (hidden in timeline). In TIMELINE: if `persons` is empty → cards path's `empty.noPersons` state (show the cards pane); else `timeline.show(persons, medicines, selectedPersonId == null, today)`, where medicines are `findActive()` / `findActiveByPerson(id)`.
- `editMedicine` already refreshes afterwards, so the timeline updates after edits.
- CSS: `.view-switch .toggle-button` square inner corners (left `6 0 0 6`, right `0 6 6 0`) so the two buttons read as one segmented control.
- Steps: in `Steps.get`, handle names of the form `timeline:<ZOOM>[:<tab index>]` before the map lookup: fire the person tab at that index (if given), then the `view.timeline` toggle, then the zoom toggle whose text is `Messages.get("zoom." + ZOOM)`.

- [ ] **Step 1: Implement the changes above.**
- [ ] **Step 2: Run** `mvn -q test` — expected: all pass.
- [ ] **Step 3: Visual check.** Build the classpath once
  (`mvn -q dependency:build-classpath -Dmdep.outputFile=<scratch>/cp.txt`), then run
  `java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/test-classes:$(cat <scratch>/cp.txt)" ua.medicine.SnapshotMain <dir> <out> [seed] [step]`
  on a fresh scratch dir with `seed`, for steps `timeline:DAYS`, `timeline:WEEKS`, `timeline:MONTHS`, `timeline:QUARTERS`, `timeline:YEARS`, and `timeline:MONTHS:1` (first person), with `theme=LIGHT` and `theme=DARK` in `<dir>/settings.properties`.
  Expected in the PNGs: bars at correct dates per seed data (Амоксицилін 10 days from today−3; Вітамін D ongoing from today−40 with `→`; Омепразол 4 weeks; Магній B6 3 months), today line, grouped headers on «Усі», weekend shading in days zoom, labels outside short bars in years zoom, readable in both themes.
- [ ] **Step 4: Commit** — `feat: timeline view on the dashboard`
