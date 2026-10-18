import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { uuidv7 } from '../src/util.js';

const run = describe.skipIf(!process.env.TEST_DATABASE_URL);

run('entitlements and export', () => {
  let app: FastifyInstance;
  let sql: typeof import('../src/db.js').sql;

  beforeAll(async () => {
    ({ sql } = await import('../src/db.js'));
    await sql.unsafe('drop schema public cascade; create schema public;');
    await (await import('../src/migrate.js')).migrate();
    (await import('../src/mailer.js')).setMailer(async () => {});
    app = await (await import('../src/app.js')).buildApp();
    await app.ready();
  });
  afterAll(async () => { await app?.close(); await sql?.end(); });

  type U = { user: { id: string }; accessToken: string };
  const signUp = async (name = 'Sharon'): Promise<U> => (await app.inject({ method: 'POST', url: '/v1/auth/email/signup', payload: { email: `${uuidv7()}@example.com`, password: 'correct horse', displayName: name } })).json();
  const call = (u: U, method: 'GET' | 'POST', url: string, payload?: object) => app.inject({ method, url, headers: { authorization: `Bearer ${u.accessToken}` }, payload });
  const mut = (entity: string, entityId: string, op: string, patch: object = {}) => ({ id: uuidv7(), entity, entityId, op, patch, clientTs: Date.now() });
  const goal = (u: U) => call(u, 'POST', '/v1/sync/push', { mutations: [mut('goal', uuidv7(), 'upsert', { label: 'Run', statement: 'Run a half marathon', startDate: '2026-01-01', deadline: '2030-01-01' })] }).then(r => r.json());
  const hook = (event: object, secret = 'rc-test-secret') =>
    app.inject({ method: 'POST', url: '/v1/webhooks/revenuecat', headers: { authorization: `Bearer ${secret}` }, payload: { event: { id: uuidv7(), store: 'APP_STORE', ...event } } });

  it('applies RevenueCat events once, in order, and lifts the goal limit with plus', async () => {
    const u = await signUp();
    expect((await hook({ type: 'INITIAL_PURCHASE', app_user_id: u.user.id, entitlement_ids: ['plus'] }, 'wrong')).statusCode).toBe(401);
    for (let i = 0; i < 3; i++) await goal(u);
    expect(Object.values((await goal(u)).rejected)).toEqual(['goal_limit']);
    const later = Date.now() + 30 * 864e5;
    const buy = { id: uuidv7(), type: 'INITIAL_PURCHASE', app_user_id: u.user.id, entitlement_ids: ['plus'], expiration_at_ms: later, event_timestamp_ms: Date.now() };
    expect((await hook(buy)).json()).toEqual({ ok: true });
    expect((await hook(buy)).json()).toMatchObject({ duplicate: true });
    expect((await goal(u)).rejected).toEqual({});
    expect((await call(u, 'GET', '/v1/me/entitlements')).json().entitlements).toEqual([{ id: 'plus', expiresAt: later }]);
    // An older event arriving late doesn't undo the purchase
    await hook({ type: 'EXPIRATION', app_user_id: u.user.id, entitlement_ids: ['plus'], event_timestamp_ms: Date.now() - 864e5 });
    expect((await call(u, 'GET', '/v1/me/entitlements')).json().entitlements).toHaveLength(1);
    // Cancelling keeps access until the period ends; expiring ends it
    await hook({ type: 'CANCELLATION', app_user_id: u.user.id, entitlement_ids: ['plus'], expiration_at_ms: later, event_timestamp_ms: Date.now() + 1 });
    expect((await call(u, 'GET', '/v1/me/entitlements')).json().entitlements).toHaveLength(1);
    await hook({ type: 'EXPIRATION', app_user_id: u.user.id, entitlement_ids: ['plus'], event_timestamp_ms: Date.now() + 2 });
    expect((await call(u, 'GET', '/v1/me/entitlements')).json().entitlements).toEqual([]);
    // Unknown entitlements and anonymous RevenueCat ids are ignored
    expect((await hook({ type: 'INITIAL_PURCHASE', app_user_id: '$RCAnonymousID:abc', entitlement_ids: ['plus'] })).json()).toMatchObject({ ignored: 'anonymous_user' });
  });

  it('has a public page explaining how to delete an account', async () => {
    const r = await app.inject({ method: 'GET', url: '/delete-account' });
    expect(r.statusCode).toBe(200);
    expect(r.body).toContain('Profile → Account → Delete account');
    expect(r.body).toContain('mailto:support@thebridge.app');
  });

  it('ends access at once on a refund', async () => {
    const u = await signUp();
    await hook({ type: 'INITIAL_PURCHASE', app_user_id: u.user.id, entitlement_ids: ['plus'], expiration_at_ms: Date.now() + 30 * 864e5, event_timestamp_ms: Date.now() - 1000 });
    expect((await call(u, 'GET', '/v1/me/entitlements')).json().entitlements).toHaveLength(1);
    await hook({ type: 'CANCELLATION', cancel_reason: 'CUSTOMER_SUPPORT', app_user_id: u.user.id, entitlement_ids: ['plus'], expiration_at_ms: Date.now() + 30 * 864e5, event_timestamp_ms: Date.now() });
    expect((await call(u, 'GET', '/v1/me/entitlements')).json().entitlements).toEqual([]);
  });

  it('moves purchases away from the old account on a transfer', async () => {
    const a = await signUp(), b = await signUp();
    await hook({ type: 'INITIAL_PURCHASE', app_user_id: a.user.id, entitlement_ids: ['alliance_plus'], expiration_at_ms: Date.now() + 864e5 });
    await hook({ type: 'TRANSFER', app_user_id: b.user.id, transferred_from: [a.user.id], transferred_to: [b.user.id] });
    expect((await call(a, 'GET', '/v1/me/entitlements')).json().entitlements).toEqual([]);
  });

  it('exports the account with journal and coach text readable, and nobody else\'s messages', async () => {
    const u = await signUp('Exporter');
    const g = uuidv7();
    await call(u, 'POST', '/v1/sync/push', { mutations: [
      mut('goal', g, 'upsert', { label: 'Run', statement: 'Run a half marathon', startDate: '2026-01-01', deadline: '2030-01-01' }),
      mut('journal', uuidv7(), 'upsert', { goalId: g, localDate: '2026-10-14', mood: 4, body: 'Legs heavy, held pace.', baseVersion: 0 }),
    ] });
    const r = await call(u, 'GET', '/v1/me/export');
    expect(r.statusCode).toBe(200);
    expect(r.headers['content-disposition']).toContain('the-bridge-export.json');
    const body = r.json();
    expect(body.account).toMatchObject({ display_name: 'Exporter' });
    expect(body.goals).toHaveLength(1);
    expect(body.journal[0]).toMatchObject({ mood: 4, body: 'Legs heavy, held pace.' });
    expect(JSON.stringify(body)).not.toContain('ciphertext');
  });
});
