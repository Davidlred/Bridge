import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { sql, type Tx } from '../db.js';
import { auth } from '../auth/tokens.js';
import { HttpError } from '../util.js';
import { deleteGoalImages } from '../goals/routes.js';
import { Feasibility } from '../ai/planSchema.js';
import { decryptText, encryptText, journalKey } from '../crypto.js';
import { uuidv7 } from '../util.js';
import { applySharedPlans, leadTasksChanged } from '../social/alliances.js';
import { checkUnlocks } from '../social/game.js';

export const FREE_GOAL_LIMIT = 3;
const PULL_PAGE = 1000;

const ISODate = z.string().regex(/^\d{4}-\d{2}-\d{2}$/);
const Mutation = z.object({
  id: z.string().uuid(),
  entity: z.enum(['goal', 'task', 'completion', 'settings', 'journal', 'chat']),
  entityId: z.string().uuid(),
  op: z.enum(['upsert', 'delete', 'complete', 'uncomplete']),
  patch: z.record(z.unknown()).default({}),
  clientTs: z.number().int().nonnegative(),
});
type Mutation = z.infer<typeof Mutation>;
const PushBody = z.object({ mutations: z.array(Mutation).max(200) });

// Patches carry changed fields only, so every field is optional. Creation needs the fields marked "required on create".
const GoalPatch = z.object({
  label: z.string().trim().min(1).max(24),          // required on create
  statement: z.string().trim().min(1).max(500),     // required on create
  startDate: ISODate,                               // required on create
  deadline: ISODate,                                // required on create; the user's pick, never altered here
  category: z.string().max(40).nullable(),
  status: z.enum(['active', 'archived', 'completed', 'paused']),
  colorIndex: z.number().int().min(0).max(2),
  routine: z.string().max(1000).nullable(),         // onboarding step 2, shown in Reality Check
  realityNote: z.string().max(1000).nullable(),     // "Why I started"
  feasibility: Feasibility.nullable(),
}).partial().strict();

const TaskPatch = z.object({
  goalId: z.string().uuid(),                        // required on create
  title: z.string().trim().min(1).max(120),         // required on create
  priority: z.enum(['High', 'Medium', 'Low']),
  timeOfDay: z.string().max(24).nullable(),
  minutes: z.number().int().min(1).max(1440).nullable(),
  rationale: z.string().max(200).nullable(),
  notes: z.string().max(2000).nullable(),
  origin: z.enum(['ai', 'user']),                   // 'shared' rows are only ever created by the server
  position: z.number().int().min(0).max(10_000),
}).partial().strict();

const CompletionPatch = z.object({ localDate: ISODate });
const JournalPatch = z.object({
  goalId: z.string().uuid().nullable(),
  localDate: ISODate,
  mood: z.number().int().min(1).max(5).nullable(),
  body: z.string().max(10_000),
  /** The version the device last saw. A different server version means another device edited it meanwhile. */
  baseVersion: z.number().int().min(0),
  /** The user picked a version after a conflict: drop the other copies. */
  resolve: z.boolean().optional(),
}).strict();
const SettingsPatch = z.record(z.union([z.string().max(200), z.number(), z.boolean(), z.null()])).refine(o => Object.keys(o).length <= 40);

class Reject extends Error {}
const reject = (code: string): never => { throw new Reject(code); };
const check = <T extends z.ZodTypeAny>(schema: T, v: unknown): z.infer<T> => { const r = schema.safeParse(v); return r.success ? r.data : reject('invalid'); };
const log = (tx: Tx, userId: string, entity: string, entityId: string, op: 'upsert' | 'delete', key: string | null = null) =>
  tx`insert into changes (user_id, entity, entity_id, key, op) values (${userId}, ${entity}, ${entityId}, ${key}, ${op})`;

