-- E2E direct messages with Olm (vodozemac). The server keeps public keys and opaque ciphertext only.
-- The placeholder Signal tables from 001 were never used.
drop table if exists dm_envelopes;
drop table if exists signal_prekeys;
drop table if exists signal_identity_keys;

-- One Olm account per app install. A reinstall is a new device with new identity keys.
create table e2e_devices (
  device_id uuid primary key,
  user_id uuid not null references users(id) on delete cascade,
  curve25519 text not null,
  ed25519 text not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index on e2e_devices (user_id);

-- Published one-time keys, each handed out once; the fallback key is reused when they run out.
create table e2e_one_time_keys (
  device_id uuid not null references e2e_devices(device_id) on delete cascade,
  key_id text not null,
  curve25519 text not null,
  fallback boolean not null default false,
  primary key (device_id, key_id)
);

-- Ciphertext waiting for a device. Deleted when that device acknowledges it, or after 30 days.
create table e2e_envelopes (
  id uuid primary key,
  recipient_device uuid not null references e2e_devices(device_id) on delete cascade,
  recipient_user uuid not null references users(id) on delete cascade,
  sender_user uuid not null references users(id) on delete cascade,
  sender_device uuid not null,
  -- Who the conversation is with, seen from the device that receives it (the sender, or for the sender's own devices, the recipient).
  peer_user uuid not null references users(id) on delete cascade,
  client_id uuid not null,
  msg_type smallint not null check (msg_type in (0, 1)),   -- Olm: 0 = pre-key message, 1 = normal
  body text not null,
  created_at timestamptz not null default now()
);
create index on e2e_envelopes (recipient_device, created_at);
create unique index on e2e_envelopes (recipient_device, client_id);
