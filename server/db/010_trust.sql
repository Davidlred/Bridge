-- Clock tampering: a completion whose local_date is more than a day from the server's idea of that user's day when it
-- arrived is kept (it's still the user's own record) but doesn't count toward anything other people see:
-- Community streaks, alliance streaks, public badges and rankings.
alter table task_completions add column suspect boolean not null default false;
