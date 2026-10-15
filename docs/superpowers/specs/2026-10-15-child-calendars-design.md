# Children in a parent's calendar — design

## Goal

Today each person links their own Google account, and their doses go to the «Ліки» calendar in it. A child often
has no Google account of their own, or the parent wants to follow the child's medicines on their own phone.

After this change a person (a **child**) can be linked **through another person's (the parent's) Google account**:
the child's doses go to a separate calendar «Ліки <child's name>» in the parent's account. The parent then sees the
child's dose events in Google Calendar next to their own «Ліки», and can tick them on the phone by colour exactly as
their own (see `2026-10-14-phone-marks-design.md`).

In the app a child is an ordinary person: own tab, cards, timeline, history, reminders. Only the Google link differs.

## Decisions

- **The calendar lives in the parent's account and is shared with nobody.** The app never touches calendar ACLs.
  (A parent may share it in Google Calendar by hand; the app does not care.)
- **The child uses the parent's token.** No token of its own, no browser sign-in when linking through a parent.
- **One level only.** The parent must be linked with their own account (not through someone else). A child cannot
  host other children.
- **Calendar name** is `"Ліки " + child's name`. Renaming the child renames the calendar on the next sync.
- **Unlinking the parent cascades**: their children are unlinked too, after a confirmation naming them.
- **Deleting a parent who has children is refused**, with a message.
- **Parent's token expired** → the parent and each of their children show «G ⚠ Перепідключити»; reconnecting from
  either tab runs the parent's sign-in, and fixes all of them.

## Data

Migration `V4__calendar_host.sql`:

```sql
ALTER TABLE person ADD COLUMN calendar_host_id INTEGER REFERENCES person (id);
ALTER TABLE person ADD COLUMN calendar_name    TEXT;
```

- `calendar_host_id` — the parent whose account holds this person's calendar; `NULL` for a person linked with their
  own account or not linked.
- `calendar_name` — the name the calendar had at the last link or sync, set only for a hosted person. Lets the sync
  see that the child was renamed.

`Person` gets `Long calendarHostId` and `String calendarName`, plus:

- `boolean hosted()` — `calendarHostId != null`;
- `long tokenOwnerId()` — `hosted() ? calendarHostId : id`;
- `withGoogle(email, calendarId)` keeps working for own links and clears both new fields;
- `withHostedGoogle(email, calendarId, hostId, calendarName)` for children.

For a linked child, `google_email` is the parent's e-mail (so `googleLinked()` stays as it is) and
`google_calendar_id` is the id of «Ліки <name>» in the parent's account.

`PersonRepository` reads and writes the new columns, and gets `findHostedBy(long hostId)`.

## Linking (`GoogleLinkService`)

- `List<Person> possibleHosts(long personId)` — linked persons, not hosted, other than this one.
- `Person linkThroughHost(long childId, long hostId)`:
  1. host must exist, be linked and not hosted; child must not be the host and must have no children of its own
     (otherwise `IllegalArgumentException`);
  2. gateway from the host's token (`AuthRequiredException` if none);
  3. `calendarId = gateway.ensureCalendar("Ліки " + child.name())`;
  4. save `withHostedGoogle(host.googleEmail(), calendarId, hostId, name)`; if the calendar id differs from the
     child's previous one, mark the child's doses dirty (same rule as `saveLink`);
  5. request a sync.
- `link(personId)` (own account): unchanged for the person. If the person hosts children, their `google_email` is
  updated to the new e-mail. If the account changed, the children's calendar ids answer 404 at the next sync and
  are re-created by the existing recovery.
