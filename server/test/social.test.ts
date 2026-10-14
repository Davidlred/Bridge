import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { uuidv7 } from '../src/util.js';

// Moderation model: "hide" any message containing "spam-me", allow the rest.
vi.mock('../src/ai/replicate.js', async orig => {
  const real = await orig<typeof import('../src/ai/replicate.js')>();
  return { ...real, runText: vi.fn(async (_m: string, input: Record<string, unknown>) => (String(input.prompt).includes('spam-me') ? 'hide' : 'allow')) };
});

const run = describe.skipIf(!process.env.TEST_DATABASE_URL);

run('community, alliances and chat', () => {
  let app: FastifyInstance;
  let sql: typeof import('../src/db.js').sql;
  const pushes: { userId: string; title: string }[] = [];

  beforeAll(async () => {
    ({ sql } = await import('../src/db.js'));
    await sql.unsafe('drop schema public cascade; create schema public;');
    await (await import('../src/migrate.js')).migrate();
    (await import('../src/mailer.js')).setMailer(async () => {});
    (await import('../src/push.js')).capturePushes((userId, p) => pushes.push({ userId, title: p.title }));
    app = await (await import('../src/app.js')).buildApp();
    await app.ready();
  });
  afterAll(async () => { await app?.close(); await sql?.end(); });

  type U = { user: { id: string }; accessToken: string; name: string };
  const signUp = async (name = 'Sharon'): Promise<U> => ({ ...(await app.inject({ method: 'POST', url: '/v1/auth/email/signup', payload: { email: `${uuidv7()}@example.com`, password: 'correct horse', displayName: name } })).json(), name });
  const h = (u: U) => ({ authorization: `Bearer ${u.accessToken}` });
  const call = (u: U, method: 'GET' | 'POST' | 'PUT' | 'DELETE', url: string, payload?: object) => app.inject({ method, url, headers: h(u), payload });
  const push = (u: U, mutations: object[]) => call(u, 'POST', '/v1/sync/push', { mutations }).then(r => r.json() as { accepted: string[]; rejected: Record<string, string> });
  const pull = (u: U, since = 0) => call(u, 'GET', `/v1/sync?since=${since}`).then(r => r.json());
  const mut = (entity: string, entityId: string, op: string, patch: object = {}) => ({ id: uuidv7(), entity, entityId, op, patch, clientTs: Date.now() });
  /** A goal with one task, and a streak of [days] (completions written straight into the database). */
  const goalWithStreak = async (u: U, days: number, category = 'fitness') => {
    const g = uuidv7(), t = uuidv7();
    await push(u, [mut('goal', g, 'upsert', { label: 'Half marathon', statement: 'Run a half marathon in under 2 hours', startDate: '2026-01-01', deadline: '2030-01-01', category }),
                   mut('task', t, 'upsert', { goalId: g, title: 'Easy run 5 km', priority: 'High' })]);
    if (days) await sql`insert into task_completions (task_id, user_id, local_date, completed_at)
                        select ${t}, ${u.user.id}, ((now() at time zone 'UTC') - interval '3 hours')::date - d, now() from generate_series(0, ${days - 1}) d`;
    return { g, t };
  };

  describe('community', () => {
    it('opens at a 7-day streak and stays open after the streak breaks', async () => {
      const a = await signUp();
      const { g, t } = await goalWithStreak(a, 6);
      let r = (await call(a, 'GET', `/v1/community?goalId=${g}`)).json();
      expect(r).toMatchObject({ unlocked: false, streak: 6, needed: 7, posts: [] });
      await sql`insert into task_completions (task_id, user_id, local_date, completed_at) values (${t}, ${a.user.id}, ((now() at time zone 'UTC') - interval '3 hours')::date - 6, now())`;
      expect((await call(a, 'GET', `/v1/community?goalId=${g}`)).json()).toMatchObject({ unlocked: true, streak: 7 });
      await sql`delete from task_completions where task_id = ${t}`;
      expect((await call(a, 'GET', `/v1/community?goalId=${g}`)).json()).toMatchObject({ unlocked: true, streak: 0 });
    });

    it('posts progress within the sharing setting, checks streak claims, and widens a quiet feed', async () => {
      const a = await signUp('Jonas Keller'), b = await signUp();
      const ga = await goalWithStreak(a, 9, 'language'), gb = await goalWithStreak(b, 7, 'fitness');
      expect((await call(a, 'POST', '/v1/community/posts', { goalId: ga.g, kind: 'streak', value: 30 })).json().error).toBe('streak_mismatch');
      expect((await call(a, 'POST', '/v1/community/posts', { goalId: ga.g, kind: 'streak', value: 9 })).statusCode).toBe(201);
      expect((await call(a, 'POST', '/v1/community/posts', { goalId: ga.g, kind: 'streak', value: 9 })).statusCode).toBe(200);    // once only
      // Default sharing is "streaks only": milestones aren't posted until the user allows them.
      expect((await call(a, 'POST', '/v1/community/posts', { goalId: ga.g, kind: 'milestone', value: 1 })).statusCode).toBe(204);
      await push(a, [mut('settings', a.user.id, 'upsert', { share: 'all' })]);
      expect((await call(a, 'POST', '/v1/community/posts', { goalId: ga.g, kind: 'milestone', value: 1 })).statusCode).toBe(201);
      await push(a, [mut('settings', a.user.id, 'upsert', { share: 'nothing' })]);
      expect((await call(a, 'POST', '/v1/community/posts', { goalId: ga.g, kind: 'day_complete' })).statusCode).toBe(204);
      // b's category (fitness) is empty, so the feed widens to everyone and says so. Only first names are shown.
      const feed = (await call(b, 'GET', `/v1/community?goalId=${gb.g}`)).json();
      expect(feed.widened).toBe(true);
      expect(feed.posts.map((p: { name: string; kind: string }) => [p.name, p.kind])).toEqual([['Jonas', 'milestone'], ['Jonas', 'streak']]);
    });

    it('reacts, reports (hidden for the reporter, for everyone at 3) and blocks', async () => {
      const [a, b, c, d] = [await signUp('Ana'), await signUp('Bo'), await signUp('Cy'), await signUp('Di')];
      const ga = await goalWithStreak(a, 7);
      const gs = await Promise.all([b, c, d].map(u => goalWithStreak(u, 7)));
      const { id } = (await call(a, 'POST', '/v1/community/posts', { goalId: ga.g, kind: 'streak', value: 7 })).json();
      expect((await call(b, 'PUT', `/v1/community/posts/${id}/reactions/keepGoing`)).statusCode).toBe(204);
      const seen = (await call(c, 'GET', `/v1/community?goalId=${gs[1]!.g}`)).json().posts.find((p: { id: string }) => p.id === id);
      expect(seen.reactions).toEqual({ respect: 0, keepGoing: 1, inspired: 0 });
      await call(b, 'POST', '/v1/reports', { targetKind: 'post', targetId: id, reason: 'spam' });
      expect((await call(b, 'GET', `/v1/community?goalId=${gs[0]!.g}`)).json().posts.some((p: { id: string }) => p.id === id)).toBe(false);
      expect((await call(c, 'GET', `/v1/community?goalId=${gs[1]!.g}`)).json().posts.some((p: { id: string }) => p.id === id)).toBe(true);
      await call(c, 'POST', '/v1/reports', { targetKind: 'post', targetId: id, reason: 'spam' });
      await call(d, 'POST', '/v1/reports', { targetKind: 'post', targetId: id, reason: 'spam' });
      expect((await call(a, 'GET', `/v1/community?goalId=${ga.g}`)).json().posts.some((p: { id: string }) => p.id === id)).toBe(false);
      // Blocks work both ways
      const { id: id2 } = (await call(a, 'POST', '/v1/community/posts', { goalId: ga.g, kind: 'streak', value: 6 })).json();
      expect(id2).toBeTruthy();
      await call(d, 'PUT', `/v1/blocks/${a.user.id}`);
      expect((await call(d, 'GET', `/v1/community?goalId=${gs[2]!.g}`)).json().posts.some((p: { id: string }) => p.id === id2)).toBe(false);
    });
  });

  describe('alliances', () => {
    it('needs an open Community to create one, and rejects bad names', async () => {
      const a = await signUp();
      const locked = await goalWithStreak(a, 2);
      expect((await call(a, 'POST', '/v1/alliances', { name: 'Dawn Runners', planMode: 'own', goalId: locked.g })).json().error).toBe('community_locked');
      const open = await goalWithStreak(a, 7);
      expect((await call(a, 'POST', '/v1/alliances', { name: 'Nazi runners', planMode: 'own', goalId: open.g })).json().error).toBe('name_not_allowed');
      const r = (await call(a, 'POST', '/v1/alliances', { name: 'Dawn Runners', planMode: 'own', goalId: open.g })).json();
      expect(r.alliance).toMatchObject({ name: 'Dawn Runners', planMode: 'own', cap: 15, role: 'owner', memberCount: 1 });
      expect(r.alliance.inviteCode).toMatch(/^DAWN-\d{4}$/);
    });

    it('shares a plan: preview, join (goal and tasks arrive by sync), read-only tasks, next-morning updates, apply now', async () => {
      const lead = await signUp('Ms Okafor'), kid = await signUp('Tunde');
      const lg = await goalWithStreak(lead, 7, 'study');
      const t2 = uuidv7();
      await push(lead, [mut('task', t2, 'upsert', { goalId: lg.g, title: 'Past paper — 1 question', priority: 'Medium', timeOfDay: '7:00 PM' })]);
      const created = (await call(lead, 'POST', '/v1/alliances', { name: 'Calc Prep 12B', planMode: 'shared', goalId: lg.g })).json().alliance;
      const preview = (await call(kid, 'GET', `/v1/alliances/preview/${created.inviteCode}`)).json().preview;
      expect(preview).toMatchObject({ name: 'Calc Prep 12B', planMode: 'shared', lead: 'Ms', members: 1, spotsLeft: 14, goal: 'Run a half marathon in under 2 hours', deadline: '2030-01-01' });
      expect(preview.tasks.map((t: { title: string }) => t.title)).toEqual(['Easy run 5 km', 'Past paper — 1 question']);
      expect(preview.pace.groupDay).toBe(1);
      const joined = (await call(kid, 'POST', '/v1/alliances/join', { code: created.inviteCode.toLowerCase() })).json().alliance;
      expect(joined).toMatchObject({ role: 'member', memberCount: 2, shared: { lead: 'Ms', me: { done: 0, total: 2 } } });
      let p = await pull(kid);
      expect(p.goals).toEqual([expect.objectContaining({ deadline: '2030-01-01', sourceAllianceId: created.id })]);
      expect(p.tasks.map((t: { title: string; origin: string }) => [t.title, t.origin])).toEqual([['Easy run 5 km', 'shared'], ['Past paper — 1 question', 'shared']]);
      // Members can tick shared tasks but not change them
      const kidTask = p.tasks[0].id;
      const edit = mut('task', kidTask, 'upsert', { title: 'Nap' });
      expect((await push(kid, [edit])).rejected).toEqual({ [edit.id]: 'shared_task_readonly' });
      expect((await push(kid, [mut('completion', kidTask, 'complete', { localDate: '2026-10-14' })])).rejected).toEqual({});
      // The lead renames a task: members keep today's checklist until their next local morning
      await push(lead, [mut('task', t2, 'upsert', { title: 'Past paper — 2 questions' })]);
      p = await pull(kid, p.cursor);
      expect(p.tasks).toEqual([]);
      await sql`update shared_plan_tasks set effective_from = effective_from - 1 where alliance_id = ${created.id} and effective_from > current_date - 1`;  // "tomorrow" arrives
      p = await pull(kid, p.cursor);
      expect(p.tasks.map((t: { title: string }) => t.title)).toEqual(['Past paper — 2 questions']);
      // "Apply now" for a correction changes only what the member hasn't ticked today
      const today = (await sql`select to_char((now() at time zone 'UTC') - interval '3 hours', 'YYYY-MM-DD') as d`)[0]!.d;
      const tasks = (await pull(kid)).tasks as { id: string; title: string }[];
      const easy = tasks.find(t => t.title === 'Easy run 5 km')!;
      await push(kid, [mut('completion', easy.id, 'complete', { localDate: today })]);
      await push(lead, [mut('task', lg.t, 'upsert', { title: 'Easy run 6 km' }), mut('task', t2, 'upsert', { title: 'Past paper — 3 questions' })]);
      expect((await call(kid, 'POST', `/v1/alliances/${created.id}/apply-now`)).statusCode).toBe(403);
      expect((await call(lead, 'POST', `/v1/alliances/${created.id}/apply-now`)).statusCode).toBe(204);
      const after = ((await pull(kid)).tasks as { title: string }[]).map(t => t.title).sort();
      expect(after).toEqual(['Easy run 5 km', 'Past paper — 3 questions']);
    });

    it('turns away bad codes, members, full alliances, ended plans and joins over the goal limit', async () => {
      const lead = await signUp(), other = await signUp();
      const lg = await goalWithStreak(lead, 7);
      const own = (await call(lead, 'POST', '/v1/alliances', { name: 'Own Plan Crew', planMode: 'own', goalId: lg.g })).json().alliance;
      const shared = (await call(lead, 'POST', '/v1/alliances', { name: 'Shared Crew', planMode: 'shared', goalId: lg.g })).json().alliance;
      expect((await call(other, 'POST', '/v1/alliances/join', { code: 'nope' })).json().error).toBe('invalid_code_format');
      expect((await call(other, 'POST', '/v1/alliances/join', { code: 'ZZZZ-0000' })).json().error).toBe('invalid_code');
      expect((await call(lead, 'POST', '/v1/alliances/join', { code: own.inviteCode })).json()).toMatchObject({ error: 'already_member', name: 'Own Plan Crew' });
      expect((await call(other, 'POST', '/v1/alliances/join', { code: own.inviteCode })).json().error).toBe('goal_required');
      // Fill the own-plan alliance to 15 with stand-in members
      for (let i = 0; i < 14; i++) {
        const u = uuidv7(), g = uuidv7();
        await sql`insert into users (id, display_name) values (${u}, 'Filler')`;
        await sql`insert into goals (id, user_id, label, statement, start_date, deadline) values (${g}, ${u}, 'x', 'x', '2026-01-01', '2030-01-01')`;
        await sql`insert into alliance_members (alliance_id, user_id, goal_id, start_date) values (${own.id}, ${u}, ${g}, '2026-01-01')`;
      }
      const og = await goalWithStreak(other, 0);
      expect((await call(other, 'POST', '/v1/alliances/join', { code: own.inviteCode, goalId: og.g })).json()).toMatchObject({ error: 'alliance_full', cap: 15 });
      // Already at 3 goals: joining a shared plan (which adds a goal) is blocked
      await goalWithStreak(other, 0); await goalWithStreak(other, 0);
      expect((await call(other, 'POST', '/v1/alliances/join', { code: shared.inviteCode })).json().error).toBe('goal_limit');
      await sql`update goals set deadline = current_date - 1, start_date = current_date - 10 where id = ${lg.g}`;
      expect((await call(other, 'GET', `/v1/alliances/preview/${shared.inviteCode}`)).json().error).toBe('plan_ended');
    });

    it('hands the lead role to the longest-standing member, and members leave with a personal goal', async () => {
      const lead = await signUp('Lead'), m1 = await signUp('First'), m2 = await signUp('Second');
      const lg = await goalWithStreak(lead, 7);
      const a = (await call(lead, 'POST', '/v1/alliances', { name: 'Hand Over', planMode: 'shared', goalId: lg.g })).json().alliance;
      await call(m1, 'POST', '/v1/alliances/join', { code: a.inviteCode });
      await call(m2, 'POST', '/v1/alliances/join', { code: a.inviteCode });
      expect((await call(lead, 'POST', `/v1/alliances/${a.id}/leave`)).statusCode).toBe(204);
      const view = (await call(m1, 'GET', `/v1/alliances/${a.id}`)).json().alliance;
      expect(view.role).toBe('owner');
      // The new lead's copy is now the source of the plan, so it's no longer read-only for them
      expect((await pull(m1)).tasks.every((t: { origin: string }) => t.origin === 'user')).toBe(true);
      await call(m2, 'POST', `/v1/alliances/${a.id}/leave`);
      const p = await pull(m2);
      expect(p.goals[0].sourceAllianceId).toBeNull();
      expect(p.tasks.every((t: { origin: string }) => t.origin === 'user')).toBe(true);
    });

    it('counts the collective streak only on days every active member did something', async () => {
      const a = await signUp('Ana'), b = await signUp('Bo');
      const ga = await goalWithStreak(a, 7);
      const al = (await call(a, 'POST', '/v1/alliances', { name: 'Pair Up', planMode: 'own', goalId: ga.g })).json().alliance;
      const gb = await goalWithStreak(b, 2);
      await call(b, 'POST', '/v1/alliances/join', { code: al.inviteCode, goalId: gb.g });
      // A new alliance's streak starts on its first day
      expect((await call(a, 'GET', `/v1/alliances/${al.id}`)).json().alliance.streak).toBe(1);
      // Started 10 days ago: a's 7 days count (b only needed from the day b joined)
      await sql`update alliances set start_date = start_date - 10 where id = ${al.id}`;
      await sql`update alliance_members set start_date = start_date - 10 where alliance_id = ${al.id} and user_id = ${a.user.id}`;
      expect((await call(a, 'GET', `/v1/alliances/${al.id}`)).json().alliance.streak).toBe(7);
      // Had b joined 5 days ago, the days b missed break it
      await sql`update alliance_members set start_date = start_date - 5 where alliance_id = ${al.id} and user_id = ${b.user.id}`;
      const v = (await call(a, 'GET', `/v1/alliances/${al.id}`)).json().alliance;
      expect(v.streak).toBe(2);
      expect(v.members.map((m: { name: string; away: boolean }) => [m.name, m.away])).toEqual([['Ana', false], ['Bo', false]]);
    });
  });

  describe('alliance chat', () => {
    it('posts once per client id, filters slurs, hides what moderation flags, and pushes @mentions (3 a day at most)', async () => {
      const a = await signUp('Ana'), b = await signUp('Bo');
      const ga = await goalWithStreak(a, 7);
      const al = (await call(a, 'POST', '/v1/alliances', { name: 'Chatters', planMode: 'own', goalId: ga.g })).json().alliance;
      const gb = await goalWithStreak(b, 0);
      await call(b, 'POST', '/v1/alliances/join', { code: al.inviteCode, goalId: gb.g });
      const clientId = uuidv7();
      expect((await call(a, 'POST', `/v1/alliances/${al.id}/messages`, { clientId, body: 'Morning @bo, run at 7?' })).statusCode).toBe(201);
      expect((await call(a, 'POST', `/v1/alliances/${al.id}/messages`, { clientId, body: 'Morning @bo, run at 7?' })).statusCode).toBe(200);
      expect((await call(a, 'POST', `/v1/alliances/${al.id}/messages`, { clientId: uuidv7(), body: 'you retard' })).json().error).toBe('message_blocked');
      await call(a, 'POST', `/v1/alliances/${al.id}/messages`, { clientId: uuidv7(), body: 'buy followers spam-me' });
      await new Promise(r => setTimeout(r, 50));                                // moderation runs just after posting
      const list = (await call(b, 'GET', `/v1/alliances/${al.id}/messages`)).json();
      expect(list.notEncrypted).toBe(true);
      expect(list.messages.map((m: { body: string; name: string }) => [m.name, m.body])).toEqual([['Ana', 'Morning @bo, run at 7?']]);
      expect(pushes.filter(p => p.userId === b.user.id).map(p => p.title)).toEqual(['Ana mentioned you in Chatters']);
      for (let i = 0; i < 4; i++) await call(a, 'POST', `/v1/alliances/${al.id}/messages`, { clientId: uuidv7(), body: `@Bo ping ${i}` });
      expect(pushes.filter(p => p.userId === b.user.id)).toHaveLength(3);
      // Outsiders can't read or post
      const outsider = await signUp();
      expect((await call(outsider, 'GET', `/v1/alliances/${al.id}/messages`)).statusCode).toBe(404);
    });

    it('delivers new messages over the WebSocket after authenticating with the first frame', async () => {
      const a = await signUp('Ana'), b = await signUp('Bo');
      const ga = await goalWithStreak(a, 7);
      const al = (await call(a, 'POST', '/v1/alliances', { name: 'Live Wire', planMode: 'own', goalId: ga.g })).json().alliance;
      await call(b, 'POST', '/v1/alliances/join', { code: al.inviteCode, goalId: (await goalWithStreak(b, 0)).g });
      const ws = await app.injectWS('/v1/rt');
      const frames: { type: string; message?: { body: string; mine: boolean }; allianceId?: string }[] = [];
      ws.on('message', (raw: Buffer) => frames.push(JSON.parse(raw.toString())));
      ws.send(JSON.stringify({ type: 'auth', token: b.accessToken }));
      for (let i = 0; i < 40 && !frames.some(f => f.type === 'ready'); i++) await new Promise(r => setTimeout(r, 25));
      await call(a, 'POST', `/v1/alliances/${al.id}/messages`, { clientId: uuidv7(), body: 'Saturday long run?' });
      for (let i = 0; i < 40 && !frames.some(f => f.type === 'message'); i++) await new Promise(r => setTimeout(r, 25));
      expect(frames.find(f => f.type === 'message')).toMatchObject({ allianceId: al.id, message: { body: 'Saturday long run?', mine: false } });
      ws.terminate();
      // A bad token is refused
      const bad = await app.injectWS('/v1/rt');
      const closed = new Promise<number>(r => bad.on('close', code => r(code)));
      bad.send(JSON.stringify({ type: 'auth', token: 'nope' }));
      expect(await closed).toBe(4401);
    });

    it('registers a device for push', async () => {
      const a = await signUp();
      const id = uuidv7();
      expect((await call(a, 'PUT', '/v1/devices', { id, platform: 'android', pushToken: 'fcm-token-123456', timezone: 'Africa/Lagos' })).statusCode).toBe(204);
      expect((await sql`select push_token from devices where id = ${id}`)[0]!.push_token).toBe('fcm-token-123456');
      expect((await sql`select home_timezone from users where id = ${a.user.id}`)[0]!.home_timezone).toBe('Africa/Lagos');
    });
  });
});
