-- Chaos Note Viewer local schema, version 1.
-- Canonical for every client (desktop, android, ...).
-- All timestamps are milliseconds since the Unix epoch (UTC) unless noted.

CREATE TABLE notes (
    -- UUIDv4 string generated on the client, so devices never collide when syncing
    id          TEXT    PRIMARY KEY NOT NULL,
    -- account id (Firebase uid) of whoever created the note, never changes afterwards;
    -- NULL = created locally before signing in, claimed by the account on first sign-in
    author_id   TEXT,
    title       TEXT    NOT NULL,
    description TEXT    NOT NULL DEFAULT '',
    completed   INTEGER NOT NULL DEFAULT 0 CHECK (completed IN (0, 1)),
    -- queue order, lower = higher priority; REAL so a note can be inserted between two neighbours
    position    REAL    NOT NULL,
    -- ms epoch of local midnight of the deadline day, NULL = no deadline
    due_date    INTEGER,
    created_at  INTEGER NOT NULL,
    -- bumped on every change, last-write-wins tiebreaker for sync
    updated_at  INTEGER NOT NULL,
    -- NULL = alive. Set when the user deletes the note: the row is hidden and kept only until the server
    -- confirmed the delete (or answered not found), then removed for good. There are no tombstones in the cloud.
    deleted_at  INTEGER
);

CREATE INDEX notes_queue ON notes (deleted_at, position);

PRAGMA user_version = 1;
