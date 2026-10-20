-- Coaching style. Balanced is free; the others come with Plus. The pick is kept when Plus lapses (the coach falls back
-- to balanced meanwhile) and returns on renewal.
alter table users add column if not exists coach_style text not null default 'balanced'
  check (coach_style in ('balanced', 'strict', 'playful', 'efficient'));
