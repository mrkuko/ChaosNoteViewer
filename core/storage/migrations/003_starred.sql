-- Starred notes, version 3. Canonical for every client.

-- 1 = the user marked the note as important (a star). Synced: it is part of the encrypted note content
-- (field "starred", a boolean; a note from a client that doesn't know it counts as not starred).
ALTER TABLE notes ADD COLUMN starred INTEGER NOT NULL DEFAULT 0 CHECK (starred IN (0, 1));

PRAGMA user_version = 3;
