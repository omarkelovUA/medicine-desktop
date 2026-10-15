# Children in a Parent's Calendar Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A person (child) can be linked through another person's (parent's) Google account; their doses go to a
calendar «Ліки <child's name>» in the parent's account.

**Architecture:** Two nullable columns on `person` (`calendar_host_id`, `calendar_name`). A child's token is the
parent's (`Person.tokenOwnerId()`); everything per-person in sync stays as it is, only the gateway (cached per token
owner within a run) and the calendar differ. Rename and deleted-calendar recovery use the person's calendar name.

**Tech Stack:** Java 21, JavaFX, SQLite + Flyway, Google Calendar API, JUnit 5 + AssertJ.

**Spec:** `docs/superpowers/specs/2026-10-15-child-calendars-design.md`

## Global Constraints

- All UI texts in Ukrainian, in `src/main/resources/ua/medicine/i18n/messages_uk.properties`; no ASCII apostrophe
  `'` in values (use `’`) — `MessagesTest.bundleHasNoAsciiApostrophes`.
- Child calendar name: `"Ліки " + child's name` (`GoogleLinkService.CALENDAR_NAME + " " + name`).
- One level only: a host is linked with its own account and is not hosted; a person who hosts others cannot become
  a child.
- Existing tests keep passing; existing 5-argument `new Person(...)` calls keep compiling.
- Run tests: `mvn -q test -Dtest=<Class>` (one class) / `mvn -q test` (all).

## Review Focus

1. **Child sorted before its parent** (`findAll` orders by id; a child may have the smaller id) — the gateway cache
   and auth-failure propagation must not depend on order. → Task 4 test
   `childBeforeParentInListStillSyncsAndSharesAuthFailure`.
2. **Editing a child's name/colour in the person dialog** must not drop the host link (the dialog rebuilds `Person`
   from 5 fields today). → Task 1 test `withNameAndColorKeepsHostLink`; Task 2 switches the dialog to it.
3. **Trying to delete a parent with children** must not forget the parent's token first (the dialog forgets the token
   before deleting). → Task 2 test `ensureDeletableRefusesParentWithChildren`, dialog calls it before `forget`.
4. **Rename failing (offline)** must be retried on the next sync, and doses still pushed. → Task 4 test
   `renameFailureIsRetriedNextSync`.
5. **Parent re-linked with another account** — children's e-mail follows, their calendars are re-created in the new
   account. → Task 3 test `relinkingParentUpdatesChildrenEmail`; Task 4 test
   `deletedChildCalendarIsRecreatedWithChildNameAndKeepsHost`.

---

### Task 1: Person model, repository, migration

**Files:**
- Create: `src/main/resources/db/migration/V4__calendar_host.sql`
- Modify: `src/main/java/ua/medicine/domain/Person.java`
- Modify: `src/main/java/ua/medicine/persistence/PersonRepository.java`
- Test: `src/test/java/ua/medicine/persistence/PersonRepositoryTest.java`, `src/test/java/ua/medicine/domain/PersonTest.java` (new)

**Interfaces:**
- Produces:
  - `record Person(Long id, String name, String color, String googleEmail, String googleCalendarId, Long calendarHostId, String calendarName)`
  - extra constructor `Person(Long id, String name, String color, String googleEmail, String googleCalendarId)` → host fields `null`
  - `boolean hosted()` — `calendarHostId != null`
  - `long tokenOwnerId()` — `hosted() ? calendarHostId : id`
  - `Person withGoogle(String email, String calendarId)` — own link; clears `calendarHostId`, `calendarName`
  - `Person withHostedGoogle(String email, String calendarId, long hostId, String calendarName)`
  - `Person withCalendar(String calendarId, String calendarName)` — keeps e-mail and host
  - `Person withNameAndColor(String name, String color)` — keeps everything else
  - `PersonRepository.findHostedBy(long hostId): List<Person>` ordered by id

- [ ] **Step 1: Write failing tests**

