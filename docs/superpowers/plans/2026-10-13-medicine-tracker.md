# Medicine Tracker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A JavaFX desktop app for a family on one computer to plan medicine courses per person, tick off doses, get in-window reminders, see history/adherence, and push each person's doses to their own Google Calendar.

**Architecture:** Single Maven module `ua.medicine`. Pure `domain` + `schedule` packages (no I/O, time via `java.time.Clock`), `persistence` on SQLite/JDBC with Flyway, `service` layer used by the JavaFX `ui`, `sync` behind a `CalendarGateway` interface. Manual wiring in `AppContext`.

**Tech Stack:** Java 21 (compiled with `--release 21`, runs on installed JDK 26), JavaFX 21, sqlite-jdbc, Flyway 9 (SQLite support in core), Google API client + Calendar v3, SLF4J/Logback, JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-10-13-medicine-tracker-design.md`

## Global Constraints

- All user-facing text in Ukrainian, from `src/main/resources/ua/medicine/i18n/messages_uk.properties`; FXML uses `%key`; no hardcoded UI strings in Java/FXML.
- Bundle values must not contain the ASCII apostrophe `'` (MessageFormat eats it); use the typographic `’` (e.g. `п’ятниця`).
- Locale `Locale.of("uk", "UA")`, 24-hour times `HH:mm`, dates `dd.MM.yyyy`.
- Data dir `~/.medicine/` (override with system property `medicine.home`): `medicine.db`, `tokens/<personId>/`, `client_secret.json`, `logs/app.log`.
- Generation window: doses exist up to `today + 14 days` (`MedicineService.WINDOW_DAYS = 14`), capped by course end.
- Fixed course end date = `startDate.plus(amount, unit).minusDays(1)` (inclusive).
- Monthly schedule on day > month length uses the month's last day.
- Google: one calendar named `Ліки` per linked account; event 15 min; summary `💊 {name} — {dosage}` / when taken `✅ {name} — {dosage}` (just `💊 {name}` if dosage blank); taken events colorId `"10"` (green).
- Reminder tick every 30 s; snooze 10 min; sync debounce 2 s; periodic sync every 15 min.
- `domain` and `schedule` packages must not import JavaFX, JDBC, or Google classes.
- All times in the system default zone.

## Review Focus

1. **Ukrainian apostrophes in messages** (`п’ятниця`, `пам’ятати`) — expected to render intact; MessageFormat silently drops ASCII `'`. Test: `MessagesTest.bundleHasNoAsciiApostrophes` (Task 4).
2. **App closed for several days** — expected: doses for the closed days still exist and show as `Пропущено` in history, not silently vanish. Test: `MedicineServiceTest.extendWindowsBackfillsGapAfterAppWasClosed` + `DoseServiceTest.resolveMissedMarksPastDays` (Tasks 7, 8).
3. **Editing a medicine on the same day after ticking a dose** — expected: the ticked dose stays taken, no duplicate chips for unchanged times. Test: `MedicineServiceTest.updateKeepsTakenAndDoesNotDuplicateUnchangedTimes` (Task 7).
4. **Midnight passes while the app is open** — expected: yesterday's unticked dose becomes MISSED, daily cards switch to the new day. Test: `DoseStatusResolverTest.overdueBecomesMissedAfterMidnight` (Task 3) + `AppJobs` calls `resolveMissed` + `extendWindows` at midnight (Task 14).
5. **One person's Google token revoked** — expected: other persons still sync; that person shows `G ⚠`. Test: `CalendarSyncServiceTest.authFailureForOnePersonDoesNotStopOthers` (Task 9).

---

## File Structure

```
pom.xml
README.md
src/main/java/ua/medicine/
  App.java, AppContext.java, AppPaths.java, AppJobs.java
  domain/   PeriodUnit, Course, Schedule, Person, Medicine, DoseOccurrence, DoseStatus, SyncState
  schedule/ ScheduleExpander, DoseStatusResolver, CourseProgress, CurrentPeriod, DateRange
  i18n/     Messages, UkrainianPlural, Formats
  persistence/ Database, PersonRepository, MedicineRepository, MedicineCodec, DoseRepository
  service/  SyncTrigger, PersonService, PersonHasMedicinesException, MedicineService,
            DoseService, DoseView, HistoryService, HistoryFilter, HistoryRow, Adherence,
            ReminderService, Reminder
  sync/     CalendarEvent, CalendarEvents, CalendarGateway, CalendarGatewayFactory,
            AuthRequiredException, EventNotFoundException, SyncResult, CalendarSyncService,
            GoogleAuthService, GoogleCalendarGateway, GoogleCalendarGatewayFactory, GoogleLinkService
  ui/       DashboardController, MedicineCard, MedicineDialogController, PersonDialogController,
            HistoryController, NotificationPane, Dialogs
src/main/resources/
  logback.xml
  db/migration/V1__init.sql
  ua/medicine/i18n/messages_uk.properties
  ua/medicine/ui/ dashboard.fxml, medicine-dialog.fxml, person-dialog.fxml, history.fxml, theme.css
src/test/java/ua/medicine/ ... mirrors main; plus testutil/MutableClock, testutil/TestDb
```

---

### Task 1: Project scaffold + domain model

**Files:**
- Create: `pom.xml`, `src/main/java/ua/medicine/domain/*.java`, `src/main/resources/logback.xml`
- Test: `src/test/java/ua/medicine/domain/CourseTest.java`, `ScheduleTest.java`, `src/test/java/ua/medicine/testutil/MutableClock.java`

**Interfaces:**
- Produces:
  - `enum PeriodUnit { DAYS, WEEKS, MONTHS, YEARS; ChronoUnit chronoUnit(); }`
  - `sealed interface Course { Optional<LocalDate> endDate(LocalDate start); record Fixed(int amount, PeriodUnit unit) implements Course; record Ongoing() implements Course; }` — `Fixed` throws `IllegalArgumentException` if `amount < 1` or `unit == null`.
  - `sealed interface Schedule { List<LocalTime> times(); record Daily(List<LocalTime> times); record Weekly(Set<DayOfWeek> days, List<LocalTime> times); record Monthly(int dayOfMonth, List<LocalTime> times); }` — compact constructors: times copied, de-duplicated, sorted, unmodifiable, non-empty (else IAE); Weekly days non-empty → unmodifiable `EnumSet` copy; Monthly `1..31`.
  - `record Person(Long id, String name, String color, String googleEmail, String googleCalendarId) { boolean googleLinked(); Person withId(long); Person withGoogle(String email, String calendarId); }` (`withGoogle(null,null)` unlinks).
  - `record Medicine(Long id, long personId, String name, String dosage, String color, LocalDate startDate, Course course, Schedule schedule, boolean active) { Optional<LocalDate> endDate(); boolean isFinished(LocalDate today); Medicine withId(long); Medicine withActive(boolean); }`
  - `record DoseOccurrence(Long id, long medicineId, LocalDateTime scheduledAt, LocalDateTime takenAt, boolean missed, String calendarEventId, SyncState syncState)` with `static DoseOccurrence planned(long medicineId, LocalDateTime at)` (DIRTY, not taken) and withers `withId`, `withTakenAt`, `withMissed`, `withCalendarEventId`, `withSyncState`.
  - `enum DoseStatus { PENDING, OVERDUE, TAKEN, MISSED }`, `enum SyncState { CLEAN, DIRTY, DELETE_PENDING }`
  - test util `MutableClock extends Clock` with `static MutableClock at(String isoLocalDateTime)`, `void set(LocalDateTime)`, `void advance(Duration)`; zone = `ZoneId.systemDefault()`.

- [ ] **Step 1: Create `pom.xml`**

