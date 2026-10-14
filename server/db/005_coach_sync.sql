-- Coach conversations follow the account across devices.
-- Text and suggestion are encrypted with the user's journal key (they are as personal as the journal).
create table coach_messages (
  id uuid primary key,                       -- the client's id, so the device that sent it recognises it
  user_id uuid not null references users(id) on delete cascade,
  conversation uuid not null,
  role text not null check (role in ('user', 'coach')),
  body_ciphertext bytea not null,            -- {"text": "...", "suggestion": {...} | null}
  status text not null default 'done' check (status in ('sent', 'done', 'interrupted')),
  suggestion_state text check (suggestion_state in ('pending', 'added', 'dismissed')),
  created_at timestamptz not null
);
create index on coach_messages (user_id, created_at);
