-- Each goal has its own coach conversation: the goal the message was about. Null for messages from before this (and when
-- the goal is deleted); the apps show those in the oldest goal's thread.
alter table coach_messages add column if not exists goal_id uuid references goals(id) on delete set null;
create index if not exists coach_messages_goal on coach_messages (user_id, goal_id, created_at);