groupId `ua.medicine`, artifactId `medicine`, `maven.compiler.release=21`, UTF-8. Dependencies: `org.openjfx:javafx-controls:21.0.5`, `org.openjfx:javafx-fxml:21.0.5`, `org.xerial:sqlite-jdbc:3.46.1.3`, `org.flywaydb:flyway-core:9.22.3`, `com.google.api-client:google-api-client:2.7.0`, `com.google.oauth-client:google-oauth-client-jetty:1.36.0`, `com.google.apis:google-api-services-calendar:v3-rev20241101-2.0.0`, `ch.qos.logback:logback-classic:1.5.12`; test: `org.junit.jupiter:junit-jupiter:5.11.3`, `org.assertj:assertj-core:3.26.3`. Plugins: `maven-surefire-plugin:3.5.2`, `org.openjfx:javafx-maven-plugin:0.0.8` with `mainClass` `ua.medicine.App`. Classpath app (no `module-info.java`). `logback.xml`: rolling file `${medicine.home:-${user.home}/.medicine}/logs/app.log` + console, level INFO.

- [ ] **Step 2: Write failing tests**

```java
class CourseTest {
    @Test void fixedEndDateIsInclusive() {
        assertThat(new Course.Fixed(10, PeriodUnit.DAYS).endDate(LocalDate.of(2026,10,13)))
            .contains(LocalDate.of(2026,10,22));
        assertThat(new Course.Fixed(1, PeriodUnit.MONTHS).endDate(LocalDate.of(2026,1,31)))
            .contains(LocalDate.of(2026,2,27));
        assertThat(new Course.Fixed(2, PeriodUnit.WEEKS).endDate(LocalDate.of(2026,10,13)))
            .contains(LocalDate.of(2026,10,26));
    }
    @Test void ongoingHasNoEnd() {
        assertThat(new Course.Ongoing().endDate(LocalDate.of(2026,10,13))).isEmpty();
    }
    @Test void fixedRejectsZeroAmount() {
        assertThatThrownBy(() -> new Course.Fixed(0, PeriodUnit.DAYS)).isInstanceOf(IllegalArgumentException.class);
    }
}
class ScheduleTest {
    @Test void timesAreSortedAndDistinct() {
        var s = new Schedule.Daily(List.of(LocalTime.of(20,0), LocalTime.of(8,0), LocalTime.of(20,0)));
        assertThat(s.times()).containsExactly(LocalTime.of(8,0), LocalTime.of(20,0));
    }
    @Test void emptyTimesRejected() {
        assertThatThrownBy(() -> new Schedule.Daily(List.of())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void weeklyNeedsDays() {
        assertThatThrownBy(() -> new Schedule.Weekly(Set.of(), List.of(LocalTime.NOON))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void monthlyDayRange() {
        assertThatThrownBy(() -> new Schedule.Monthly(32, List.of(LocalTime.NOON))).isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 3: Run** `mvn -q test` — Expected: compilation FAIL (types missing).
- [ ] **Step 4: Implement the domain types and `MutableClock` per Interfaces.**
- [ ] **Step 5: Run** `mvn -q test` — Expected: all PASS; also confirms all dependency versions resolve.
- [ ] **Step 6: Commit** `git add -A && git commit -m "feat: project scaffold and domain model"`

---

### Task 2: ScheduleExpander

**Files:**
- Create: `src/main/java/ua/medicine/schedule/ScheduleExpander.java`
- Test: `src/test/java/ua/medicine/schedule/ScheduleExpanderTest.java`

**Interfaces:**
- Consumes: `Medicine`, `Schedule`, `Course` (Task 1)
- Produces: `static List<LocalDateTime> ScheduleExpander.expand(Medicine m, LocalDate from, LocalDate to)` — all dose times on dates in `[from, to]` (inclusive) intersected with `[startDate, endDate]`; sorted ascending; empty if `from > to`.

- [ ] **Step 1: Write failing tests** (helper `med(LocalDate start, Course c, Schedule s)` builds a Medicine with personId 1)

```java
@Test void dailyMultipleTimes() {
    var m = med(d("2026-10-13"), new Course.Fixed(2, DAYS), new Schedule.Daily(List.of(t("08:00"), t("20:00"))));
    assertThat(ScheduleExpander.expand(m, d("2026-10-01"), d("2026-10-31")))
        .containsExactly(dt("2026-10-13T08:00"), dt("2026-10-13T20:00"), dt("2026-10-14T08:00"), dt("2026-10-14T20:00"));
}
@Test void weeklyOnChosenDays() {   // 2026-10-13 is Tuesday
    var m = med(d("2026-10-13"), new Course.Ongoing(), new Schedule.Weekly(Set.of(MONDAY, THURSDAY), List.of(t("09:00"))));
    assertThat(ScheduleExpander.expand(m, d("2026-10-13"), d("2026-10-20")))
        .containsExactly(dt("2026-10-15T09:00"), dt("2026-10-19T09:00"));
}
@Test void monthly31stUsesLastDayOfShortMonths() {
    var m = med(d("2026-01-01"), new Course.Ongoing(), new Schedule.Monthly(31, List.of(t("09:00"))));
    assertThat(ScheduleExpander.expand(m, d("2026-01-01"), d("2026-04-30")))
        .containsExactly(dt("2026-01-31T09:00"), dt("2026-02-28T09:00"), dt("2026-03-31T09:00"), dt("2026-04-30T09:00"));
}
@Test void monthly29thInLeapFebruary() {
    var m = med(d("2028-01-01"), new Course.Ongoing(), new Schedule.Monthly(30, List.of(t("09:00"))));
    assertThat(ScheduleExpander.expand(m, d("2028-02-01"), d("2028-02-29"))).containsExactly(dt("2028-02-29T09:00"));
}
@Test void ongoingLimitedOnlyByWindow() {
    var m = med(d("2026-01-01"), new Course.Ongoing(), new Schedule.Daily(List.of(t("08:00"))));
    assertThat(ScheduleExpander.expand(m, d("2026-10-13"), d("2026-10-27"))).hasSize(15);
}
@Test void nothingBeforeStartOrAfterEnd() {
    var m = med(d("2026-10-13"), new Course.Fixed(1, WEEKS), new Schedule.Daily(List.of(t("08:00"))));
    var r = ScheduleExpander.expand(m, d("2026-10-01"), d("2026-12-31"));
    assertThat(r).hasSize(7).first().isEqualTo(dt("2026-10-13T08:00"));
    assertThat(r).last().isEqualTo(dt("2026-10-19T08:00"));
}
@Test void emptyWhenFromAfterTo() {
    var m = med(d("2026-10-13"), new Course.Ongoing(), new Schedule.Daily(List.of(t("08:00"))));
    assertThat(ScheduleExpander.expand(m, d("2026-10-20"), d("2026-10-19"))).isEmpty();
}
```

- [ ] **Step 2: Run** `mvn -q test -Dtest=ScheduleExpanderTest` — Expected: FAIL (class missing).
- [ ] **Step 3: Implement** — iterate dates from `max(from,start)` to `min(to,end)`; a date matches if Daily, or Weekly day ∈ days, or Monthly `date.getDayOfMonth() == min(dayOfMonth, date.lengthOfMonth())`; emit `date.atTime(t)` for each time.
- [ ] **Step 4: Run** the test — Expected: PASS.
- [ ] **Step 5: Commit** `git commit -am "feat: schedule expander"` (add new files first).

---

### Task 3: Status, course progress, current period

**Files:**
- Create: `src/main/java/ua/medicine/schedule/DoseStatusResolver.java`, `CourseProgress.java`, `CurrentPeriod.java`, `DateRange.java`
- Test: `DoseStatusResolverTest.java`, `CourseProgressTest.java`, `CurrentPeriodTest.java`

**Interfaces:**
- Produces:
  - `static DoseStatus DoseStatusResolver.statusOf(DoseOccurrence d, LocalDateTime now)` — TAKEN if `takenAt != null`; MISSED if `missed` or (`scheduledAt.toLocalDate() < now.toLocalDate()`); PENDING if `scheduledAt > now`; else OVERDUE.
  - `record CourseProgress(int current, int total, PeriodUnit unit, LocalDate endDate)` with `static Optional<CourseProgress> of(Medicine m, LocalDate today)` — empty for Ongoing; `total = amount`; `current = 0` if today < start, else `min(total, unit.chronoUnit().between(start, today) + 1)`; `double fraction()` = current/total.
  - `record DateRange(LocalDate from, LocalDate to)` and `static DateRange CurrentPeriod.of(Schedule s, LocalDate today)` — Daily: `[today,today]`; Weekly: Monday..Sunday containing today; Monthly: first..last day of month.

- [ ] **Step 1: Write failing tests**

```java
// DoseStatusResolverTest
var dose = DoseOccurrence.planned(1, dt("2026-10-13T09:00"));
@Test void pendingBeforeTime()   { assertThat(statusOf(dose, dt("2026-10-13T08:59"))).isEqualTo(PENDING); }
@Test void overdueAfterTimeSameDay() { assertThat(statusOf(dose, dt("2026-10-13T09:00"))).isEqualTo(OVERDUE); }
@Test void overdueBecomesMissedAfterMidnight() { assertThat(statusOf(dose, dt("2026-10-14T00:00"))).isEqualTo(MISSED); }
@Test void takenWins() { assertThat(statusOf(dose.withTakenAt(dt("2026-10-13T09:05")), dt("2026-10-20T00:00"))).isEqualTo(TAKEN); }
@Test void persistedMissed() { assertThat(statusOf(dose.withMissed(true), dt("2026-10-13T10:00"))).isEqualTo(MISSED); }

