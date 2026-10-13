import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { uuidv7 } from '../src/util.js';

// Needs a real Postgres: TEST_DATABASE_URL=postgres://… npm test  (the database is wiped on every run)
const run = describe.skipIf(!process.env.TEST_DATABASE_URL);

run('api', () => {
  let app: FastifyInstance;
  let sql: typeof import('../src/db.js').sql;
  const mails: { to: string; text: string }[] = [];

  beforeAll(async () => {
    ({ sql } = await import('../src/db.js'));
    await sql.unsafe('drop schema public cascade; create schema public;');
    await (await import('../src/migrate.js')).migrate();
    (await import('../src/mailer.js')).setMailer(async m => { mails.push(m); });
    app = await (await import('../src/app.js')).buildApp();
  });
  afterAll(async () => { await app?.close(); await sql?.end(); });

  const call = (method: 'GET' | 'POST' | 'DELETE', url: string, body?: unknown, token?: string) =>
    app.inject({ method, url, payload: body as object | undefined, headers: token ? { authorization: `Bearer ${token}` } : {} });
  const signUp = async (email = `${uuidv7()}@example.com`, password = 'correct horse') => {
    const r = await call('POST', '/v1/auth/email/signup', { email, password, displayName: 'Sharon', timezone: 'Africa/Lagos' });
    expect(r.statusCode).toBe(200);
    return { email, password, ...(r.json() as { user: { id: string }; accessToken: string; refreshToken: string }) };
  };
  const mut = (entity: string, entityId: string, op: string, patch: object = {}, clientTs = Date.now()) => ({ id: uuidv7(), entity, entityId, op, patch, clientTs });
  const push = async (token: string, mutations: object[]) => (await call('POST', '/v1/sync/push', { mutations }, token)).json() as { accepted: string[]; rejected: Record<string, string> };
  const pull = async (token: string, since = 0) => (await call('GET', `/v1/sync?since=${since}`, undefined, token)).json();
  const goal = (over: object = {}) => ({ label: 'Half marathon', statement: 'Run a half marathon', startDate: '2026-10-13', deadline: '2027-02-09', ...over });

  describe('auth', () => {
    it('signs up, returns the profile and rejects a duplicate email', async () => {
      const a = await signUp('Sharon@Example.com');
      const me = await call('GET', '/v1/me', undefined, a.accessToken);
      expect(me.json().user).toMatchObject({ email: 'sharon@example.com', displayName: 'Sharon', coachName: 'Arc', homeTimezone: 'Africa/Lagos' });
      const dup = await call('POST', '/v1/auth/email/signup', { email: 'sharon@example.com', password: 'another pass', displayName: 'Sharon' });
      expect(dup.statusCode).toBe(409);
      expect(dup.json().error).toBe('email_taken');
    });

    it('validates input and rejects requests without a token', async () => {
      expect((await call('POST', '/v1/auth/email/signup', { email: 'nope', password: 'short', displayName: 'S' })).statusCode).toBe(400);
      expect((await call('GET', '/v1/me')).statusCode).toBe(401);
      expect((await call('GET', '/v1/me', undefined, 'garbage')).statusCode).toBe(401);
    });

    it('locks the account for 15 minutes after 5 wrong passwords', async () => {
      const a = await signUp();
      for (let i = 0; i < 4; i++) expect((await call('POST', '/v1/auth/email/login', { email: a.email, password: 'wrong-pass' })).statusCode).toBe(401);
      const fifth = await call('POST', '/v1/auth/email/login', { email: a.email, password: 'wrong-pass' });
      expect(fifth.statusCode).toBe(429);
      expect(fifth.json()).toMatchObject({ error: 'too_many_attempts', retryAfterSec: 900 });
      expect((await call('POST', '/v1/auth/email/login', { email: a.email, password: a.password })).statusCode).toBe(429);
      await sql`update users set locked_until = now() - interval '1 second' where id = ${a.user.id}`;
      expect((await call('POST', '/v1/auth/email/login', { email: a.email, password: a.password })).statusCode).toBe(200);
    });

    it('answers the same for an unknown email as for a wrong password', async () => {
      const r = await call('POST', '/v1/auth/email/login', { email: 'nobody@example.com', password: 'whatever1' });
      expect([r.statusCode, r.json().error]).toEqual([401, 'invalid_credentials']);
    });

    it('rotates refresh tokens and revokes the family when an old one is replayed', async () => {
      const a = await signUp();
      const r1 = await call('POST', '/v1/auth/refresh', { refreshToken: a.refreshToken });
      expect(r1.statusCode).toBe(200);
      const t1 = r1.json() as { accessToken: string; refreshToken: string };
      expect((await call('GET', '/v1/me', undefined, t1.accessToken)).statusCode).toBe(200);
      expect((await call('GET', '/v1/me', undefined, a.accessToken)).statusCode).toBe(401);       // rotated session
      expect((await call('POST', '/v1/auth/refresh', { refreshToken: a.refreshToken })).statusCode).toBe(401);  // replay
      expect((await call('POST', '/v1/auth/refresh', { refreshToken: t1.refreshToken })).statusCode).toBe(401); // family revoked
      expect((await call('GET', '/v1/me', undefined, t1.accessToken)).statusCode).toBe(401);
    });

    it('logs out', async () => {
      const a = await signUp();
      expect((await call('POST', '/v1/auth/logout', undefined, a.accessToken)).statusCode).toBe(204);
      expect((await call('GET', '/v1/me', undefined, a.accessToken)).statusCode).toBe(401);
      expect((await call('POST', '/v1/auth/refresh', { refreshToken: a.refreshToken })).statusCode).toBe(401);
    });

    it('resets a password with a single-use link and signs out other sessions', async () => {
      const a = await signUp();
      expect((await call('POST', '/v1/auth/password/forgot', { email: a.email })).statusCode).toBe(204);
      expect((await call('POST', '/v1/auth/password/forgot', { email: 'nobody@example.com' })).statusCode).toBe(204);
      const token = /\/reset\?token=(\S+)/.exec(mails.filter(m => m.to === a.email).at(-1)!.text)![1]!;
      expect((await call('GET', `/reset?token=${token}`)).statusCode).toBe(200);
      expect((await call('POST', '/v1/auth/password/reset', { token, password: 'brand new pass' })).statusCode).toBe(204);
      expect((await call('POST', '/v1/auth/password/reset', { token, password: 'second attempt' })).statusCode).toBe(400);
      expect((await call('GET', '/v1/me', undefined, a.accessToken)).statusCode).toBe(401);
      expect((await call('POST', '/v1/auth/email/login', { email: a.email, password: a.password })).statusCode).toBe(401);
      expect((await call('POST', '/v1/auth/email/login', { email: a.email, password: 'brand new pass' })).statusCode).toBe(200);
    });

    it('confirms an email from the link sent at sign-up', async () => {
      const a = await signUp();
      const token = /\/verify\?token=(\S+)/.exec(mails.find(m => m.to === a.email)!.text)![1]!;
      expect((await call('GET', `/verify?token=${token}`)).statusCode).toBe(200);
      expect((await call('GET', `/verify?token=${token}`)).statusCode).toBe(400);
      expect((await call('GET', '/v1/me', undefined, a.accessToken)).json().user.emailVerified).toBe(true);
    });

    it('deletes an account: signed out at once, email free again, rows purged after 30 days', async () => {
      const a = await signUp();
      await push(a.accessToken, [mut('goal', uuidv7(), 'upsert', goal())]);
      expect((await call('DELETE', '/v1/account', undefined, a.accessToken)).statusCode).toBe(204);
      expect((await call('GET', '/v1/me', undefined, a.accessToken)).statusCode).toBe(401);
      expect((await call('POST', '/v1/auth/refresh', { refreshToken: a.refreshToken })).statusCode).toBe(401);
      expect((await call('POST', '/v1/auth/email/login', { email: a.email, password: a.password })).statusCode).toBe(401);
      await signUp(a.email);
      const { purgeDeletedAccounts } = await import('../src/auth/routes.js');
      expect(await purgeDeletedAccounts()).toBe(0);
      await sql`update users set deleted_at = now() - interval '31 days' where id = ${a.user.id}`;
      expect(await purgeDeletedAccounts()).toBe(1);
      expect((await sql`select 1 from goals where user_id = ${a.user.id}`).length).toBe(0);
    });

    it('reports social sign-in as unavailable until client ids are configured', async () => {
      const r = await call('POST', '/v1/auth/google', { idToken: 'x'.repeat(40) });
      expect([r.statusCode, r.json().error]).toEqual([503, 'provider_not_configured']);
    });

    it('asks old app versions to upgrade', async () => {
      const r = await app.inject({ method: 'GET', url: '/healthz', headers: { 'x-platform': 'android', 'x-app-version': '0.9.0' } });
      expect(r.statusCode).toBe(426);
    });
  });

  describe('sync', () => {
    it('creates goals and tasks, is idempotent, and pulls them back with a cursor', async () => {
      const a = await signUp();
      const g = uuidv7(), t = uuidv7();
      const batch = [mut('goal', g, 'upsert', goal({ colorIndex: 1 })), mut('task', t, 'upsert', { goalId: g, title: 'Easy 3 km run', priority: 'High', timeOfDay: '06:45', origin: 'ai' })];
      expect(await push(a.accessToken, batch)).toEqual({ accepted: batch.map(m => m.id), rejected: {} });
      expect(await push(a.accessToken, batch)).toEqual({ accepted: batch.map(m => m.id), rejected: {} });   // retry
      const p = await pull(a.accessToken);
      expect(p.goals).toHaveLength(1);
      expect(p.goals[0]).toMatchObject({ id: g, deadline: '2027-02-09', startDate: '2026-10-13', colorIndex: 1, status: 'active' });
      expect(p.tasks[0]).toMatchObject({ id: t, goalId: g, title: 'Easy 3 km run', priority: 'High', origin: 'ai', deletedAt: null });
      expect(p.cursor).toBeGreaterThan(0);
      expect(await pull(a.accessToken, p.cursor)).toMatchObject({ cursor: p.cursor, goals: [], tasks: [], hasMore: false });
    });

    it('merges task edits per field and lets deletes win', async () => {
      const a = await signUp();
      const g = uuidv7(), t = uuidv7();
      await push(a.accessToken, [mut('goal', g, 'upsert', goal()), mut('task', t, 'upsert', { goalId: g, title: 'Read', priority: 'Low', notes: 'ch. 1' })]);
      await push(a.accessToken, [mut('task', t, 'upsert', { title: 'Read 20 pages' })]);      // device A
      await push(a.accessToken, [mut('task', t, 'upsert', { priority: 'High', notes: null })]); // device B
      let p = await pull(a.accessToken);
      expect(p.tasks[0]).toMatchObject({ title: 'Read 20 pages', priority: 'High', notes: null });
      await push(a.accessToken, [mut('task', t, 'delete')]);
      expect(await push(a.accessToken, [mut('task', t, 'upsert', { title: 'Back from the dead' })])).toMatchObject({ rejected: {} });
      p = await pull(a.accessToken);
      expect(p.tasks[0].title).toBe('Read 20 pages');
      expect(p.tasks[0].deletedAt).toBeTypeOf('number');
    });

    it('treats completions as a set and only lets a newer uncomplete remove one', async () => {
      const a = await signUp();
      const g = uuidv7(), t = uuidv7(), day = { localDate: '2026-10-13' };
      await push(a.accessToken, [mut('goal', g, 'upsert', goal()), mut('task', t, 'upsert', { goalId: g, title: 'Stretch' })]);
      await push(a.accessToken, [mut('completion', t, 'complete', day, 2_000), mut('completion', t, 'complete', day, 3_000)]);   // two devices
      let p = await pull(a.accessToken);
      expect(p.completions).toEqual([{ taskId: t, localDate: '2026-10-13', completedAt: 2_000 }]);
      await push(a.accessToken, [mut('completion', t, 'uncomplete', day, 1_000)]);   // older than the completion: ignored
      expect((await pull(a.accessToken)).completions).toHaveLength(1);
      await push(a.accessToken, [mut('completion', t, 'uncomplete', day, 5_000)]);
      p = await pull(a.accessToken, p.cursor);
      expect(p.completions).toEqual([]);
      expect(p.removed.completions).toEqual([{ taskId: t, localDate: '2026-10-13' }]);
    });

    it('enforces the free limit of 3 active goals, and archiving frees a slot', async () => {
      const a = await signUp();
      const ids = [uuidv7(), uuidv7(), uuidv7(), uuidv7()];
      const batch = ids.map(id => mut('goal', id, 'upsert', goal()));
      const r = await push(a.accessToken, batch);
      expect(r.accepted).toHaveLength(3);
      expect(r.rejected).toEqual({ [batch[3]!.id]: 'goal_limit' });
      expect((await push(a.accessToken, [batch[3]!])).rejected).toEqual({ [batch[3]!.id]: 'goal_limit' });   // replay keeps the verdict
      await push(a.accessToken, [mut('goal', ids[0]!, 'upsert', { status: 'archived' })]);
      expect((await push(a.accessToken, [mut('goal', ids[3]!, 'upsert', goal())])).rejected).toEqual({});
    });

    it('rejects edits to shared-plan tasks but still accepts completing them', async () => {
      const a = await signUp();
      const g = uuidv7(), t = uuidv7();
      await push(a.accessToken, [mut('goal', g, 'upsert', goal())]);
      await sql`insert into tasks (id, goal_id, user_id, title, origin) values (${t}, ${g}, ${a.user.id}, 'Group run', 'shared')`;
      const edit = mut('task', t, 'upsert', { title: 'Solo nap' }), del = mut('task', t, 'delete'), done = mut('completion', t, 'complete', { localDate: '2026-10-13' });
      expect(await push(a.accessToken, [edit, del, done])).toEqual({ accepted: [done.id], rejected: { [edit.id]: 'shared_task_readonly', [del.id]: 'shared_task_readonly' } });
      expect((await push(a.accessToken, [mut('task', uuidv7(), 'upsert', { goalId: g, title: 'Sneaky', origin: 'shared' })])).accepted).toEqual([]);
    });

    it("never touches another user's rows", async () => {
      const a = await signUp(), b = await signUp();
      const g = uuidv7();
      await push(a.accessToken, [mut('goal', g, 'upsert', goal())]);
      const steal = mut('goal', g, 'upsert', { label: 'Mine now' }), wipe = mut('goal', g, 'delete'), add = mut('task', uuidv7(), 'upsert', { goalId: g, title: 'Intruder' });
      expect((await push(b.accessToken, [steal, wipe, add])).rejected).toEqual({ [steal.id]: 'forbidden', [wipe.id]: 'forbidden', [add.id]: 'goal_missing' });
      expect((await pull(b.accessToken)).goals).toEqual([]);
      expect((await pull(a.accessToken)).goals[0].label).toBe('Half marathon');
    });

    it('keeps the user-picked deadline authoritative and refuses one before the start date', async () => {
      const a = await signUp();
      const g = uuidv7();
      await push(a.accessToken, [mut('goal', g, 'upsert', goal())]);
      const bad = mut('goal', g, 'upsert', { deadline: '2026-10-13' });
      expect((await push(a.accessToken, [bad])).rejected).toEqual({ [bad.id]: 'invalid' });
      await push(a.accessToken, [mut('goal', g, 'upsert', { deadline: '2027-06-01' })]);
      expect((await pull(a.accessToken)).goals[0].deadline).toBe('2027-06-01');
    });

    it('reports deleted goals and syncs settings', async () => {
      const a = await signUp();
      const g = uuidv7();
      await push(a.accessToken, [mut('goal', g, 'upsert', goal()), mut('settings', a.user.id, 'upsert', { dailyReminder: '08:00', reduceMotion: true })]);
      const first = await pull(a.accessToken);
      expect(first.settings).toEqual({ dailyReminder: '08:00', reduceMotion: true });
      await push(a.accessToken, [mut('goal', g, 'delete')]);
      const p = await pull(a.accessToken, first.cursor);
      expect(p.removed.goals).toEqual([g]);
      expect(p.settings).toBeNull();
    });
  });
});