`PersonTest`:
- `withNameAndColorKeepsHostLink`: `new Person(1L,"Софія","#e08a1e","o@g","cal",2L,"Ліки Софія").withNameAndColor("Соня","#000")` → name `Соня`, color `#000`, `calendarHostId` `2L`, `calendarName` `Ліки Софія`, email/calendar unchanged.
- `withGoogleClearsHostLink`: hosted person `.withGoogle("s@g","own")` → `hosted()` false, `calendarName` null.
- `tokenOwnerIsHostForChildAndSelfOtherwise`: `tokenOwnerId()` is `2` for the hosted person above, `1` for `new Person(1L,…,null,null)`.

`PersonRepositoryTest`:
- `hostFieldsRoundTripAndFindHostedBy`: save parent; save child `withHostedGoogle("o@g","cal-Ліки Софія",parent.id(),"Ліки Софія")`; `findById(child)` equals saved child; `findHostedBy(parent.id())` contains exactly the child; `findHostedBy(child.id())` empty.

- [ ] **Step 2: Run** `mvn -q test -Dtest='PersonTest,PersonRepositoryTest'` — Expected: compilation FAIL (no such members).

- [ ] **Step 3: Implement**

`V4__calendar_host.sql`:
```sql
ALTER TABLE person ADD COLUMN calendar_host_id INTEGER REFERENCES person (id);
ALTER TABLE person ADD COLUMN calendar_name    TEXT;
```
`Person`: members listed above. `PersonRepository`: INSERT/UPDATE/`bind`/`map` with the two new columns
(`calendar_host_id` via `setObject`/`getObject(…, Long.class)` so `NULL` stays `null`); `findHostedBy` =
`SELECT * FROM person WHERE calendar_host_id=? ORDER BY id`.

- [ ] **Step 4: Run** `mvn -q test` — Expected: all PASS (the migration runs in `TestDb`; existing tests and
  `MergeDuplicatesMigrationTest` prove V4 applies over V1–V3).

- [ ] **Step 5: Commit** `git commit -m "feat: person can keep its calendar in another person's account"`

---

### Task 2: Refuse deleting a parent with children

**Files:**
- Create: `src/main/java/ua/medicine/service/PersonHostsChildrenException.java`
- Modify: `src/main/java/ua/medicine/service/PersonService.java`
- Modify: `src/main/java/ua/medicine/ui/PersonDialogController.java`
- Modify: `src/main/resources/ua/medicine/i18n/messages_uk.properties`
- Test: `src/test/java/ua/medicine/service/PersonServiceTest.java`

**Interfaces:**
- Consumes: `PersonRepository.findHostedBy`, `Person.withNameAndColor` (Task 1)
- Produces:
  - `PersonHostsChildrenException extends RuntimeException` with `List<String> childNames()`
  - `PersonService.ensureDeletable(long personId)` — throws `PersonHostsChildrenException` (checked first), then `PersonHasMedicinesException`
  - `PersonService.delete(long)` calls `ensureDeletable` first

- [ ] **Step 1: Write failing test** `ensureDeletableRefusesParentWithChildren`: create parent and child (child saved
  via `personRepo.save(child.withHostedGoogle("o@g","c",parent.id(),"Ліки Софія"))`);
  `assertThatThrownBy(() -> persons.ensureDeletable(parent.id()))` is `PersonHostsChildrenException` with
  `childNames()` `["Софія"]`; same for `persons.delete(parent.id())`; `persons.all()` still has 2;
  `persons.delete(child.id())` succeeds.

- [ ] **Step 2: Run** `mvn -q test -Dtest=PersonServiceTest` — Expected: compilation FAIL.

- [ ] **Step 3: Implement** the service members. In `PersonDialogController`:
  - save uses `ctx.personService().update(existing.withNameAndColor(text, color.selected()))`;
  - delete calls `ctx.personService().ensureDeletable(existing.id())` **before** `ctx.googleAuth().forget(...)`;
  - catch `PersonHostsChildrenException ex` → `Dialogs.error(…, Messages.get("error.personHostsChildren", String.join(", ", ex.childNames())))`.
  - Message: `error.personHostsChildren=Неможливо видалити: через календар цієї особи підключено інших: {0}`

