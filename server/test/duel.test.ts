import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { uuidv7 } from '../src/util.js';
import { firstRound, midnightAfter, nextRound, roundLabel, roundsFor } from '../src/games/pairing.js';
import { fourModule, COLS } from '../src/games/four.js';
import { MoveError } from '../src/games/module.js';

vi.mock('../src/ai/replicate.js', async orig => ({ ...(await orig<typeof import('../src/ai/replicate.js')>()), runText: vi.fn(async () => 'allow') }));

describe('Bracket generation', () => {
  const ids = (n: number) => Array.from({ length: n }, (_, i) => `u${i}`);
  /** Plays a whole bracket with the first player of each pair winning, and checks every rule on the way. */
  const playOut = (n: number) => {
    let { pairs, bye } = firstRound(ids(n), `seed-${n}`, new Set(), new Set());
    const seen = new Set<string>(), hadBye = new Set<string>();
    let rounds = 1, lastBye: { userId: string; vsWinner: string } | null = null;
    for (;;) {
      const inRound = [...pairs.flat(), ...(bye ? [bye] : [])];
      expect(new Set(inRound).size).toBe(inRound.length);                       // nobody twice in a round
      // Never two byes for one player, unless everyone left has already had one.
      if (bye) { if (hadBye.has(bye)) expect(inRound.every(p => hadBye.has(p))).toBe(true); hadBye.add(bye); }
      for (const p of pairs.flat()) seen.add(p);
      const winners = pairs.map(p => p[0]);
      if (lastBye) expect(pairs.some(p => p.includes(lastBye!.userId))).toBe(true);
      const field = winners.length + (bye ? 1 : 0);
      if (field <= 1) break;
      lastBye = bye ? { userId: bye, vsWinner: winners[winners.length - 1]! } : null;
      ({ pairs, bye } = nextRound(winners, lastBye, hadBye));
      rounds++;
      if (pairs.length === 0 && !bye) break;
    }
    return { rounds, seen };
  };
  it('handles every size from 2 to 15 (and 33) in ceil(log2 n) rounds, everyone plays', () => {
    for (const n of [...Array.from({ length: 14 }, (_, i) => i + 2), 33]) {
      const { rounds, seen } = playOut(n);
      expect(rounds).toBe(roundsFor(n));
      expect(seen.size).toBe(n);
    }
  });
  it('gives an odd number a bye that meets a named winner', () => {
    const r = firstRound(ids(3), 's', new Set(), new Set());
    expect(r.pairs).toHaveLength(1);
    expect(r.bye).toBeTruthy();
    const next = nextRound([r.pairs[0]![1]], { userId: r.bye!, vsWinner: r.pairs[0]![1] }, new Set([r.bye!]));
    expect(next.pairs).toEqual([[r.bye, r.pairs[0]![1]]]);
  });
  it('keeps blocked members apart and avoids last night’s pairs when it can', () => {
    const players = ids(4);
    const blocked = new Set(['u0|u1', 'u2|u3']);
    for (let i = 0; i < 10; i++) {
      const { pairs } = firstRound(players, `b${i}`, new Set(['u0|u2']), blocked);
      for (const [a, b] of pairs) { expect(blocked.has(a < b ? `${a}|${b}` : `${b}|${a}`)).toBe(false); expect([a, b].sort().join('|')).not.toBe('u0|u2'); }
    }
  });
  it('names the rounds', () => {
    expect([1, 2].map(r => roundLabel(r, 2))).toEqual(['semis', 'final']);
    expect([1, 2, 3, 4].map(r => roundLabel(r, 4))).toEqual(['round', 'quarters', 'semis', 'final']);
  });
  it('ends at the next local midnight, at least an hour away', () => {
    const at = Date.UTC(2026, 10, 18, 18, 0);                                   // 19:00 in Lagos
    expect(midnightAfter(at, 'Africa/Lagos').toISOString()).toBe('2026-11-18T23:00:00.000Z');
    expect(midnightAfter(Date.UTC(2026, 10, 18, 22, 30), 'Africa/Lagos').toISOString()).toBe('2026-11-19T23:00:00.000Z');
    expect(midnightAfter(at, 'America/New_York').toISOString()).toBe('2026-11-19T05:00:00.000Z');
  });
});

