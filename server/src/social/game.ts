import type { FastifyInstance } from 'fastify';
import { randomInt } from 'node:crypto';
import { z } from 'zod';
import { sql } from '../db.js';
import { auth } from '../auth/tokens.js';
import { HttpError, uuidv7 } from '../util.js';
import { bus } from '../realtime.js';
import { push } from '../push.js';
import { blockedWith, firstName, localToday } from './progress.js';

/**
 * Game Night, "Who's Drifting?". Server-authoritative: the server picks the Drifter, owns the planks, validates every
 * intent and writes the clues. Nobody learns who the Drifter is until the result, except the Drifter.
 *
 * State lives in this process. With several API machines, route an alliance's game to one machine (Fly's
 * replay header on the alliance id) or move the state into Redis; persisted results are already in Postgres.
 */
export const timing = { lobbyMs: 60_000, roleMs: 10_000, roundMs: 15_000, voteMs: 20_000, graceMs: 10_000, resultMs: 120_000 };
const PLANKS = 8;
const START_CRACKED = [1, 4, 6];
const MIN_RANKED = 4;
const MIN_PLAYERS = 3;
const ACTIONS_PER_SEC = 4;
const FUN_WIN = 12, FUN_LOSE = 6;

type Phase = 'lobby' | 'role' | 'round' | 'vote' | 'result' | 'cancelled';
interface Player {
  userId: string; name: string; ready: boolean; begun: boolean; role?: 'builder' | 'drifter';
  actions: number; vote?: string | null; links: number; absent: boolean; recent: number[]; graceTimer?: NodeJS.Timeout;
}
interface Game {
  id: string; allianceId: string; ranked: boolean; wantsRanked: boolean; phase: Phase; players: Map<string, Player>;
  planks: ('ok' | 'cracked')[]; clues: { who: string; text: string }[]; endsAt: number; timer?: NodeJS.Timeout;
  drifterId?: string; ejectedId?: string | null; buildersWon?: boolean; tally?: Record<string, number>; localDate: string;
}

const games = new Map<string, Game>();
const openByAlliance = new Map<string, string>();

// --- Unlock ---

/** Who has finished today's tasks for the alliance goal. Away members (no completion in 3 days) don't count. */
export async function unlockStatus(allianceId: string) {
  const members = await sql`select m.user_id, m.goal_id, u.display_name from alliance_members m join users u on u.id = m.user_id
                            where m.alliance_id = ${allianceId} and m.status <> 'left' and u.deleted_at is null order by m.joined_at`;
  const rows = [] as { userId: string; name: string; done: boolean; left: number }[];
  for (const m of members) {
    const today = await localToday(sql, m.user_id);
    const [r] = await sql`select (select count(*)::int from tasks where goal_id = ${m.goal_id} and deleted_at is null) as total,
                                 (select count(*)::int from task_completions c join tasks t on t.id = c.task_id where t.goal_id = ${m.goal_id} and t.deleted_at is null and c.local_date = ${today}) as done,
                                 exists(select 1 from task_completions c join tasks t on t.id = c.task_id where t.goal_id = ${m.goal_id} and c.local_date > ${today}::date - 3) as active`;
    if (!r!.active) continue;                                                  // away: excluded, not waited on
    rows.push({ userId: m.user_id, name: firstName(m.display_name), done: r!.total > 0 && r!.done >= r!.total, left: Math.max(0, r!.total - r!.done) });
  }
  const unlocked = rows.length > 0 && rows.every(r => r.done);
  return { unlocked, ranked: unlocked && rows.length >= MIN_RANKED, eligible: rows.length, finished: rows.filter(r => r.done).length, members: rows };
}

/** After completions sync: announce "Game night is open" once per alliance per day. */
export async function checkUnlocks(userId: string) {
  const alliances = await sql`select alliance_id from alliance_members where user_id = ${userId} and status <> 'left'`;
  for (const { alliance_id: id } of alliances) {
    const s = await unlockStatus(id);
    if (!s.unlocked) continue;
    const [fresh] = await sql`insert into game_unlocks (alliance_id, local_date) values (${id}, ${await localToday(sql, userId)}) on conflict do nothing returning 1`;
    if (!fresh) continue;
    bus.publish(`alliance:${id}`, { type: 'game:unlocked' });
    const [a] = await sql`select name from alliances where id = ${id}`;
    for (const m of s.members) void push(m.userId, { title: `Game night is open in ${a!.name}`, body: 'Everyone finished today.', data: { allianceId: id }, collapseKey: `game-${id}` }, 'system');
  }
}

