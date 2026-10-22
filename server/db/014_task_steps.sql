-- Steps: one-time tasks for a given local day (install this, read these chapters), next to the daily habits (on_date null).
-- Resources: up to three links per task or step, [{ "title", "url" }], checked by the server when the AI writes them.
alter table tasks add column if not exists on_date date;
alter table tasks add column if not exists resources jsonb not null default '[]'::jsonb;
