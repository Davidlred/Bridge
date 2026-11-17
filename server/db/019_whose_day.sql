-- Game Night #2, "Whose Day?": guess whose finished list Arc is showing.

-- Private tasks and goals: their names never leave the owner's devices in games (shown as "Private task").
alter table tasks add column if not exists private boolean not null default false;
alter table goals add column if not exists private boolean not null default false;

-- Both games share sessions and players. Whose Day? stores each player's points; the winner is the top scorer.
alter table game_sessions add column if not exists kind text not null default 'drifting' check (kind in ('drifting', 'whose_day'));
alter table game_sessions add column if not exists winner_id uuid references users(id);
alter table game_players drop constraint if exists game_players_role_check;
alter table game_players add constraint game_players_role_check check (role in ('builder', 'drifter', 'player'));
alter table game_players add column if not exists score int not null default 0;
