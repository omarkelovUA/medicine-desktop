# Marking doses from the phone — design

## Goal

Today a dose can be ticked only in the desktop app. Each dose is already an event in the person's «Ліки» Google
calendar, and a taken dose's event is green (colour «Basil»). After this change a person can **tick a dose on the
phone by making its event green** in Google Calendar, and untick it by removing the green. The app picks the
change up the next time it syncs.

Sync becomes two-way: the app still pushes its changes, and now also reads the colours back.

## Decisions

- **Green means taken.** Both greens count: «Basil» (`colorId` 10, the app's own) and «Sage» (`colorId` 2).
  Any other colour, or no colour, means not taken.
- **Unticking from the phone is allowed**: removing the green from a taken dose's event unticks it.
- **Own-day rule**, as in the app: a phone change counts only if it was made on the dose's own day (the event's
  `updated` time, in the system zone, falls on the dose's date). The app may see it later — e.g. the next morning —
  and still accepts it. A change made on a later day is ignored and the event is restored on the next push.
- **The time of a phone tick** is the event's `updated` time.
- **No invitations, no attendees.** The RSVP approach was tested and rejected: every created event notifies the
  phone, so a sync of 50 doses would give 50 notifications. Events stay as they are; only the dose reminder at its
  time notifies.
- **The app's pending change wins.** A dose changed in the app but not yet pushed is not touched by the pull; its
  push then overwrites the event.

## Reading events back

### Gateway

`CalendarGateway` gets one method:

```java
/** Events of the calendar starting in [from, toExclusive); deleted events are not returned. */
List<RemoteEvent> listEvents(String calendarId, LocalDateTime from, LocalDateTime toExclusive);
```

`record RemoteEvent(String id, String colorId, Instant updated)`.

`GoogleCalendarGateway` implements it with `events.list` (`timeMin`, `timeMax`, `showDeleted=false`, following
`nextPageToken`), mapping errors like the other calls (a 404 means the calendar is gone).

### When

The pull runs inside `CalendarSyncService.syncPerson`, **after** the push, for every linked person. Sync already
runs after each change in the app, at the start of the app and on a new day; the periodic sync in `AppJobs` changes
from every 15 minutes to **every 5 minutes**, so phone marks show up in the app within 5 minutes while it is open.

### Window

Events starting from **7 days before today** up to the end of today. This covers the app being closed for several
days. Future doses are not pulled: they cannot be ticked yet.

### Rules

For each listed event the app finds the dose with that `calendar_event_id` among the person's doses in the window
(one query). Events with no dose (not created by the app) are ignored. Doses whose event is missing from the list
are ignored too; a deleted event is re-created by the push as today.

The rules live in a small pure class `RemoteMarks` (in `ua.medicine.sync`) that, given a dose, the remote event and
the zone, returns one of `NONE`, `TAKE(at)`, `UNTAKE`, `RESTORE`:

| Dose in the app       | Event                 | Changed on the dose's day | Result                       |
|-----------------------|-----------------------|---------------------------|------------------------------|
| not `CLEAN`           | anything              | —                         | `NONE` (app change wins)     |
| not taken             | not green             | —                         | `NONE`                       |
| taken                 | green                 | —                         | `NONE`                       |
| not taken             | green                 | yes                       | `TAKE(updated)`              |
| taken                 | not green             | yes                       | `UNTAKE`                     |
| not taken             | green                 | no                        | `RESTORE`                    |
| taken                 | not green             | no                        | `RESTORE`                    |

Because a `CLEAN` dose's event holds exactly what the app last pushed, any difference was made on the phone.

### Applying

`DoseRepository` gets guarded single-statement writes, in the style of `recordPushed`, so a tick made in the app
while the sync thread is pulling is never overwritten:

- `markTakenFromPhone(id, at)`: `taken_at=at, missed=0, sync_state='DIRTY'` where `taken_at IS NULL AND
  sync_state='CLEAN'`.
- `unmarkTakenFromPhone(id, missed)`: `taken_at=NULL, missed=?, sync_state='DIRTY'` where `taken_at IS NOT NULL
  AND sync_state='CLEAN'`. `missed` is true when the dose's day is already over, as `resolveMissed` would set.
- `RESTORE` uses a guarded `UPDATE ... SET sync_state='DIRTY' WHERE id=? AND sync_state='CLEAN'`.

All three leave the dose `DIRTY`, so a second push in the same sync run sends the event back in the app's form:
title with «✅» or «💊», colour Basil or none. A «Sage» tick thus becomes «Basil» — phone and app look the same.
The sync service runs the push again only when the pull changed something.

### Result and UI

`SyncResult` gets a `pulled` count (doses ticked or unticked from the phone). When it is above zero, the
dashboard's `onSyncResult` refreshes the view at once instead of waiting for the next 30-second tick. The status
text does not change.

## Errors

A failed `listEvents` is handled like a failed push: `UncheckedIOException` counts as failed and is logged,
`AuthRequiredException` marks the person as needing re-linking, and `CalendarNotFoundException` re-creates the
calendar as today. Doses are not changed by a failed pull.

## README

A short section «Позначка з телефона»: open the dose's event in Google Calendar on the phone → edit → colour
«Базилік» or «Шавлія» → save; remove the colour to untick; it must be done on the dose's day; the app shows it after
its next sync (within 5 minutes while open).

## Testing

- `RemoteMarksTest`: every row of the table, both greens, a change just before and just after midnight.
- `DoseRepositoryTest`: the guarded writes do nothing on a `DIRTY` row or on a row already in the target state.
- `FakeCalendarGateway`: `listEvents` returning its events with a colour and an `updated` time the test can set
  (to simulate a phone edit).
- `CalendarSyncServiceTest`: a phone tick ends with the dose taken at the edit time and the event pushed back with
  «✅» and Basil; a later-day edit is restored; a dose ticked in the app before the pull keeps the app's state;
  a failing list counts as failed.

## Out of scope

- Reminders already shown in the app's notification pane are not removed when the dose is ticked from the phone;
  their «Прийнято» button does nothing for an already-taken dose.
- Incremental sync with sync tokens.