async function applyGoal(tx: Tx, userId: string, m: Mutation) {
  const [row] = await tx`select user_id, status, start_date::text, deadline::text from goals where id = ${m.entityId} for update`;
  if (row && row.user_id !== userId) reject('forbidden');
  if (m.op === 'delete') {
    if (row) { await tx`delete from goals where id = ${m.entityId}`; await log(tx, userId, 'goal', m.entityId, 'delete'); await deleteGoalImages(userId, m.entityId); }
    return;
  }
  if (m.op !== 'upsert') reject('invalid');
  const p = check(GoalPatch, m.patch);
  // A paused goal is read-only: only its status can change (resume, complete, archive).
  if (row?.status === 'paused' && Object.keys(p).some(k => k !== 'status')) reject('goal_paused');
  const becomesActive = (p.status ?? row?.status ?? 'active') === 'active' && row?.status !== 'active';
  if (becomesActive) {
    const [c] = await tx`select count(*)::int as n, exists(select 1 from entitlements where user_id = ${userId} and entitlement = 'plus' and (expires_at is null or expires_at > now())) as plus
                         from goals where user_id = ${userId} and status = 'active'`;
    if (!c!.plus && c!.n >= FREE_GOAL_LIMIT) reject('goal_limit');
  }
  if (!row) {
    if (!p.label || !p.statement || !p.startDate || !p.deadline || p.deadline <= p.startDate) reject('invalid');
    await tx`insert into goals (id, user_id, label, statement, category, start_date, deadline, status, color_index, routine, reality_note, feasibility)
             values (${m.entityId}, ${userId}, ${p.label!}, ${p.statement!}, ${p.category ?? null}, ${p.startDate!}, ${p.deadline!}, ${p.status ?? 'active'}, ${p.colorIndex ?? 0},
                     ${p.routine ?? null}, ${p.realityNote ?? null}, ${p.feasibility ? tx.json(p.feasibility) : null})`;
  } else {
    if ((p.deadline ?? row.deadline) <= (p.startDate ?? row.start_date)) reject('invalid');
    await tx`update goals set
        label = coalesce(${p.label ?? null}, label), statement = coalesce(${p.statement ?? null}, statement),
        category = case when ${'category' in p} then ${p.category ?? null} else category end,
        start_date = coalesce(${p.startDate ?? null}::date, start_date), deadline = coalesce(${p.deadline ?? null}::date, deadline),
        status = coalesce(${p.status ?? null}::goal_status, status), color_index = coalesce(${p.colorIndex ?? null}, color_index),
        routine = case when ${'routine' in p} then ${p.routine ?? null} else routine end,
        reality_note = case when ${'realityNote' in p} then ${p.realityNote ?? null} else reality_note end,
        feasibility = case when ${'feasibility' in p} then ${p.feasibility ? tx.json(p.feasibility) : null}::jsonb else feasibility end,
        updated_at = now()
      where id = ${m.entityId}`;
  }
  await log(tx, userId, 'goal', m.entityId, 'upsert');
}

async function applyTask(tx: Tx, userId: string, m: Mutation) {
  await applyTaskChange(tx, userId, m);
  // The lead of a shared-plan alliance edited the plan: members get it the next local morning.
  const [t] = await tx`select goal_id from tasks where id = ${m.entityId}`;
  if (t) await leadTasksChanged(tx, t.goal_id);
}

