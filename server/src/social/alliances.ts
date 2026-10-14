import type { FastifyInstance } from 'fastify';
import { randomInt } from 'node:crypto';
import { z } from 'zod';
import { sql, type Tx } from '../db.js';
import { auth } from '../auth/tokens.js';
import { HttpError, uuidv7 } from '../util.js';
import { blockedWith, communityUnlocked, firstName, localToday, streakOf } from './progress.js';

export const FREE_CAP = 15;
const FREE_GOAL_LIMIT = 3;
export const PLUS_CAP = 50;
const AWAY_AFTER_DAYS = 3;

const NAME_BLOCK = /\b(fuck|shit|bitch|cunt|nigg|fag|retard|whore|slut|nazi|hitler|rape|porn)/i;
const addDays = (d: string, n: number) => { const x = new Date(`${d}T00:00:00Z`); x.setUTCDate(x.getUTCDate() + n); return x.toISOString().slice(0, 10); };
const log = (db: Tx, userId: string, entity: string, id: string, op: 'upsert' | 'delete' = 'upsert') =>
  db`insert into changes (user_id, entity, entity_id, op) values (${userId}, ${entity}, ${id}, ${op})`;

/** "DAWN-4821": four letters from the name, four digits. Unique; the code is the alliance's identity (names aren't). */
async function newCode(db: Tx, name: string): Promise<string> {
  const letters = (name.toUpperCase().replace(/[^A-Z]/g, '') + 'XXXX').slice(0, 4);
  for (;;) {
    const code = `${letters}-${randomInt(1000, 10000)}`;
    const [taken] = await db`select 1 from alliances where invite_code = ${code}`;
    if (!taken) return code;
  }
}

/** Owner's Alliance+ raises the member cap to 50. */
const capFor = async (db: Tx | typeof sql, ownerId: string) => {
  const [e] = await db`select 1 from entitlements where user_id = ${ownerId} and entitlement = 'alliance_plus' and (expires_at is null or expires_at > now())`;
  return e ? PLUS_CAP : FREE_CAP;
};

/** Snapshot of the lead's current tasks as a shared-plan version, effective from [from]. Replaces a version that hasn't started yet. */
export async function publishPlanVersion(db: Tx, allianceId: string, from: string): Promise<number> {
  const [a] = await db`select lead_goal_id from alliances where id = ${allianceId}`;
  // Version numbers only grow (a member in another time zone may already have applied a version that starts "tomorrow" here).
  // A newer snapshot supersedes versions that haven't started yet, and older versions starting the same day.
  const [latest] = await db`select coalesce(max(version), 0) as v from shared_plan_tasks where alliance_id = ${allianceId}`;
  const version = (latest!.v as number) + 1;
  await db`delete from shared_plan_tasks where alliance_id = ${allianceId} and effective_from >= ${from}`;
  const tasks = await db`select id, title, priority, time_of_day, minutes, rationale, position from tasks where goal_id = ${a!.lead_goal_id} and deleted_at is null order by position`;
  for (const t of tasks) {
    await db`insert into shared_plan_tasks (id, alliance_id, version, title, priority, time_of_day, minutes, rationale, position, effective_from, source_task_id)
             values (${uuidv7()}, ${allianceId}, ${version}, ${t.title}, ${t.priority}, ${t.time_of_day}, ${t.minutes}, ${t.rationale}, ${t.position}, ${from}, ${t.id})`;
  }
  return version;
}

/** The lead changed their tasks: members get the new plan the next local morning (never mid-day). */
export async function leadTasksChanged(db: Tx, goalId: string) {
  const shared = await db`select id, owner_id from alliances where lead_goal_id = ${goalId} and plan_mode = 'shared' and archived_at is null`;
  for (const a of shared) await publishPlanVersion(db, a.id, addDays(await localToday(db, a.owner_id), 1));
}

/**
 * Brings a member's copy of a shared plan up to the newest version that has started in their local day.
 * [onlyIncomplete] ("apply now" corrections) leaves tasks already ticked today untouched.
 */
