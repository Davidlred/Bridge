-- Game Night Arcade: 1v1 brackets, matches (live or turn by turn), challenge cards in the alliance chat, and the
-- arcade games in the shared session history (the Fun ranking counts them like the first two games).

alter table game_sessions drop constraint if exists game_sessions_kind_check;
alter table game_sessions add constraint game_sessions_kind_check check (kind in ('drifting', 'whose_day', 'word', 'scramble', 'cup', 'pool', 'hoops', 'darts', 'golf', 'four', 'fleet', 'push'));

-- A knockout bracket for one Game Night. Rounds are made one at a time: an odd round gives one member a bye, and the
-- bye meets the winner of a named match in the next round. Unplayed matches are settled at the deadline (midnight).
create table game_brackets (
  id uuid primary key,
  alliance_id uuid not null references alliances(id) on delete cascade,
  game text not null,
  local_date date not null,
  deadline timestamptz not null,
  round int not null default 1,
  rounds int not null,
  votes int not null default 0,
  voters int not null default 0,
  -- [{round, userId, vsMatch}] : who sat out which round, and whose winner they meet.
  byes jsonb not null default '[]',
  champion_id uuid references users(id) on delete set null,
  status text not null default 'active' check (status in ('active', 'done')),
  created_at timestamptz not null default now(),
  ended_at timestamptz
);
create index on game_brackets (alliance_id, created_at desc);

create table game_bracket_players (
  bracket_id uuid not null references game_brackets(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  seed int not null,
  primary key (bracket_id, user_id)
);

-- One 1v1 match. The game module owns `state` (validated on the server); `turn` is whose move it is (null while both
-- may move, or when it's over). Moves are counted per player for the deadline rule.
create table game_matches (
  id uuid primary key,
  alliance_id uuid not null references alliances(id) on delete cascade,
  game text not null,
  source text not null check (source in ('game_night', 'chat_challenge')),
  bracket_id uuid references game_brackets(id) on delete cascade,
  round int,
  slot int,
  player_a uuid not null references users(id) on delete cascade,
  player_b uuid not null references users(id) on delete cascade,
  mode text not null default 'duel_turns' check (mode in ('duel_live', 'duel_turns')),
  seed text not null,
  state jsonb not null,
  turn uuid,
  moves_a int not null default 0,
  moves_b int not null default 0,
  status text not null default 'waiting' check (status in ('waiting', 'active', 'done')),
  winner_id uuid references users(id) on delete set null,
  score_a int not null default 0,
  score_b int not null default 0,
  result text check (result in ('played', 'resigned', 'claimed', 'deadline', 'left')),
  chat_message_id uuid,
  version int not null default 0,
  last_move_at timestamptz not null default now(),
  nudged_at timestamptz,
  -- "Your turn" waits until this player has finished today's tasks (a chat challenge sent before they were done).
  held_for uuid,
  created_at timestamptz not null default now(),
  ended_at timestamptz
);
create index on game_matches (bracket_id, round, slot);
create index on game_matches (player_a) where status <> 'done';
create index on game_matches (player_b) where status <> 'done';
create index on game_matches (status, last_move_at) where status <> 'done';

-- The challenge card in the alliance chat points at its match; the card is drawn from the match's current state.
alter table alliance_messages drop constraint if exists alliance_messages_kind_check;
alter table alliance_messages add constraint alliance_messages_kind_check check (kind in ('text', 'system', 'game', 'media', 'game_challenge'));
alter table alliance_messages add column if not exists match_id uuid references game_matches(id) on delete set null;