async function applyTaskChange(tx: Tx, userId: string, m: Mutation) {
  const [row] = await tx`select user_id, origin, deleted_at from tasks where id = ${m.entityId} for update`;
  if (row && row.user_id !== userId) reject('forbidden');
  // Shared-plan tasks belong to the alliance lead; members can complete them but never change them.
  if (row?.origin === 'shared') reject('shared_task_readonly');
  const newGoal = z.string().uuid().safeParse(m.patch.goalId);
  const [owner] = await tx`select g.status from goals g where g.id = coalesce((select goal_id from tasks where id = ${m.entityId}), ${newGoal.success ? newGoal.data : null}::uuid)`;
  if (owner?.status === 'paused') reject('goal_paused');
  if (m.op === 'delete') {
    if (row && !row.deleted_at) { await tx`update tasks set deleted_at = now(), updated_at = now() where id = ${m.entityId}`; await log(tx, userId, 'task', m.entityId, 'upsert'); }
    return;
  }
  if (m.op !== 'upsert') reject('invalid');
  if (row?.deleted_at) return;                       // deletes win over edits
  const p = check(TaskPatch, m.patch);
  if (!row) {
    if (!p.goalId || !p.title) reject('invalid');
    const [g] = await tx`select 1 from goals where id = ${p.goalId!} and user_id = ${userId}`;
    if (!g) reject('goal_missing');
    await tx`insert into tasks (id, goal_id, user_id, title, priority, time_of_day, minutes, rationale, notes, origin, position)
             values (${m.entityId}, ${p.goalId!}, ${userId}, ${p.title!}, ${p.priority ?? 'Medium'}, ${p.timeOfDay ?? null}, ${p.minutes ?? null}, ${p.rationale ?? null}, ${p.notes ?? null}, ${p.origin ?? 'user'}, ${p.position ?? 0})`;
  } else {
    // Last writer wins per field: only the fields present in the patch are touched, in server-receipt order.
    await tx`update tasks set
        title = coalesce(${p.title ?? null}, title), priority = coalesce(${p.priority ?? null}::priority, priority),
        time_of_day = case when ${'timeOfDay' in p} then ${p.timeOfDay ?? null} else time_of_day end,
        minutes = case when ${'minutes' in p} then ${p.minutes ?? null}::int else minutes end,
        rationale = case when ${'rationale' in p} then ${p.rationale ?? null} else rationale end,
        notes = case when ${'notes' in p} then ${p.notes ?? null} else notes end,
        position = coalesce(${p.position ?? null}, position), updated_at = now()
      where id = ${m.entityId}`;
  }
  await log(tx, userId, 'task', m.entityId, 'upsert');
}

async function applyCompletion(tx: Tx, userId: string, m: Mutation) {
  const { localDate } = check(CompletionPatch, m.patch);
  const [t] = await tx`select g.status from tasks t join goals g on g.id = t.goal_id where t.id = ${m.entityId} and t.user_id = ${userId}`;
  if (!t) reject('task_missing');
  if (t!.status === 'paused') reject('goal_paused');
  const at = new Date(m.clientTs);
  if (m.op === 'complete') {
    // Set semantics: two devices completing the same task on the same day yield one completion.
    // A local date more than a day away from the server's view of the user's day (receipt time ± 36h) is suspect:
    // kept for the user, ignored for anything public (docs/EDGE_CASES.md → clock tampering).
    const [day] = await tx`select ((now() at time zone coalesce(home_timezone, 'UTC')) - interval '3 hours')::date as today from users where id = ${userId}`;
    const suspect = Math.abs(Date.parse(`${localDate}T00:00:00Z`) - (day!.today as Date).getTime()) > 864e5;
    const done = await tx`insert into task_completions (task_id, user_id, local_date, completed_at, suspect) values (${m.entityId}, ${userId}, ${localDate}, ${at}, ${suspect})
                          on conflict (task_id, local_date) do nothing returning 1`;
    if (done.length) await log(tx, userId, 'completion', m.entityId, 'upsert', localDate);
  } else if (m.op === 'uncomplete') {
    // Only an uncomplete newer than the completion removes it.
    const gone = await tx`delete from task_completions where task_id = ${m.entityId} and local_date = ${localDate} and completed_at <= ${at} returning 1`;
    if (gone.length) await log(tx, userId, 'completion', m.entityId, 'delete', localDate);
  } else reject('invalid');
}

async function applySettings(tx: Tx, userId: string, m: Mutation) {
  if (m.op !== 'upsert' || m.entityId !== userId) reject('invalid');
  await tx`update users set settings = settings || ${tx.json(check(SettingsPatch, m.patch))} where id = ${userId}`;
  await log(tx, userId, 'settings', userId, 'upsert');
}

/**
 * Journal: optimistic versions. On a conflict both versions are kept: the incoming one becomes current, the other is
 * stored as a conflict copy (conflict_of = current id) until the user picks. Bodies are encrypted with the user's key.
 */