describe('Four in a Row', () => {
  const P: [string, string] = ['a', 'b'];
  const start = () => { const s = fourModule.createState(P, 'x', { planWords: [], perks: [] }); return { s, first: fourModule.turn(s)!, second: fourModule.turn(s) === 'a' ? 'b' : 'a' }; };
  it('wins on four across, down and diagonally', () => {
    let { s, first, second } = start();
    for (const [who, col] of [[first, 0], [second, 0], [first, 1], [second, 1], [first, 2], [second, 2], [first, 3]] as const) s = fourModule.applyMove(s, who, { col }, { now: 0 });
    expect(fourModule.isOver(s)).toMatchObject({ done: true, winnerId: first });
    expect(s.line).toHaveLength(4);
    ({ s, first, second } = start());
    for (const [who, col] of [[first, 3], [second, 0], [first, 3], [second, 0], [first, 3], [second, 0], [first, 3]] as const) s = fourModule.applyMove(s, who, { col }, { now: 0 });
    expect(fourModule.isOver(s).winnerId).toBe(first);
    ({ s, first, second } = start());
    // diagonal: first on (5,0) (4,1) (3,2) (2,3)
    const seq: [string, number][] = [[first, 0], [second, 1], [first, 1], [second, 2], [first, 2], [second, 3], [first, 2], [second, 3], [first, 3], [second, 6], [first, 3]];
    for (const [who, col] of seq) s = fourModule.applyMove(s, who, { col }, { now: 0 });
    expect(fourModule.isOver(s).winnerId).toBe(first);
  });
  it('rejects moves out of turn, full columns and moves after the end', () => {
    let { s, first, second } = start();
    expect(() => fourModule.applyMove(s, second, { col: 0 }, { now: 0 })).toThrow(MoveError);
    expect(() => fourModule.applyMove(s, first, { col: COLS }, { now: 0 })).toThrow('bad_move');
    for (let i = 0; i < 6; i++) s = fourModule.applyMove(s, i % 2 ? second : first, { col: 6 }, { now: 0 });
    expect(() => fourModule.applyMove(s, first, { col: 6 }, { now: 0 })).toThrow('column_full');
  });
  it('is a draw when the board fills with no line', () => {
    let { s, first, second } = start();
    // Columns filled in pairs (0,1), (2,3), (4,5) then 6: a pattern with no four.
    const order = [0, 1, 0, 1, 0, 1, 1, 0, 1, 0, 1, 0, 2, 3, 2, 3, 2, 3, 3, 2, 3, 2, 3, 2, 4, 5, 4, 5, 4, 5, 5, 4, 5, 4, 5, 4, 6, 6, 6, 6, 6, 6];
    order.forEach((col, i) => { s = fourModule.applyMove(s, i % 2 ? second : first, { col }, { now: 0 }); });
    expect(fourModule.isOver(s)).toMatchObject({ done: true, winnerId: undefined });
    expect(s.draw).toBe(true);
  });
});

const run = describe.skipIf(!process.env.TEST_DATABASE_URL);

