import { sql, type Tx } from '../db.js';

type Db = Tx | typeof sql;

/** The user's local calendar date (their day closes at 03:00, as on the device). */
export async function localToday(db: Db, userId: string): Promise<string> {
  const [r] = await db`select to_char((now() at time zone coalesce(home_timezone, 'UTC')) - interval '3 hours', 'YYYY-MM-DD') as d from users where id = ${userId}`;
  return r?.d ?? new Date().toISOString().slice(0, 10);
}

const prevDay = (d: string) => { const x = new Date(`${d}T00:00:00Z`); x.setUTCDate(x.getUTCDate() - 1); return x.toISOString().slice(0, 10); };

/** Consecutive days with at least one completion, ending today (or yesterday while today is still open). Same rule as the apps. */
export function streakOf(days: Set<string>, today: string): number {
  let d = days.has(today) ? today : prevDay(today), n = 0;
  while (days.has(d)) { n++; d = prevDay(d); }
  return n;
}

/** Streak on one goal, computed from the completions the server has (so a device can't claim more). */
export async function goalStreak(db: Db, userId: string, goalId: string): Promise<number> {
  const rows = await db`select distinct c.local_date::text as d from task_completions c join tasks t on t.id = c.task_id
                        where t.goal_id = ${goalId} and c.user_id = ${userId} and not c.suspect and c.local_date > current_date - 400`;
  return streakOf(new Set(rows.map(r => r.d as string)), await localToday(db, userId));
}

export const COMMUNITY_STREAK = 7;

/** Opens Community for a goal at a 7-day streak. It stays open if the streak later breaks. */
export async function communityUnlocked(db: Db, userId: string, goalId: string): Promise<{ unlocked: boolean; streak: number }> {
  const [g] = await db`select community_unlocked_at from goals where id = ${goalId} and user_id = ${userId}`;
  if (!g) return { unlocked: false, streak: 0 };
  const streak = await goalStreak(db, userId, goalId);
  if (g.community_unlocked_at) return { unlocked: true, streak };
  if (streak >= COMMUNITY_STREAK) {
    await db`update goals set community_unlocked_at = now() where id = ${goalId}`;
    return { unlocked: true, streak };
  }
  return { unlocked: false, streak };
}

/** Anyone either side of a block is invisible to the other. */
export async function blockedWith(db: Db, userId: string): Promise<string[]> {
  const rows = await db`select blocked_id as id from blocks where blocker_id = ${userId} union select blocker_id from blocks where blocked_id = ${userId}`;
  return rows.map(r => r.id as string);
}

export const firstName = (displayName: string) => displayName.trim().split(/\s+/)[0] ?? displayName;