async function applyJournal(tx: Tx, userId: string, m: Mutation) {
  const [byId] = await tx`select user_id, goal_id, local_date::text, version, conflict_of, mood, body_ciphertext from journal_entries where id = ${m.entityId} for update`;
  if (byId && byId.user_id !== userId) reject('forbidden');
  if (m.op === 'delete') {
    if (byId) {
      const gone = await tx`delete from journal_entries where (id = ${m.entityId} or conflict_of = ${m.entityId}) and user_id = ${userId} returning id`;
      for (const g of gone) await log(tx, userId, 'journal', g.id, 'delete');
    }
    return;
  }
  if (m.op !== 'upsert') reject('invalid');
  const p = check(JournalPatch, m.patch);
  if (p.goalId) { const [g] = await tx`select 1 from goals where id = ${p.goalId} and user_id = ${userId}`; if (!g) reject('goal_missing'); }
  if (byId?.conflict_of) return;                     // a conflict copy is read-only; picking one goes through the current row
  const key = await journalKey(tx, userId);
  const sealed = encryptText(key, p.body);
  const copyOf = async (row: { id: string; mood: number | null; body_ciphertext: Buffer | null; version: number }, current: string) => {
    const copyId = uuidv7();
    await tx`insert into journal_entries (id, user_id, goal_id, local_date, mood, body_ciphertext, body_length, version, conflict_of)
             values (${copyId}, ${userId}, ${p.goalId}, ${p.localDate}, ${row.mood}, ${row.body_ciphertext}, 0, ${row.version}, ${current})`;
    await log(tx, userId, 'journal', copyId, 'upsert');
  };

  if (byId) {
    if (byId.version !== p.baseVersion && !p.resolve) await copyOf({ id: m.entityId, mood: byId.mood, body_ciphertext: byId.body_ciphertext, version: byId.version }, m.entityId);
    await tx`update journal_entries set mood = ${p.mood}, body_ciphertext = ${sealed}, body_length = ${p.body.length}, version = version + 1, updated_at = now() where id = ${m.entityId}`;
    if (p.resolve) {
      const gone = await tx`delete from journal_entries where conflict_of = ${m.entityId} returning id`;
      for (const g of gone) await log(tx, userId, 'journal', g.id, 'delete');
    }
    await log(tx, userId, 'journal', m.entityId, 'upsert');
    return;
  }
  // New id. Another device may already have written this day under a different id: keep that as a conflict copy.
  const [current] = await tx`select id, mood, body_ciphertext, version from journal_entries
                             where user_id = ${userId} and goal_id is not distinct from ${p.goalId} and local_date = ${p.localDate} and conflict_of is null for update`;
  let version = 1;
  if (current) {
    version = (current.version as number) + 1;
    // Step it out of the "one current entry per day" index first (self reference), then point it at the new entry.
    await tx`update journal_entries set conflict_of = id where id = ${current.id}`;
  }
  await tx`insert into journal_entries (id, user_id, goal_id, local_date, mood, body_ciphertext, body_length, version)
           values (${m.entityId}, ${userId}, ${p.goalId}, ${p.localDate}, ${p.mood}, ${sealed}, ${p.body.length}, ${version})`;
  if (current) {
    await tx`update journal_entries set conflict_of = ${m.entityId}, updated_at = now() where id = ${current.id}`;
    await log(tx, userId, 'journal', current.id, 'upsert');
  }
  await log(tx, userId, 'journal', m.entityId, 'upsert');
}

/** Coach messages are created by the coach route; devices only change a suggestion's state or delete a message. */
async function applyChat(tx: Tx, userId: string, m: Mutation) {
  const [row] = await tx`select user_id from coach_messages where id = ${m.entityId} for update`;
  if (!row) return;                                   // never reached the server (failed send): nothing to do
  if (row.user_id !== userId) reject('forbidden');
  if (m.op === 'delete') {
    await tx`delete from coach_messages where id = ${m.entityId}`;
    await log(tx, userId, 'chat', m.entityId, 'delete');
    return;
  }
  if (m.op !== 'upsert') reject('invalid');
  const p = check(z.object({ suggestionState: z.enum(['pending', 'added', 'dismissed']) }).strict(), m.patch);
  await tx`update coach_messages set suggestion_state = ${p.suggestionState} where id = ${m.entityId}`;
  await log(tx, userId, 'chat', m.entityId, 'upsert');
}

