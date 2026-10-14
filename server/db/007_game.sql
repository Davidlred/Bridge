-- Milestone 5: Game Night, rankings, Hall of Fame.

-- "Game night is open" is announced once per alliance per day; "Nudge" reaches each member once per day.
create table game_unlocks (
  alliance_id uuid not null references alliances(id) on delete cascade,
  local_date date not null,
  primary key (alliance_id, local_date)
);
create table game_nudges (
  alliance_id uuid not null references alliances(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  local_date date not null,
  primary key (alliance_id, user_id, local_date)
);

-- Weekly scores are recomputed when they're more than a few minutes old.
alter table alliance_scores_weekly add column computed_at timestamptz not null default now();
alter table alliance_scores_weekly add column members int not null default 0;
create index on alliance_scores_weekly (week_start, score desc);

-- Badges stay earned even if the streak behind them later breaks.
create table badges (
  user_id uuid not null references users(id) on delete cascade,
  badge text not null,
  earned_at timestamptz not null default now(),
  primary key (user_id, badge)
);
