-- The Bridge: initial schema (Postgres 16)
create extension if not exists citext;

create type plan_mode as enum ('own', 'shared');
create type member_role as enum ('owner', 'member');
create type member_status as enum ('active', 'away', 'left');
create type task_origin as enum ('ai', 'user', 'shared');
create type priority as enum ('High', 'Medium', 'Low');
create type goal_status as enum ('active', 'archived', 'completed');

create table users (
  id uuid primary key,
  email citext unique,
  email_verified boolean not null default false,
  password_hash text,
  display_name text not null check (length(display_name) between 1 and 40),
  birth_year int,
  country char(2),
  home_timezone text not null default 'UTC',
  data_region text not null default 'us',
  coach_name text not null default 'Arc',
  settings jsonb not null default '{}',
  created_at timestamptz not null default now(),
  deleted_at timestamptz
);

create table auth_identities (
  provider text not null check (provider in ('apple', 'google', 'email')),
  subject text not null,
  user_id uuid not null references users(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (provider, subject)
);

create table sessions (
  id uuid primary key,
  user_id uuid not null references users(id) on delete cascade,
  family uuid not null,
  refresh_hash bytea not null,
  device_id uuid,
  expires_at timestamptz not null,
  revoked_at timestamptz,
  created_at timestamptz not null default now()
);
create index on sessions (user_id) where revoked_at is null;

create table devices (
  id uuid primary key,
  user_id uuid not null references users(id) on delete cascade,
  platform text not null check (platform in ('ios', 'android')),
  push_token text,
  app_version text,
  locale text,
  timezone text,
  last_seen_at timestamptz not null default now()
);

create table goals (
  id uuid primary key,
  user_id uuid not null references users(id) on delete cascade,
  label text not null,
  statement text not null,
  category text,
  start_date date not null,
  deadline date not null,
  status goal_status not null default 'active',
  source_alliance_id uuid,
  feasibility jsonb,
  cover_url text,
  updated_at timestamptz not null default now(),
  check (deadline > start_date)
);
create index on goals (user_id) where status = 'active';

create table tasks (
  id uuid primary key,
  goal_id uuid not null references goals(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  title text not null check (length(title) between 1 and 120),
  priority priority not null default 'Medium',
  time_of_day text,
  minutes int,
  rationale text,
  notes text,
  origin task_origin not null default 'user',
  shared_task_id uuid,
  position int not null default 0,
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);
create index on tasks (goal_id) where deleted_at is null;

create table task_completions (
  task_id uuid not null references tasks(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  local_date date not null,
  completed_at timestamptz not null,
  received_at timestamptz not null default now(),
  primary key (task_id, local_date)
);
create index on task_completions (user_id, local_date);

create table journal_entries (
  id uuid primary key,
  user_id uuid not null references users(id) on delete cascade,
  goal_id uuid references goals(id) on delete set null,
  local_date date not null,
  mood smallint check (mood between 1 and 5),
  body_ciphertext bytea,
  version int not null default 1,
  conflict_of uuid references journal_entries(id),
  updated_at timestamptz not null default now()
);
create unique index on journal_entries (user_id, goal_id, local_date) where conflict_of is null;

create table alliances (
  id uuid primary key,
  owner_id uuid not null references users(id),
  name text not null check (length(name) between 2 and 40),
  category text,
  plan_mode plan_mode not null default 'own',
  lead_goal_id uuid references goals(id),
  start_date date not null,
  cap int not null default 15 check (cap between 2 and 50),
  invite_code text not null unique,
  created_at timestamptz not null default now(),
  archived_at timestamptz
);

create table alliance_members (
  alliance_id uuid not null references alliances(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  goal_id uuid not null references goals(id) on delete cascade,
  role member_role not null default 'member',
  status member_status not null default 'active',
  joined_at timestamptz not null default now(),
  start_date date not null,
  primary key (alliance_id, user_id)
);
create index on alliance_members (user_id) where status <> 'left';

create table shared_plan_tasks (
  id uuid primary key,
  alliance_id uuid not null references alliances(id) on delete cascade,
  version int not null,
  title text not null,
  priority priority not null,
  time_of_day text,
  minutes int,
  rationale text,
  position int not null,
  effective_from date not null,
  deleted_at timestamptz
);
create index on shared_plan_tasks (alliance_id, version);

create table alliance_messages (
  id uuid primary key,
  alliance_id uuid not null references alliances(id) on delete cascade,
  user_id uuid references users(id) on delete set null,
  kind text not null default 'text' check (kind in ('text', 'system', 'game', 'media')),
  body text,
  media_url text,
  moderation text not null default 'pending' check (moderation in ('pending', 'allow', 'hidden', 'blocked')),
  created_at timestamptz not null default now()
);
create index on alliance_messages (alliance_id, created_at desc);

create table community_posts (
  id uuid primary key,
  user_id uuid not null references users(id) on delete cascade,
  category text not null,
  kind text not null check (kind in ('streak', 'milestone', 'text')),
  body text,
  moderation text not null default 'pending',
  created_at timestamptz not null default now()
);
create index on community_posts (category, created_at desc) where moderation = 'allow';

create table reports (
  id uuid primary key,
  reporter_id uuid not null references users(id),
  target_kind text not null,
  target_id uuid not null,
  reason text not null,
  evidence jsonb,
  status text not null default 'open',
  created_at timestamptz not null default now()
);

create table blocks (
  blocker_id uuid not null references users(id) on delete cascade,
  blocked_id uuid not null references users(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (blocker_id, blocked_id)
);

create table game_sessions (
  id uuid primary key,
  alliance_id uuid not null references alliances(id) on delete cascade,
  local_date date not null,
  ranked boolean not null default true,
  drifter_id uuid references users(id),
  ejected_id uuid references users(id),
  builders_won boolean,
  started_at timestamptz not null default now(),
  ended_at timestamptz
);
create unique index on game_sessions (alliance_id, local_date) where ranked;

create table game_players (
  game_id uuid not null references game_sessions(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  role text not null check (role in ('builder', 'drifter')),
  actions int not null default 0,
  vote_for uuid,
  absent boolean not null default false,
  primary key (game_id, user_id)
);

create table alliance_scores_weekly (
  alliance_id uuid not null references alliances(id) on delete cascade,
  week_start date not null,
  productivity real not null,
  efficiency real not null,
  fun real not null,
  score real not null,
  primary key (alliance_id, week_start)
);

create table entitlements (
  user_id uuid not null references users(id) on delete cascade,
  entitlement text not null check (entitlement in ('plus', 'alliance_plus')),
  expires_at timestamptz,
  store text,
  updated_at timestamptz not null default now(),
  primary key (user_id, entitlement)
);

-- E2E messaging: server stores public material and opaque ciphertext only
create table signal_identity_keys (
  user_id uuid not null references users(id) on delete cascade,
  device_id uuid not null references devices(id) on delete cascade,
  identity_key bytea not null,
  registration_id int not null,
  primary key (user_id, device_id)
);
create table signal_prekeys (
  device_id uuid not null references devices(id) on delete cascade,
  key_id int not null,
  kind text not null check (kind in ('signed', 'one_time', 'kyber')),
  public_key bytea not null,
  signature bytea,
  primary key (device_id, kind, key_id)
);
create table dm_envelopes (
  id uuid primary key,
  recipient_device_id uuid not null references devices(id) on delete cascade,
  sender_user_id uuid not null references users(id) on delete cascade,
  ciphertext bytea not null,
  created_at timestamptz not null default now()
);
create index on dm_envelopes (recipient_device_id, created_at);

-- Sync: per-user change log
create table changes (
  server_seq bigserial primary key,
  user_id uuid not null references users(id) on delete cascade,
  entity text not null,
  entity_id uuid not null,
  op text not null check (op in ('upsert', 'delete')),
  created_at timestamptz not null default now()
);
create index on changes (user_id, server_seq);

create table processed_mutations (
  id uuid primary key,
  user_id uuid not null,
  processed_at timestamptz not null default now()
);

create table ai_usage (
  user_id uuid not null references users(id) on delete cascade,
  day date not null,
  kind text not null,
  count int not null default 0,
  primary key (user_id, day, kind)
);
