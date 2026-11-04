-- Date of birth, asked at sign-up: the app is for 12 and over, and accounts under 18 get extra protections
-- (no direct messages with adults, not findable by strangers, no AI images from their photo). Null for accounts made
-- before this; they are treated as adults.
alter table users add column if not exists birth_date date;
