# Medicine Tracker — Design Spec

Date: 2026-10-13
Status: Draft for review

## 1. Purpose

A JavaFX desktop application for a family sharing one computer to plan and track
medicine intake. Each family member (person) has their own medicines, each with a
course length and a dosing schedule. The app shows what has to be taken, lets the
user tick off doses, reminds while open, keeps a history with adherence, and pushes
each person's doses to that person's own Google Calendar.

All user-facing text is in Ukrainian.

### Success criteria

- Any family member can add a medicine for any person in under a minute.
- The dashboard shows, per person, which doses of the current period are taken,
  pending, or overdue.
- Doses appear in the person's Google Calendar (if linked) and are marked ✅ when taken.
- History shows taken/missed doses and adherence % per medicine.

### Out of scope (v1)

- Multiple computers / shared data across devices.
- Login, passwords, or per-person privacy (everyone sees everything).
- Two-way calendar sync (changes in Google are not read back).
- System tray, autostart, OS-level notifications while the window is closed.
- Pill stock tracking.
- Notes and attached medicine instructions (planned next; the model must allow
  adding them as new tables without changing existing ones).

## 2. Domain model

### Person
| Field | Notes |
|---|---|
| id | |
| name | required, unique |
| color | avatar color |
| googleEmail | null when not linked |
| googleCalendarId | id of the "Ліки" calendar in that account; null when not linked |

A person without a Google account is fully supported; sync is simply skipped for them.

### Medicine
| Field | Notes |
|---|---|
| id | |
| personId | owner, required |
| name | required |
| dosage | free text, e.g. "500 мг, 1 таблетка після їжі"; optional |
| color | card color |
| startDate | required |
| course | `Fixed(amount ≥ 1, unit ∈ {DAYS, WEEKS, MONTHS, YEARS})` or `Ongoing` |
| schedule | see below |
| active | false = soft-deleted |

**Course end date (Fixed):** `startDate.plus(amount, unit).minusDays(1)` (inclusive).
Ongoing has no end date.

**Schedule** (sealed type), each with a non-empty, sorted, distinct list of `LocalTime`:
- `Daily(times)` — every day.
- `Weekly(daysOfWeek, times)` — on the chosen weekdays (non-empty set).
- `Monthly(dayOfMonth 1..31, times)` — if the month is shorter, use its last day.

### DoseOccurrence
One concrete scheduled dose = one checkbox.

| Field | Notes |
|---|---|
| id | |
| medicineId | |
| scheduledAt | `LocalDateTime`, system zone |
| takenAt | null if not taken |
| missed | persisted once a dose is resolved as MISSED |
| calendarEventId | null until pushed |
| syncState | `CLEAN`, `DIRTY`, `DELETE_PENDING` |

### DoseStatus (derived, except TAKEN/MISSED)
Given `now`:
- `TAKEN` — `takenAt != null`.
- `PENDING` — `scheduledAt > now`.
- `OVERDUE` — `scheduledAt ≤ now`, not taken, and `now` is still on the same calendar
  day as `scheduledAt`. Shown red on the dashboard.
- `MISSED` — not taken and the day of `scheduledAt` has ended. Persisted (`missed = true`)
  by the status resolver job.

(The earlier "DUE" idea is folded into OVERDUE: once the time passes the dose is red
until taken.)

### Rules
- **Generation window:** doses exist from course start up to `today + 14 days`
  (capped by course end). A daily job and app startup extend the window.
- **Editing a medicine:** delete all future, not-taken doses (`scheduledAt > now`) and
  regenerate from the new definition. Taken and missed doses are never modified.
- **Deleting a medicine:** sets `active = false`; future not-taken doses are deleted
  (their calendar events marked `DELETE_PENDING`); history is kept.
- **Untick:** a taken dose can be unticked only on the same calendar day as `scheduledAt`.
- **Course finished:** a medicine whose end date is before today is "finished" and hidden
  from the dashboard unless "Показати завершені" is on.
- **Time zone:** system default zone for everything.

## 3. Screens (Ukrainian UI)

### Dashboard (main window)
- Header: "💊 Ліки родини", buttons "Історія", "⟳ Синхронізувати", "+ Додати ліки".
- Person tabs: "Усі" | one tab per person (avatar, name, red badge with overdue count)
  | "+ Особа".
