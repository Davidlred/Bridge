-- Milestone 1: auth hardening, password reset / email verification, Apple web hand-off, sync bookkeeping

-- Wrong password x5 -> 15-minute cooldown
alter table users add column failed_logins int not null default 0;
alter table users add column locked_until timestamptz;

-- Single-use links sent by email (password reset: 15 min, email verification: 24 h)
create table email_tokens (
  token_hash bytea primary key,
  user_id uuid not null references users(id) on delete cascade,
  kind text not null check (kind in ('reset', 'verify')),
  expires_at timestamptz not null,
  used_at timestamptz
);
create index on email_tokens (user_id, kind);

-- Sign in with Apple on Android runs in a browser tab; the result is handed back to the app with a one-time code (PKCE-bound)
create table auth_handoffs (
  state text primary key,
  nonce text not null,
  challenge text not null,
  code_hash bytea unique,
  user_id uuid references users(id) on delete cascade,
  expires_at timestamptz not null
);

-- Goal card colour is picked on device and must survive a reinstall
alter table goals add column color_index smallint not null default 0;

-- Completions are keyed by (task, local_date); the change log needs the date to address them
alter table changes add column key text;

-- Replay the original outcome when a client retries a mutation
alter table processed_mutations add column result text;
create index on processed_mutations (processed_at);