// CourseProgressTest
@Test void dayFourOfTen() {
    var p = CourseProgress.of(med(d("2026-10-10"), new Course.Fixed(10, DAYS)), d("2026-10-13")).orElseThrow();
    assertThat(p.current()).isEqualTo(4); assertThat(p.total()).isEqualTo(10); assertThat(p.endDate()).isEqualTo(d("2026-10-19"));
}
@Test void weekThreeOfFour() {
    var p = CourseProgress.of(med(d("2026-09-28"), new Course.Fixed(4, WEEKS)), d("2026-10-13")).orElseThrow();
    assertThat(p.current()).isEqualTo(3);
}
@Test void clampedAfterEnd() {
    assertThat(CourseProgress.of(med(d("2026-01-01"), new Course.Fixed(3, DAYS)), d("2026-10-13")).orElseThrow().current()).isEqualTo(3);
}
@Test void zeroBeforeStart() {
    assertThat(CourseProgress.of(med(d("2026-11-01"), new Course.Fixed(3, DAYS)), d("2026-10-13")).orElseThrow().current()).isZero();
}
@Test void ongoingHasNone() { assertThat(CourseProgress.of(med(d("2026-01-01"), new Course.Ongoing()), d("2026-10-13"))).isEmpty(); }

// CurrentPeriodTest (2026-10-13 is Tuesday)
@Test void weekly() { assertThat(CurrentPeriod.of(weekly, d("2026-10-13"))).isEqualTo(new DateRange(d("2026-10-12"), d("2026-10-18"))); }
@Test void monthly() { assertThat(CurrentPeriod.of(monthly, d("2026-02-10"))).isEqualTo(new DateRange(d("2026-02-01"), d("2026-02-28"))); }
@Test void daily() { assertThat(CurrentPeriod.of(daily, d("2026-10-13"))).isEqualTo(new DateRange(d("2026-10-13"), d("2026-10-13"))); }
```

- [ ] **Step 2: Run** `mvn -q test -Dtest='DoseStatusResolverTest,CourseProgressTest,CurrentPeriodTest'` — Expected: FAIL.
- [ ] **Step 3: Implement per Interfaces.**
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `"feat: dose status, course progress, current period"`

---

### Task 4: Ukrainian i18n helpers + message bundle

**Files:**
- Create: `src/main/java/ua/medicine/i18n/Messages.java`, `UkrainianPlural.java`, `Formats.java`, `src/main/resources/ua/medicine/i18n/messages_uk.properties`
- Test: `MessagesTest.java`, `UkrainianPluralTest.java`, `FormatsTest.java`

**Interfaces:**
- Produces:
  - `Messages.LOCALE` (`Locale.of("uk","UA")`), `static ResourceBundle bundle()` (base name `ua.medicine.i18n.messages`, locale LOCALE), `static String get(String key, Object... args)` (MessageFormat with LOCALE when args present, raw value otherwise).
  - `static String UkrainianPlural.choose(long n, String one, String few, String many)` — `n%10==1 && n%100!=11` → one; `n%10 in 2..4 && n%100 not in 12..14` → few; else many.
  - `Formats`: `static String time(LocalTime)` → `08:00`; `date(LocalDate)` → `13.10.2026`; `dayMonth(LocalDate)` → `22 жовтня`; `dayHeader(LocalDate)` → `вівторок, 13 жовтня`; `amount(int n, PeriodUnit u)` → `1 день`/`2 дні`/`5 днів`, `1 тиждень/2 тижні/5 тижнів`, `1 місяць/2 місяці/5 місяців`, `1 рік/2 роки/5 років`; `progress(CourseProgress)` → `День 4 з 10` / `Тиждень 3 з 4` / `Місяць 1 з 3` / `Рік 1 з 2`; `scheduleSummary(Schedule)` → Daily: `щодня` for 1 time, `{n}×/день` for more; Weekly: lowercase short day names joined `, ` in Mon..Sun order (`пн, чт`); Monthly: `щомісяця {d}-го`.
  - Bundle keys used by later tasks (Ukrainian values): `app.title=💊 Ліки родини`, `tab.all=Усі`, `tab.addPerson=+ Особа`, `btn.addMedicine=+ Додати ліки`, `btn.history=Історія`, `btn.sync=⟳ Синхронізувати`, `btn.save=Зберегти`, `btn.cancel=Скасувати`, `btn.delete=Видалити`, `btn.editPerson=Редагувати особу`, `toggle.showFinished=Показати завершені`, `google.connect=Підключити Google`, `google.connected=G ✓ {0}`, `google.reconnect=G ⚠ Перепідключити`, `google.disconnect=Відключити Google`, `google.noClientSecret=Щоб підключити Google, покладіть client_secret.json у {0}`, `status.overdue=Прострочено`, `status.taken=Прийнято`, `status.missed=Пропущено`, `status.pending=Заплановано`, `card.ongoing=Постійно · з {0}`, `card.until=до {0}`, `dialog.medicine.new=Нові ліки`, `dialog.medicine.edit=Редагувати ліки`, `field.person=Особа`, `field.name=Назва`, `field.dosage=Дозування`, `field.color=Колір`, `field.start=Початок курсу`, `field.duration=Тривалість`, `duration.fixed=Визначена`, `duration.ongoing=Постійно`, `field.end=Закінчення: {0}`, `field.frequency=Частота`, `freq.daily=Щодня`, `freq.weekly=Щотижня`, `freq.monthly=Щомісяця`, `field.weekdays=Дні тижня`, `field.dayOfMonth=День місяця`, `field.times=Час прийому`, `btn.addTime=+ Додати час`, `unit.days=днів`, `unit.weeks=тижнів`, `unit.months=місяців`, `unit.years=років`, `error.nameRequired=Вкажіть назву`, `error.timeRequired=Додайте хоча б один час прийому`, `error.amount=Тривалість має бути не менше 1`, `error.weekdayRequired=Оберіть хоча б один день тижня`, `error.personHasMedicines=Неможливо видалити: у цієї особи є активні ліки`, `confirm.deleteMedicine=Видалити «{0}»? Історія прийому збережеться.`, `dialog.person.new=Нова особа`, `dialog.person.edit=Редагувати особу`, `history.title=Історія прийому`, `history.allMedicines=Усі ліки`, `history.allPersons=Усі особи`, `history.days7=Останні 7 днів`, `history.days30=Останні 30 днів`, `history.days90=Останні 90 днів`, `history.all=Увесь час`, `history.col.time=Дата і час`, `history.col.person=Особа`, `history.col.medicine=Ліки`, `history.col.status=Статус`, `history.col.takenAt=Прийнято о`, `reminder.text={0}: {1} ({2})`, `reminder.taken=Прийнято`, `reminder.snooze=Нагадати через 10 хв`, `sync.ok=Синхронізовано о {0}`, `sync.error=Помилка синхронізації: {0}`, `error.db=Помилка бази даних: {0}`.

- [ ] **Step 1: Write failing tests**

```java
@Test void bundleHasNoAsciiApostrophes() {
    var b = Messages.bundle();
    assertThat(b.keySet()).allSatisfy(k -> assertThat(b.getString(k)).doesNotContain("'"));
}
@Test void formatsArgs() { assertThat(Messages.get("google.connected", "a@b.c")).isEqualTo("G ✓ a@b.c"); }