- Per-person tab toolbar: Google status ("G ✓ email" / "Підключити Google" /
  "G ⚠ Перепідключити"), "Редагувати особу", "Показати завершені" toggle.
- Body: grid of medicine cards. In "Усі", each card also shows the person's avatar.
- Card content:
  - color dot, name, dosage, schedule summary ("3×/день", "пн, чт", "щомісяця 1-го").
  - course progress bar + text: "День 4 з 10 · до 22 жовтня" for Fixed
    (unit-appropriate: "Тиждень 3 з 4", "Місяць 1 з 3", "Рік 1 з 2");
    "Постійно · з 1 вересня" for Ongoing.
  - dose chips with checkboxes for the current period: Daily → today's doses;
    Weekly → this week's (Mon–Sun) doses; Monthly → this month's doses. Overdue chips
    are red with "Прострочено".
- Clicking a card (outside the chips) opens the edit dialog.

### Person dialog
Name, color, Google link/unlink, delete person (only if they have no active medicines;
otherwise show a message).

### Add / edit medicine dialog
- Особа (person, preselected from current tab), Назва, Дозування, Колір, Початок курсу.
- Тривалість: "Визначена" (amount + днів/тижнів/місяців/років, shows computed
  "Закінчення: dd.MM.yyyy") or "Постійно".
- Частота: "Щодня" / "Щотижня" (weekday toggles пн..нд) / "Щомісяця" (day 1..31).
- Час прийому: list of times, "+ Додати час", remove per time.
- Buttons: "Видалити" (edit mode only, with confirmation), "Скасувати", "Зберегти".
- Validation (inline, Ukrainian messages): name required; ≥ 1 time; amount ≥ 1;
  weekly needs ≥ 1 weekday.

### History
- Filters: person, medicine ("Усі ліки"), period (7 / 30 / 90 днів / увесь час).
- Adherence tiles per medicine: `taken / (taken + missed)`, shown as %.
- Table: Дата і час, Особа, Ліки, Статус (Прийнято / Пропущено / Прострочено),
  Прийнято о.

### Reminders (in-window only)
- `ReminderService` ticks every 30 s. When a dose's time arrives (and it's not taken),
  show an in-window notification popup stacked bottom-right:
  "Марія: Амоксицилін — 500 мг (08:00)" with buttons "Прийнято" and
  "Нагадати через 10 хв". Snooze is in-memory only.
- Also plays the system beep and requests window attention (taskbar flash) if
  not focused.
- On startup, no popups for past doses; they show as overdue/missed on the dashboard.

## 4. Architecture

Single Maven module, base package `ua.medicine`, Java 21+, JavaFX 21.

```
ua.medicine
├── App                    JavaFX Application entry point
├── AppContext             manual wiring of all services/repositories
├── domain                 Person, Medicine, Course, Schedule, DoseOccurrence, DoseStatus
├── schedule               ScheduleExpander, DoseStatusResolver, CourseProgress  (pure)
├── persistence            Database (connection + Flyway), PersonRepository,
│                          MedicineRepository, DoseRepository
├── service                PersonService, MedicineService, DoseService,
│                          ReminderService, Clock injection
├── sync                   CalendarGateway (interface), GoogleCalendarGateway,
│                          GoogleAuthService, CalendarSyncService
└── ui                     controllers + FXML (dashboard, medicine-dialog,
                           person-dialog, history, notification), i18n, theme.css
```

Principles:
- `domain` and `schedule` have no dependencies on JavaFX, JDBC, or Google — pure
  and unit-testable. Time is passed in (`java.time.Clock`).
- UI talks only to services; services talk to repositories and `CalendarSyncService`.
- Long-running work (sync, DB writes that could block) runs off the FX thread via a
  single background executor; UI updates via `Platform.runLater`.

### Storage
SQLite at `~/.medicine/medicine.db`, migrations by Flyway (`V1__init.sql`).

