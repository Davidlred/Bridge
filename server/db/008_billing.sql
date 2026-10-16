-- Milestone 6: RevenueCat webhook events are applied once (RevenueCat retries until it gets a 200).
create table billing_events (
  id text primary key,
  type text not null,
  received_at timestamptz not null default now()
);