@ParameterizedTest @CsvSource({"1,день","2,дні","4,дні","5,днів","11,днів","12,днів","21,день","22,дні","25,днів","111,днів"})
void plural(long n, String expected) { assertThat(UkrainianPlural.choose(n, "день", "дні", "днів")).isEqualTo(expected); }

@Test void dayHeader() { assertThat(Formats.dayHeader(LocalDate.of(2026,10,13))).isEqualTo("вівторок, 13 жовтня"); }
@Test void dayMonth()  { assertThat(Formats.dayMonth(LocalDate.of(2026,10,22))).isEqualTo("22 жовтня"); }
@Test void amount()    { assertThat(Formats.amount(3, PeriodUnit.MONTHS)).isEqualTo("3 місяці"); }
@Test void weeklySummary() {
    assertThat(Formats.scheduleSummary(new Schedule.Weekly(Set.of(THURSDAY, MONDAY), List.of(LocalTime.NOON)))).isEqualTo("пн, чт");
}
@Test void dailySummary() {
    assertThat(Formats.scheduleSummary(new Schedule.Daily(List.of(LocalTime.of(8,0), LocalTime.of(14,0), LocalTime.of(20,0))))).isEqualTo("3×/день");
}
```
(Add `junit-jupiter-params` comes with `junit-jupiter` aggregate.)

- [ ] **Step 2: Run** `mvn -q test -Dtest='MessagesTest,UkrainianPluralTest,FormatsTest'` — Expected: FAIL.
- [ ] **Step 3: Implement**; bundle file saved as UTF-8 (Java 9+ reads UTF-8 bundles). If the JDK's `uk` day/month names differ from the expected strings, fix the expected format pattern (`EEEE, d MMMM`), not the test value.
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `"feat: Ukrainian messages and formatting"`

---

### Task 5: Database + PersonRepository

**Files:**
- Create: `src/main/java/ua/medicine/AppPaths.java`, `persistence/Database.java`, `persistence/PersonRepository.java`, `src/main/resources/db/migration/V1__init.sql`
- Test: `persistence/PersonRepositoryTest.java`, `testutil/TestDb.java`

**Interfaces:**
- Produces:
  - `record AppPaths(Path base) { static AppPaths defaults(); Path db(); Path tokens(); Path clientSecret(); }` — `defaults()` uses `System.getProperty("medicine.home")` or `~/.medicine`, creates dirs.
  - `Database(String jdbcUrl)` — opens one `Connection`, sets `PRAGMA foreign_keys=ON`; `void migrate()` (Flyway, `classpath:db/migration`); `Connection connection()`; `implements AutoCloseable`. Methods of repositories `synchronized` on the Database instance.
  - `TestDb.create()` → migrated `Database` on a temp file (`Files.createTempFile`).
  - `PersonRepository(Database)`: `Person save(Person)` (insert when id null, else update; returns with id), `Optional<Person> findById(long)`, `List<Person> findAll()` (by id), `void delete(long id)`.
- `V1__init.sql`: exactly the three tables from spec §4 Storage, plus `CREATE INDEX dose_scheduled ON dose(scheduled_at)`.

- [ ] **Step 1: Write failing tests**

```java
@Test void insertFindUpdateDelete() {
    var repo = new PersonRepository(TestDb.create());
    var p = repo.save(new Person(null, "Марія", "#c0397b", null, null));
    assertThat(p.id()).isNotNull();
    repo.save(p.withGoogle("m@gmail.com", "cal123"));
    assertThat(repo.findById(p.id())).get().extracting(Person::googleEmail).isEqualTo("m@gmail.com");
    assertThat(repo.findAll()).hasSize(1);
    repo.delete(p.id());
    assertThat(repo.findAll()).isEmpty();
}
@Test void nameUnique() {
    var repo = new PersonRepository(TestDb.create());
    repo.save(new Person(null, "Софія", "#e08a1e", null, null));
    assertThatThrownBy(() -> repo.save(new Person(null, "Софія", "#000", null, null))).isInstanceOf(RuntimeException.class);
}
```

- [ ] **Step 2: Run** `mvn -q test -Dtest=PersonRepositoryTest` — Expected: FAIL.
- [ ] **Step 3: Implement**; wrap `SQLException` in `UncheckedIOException`-style `IllegalStateException` (one helper in `Database`: `<T> T tx(SqlFunction<T>)`).
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `"feat: SQLite database and person repository"`

---

### Task 6: MedicineRepository + DoseRepository

**Files:**
- Create: `persistence/MedicineCodec.java`, `persistence/MedicineRepository.java`, `persistence/DoseRepository.java`
- Test: `MedicineRepositoryTest.java`, `DoseRepositoryTest.java`

**Interfaces:**
- Consumes: `Database`, `TestDb` (Task 5)
- Produces:
  - `MedicineCodec`: course ↔ (`course_amount`, `course_unit`) (both null = Ongoing); schedule ↔ (`schedule_type` `DAILY|WEEKLY|MONTHLY`, `schedule_days` `MON,THU` or `31` or null, `schedule_times` `08:00,20:00`).
  - `MedicineRepository(Database)`: `Medicine save(Medicine)`, `Optional<Medicine> findById(long)`, `List<Medicine> findActive()`, `List<Medicine> findActiveByPerson(long personId)`, `List<Medicine> findAll()`, `long countActiveByPerson(long personId)`.
  - `DoseRepository(Database)` — all finders exclude `DELETE_PENDING` rows unless stated:
    - `void insertAll(List<DoseOccurrence>)` (`INSERT OR IGNORE`)
    - `DoseOccurrence update(DoseOccurrence)`
    - `Optional<DoseOccurrence> findById(long)`
    - `List<DoseOccurrence> findByMedicineBetween(long medicineId, LocalDateTime from, LocalDateTime toExclusive)`
    - `List<DoseOccurrence> findBetween(LocalDateTime from, LocalDateTime toExclusive)`
    - `List<DoseOccurrence> findFutureUntaken(long medicineId, LocalDateTime after)` (`scheduled_at > after AND taken_at IS NULL`)
    - `List<DoseOccurrence> findUnresolvedBefore(LocalDateTime t)` (`taken_at IS NULL AND missed=0 AND scheduled_at < t`)
    - `Optional<LocalDateTime> maxScheduledAt(long medicineId)`
    - `List<DoseOccurrence> findNeedingSync(long personId)` — joins medicine; `sync_state IN ('DIRTY','DELETE_PENDING')` (includes DELETE_PENDING)
    - `void markDirtyForPerson(long personId)` (all rows of the person's medicines with `sync_state='CLEAN'` → `DIRTY`)
    - `void delete(long id)`

- [ ] **Step 1: Write failing tests**

```java
@Test void medicineRoundTripAllScheduleKinds() {
    // save Fixed/Daily, Ongoing/Weekly{MON,THU}, Fixed(3,MONTHS)/Monthly(31) and assert findById equals saved (records equal)
}
@Test void findActiveByPersonExcludesInactive() { /* save one active, one withActive(false) → only active returned */ }
@Test void insertAllIgnoresDuplicates() {
    doses.insertAll(List.of(planned(mid, dt("2026-10-13T08:00")), planned(mid, dt("2026-10-13T08:00"))));
    assertThat(doses.findByMedicineBetween(mid, dt("2026-10-13T00:00"), dt("2026-10-14T00:00"))).hasSize(1);
}
@Test void deletePendingHiddenFromFindersButVisibleToSync() {
    var d = only(doses.findBetween(...)); doses.update(d.withSyncState(DELETE_PENDING).withCalendarEventId("ev1"));
    assertThat(doses.findBetween(...)).isEmpty();
    assertThat(doses.findNeedingSync(personId)).extracting(DoseOccurrence::calendarEventId).containsExactly("ev1");
}
@Test void unresolvedBefore() { /* one taken, one missed, one plain past, one future → only plain past returned */ }
@Test void maxScheduledAt() { /* two doses → returns later one; none → empty */ }
```
Write each commented test fully with the values given.

- [ ] **Step 2: Run** `mvn -q test -Dtest='MedicineRepositoryTest,DoseRepositoryTest'` — Expected: FAIL.
- [ ] **Step 3: Implement.** Store `LocalDateTime` as ISO text `yyyy-MM-ddTHH:mm` (lexicographic order = time order).
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `"feat: medicine and dose repositories"`

---

### Task 7: PersonService + MedicineService (generation window)

**Files:**
- Create: `service/SyncTrigger.java`, `service/PersonService.java`, `service/PersonHasMedicinesException.java`, `service/MedicineService.java`
- Test: `service/PersonServiceTest.java`, `service/MedicineServiceTest.java`

**Interfaces:**
- Consumes: repositories (Tasks 5–6), `ScheduleExpander` (Task 2), `MutableClock`
- Produces:
  - `interface SyncTrigger { void requestSync(); SyncTrigger NOOP = () -> {}; }`
  - `PersonService(PersonRepository, MedicineRepository)`: `List<Person> all()`, `Person create(String name, String color)`, `Person update(Person)`, `void delete(long id)` throws `PersonHasMedicinesException` (RuntimeException) when `countActiveByPerson > 0`; otherwise, in one transaction, deletes the person's inactive medicines and their doses (new `MedicineRepository.deleteInactiveByPerson(long personId)`, which deletes doses first) and then the person. The UI later reports the exception with `error.personHasMedicines`.
  - `MedicineService(MedicineRepository, DoseRepository, Clock, SyncTrigger)`, `static final int WINDOW_DAYS = 14`:
    - `Medicine create(Medicine)` — saves, generates doses with `scheduledAt >= today 00:00` up to `today+14`.
    - `Medicine update(Medicine)` — saves; computes new future times (`> now`, up to `today+14`); existing future untaken doses at a time in the new set are kept and set DIRTY; others removed (`DELETE_PENDING` if `calendarEventId != null`, else deleted); missing times inserted.
    - `void delete(long id)` — `active=false`; future untaken doses removed as in update.
    - `void extendWindows()` — for each active medicine: `from = maxScheduledAt` (exclusive, as date of that time, filtering times `> max`) else `max(start, today)` start-of-day; to `today+14`; insert.
    - every mutating method calls `syncTrigger.requestSync()` at the end.

- [ ] **Step 1: Write failing tests** (clock at `2026-10-13T12:00`; `daily(08:00,20:00)` Ongoing from 2026-10-13)

```java
@Test void createGeneratesTodayThroughWindow() {
    var m = svc.create(medicine);
    var doses = doseRepo.findByMedicineBetween(m.id(), dt("2026-10-01T00:00"), dt("2026-12-01T00:00"));
    assertThat(doses).hasSize(30);                       // 13..27 Oct × 2
    assertThat(doses.getFirst().scheduledAt()).isEqualTo(dt("2026-10-13T08:00"));
    assertThat(doses.getLast().scheduledAt()).isEqualTo(dt("2026-10-27T20:00"));
}
@Test void updateKeepsTakenAndDoesNotDuplicateUnchangedTimes() {
    var m = svc.create(medicine);
    var morning = first(m, "2026-10-13T08:00"); doseRepo.update(morning.withTakenAt(dt("2026-10-13T08:05")));
    svc.update(m.withSchedule(new Schedule.Daily(List.of(t("08:00"), t("14:00"), t("20:00")))));
    var today = doseRepo.findByMedicineBetween(m.id(), dt("2026-10-13T00:00"), dt("2026-10-14T00:00"));
    assertThat(today).extracting(DoseOccurrence::scheduledAt)
        .containsExactly(dt("2026-10-13T08:00"), dt("2026-10-13T14:00"), dt("2026-10-13T20:00"));
    assertThat(today.getFirst().takenAt()).isEqualTo(dt("2026-10-13T08:05"));
}
@Test void updateRemovedTimeWithEventBecomesDeletePending() {
    // give the 20:00 dose of 2026-10-14 calendarEventId "ev"; update to Daily(08:00) → findNeedingSync contains it as DELETE_PENDING;
    // 20:00 doses without event id are gone from the table
}
@Test void deleteDeactivatesAndRemovesFutureKeepsPast() {
    // take 08:00 today; delete → medicine inactive; today 08:00 still present & taken; nothing after now remains visible
}
@Test void extendWindowsBackfillsGapAfterAppWasClosed() {
    var m = svc.create(medicine);                 // up to 2026-10-27
    clock.set(dt("2026-11-05T09:00"));
    svc.extendWindows();
    var all = doseRepo.findByMedicineBetween(m.id(), dt("2026-10-13T00:00"), dt("2026-12-01T00:00"));
    assertThat(all).hasSize(76);   // 2026-10-13..2026-11-19 = 38 days × 2, gap days included
}
@Test void fixedCourseStopsAtEnd() { /* Fixed(3,DAYS) from today → exactly 6 doses */ }
@Test void mutationsRequestSync() { /* counting SyncTrigger: create+update+delete → 3 */ }
// PersonServiceTest
@Test void cannotDeletePersonWithActiveMedicine() { assertThatThrownBy(() -> persons.delete(pid)).isInstanceOf(PersonHasMedicinesException.class); }
@Test void deletePersonWithOnlyInactiveMedicinesRemovesThem() { ... }
```
Add `Medicine withSchedule(Schedule)` wither to `Medicine` (Task 1 type) as part of this task.

- [ ] **Step 2: Run** `mvn -q test -Dtest='PersonServiceTest,MedicineServiceTest'` — Expected: FAIL.
- [ ] **Step 3: Implement per Interfaces.**
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `"feat: person and medicine services with rolling dose window"`

---

### Task 8: DoseService

**Files:**
- Create: `service/DoseService.java`, `service/DoseView.java`
- Test: `service/DoseServiceTest.java`

**Interfaces:**
- Consumes: `DoseRepository`, `MedicineRepository`, `DoseStatusResolver`, `CurrentPeriod`
- Produces:
  - `record DoseView(DoseOccurrence dose, DoseStatus status)`
  - `DoseService(DoseRepository, MedicineRepository, Clock, SyncTrigger)`:
    - `DoseOccurrence markTaken(long doseId)` — `takenAt = now`, `missed=false`, DIRTY; idempotent if already taken.
    - `DoseOccurrence unmarkTaken(long doseId)` — throws `IllegalStateException` unless `now.toLocalDate() == scheduledAt.toLocalDate()`; clears `takenAt`, DIRTY.
    - `int resolveMissed()` — `findUnresolvedBefore(today 00:00)` → `missed=true` (sync state unchanged); returns count.
    - `List<DoseView> currentPeriodDoses(Medicine m)` — doses in `CurrentPeriod.of(m.schedule(), today)`, sorted, with status.
    - `int overdueCount(long personId)` — doses of the person's active medicines today with status OVERDUE.

- [ ] **Step 1: Write failing tests** (clock `2026-10-13T12:00`)

```java
@Test void markTakenSetsTimeAndDirty() { var d = svc.markTaken(id08); assertThat(d.takenAt()).isEqualTo(dt("2026-10-13T12:00")); assertThat(d.syncState()).isEqualTo(DIRTY); }
@Test void markTakenAllowedForFutureDoseToday() { assertThat(svc.markTaken(id20).takenAt()).isNotNull(); }
@Test void unmarkSameDayOk() { svc.markTaken(id08); assertThat(svc.unmarkTaken(id08).takenAt()).isNull(); }
@Test void unmarkNextDayRejected() { svc.markTaken(id08); clock.set(dt("2026-10-14T07:00")); assertThatThrownBy(() -> svc.unmarkTaken(id08)).isInstanceOf(IllegalStateException.class); }
@Test void resolveMissedMarksPastDays() { clock.set(dt("2026-10-15T09:00")); assertThat(svc.resolveMissed()).isEqualTo(4); }   // 13th & 14th × 2
@Test void currentPeriodWeeklyShowsWholeWeek() { /* Weekly{MON,THU} 09:00, start 2026-10-12, created at 2026-10-13T12:00 (so no dose on the 12th); a second Weekly dose on 2026-10-19 exists but is outside the week → views == [2026-10-15T09:00 PENDING] */ }
@Test void overdueCountForPerson() { assertThat(svc.overdueCount(pid)).isEqualTo(1); }   // 08:00 overdue, 20:00 pending
```

- [ ] **Step 2: Run** `mvn -q test -Dtest=DoseServiceTest` — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `"feat: dose service (take/untake, missed resolution, current period)"`

---

### Task 9: Calendar sync core (gateway interface + sync service)

**Files:**
- Create: `sync/CalendarEvent.java`, `sync/CalendarEvents.java`, `sync/CalendarGateway.java`, `sync/CalendarGatewayFactory.java`, `sync/AuthRequiredException.java`, `sync/EventNotFoundException.java`, `sync/SyncResult.java`, `sync/CalendarSyncService.java`
- Test: `sync/CalendarEventsTest.java`, `sync/CalendarSyncServiceTest.java`, `sync/FakeCalendarGateway.java` (test)

**Interfaces:**
- Produces:
  - `record CalendarEvent(String summary, String description, LocalDateTime start, int durationMinutes, boolean taken)`
  - `static CalendarEvent CalendarEvents.from(Medicine m, DoseOccurrence d)` — summary per Global Constraints; description = `Formats.scheduleSummary(m.schedule())`; duration 15; `taken = d.takenAt() != null`.
  - `interface CalendarGateway { String ensureCalendar(String name); String insertEvent(String calendarId, CalendarEvent e); void patchEvent(String calendarId, String eventId, CalendarEvent e); void deleteEvent(String calendarId, String eventId); }` — patch throws `EventNotFoundException` on 404; any call may throw `AuthRequiredException` (both `RuntimeException`); other failures `UncheckedIOException`.
  - `interface CalendarGatewayFactory { CalendarGateway forPerson(Person p); }` (throws `AuthRequiredException` if no stored credential)
  - `record SyncResult(int pushed, int deleted, int failed, Set<Long> authFailedPersonIds, String lastError)`
  - `CalendarSyncService(PersonRepository, MedicineRepository, DoseRepository, CalendarGatewayFactory) implements SyncTrigger`:
    - `SyncResult syncNow()` — for each linked person (`googleLinked()`), for each `findNeedingSync(personId)`: DELETE_PENDING → `deleteEvent` (ignore not-found) then `delete` row; DIRTY without event id → `insertEvent`, store id, CLEAN; DIRTY with id → `patchEvent`, on `EventNotFoundException` → insert; CLEAN. `AuthRequiredException` → add person to `authFailedPersonIds`, skip rest of that person. `UncheckedIOException` → `failed++`, keep row dirty, continue. For unlinked persons: DELETE_PENDING rows are just deleted locally.
    - `void requestSync()` — debounce 2 s on a single-thread `ScheduledExecutorService`, then `syncNow()`; result passed to `setListener(Consumer<SyncResult>)`.
    - `void shutdown()`.

- [ ] **Step 1: Write failing tests** — `FakeCalendarGateway` keeps `Map<String, CalendarEvent>` and counters; can be told `failAuth = true` or `failIo = true`.

```java
@Test void summaryPlainAndTaken() {
    assertThat(CalendarEvents.from(amox, dose).summary()).isEqualTo("💊 Амоксицилін — 500 мг");
    assertThat(CalendarEvents.from(amox, dose.withTakenAt(now)).summary()).isEqualTo("✅ Амоксицилін — 500 мг");
    assertThat(CalendarEvents.from(amoxNoDosage, dose).summary()).isEqualTo("💊 Амоксицилін");
}
@Test void insertsDirtyAndMarksClean() { var r = sync.syncNow(); assertThat(r.pushed()).isEqualTo(n); assertThat(fake.events).hasSize(n); assertThat(doseRepo.findNeedingSync(pid)).isEmpty(); }
@Test void takenDosePatchedNotReinserted() { sync.syncNow(); doseService.markTaken(id); sync.syncNow(); assertThat(fake.inserts).isEqualTo(n); assertThat(fake.events.get(evId).taken()).isTrue(); }
@Test void patch404Reinserts() { /* remove event from fake, mark dose dirty, sync → new event id stored */ }
@Test void deletePendingDeletesEventAndRow() { ... }
@Test void unlinkedPersonSkippedButDeletePendingRowsRemoved() { ... }
@Test void ioFailureKeepsDirty() { fake.failIo = true; assertThat(sync.syncNow().failed()).isPositive(); assertThat(doseRepo.findNeedingSync(pid)).isNotEmpty(); }
@Test void authFailureForOnePersonDoesNotStopOthers() {
    // factory returns failing gateway for Марія, working fake for Олександр
    var r = sync.syncNow();
    assertThat(r.authFailedPersonIds()).containsExactly(mariaId);
    assertThat(doseRepo.findNeedingSync(oleksandrId)).isEmpty();
}
```

- [ ] **Step 2: Run** `mvn -q test -Dtest='CalendarEventsTest,CalendarSyncServiceTest'` — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `"feat: calendar sync service behind gateway interface"`

---

### Task 10: Google implementation (auth, gateway, linking)

**Files:**
- Create: `sync/GoogleAuthService.java`, `sync/GoogleCalendarGateway.java`, `sync/GoogleCalendarGatewayFactory.java`, `sync/GoogleLinkService.java`
- Test: manual (real Google account) — no automated test.

**Interfaces:**
- Consumes: `AppPaths` (Task 5), `CalendarGateway`, `CalendarGatewayFactory`, `CalendarSyncService` (Task 9), `PersonRepository`, `DoseRepository.markDirtyForPerson`
- Produces:
  - `GoogleAuthService(AppPaths)`: `boolean clientSecretPresent()`; `Credential authorize(long personId)` — `GoogleAuthorizationCodeFlow` with `FileDataStoreFactory(paths.tokens().resolve(personId))`, scope `CalendarScopes.CALENDAR`, offline access, `LocalServerReceiver` (port 0) + `AuthorizationCodeInstalledApp` (opens system browser); `Optional<Credential> load(long personId)`; `void forget(long personId)` (delete token dir).
  - `GoogleCalendarGateway(Calendar client)` implements `CalendarGateway`: `ensureCalendar` finds calendarList entry with summary `Ліки` or inserts one (timeZone = system zone id); events with `EventDateTime` in system zone, `reminders.useDefault=false` + one popup at 0 min, `colorId "10"` when taken else null; maps `GoogleJsonResponseException` 404/410 → `EventNotFoundException` (delete ignores), 401/`TokenResponseException` → `AuthRequiredException`, `IOException` → `UncheckedIOException`. `String primaryEmail()` = `calendarList().get("primary").getId()`.
  - `GoogleCalendarGatewayFactory(GoogleAuthService)` implements `CalendarGatewayFactory`, app name `Medicine`.
  - `GoogleLinkService(GoogleAuthService, PersonRepository, DoseRepository, SyncTrigger)`: `Person link(long personId)` (authorize → gateway → email + `ensureCalendar("Ліки")` → save person → `markDirtyForPerson` → `requestSync`); `Person unlink(long personId)` (forget token, `withGoogle(null,null)`; reset that person's `calendar_event_id` to null and `sync_state` to DIRTY via new `DoseRepository.resetSyncForPerson(long)` — add with a repository test).

- [ ] **Step 1: Write failing repository test** `DoseRepositoryTest.resetSyncForPersonClearsEventIds`, run, implement `resetSyncForPerson`, run → PASS.
- [ ] **Step 2: Implement the four Google classes.**
- [ ] **Step 3: Run** `mvn -q test` — Expected: all PASS (compiles against Google libs).
- [ ] **Step 4: Commit** `"feat: Google OAuth and Calendar gateway"` — manual verification happens in Task 15.

---

### Task 11: App shell, wiring, dashboard with person tabs and medicine cards

**Files:**
- Create: `App.java`, `AppContext.java`, `ui/DashboardController.java`, `ui/MedicineCard.java`, `ui/Dialogs.java`, `src/main/resources/ua/medicine/ui/dashboard.fxml`, `ui/theme.css`
- Test: manual

**Interfaces:**
- Consumes: all services (Tasks 7–10), `Messages`, `Formats`, `CourseProgress`
- Produces:
  - `AppContext(AppPaths)` — builds `Database` (migrate), repos, `CalendarSyncService`, services, `GoogleAuthService`, `GoogleLinkService`; getters for each; `close()`.
  - `App extends Application` — loads `dashboard.fxml` with `Messages.bundle()`, title `app.title`, 1100×750, `theme.css`; on start: `medicineService.extendWindows()`, `doseService.resolveMissed()`, `sync.requestSync()`; on stop: `AppJobs.stop()`, `context.close()`.
  - `DashboardController`: `void refresh()` (public, called by jobs/dialogs on FX thread). Builds tabs: `Усі`, one per person (avatar circle with initial + name + red overdue badge), `+ Особа` (selecting it opens person dialog and reselects previous tab). Per-person toolbar: Google status button (`google.connect` / `google.connected` / `google.reconnect` when id in last `SyncResult.authFailedPersonIds`), `btn.editPerson`, `toggle.showFinished`. Header: `btn.history`, `btn.sync`, `btn.addMedicine`. Status bar label: `sync.ok`/`sync.error`. Body: `FlowPane` of `MedicineCard`s inside a `ScrollPane`.
  - `MedicineCard(Medicine, Person ownerOrNull, List<DoseView>, LocalDate today, Consumer<Long> onToggle, Runnable onOpen)` — `VBox` with color dot, name, dosage, `Formats.scheduleSummary`, `ProgressBar` + `Formats.progress(...) · card.until` (or `card.ongoing`), chips: `CheckBox` with `Formats.time` (Weekly/Monthly chips prefixed with short day/date), style class `overdue` + `status.overdue` text when OVERDUE, disabled when MISSED. Toggle on unchecked → `markTaken`; on checked → `unmarkTaken` (on `IllegalStateException` revert checkbox).
  - DB/service calls from UI run on a background executor in `AppContext` (`ExecutorService io()`), results applied with `Platform.runLater`; failures → `Dialogs.error(Messages.get("error.db", msg))`.
- Finished medicines (`isFinished(today)`) hidden unless `showFinished` toggled.

- [ ] **Step 1: Implement `AppContext`, `App`, `Dialogs`, `dashboard.fxml`, `theme.css` (green accent `#2f7d5b`, overdue red `#d33`), `DashboardController`, `MedicineCard`.**
- [ ] **Step 2: Run** `mvn -q test` — Expected: PASS (no regressions).
- [ ] **Step 3: Manual check** — `mvn javafx:run -Dmedicine.home=/tmp/med-dev` with a few rows inserted via `sqlite3 /tmp/med-dev/medicine.db` (one person, one daily medicine) and app restart: window opens with Ukrainian title, tabs `Усі | <name> | + Особа`, card shows chips; ticking a chip survives restart; unticking works; overdue chip is red.
- [ ] **Step 4: Commit** `"feat: dashboard with person tabs and medicine cards"`