- `unlink(personId)`:
  - for a hosted person: clear the link fields; **do not** forget any token (it is the parent's);
  - for a parent: forget the token, unlink the person and each child.
- A token is forgotten only by `tokenOwnerId` == the person's own id.

## Sync (`CalendarSyncService`)

- `GoogleCalendarGatewayFactory.forPerson(person)` loads the token of `person.tokenOwnerId()`.
- Within one run the gateway is created once per token owner and reused for the parent and children.
- If creating the gateway or a call throws `AuthRequiredException`, the person **and every person with the same
  token owner** go into `authFailed` (a child's failure is the parent's failure and vice versa).
- **Rename:** before pushing a hosted person, if `calendarName` ≠ `"Ліки " + name`, call
  `gateway.renameCalendar(calendarId, newName)` and save the new `calendarName`. A failure is counted like other
  push failures and retried on the next sync.
- **Calendar deleted** (`CalendarNotFoundException`): as today, but the calendar is re-created with the person's
  calendar name (`"Ліки"` for own links, `"Ліки <name>"` for children), and a hosted person keeps its host fields.
- Push and pull of doses are unchanged — they already work per person and calendar id.

`CalendarGateway` gets:

```java
/** @throws CalendarNotFoundException if the calendar no longer exists */
void renameCalendar(String calendarId, String name);
```

implemented in `GoogleCalendarGateway` with `calendars().patch(calendarId, new Calendar().setSummary(name))`, and in
`FakeCalendarGateway` for tests.

Renaming a person in `PersonDialogController` only saves the name and requests a sync; the sync does the rest.

## Deleting a person (`PersonService`)

`delete(personId)` throws `PersonHostsChildrenException` if `findHostedBy(personId)` is not empty, checked before the
active-medicines check. The dialog shows «Неможливо видалити: через календар цієї особи підключено інших: {0}».
Deleting a child forgets no token (only the own token is forgotten, as today, which a child does not have).

## UI (dashboard person bar)

Google button, per selected person:

| State | Text | Click |
|---|---|---|
| not linked, there are possible hosts | «Підключити Google» | `ContextMenu`: «Власний акаунт Google…» + «Через календар {0} ({1})» per host |
| not linked, no possible hosts | «Підключити Google» | own sign-in, as today |
| linked own | «G ✓ {email}» | unlink, as today; if the person hosts children the confirmation adds «Також буде відключено: {names}» |
| linked through a parent | «G ✓ через {parent name}» | confirm and unlink the child |
| auth failed (own or parent's) | «G ⚠ Перепідключити» | own sign-in of the token owner (`link(tokenOwnerId)`) |

Linking through a host runs off the UI thread like the own sign-in; on failure — «Не вдалося підключити Google.
Спробуйте ще раз.» (existing message). On success the button and the sync status refresh as today.

New texts in `messages_uk.properties`:

```
google.ownAccount=Власний акаунт Google…
google.throughHost=Через календар {0} ({1})
google.connectedThrough=G ✓ через {0}
google.disconnectAlso=Також буде відключено: {0}
error.personHostsChildren=Неможливо видалити: через календар цієї особи підключено інших: {0}
```

## Tests

- `PersonRepositoryTest` — new columns round-trip; `findHostedBy`.
- Migration — V4 applies over an existing DB; existing persons get `NULL` in both columns.
- `GoogleLinkServiceTest`:
  - linking through a host creates «Ліки <name>» in the host's gateway and stores host e-mail, id and name;
  - refused when the host is not linked, is hosted, or is the child itself, or the child hosts others;
  - re-linking to the same calendar keeps doses clean; a different calendar marks them dirty;
  - unlinking a child keeps the parent's token; unlinking a parent unlinks the children and forgets one token;
  - re-linking a parent updates the children's e-mail.
- `CalendarSyncServiceTest` (fake gateway):
  - a child's doses are pushed to the child's calendar via the parent's gateway; phone marks are pulled from it;
  - the parent's `AuthRequiredException` puts the parent and children into `authFailed`;
  - a deleted child calendar is re-created as «Ліки <name>» and the host link is kept;
  - a renamed child gets `renameCalendar` once, and `calendarName` is saved.
- `PersonServiceTest` — deleting a parent with children throws `PersonHostsChildrenException`.
- `MessagesTest` — the new keys exist (if it checks keys).

## README

A short section «Діти в календарі батьків»: connect the parent first, then on the child's tab
«Підключити Google» → «Через календар …»; the calendar «Ліки <ім’я>» appears in the parent's account, doses can be
ticked from the parent's phone; unlinking the parent unlinks the children.

## Out of scope

- Sharing the child's calendar with the child's own Google account.
- Children of children; several parents for one child.
- Choosing the calendar name other than «Ліки <name>».
