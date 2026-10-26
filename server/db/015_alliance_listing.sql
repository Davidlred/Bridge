-- Findable alliances: the lead can list one so others (and the coach) can find it; private (code-only) stays the default.
-- "about" is the lead's one-line description, shown in suggestions.
alter table alliances add column if not exists listed boolean not null default false;
alter table alliances add column if not exists about text check (about is null or length(about) <= 140);
create index if not exists alliances_listed on alliances (category) where listed and archived_at is null;
