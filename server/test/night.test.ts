import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { uuidv7 } from '../src/util.js';
import { leaders, nightTiming, pick } from '../src/games/night.js';

vi.mock('../src/ai/replicate.js', async orig => ({ ...(await orig<typeof import('../src/ai/replicate.js')>()), runText: vi.fn(async () => 'allow') }));

describe('Game Night vote rules', () => {
  it('most votes wins', () => {
    expect(leaders({ drift: 3, whose: 1 })).toEqual(['drift']);
    expect(pick({ drift: 3, whose: 1 }, ['drift', 'whose'], 'drift')).toBe('drift');   // last night's game can still win outright
  });
  it('breaks ties at random but skips last night’s game', () => {
    expect(leaders({ drift: 2, whose: 2 })).toEqual(['drift', 'whose']);
    for (let i = 0; i < 20; i++) expect(pick({ drift: 2, whose: 2 }, ['drift', 'whose'], 'whose')).toBe('drift');
    expect(pick({ drift: 2, whose: 2 }, ['drift', 'whose'], null, () => 1)).toBe('whose');
  });
  it('picks from every playable game when nobody votes, still skipping last night’s', () => {
    expect(leaders({})).toEqual([]);
    expect(pick({}, ['drift', 'whose'], 'drift')).toBe('whose');
    expect(pick({}, ['drift'], 'drift')).toBe('drift');                                  // the only choice
    expect(pick({}, [], null)).toBeNull();
  });
});

const run = describe.skipIf(!process.env.TEST_DATABASE_URL);

