CREATE TABLE person (
    id                 INTEGER PRIMARY KEY,
    name               TEXT    NOT NULL UNIQUE,
    color              TEXT    NOT NULL,
    google_email       TEXT,
    google_calendar_id TEXT
);

CREATE TABLE medicine (
    id             INTEGER PRIMARY KEY,
    person_id      INTEGER NOT NULL REFERENCES person (id),
    name           TEXT    NOT NULL,
    dosage         TEXT,
    color          TEXT    NOT NULL,
    start_date     TEXT    NOT NULL,
    course_amount  INTEGER,
    course_unit    TEXT,
    schedule_type  TEXT    NOT NULL,
    schedule_days  TEXT,
    schedule_times TEXT    NOT NULL,
    active         INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE dose (
    id                INTEGER PRIMARY KEY,
    medicine_id       INTEGER NOT NULL REFERENCES medicine (id),
    scheduled_at      TEXT    NOT NULL,
    taken_at          TEXT,
    missed            INTEGER NOT NULL DEFAULT 0,
    calendar_event_id TEXT,
    sync_state        TEXT    NOT NULL DEFAULT 'DIRTY',
    UNIQUE (medicine_id, scheduled_at)
);

CREATE INDEX dose_scheduled ON dose (scheduled_at);
