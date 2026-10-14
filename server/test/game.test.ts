import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { uuidv7 } from '../src/util.js';
import { parseTime, sizeBand, tier } from '../src/social/rankings.js';
import { longestRun } from '../src/social/badges.js';

vi.mock('../src/ai/replicate.js', async orig => ({ ...(await orig<typeof import('../src/ai/replicate.js')>()), runText: vi.fn(async () => 'allow') }));

describe('ranking and badge helpers', () => {
  it('reads task times', () => {
    expect(parseTime('7:00 PM')).toBe(19 * 60);
    expect(parseTime('12:30 am')).toBe(30);
    expect(parseTime('19:45')).toBe(19 * 60 + 45);
    expect(parseTime('7 am')).toBe(7 * 60);
    expect(parseTime('After run')).toBeNull();
    expect(parseTime('25:00')).toBeNull();
  });
  it('tiers, bands and runs', () => {
    expect([tier(90), tier(80), tier(40)]).toEqual(['gold', 'silver', 'bronze']);
    expect([sizeBand(4), sizeBand(15), sizeBand(30)]).toEqual(['2-5', '6-15', '16+']);
    expect(longestRun(['2026-10-01', '2026-10-02', '2026-10-03', '2026-10-05'])).toBe(3);
  });
});

const run = describe.skipIf(!process.env.TEST_DATABASE_URL);