---

### Task 12: Medicine dialog + person dialog

**Files:**
- Create: `ui/MedicineDialogController.java`, `ui/PersonDialogController.java`, `src/main/resources/ua/medicine/ui/medicine-dialog.fxml`, `ui/person-dialog.fxml`, `ui/MedicineForm.java`
- Test: `src/test/java/ua/medicine/ui/MedicineFormTest.java`

**Interfaces:**
- Consumes: `MedicineService`, `PersonService`, `GoogleLinkService`, `GoogleAuthService.clientSecretPresent()`, `DashboardController.refresh()`
- Produces:
  - `record MedicineForm(Long personId, String name, String dosage, String color, LocalDate start, boolean ongoing, Integer amount, PeriodUnit unit, String frequency /* DAILY|WEEKLY|MONTHLY */, Set<DayOfWeek> weekdays, Integer dayOfMonth, List<LocalTime> times)` with `Map<String,String> validate()` (field → message key: `name`→`error.nameRequired`, `times`→`error.timeRequired`, `amount`→`error.amount`, `weekdays`→`error.weekdayRequired`) and `Medicine toMedicine(Long id, boolean active)`; `static MedicineForm from(Medicine)`. Pure, no JavaFX.
  - `static Optional<Medicine> MedicineDialogController.show(Window owner, AppContext ctx, Medicine existingOrNull, Long preselectedPersonId)` — layout per spec §3 (person combo, name, dosage, 8 preset color swatches, `DatePicker` start, fixed/ongoing toggle + `Spinner` amount + unit combo with `unit.*`, computed `field.end`, frequency toggle showing weekday toggles or day-of-month spinner 1–31, times list with `Spinner`-based hour:minute entry + `btn.addTime`, remove ✕); inline error labels from `validate()`; `btn.delete` (edit mode) asks `confirm.deleteMedicine`.
  - `static Optional<Person> PersonDialogController.show(Window owner, AppContext ctx, Person existingOrNull)` — name, color swatches, Google link/unlink (link runs on io executor; disabled with tooltip `google.noClientSecret` if no client secret), delete (shows `error.personHasMedicines` on exception).