run('Matches, challenges and brackets', () => {
  let app: FastifyInstance;
  let sql: typeof import('../src/db.js').sql;
  const pushes: { userId: string; title: string }[] = [];

  beforeAll(async () => {
    ({ sql } = await import('../src/db.js'));
    await sql.unsafe('drop schema public cascade; create schema public;');
    await (await import('../src/migrate.js')).migrate();
    (await import('../src/mailer.js')).setMailer(async () => {});
    (await import('../src/push.js')).capturePushes((userId, p) => pushes.push({ userId, title: p.title }));
    const { nightTiming } = await import('../src/games/night.js');
    Object.assign(nightTiming, { voteMs: 1500, keepMs: 2000 });
    app = await (await import('../src/app.js')).buildApp();
    await app.ready();
  });
  afterAll(async () => { await app?.close(); await sql?.end(); });

  type U = { user: { id: string }; accessToken: string };
  const signUp = async (name: string): Promise<U> => (await app.inject({ method: 'POST', url: '/v1/auth/email/signup', payload: { email: `${uuidv7()}@example.com`, password: 'correct horse', displayName: name } })).json();
  const call = (u: U, method: 'GET' | 'POST', url: string, payload?: object) => app.inject({ method, url, headers: { authorization: `Bearer ${u.accessToken}` }, payload });
  const mut = (entity: string, entityId: string, op: string, patch: object = {}) => ({ id: uuidv7(), entity, entityId, op, patch, clientTs: Date.now() });

  const player = async (name: string, doneToday = true) => {
    const u = await signUp(name);
    const g = uuidv7(), t = uuidv7();
    await call(u, 'POST', '/v1/sync/push', { mutations: [mut('goal', g, 'upsert', { label: 'Run', statement: 'Run a half marathon', startDate: '2026-01-01', deadline: '2030-01-01', category: 'fitness' }),
      mut('task', t, 'upsert', { goalId: g, title: 'Morning stretch', priority: 'High', minutes: 30 })] });
    await sql`insert into task_completions (task_id, user_id, local_date, completed_at) select ${t}, ${u.user.id}, ((now() at time zone 'UTC') - interval '3 hours')::date - d, now() from generate_series(${doneToday ? 0 : 1}, 7) d`;
    return { ...u, goalId: g, taskId: t };
  };
  const alliance = async (n: number, doneToday = true) => {
    const ps = [] as Awaited<ReturnType<typeof player>>[];
    for (let i = 0; i < n; i++) ps.push(await player(`P${i}`, i === 0 || doneToday));
    const a = (await call(ps[0]!, 'POST', '/v1/alliances', { name: 'Players', planMode: 'own', goalId: ps[0]!.goalId })).json().alliance;
    for (const p of ps.slice(1)) await call(p, 'POST', '/v1/alliances/join', { code: a.inviteCode, goalId: p.goalId });
    return { a, ps };
  };
  const byId = (ps: U[], id: string) => ps.find(p => p.user.id === id)!;

  it('plays a chat challenge to the end: card, turns, pushes, result line and the Fun ranking', async () => {
    const { a, ps } = await alliance(2);
    const [ada, kemi] = ps as [U, U];
    const r = await call(ada, 'POST', `/v1/alliances/${a.id}/challenges`, { game: 'four', opponentId: kemi.user.id });
    expect(r.statusCode).toBe(201);
    const m = r.json().match;
    expect(m.turn).toBe(ada.user.id);                                       // the challenger goes first
    expect(pushes.some(p => p.userId === ada.user.id)).toBe(false);          // and isn't pushed about their own challenge
    expect(m.view.board).toHaveLength(42);
    const chat = (await call(kemi, 'GET', `/v1/alliances/${a.id}/messages`)).json().messages;
    const cardMsg = chat.find((x: { kind: string }) => x.kind === 'game_challenge');
    expect(cardMsg.match).toMatchObject({ matchId: m.id, game: 'four', turn: ada.user.id, status: 'waiting' });

    expect((await call(kemi, 'POST', `/v1/matches/${m.id}/move`, { move: { col: 0 } })).json().error).toBe('not_your_turn');
    let v = m;
    for (const [u, col] of [[ada, 0], [kemi, 1], [ada, 0], [kemi, 1], [ada, 0], [kemi, 1]] as const) {
      const res = await call(u, 'POST', `/v1/matches/${m.id}/move`, { move: { col } });
      expect(res.statusCode).toBe(200);
      v = res.json().match;
    }
    expect(pushes.some(p => p.userId === kemi.user.id && p.title.startsWith('Your turn in Four in a Row vs P0'))).toBe(true);
    v = (await call(ada, 'POST', `/v1/matches/${m.id}/move`, { move: { col: 0 } })).json().match;
    expect(v).toMatchObject({ status: 'done', winnerId: ada.user.id, result: 'played' });
    const after = (await call(kemi, 'GET', `/v1/alliances/${a.id}/messages`)).json().messages;
    expect(after.some((x: { kind: string; body: string }) => x.kind === 'game' && x.body === 'Four in a Row: P0 beat P1')).toBe(true);
    expect(after.find((x: { kind: string }) => x.kind === 'game_challenge').match.status).toBe('done');
    const [s] = await sql`select kind, ranked, winner_id from game_sessions where id = ${m.id}`;
    expect(s).toMatchObject({ kind: 'four', winner_id: ada.user.id });

    const re = await call(kemi, 'POST', `/v1/matches/${m.id}/rematch`);
    expect(re.statusCode).toBe(201);
    expect(re.json().match.turn).toBe(kemi.user.id);
    // Not in the alliance: can't see the match or challenge anyone in it.
    const stranger = await player('Zed');
    expect((await call(stranger, 'GET', `/v1/matches/${m.id}`)).statusCode).toBe(404);
    expect((await call(stranger, 'POST', `/v1/alliances/${a.id}/challenges`, { game: 'four', opponentId: ada.user.id })).statusCode).toBe(404);
    expect((await call(ada, 'POST', `/v1/alliances/${a.id}/challenges`, { game: 'drift', opponentId: kemi.user.id })).json().error).toBe('game_unavailable');
  });

  it('holds the push until the opponent has finished today, then sends it', async () => {
    const { a, ps } = await alliance(2, false);
    const [ada, kemi] = ps as [U & { taskId: string }, U & { taskId: string }];
    pushes.length = 0;
    const m = (await call(ada, 'POST', `/v1/alliances/${a.id}/challenges`, { game: 'four', opponentId: kemi.user.id })).json().match;
    await call(ada, 'POST', `/v1/matches/${m.id}/move`, { move: { col: 3 } });
    expect(pushes.some(p => p.userId === kemi.user.id)).toBe(false);
    expect((await call(kemi, 'GET', `/v1/matches/${m.id}`)).json().match.held).toBe(true);
    const today = (await sql`select ((now() at time zone 'UTC') - interval '3 hours')::date::text as d`)[0]!.d;
    await call(kemi, 'POST', '/v1/sync/push', { mutations: [mut('completion', kemi.taskId, 'complete', { localDate: today })] });
    const yourTurn = () => pushes.some(p => p.userId === kemi.user.id && p.title.startsWith('Your turn'));
    for (let i = 0; i < 150 && !yourTurn(); i++) await new Promise(r => setTimeout(r, 20));
    expect(pushes.some(p => p.userId === kemi.user.id && p.title.startsWith('Your turn'))).toBe(true);
  });

  it('lets the waiting player claim after 48 hours, and resigning gives the win away', async () => {
    const { a, ps } = await alliance(2);
    const [ada, kemi] = ps as [U, U];
    const m = (await call(ada, 'POST', `/v1/alliances/${a.id}/challenges`, { game: 'four', opponentId: kemi.user.id })).json().match;
    await call(ada, 'POST', `/v1/matches/${m.id}/move`, { move: { col: 3 } });
    expect((await call(ada, 'POST', `/v1/matches/${m.id}/claim`)).json().error).toBe('too_soon');
    await sql`update game_matches set last_move_at = now() - interval '49 hours' where id = ${m.id}`;
    expect((await call(kemi, 'POST', `/v1/matches/${m.id}/claim`)).statusCode).toBe(409);   // it's their own turn
    const claimed = (await call(ada, 'POST', `/v1/matches/${m.id}/claim`)).json().match;
    expect(claimed).toMatchObject({ status: 'done', winnerId: ada.user.id, result: 'claimed' });
    const m2 = (await call(ada, 'POST', `/v1/alliances/${a.id}/challenges`, { game: 'four', opponentId: kemi.user.id })).json().match;
    expect((await call(ada, 'POST', `/v1/matches/${m2.id}/resign`)).json().match).toMatchObject({ winnerId: kemi.user.id, result: 'resigned' });
  });

  it('nudges once after 24 hours without a move', async () => {
    const { a, ps } = await alliance(2);
    const [ada, kemi] = ps as [U, U];
    const m = (await call(ada, 'POST', `/v1/alliances/${a.id}/challenges`, { game: 'four', opponentId: kemi.user.id })).json().match;
    await call(ada, 'POST', `/v1/matches/${m.id}/move`, { move: { col: 3 } });
    await sql`update game_matches set last_move_at = now() - interval '25 hours' where id = ${m.id}`;
    pushes.length = 0;
    const { sweepArcade } = await import('../src/games/arcade.js');
    await sweepArcade(); await sweepArcade();
    expect(pushes.filter(p => p.userId === kemi.user.id && p.title === 'P0 is waiting on you in Four in a Row')).toHaveLength(1);
  });

  it('runs a Game Night bracket: the vote picks a 1v1 game, 3 players with a bye, a final and an Alliance champ', async () => {
    const { a, ps } = await alliance(3);
    const night = (await call(ps[0]!, 'POST', `/v1/alliances/${a.id}/night`)).json().night;
    expect(night.games.find((g: { id: string }) => g.id === 'four').votable).toBe(true);
    const { decideNow } = await import('../src/games/night.js');
    const ws = await app.injectWS('/v1/rt');
    ws.send(JSON.stringify({ type: 'auth', token: ps[0]!.accessToken }));
    await new Promise(r => setTimeout(r, 200));
    ws.send(JSON.stringify({ type: 'vote:join', nightId: night.id }));
    ws.send(JSON.stringify({ type: 'vote:cast', nightId: night.id, game: 'four' }));
    await new Promise(r => setTimeout(r, 200));
    await decideNow(a.id);
    ws.terminate();
    const card = (await call(ps[1]!, 'GET', `/v1/alliances/${a.id}/game`)).json();
    expect(card.bracket).toMatchObject({ game: 'four', status: 'active' });
    let b = (await call(ps[1]!, 'GET', `/v1/brackets/${card.bracket.id}`)).json().bracket;
    expect(b).toMatchObject({ round: 1, roundCount: 2, stages: ['semis', 'final'], votes: 1, voters: 1 });
    expect(b.rounds[0].matches).toHaveLength(1);
    expect(b.rounds[0].bye.user).toBeTruthy();
    expect(b.rounds[0].bye.vs).toHaveLength(2);

    // Play the semi: whoever's turn it is drops in column 0, the other in 1, until four in a row.
    const play = async (matchId: string) => {
      for (let i = 0; i < 20; i++) {
        const m = (await call(ps[0]!, 'GET', `/v1/brackets/${b.id}`)).json().bracket.rounds.flatMap((r: { matches: { id: string; turn: string; status: string }[] }) => r.matches).find((x: { id: string }) => x.id === matchId);
        if (m.status === 'done') return;
        const who = byId(ps, m.turn);
        const view = (await call(who, 'GET', `/v1/matches/${matchId}`)).json().match;
        const col = view.view.players[0] === who.user.id ? 0 : 1;
        await call(who, 'POST', `/v1/matches/${matchId}/move`, { move: { col } });
      }
    };
    await play(b.rounds[0].matches[0].id);
    b = (await call(ps[1]!, 'GET', `/v1/brackets/${b.id}`)).json().bracket;
    expect(b.round).toBe(2);
    const final = b.rounds[1].matches[0];
    expect([final.a.id, final.b.id]).toContain(b.rounds[0].bye.user.id);       // the bye meets the semi's winner
    await play(final.id);
    b = (await call(ps[1]!, 'GET', `/v1/brackets/${b.id}`)).json().bracket;
    expect(b.status).toBe('done');
    expect(b.champion).toBeTruthy();
    const chat = (await call(ps[1]!, 'GET', `/v1/alliances/${a.id}/messages`)).json().messages.map((m: { body: string }) => m.body);
    expect(chat).toContain(`Four in a Row night: ${b.champion.name} is the Alliance champ`);
    const champ = byId(ps, b.champion.id);
    const badges = (await call(champ, 'GET', '/v1/me/badges')).json().badges;
    expect(badges.find((x: { key: string }) => x.key === 'champ').earned).toBe(true);
    const [s] = await sql`select kind, winner_id from game_sessions where id = ${b.id}`;
    expect(s).toMatchObject({ kind: 'four', winner_id: b.champion.id });
  });

  it('settles a bracket at the deadline (more moves wins, then random) and moves on when a member leaves', async () => {
    const { a, ps } = await alliance(4);
    const { createBracket } = await import('../src/games/bracket.js');
    const id = await createBracket(a.id, 'four', 0, 0);
    let b = (await call(ps[0]!, 'GET', `/v1/brackets/${id}`)).json().bracket;
    expect(b.rounds[0].matches).toHaveLength(2);
    const m0 = b.rounds[0].matches[0];
    const mover = byId(ps, (await call(byId(ps, m0.a.id), 'GET', `/v1/matches/${m0.id}`)).json().match.turn);
    await call(mover, 'POST', `/v1/matches/${m0.id}/move`, { move: { col: 2 } });
    // Someone in the other semi leaves: their opponent goes through.
    const m1 = b.rounds[0].matches[1];
    const leaver = byId(ps, m1.a.id);
    await call(leaver, 'POST', `/v1/alliances/${a.id}/leave`);
    b = (await call(ps.find(p => p !== leaver)!, 'GET', `/v1/brackets/${id}`)).json().bracket;
    expect(b.rounds[0].matches[1]).toMatchObject({ status: 'done', winnerId: m1.b.id, result: 'left' });
    await sql`update game_brackets set deadline = now() - interval '1 minute' where id = ${id}`;
    const { sweepArcade } = await import('../src/games/arcade.js');
    await sweepArcade();
    b = (await call(byId(ps, m1.b.id), 'GET', `/v1/brackets/${id}`)).json().bracket;
    expect(b.rounds[0].matches[0]).toMatchObject({ status: 'done', winnerId: mover.user.id, result: 'deadline' });
    expect(b.status).toBe('done');
    expect([mover.user.id, m1.b.id]).toContain(b.champion.id);
  });
  it('runs an Everyone game (Hoops) from the vote: lobby, ready, shots, results and the session', async () => {
    const { a, ps } = await alliance(2);
    const night = (await call(ps[0]!, 'POST', `/v1/alliances/${a.id}/night`)).json().night;
    expect(night.games.find((g: { id: string }) => g.id === 'hoops').votable).toBe(true);
    const socks = await Promise.all(ps.map(async u => {
      const ws = await app.injectWS('/v1/rt');
      const frames: any[] = [];
      ws.on('message', (b: Buffer) => frames.push(JSON.parse(b.toString())));
      ws.send(JSON.stringify({ type: 'auth', token: u.accessToken }));
      for (let i = 0; i < 100 && !frames.some(f => f.type === 'ready'); i++) await new Promise(r => setTimeout(r, 15));
      return { ws, frames, send: (o: object) => ws.send(JSON.stringify(o)), last: () => [...frames].reverse().find(f => f.type === 'arc:state')?.game };
    }));
    for (const s of socks) { s.send({ type: 'vote:join', nightId: night.id }); s.send({ type: 'vote:cast', nightId: night.id, game: 'hoops' }); }
    const wait = async (ok: () => boolean) => { for (let i = 0; i < 300 && !ok(); i++) await new Promise(r => setTimeout(r, 15)); expect(ok()).toBe(true); };
    await wait(() => socks.every(s => s.frames.some(f => f.type === 'vote:state' && f.night.phase === 'decided')));
    const card = (await call(ps[0]!, 'GET', `/v1/alliances/${a.id}/game`)).json();
    expect(card.live).toMatchObject({ game: 'hoops', phase: 'lobby' });
    for (const s of socks) s.send({ type: 'arc:join', gameId: card.live.id });
    await wait(() => socks.every(s => s.last()?.players.length === 2));
    for (const s of socks) s.send({ type: 'arc:ready', gameId: card.live.id });
    await wait(() => socks.every(s => s.last()?.phase === 'playing'));
    const world = socks[0]!.last().world;
    await new Promise(r => setTimeout(r, Math.max(0, world.startsAt - Date.now() + 50)));
    socks[0]!.send({ type: 'arc:move', gameId: card.live.id, move: { angle: 0, power: 0.5 } });
    await wait(() => socks[0]!.frames.some(f => f.type === 'arc:you'));
    const { finishNow } = await import('../src/games/live.js');
    await finishNow(a.id);
    await wait(() => socks.every(s => s.last()?.phase === 'results'));
    expect(socks[1]!.last().results).toHaveLength(2);
    const [row] = await sql`select kind from game_sessions where id = ${card.live.id}`;
    expect(row!.kind).toBe('hoops');
    socks.forEach(s => s.ws.terminate());
  });
});
