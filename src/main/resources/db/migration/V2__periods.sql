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