const appliers = { goal: applyGoal, task: applyTask, completion: applyCompletion, settings: applySettings, journal: applyJournal, chat: applyChat };
const ms = (d: Date | null) => (d ? d.getTime() : null);

/**
 * Without Plus, three goals stay active. When Plus ends with more, the three worked on most recently stay and the rest
 * pause (read-only, history kept). The apps then let the user choose a different three. Runs on the billing webhook and on pull.
 */
export async function enforceGoalLimit(userId: string) {
  await sql.begin(async tx => {
    await tx`select pg_advisory_xact_lock(hashtextextended(${userId}, 0))`;
    const [c] = await tx`select count(*)::int as n, exists(select 1 from entitlements where user_id = ${userId} and entitlement = 'plus' and (expires_at is null or expires_at > now())) as plus
                         from goals where user_id = ${userId} and status = 'active'`;
    if (c!.plus || c!.n <= FREE_GOAL_LIMIT) return;
    const extra = await tx`select g.id from goals g where g.user_id = ${userId} and g.status = 'active'
                           order by (select max(c.local_date) from task_completions c join tasks t on t.id = c.task_id where t.goal_id = g.id) desc nulls last, g.updated_at desc
                           offset ${FREE_GOAL_LIMIT}`;
    for (const g of extra) {
      await tx`update goals set status = 'paused', updated_at = now() where id = ${g.id}`;
      await log(tx, userId, 'goal', g.id, 'upsert');
    }
  });
}