// --- Views (never include roles before the result) ---

function view(g: Game) {
  const ended = g.phase === 'result';
  const fun = (p: Player) => (ended && g.ranked && !p.absent ? (((p.role === 'builder') === g.buildersWon) ? FUN_WIN : FUN_LOSE) : 0);
  const builders = [...g.players.values()].filter(p => p.role === 'builder' && !p.absent);
  const mvp = ended && builders.length ? builders.reduce((a, b) => (b.actions > a.actions ? b : a)) : null;
  return {
    id: g.id, allianceId: g.allianceId, ranked: g.ranked, phase: g.phase, endsAt: g.endsAt,
    players: [...g.players.values()].map(p => ({ userId: p.userId, name: p.name, ready: p.ready, absent: p.absent, voted: p.vote !== undefined,
      ...(ended ? { role: p.role, actions: p.actions, fun: fun(p) } : {}) })),
    planks: g.planks, integrity: Math.round((g.planks.filter(p => p === 'ok').length / PLANKS) * 100), clues: g.clues.slice(-6),
    ...(ended ? { drifterId: g.drifterId, ejectedId: g.ejectedId ?? null, buildersWon: g.buildersWon, tally: g.tally ?? {}, mvpId: mvp && mvp.actions > 0 ? mvp.userId : null } : {}),
  };
}

const publish = (g: Game) => bus.publish(`game:${g.id}`, { type: 'game:state', game: view(g) });
const present = (g: Game) => [...g.players.values()].filter(p => !p.absent);
const schedule = (g: Game, ms: number, next: () => void) => { clearTimeout(g.timer); g.endsAt = Date.now() + ms; g.timer = setTimeout(next, ms); };

// --- Lifecycle ---

/** Opens a lobby (or returns the open one). Ranked when everyone finished today with at least 4 eligible; otherwise practice. */
async function openLobby(allianceId: string): Promise<Game> {
  const existing = openByAlliance.get(allianceId);
  if (existing && games.get(existing) && !['result', 'cancelled'].includes(games.get(existing)!.phase)) return games.get(existing)!;
  const s = await unlockStatus(allianceId);
  const [owner] = await sql`select owner_id from alliances where id = ${allianceId}`;
  const g: Game = {
    id: uuidv7(), allianceId, ranked: false, wantsRanked: s.ranked, phase: 'lobby', players: new Map(), planks: [], clues: [], endsAt: 0,
    localDate: await localToday(sql, owner!.owner_id),
  };
  games.set(g.id, g); openByAlliance.set(allianceId, g.id);
  schedule(g, timing.lobbyMs, () => void start(g, true));
  return g;
}

async function start(g: Game, timedOut: boolean) {
  if (g.phase !== 'lobby') return;
  const ready = [...g.players.values()].filter(p => p.ready && !p.absent);
  if (ready.length < MIN_PLAYERS) {
    if (!timedOut) return;
    g.phase = 'cancelled'; clearTimeout(g.timer); publish(g); cleanup(g); return;
  }
  for (const p of g.players.values()) if (!p.ready) g.players.delete(p.userId);   // the timeout leaves the unready behind
  const list = [...g.players.values()];
  const drifter = list[randomInt(list.length)]!;
  for (const p of list) p.role = p === drifter ? 'drifter' : 'builder';
  g.drifterId = drifter.userId;
  g.planks = Array.from({ length: PLANKS }, (_, i) => (START_CRACKED.includes(i) ? 'cracked' : 'ok'));
  // One ranked game per alliance per day; replays are practice.
  g.ranked = false;
  if (g.wantsRanked && list.length >= MIN_RANKED) {
    const [row] = await sql`insert into game_sessions (id, alliance_id, local_date, ranked) values (${g.id}, ${g.allianceId}, ${g.localDate}, true) on conflict do nothing returning 1`;
    g.ranked = !!row;
  }
  if (!g.ranked) await sql`insert into game_sessions (id, alliance_id, local_date, ranked) values (${g.id}, ${g.allianceId}, ${g.localDate}, false)`;
  g.phase = 'role';
  for (const p of list) bus.publish(`user:${p.userId}`, { type: 'game:role', gameId: g.id, role: p.role });   // only to that player
  publish(g);
  schedule(g, timing.roleMs, () => beginRound(g));
}