run('game night', () => {
  let app: FastifyInstance;
  let sql: typeof import('../src/db.js').sql;
  let timing: typeof import('../src/social/game.js').timing;
  const pushes: { userId: string; title: string }[] = [];

  beforeAll(async () => {
    ({ sql } = await import('../src/db.js'));
    await sql.unsafe('drop schema public cascade; create schema public;');
    await (await import('../src/migrate.js')).migrate();
    (await import('../src/mailer.js')).setMailer(async () => {});
    (await import('../src/push.js')).capturePushes((userId, p) => pushes.push({ userId, title: p.title }));
    ({ timing } = await import('../src/social/game.js'));
    Object.assign(timing, { lobbyMs: 3000, roleMs: 300, roundMs: 500, voteMs: 3000, graceMs: 150, resultMs: 100 });
    app = await (await import('../src/app.js')).buildApp();
    await app.ready();
  });
  afterAll(async () => { await app?.close(); await sql?.end(); });

  type U = { user: { id: string }; accessToken: string };
  const signUp = async (name: string): Promise<U> => (await app.inject({ method: 'POST', url: '/v1/auth/email/signup', payload: { email: `${uuidv7()}@example.com`, password: 'correct horse', displayName: name } })).json();
  const call = (u: U, method: 'GET' | 'POST', url: string, payload?: object) => app.inject({ method, url, headers: { authorization: `Bearer ${u.accessToken}` }, payload });
  const mut = (entity: string, entityId: string, op: string, patch: object = {}) => ({ id: uuidv7(), entity, entityId, op, patch, clientTs: Date.now() });
  const today = async () => (await sql`select to_char((now() at time zone 'UTC') - interval '3 hours', 'YYYY-MM-DD') as d`)[0]!.d as string;
  const until = async (ok: () => boolean, ms = 3000) => { for (let t = 0; t < ms && !ok(); t += 20) await new Promise(r => setTimeout(r, 20)); expect(ok()).toBe(true); };

  /** A member with one task on their goal and a 7-day streak (so Community and alliances are open). */
  const player = async (name: string) => {
    const u = await signUp(name);
    const g = uuidv7(), t = uuidv7();
    await call(u, 'POST', '/v1/sync/push', { mutations: [mut('goal', g, 'upsert', { label: 'Run', statement: 'Run a half marathon', startDate: '2026-01-01', deadline: '2030-01-01' }),
      mut('task', t, 'upsert', { goalId: g, title: 'Easy run', priority: 'High', timeOfDay: '7:00 AM' })] });
    await sql`insert into task_completions (task_id, user_id, local_date, completed_at) select ${t}, ${u.user.id}, ((now() at time zone 'UTC') - interval '3 hours')::date - d, now() from generate_series(1, 7) d`;
    return { ...u, goalId: g, taskId: t, name };
  };
  const finishToday = async (p: Awaited<ReturnType<typeof player>>) =>
    call(p, 'POST', '/v1/sync/push', { mutations: [mut('completion', p.taskId, 'complete', { localDate: await today() })] });

  const alliance = async (n: number) => {
    const ps = [] as Awaited<ReturnType<typeof player>>[];
    for (const name of ['Ana', 'Bo', 'Cy', 'Di', 'Ed'].slice(0, n)) ps.push(await player(name));
    const a = (await call(ps[0]!, 'POST', '/v1/alliances', { name: 'Night Owls', planMode: 'own', goalId: ps[0]!.goalId })).json().alliance;
    for (const p of ps.slice(1)) await call(p, 'POST', '/v1/alliances/join', { code: a.inviteCode, goalId: p.goalId });
    return { a, ps };
  };

  type Frame = { type: string; game?: Record<string, any>; role?: string; error?: string };
  const connect = async (u: U) => {
    const ws = await app.injectWS('/v1/rt');
    const frames: Frame[] = [];
    ws.on('message', (raw: Buffer) => frames.push(JSON.parse(raw.toString())));
    ws.send(JSON.stringify({ type: 'auth', token: u.accessToken }));
    await until(() => frames.some(f => f.type === 'ready'));
    const last = () => [...frames].reverse().find(f => f.type === 'game:state')?.game;
    return { ws, frames, last, send: (o: object) => ws.send(JSON.stringify(o)) };
  };

  it('opens when every active member has finished today, announces it once, and nudges the rest once', async () => {
    const { a, ps } = await alliance(4);
    await Promise.all(ps.slice(0, 3).map(finishToday));
    let s = (await call(ps[0]!, 'GET', `/v1/alliances/${a.id}/game`)).json();
    expect(s).toMatchObject({ unlocked: false, eligible: 4, finished: 3, waiting: ['Di'], needsPlayers: false });
    expect((await call(ps[0]!, 'POST', `/v1/alliances/${a.id}/game/nudge`)).json()).toEqual({ sent: 1 });
    expect((await call(ps[1]!, 'POST', `/v1/alliances/${a.id}/game/nudge`)).json()).toEqual({ sent: 0 });   // once a day
    expect(pushes.filter(p => p.userId === ps[3]!.user.id).map(p => p.title)).toEqual(['Ana is waiting on you in Night Owls']);
    await finishToday(ps[3]!);
    await until(() => pushes.filter(p => p.title === 'Game night is open in Night Owls').length === 4);
    await finishToday(ps[3]!);                                                    // a repeat doesn't announce again
    await new Promise(r => setTimeout(r, 100));
    expect(pushes.filter(p => p.title === 'Game night is open in Night Owls')).toHaveLength(4);
    s = (await call(ps[2]!, 'GET', `/v1/alliances/${a.id}/game`)).json();
    expect(s).toMatchObject({ unlocked: true, finished: 4, waiting: [], rankedToday: false });
  });

  it('plays a ranked game: one secret Drifter, server-checked moves, clues, a vote, and saved results', async () => {
    const { a, ps } = await alliance(4);
    await Promise.all(ps.map(finishToday));
    const lobby = (await call(ps[0]!, 'POST', `/v1/alliances/${a.id}/game`)).json().game;
    expect(lobby).toMatchObject({ phase: 'lobby', planks: [] });
    expect((await call(ps[1]!, 'POST', `/v1/alliances/${a.id}/game`)).json().game.id).toBe(lobby.id);   // one open lobby
    const socks = await Promise.all(ps.map(connect));
    for (const s of socks) s.send({ type: 'game:join', gameId: lobby.id });
    await until(() => socks[0]!.last()?.players.length === 4);
    for (const s of socks) s.send({ type: 'game:ready', gameId: lobby.id });
    await until(() => socks.every(s => s.frames.some(f => f.type === 'game:role')));
    const roles = socks.map(s => s.frames.find(f => f.type === 'game:role')!.role);
    expect(roles.filter(r => r === 'drifter')).toHaveLength(1);
    // Nobody else learns the role from the shared state
    expect(JSON.stringify(socks[0]!.frames.filter(f => f.type === 'game:state'))).not.toMatch(/drifter/);
    const d = roles.indexOf('drifter'), b = roles.indexOf('builder');
    for (const s of socks) s.send({ type: 'game:begin', gameId: lobby.id });
    await until(() => socks[b]!.last()?.phase === 'round');
    expect(socks[b]!.last()!.planks).toEqual(['ok', 'cracked', 'ok', 'ok', 'cracked', 'ok', 'cracked', 'ok']);
    socks[d]!.send({ type: 'game:act', gameId: lobby.id, plank: 0 });               // Drifter loosens a solid plank
    socks[b]!.send({ type: 'game:act', gameId: lobby.id, plank: 1 });               // a Builder repairs a cracked one
    socks[b]!.send({ type: 'game:act', gameId: lobby.id, plank: 2 });               // Builders can't loosen: ignored
    await until(() => socks[b]!.last()?.clues.length === 2);
    const g = socks[b]!.last()!;
    expect(g.planks.slice(0, 3)).toEqual(['cracked', 'ok', 'ok']);
    expect(g.clues.map((c: { text: string }) => c.text).sort()[0]).toMatch(/^(Ana|Bo|Cy|Di) repaired plank 2\.$|^Plank 1 loosened\./);
    await until(() => socks[b]!.last()?.phase === 'vote', 2000);
    const drifterId = ps[d]!.user.id;
    socks.forEach((s, i) => s.send({ type: 'game:vote', gameId: lobby.id, target: i === d ? ps[b]!.user.id : drifterId }));
    await until(() => socks[0]!.last()?.phase === 'result');
    const r = socks[0]!.last()!;
    expect(r).toMatchObject({ ranked: true, drifterId, ejectedId: drifterId, buildersWon: true, tally: { [drifterId]: 3, [ps[b]!.user.id]: 1 } });
    expect(r.players.find((p: { userId: string }) => p.userId === drifterId)).toMatchObject({ role: 'drifter', fun: 6 });
    expect(r.players.find((p: { userId: string }) => p.userId === ps[b]!.user.id)).toMatchObject({ role: 'builder', fun: 12 });
    const [row] = await sql`select ranked, builders_won, drifter_id from game_sessions where id = ${lobby.id}`;
    expect(row).toMatchObject({ ranked: true, builders_won: true, drifter_id: drifterId });
    expect((await sql`select count(*)::int as n from game_players where game_id = ${lobby.id}`)[0]!.n).toBe(4);
    // Sharing the result posts one line to the alliance chat, once
    expect((await call(ps[1]!, 'POST', `/v1/alliances/${a.id}/games/${lobby.id}/share`)).statusCode).toBe(201);
    expect((await call(ps[2]!, 'POST', `/v1/alliances/${a.id}/games/${lobby.id}/share`)).statusCode).toBe(200);
    const chat = (await call(ps[0]!, 'GET', `/v1/alliances/${a.id}/messages`)).json().messages;
    expect(chat.map((m: { kind: string; body: string }) => [m.kind, m.body])).toEqual([['game', `Game night: the Builders caught ${ps[d]!.name}.`]]);
    // Replays the same day are practice
    await new Promise(res => setTimeout(res, 150));
    const again = (await call(ps[0]!, 'POST', `/v1/alliances/${a.id}/game`)).json().game;
    for (const s of socks) s.send({ type: 'game:join', gameId: again.id });
    await until(() => socks[0]!.last()?.id === again.id && socks[0]!.last()!.players.length === 4);
    for (const s of socks) s.send({ type: 'game:ready', gameId: again.id });
    await until(() => socks[0]!.last()?.id === again.id && socks[0]!.last()!.phase === 'role');
    expect(socks[0]!.last()!.ranked).toBe(false);
    // The Drifter walking away hands the Builders the win after the grace period
    const role2 = socks.map(s => [...s.frames].reverse().find(f => f.type === 'game:role')!.role);
    socks[role2.indexOf('drifter')]!.ws.terminate();
    await until(() => socks[role2.indexOf('builder')]!.last()?.phase === 'result');
    expect(socks[role2.indexOf('builder')]!.last()).toMatchObject({ buildersWon: true, ejectedId: null });
    socks.forEach(s => s.ws.terminate());
  });

  it('rates the alliance and ranks it on its board', async () => {
    const { a, ps } = await alliance(2);
    await Promise.all(ps.map(finishToday));
    const r = (await call(ps[0]!, 'GET', `/v1/alliances/${a.id}/rating`)).json();
    expect(r).toMatchObject({ rank: expect.any(Number), band: '2-5' });
    for (const k of ['overall', 'productivity', 'efficiency', 'fun']) expect(r[k]).toBeGreaterThanOrEqual(0), expect(r[k]).toBeLessThanOrEqual(100);
    expect(r.productivity).toBeGreaterThan(0);
    const board = (await call(ps[0]!, 'GET', `/v1/alliances/${a.id}/rankings`)).json();
    expect(board.rows.filter((x: { mine: boolean }) => x.mine)).toHaveLength(1);
    expect((await call(await signUp('Out'), 'GET', `/v1/alliances/${a.id}/rating`)).statusCode).toBe(404);
  });

  it('awards Hall of Fame badges and keeps them', async () => {
    const p = await player('Zed');
    let b = (await call(p, 'GET', '/v1/me/badges')).json();
    const earned = (x: typeof b) => x.badges.filter((y: { earned: boolean }) => y.earned).map((y: { key: string }) => y.key);
    expect(earned(b)).toEqual(['first', 's7', 'week']);               // one task, done 7 days running
    expect(b.badges.find((y: { key: string }) => y.key === 's21')).toMatchObject({ earned: false, goal: 21 });
    await sql`delete from task_completions where user_id = ${p.user.id}`;
    b = (await call(p, 'GET', '/v1/me/badges')).json();
    expect(earned(b)).toEqual(['first', 's7', 'week']);                          // earned once, kept
  });
});
