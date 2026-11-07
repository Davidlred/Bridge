import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { sql } from '../db.js';
import { auth } from '../auth/tokens.js';
import { HttpError } from '../util.js';
import { decryptText, journalKey } from '../crypto.js';
import { push } from '../push.js';
import { blockedWith, firstName, localToday, streakOf } from './progress.js';

/**
 * Students view: the lead of a shared plan (a teacher, a coach) follows each member's progress on that plan.
 * Only the plan's tasks and their check-ins, only for members who agreed to share, never their other goals.
 */

const WEEK = 7;
const addDays = (d: string, n: number) => { const x = new Date(`${d}T00:00:00Z`); x.setUTCDate(x.getUTCDate() + n); return x.toISOString().slice(0, 10); };
const daysBetween = (a: string, b: string) => Math.round((Date.parse(`${b}T00:00:00Z`) - Date.parse(`${a}T00:00:00Z`)) / 864e5);
const avg = (xs: number[]) => (xs.length ? Math.round((xs.reduce((s, x) => s + x, 0) / xs.length) * 10) / 10 : null);

type Member = { user_id: string; goal_id: string; start_date: string; display_name: string; shares_progress: boolean; finished: boolean };
type Attention = 'away' | 'low_feel' | 'behind' | null;
type Student = {
  userId: string; name: string; sharing: boolean; finished: boolean; startDate: string;
  progress: { streak: number; lastActive: string | null; week: { done: number; due: number }; today: { done: number; total: number }; feel: number | null; attention: Attention; nudgedToday: boolean } | null;
};

/** The alliance, if [userId] leads it and it is a shared plan. */
async function leadOf(allianceId: string, userId: string) {
  const [a] = await sql`select id, name, owner_id, lead_goal_id, plan_mode from alliances where id = ${allianceId} and archived_at is null`;
  if (!a) throw new HttpError(404, 'not_found');
  if (a.owner_id !== userId) throw new HttpError(403, 'lead_only');
  if (a.plan_mode !== 'shared') throw new HttpError(409, 'not_shared_plan');
  return a;
}

async function membersOf(allianceId: string, leadId: string): Promise<Member[]> {
  const blocked = new Set(await blockedWith(sql, leadId));
  const rows = await sql`select m.user_id, m.goal_id, m.start_date::text, m.shares_progress, u.display_name, g.status = 'completed' as finished
                         from alliance_members m join users u on u.id = m.user_id join goals g on g.id = m.goal_id
                         where m.alliance_id = ${allianceId} and m.role = 'member' and m.status <> 'left' and u.deleted_at is null order by m.joined_at`;
  return (rows as unknown as Member[]).filter(m => !blocked.has(m.user_id));
}

/** One member's week on the plan: what was due, what they did, how it felt. */
async function weekOf(m: Member) {
  const today = await localToday(sql, m.user_id);
  const days = Array.from({ length: WEEK }, (_, i) => addDays(today, i - WEEK + 1));
  const tasks = await sql`select id, shared_task_id, title, measure, position from tasks where goal_id = ${m.goal_id} and origin = 'shared' and deleted_at is null order by position`;
  const all = await sql`select id from tasks where goal_id = ${m.goal_id} and origin = 'shared'`;
  const ids = all.map(t => t.id as string);
  const done = ids.length ? await sql`select task_id, local_date::text as d from task_completions where task_id in ${sql(ids)} and not suspect and local_date > ${today}::date - 400` : [];
  const checks = ids.length ? await sql`select task_id, local_date::text as d, feel, amount, note_ciphertext from checkins where task_id in ${sql(ids)} and local_date >= ${days[0]!}` : [];
  const active = new Set(done.map(c => c.d as string));
  const lastActive = [...active].sort().pop() ?? null;
  let due = 0, did = 0;
  for (const d of days) {
    if (d < m.start_date) continue;
    const n = done.filter(c => c.d === d && tasks.some(t => t.id === c.task_id)).length;
    due += tasks.length; did += Math.min(n, tasks.length);
  }
  const todayDone = done.filter(c => c.d === today && tasks.some(t => t.id === c.task_id)).length;
  const feels = checks.map(c => c.feel as number | null).filter((x): x is number => x != null);
  const feel = avg(feels);
  // Days without a tick, counting from the day they joined when they never ticked one.
  const idle = lastActive ? daysBetween(lastActive, today) : daysBetween(m.start_date, today) + 1;
  const attention: Attention = m.finished ? null : idle >= 3 ? 'away' : feel != null && feels.length >= 2 && feel <= 2 ? 'low_feel' : due >= 3 && did / due < 0.5 ? 'behind' : null;
  return { today, days, tasks, done, checks, lastActive, due, did, todayDone, feel, attention, streak: streakOf(active, today) };
}