function beginRound(g: Game) {
  if (g.phase !== 'role') return;
  g.phase = 'round';
  publish(g);
  schedule(g, timing.roundMs, () => beginVote(g));
}

function beginVote(g: Game) {
  if (g.phase !== 'round') return;
  g.phase = 'vote';
  publish(g);
  schedule(g, timing.voteMs, () => void finish(g));
}

async function finish(g: Game, drifterLeft = false) {
  if (g.phase === 'result' || g.phase === 'cancelled') return;
  clearTimeout(g.timer);
  const tally: Record<string, number> = {};
  for (const p of present(g)) if (p.vote) tally[p.vote] = (tally[p.vote] ?? 0) + 1;
  const sorted = Object.entries(tally).sort((a, b) => b[1] - a[1]);
  // A tie ejects nobody, and then the Drifter wins.
  g.ejectedId = drifterLeft ? null : sorted.length && (sorted.length === 1 || sorted[0]![1] > sorted[1]![1]) ? sorted[0]![0] : null;
  g.buildersWon = drifterLeft || g.ejectedId === g.drifterId;
  g.tally = tally;
  g.phase = 'result';
  g.endsAt = Date.now();
  await sql`update game_sessions set drifter_id = ${g.drifterId!}, ejected_id = ${g.ejectedId ?? null}, builders_won = ${g.buildersWon}, ended_at = now() where id = ${g.id}`;
  for (const p of g.players.values()) {
    await sql`insert into game_players (game_id, user_id, role, actions, vote_for, absent) values (${g.id}, ${p.userId}, ${p.role!}, ${p.actions}, ${p.vote ?? null}, ${p.absent})
              on conflict (game_id, user_id) do nothing`;
  }
  publish(g);
  g.timer = setTimeout(() => cleanup(g), timing.resultMs);
}

function cleanup(g: Game) {
  clearTimeout(g.timer);
  for (const p of g.players.values()) clearTimeout(p.graceTimer);
  if (openByAlliance.get(g.allianceId) === g.id) openByAlliance.delete(g.allianceId);
  games.delete(g.id);
}

/** A player gone for longer than the grace period is absent; the game goes on with at least 3, and a missing Drifter loses. */
function markAbsent(g: Game, p: Player) {
  if (p.links > 0 || p.absent || g.phase === 'result' || g.phase === 'cancelled') return;
  if (g.phase === 'lobby') { g.players.delete(p.userId); publish(g); return; }
  p.absent = true;
  if (p.role === 'drifter') { void finish(g, true); return; }
  if (present(g).length < MIN_PLAYERS) { void finish(g); return; }
  publish(g);
  if (g.phase === 'vote' && present(g).every(x => x.vote !== undefined)) void finish(g);
}

// --- Intents from the socket ---

const Frame = z.discriminatedUnion('type', [
  z.object({ type: z.literal('game:join'), gameId: z.string().uuid() }),
  z.object({ type: z.literal('game:ready'), gameId: z.string().uuid() }),
  z.object({ type: z.literal('game:begin'), gameId: z.string().uuid() }),
  z.object({ type: z.literal('game:act'), gameId: z.string().uuid(), plank: z.number().int().min(0).max(PLANKS - 1) }),
  z.object({ type: z.literal('game:vote'), gameId: z.string().uuid(), target: z.string().uuid() }),
  z.object({ type: z.literal('game:leave'), gameId: z.string().uuid() }),
]);

export interface SocketCtx { send: (e: unknown) => void; watch: (channel: string) => void; games: Set<string> }