run('Game Night vote', () => {
  let app: FastifyInstance;
  let sql: typeof import('../src/db.js').sql;

  beforeAll(async () => {
    ({ sql } = await import('../src/db.js'));
    await sql.unsafe('drop schema public cascade; create schema public;');
    await (await import('../src/migrate.js')).migrate();
    (await import('../src/mailer.js')).setMailer(async () => {});
    (await import('../src/push.js')).capturePushes(() => {});
    Object.assign(nightTiming, { voteMs: 1500, keepMs: 2000 });
    app = await (await import('../src/app.js')).buildApp();
    await app.ready();
  });
  afterAll(async () => { await app?.close(); await sql?.end(); });

  type U = { user: { id: string }; accessToken: string };
  const signUp = async (name: string): Promise<U> => (await app.inject({ method: 'POST', url: '/v1/auth/email/signup', payload: { email: `${uuidv7()}@example.com`, password: 'correct horse', displayName: name } })).json();
  const call = (u: U, method: 'GET' | 'POST', url: string, payload?: object) => app.inject({ method, url, headers: { authorization: `Bearer ${u.accessToken}` }, payload });
  const mut = (entity: string, entityId: string, op: string, patch: object = {}) => ({ id: uuidv7(), entity, entityId, op, patch, clientTs: Date.now() });
  const until = async (ok: () => boolean, ms = 4000) => { for (let t = 0; t < ms && !ok(); t += 15) await new Promise(r => setTimeout(r, 15)); expect(ok()).toBe(true); };

  const player = async (name: string) => {
    const u = await signUp(name);
    const g = uuidv7(), t = uuidv7();
    await call(u, 'POST', '/v1/sync/push', { mutations: [mut('goal', g, 'upsert', { label: 'Run', statement: 'Run a half marathon', startDate: '2026-01-01', deadline: '2030-01-01', category: 'fitness' }),
      mut('task', t, 'upsert', { goalId: g, title: 'Run', priority: 'High', minutes: 30 })] });
    await sql`insert into task_completions (task_id, user_id, local_date, completed_at) select ${t}, ${u.user.id}, ((now() at time zone 'UTC') - interval '3 hours')::date - d, now() from generate_series(1, 7) d`;
    return { ...u, goalId: g };
  };
  const alliance = async (n: number) => {
    const ps = [] as Awaited<ReturnType<typeof player>>[];
    for (let i = 0; i < n; i++) ps.push(await player(`P${i}`));
    const a = (await call(ps[0]!, 'POST', '/v1/alliances', { name: 'Voters', planMode: 'own', goalId: ps[0]!.goalId })).json().alliance;
    for (const p of ps.slice(1)) await call(p, 'POST', '/v1/alliances/join', { code: a.inviteCode, goalId: p.goalId });
    return { a, ps };
  };
  type Frame = { type: string; night?: Record<string, any>; game?: string | null; error?: string };
  const connect = async (u: U) => {
    const ws = await app.injectWS('/v1/rt');
    const frames: Frame[] = [];
    ws.on('message', (b: Buffer) => frames.push(JSON.parse(b.toString())));
    ws.send(JSON.stringify({ type: 'auth', token: u.accessToken }));
    await until(() => frames.some(f => f.type === 'ready'));
    const last = () => [...frames].reverse().find(f => f.type === 'vote:state')?.night;
    const mine = () => [...frames].reverse().find(f => f.type === 'vote:you')?.game;
    return { ws, frames, last, mine, send: (o: object) => ws.send(JSON.stringify(o)) };
  };

  it('votes, changes and removes votes, ends early when everyone has voted, and opens the winner', async () => {
    const { a, ps } = await alliance(3);
    const opened = await call(ps[0]!, 'POST', `/v1/alliances/${a.id}/night`);
    expect(opened.statusCode).toBe(200);
    const night = opened.json().night;
    expect(night.phase).toBe('voting');
    expect(night.total).toBe(3);
    expect(night.games).toHaveLength(12);
    expect(night.games.find((g: any) => g.id === 'drift').votable).toBe(true);
    expect(night.games.find((g: any) => g.id === 'four').votable).toBe(false);         // not built yet
    // A second member opening it gets the same vote; the game card shows it.
    expect((await call(ps[1]!, 'POST', `/v1/alliances/${a.id}/night`)).json().night.id).toBe(night.id);
    expect((await call(ps[2]!, 'GET', `/v1/alliances/${a.id}/game`)).json().night.id).toBe(night.id);

    const socks = await Promise.all(ps.map(connect));
    socks.forEach(s => s.send({ type: 'vote:join', nightId: night.id }));
    await until(() => socks.every(s => s.last()));

    socks[0]!.send({ type: 'vote:cast', nightId: night.id, game: 'whose' });
    await until(() => socks[2]!.last()?.voted === 1);
    expect(socks[0]!.mine()).toBe('whose');
    expect(socks[2]!.last()!.leader).toEqual({ game: 'whose', votes: 1 });
    // Tap again to take it back; then switch.
    socks[0]!.send({ type: 'vote:cast', nightId: night.id, game: 'whose' });
    await until(() => socks[2]!.last()?.voted === 0);
    expect(socks[0]!.mine()).toBeNull();
    socks[0]!.send({ type: 'vote:cast', nightId: night.id, game: 'four' });
    await until(() => socks[0]!.frames.some(f => f.type === 'vote:error' && f.error === 'not_votable'));

    socks[0]!.send({ type: 'vote:cast', nightId: night.id, game: 'drift' });
    socks[1]!.send({ type: 'vote:cast', nightId: night.id, game: 'whose' });
    await until(() => socks[2]!.last()?.voted === 2);
    expect(socks[2]!.last()!.tied).toEqual({ games: expect.arrayContaining(['drift', 'whose']), votes: 1 });
    socks[2]!.send({ type: 'vote:cast', nightId: night.id, game: 'whose' });
    // Everyone voted: decided before the timer, and the winner's lobby is open.
    await until(() => socks.every(s => s.last()?.phase === 'decided'), 1000);
    expect(socks[0]!.last()!.chosen).toBe('whose');
    const card = (await call(ps[0]!, 'GET', `/v1/alliances/${a.id}/game`)).json();
    expect(card.whoseDay?.phase).toBe('lobby');
    expect(card.night).toBeNull();
    // With a game running, opening a vote sends you to that game.
    const again = await call(ps[1]!, 'POST', `/v1/alliances/${a.id}/night`);
    expect(again.statusCode).toBe(409);
    expect(again.json()).toMatchObject({ error: 'other_game_open', game: 'whose', kind: 'whose_day' });
    socks.forEach(s => s.ws.terminate());
  });

  it('picks a game when the timer runs out with no votes, and needs 3 members', async () => {
    const { a, ps } = await alliance(3);
    const night = (await call(ps[0]!, 'POST', `/v1/alliances/${a.id}/night`)).json().night;
    const s = await connect(ps[0]!);
    s.send({ type: 'vote:join', nightId: night.id });
    await until(() => s.last()?.phase === 'decided', 3000);
    expect(['drift', 'whose']).toContain(s.last()!.chosen);
    s.ws.terminate();

    const pair = await alliance(2);
    const r = await call(pair.ps[0]!, 'POST', `/v1/alliances/${pair.a.id}/night`);
    expect(r.statusCode).toBe(409);
    expect(r.json()).toMatchObject({ error: 'need_players', needs: 3 });
  });
});