```sql
person(id INTEGER PK, name TEXT NOT NULL UNIQUE, color TEXT NOT NULL,
       google_email TEXT, google_calendar_id TEXT)
medicine(id INTEGER PK, person_id INTEGER NOT NULL REFERENCES person(id),
         name TEXT NOT NULL, dosage TEXT, color TEXT NOT NULL,
         start_date TEXT NOT NULL, course_amount INTEGER, course_unit TEXT,
         schedule_type TEXT NOT NULL, schedule_days TEXT, schedule_times TEXT NOT NULL,
         active INTEGER NOT NULL DEFAULT 1)
dose(id INTEGER PK, medicine_id INTEGER NOT NULL REFERENCES medicine(id),
     scheduled_at TEXT NOT NULL, taken_at TEXT, missed INTEGER NOT NULL DEFAULT 0,
     calendar_event_id TEXT, sync_state TEXT NOT NULL DEFAULT 'DIRTY',
     UNIQUE(medicine_id, scheduled_at))
```
- `course_amount`/`course_unit` null ⇒ Ongoing.
- `schedule_days`: CSV of weekdays (`MON,THU`) for Weekly, day number for Monthly.
- `schedule_times`: CSV of `HH:mm`.
- Dates/times stored as ISO-8601 text.

### Google Calendar sync (one-way push + taken status)
- **Credentials:** user creates an OAuth "Desktop app" client in Google Cloud Console
  and places `client_secret.json` in `~/.medicine/`. README documents the steps.
  Without that file, Google features are disabled with an explanatory message.
- **Linking:** "Підключити Google" on a person runs the installed-app OAuth flow
  (loopback redirect, system browser). Scope: `calendar` (needed to create a calendar).
  Token stored per person in `~/.medicine/tokens/<personId>/`. After linking, the app
  creates (or finds by name) a calendar "Ліки" and stores its id and the account email.
- **Unlinking:** deletes the stored token and clears the person's google fields. Events
  stay in Google (user can delete the "Ліки" calendar).
- **Event mapping:** one event per dose, 15 min long at `scheduledAt`,
  summary "💊 {name} — {dosage}", description with schedule info,
  popup reminder at 0 min. When taken: summary "✅ {name} — {dosage}", green color.
  Missed doses are left as-is.
- **Sync worker:** any change sets `sync_state = DIRTY` (or `DELETE_PENDING`). The
  worker pushes dirty doses for linked persons: insert if no `calendar_event_id`,
  else patch; deletes for `DELETE_PENDING` then removes the row. Triggered on startup,
  after each change (debounced 2 s), every 15 min, and by "⟳". Only doses within the
  generation window are pushed.
- **Failures:** network errors leave rows dirty for the next run. Auth errors
  (revoked/expired refresh token) mark the person "G ⚠" until reconnected. A 404 on
  patch re-inserts the event. Errors are logged and shown in a status bar text; they
  never block the UI.

### Background jobs
- On startup and at each local midnight: extend generation window, resolve MISSED.
- Every 30 s: reminder tick, refresh dashboard statuses.

## 5. Internationalization
- `messages_uk.properties` resource bundle; FXML uses `%key`. No hardcoded UI strings.
- `Locale.of("uk", "UA")` for date formatting (e.g. "вівторок, 13 жовтня"), 24-hour times.
- Ukrainian plural forms for units ("1 день / 2 дні / 5 днів") via a small helper.

## 6. Error handling
- Validation errors: inline under fields in dialogs.
- Database errors: error dialog with message; logged with stack trace.
- Sync errors: status bar + person tab badge; never modal.
- Logging: SLF4J + Logback to `~/.medicine/logs/app.log` (rolling).

## 7. Testing
- **Unit (JUnit 5 + AssertJ):** `ScheduleExpander` (daily/weekly/monthly, 31st in short
  months, Feb 29, ongoing across window, course end boundary, multiple times),
  `DoseStatusResolver` (each status transition around midnight), `CourseProgress`,
  Ukrainian plural helper.
- **Repository:** against a temp-file SQLite DB with Flyway migrations applied.
- **Service:** `MedicineService` edit/regenerate semantics, `DoseService` untick rule,
  `CalendarSyncService` against a fake `CalendarGateway` (insert/patch/delete/404/auth error).
- **UI:** manual checklist (no automated UI tests in v1).

## 8. Build & run
- Maven, `javafx-maven-plugin`; run with `mvn javafx:run`.
- Dependencies: openjfx (controls, fxml), sqlite-jdbc, flyway-core,
  google-api-client, google-oauth-client-jetty, google-api-services-calendar,
  slf4j + logback, junit-jupiter, assertj.
- Packaging via `jpackage` is a later step.

## 9. Future extensions (designed for, not built)
- Notes per medicine: `medicine_note` table.
- Attached instructions (file or URL found on the internet): `medicine_attachment`
  table + files under `~/.medicine/attachments/`.