export async function gameFrame(userId: string, raw: unknown, ctx: SocketCtx) {
  const f = Frame.safeParse(raw);
  if (!f.success) return ctx.send({ type: 'game:error', error: 'invalid_frame' });
  const m = f.data;
  const g = games.get(m.gameId);
  if (!g) return ctx.send({ type: 'game:error', gameId: m.gameId, error: 'game_over' });
  let p = g.players.get(userId);

  if (m.type === 'game:join') {
    const [member] = await sql`select 1 from alliance_members where alliance_id = ${g.allianceId} and user_id = ${userId} and status <> 'left'`;
    if (!member) return ctx.send({ type: 'game:error', gameId: g.id, error: 'not_member' });
    if (!p) {
      if (g.phase !== 'lobby') return ctx.send({ type: 'game:error', gameId: g.id, error: 'already_started' });   // can join the next one
      const blocked = new Set(await blockedWith(sql, userId));
      if ([...g.players.keys()].some(id => blocked.has(id))) return ctx.send({ type: 'game:error', gameId: g.id, error: 'unavailable' });
      const [u] = await sql`select display_name from users where id = ${userId}`;
      p = { userId, name: firstName(u!.display_name), ready: false, begun: false, actions: 0, links: 0, absent: false, recent: [] };
      g.players.set(userId, p);
    }
    if (!ctx.games.has(g.id)) { ctx.games.add(g.id); ctx.watch(`game:${g.id}`); p.links++; }
    clearTimeout(p.graceTimer);
    // Rejoining after a short drop: current state, plus the player's own role.
    ctx.send({ type: 'game:state', game: view(g) });
    if (p.role && g.phase !== 'result') ctx.send({ type: 'game:role', gameId: g.id, role: p.role });
    publish(g);
    return;
  }
  if (!p || p.absent) return ctx.send({ type: 'game:error', gameId: g.id, error: 'not_in_game' });

  switch (m.type) {
    case 'game:ready': {
      if (g.phase !== 'lobby') return;
      p.ready = true;
      publish(g);
      // Everyone in the lobby is ready and there are enough for a full game: start now.
      const all = [...g.players.values()];
      if (all.length >= MIN_RANKED && all.every(x => x.ready)) await start(g, false);
      return;
    }
    case 'game:begin': {
      if (g.phase !== 'role') return;
      p.begun = true;
      if (present(g).every(x => x.begun)) beginRound(g);
      return;
    }
    case 'game:act': {
      if (g.phase !== 'round') return;
      const now = Date.now();
      p.recent = p.recent.filter(t => now - t < 1000);
      if (p.recent.length >= ACTIONS_PER_SEC) return ctx.send({ type: 'game:error', gameId: g.id, error: 'slow_down' });
      p.recent.push(now);
      const i = m.plank;
      if (p.role === 'builder' && g.planks[i] === 'cracked') {
        g.planks[i] = 'ok'; p.actions++;
        g.clues.push({ who: p.userId, text: `${p.name} repaired plank ${i + 1}.` });
      } else if (p.role === 'drifter' && g.planks[i] === 'ok') {
        g.planks[i] = 'cracked'; p.actions++;
        // Somebody was seen nearby: usually the Drifter, sometimes an innocent Builder.
        const others = present(g).filter(x => x.userId !== p!.userId);
        const near = others.length && randomInt(100) >= 65 ? others[randomInt(others.length)]! : p;
        g.clues.push({ who: near.userId, text: `Plank ${i + 1} loosened. ${near.name} was nearby.` });
      } else return;
      publish(g);
      return;
    }
    case 'game:vote': {
      if (g.phase !== 'vote' || m.target === userId || !g.players.get(m.target) || p.vote !== undefined) return;
      p.vote = m.target;
      publish(g);
      if (present(g).every(x => x.vote !== undefined)) await finish(g);
      return;
    }
    case 'game:leave': {
      p.links = 0; ctx.games.delete(g.id);
      markAbsent(g, p);
      return;
    }
  }
}

/** The socket closed: each game it was in starts the 10-second grace period for this player. */
export function socketClosed(userId: string, ctx: SocketCtx) {
  for (const id of ctx.games) {
    const g = games.get(id), p = g?.players.get(userId);
    if (!g || !p) continue;
    p.links = Math.max(0, p.links - 1);
    if (p.links === 0) { clearTimeout(p.graceTimer); p.graceTimer = setTimeout(() => markAbsent(g, p), timing.graceMs); }
  }
}

// --- REST: status, open a lobby, nudge ---

