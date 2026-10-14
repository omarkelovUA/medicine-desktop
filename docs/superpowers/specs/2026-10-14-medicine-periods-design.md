# Medicine periods — design

## Goal

A medicine is often taken in several **periods** with rests in between: Vitamin A for a month in January, then
again from August. Today the only way to do that is to create a second medicine with the same name, which gives
two lines on the dashboard and two entries in the history filter.

After this change **one medicine has many periods**. The next period is added to the existing medicine after a
rest. The dashboard shows all of a medicine's periods on one line, and the history shows one medicine with all its
periods.

## Decisions

- A period is **only dates**: a start date plus a duration (fixed amount of days / weeks / months / years, or
  «Постійно»). Name, dosage, colour and schedule (days, times) belong to the medicine and are shared by all its
  periods.
- Existing duplicates (same person, same name) are **merged automatically** by a database migration.
- History shows a **list of periods** above the dose table and a «Період» column.

## Domain

- New record `Period(LocalDate start, Course course)` with `endDate()` (inclusive, empty for ongoing),
  `contains(LocalDate)`, `isFinished(LocalDate today)`.
- `Medicine` replaces `startDate` and `course` with `List<Period> periods`. The canonical constructor enforces:
  - at least one period;
  - periods sorted by start;
  - no overlap: each period starts after the previous one's end date;
  - only the last period may be `Course.Ongoing`.
  Violations throw `IllegalArgumentException` (the dialog validates first and shows a message, see below).
- Helpers on `Medicine`:
  - `firstStart()`, `lastPeriod()`;
  - `endDate()`: the last period's end (empty if ongoing);
  - `isFinished(today)`: the last period has ended;
  - `periodAt(LocalDate)`: the period containing the date, if any;
  - `relevantPeriod(today)`: the period containing today, else the next upcoming one, else the last one. Cards and
    progress use this.
- `Medicine.withPeriods(List<Period>)` for the dialog and the merge.

## Persistence

### Schema (migration `V2__periods.sql`)

```sql
CREATE TABLE period (
    id            INTEGER PRIMARY KEY,
    medicine_id   INTEGER NOT NULL REFERENCES medicine (id),
    start_date    TEXT    NOT NULL,
    course_amount INTEGER,
    course_unit   TEXT
);
CREATE INDEX period_medicine ON period (medicine_id);
```

Each existing medicine's `start_date`, `course_amount`, `course_unit` are copied into one `period` row, then those
three columns are dropped from `medicine`.

### Repository

- `MedicineRepository` loads periods with their medicine (one extra query per load, joined by `medicine_id`,
  ordered by `start_date`). `save` replaces a medicine's period rows in the same transaction.
- `MedicineCodec` keeps encoding `Course` as amount/unit, now for period rows.

### Merging duplicates (Java migration `V3__merge_duplicate_medicines`)

Runs once, in Java, because SQLite's `lower()` does not fold Cyrillic.

1. Group all medicines (active and deleted) by person and by name stripped and lower-cased (`Locale.ROOT`).
2. In a group of two or more, the **survivor** is the active medicine with the latest last-period start (ties:
   higher id); if none is active, the one with the latest start. Its name, dosage, colour and schedule are kept.
3. If any two periods in the group overlap, or two medicines in it have a dose at the same date-time, the group is
   **not merged** (these were taken at the same time on purpose, e.g. one entry for the morning, another for the
   evening). This also guarantees `UNIQUE (medicine_id, scheduled_at)` cannot conflict.
4. Otherwise, the other medicines' periods are moved to the survivor, their doses are re-pointed to the survivor
   (`medicine_id`), and the other medicine rows are deleted. Dose rows keep their `calendar_event_id`, so Google
   Calendar events are not duplicated.
5. The survivor is active if any medicine in the group was active.

## Doses

- `ScheduleExpander.expand(m, from, to)` produces dose times only on dates inside one of the periods. A rest has no
  doses.
- `MedicineService` is otherwise unchanged: `update` already regenerates future untaken doses and keeps taken and
  past ones, so adding, editing or removing a period goes through `update`. `extendWindows` keeps filling up to
  today + 14 days and naturally skips rests.
- Past doses that end up outside every period after an edit stay in history, as today.
- Google Calendar sync is unchanged: events are per dose.

## Medicine dialog

- The start date and duration fields are replaced by a section **«Періоди прийому»**: one row per period, with a
  start date picker, the duration (amount + unit, or «Постійно») and a remove button «✕». The remove button is
  disabled when only one period is left.
- **«Додати період»** appends a row starting the day after the last period's end, with the last period's duration.
  It is disabled while the last period is «Постійно», with the tooltip «Спершу завершіть поточний період».
- A «Постійно» period that has started gets the button **«Завершити сьогодні»**: it becomes a fixed period of N
  days ending today.
- Validation messages (shown like the other dialog errors):
  - «Періоди не повинні перетинатися»;
  - «Лише останній період може бути постійним».
  Rows are sorted by start date on save.
- **Same name for a new medicine.** When saving a *new* medicine whose name (stripped, case-insensitive) matches an
  existing medicine of the same person, the app asks: «"Вітамін A" вже є. Додати новий період до наявних ліків?»
  - **Так**: the form's periods are added to the existing medicine. Its dosage, colour and schedule are updated
    from the form, and a deleted medicine becomes active again. If the periods would overlap, the overlap message
    is shown and nothing is saved.
  - **Ні**: a separate medicine is created, as today.
- All texts go to `messages_uk.properties`.

## Dashboard

### Шкала часу
- One row per medicine. A row is shown when any of its periods overlaps the visible range.
- Each period is its own bar on that row, in the medicine's colour, with a gap for the rest. The «→» arrow and the
  cut-off edges apply per bar.
- Tooltip per bar: name, dosage and that period's dates. A click on any bar opens the medicine dialog.
- Row order: by the start of the first period visible in the range, then by name.

### Картки
- One card per medicine. Progress and dates use `relevantPeriod(today)`:
  - in a period: «день 4 з 10» as today;
  - in a rest with a period planned: the card shows it as not started yet, with its start date («з 01.08»);
  - after the last period: finished, hidden unless «Показати завершені» is checked.

## History

- The medicine filter lists each medicine once (follows from the merge).
- When one medicine is selected, a line above the table lists its periods:
  «Періоди: 01.01–30.01.2026 · 01.08.2026–…» (the year is shown once per period range; «…» for ongoing).
- `HistoryRow` gets a `period` text; the table gets a **«Період»** column with the range of the period that
  contains the dose (empty if the dose is outside every period).
- The adherence % per medicine covers all its periods.

## Testing

- `Period` and `Medicine` invariants: order, overlap, ongoing only last, `relevantPeriod`.
- `ScheduleExpander`: no doses during a rest; doses on both sides of it.
- `MedicineRepository`: periods round-trip; `save` replaces periods.
- Migrations on a test database: periods created from the old columns; duplicates merged (Cyrillic case, spaces,
  deleted + active, doses re-pointed with event ids kept); overlapping duplicates left separate.
- `MedicineService`: adding a period generates doses only inside it; removing a future period deletes its untaken
  doses.
- `MedicineForm`: validation messages; «Додати період» default dates; «Завершити сьогодні».
- `TimelineRows` and bar placement with several periods; a medicine whose periods all lie outside the range is hidden.
- `HistoryService`: period line text and the «Період» column.

## Out of scope

- Different schedule or dosage per period.
- Adherence split per period.
- Renaming one medicine to another's name (no merge on rename).
