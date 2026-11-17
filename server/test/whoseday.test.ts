import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { uuidv7 } from '../src/util.js';
import { arcLine, displayNames, goalHint, rank, stageAt, streakOf, strip, wdTiming } from '../src/social/whoseday.js';

vi.mock('../src/ai/replicate.js', async orig => ({ ...(await orig<typeof import('../src/ai/replicate.js')>()), runText: vi.fn(async () => 'allow') }));

describe('Whose Day? rules', () => {
  it('unlocks a clue every 5 seconds and closes guessing at 15', () => {
    const t0 = 1_000_000;
    expect([0, 4999, 5000, 9999, 10_000, 14_999, 15_000, 19_000].map(ms => stageAt(t0, t0 + ms))).toEqual([1, 1, 2, 2, 3, 3, 4, 4]);
  });
  it('strips private tasks down to their length', () => {
    const t = { name: 'Therapy', private: true, durationMin: 50, priority: 'high' as const, doneAt: '09:00' };
    expect(strip(t, false)).toEqual({ name: null, private: true, durationMin: 50, priority: 'high', doneAt: '09:00' });
    expect(strip({ ...t, private: false }, true).name).toBeNull();                 // a private goal hides all its tasks
    expect(strip({ ...t, private: false }, false).name).toBe('Therapy');
  });
  it('hints at the goal, never names it', () => {
    expect(goalHint('money', false)).toBe('someone saving for something');
    expect(goalHint('money', true)).toBe('a private goal');
    expect(goalHint(null, false)).toBe('someone working on a goal');
  });
  it('counts streaks, adds a last initial to shared first names, and breaks ties', () => {
    expect(streakOf(['2026-11-17', '2026-11-16', '2026-11-14'], '2026-11-17')).toBe(2);
    expect(streakOf(['2026-11-16'], '2026-11-17')).toBe(0);
    const n = displayNames([{ id: 'a', displayName: 'Sam Kane' }, { id: 'b', displayName: 'Sam Ode' }, { id: 'c', displayName: 'Kemi Bello' }, { id: 'd', displayName: 'sam' }]);
    expect([...n.values()]).toEqual(['Sam K.', 'Sam O.', 'Kemi', 'sam']);
    const p = (name: string, score: number, early: number, lockMs: number[]) => ({ name, score, early, lockMs });
    expect(rank([p('A', 5, 1, [4000]), p('B', 5, 2, [9000]), p('C', 5, 2, [2000]), p('D', 7, 0, [])]).map(x => x.name)).toEqual(['D', 'C', 'B', 'A']);
  });
  it("writes Arc's line from a named task", () => {
    const list = { ownerId: 'x', allianceGoalId: 'g', goalHint: '', goalTitle: 'Licence', deadline: null, goalPrivate: false,
      tasks: [{ name: null, private: true, durationMin: 10, priority: 'low' as const, doneAt: '06:00', goalId: 'g', streak: 9 },
        { name: 'Hazard clips', private: false, durationMin: 20, priority: 'high' as const, doneAt: '07:40', goalId: 'g', streak: 4 }] };
    expect(arcLine(list, 'Kemi')).toBe('Hazard clips at 7:40am. 4 days running. Kemi means it.');
  });
});

const run = describe.skipIf(!process.env.TEST_DATABASE_URL);