export async function studentRoutes(app: FastifyInstance) {
  /** Everyone on the plan, the ones who need attention first, plus how the class does on each task this week. */
  app.get<{ Params: { id: string } }>('/v1/alliances/:id/students', { onRequest: auth }, async req => {
    const a = await leadOf(req.params.id, req.userId);
    const members = await membersOf(a.id, req.userId);
    const plan = await sql`select id, title, measure from tasks where goal_id = ${a.lead_goal_id} and deleted_at is null order by position`;
    const perTask = new Map<string, { done: number; due: number; feels: number[] }>(plan.map(t => [t.id as string, { done: 0, due: 0, feels: [] }]));
    const students: Student[] = [];
    for (const m of members) {
      const base = { userId: m.user_id, name: firstName(m.display_name), sharing: m.shares_progress, finished: m.finished, startDate: m.start_date };
      if (!m.shares_progress) { students.push({ ...base, progress: null }); continue; }
      const w = await weekOf(m);
      if (!m.finished) for (const t of w.tasks) {
        const s = perTask.get(t.shared_task_id as string);
        if (!s) continue;
        for (const d of w.days) if (d >= m.start_date) { s.due++; if (w.done.some(c => c.task_id === t.id && c.d === d)) s.done++; }
        for (const c of w.checks) if (c.task_id === t.id && c.feel != null) s.feels.push(c.feel as number);
      }
      const [n] = await sql`select 1 from lead_nudges where alliance_id = ${a.id} and user_id = ${m.user_id} and local_date = ${w.today}`;
      students.push({
        ...base,
        progress: {
          streak: w.streak, lastActive: w.lastActive, week: { done: w.did, due: w.due }, today: { done: w.todayDone, total: w.tasks.length },
          feel: w.feel, attention: w.attention, nudgedToday: !!n,
        },
      });
    }
    const rank = (s: Student) => (!s.progress ? 3 : s.progress.attention ? 0 : s.finished ? 2 : 1);
    const rate = (s: Student) => (s.progress && s.progress.week.due ? s.progress.week.done / s.progress.week.due : 1);
    students.sort((x, y) => rank(x) - rank(y) || rate(x) - rate(y) || x.name.localeCompare(y.name));
    const sharing = students.filter(s => s.progress && !s.finished);
    const week = sharing.reduce((t, s) => ({ done: t.done + s.progress!.week.done, due: t.due + s.progress!.week.due }), { done: 0, due: 0 });
    return {
      name: a.name, members: members.length, sharing: students.filter(s => s.sharing).length, week,
      students,
      tasks: plan.map(t => { const s = perTask.get(t.id as string)!; return { id: t.id, title: t.title, measure: t.measure ?? null, done: s.done, due: s.due, feel: avg(s.feels) }; }),
    };
  });

  /** One member's last 7 days, task by task, with their check-ins. */
  app.get<{ Params: { id: string; userId: string } }>('/v1/alliances/:id/students/:userId', { onRequest: auth }, async req => {
    const a = await leadOf(req.params.id, req.userId);
    const m = (await membersOf(a.id, req.userId)).find(x => x.user_id === req.params.userId);
    if (!m) throw new HttpError(404, 'not_found');
    if (!m.shares_progress) throw new HttpError(403, 'not_sharing');
    const w = await weekOf(m);
    const key = w.checks.some(c => c.note_ciphertext) ? await journalKey(sql, m.user_id) : null;
    const [n] = await sql`select 1 from lead_nudges where alliance_id = ${a.id} and user_id = ${m.user_id} and local_date = ${w.today}`;
    return {
      userId: m.user_id, name: firstName(m.display_name), startDate: m.start_date, finished: m.finished, days: w.days,
      streak: w.streak, lastActive: w.lastActive, week: { done: w.did, due: w.due }, feel: w.feel, attention: w.attention, nudgedToday: !!n,
      tasks: w.tasks.map(t => ({
        id: t.shared_task_id, title: t.title, measure: t.measure ?? null,
        days: w.days.map(d => {
          const c = w.checks.find(x => x.task_id === t.id && x.d === d);
          const state = d < m.start_date ? 'before' : w.done.some(x => x.task_id === t.id && x.d === d) ? 'done' : d === w.today ? 'open' : 'missed';
          return { date: d, state, feel: c?.feel ?? null, amount: c?.amount == null ? null : Number(c.amount), note: c?.note_ciphertext ? decryptText(key!, c.note_ciphertext) : null };
        }),
      })),
    };
  });

  /** "Keep going": one push a day per member, naming the plan, never a task. */
  app.post<{ Params: { id: string; userId: string } }>('/v1/alliances/:id/students/:userId/nudge', { onRequest: auth, config: { rateLimit: { max: 60, timeWindow: '1 hour' } } }, async (req, reply) => {
    const a = await leadOf(req.params.id, req.userId);
    const m = (await membersOf(a.id, req.userId)).find(x => x.user_id === req.params.userId);
    if (!m) throw new HttpError(404, 'not_found');
    const [fresh] = await sql`insert into lead_nudges (alliance_id, user_id, local_date) values (${a.id}, ${m.user_id}, ${await localToday(sql, m.user_id)}) on conflict do nothing returning 1`;
    if (!fresh) throw new HttpError(409, 'already_nudged');
    const [me] = await sql`select display_name from users where id = ${req.userId}`;
    void push(m.user_id, { title: `${firstName(me!.display_name)} is cheering you on`, body: `Keep going on ${a.name}.`, data: { allianceId: a.id }, collapseKey: `lead-nudge-${a.id}` }, 'system');
    return reply.code(204).send();
  });

  /** A member starts or stops sharing their progress on this plan with the lead. */
  app.put<{ Params: { id: string } }>('/v1/alliances/:id/sharing', { onRequest: auth }, async (req, reply) => {
    const b = z.object({ on: z.boolean() }).safeParse(req.body);
    if (!b.success) throw new HttpError(400, 'invalid_request');
    const [r] = await sql`update alliance_members m set shares_progress = ${b.data.on} from alliances a
                          where a.id = m.alliance_id and m.alliance_id = ${req.params.id} and m.user_id = ${req.userId} and m.role = 'member' and m.status <> 'left' and a.plan_mode = 'shared'
                          returning 1`;
    if (!r) throw new HttpError(404, 'not_found');
    return reply.code(204).send();
  });
}
