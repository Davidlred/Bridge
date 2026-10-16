import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { uuidv7 } from '../src/util.js';

vi.mock('../src/ai/replicate.js', async orig => ({ ...(await orig<typeof import('../src/ai/replicate.js')>()), runText: vi.fn(async () => 'allow') }));

const run = describe.skipIf(!process.env.TEST_DATABASE_URL);

run('encrypted direct messages (relay)', () => {
  let app: FastifyInstance;
  let sql: typeof import('../src/db.js').sql;
  const pushes: { userId: string; title: string; body?: string }[] = [];

  beforeAll(async () => {
    ({ sql } = await import('../src/db.js'));
    await sql.unsafe('drop schema public cascade; create schema public;');
    await (await import('../src/migrate.js')).migrate();
    (await import('../src/mailer.js')).setMailer(async () => {});
    (await import('../src/push.js')).capturePushes((userId, p) => pushes.push({ userId, title: p.title, body: p.body }));
    app = await (await import('../src/app.js')).buildApp();
    await app.ready();
  });
  afterAll(async () => { await app?.close(); await sql?.end(); });

  type U = { user: { id: string }; accessToken: string; device: string };
  const key = () => Buffer.from(uuidv7() + uuidv7()).toString('base64').slice(0, 43);
  const signUp = async (name: string): Promise<U> => ({ ...(await app.inject({ method: 'POST', url: '/v1/auth/email/signup', payload: { email: `${uuidv7()}@example.com`, password: 'correct horse', displayName: name } })).json(), device: uuidv7() });
  const call = (u: U, method: 'GET' | 'POST' | 'PUT', url: string, payload?: object) => app.inject({ method, url, headers: { authorization: `Bearer ${u.accessToken}` }, payload });
  const mut = (entity: string, entityId: string, op: string, patch: object = {}) => ({ id: uuidv7(), entity, entityId, op, patch, clientTs: Date.now() });
  const register = async (u: U, device = u.device) => {
    expect((await call(u, 'PUT', `/v1/e2e/devices/${device}`, { curve25519: key(), ed25519: key() })).statusCode).toBe(204);
    return (await call(u, 'POST', `/v1/e2e/devices/${device}/keys`, { oneTimeKeys: [{ id: 'a', key: key() }, { id: 'b', key: key() }], fallback: { id: 'f', key: key() } })).json();
  };
  /** Two people in one alliance (Community opened with a 7-day streak). */
  const pair = async () => {
    const a = await signUp('Ana'), b = await signUp('Bo');
    for (const u of [a, b]) {
      const g = uuidv7(), t = uuidv7();
      await call(u, 'POST', '/v1/sync/push', { mutations: [mut('goal', g, 'upsert', { label: 'Run', statement: 'Run a half marathon', startDate: '2026-01-01', deadline: '2030-01-01' }),
        mut('task', t, 'upsert', { goalId: g, title: 'Run', priority: 'High' })] });
      await sql`insert into task_completions (task_id, user_id, local_date, completed_at) select ${t}, ${u.user.id}, current_date - d, now() from generate_series(0, 7) d`;
      (u as U & { goal: string }).goal = g;
    }
    const al = (await call(a, 'POST', '/v1/alliances', { name: 'Pen Pals', planMode: 'own', goalId: (a as U & { goal: string }).goal })).json().alliance;
    await call(b, 'POST', '/v1/alliances/join', { code: al.inviteCode, goalId: (b as U & { goal: string }).goal });
    return { a, b, al };
  };

  it('publishes device keys and hands each one-time key out once, then the fallback', async () => {
    const { a, b } = await pair();
    expect(await register(a)).toEqual({ oneTimeKeys: 2 });
    await register(b);
    const devices = (await call(b, 'GET', `/v1/e2e/users/${a.user.id}/devices`)).json().devices;
    expect(devices).toEqual([expect.objectContaining({ deviceId: a.device })]);
    const claims = await Promise.all([1, 2, 3].map(() => call(b, 'POST', '/v1/e2e/claim', { deviceIds: [a.device] }).then(r => r.json().keys[0])));
    expect(claims.map(c => c.keyId).sort()).toEqual(['a', 'b', 'f']);
    expect(claims.find(c => c.keyId === 'f').fallback).toBe(true);
    // Someone else can't take over a registered device id
    expect((await call(b, 'PUT', `/v1/e2e/devices/${a.device}`, { curve25519: key(), ed25519: key() })).statusCode).toBe(409);
  });

  it('relays ciphertext to every device, once, with a content-free push; acknowledged envelopes are gone', async () => {
    const { a, b } = await pair();
    await register(a); await register(b);
    const aTablet = uuidv7(); await register(a, aTablet);
    const clientId = uuidv7();
    const send = () => call(a, 'POST', '/v1/e2e/messages', { to: b.user.id, fromDevice: a.device, clientId,
      envelopes: [{ deviceId: b.device, type: 0, body: 'CIPHERTEXT-FOR-BO' }, { deviceId: aTablet, type: 0, body: 'CIPHERTEXT-FOR-ANA-TABLET' }, { deviceId: uuidv7(), type: 1, body: 'nobody' }] });
    expect((await send()).json()).toEqual({ delivered: 1 });
    expect((await send()).json()).toEqual({ delivered: 0 });                                  // same clientId: not again
    expect(pushes.filter(p => p.userId === b.user.id)).toEqual([{ userId: b.user.id, title: 'New message', body: undefined }]);
    const inbox = (await call(b, 'GET', `/v1/e2e/inbox?deviceId=${b.device}`)).json().envelopes;
    expect(inbox).toEqual([expect.objectContaining({ from: a.user.id, fromDevice: a.device, peer: a.user.id, type: 0, body: 'CIPHERTEXT-FOR-BO', clientId })]);
    const tablet = (await call(a, 'GET', `/v1/e2e/inbox?deviceId=${aTablet}`)).json().envelopes;
    expect(tablet).toEqual([expect.objectContaining({ peer: b.user.id, body: 'CIPHERTEXT-FOR-ANA-TABLET' })]);   // the sender's other device gets a copy
    await call(b, 'POST', '/v1/e2e/inbox/ack', { deviceId: b.device, ids: inbox.map((e: { id: string }) => e.id) });
    expect((await call(b, 'GET', `/v1/e2e/inbox?deviceId=${b.device}`)).json().envelopes).toEqual([]);
    // Someone else's device inbox is off limits
    expect((await call(a, 'GET', `/v1/e2e/inbox?deviceId=${b.device}`)).statusCode).toBe(404);
  });

  it('only allows messages between alliance mates who have not blocked each other', async () => {
    const { a, b } = await pair();
    const stranger = await signUp('Cy');
    await register(a); await register(b); await register(stranger);
    expect((await call(stranger, 'GET', `/v1/e2e/users/${a.user.id}/devices`)).statusCode).toBe(403);
    expect((await call(stranger, 'POST', '/v1/e2e/claim', { deviceIds: [a.device] })).json().keys).toEqual([]);
    expect((await call(a, 'GET', '/v1/e2e/contacts')).json().contacts).toEqual([{ userId: b.user.id, name: 'Bo', ready: true }]);
    await call(b, 'PUT', `/v1/blocks/${a.user.id}`);
    expect((await call(a, 'POST', '/v1/e2e/messages', { to: b.user.id, fromDevice: a.device, clientId: uuidv7(), envelopes: [{ deviceId: b.device, type: 1, body: 'hi' }] })).json().error).toBe('cannot_message');
    expect((await call(a, 'GET', '/v1/e2e/contacts')).json().contacts).toEqual([]);
  });

  it('removes keys and waiting messages when the account is deleted', async () => {
    const { a, b } = await pair();
    await register(a); await register(b);
    await call(a, 'POST', '/v1/e2e/messages', { to: b.user.id, fromDevice: a.device, clientId: uuidv7(), envelopes: [{ deviceId: b.device, type: 0, body: 'x'.repeat(30) }] });
    await app.inject({ method: 'DELETE', url: '/v1/account', headers: { authorization: `Bearer ${a.accessToken}` } });
    expect((await sql`select count(*)::int as n from e2e_devices where user_id = ${a.user.id}`)[0]!.n).toBe(0);
    expect((await sql`select count(*)::int as n from e2e_envelopes where sender_user = ${a.user.id}`)[0]!.n).toBe(0);
  });
});