async function applyToMember(db: Tx, allianceId: string, m: { user_id: string; goal_id: string; applied_version: number }, onlyIncomplete = false, force = false) {
  const today = await localToday(db, m.user_id);
  const [v] = await db`select max(version) as v from shared_plan_tasks where alliance_id = ${allianceId} and (effective_from <= ${today} or ${force})`;
  if (!v?.v || (v.v <= m.applied_version && !force)) return;
  const rows = await db`select title, priority, time_of_day, minutes, rationale, position, source_task_id from shared_plan_tasks where alliance_id = ${allianceId} and version = ${v.v} and deleted_at is null`;
  const mine = await db`select t.id, t.shared_task_id, t.deleted_at, t.title, t.priority, t.time_of_day, t.minutes, t.rationale, t.position,
                               exists(select 1 from task_completions c where c.task_id = t.id and c.local_date = ${today}) as done
                        from tasks t where t.goal_id = ${m.goal_id} and t.origin = 'shared'`;
  for (const r of rows) {
    const t = mine.find(x => x.shared_task_id === r.source_task_id);
    if (t && onlyIncomplete && t.done) continue;
    if (t) {
      // Unchanged tasks aren't touched, so devices only download what the lead actually changed.
      if (!t.deleted_at && (['title', 'priority', 'time_of_day', 'minutes', 'rationale', 'position'] as const).every(k => t[k] === r[k])) continue;
      await db`update tasks set title = ${r.title}, priority = ${r.priority}, time_of_day = ${r.time_of_day}, minutes = ${r.minutes}, rationale = ${r.rationale},
                                position = ${r.position}, deleted_at = null, updated_at = now() where id = ${t.id}`;
      await log(db, m.user_id, 'task', t.id);
    } else {
      const id = uuidv7();
      await db`insert into tasks (id, goal_id, user_id, title, priority, time_of_day, minutes, rationale, origin, shared_task_id, position)
               values (${id}, ${m.goal_id}, ${m.user_id}, ${r.title}, ${r.priority}, ${r.time_of_day}, ${r.minutes}, ${r.rationale}, 'shared', ${r.source_task_id}, ${r.position})`;
      await log(db, m.user_id, 'task', id);
    }
  }
  for (const t of mine) {
    if (t.deleted_at || rows.some(r => r.source_task_id === t.shared_task_id) || (onlyIncomplete && t.done)) continue;
    await db`update tasks set deleted_at = now(), updated_at = now() where id = ${t.id}`;
    await log(db, m.user_id, 'task', t.id);
  }
  await db`update alliance_members set applied_version = ${v.v} where alliance_id = ${allianceId} and user_id = ${m.user_id}`;
}

/** Called before a sync pull: applies any shared-plan version that has started in the user's local day. */
export async function applySharedPlans(userId: string) {
  const ms = await sql`select m.alliance_id, m.user_id, m.goal_id, m.applied_version from alliance_members m join alliances a on a.id = m.alliance_id
                       where m.user_id = ${userId} and m.status <> 'left' and m.role = 'member' and a.plan_mode = 'shared' and a.archived_at is null`;
  for (const m of ms) await sql.begin(tx => applyToMember(tx, m.alliance_id, m as never));
}

/** A member's goal stops following the alliance (they left, or the alliance ended): it becomes a normal personal goal. */
async function detachGoal(db: Tx, userId: string, goalId: string) {
  await db`update goals set source_alliance_id = null, updated_at = now() where id = ${goalId} and user_id = ${userId}`;
  const tasks = await db`update tasks set origin = 'user', shared_task_id = null, updated_at = now() where goal_id = ${goalId} and origin = 'shared' returning id`;
  await log(db, userId, 'goal', goalId);
  for (const t of tasks) await log(db, userId, 'task', t.id);
}

/** Leaving: a lead hands over to the longest-standing member; with nobody left the alliance is archived. */
export async function leave(db: Tx, allianceId: string, userId: string) {
  const [me] = await db`update alliance_members set status = 'left' where alliance_id = ${allianceId} and user_id = ${userId} and status <> 'left' returning role, goal_id`;
  if (!me) return;
  const [a] = await db`select plan_mode from alliances where id = ${allianceId}`;
  if (me.role === 'member' && a!.plan_mode === 'shared') await detachGoal(db, userId, me.goal_id);
  if (me.role !== 'owner') return;
  const [next] = await db`select user_id, goal_id from alliance_members where alliance_id = ${allianceId} and status <> 'left' order by joined_at limit 1`;
  if (!next) { await db`update alliances set archived_at = now() where id = ${allianceId}`; return; }
  await db`update alliance_members set role = 'owner' where alliance_id = ${allianceId} and user_id = ${next.user_id}`;
  await db`update alliance_members set role = 'member' where alliance_id = ${allianceId} and user_id = ${userId}`;
  // The new lead's copy becomes the plan's source of truth from now on.
  if (a!.plan_mode === 'shared') {
    await db`update alliances set owner_id = ${next.user_id}, lead_goal_id = ${next.goal_id} where id = ${allianceId}`;
    await detachGoal(db, next.user_id, next.goal_id);
  } else {
    await db`update alliances set owner_id = ${next.user_id} where id = ${allianceId}`;
  }
}

