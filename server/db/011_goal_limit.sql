-- Plus ended with more than three active goals: the extras pause. Paused goals keep their history and are read-only
-- until the user picks them as one of their three, or has Plus again (docs/EDGE_CASES.md → Payments).
alter type goal_status add value if not exists 'paused';
