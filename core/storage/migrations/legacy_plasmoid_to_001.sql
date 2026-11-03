-- One-off upgrade for databases created by the first plasmoid builds
-- (notes.id INTEGER AUTOINCREMENT, no deleted_at) to schema version 1.
-- Run only when PRAGMA user_version = 0 and the notes table exists.

ALTER TABLE notes RENAME TO notes_legacy;

CREATE TABLE notes (
    id          TEXT    PRIMARY KEY NOT NULL,
    author_id   TEXT,
    title       TEXT    NOT NULL,
    description TEXT    NOT NULL DEFAULT '',
    completed   INTEGER NOT NULL DEFAULT 0 CHECK (completed IN (0, 1)),
    position    REAL    NOT NULL,
    due_date    INTEGER,
    created_at  INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL,
    deleted_at  INTEGER
);

-- random UUIDv4 per row (version nibble 4, variant nibble 8..b)
INSERT INTO notes (id, title, description, completed, position, due_date, created_at, updated_at)
SELECT lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4'
         || substr(lower(hex(randomblob(2))), 2) || '-'
         || substr('89ab', 1 + (abs(random()) % 4), 1) || substr(lower(hex(randomblob(2))), 2) || '-'
         || lower(hex(randomblob(6))),
       title, description, completed, position, due_date, created_at, updated_at
FROM notes_legacy;

DROP TABLE notes_legacy;

CREATE INDEX notes_queue ON notes (deleted_at, position);

PRAGMA user_version = 1;