- [ ] **Step 1: Write failing `MedicineFormTest`**: blank name → `name` error; empty times → `times`; ongoing=false & amount 0 → `amount`; WEEKLY with empty weekdays → `weekdays`; valid weekly form `toMedicine` yields `Schedule.Weekly({MON,THU},[09:00])` and `Course.Fixed(3, MONTHS)`; `from(m).toMedicine(m.id(), true)` equals `m`.
- [ ] **Step 2: Run** `mvn -q test -Dtest=MedicineFormTest` — Expected: FAIL.
- [ ] **Step 3: Implement `MedicineForm`; run test** — Expected: PASS.
- [ ] **Step 4: Implement both dialogs and hook them into dashboard (`btn.addMedicine`, card click, `+ Особа`, `btn.editPerson`, Google status button).**
- [ ] **Step 5: Manual check** — with a fresh `-Dmedicine.home=/tmp/med-dev2`: add two persons; add daily/weekly/monthly medicines incl. ongoing; validation messages appear in Ukrainian; edit changes chips; delete hides card; deleting a person with medicines shows the error.
- [ ] **Step 6: Commit** `"feat: medicine and person dialogs"`

---

### Task 13: History screen + adherence

**Files:**
- Create: `service/HistoryService.java`, `service/HistoryFilter.java`, `service/HistoryRow.java`, `service/Adherence.java`, `ui/HistoryController.java`, `src/main/resources/ua/medicine/ui/history.fxml`
- Test: `service/HistoryServiceTest.java`