export async function gameRoutes(app: FastifyInstance) {
  const member = async (allianceId: string, userId: string) => {
    const [m] = await sql`select 1 from alliance_members m join alliances a on a.id = m.alliance_id where m.alliance_id = ${allianceId} and m.user_id = ${userId} and m.status <> 'left' and a.archived_at is null`;
    if (!m) throw new HttpError(404, 'not_found');
  };

  /** Game card: who has finished (names only, never tasks), and the open lobby if there is one. */
  app.get<{ Params: { id: string } }>('/v1/alliances/:id/game', { onRequest: auth }, async req => {
    await member(req.params.id, req.userId);
    const s = await unlockStatus(req.params.id);
    const open = openByAlliance.get(req.params.id);
    const g = open ? games.get(open) : undefined;
    const today = await localToday(sql, req.userId);
    const [played] = await sql`select 1 from game_sessions where alliance_id = ${req.params.id} and ranked and local_date = ${today}`;
    return {
      unlocked: s.unlocked, rankedToday: !!played, eligible: s.eligible, finished: s.finished, needsPlayers: s.eligible < MIN_RANKED,
      waiting: s.members.filter(m => !m.done && m.userId !== req.userId).map(m => m.name),
      meLeft: s.members.find(m => m.userId === req.userId)?.left ?? null,
      game: g && !['result', 'cancelled'].includes(g.phase) ? view(g) : null,
    };
  });

  /** Opens the lobby. Before everyone has finished (or with fewer than 4 eligible) it's practice: no ranking points. */
  app.post<{ Params: { id: string } }>('/v1/alliances/:id/game', { onRequest: auth }, async req => {
    await member(req.params.id, req.userId);
    const g = await openLobby(req.params.id);
    return { game: view(g) };
  });

  /** "Share to alliance chat": one line about the result, posted once per game. */
  app.post<{ Params: { id: string; gameId: string } }>('/v1/alliances/:id/games/:gameId/share', { onRequest: auth }, async (req, reply) => {
    await member(req.params.id, req.userId);
    if (!z.string().uuid().safeParse(req.params.gameId).success) throw new HttpError(404, 'not_found');
    const [s] = await sql`select s.builders_won, u.display_name from game_sessions s left join users u on u.id = s.drifter_id
                          where s.id = ${req.params.gameId} and s.alliance_id = ${req.params.id} and s.ended_at is not null`;
    if (!s) throw new HttpError(404, 'not_found');
    const name = firstName(s.display_name ?? '');
    const body = s.builders_won ? `Game night: the Builders caught ${name}.` : `Game night: ${name} drifted away undetected.`;
    const [m] = await sql`insert into alliance_messages (id, alliance_id, user_id, kind, body, client_id, moderation) values (${uuidv7()}, ${req.params.id}, null, 'game', ${body}, ${req.params.gameId}, 'allow')
                          on conflict (alliance_id, client_id) where client_id is not null do nothing returning id, created_at`;
    if (m) bus.publish(`alliance:${req.params.id}`, { type: 'message', message: { id: m.id, clientId: req.params.gameId, userId: null, name: null, mine: false, kind: 'game', body, createdAt: (m.created_at as Date).getTime() } });
    return reply.code(m ? 201 : 200).send({ body });
  });

  /** "Nudge": one push per waiting member per day, naming the alliance, never the tasks. */
  app.post<{ Params: { id: string } }>('/v1/alliances/:id/game/nudge', { onRequest: auth, config: { rateLimit: { max: 10, timeWindow: '1 hour' } } }, async (req, reply) => {
    await member(req.params.id, req.userId);
    const s = await unlockStatus(req.params.id);
    const [a] = await sql`select name from alliances where id = ${req.params.id}`;
    const [me] = await sql`select display_name from users where id = ${req.userId}`;
    let sent = 0;
    for (const m of s.members.filter(x => !x.done && x.userId !== req.userId)) {
      const [fresh] = await sql`insert into game_nudges (alliance_id, user_id, local_date) values (${req.params.id}, ${m.userId}, ${await localToday(sql, m.userId)}) on conflict do nothing returning 1`;
      if (!fresh) continue;
      sent++;
      void push(m.userId, { title: `${firstName(me!.display_name)} is waiting on you in ${a!.name}`, body: 'Finish today\'s tasks to open game night.', data: { allianceId: req.params.id }, collapseKey: `nudge-${req.params.id}` }, 'system');
    }
    return reply.send({ sent });
  });
}