- [ ] **Step 4: Run** `mvn -q test` — Expected: all PASS.

- [ ] **Step 5: Commit** `git commit -m "feat: a parent hosting children cannot be deleted"`

---

### Task 3: Linking through a parent (`GoogleLinkService`)

**Files:**
- Modify: `src/main/java/ua/medicine/sync/GoogleLinkService.java`
- Modify: `src/main/java/ua/medicine/sync/GoogleCalendarGatewayFactory.java`
- Test: `src/test/java/ua/medicine/sync/GoogleLinkServiceTest.java`

**Interfaces:**
- Consumes: Task 1 `Person` members, `PersonRepository.findHostedBy`
- Produces:
  - `static String childCalendarName(String personName)` → `CALENDAR_NAME + " " + personName`
  - `static String calendarNameFor(Person p)` → `p.hosted() ? childCalendarName(p.name()) : CALENDAR_NAME`
  - `List<Person> possibleHosts(long personId)` — linked, not hosted, id ≠ personId
  - `List<Person> hostedBy(long hostId)` — delegates to the repository
  - `Person linkThroughHost(long childId, long hostId)` — validates (below), gateway `gateways.forPerson(host)`, `ensureCalendar(childCalendarName(child.name()))`, forgets the child's own token if it had one (`!child.hosted()` → `auth.forget(childId)`), `saveHostedLink`, `syncTrigger.requestSync()`
  - package-private `Person saveHostedLink(Person child, Person host, String calendarId)`
  - `GoogleCalendarGatewayFactory.forPerson(p)` loads the token of `p.tokenOwnerId()`

Validation in `linkThroughHost` (each `IllegalArgumentException`, before any Google call): host missing; host not
linked; host hosted; `childId == hostId`; `hostedBy(childId)` not empty.

`saveHostedLink`: saves `child.withHostedGoogle(host.googleEmail(), calendarId, host.id(), childCalendarName(child.name()))`;
if `calendarId` ≠ `child.googleCalendarId()` → `doses.markDirtyForPerson(child.id())`.

`saveLink` (own link) additionally: for each `hostedBy(person.id())` whose e-mail differs, save it with the new
e-mail (other fields unchanged).

`unlink(personId)`: hosted → save `withGoogle(null, null)`, no token touched. Otherwise → `auth.forget(personId)`,
save it and each `hostedBy(personId)` with `withGoogle(null, null)`.

- [ ] **Step 1: Write failing tests** (use the existing setup: `TestDb`, real `GoogleAuthService` on `@TempDir`):
  - `saveHostedLinkStoresHostEmailIdAndCalendarName`: parent linked `"o@gmail.com","calO"`; `saveHostedLink(sofia, parent, "cal-Ліки Софія")` → stored `googleEmail` `o@gmail.com`, `calendarHostId` parent id, `calendarName` `Ліки Софія`, `googleLinked()` true.
  - `hostedRelinkToSameCalendarKeepsDosesCleanOtherCalendarMarksDirty`: as the existing own-link test, with `saveHostedLink`.
  - `linkThroughHostRefusesInvalidHosts`: `IllegalArgumentException` for unlinked host, hosted host, host == child, child that hosts someone.
  - `unlinkingChildKeepsParentToken`: create `home/tokens/<parentId>/StoredCredential` file; `unlink(child)` → child not linked, not hosted; file still exists; parent still linked.
  - `unlinkingParentUnlinksChildren`: parent with two children; `unlink(parent)` → all three not linked, `hostedBy(parent)` empty, the parent's token dir gone.
  - `relinkingParentUpdatesChildrenEmail`: `saveLink(parent, "new@gmail.com", "calX")` → child's `googleEmail` `new@gmail.com`, `calendarHostId`, `googleCalendarId` unchanged.
  - `possibleHostsAreLinkedNonHostedOthers`: with parent (linked), child (hosted), unlinked Марко → `possibleHosts(marko)` = `[parent]`; `possibleHosts(parent)` excludes parent.
  - `calendarNameFor`: `Ліки` for own link, `Ліки Софія` for hosted Софія.

