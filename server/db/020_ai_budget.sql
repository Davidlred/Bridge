-- A ceiling on AI calls across everyone per day (AI_DAILY_BUDGET), so a test group can't run up the Replicate bill.
create table if not exists ai_budget (
  day date primary key,
  calls int not null default 0
);
