import type { FastifyInstance } from 'fastify';
import { sql } from '../db.js';
import { auth } from '../auth/tokens.js';
import { localToday, streakOf } from './progress.js';

/** Hall of Fame. Badges are worked out from the user's own records and kept once earned. */
export const BADGES = ['first', 's7', 's21', 'arch', 's30', 'week', 'ref', 'ally', 'goal'] as const;
type Badge = (typeof BADGES)[number];
const STREAK_GOALS: Partial<Record<Badge, number>> = { s7: 7, s21: 21, s30: 30 };

const prevDay = (d: string) => { const x = new Date(`${d}T00:00:00Z`); x.setUTCDate(x.getUTCDate() - 1); return x.toISOString().slice(0, 10); };

/** Longest run of consecutive days in [days]. */
export function longestRun(days: Iterable<string>): number {
  const set = new Set(days);
  let best = 0;
  for (const d of set) {
    if (set.has(prevDay(d))) continue;                       // only count from the start of each run
    let n = 0, x = d;
    while (set.has(x)) { n++; const y = new Date(`${x}T00:00:00Z`); y.setUTCDate(y.getUTCDate() + 1); x = y.toISOString().slice(0, 10); }
    best = Math.max(best, n);
  }
  return best;
}

async function evaluate(userId: string) {
  const goals = await sql`select id from goals where user_id = ${userId}`;
  let bestStreak = 0, perfectRun = 0, current = 0;
  const today = await localToday(sql, userId);
  for (const g of goals) {
    const rows = await sql`select c.local_date::text as d, count(*)::int as n from task_completions c join tasks t on t.id = c.task_id
                           where t.goal_id = ${g.id} and c.user_id = ${userId} group by 1`;
    const [{ tasks }] = await sql`select count(*)::int as tasks from tasks where goal_id = ${g.id} and deleted_at is null` as unknown as [{ tasks: number }];
    const days = rows.map(r => r.d as string);
    bestStreak = Math.max(bestStreak, longestRun(days));
    current = Math.max(current, streakOf(new Set(days), today));
    // Perfect week: every task done, seven days running (judged against today's task count).
    if (tasks > 0) perfectRun = Math.max(perfectRun, longestRun(rows.filter(r => r.n >= tasks).map(r => r.d as string)));
  }
  const [f] = await sql`
    select exists(select 1 from task_completions where user_id = ${userId}) as first,
           exists(select 1 from tasks where user_id = ${userId} and origin = 'ai' and updated_at > to_timestamp(('x' || substr(replace(id::text, '-', ''), 1, 12))::bit(48)::bigint / 1000.0) + interval '1 minute') as arch,
           (select count(*)::int from journal_entries where user_id = ${userId} and conflict_of is null and (body_length > 0 or mood is not null)) as journal,
           exists(select 1 from alliances where owner_id = ${userId}) as ally,
           exists(select 1 from goals where user_id = ${userId} and status = 'completed') as goal`;
  const earned: Record<Badge, boolean> = {
    first: f!.first, s7: bestStreak >= 7, s21: bestStreak >= 21, arch: f!.arch, s30: bestStreak >= 30, week: perfectRun >= 7,
    ref: f!.journal >= 30, ally: f!.ally, goal: f!.goal,
  };
  return { earned, current, journal: f!.journal as number };
}

export async function badgeRoutes(app: FastifyInstance) {
  app.get('/v1/me/badges', { onRequest: auth }, async req => {
    const { earned, current, journal } = await evaluate(req.userId);
    for (const b of BADGES) if (earned[b]) await sql`insert into badges (user_id, badge) values (${req.userId}, ${b}) on conflict do nothing`;
    const rows = await sql`select badge, earned_at from badges where user_id = ${req.userId}`;
    const at = new Map(rows.map(r => [r.badge as string, (r.earned_at as Date).getTime()]));
    const [u] = await sql`select settings from users where id = ${req.userId}`;
    return {
      public: (u!.settings as Record<string, unknown>)?.badgesPublic === true,
      badges: BADGES.map(k => ({
        key: k, earned: at.has(k), earnedAt: at.get(k) ?? null,
        // Progress toward the streak badges and the journal one, so locked badges say how far there is to go.
        progress: STREAK_GOALS[k] ? Math.min(current, STREAK_GOALS[k]!) : k === 'ref' ? Math.min(journal, 30) : null,
        goal: STREAK_GOALS[k] ?? (k === 'ref' ? 30 : null),
      })),
    };
  });
}