- [ ] **Step 2: Run** `mvn -q test -Dtest=GoogleLinkServiceTest` — Expected: compilation FAIL.

- [ ] **Step 3: Implement** the members above.

- [ ] **Step 4: Run** `mvn -q test` — Expected: all PASS.

- [ ] **Step 5: Commit** `git commit -m "feat: link a person through a parent's Google account"`

---

### Task 4: Sync for children — shared gateway, auth, rename, recovery

**Files:**
- Modify: `src/main/java/ua/medicine/sync/CalendarGateway.java`, `GoogleCalendarGateway.java`, `CalendarSyncService.java`
- Modify: `src/test/java/ua/medicine/sync/FakeCalendarGateway.java`
- Test: `src/test/java/ua/medicine/sync/CalendarSyncServiceTest.java`

**Interfaces:**
- Consumes: `Person.tokenOwnerId()`, `withCalendar`, `GoogleLinkService.calendarNameFor` (Tasks 1, 3)
- Produces: `CalendarGateway.renameCalendar(String calendarId, String name)` — `@throws CalendarNotFoundException`;
  Google impl `client.calendars().patch(calendarId, new Calendar().setSummary(name))` through `call(null, calendarId, …)`.

`FakeCalendarGateway`: `final Map<String, String> renamed` (calendarId → name), `int renames`; `renameCalendar`
runs `check()`, throws `CalendarNotFoundException` if `calendarDeleted`, else records.

`CalendarSyncService.syncNow` changes:
```
Map<Long, CalendarGateway> byOwner;  Set<Long> failedOwners
for person in findAll():
    not linked → as today
    owner = person.tokenOwnerId()
    if failedOwners has owner → continue
    try: gateway = byOwner.computeIfAbsent(owner, k -> gateways.forPerson(person)); sync (recovery as below)
    catch AuthRequiredException: failedOwners.add(owner); lastError; log
authFailed = ids of linked persons whose tokenOwnerId ∈ failedOwners (in findAll order)
```
`syncPerson` takes the `Person` (and calendar id). Before `pushChanges`, for a hosted person whose `calendarName` ≠
`calendarNameFor(person)`: `renameCalendar`, then `persons.save(person.withCalendar(calendarId, newName))`; an
`UncheckedIOException` there counts as `failed` and pushing continues; `CalendarNotFoundException` propagates.
Recovery: `ensureCalendar(calendarNameFor(person))`, save `person.withCalendar(newId, person.hosted() ? calendarNameFor(person) : null)`.

