ALTER TABLE person ADD COLUMN calendar_host_id INTEGER REFERENCES person (id);
ALTER TABLE person ADD COLUMN calendar_name    TEXT;