**Interfaces:**
- Produces:
  - `record HistoryFilter(Long personId, Long medicineId, Integer lastDays)` (null = all)
  - `record HistoryRow(LocalDateTime scheduledAt, String personName, String medicineName, DoseStatus status, LocalDateTime takenAt)`
  - `record Adherence(long taken, long missed) { OptionalInt percent(); }` — `round(100.0*taken/(taken+missed))`, empty when both 0.
  - `HistoryService(DoseRepository, MedicineRepository, PersonRepository, Clock)`: `List<HistoryRow> rows(HistoryFilter)` — doses with `scheduledAt <= now` in range, newest first, includes inactive medicines; `Map<Medicine, Adherence> adherence(HistoryFilter)` — counts TAKEN and MISSED only (OVERDUE excluded), ordered by medicine name.
  - `static void HistoryController.show(Window owner, AppContext ctx)` — non-modal window; filters (person combo with `history.allPersons`, medicine combo with `history.allMedicines`, period combo 7/30/90/all, default 30), adherence tiles, `TableView` with the five `history.col.*` columns, statuses colored.

- [ ] **Step 1: Write failing tests**: 3 taken + 1 missed + 1 overdue today → `percent()` = 75, rows exclude future doses, newest first; filter by `lastDays=7` excludes a dose 10 days ago; `new Adherence(0,0).percent()` empty.
- [ ] **Step 2: Run** `mvn -q test -Dtest=HistoryServiceTest` — Expected: FAIL.
- [ ] **Step 3: Implement service; run** — Expected: PASS.
- [ ] **Step 4: Implement `HistoryController` + fxml, open from `btn.history`.**
- [ ] **Step 5: Manual check** — history window shows tiles and rows in Ukrainian; filters update.
- [ ] **Step 6: Commit** `"feat: history screen with adherence"`

