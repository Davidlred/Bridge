import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { sql } from '../db.js';
import { auth } from '../auth/tokens.js';
import { HttpError } from '../util.js';
import { localToday } from './progress.js';

/**
 * Weekly alliance rankings (docs/ARCHITECTURE.md → Rankings). Each part is 0..1:
 * - Productivity: tasks completed / tasks scheduled for each member's alliance goal this week.
 * - Efficiency: 60% on-time completions (done by the task's time, within 2 hours; free-text times count as on time) + 40% continuity (days with a completion).
 * - Fun: 40% ranked game nights (cap 7), 30% game participation, 20% chat (capped at 10 messages per member), 10% reactions given (capped at 5 per member).
 * Score = 100·(0.45·P + 0.35·E + 0.20·F), pulled toward this week's mean for alliances under 5 members or 3 active days.
 */
const WEIGHTS = { productivity: 0.45, efficiency: 0.35, fun: 0.2 };
const STALE_MS = 10 * 60_000;
const PRIOR_MEAN = 60, PRIOR_WEIGHT = 3;

const addDays = (d: string, n: number) => { const x = new Date(`${d}T00:00:00Z`); x.setUTCDate(x.getUTCDate() + n); return x.toISOString().slice(0, 10); };
const daysBetween = (a: string, b: string) => Math.round((Date.parse(`${b}T00:00:00Z`) - Date.parse(`${a}T00:00:00Z`)) / 864e5);
/** Monday of the week containing [d]. */
const monday = (d: string) => addDays(d, -((new Date(`${d}T00:00:00Z`).getUTCDay() + 6) % 7));
export const tier = (score: number) => (score >= 85 ? 'gold' : score >= 75 ? 'silver' : 'bronze');
export const sizeBand = (n: number) => (n <= 5 ? '2-5' : n <= 15 ? '6-15' : '16+');

/** "7:00 PM", "19:00", "7 am" → minutes after midnight; anything else (e.g. "After run") → null. */
export function parseTime(s: string | null): number | null {
  const m = s?.trim().match(/^(\d{1,2})(?::(\d{2}))?\s*([ap]\.?m\.?)?$/i);
  if (!m) return null;
  let h = Number(m[1]); const min = Number(m[2] ?? 0); const ap = m[3]?.[0]?.toLowerCase();
  if (h > 23 || min > 59 || (ap && (h < 1 || h > 12))) return null;
  if (ap === 'p' && h < 12) h += 12;
  if (ap === 'a' && h === 12) h = 0;
  return h * 60 + min;
}

interface Score { productivity: number; efficiency: number; fun: number; score: number; members: number; weekStart: string }

async function compute(allianceId: string): Promise<Score> {
  const [a] = await sql`select owner_id from alliances where id = ${allianceId}`;
  const today = await localToday(sql, a!.owner_id);
  const weekStart = monday(today);
  const members = await sql`select m.user_id, m.goal_id, greatest(m.start_date, ${weekStart}::date)::text as from_day, coalesce(u.home_timezone, 'UTC') as tz
                            from alliance_members m join users u on u.id = m.user_id where m.alliance_id = ${allianceId} and m.status <> 'left' and u.deleted_at is null`;
  const n = members.length || 1;
  let scheduled = 0, completed = 0, onTime = 0, continuity = 0;
  const activeDays = new Set<string>();
  for (const m of members) {
    const days = Math.max(1, daysBetween(m.from_day, today) + 1);
    const [{ tasks }] = await sql`select count(*)::int as tasks from tasks where goal_id = ${m.goal_id} and deleted_at is null` as unknown as [{ tasks: number }];
    scheduled += tasks * days;
    const done = await sql`select c.local_date::text as d, t.time_of_day, extract(hour from c.completed_at at time zone ${m.tz})::int * 60 + extract(minute from c.completed_at at time zone ${m.tz})::int as at
                           from task_completions c join tasks t on t.id = c.task_id where t.goal_id = ${m.goal_id} and c.local_date between ${m.from_day} and ${today}`;
    completed += done.length;
    for (const c of done) {
      activeDays.add(c.d);
      const due = parseTime(c.time_of_day);
      if (due === null || c.at <= due + 120) onTime++;
    }
    continuity += new Set(done.map(c => c.d)).size / days;
  }
  const productivity = scheduled ? Math.min(1, completed / scheduled) : 0;
  const efficiency = completed ? 0.6 * (onTime / completed) + 0.4 * (continuity / n) : 0.4 * (continuity / n);
  const [g] = await sql`select count(*)::int as games, coalesce(avg((select count(*) from game_players p where p.game_id = s.id and not p.absent)::real / ${n}), 0)::real as participation
                        from game_sessions s where s.alliance_id = ${allianceId} and s.ranked and s.local_date between ${weekStart} and ${today}`;
  const [chat] = await sql`select count(*)::int as n from alliance_messages where alliance_id = ${allianceId} and user_id is not null and created_at >= ${weekStart}::date`;
  const [rx] = await sql`select count(*)::int as n from reactions r join alliance_members m on m.user_id = r.user_id and m.alliance_id = ${allianceId} and m.status <> 'left' where r.created_at >= ${weekStart}::date`;
  const fun = 0.4 * Math.min(1, g!.games / 7) + 0.3 * Math.min(1, g!.participation) + 0.2 * Math.min(1, chat!.n / (n * 10)) + 0.1 * Math.min(1, rx!.n / (n * 5));
  let score = 100 * (WEIGHTS.productivity * productivity + WEIGHTS.efficiency * efficiency + WEIGHTS.fun * fun);
  // Small or brand-new alliances can't top the board by luck.
  if (members.length < 5 || activeDays.size < 3) {
    const [mean] = await sql`select avg(score)::real as m from alliance_scores_weekly where week_start = ${weekStart} and members >= 5`;
    const prior = mean?.m ?? PRIOR_MEAN;
    score = (members.length * score + PRIOR_WEIGHT * prior) / (members.length + PRIOR_WEIGHT);
  }
  const s = { productivity, efficiency, fun, score: Math.round(score * 10) / 10, members: members.length, weekStart };
  await sql`insert into alliance_scores_weekly (alliance_id, week_start, productivity, efficiency, fun, score, members, computed_at)
            values (${allianceId}, ${weekStart}, ${productivity}, ${efficiency}, ${fun}, ${s.score}, ${s.members}, now())
            on conflict (alliance_id, week_start) do update set productivity = excluded.productivity, efficiency = excluded.efficiency, fun = excluded.fun,
              score = excluded.score, members = excluded.members, computed_at = now()`;
  return s;
}

