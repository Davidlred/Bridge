-- Milestone 4: Community, alliances, shared plans, alliance chat, push devices

-- Community opens per goal at a 7-day streak and stays open after that.
alter table goals add column community_unlocked_at timestamptz;

-- Feed posts are generated from progress (no free text), so they carry the goal's short name and a number.
alter table community_posts add column goal_id uuid references goals(id) on delete cascade;
alter table community_posts add column label text;
alter table community_posts add column value int;
alter table community_posts add column local_date date;
create unique index on community_posts (goal_id, kind, value) where kind in ('streak', 'milestone');
create unique index on community_posts (goal_id, kind, local_date) where kind = 'day_complete';
alter table community_posts drop constraint community_posts_kind_check;
alter table community_posts add constraint community_posts_kind_check check (kind in ('streak', 'milestone', 'day_complete', 'text'));
alter table community_posts alter column moderation set default 'allow';

create table reactions (
  post_id uuid not null references community_posts(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  kind text not null check (kind in ('respect', 'keep_going', 'inspired')),
  created_at timestamptz not null default now(),
  primary key (post_id, user_id, kind)
);

create unique index on reports (reporter_id, target_kind, target_id);

-- Shared plans: each version row remembers which of the lead's tasks it came from, so members' copies can be updated in place.
alter table shared_plan_tasks add column source_task_id uuid;
alter table alliance_members add column applied_version int not null default 0;

alter table alliance_messages alter column moderation set default 'allow';
alter table alliance_messages add column client_id uuid;
create unique index on alliance_messages (alliance_id, client_id) where client_id is not null;

alter table devices add column updated_at timestamptz not null default now();
