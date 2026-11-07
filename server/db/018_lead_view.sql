-- Leads of a shared plan see each member's progress on that plan (Students view). Members agree when they join
-- and can stop sharing at any time. Members who joined before this existed start as not sharing.
alter table alliance_members add column shares_progress boolean not null default false;

-- What members log after ticking a task, in the unit the lead picked for it.
alter table tasks add column measure text check (measure in ('reps', 'kg', 'min', 'km', 'pages'));
alter table shared_plan_tasks add column measure text;

-- A check-in on one task for one local day: how it felt, an amount and a short note (sealed with the user's key).
create table checkins (
  task_id uuid not null references tasks(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  local_date date not null,
  feel smallint check (feel between 1 and 5),
  amount numeric(8, 1) check (amount >= 0 and amount < 100000),
  note_ciphertext bytea,
  updated_at timestamptz not null default now(),
  primary key (task_id, local_date)
);
create index on checkins (user_id, local_date);

-- A lead nudges one member at most once a day.
create table lead_nudges (
  alliance_id uuid not null references alliances(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  local_date date not null,
  primary key (alliance_id, user_id, local_date)
);