/** This week's score, recomputed when older than ten minutes. */
export async function scoreOf(allianceId: string): Promise<Score> {
  const [row] = await sql`select productivity, efficiency, fun, score, members, week_start::text as week_start, computed_at from alliance_scores_weekly
                          where alliance_id = ${allianceId} order by week_start desc limit 1`;
  if (row && Date.now() - (row.computed_at as Date).getTime() < STALE_MS) {
    const [a] = await sql`select owner_id from alliances where id = ${allianceId}`;
    if (row.week_start === monday(await localToday(sql, a!.owner_id)))
      return { productivity: row.productivity, efficiency: row.efficiency, fun: row.fun, score: row.score, members: row.members, weekStart: row.week_start };
  }
  return compute(allianceId);
}

/** Alliances on the same board: same goal category and size band. */
async function board(allianceId: string) {
  const [a] = await sql`select category from alliances where id = ${allianceId}`;
  const mine = await scoreOf(allianceId);
  const peers = await sql`select a.id, a.name, (select count(*)::int from alliance_members m where m.alliance_id = a.id and m.status <> 'left') as members
                          from alliances a where a.archived_at is null and coalesce(a.category, 'other') = ${a!.category ?? 'other'}`;
  const band = sizeBand(mine.members);
  const rows = [] as { id: string; name: string; score: number; members: number }[];
  for (const p of peers.filter(p => sizeBand(p.members) === band)) rows.push({ id: p.id, name: p.name, members: p.members, score: (await scoreOf(p.id)).score });
  rows.sort((x, y) => y.score - x.score);
  return { band, category: a!.category ?? 'other', weekStart: mine.weekStart, mine, rows };
}

export async function rankingRoutes(app: FastifyInstance) {
  const member = async (allianceId: string, userId: string) => {
    if (!z.string().uuid().safeParse(allianceId).success) throw new HttpError(404, 'not_found');
    const [m] = await sql`select 1 from alliance_members where alliance_id = ${allianceId} and user_id = ${userId} and status <> 'left'`;
    if (!m) throw new HttpError(404, 'not_found');
  };
  const pct = (v: number) => Math.round(v * 100);

  /** The rating card: overall, tier, rank on its board, and the three parts. */
  app.get<{ Params: { id: string } }>('/v1/alliances/:id/rating', { onRequest: auth }, async req => {
    await member(req.params.id, req.userId);
    const b = await board(req.params.id);
    const s = b.mine;
    return {
      overall: Math.round(s.score), tier: tier(s.score), rank: b.rows.findIndex(r => r.id === req.params.id) + 1, of: b.rows.length, band: b.band, weekStart: b.weekStart,
      productivity: pct(s.productivity), efficiency: pct(s.efficiency), fun: pct(s.fun),
    };
  });

  /** The board: top 20 in this category and size band, plus where this alliance stands. */
  app.get<{ Params: { id: string } }>('/v1/alliances/:id/rankings', { onRequest: auth }, async req => {
    await member(req.params.id, req.userId);
    const b = await board(req.params.id);
    const rank = b.rows.findIndex(r => r.id === req.params.id) + 1;
    const top = b.rows.slice(0, 20).map((r, i) => ({ rank: i + 1, name: r.name, score: Math.round(r.score), tier: tier(r.score), members: r.members, mine: r.id === req.params.id }));
    return { category: b.category, band: b.band, weekStart: b.weekStart, rank, of: b.rows.length, rows: top };
  });
}
