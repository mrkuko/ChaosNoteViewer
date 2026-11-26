-- Tags on notes, version 4. Canonical for every client.

-- The note's tags as a JSON array of strings, e.g. ["work","home"]. A tag is 1 to 15 printable ASCII
-- characters (space to ~) with no space at either end, and a note has each tag once (compared without case).
-- Synced: it is part of the encrypted note content (field "tags", an array of strings; a note from a client
-- that doesn't know it has no tags). There is no list of tags anywhere else: the known tags are the ones
-- the notes carry.
ALTER TABLE notes ADD COLUMN tags TEXT NOT NULL DEFAULT '[]';

PRAGMA user_version = 4;