run('Whose Day? games', () => {
  let app: FastifyInstance;
  let sql: typeof import('../src/db.js').sql;

  beforeAll(async () => {
    ({ sql } = await import('../src/db.js'));
    await sql.unsafe('drop schema public cascade; create schema public;');
    await (await import('../src/migrate.js')).migrate();
    (await import('../src/mailer.js')).setMailer(async () => {});
    (await import('../src/push.js')).capturePushes(() => {});
    Object.assign(wdTiming, { lobbyMs: 20_000, stageMs: 400, closeMs: 1200, roundMs: 1600, revealMs: 150, graceMs: 120, resultMs: 5000 });
    app = await (await import('../src/app.js')).buildApp();
    await app.ready();
  });
  afterAll(async () => { await app?.close(); await sql?.end(); });

  type U = { user: { id: string }; accessToken: string };
  const signUp = async (name: string): Promise<U> => (await app.inject({ method: 'POST', url: '/v1/auth/email/signup', payload: { email: `${uuidv7()}@example.com`, password: 'correct horse', displayName: name } })).json();
  const call = (u: U, method: 'GET' | 'POST', url: string, payload?: object) => app.inject({ method, url, headers: { authorization: `Bearer ${u.accessToken}` }, payload });
  const mut = (entity: string, entityId: string, op: string, patch: object = {}) => ({ id: uuidv7(), entity, entityId, op, patch, clientTs: Date.now() });
  const today = async () => (await sql`select to_char((now() at time zone 'UTC') - interval '3 hours', 'YYYY-MM-DD') as d`)[0]!.d as string;
  const until = async (ok: () => boolean, ms = 4000) => { for (let t = 0; t < ms && !ok(); t += 15) await new Promise(r => setTimeout(r, 15)); expect(ok()).toBe(true); };
  const sleep = (ms: number) => new Promise(r => setTimeout(r, ms));

  /** A member who finished today: a public task, and for some a private one too. */
  const player = async (name: string, opts: { secret?: string; optOut?: boolean } = {}) => {
    const u = await signUp(name);
    const g = uuidv7(), t = uuidv7(), s = uuidv7();
    const muts = [mut('goal', g, 'upsert', { label: 'Run', statement: 'Run a half marathon', startDate: '2026-01-01', deadline: '2030-01-01', category: 'fitness' }),
      mut('task', t, 'upsert', { goalId: g, title: `${name}'s run`, priority: 'High', minutes: 30 })];
    if (opts.secret) muts.push(mut('task', s, 'upsert', { goalId: g, title: opts.secret, priority: 'Low', minutes: 50, private: true }));
    if (opts.optOut) muts.push(mut('settings', u.user.id, 'upsert', { whoseDayShareList: false }));
    await call(u, 'POST', '/v1/sync/push', { mutations: muts });
    // A week of history so Community and alliances are open.
    await sql`insert into task_completions (task_id, user_id, local_date, completed_at) select ${t}, ${u.user.id}, ((now() at time zone 'UTC') - interval '3 hours')::date - d, now() from generate_series(1, 7) d`;
    const d = await today();
    await call(u, 'POST', '/v1/sync/push', { mutations: [mut('completion', t, 'complete', { localDate: d }), ...(opts.secret ? [mut('completion', s, 'complete', { localDate: d })] : [])] });
    return { ...u, goalId: g, name };
  };

  const alliance = async (specs: [string, { secret?: string; optOut?: boolean }?][]) => {
    const ps = [] as Awaited<ReturnType<typeof player>>[];
    for (const [name, o] of specs) ps.push(await player(name, o));
    const a = (await call(ps[0]!, 'POST', '/v1/alliances', { name: 'Night Owls', planMode: 'own', goalId: ps[0]!.goalId })).json().alliance;
    for (const p of ps.slice(1)) await call(p, 'POST', '/v1/alliances/join', { code: a.inviteCode, goalId: p.goalId });
    return { a, ps };
  };

  type Frame = { type: string; game?: Record<string, any>; owner?: boolean; error?: string; preview?: { name: string | null; private: boolean }[]; guess?: unknown; round?: number };
  const connect = async (u: U) => {
    const ws = await app.injectWS('/v1/rt');
    const frames: Frame[] = [];
    const raw: string[] = [];
    ws.on('message', (b: Buffer) => { raw.push(b.toString()); frames.push(JSON.parse(b.toString())); });
    ws.send(JSON.stringify({ type: 'auth', token: u.accessToken }));
    await until(() => frames.some(f => f.type === 'ready'));
    const last = () => [...frames].reverse().find(f => f.type === 'wd:state')?.game;
    const me = () => [...frames].reverse().find(f => f.type === 'wd:you');
    return { ws, frames, raw, last, me, send: (o: object) => ws.send(JSON.stringify(o)) };
  };

  it('plays a full game: secret owners, clues by server time, 3/2/1 points, fooled points, awards, and private tasks never leave the owner', async () => {
    const { a, ps } = await alliance([['Ana', { secret: 'Therapy session' }], ['Bo'], ['Cy'], ['Di', { optOut: true }]]);
    const ids = ps.map(p => p.user.id);
    const st = (await call(ps[0]!, 'GET', `/v1/alliances/${a.id}/game`)).json();
    expect(st).toMatchObject({ unlocked: true, whoseDay: null });
    const lobby = (await call(ps[1]!, 'POST', `/v1/alliances/${a.id}/game`, { kind: 'whose_day' })).json().game;
    expect(lobby).toMatchObject({ kind: 'whose_day', phase: 'lobby', rounds: 3 });
    // One game at a time: Who's Drifting? can't open beside it
    expect((await call(ps[2]!, 'POST', `/v1/alliances/${a.id}/game`, { kind: 'drifting' })).json()).toEqual({ error: 'other_game_open', kind: 'whose_day' });
    expect((await call(ps[2]!, 'GET', `/v1/alliances/${a.id}/game`)).json().whoseDay.id).toBe(lobby.id);

    const socks = await Promise.all(ps.map(connect));
    for (const s of socks) s.send({ type: 'wd:join', gameId: lobby.id });
    await until(() => socks[0]!.last()?.players.length === 4 && socks.every(s => s.me()));
    // Ana's preview shows her private task locked; Di is out of the pool
    expect(socks[0]!.me()!.preview).toEqual(expect.arrayContaining([expect.objectContaining({ name: null, private: true, durationMin: 50 }), expect.objectContaining({ name: "Ana's run", private: false })]));
    expect(socks[3]!.me()).toMatchObject({ pool: false });

    socks[1]!.send({ type: 'wd:start', gameId: lobby.id });
    const seen = new Set<string>();
    for (let round = 0; round < 3; round++) {
      await until(() => socks[0]!.last()?.phase === 'round' && socks[0]!.last()!.current === round && socks.every(s => s.me()?.round === round));
      const g0 = socks[0]!.last()!;
      expect(g0.round).toMatchObject({ index: round, total: 3, stage: 1, guessed: 0, guessers: 3 });
      expect(g0.round.reveal).toBeUndefined();
      expect(g0.round.goalHint).toBeUndefined();
      expect(g0.round.tasks.every((t: Record<string, unknown>) => t.priority === undefined && t.doneAt === undefined && t.durationMin === null)).toBe(true);
      const o = socks.findIndex(s => s.me()!.owner === true);
      expect(socks.filter(s => s.me()!.owner === true)).toHaveLength(1);
      expect(o).not.toBe(3);                                                       // opted out: never picked
      seen.add(ids[o]!);
      const others = [0, 1, 2, 3].filter(i => i !== o);
      // The owner can't guess
      const own = socks[o]!.frames.filter(f => f.error === 'own_list').length;
      socks[o]!.send({ type: 'wd:guess', gameId: lobby.id, target: ids[others[0]!]! });
      await until(() => socks[o]!.frames.filter(f => f.error === 'own_list').length === own + 1);
      // Right at stage 1 (3 points), then a second guess is refused
      socks[others[0]!]!.send({ type: 'wd:guess', gameId: lobby.id, target: ids[o]! });
      await until(() => socks[others[0]!]!.me()!.guess !== null && socks[1]!.last()!.round?.guessed === 1);
      const refused = socks[others[0]!]!.frames.filter(f => f.error === 'already_guessed').length;
      socks[others[0]!]!.send({ type: 'wd:guess', gameId: lobby.id, target: ids[others[1]!]! });
      await until(() => socks[others[0]!]!.frames.filter(f => f.error === 'already_guessed').length === refused + 1);
      // Wrong at stage 1 (0 points, +1 for the owner)
      socks[others[1]!]!.send({ type: 'wd:guess', gameId: lobby.id, target: ids[others[2]!]! });
      // Stage 2 brings length, priority and done time; then a right guess there scores 2
      await until(() => socks[others[2]!]!.last()?.round?.stage === 2);
      const g2 = socks[others[2]!]!.last()!.round;
      expect(g2.minutes).toBeGreaterThan(0);
      expect(g2.tasks[0]).toMatchObject({ priority: expect.any(String), doneAt: expect.stringMatching(/^\d\d:\d\d$/) });
      expect(g2.goalHint).toBeUndefined();
      socks[others[2]!]!.send({ type: 'wd:guess', gameId: lobby.id, target: ids[o]! });
      // Everyone guessed: straight to the reveal
      await until(() => socks[0]!.last()?.phase === 'reveal' && socks[0]!.last()!.current === round);
      const r = socks[0]!.last()!.round.reveal;
      expect(r).toMatchObject({ ownerId: ids[o], goalTitle: 'Run', deadline: '2030-01-01', ownerPoints: 1, arcLine: expect.stringContaining('means it.') });
      expect(Object.fromEntries(r.guesses.map((x: { userId: string; points: number }) => [x.userId, x.points]))).toEqual({ [ids[others[0]!]!]: 3, [ids[others[1]!]!]: 0, [ids[others[2]!]!]: 2 });
    }
    expect(seen.size).toBe(3);
    await until(() => socks[0]!.last()?.phase === 'results');
    const res = socks[0]!.last()!.results;
    const total = Object.fromEntries(res.scores.map((x: { userId: string; score: number }) => [x.userId, x.score]));
    expect(Object.values(total).reduce((x: number, y) => x + (y as number), 0)).toBe(3 * (3 + 2 + 1));
    expect(res.sharpest).toMatchObject({ userId: expect.any(String), points: expect.any(Number) });
    expect(res.darkHorse).toMatchObject({ fooled: 1, lists: 1 });
    expect(res.favourite).toMatchObject({ task: expect.stringMatching(/'s run$/), streak: 8 });
    // The private task's name never reached any device, its owner's included
    for (const s of socks) expect(s.raw.join('\n')).not.toContain('Therapy session');
    // Owners were never named before their reveal
    const before = socks[1]!.raw.filter(x => x.includes('"phase":"round"'));
    expect(before.every(x => !x.includes('ownerId'))).toBe(true);

    const [row] = await sql`select kind, ranked, winner_id from game_sessions where id = ${lobby.id}`;
    expect(row).toMatchObject({ kind: 'whose_day', ranked: true, winner_id: res.scores[0].userId });
    expect((await call(ps[2]!, 'POST', `/v1/alliances/${a.id}/games/${lobby.id}/share`)).statusCode).toBe(201);
    const chat = (await call(ps[0]!, 'GET', `/v1/alliances/${a.id}/messages`)).json().messages;
    expect(chat.at(-1).body).toMatch(/^Game night: \w+ won Whose Day\? with \d+ points\.$/);
    socks.forEach(s => s.ws.terminate());
  });

  it('needs 3 players, turns late joiners away, saves the pool switch, resumes on reconnect and ends early below 3', async () => {
    const { a, ps } = await alliance([['Eve'], ['Fay'], ['Gus'], ['Hal']]);
    const lobby = (await call(ps[0]!, 'POST', `/v1/alliances/${a.id}/game`, { kind: 'whose_day' })).json().game;
    const [e, f, g, h] = await Promise.all(ps.map(connect));
    e!.send({ type: 'wd:join', gameId: lobby.id }); f!.send({ type: 'wd:join', gameId: lobby.id });
    await until(() => e!.last()?.players.length === 2);
    e!.send({ type: 'wd:start', gameId: lobby.id });
    await until(() => e!.frames.some(x => x.error === 'need_players'));
    // The pool switch is a synced setting
    f!.send({ type: 'wd:pool', gameId: lobby.id, on: false });
    await until(() => f!.me()?.pool === false);
    expect((await sql`select settings->>'whoseDayShareList' as v from users where id = ${ps[1]!.user.id}`)[0]!.v).toBe('false');
    f!.send({ type: 'wd:pool', gameId: lobby.id, on: true });
    await until(() => f!.me()?.pool === true);
    g!.send({ type: 'wd:join', gameId: lobby.id });
    await until(() => e!.last()?.players.length === 3);
    e!.send({ type: 'wd:start', gameId: lobby.id });
    await until(() => e!.last()?.phase === 'round');
    expect(e!.last()!.rounds).toBe(3);
    // Too late to join
    h!.send({ type: 'wd:join', gameId: lobby.id });
    await until(() => h!.frames.some(x => x.error === 'already_started'));
    // A dropped player rejoins inside the grace period and gets the current stage back
    f!.ws.terminate();
    const f2 = await connect(ps[1]!);
    f2.send({ type: 'wd:join', gameId: lobby.id });
    await until(() => !!f2.last() && !!f2.me());
    expect(f2.last()).toMatchObject({ id: lobby.id, phase: expect.stringMatching(/round|reveal/) });
    expect(f2.last()!.players.every((p: { absent: boolean }) => !p.absent)).toBe(true);
    // Leaving drops the game below 3: it ends with results
    g!.send({ type: 'wd:leave', gameId: lobby.id });
    await until(() => e!.last()?.phase === 'results');
    expect(e!.last()!.results.scores).toHaveLength(3);
    await sleep(50);
    [e, f2, g, h].forEach(s => s!.ws.terminate());
  });
});