/** Members, collective streak and the shared-plan progress for one alliance, as seen by [userId]. */
async function summary(allianceId: string, userId: string) {
  const [a] = await sql`select a.id, a.name, a.plan_mode, a.cap, a.invite_code, a.start_date::text, a.owner_id, a.lead_goal_id, a.category,
                               g.statement as goal, g.label, g.deadline::text, u.display_name as lead
                        from alliances a join goals g on g.id = a.lead_goal_id join users u on u.id = a.owner_id where a.id = ${allianceId}`;
  if (!a) throw new HttpError(404, 'not_found');
  const blocked = new Set(await blockedWith(sql, userId));
  const members = await sql`select m.user_id, m.goal_id, m.role, m.start_date::text, m.joined_at, u.display_name
                            from alliance_members m join users u on u.id = m.user_id where m.alliance_id = ${allianceId} and m.status <> 'left' and u.deleted_at is null order by m.joined_at`;
  const today = await localToday(sql, userId);
  const done = await sql`select t.goal_id, c.local_date::text as d, count(*)::int as n from task_completions c join tasks t on t.id = c.task_id
                         where t.goal_id in ${sql(members.map(m => m.goal_id as string))} and c.local_date > ${today}::date - 60 group by 1, 2`;
  const days = new Map<string, Map<string, number>>();
  for (const r of done) { const g = days.get(r.goal_id) ?? new Map(); g.set(r.d, r.n); days.set(r.goal_id, g); }
  // Away: no completion in the last 3 days (excluded from the collective streak and Game Night).
  const away = (goalId: string) => ![0, 1, 2].some(i => days.get(goalId)?.has(addDays(today, -i)));
  // Collective streak: days on which every active member who had joined by then completed at least one task.
  const active = members.filter(m => !away(m.goal_id));
  const all = new Set<string>();
  for (let i = 0; i < 60; i++) {
    const d = addDays(today, -i);
    const due = active.filter(m => m.start_date <= d);
    if (due.length && due.every(m => days.get(m.goal_id)?.has(d))) all.add(d);
  }
  const tasksToday = async (goalId: string) => {
    const [r] = await sql`select count(*)::int as total, count(c.task_id)::int as done from tasks t left join task_completions c on c.task_id = t.id and c.local_date = ${today}
                          where t.goal_id = ${goalId} and t.deleted_at is null`;
    return { total: r!.total as number, done: r!.done as number };
  };
  const me = members.find(m => m.user_id === userId);
  let shared = null;
  if (a.plan_mode === 'shared' && me) {
    const mine = await tasksToday(me.goal_id);
    let gDone = 0, gTotal = 0;
    for (const m of active) { const t = await tasksToday(m.goal_id); gDone += t.done; gTotal += t.total; }
    shared = { lead: firstName(a.lead), goal: a.goal, deadline: a.deadline, me: mine, group: { done: gDone, total: gTotal }, isLead: a.owner_id === userId };
  }
  const feed = await sql`
    select u.display_name, m.user_id, 'joined' as what, null as title, m.joined_at as at from alliance_members m join users u on u.id = m.user_id
      where m.alliance_id = ${allianceId} and m.joined_at > now() - interval '14 days'
    union all
    select u.display_name, c.user_id, 'completed', case when t.origin = 'shared' then t.title end, c.completed_at
      from task_completions c join tasks t on t.id = c.task_id join users u on u.id = c.user_id
      where t.goal_id in ${sql(members.map(m => m.goal_id as string))} and c.completed_at > now() - interval '3 days'
    order by at desc limit 20`;
  return {
    id: a.id, name: a.name, planMode: a.plan_mode, inviteCode: a.invite_code, cap: await capFor(sql, a.owner_id), startDate: a.start_date,
    role: me?.role ?? null, goalId: me?.goal_id ?? null,
    streak: streakOf(all, today),
    members: members.filter(m => !blocked.has(m.user_id)).map(m => ({ userId: m.user_id, name: firstName(m.display_name), role: m.role, away: away(m.goal_id), me: m.user_id === userId })),
    memberCount: members.length,
    shared,
    // Own-plan task titles are personal, so the feed only names shared-plan tasks.
    feed: feed.filter(f => !blocked.has(f.user_id)).map(f => ({ name: f.user_id === userId ? null : firstName(f.display_name), what: f.what, title: f.title, at: (f.at as Date).getTime() })),
  };
}

