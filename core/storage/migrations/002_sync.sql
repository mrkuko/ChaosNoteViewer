-- Sync bookkeeping, version 2. Canonical for every client.

-- 1 = changed locally and not pushed yet (the outbox)
ALTER TABLE notes ADD COLUMN dirty INTEGER NOT NULL DEFAULT 0 CHECK (dirty IN (0, 1));
-- Firestore serverUpdatedAt (ms epoch) of the version last pulled/pushed, NULL = never synced
ALTER TABLE notes ADD COLUMN remote_updated_at INTEGER;

CREATE INDEX notes_dirty ON notes (dirty) WHERE dirty = 1;

-- key/value store for sync state:
--   uid           Firebase uid this database is bound to
--   cursor_ts     serverUpdatedAt of the last pulled document, exactly as Firestore sent it
--                 (RFC 3339 with microseconds, as text: rounding to ms would re-fetch that document forever)
--   cursor_id     document id of the last pulled document
CREATE TABLE sync_state (
    key   TEXT PRIMARY KEY NOT NULL,
    value TEXT NOT NULL
);

PRAGMA user_version = 2;
