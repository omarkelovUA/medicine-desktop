# Timeline dashboard — design

## Goal

Besides the existing cards dashboard, offer a **timeline (Gantt) view**: one row per medicine, a coloured bar
from course start to course end on a time axis, with zoom levels days / weeks / months / quarters / years.
Purpose: see at a glance which courses run when — past, current and planned — per person or for the whole family.

## User-facing behaviour

### View switch
- The person bar gets a toggle **«Картки | Шкала часу»**, available on «Усі» and on every person tab.
- Cards view is unchanged. In timeline view the «Показати завершені» checkbox is hidden (dates already separate
  finished courses).
- The chosen view is global (not per tab) and is remembered in `settings.properties`.

### Timeline toolbar
- Zoom buttons (toggle group): **Дні · Тижні · Місяці · Квартали · Роки**. Default: Місяці.
- Navigation: **‹**, **Сьогодні**, **›**, then a label with the visible range.
- The zoom is remembered in `settings.properties`. Navigation position is not remembered: the app always opens on today.

### Visible span and stepping

| Zoom | Column | Visible span | Range start for anchor date `d` | Range label |
|---|---|---|---|---|
| Дні | 1 day | 1 calendar month | 1st of `d`'s month | `жовтень 2026` |
| Тижні | Mon–Sun week | 13 weeks | Monday of `d`'s week, minus 2 weeks | `28 вер – 27 груд 2026` |
| Місяці | 1 month | 12 months | 1st of `d`'s month, minus 2 months | `серп 2026 – лип 2027` |
| Квартали | 1 quarter | 8 quarters | 1st day of `d`'s quarter, minus 1 quarter | `III кв. 2026 – II кв. 2028` |
| Роки | 1 year | 5 years | 1 Jan of `d`'s year, minus 1 year | `2025 – 2029` |

- ‹ / › move the range by one full span; «Сьогодні» recomputes the range from today.
- The lead-in (e.g. "minus 2 months") keeps a little past visible next to today.

### Header
- Column labels: days `13` (with a short weekday `пн` under it), weeks `6–12 жовт`, months `жовт` (plus the year
  on January and on the first column), quarters `IV кв. 2026`, years `2026`.
- In days zoom, Saturday and Sunday columns are lightly shaded.
- A vertical **today line** runs across header and rows when today is in range.

### Rows and bars
- Rows: every medicine of the selected person (or of everyone in «Усі») whose course overlaps the visible range —
  finished courses included. Order: by start date, then name.
- In «Усі», rows are grouped by person: a group header row with the person's avatar and name.
- Left fixed column: the medicine name (with its colour dot).
- Bar: medicine colour, from the start date to the end date (inclusive), positioned by exact date, so it can start
  or end mid-column. Label inside: `Назва · дозування`; if the bar is too short, the label is drawn to the right of it.
- Bars cut by the range edge get a flat edge; an ongoing course (`Постійно`) that runs past the right edge shows `→`.
- Tooltip: `Назва — дозування`, `13.10.2026 – 22.10.2026` or `з 13.10.2026 · Постійно`.
- Click on a bar opens the existing medicine edit dialog; after it closes the timeline refreshes.
- Empty state: the existing «немає осіб» message when there are no persons; a new
  «Немає ліків у цьому періоді» when no course overlaps the visible range (the toolbar stays, so you can navigate).

### Theming
All colours come from theme tokens (`-surface`, `-border`, `-muted`, `-hover`, `-danger` for the today line, a new
`-weekend` token), so light and dark both work.

## Architecture

Approach: a custom layout pane where bars are absolutely positioned by date (chosen over a cell grid, which can only
snap to whole columns, and a canvas, which loses CSS theming and hover/click).

### `ua.medicine.schedule.TimeScale` (pure, unit-tested)
- `enum Zoom { DAYS, WEEKS, MONTHS, QUARTERS, YEARS }`.
- `static TimeScale around(Zoom zoom, LocalDate anchor)` → the visible range per the table above.
- `TimeScale previous()`, `TimeScale next()`.
- `LocalDate start()`, `LocalDate endExclusive()`, `Zoom zoom()`.
- `List<Column> columns()` — `record Column(LocalDate start, LocalDate endExclusive)`.
- `double fraction(LocalDate date)` — position of the start of `date` in [0, 1] across the range,
  proportional to days (so months of different length get proportional widths); clamped.
- `boolean contains(LocalDate date)`.

### `ua.medicine.ui.TimeLabels` (pure, unit-tested)
Ukrainian column and range labels for a `TimeScale`, using `Messages`/bundle keys for month and weekday abbreviations
and the quarter format.

### `ua.medicine.ui.TimelineView` (JavaFX)
- A `VBox`: toolbar, then a `ScrollPane` (vertical only) holding header + rows.
- Rows area: left name column of fixed width; right area is a `Pane` whose children (column stripes, bars, today line)
  are laid out in `layoutChildren()` from `TimeScale.fraction(...)` × the current width, so it follows window resizes.
- API: `TimelineView(Settings settings, Consumer<Medicine> onOpen)`,
  `void show(List<Person> persons, List<Medicine> medicines, boolean grouped, LocalDate today)`; the visible rows
  (person headers and medicines) are chosen by a pure `TimelineRows.build(...)` helper.
- The view owns its `TimeScale` state; zoom changes write `settings.setTimelineZoom(...)`.

### `DashboardController`
- Holds both the cards `ScrollPane` and the `TimelineView` in the center; the toggle swaps which one is visible.
- `refresh()` builds whichever view is active. For the timeline, medicines come from
  `MedicineRepository.findActive()` / `findActiveByPerson(id)` (all non-deleted medicines; filtering to the visible
  range happens in the view).

### `Settings`
- `DashboardView dashboardView()` / `setDashboardView(...)` (`CARDS` default).
- `TimeScale.Zoom timelineZoom()` / `setTimelineZoom(...)` (`MONTHS` default). Unknown values fall back to defaults.

### Strings
New keys in `messages_uk.properties`: view toggle, zoom names, «Сьогодні», month abbreviations, weekday
abbreviations, quarter format, tooltip formats.

## Out of scope
Dose-level detail or adherence in the bars, dragging bars to change dates, horizontal scrolling/zooming with the
mouse wheel, per-tab view memory.

## Testing
- `TimeScaleTest`: range start/end per zoom (including month-end, year-end and quarter boundaries), `next`/`previous`
  round-trip, column count per zoom, `fraction` at range start/end/middle and clamping.
- `TimeLabelsTest`: labels for each zoom, including the year boundary.
- `SettingsTest`: new keys round-trip and fall back on unknown values.
- Visual check with `SnapshotMain`: timeline in every zoom, in light and dark, on «Усі» and a person tab.