const CreateBody = z.object({ name: z.string().trim().min(3).max(40), planMode: z.enum(['own', 'shared']), goalId: z.string().uuid() });

export async function allianceRoutes(app: FastifyInstance) {
  app.post('/v1/alliances', { onRequest: auth, config: { rateLimit: { max: 10, timeWindow: '1 hour' } } }, async req => {
    const p = CreateBody.safeParse(req.body);
    if (!p.success) throw new HttpError(400, 'invalid_request', { field: p.error.issues[0]?.path.join('.') });
    const b = p.data;
    if (NAME_BLOCK.test(b.name)) throw new HttpError(422, 'name_not_allowed');
    const [g] = await sql`select category from goals where id = ${b.goalId} and user_id = ${req.userId} and status = 'active'`;
    if (!g) throw new HttpError(404, 'goal_missing');
    if (!(await communityUnlocked(sql, req.userId, b.goalId)).unlocked) throw new HttpError(403, 'community_locked');
    const id = uuidv7();
    await sql.begin(async tx => {
      const today = await localToday(tx, req.userId);
      await tx`insert into alliances (id, owner_id, name, category, plan_mode, lead_goal_id, start_date, cap, invite_code)
               values (${id}, ${req.userId}, ${b.name}, ${g.category}, ${b.planMode}, ${b.goalId}, ${today}, ${await capFor(tx, req.userId)}, ${await newCode(tx, b.name)})`;
      await tx`insert into alliance_members (alliance_id, user_id, goal_id, role, start_date) values (${id}, ${req.userId}, ${b.goalId}, 'owner', ${today})`;
      if (b.planMode === 'shared') await publishPlanVersion(tx, id, today);
    });
    return { alliance: await summary(id, req.userId) };
  });

  app.get('/v1/alliances', { onRequest: auth }, async req => {
    const rows = await sql`select alliance_id from alliance_members m join alliances a on a.id = m.alliance_id
                           where m.user_id = ${req.userId} and m.status <> 'left' and a.archived_at is null order by m.joined_at`;
    return { alliances: await Promise.all(rows.map(r => summary(r.alliance_id, req.userId))) };
  });

  app.get<{ Params: { id: string } }>('/v1/alliances/:id', { onRequest: auth }, async req => {
    const [m] = await sql`select 1 from alliance_members where alliance_id = ${req.params.id} and user_id = ${req.userId} and status <> 'left'`;
    if (!m) throw new HttpError(404, 'not_found');
    return { alliance: await summary(req.params.id, req.userId) };
  });

  /** What someone sees before joining, including the pace note for late joiners. */
  const preview = async (code: string, userId: string) => {
    const [a] = await sql`
      select a.id, a.name, a.plan_mode, a.owner_id, a.start_date::text, g.deadline::text, g.statement as goal, g.label, u.display_name as lead, a.archived_at,
             (select count(*)::int from alliance_members m where m.alliance_id = a.id and m.status <> 'left') as members,
             exists(select 1 from alliance_members m where m.alliance_id = a.id and m.user_id = ${userId} and m.status <> 'left') as already
      from alliances a join goals g on g.id = a.lead_goal_id join users u on u.id = a.owner_id
      where a.invite_code = ${code.trim().toUpperCase()}`;
    if (!a || a.archived_at) throw new HttpError(404, 'invalid_code');
    if (a.already) throw new HttpError(409, 'already_member', { name: a.name });
    const cap = await capFor(sql, a.owner_id);
    if (a.members >= cap) throw new HttpError(409, 'alliance_full', { cap });
    const today = await localToday(sql, userId);
    if (a.plan_mode === 'shared' && a.deadline <= today) throw new HttpError(410, 'plan_ended');
    const [v] = await sql`select max(version) as v from shared_plan_tasks where alliance_id = ${a.id} and effective_from <= ${today}`;
    const tasks = a.plan_mode === 'shared' && v?.v ? await sql`select title, priority, time_of_day from shared_plan_tasks where alliance_id = ${a.id} and version = ${v.v} and deleted_at is null order by position` : [];
    const day = (x: string) => Math.round((Date.parse(x) - Date.parse(a.start_date)) / 864e5) + 1;
    return {
      id: a.id, name: a.name, planMode: a.plan_mode, lead: firstName(a.lead), members: a.members, cap, spotsLeft: cap - a.members,
      goal: a.plan_mode === 'shared' ? a.goal : null, label: a.label, deadline: a.plan_mode === 'shared' ? a.deadline : null,
      tasks: tasks.map(t => ({ title: t.title, priority: t.priority, timeOfDay: t.time_of_day })),
      // Late joiners start at day 1 with the same deadline; days before joining don't count against anyone.
      pace: a.plan_mode === 'shared' ? { groupDay: day(today), groupTotal: day(a.deadline), yourTotal: Math.round((Date.parse(a.deadline) - Date.parse(today)) / 864e5) + 1 } : null,
    };
  };

  app.get<{ Params: { code: string } }>('/v1/alliances/preview/:code', { onRequest: auth }, async req => ({ preview: await preview(req.params.code, req.userId) }));

  app.post('/v1/alliances/join', { onRequest: auth, config: { rateLimit: { max: 20, timeWindow: '1 hour' } } }, async req => {
    const b = z.object({ code: z.string().regex(/^[A-Za-z]{4}-\d{4}$/), goalId: z.string().uuid().optional() }).safeParse(req.body);
    if (!b.success) throw new HttpError(400, 'invalid_code_format');
    const p = await preview(b.data.code, req.userId);
    const today = await localToday(sql, req.userId);
    await sql.begin(async tx => {
      await tx`select 1 from alliances where id = ${p.id} for update`;           // serialises joins, so the cap holds
      const [n] = await tx`select count(*)::int as n from alliance_members where alliance_id = ${p.id} and status <> 'left'`;
      if (n!.n >= p.cap) throw new HttpError(409, 'alliance_full', { cap: p.cap });
      let goalId = b.data.goalId;
      if (p.planMode === 'shared') {
        // Joining a shared plan creates the goal, so it counts against the free limit of 3.
        const [c] = await tx`select count(*)::int as n, exists(select 1 from entitlements where user_id = ${req.userId} and entitlement = 'plus' and (expires_at is null or expires_at > now())) as plus
                             from goals where user_id = ${req.userId} and status = 'active'`;
        if (!c!.plus && c!.n >= FREE_GOAL_LIMIT) throw new HttpError(409, 'goal_limit');
        const [a] = await tx`select a.lead_goal_id, a.category, g.label, g.statement, g.deadline::text from alliances a join goals g on g.id = a.lead_goal_id where a.id = ${p.id}`;
        goalId = uuidv7();
        await tx`insert into goals (id, user_id, label, statement, category, start_date, deadline, source_alliance_id, color_index)
                 values (${goalId}, ${req.userId}, ${a!.label}, ${a!.statement}, ${a!.category}, ${today}, ${a!.deadline}, ${p.id},
                         (select count(*) % 3 from goals where user_id = ${req.userId} and status = 'active'))`;
        await log(tx, req.userId, 'goal', goalId);
      } else {
        const [g] = goalId ? await tx`select 1 from goals where id = ${goalId} and user_id = ${req.userId} and status = 'active'` : [];
        if (!g) throw new HttpError(400, 'goal_required');
      }
      await tx`insert into alliance_members (alliance_id, user_id, goal_id, role, start_date, status, joined_at, applied_version)
               values (${p.id}, ${req.userId}, ${goalId!}, 'member', ${today}, 'active', now(), 0)
               on conflict (alliance_id, user_id) do update set status = 'active', goal_id = excluded.goal_id, start_date = excluded.start_date, joined_at = now(), applied_version = 0, role = 'member'`;
      if (p.planMode === 'shared') await applyToMember(tx, p.id, { user_id: req.userId, goal_id: goalId!, applied_version: 0 });
    });
    return { alliance: await summary(p.id, req.userId) };
  });

  app.post<{ Params: { id: string } }>('/v1/alliances/:id/leave', { onRequest: auth }, async (req, reply) => {
    await sql.begin(tx => leave(tx, req.params.id, req.userId));
    return reply.code(204).send();
  });

  /** Lead only: push the current plan to members now, changing only tasks they haven't ticked today. */
  app.post<{ Params: { id: string } }>('/v1/alliances/:id/apply-now', { onRequest: auth }, async (req, reply) => {
    const [a] = await sql`select owner_id, plan_mode from alliances where id = ${req.params.id} and archived_at is null`;
    if (!a || a.plan_mode !== 'shared') throw new HttpError(404, 'not_found');
    if (a.owner_id !== req.userId) throw new HttpError(403, 'lead_only');
    await sql.begin(async tx => {
      await publishPlanVersion(tx, req.params.id, await localToday(tx, req.userId));
      const members = await tx`select user_id, goal_id, applied_version from alliance_members where alliance_id = ${req.params.id} and role = 'member' and status <> 'left'`;
      for (const m of members) await applyToMember(tx, req.params.id, m as never, true, true);
    });
    return reply.code(204).send();
  });
}