export async function syncRoutes(app: FastifyInstance) {
  app.post('/v1/sync/push', { onRequest: auth }, async req => {
    const body = PushBody.safeParse(req.body);
    if (!body.success) throw new HttpError(400, 'invalid_request');
    const accepted: string[] = [], rejected: Record<string, string> = {};
    for (const m of body.data.mutations) {
      const result = await sql.begin(async tx => {
        // One writer per user at a time, so server_seq order equals commit order and pulls never skip a change.
        await tx`select pg_advisory_xact_lock(hashtextextended(${req.userId}, 0))`;
        const [fresh] = await tx`insert into processed_mutations (id, user_id) values (${m.id}, ${req.userId}) on conflict (id) do nothing returning 1`;
        if (!fresh) {
          const [seen] = await tx`select result from processed_mutations where id = ${m.id} and user_id = ${req.userId}`;
          return seen ? (seen.result as string | null) : 'forbidden';
        }
        try {
          await tx.savepoint(sp => appliers[m.entity](sp, req.userId, m));
          return null;
        } catch (e) {
          if (!(e instanceof Reject)) throw e;
          await tx`update processed_mutations set result = ${e.message} where id = ${m.id}`;
          return e.message;
        }
      });
      if (result) rejected[m.id] = result; else accepted.push(m.id);
    }
    // A finished day may open game night for an alliance; checked after the response, never blocking sync.
    if (body.data.mutations.some(m => m.entity === 'completion' && accepted.includes(m.id))) void checkUnlocks(req.userId).catch(e => req.log.warn({ err: String(e) }, 'game_unlock_check'));
    return { accepted, rejected };
  });

  app.get<{ Querystring: { since?: string } }>('/v1/sync', { onRequest: auth }, async req => {
    const since = z.coerce.number().int().nonnegative().catch(0).parse(req.query.since);
    // Shared-plan updates take effect on the member's local morning; bring them in before reading changes.
    await applySharedPlans(req.userId);
    // Plus may have lapsed without a webhook reaching us (or before it did).
    await enforceGoalLimit(req.userId);
    const rows = await sql`select server_seq, entity, entity_id, key, op from changes where user_id = ${req.userId} and server_seq > ${since} order by server_seq limit ${PULL_PAGE}`;
    // Keep only the latest change per row; current state is read from the tables.
    const latest = new Map<string, (typeof rows)[number]>();
    for (const r of rows) latest.set(`${r.entity}:${r.entity_id}:${r.key ?? ''}`, r);
    const of = (entity: string) => [...latest.values()].filter(r => r.entity === entity);
    const goalIds = of('goal').map(r => r.entity_id as string), taskIds = of('task').map(r => r.entity_id as string), comps = of('completion');

    const goals = goalIds.length ? await sql`select id, label, statement, category, start_date::text, deadline::text, status, source_alliance_id, color_index, routine, reality_note, feasibility, updated_at from goals where id in ${sql(goalIds)} and user_id = ${req.userId}` : [];
    const tasks = taskIds.length ? await sql`select id, goal_id, title, priority, time_of_day, minutes, rationale, notes, origin, shared_task_id, position, deleted_at, updated_at from tasks where id in ${sql(taskIds)} and user_id = ${req.userId}` : [];
    const live = comps.length ? await sql`select task_id, local_date::text, completed_at from task_completions where user_id = ${req.userId} and (task_id, local_date) in (select * from unnest(${comps.map(c => c.entity_id as string)}::uuid[], ${comps.map(c => c.key as string)}::date[]))` : [];
    const [me] = of('settings').length ? await sql`select settings from users where id = ${req.userId}` : [];
    const journalIds = of('journal').map(r => r.entity_id as string);
    const journalRows = journalIds.length ? await sql`select id, goal_id, local_date::text, mood, body_ciphertext, version, conflict_of, updated_at from journal_entries where id in ${sql(journalIds)} and user_id = ${req.userId}` : [];
    const chatIds = of('chat').map(r => r.entity_id as string);
    const chatRows = chatIds.length ? await sql`select id, conversation, role, body_ciphertext, status, suggestion_state, created_at from coach_messages where id in ${sql(chatIds)} and user_id = ${req.userId}` : [];
    const key = journalRows.length || chatRows.length ? await journalKey(sql, req.userId) : null;

    const alive = new Set(goals.map(g => g.id as string)), done = new Set(live.map(c => `${c.task_id}:${c.local_date}`));
    return {
      cursor: rows.length ? Number(rows[rows.length - 1]!.server_seq) : since,
      hasMore: rows.length === PULL_PAGE,
      goals: goals.map(g => ({ id: g.id, label: g.label, statement: g.statement, category: g.category, startDate: g.start_date, deadline: g.deadline, status: g.status, sourceAllianceId: g.source_alliance_id, colorIndex: g.color_index, routine: g.routine, realityNote: g.reality_note, feasibility: g.feasibility, updatedAt: ms(g.updated_at) })),
      tasks: tasks.map(t => ({ id: t.id, goalId: t.goal_id, title: t.title, priority: t.priority, timeOfDay: t.time_of_day, minutes: t.minutes, rationale: t.rationale, notes: t.notes, origin: t.origin, sharedTaskId: t.shared_task_id, position: t.position, deletedAt: ms(t.deleted_at), updatedAt: ms(t.updated_at) })),
      completions: live.map(c => ({ taskId: c.task_id, localDate: c.local_date, completedAt: ms(c.completed_at) })),
      removed: {
        goals: goalIds.filter(id => !alive.has(id)),
        completions: comps.filter(c => !done.has(`${c.entity_id}:${c.key}`)).map(c => ({ taskId: c.entity_id, localDate: c.key })),
      },
      settings: me?.settings ?? null,
      journal: journalRows.map(j => ({ id: j.id, goalId: j.goal_id, localDate: j.local_date, mood: j.mood, body: decryptText(key!, j.body_ciphertext), version: j.version, conflictOf: j.conflict_of, updatedAt: ms(j.updated_at) })),
      removedJournal: journalIds.filter(id => !journalRows.some(j => j.id === id)),
      chat: chatRows.map(c => {
        const body = JSON.parse(decryptText(key!, c.body_ciphertext)) as { text: string; suggestion: unknown };
        return { id: c.id, conversation: c.conversation, role: c.role, text: body.text, suggestion: body.suggestion ?? null, status: c.status, suggestionState: c.suggestion_state, createdAt: ms(c.created_at) };
      }),
      removedChat: chatIds.filter(id => !chatRows.some(c => c.id === id)),
    };
  });
}
