-- Milestone 3: journal encryption keys, journal conflicts

-- Per-user data key for journal bodies, stored wrapped with the server key (JOURNAL_KEK). Deleting it makes the bodies unreadable.
alter table users add column journal_dek bytea;

-- A conflict copy keeps the other device's version until the user picks one. Conflict copies are never "current".
alter table journal_entries add column body_length int not null default 0;
create index on journal_entries (conflict_of) where conflict_of is not null;