- [ ] **Step 1: Update the test factory and write failing tests.** Factory lambda uses `gateways.get(p.tokenOwnerId())`.
  Helper: `Person child(String name, Person host)` saves `withHostedGoogle(host.googleEmail(), "cal-Ліки " + name, host.id(), "Ліки " + name)`.
  - `childDosesGoToChildCalendarThroughParentGateway`: child of Олександр with a medicine → after `syncNow` its doses are CLEAN, and their events are in `fake` (count = 2 + child's); add a `Map<String,String> eventCalendar` to the fake (eventId → calendarId) and assert the child's events are in `cal-Ліки Софія`.
  - `phoneMarkOnChildEventIsPulled`: as the existing phone-mark test, for the child's dose event.
  - `parentAuthFailureMarksChildrenToo`: `fake.failAuth = true` → `authFailedPersonIds()` contains Олександр and the child.
  - `childBeforeParentInListStillSyncsAndSharesAuthFailure`: save the child first, then a new parent (larger id) with its own fake; sync succeeds for both with one `forPerson` call per owner (count calls in the factory); with `failAuth` both are in `authFailedPersonIds`.
  - `deletedChildCalendarIsRecreatedWithChildNameAndKeepsHost`: `fake.calendarDeleted = true` → child's `googleCalendarId` `cal-Ліки Софія`, still `hosted()`, `calendarName` `Ліки Софія`, doses CLEAN.
  - `renamedChildCalendarIsRenamedOnce`: rename child to `Соня` (`withNameAndColor`) → after sync `fake.renamed` has `Ліки Соня`, stored `calendarName` `Ліки Соня`; second sync → `renames` still 1.
  - `renameFailureIsRetriedNextSync`: rename then `failIo` only on rename (add `boolean failRename` to the fake throwing `UncheckedIOException`) → `failed()` positive, `calendarName` unchanged, doses still pushed; clear flag, sync → renamed.

- [ ] **Step 2: Run** `mvn -q test -Dtest=CalendarSyncServiceTest` — Expected: new tests FAIL (compile error on `renameCalendar` first).

- [ ] **Step 3: Implement** gateway method, fake, and sync changes above.

- [ ] **Step 4: Run** `mvn -q test` — Expected: all PASS, including the existing auth tests (`authFailureForOnePersonDoesNotStopOthers`, `missingCredentialIsAuthFailure`).

- [ ] **Step 5: Commit** `git commit -m "feat: sync children's doses to their calendar in the parent's account"`

---

### Task 5: Dashboard Google button, texts, README

**Files:**
- Modify: `src/main/java/ua/medicine/ui/DashboardController.java` (`buildPersonBar`, `toggleGoogle`)
- Modify: `src/main/resources/ua/medicine/i18n/messages_uk.properties`
- Modify: `README.md`
- Test: `src/test/java/ua/medicine/i18n/MessagesTest.java`

**Interfaces:**
- Consumes: `GoogleLinkService.possibleHosts`, `hostedBy`, `linkThroughHost`, `link`, `unlink`; `Person.hosted()`, `tokenOwnerId()`

Messages (exact):
```
google.ownAccount=Власний акаунт Google…
google.throughHost=Через календар {0} ({1})
google.connectedThrough=G ✓ через {0}
google.disconnectAlso=Також буде відключено: {0}
```

Behaviour (spec, «UI» table):
- `buildPersonBar`: linked, hosted, not auth-failed → `google.connectedThrough` with the host's name (from the `persons` list).
- `toggleGoogle`:
  - linked and not auth-failed → confirm `confirm.disconnect`; for a non-hosted person with `hostedBy` not empty append `"\n" + google.disconnectAlso(names joined ", ")`; `unlink`.
  - linked and auth-failed → own sign-in of `p.tokenOwnerId()`; on success remove from `authFailedPersonIds` every person with that token owner.
  - not linked, `possibleHosts` empty → own sign-in of `p.id()` (today's code, extracted to `private void signIn(long personId)`).
  - not linked, hosts present → `ContextMenu` shown under `googleButton` (`show(googleButton, Side.BOTTOM, 0, 0)`): `google.ownAccount` → `signIn(p.id())`; per host `google.throughHost(name, email)` → `linkThroughHost` on `ctx.io()`, button disabled with «…» meanwhile, failure → `google.linkFailed`, then `refresh()`.

README: section «Діти в календарі батьків» after «Позначка з телефона»: connect the parent first; on the child's tab
«Підключити Google» → «Через календар …»; calendar «Ліки <ім’я>» appears in the parent's account; doses can be
ticked from the parent's phone; renaming the child renames the calendar; disconnecting the parent disconnects the
children; a parent with connected children cannot be deleted. Add a line to the feature list at the top.

- [ ] **Step 1: Write failing test** `MessagesTest.childCalendarTexts`: `Messages.get("google.throughHost","Олена","o@g")` = `Через календар Олена (o@g)`; `Messages.get("google.connectedThrough","Олена")` = `G ✓ через Олена`.
- [ ] **Step 2: Run** `mvn -q test -Dtest=MessagesTest` — Expected: FAIL (missing key).
- [ ] **Step 3: Implement** messages, dashboard changes, README.
- [ ] **Step 4: Run** `mvn -q test` — Expected: all PASS. Then `mvn javafx:run` and check by hand: a person with no
  connected others gets the browser directly; with a connected parent the menu shows both items; linking through the
  parent shows «G ✓ через …»; disconnecting the parent asks with the children's names and disconnects them.
- [ ] **Step 5: Commit** `git commit -m "feat: connect a child through a parent's calendar from the person bar"`
