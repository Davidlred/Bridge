-- Deleting a goal (long-press on Today → Delete) first leaves its alliances; an archived alliance may still point at
-- the deleted goal as its lead, so that link clears instead of blocking the delete.
alter table alliances drop constraint if exists alliances_lead_goal_id_fkey;
alter table alliances add constraint alliances_lead_goal_id_fkey foreign key (lead_goal_id) references goals(id) on delete set null;
