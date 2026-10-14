-- Milestone 2: routine + "why I started" on goals, user photo with consent, AI goal images

alter table goals add column routine text;
alter table goals add column reality_note text;

-- Front-facing photo used to personalise goal images. Consent is recorded when it is uploaded.
alter table users add column photo_key text;
alter table users add column photo_consent_at timestamptz;

-- "Future Self" and "If nothing changes" images. Keyed by the client-generated goal id, because they are
-- generated during onboarding before the goal row is first synced.
create table goal_images (
  goal_id uuid not null,
  user_id uuid not null references users(id) on delete cascade,
  kind text not null check (kind in ('future', 'reality')),
  status text not null default 'running' check (status in ('running', 'done', 'failed')),
  storage_key text,
  with_face boolean not null,
  error text,
  updated_at timestamptz not null default now(),
  primary key (goal_id, kind)
);
create index on goal_images (user_id);