---

### Task 14: Reminders + background jobs

**Files:**
- Create: `service/ReminderService.java`, `service/Reminder.java`, `ui/NotificationPane.java`, `AppJobs.java`
- Test: `service/ReminderServiceTest.java`

**Interfaces:**
- Produces:
  - `record Reminder(long doseId, String personName, String medicineName, String dosage, LocalTime time)`
  - `ReminderService(DoseRepository, MedicineRepository, PersonRepository, Clock)` — records `startedAt = now` at construction; `List<Reminder> due()` returns doses with `startedAt.truncatedTo(MINUTES) <= scheduledAt <= now`, same day, not taken, active medicine, not yet returned, and not snoozed past now; marks returned. `void snooze(long doseId, Duration d)` — eligible again at `now + d`.
  - `NotificationPane` — `StackPane` overlay bottom-right in the dashboard root, stacks cards with `reminder.text` (`{person}: {medicine — dosage} ({HH:mm})`), buttons `reminder.taken` (→ `markTaken`, refresh, dismiss) and `reminder.snooze` (→ `snooze(10 min)`, dismiss); on each new reminder: `java.awt.Toolkit.getDefaultToolkit().beep()` (skipped when `GraphicsEnvironment.isHeadless()`), and if the stage is not focused, `stage.toFront()` (best-effort attention; no OS notifications in v1).
  - `AppJobs(AppContext, DashboardController, NotificationPane)`: `start()` schedules every 30 s → `reminders.due()` + `dashboard.refresh()`; every 15 min → `sync.requestSync()`; at next local midnight then every 24 h → `medicineService.extendWindows()`, `doseService.resolveMissed()`, refresh; `stop()`.

- [ ] **Step 1: Write failing tests** (clock `2026-10-13T07:55`, daily 08:00 & 20:00)

```java
@Test void noReminderBeforeTime() { assertThat(svc.due()).isEmpty(); }
@Test void reminderAtTimeOnlyOnce() { clock.set(dt("2026-10-13T08:00")); assertThat(svc.due()).hasSize(1); assertThat(svc.due()).isEmpty(); }
@Test void noPopupsForDosesBeforeStartup() { clock.set(dt("2026-10-13T21:00")); var late = new ReminderService(...); /* started 21:00 */ assertThat(late.due()).isEmpty(); }
@Test void snoozeRepeatsAfterTenMinutes() {
    clock.set(dt("2026-10-13T08:00")); var r = svc.due().getFirst(); svc.snooze(r.doseId(), Duration.ofMinutes(10));
    clock.set(dt("2026-10-13T08:09")); assertThat(svc.due()).isEmpty();
    clock.set(dt("2026-10-13T08:10")); assertThat(svc.due()).extracting(Reminder::doseId).containsExactly(r.doseId());
}
@Test void takenDoseNotReminded() { doseService.markTaken(id08); clock.set(dt("2026-10-13T08:00")); assertThat(svc.due()).isEmpty(); }
@Test void reminderTextIncludesPerson() { clock.set(dt("2026-10-13T08:00")); assertThat(svc.due().getFirst().personName()).isEqualTo("Марія"); }
```

- [ ] **Step 2: Run** `mvn -q test -Dtest=ReminderServiceTest` — Expected: FAIL.
- [ ] **Step 3: Implement `ReminderService`; run** — Expected: PASS.
- [ ] **Step 4: Implement `NotificationPane`, `AppJobs`, wire into `App`.**
- [ ] **Step 5: Manual check** — add a medicine with a time 2 minutes ahead; popup appears within 30 s of that time with Ukrainian text; "Прийнято" ticks the chip; snooze re-shows after 10 min.
- [ ] **Step 6: Commit** `"feat: in-window reminders and background jobs"`

---

### Task 15: README + end-to-end Google verification

**Files:**
- Create: `README.md`

- [ ] **Step 1: Write README** (Ukrainian or English per user — default Ukrainian): requirements (JDK 21+, Maven), `mvn javafx:run`, data dir, Google setup: create Google Cloud project → enable Google Calendar API → OAuth consent screen (External, add each family member's email as test user) → Credentials → OAuth client ID "Desktop app" → download JSON as `~/.medicine/client_secret.json`.
- [ ] **Step 2: Run full suite** `mvn -q test` — Expected: all PASS.
- [ ] **Step 3: Manual Google check** — link a person; calendar `Ліки` appears with 💊 events for the next 14 days; tick a dose → event becomes ✅ green within ~2 s; edit medicine time → events move; delete medicine → future events disappear; unlink → status reverts to `Підключити Google`.
- [ ] **Step 4: Commit** `"docs: README with Google Calendar setup"`
