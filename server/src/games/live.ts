import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { sql } from '../db.js';
import { auth } from '../auth/tokens.js';
import { HttpError, uuidv7 } from '../util.js';
import { bus } from '../realtime.js';
import { blockedWith, firstName, goalStreak } from '../social/progress.js';
import type { SocketCtx } from '../social/game.js';
import { MoveError, type GameMeta, type LiveModule } from './module.js';
import { recordSession } from './duel.js';

/**
 * The Arcade's "Everyone" games that everybody plays at the same time on their own phone (Hoops, Mini Golf, Push Out).
 * One engine runs the lobby, the start, the play and the results; each game supplies its world and rules. The server
 * runs every shot and sends the result (and for the ball games a recording to play back), so phones can't disagree.
 *
 * Up to 15 people play; anyone joining after that watches. Like the first two games, a game lives in this process.
 */
export const liveTiming = { lobbyMs: 90_000, tickMs: 200, resultMs: 120_000, graceMs: 15_000 };
export const MAX_LIVE = 15;

export interface LiveDef<W> {
  meta: GameMeta;
  create(players: string[], seed: string, perks: string[], now: number): W;
  /** A player's move. Returns what to tell that player (their shot), and what everyone sees (an event, or nothing). */
  move(w: W, playerId: string, move: any, now: number): { reply?: unknown; event?: unknown };
  /** Called every tick while playing; may return an event for everyone (Push Out's rounds). */
  tick?(w: W, now: number): unknown | null;
  done(w: W, now: number): boolean;
  /** Each player's score; with lowerWins (golf strokes) the lowest wins. */
  scores(w: W): Record<string, number>;
  lowerWins?: boolean;
  view(w: W, now: number): unknown;
}

type Phase = 'lobby' | 'playing' | 'results' | 'cancelled';
interface Player { userId: string; name: string; ready: boolean; links: number; absent: boolean; watching: boolean; graceTimer?: NodeJS.Timeout }
interface Game {
  id: string; allianceId: string; game: string; def: LiveDef<any>; phase: Phase; players: Map<string, Player>;
  world: any; endsAt: number; timer?: NodeJS.Timeout; ticker?: NodeJS.Timeout; starting?: boolean;
  results?: { userId: string; name: string; score: number; place: number }[];
}

const games = new Map<string, Game>();
const openByAlliance = new Map<string, string>();

const playing = (g: Game) => [...g.players.values()].filter(p => !p.watching);

function view(g: Game) {
  const now = Date.now();
  return {
    id: g.id, game: g.game, allianceId: g.allianceId, phase: g.phase, endsAt: g.endsAt, now, max: MAX_LIVE, min: g.def.meta.minPlayers,
    players: [...g.players.values()].map(p => ({ userId: p.userId, name: p.name, ready: p.ready, absent: p.absent, watching: p.watching })),
    world: g.world ? g.def.view(g.world, now) : null,
    results: g.results ?? null, lowerWins: !!g.def.lowerWins,
  };
}
const publish = (g: Game) => bus.publish(`arc:${g.id}`, { type: 'arc:state', game: view(g) });

export function openLiveGame(allianceId: string): Game | null {
  const g = games.get(openByAlliance.get(allianceId) ?? '');
  return g && (g.phase === 'lobby' || g.phase === 'playing') ? g : null;
}

export async function openLiveLobby(def: LiveDef<any>, allianceId: string): Promise<Game> {
  const open = openLiveGame(allianceId);
  if (open) return open;
  const g: Game = { id: uuidv7(), allianceId, game: def.meta.id, def, phase: 'lobby', players: new Map(), world: null, endsAt: Date.now() + liveTiming.lobbyMs };
  games.set(g.id, g); openByAlliance.set(allianceId, g.id);
  g.timer = setTimeout(() => void start(g, true), liveTiming.lobbyMs);
  bus.publish(`alliance:${allianceId}`, { type: 'arc:open', gameId: g.id, game: g.game });
  return g;
}

async function start(g: Game, timedOut: boolean) {
  if (g.phase !== 'lobby' || g.starting) return;
  g.starting = true;
  try {
    const ready = playing(g).filter(p => p.ready && !p.absent);
    if (ready.length < g.def.meta.minPlayers) {
      if (!timedOut) return;
      g.phase = 'cancelled'; publish(g); cleanup(g); return;
    }
    // The ones who didn't press ready by the end of the lobby watch instead.
    for (const p of playing(g)) if (!p.ready) p.watching = true;
    const ids = ready.map(p => p.userId);
    const perks: string[] = [];
    const goals = await sql`select user_id, goal_id from alliance_members where alliance_id = ${g.allianceId} and user_id = any(${ids}::uuid[]) and status <> 'left'`;
    for (const r of goals) if (await goalStreak(sql, r.user_id, r.goal_id) >= 7) perks.push(r.user_id);
    clearTimeout(g.timer);
    g.world = g.def.create(ids, g.id, perks, Date.now());
    g.phase = 'playing';
    publish(g);
    g.ticker = setInterval(() => {
      const now = Date.now();
      if (g.def.tick) { const e = g.def.tick(g.world, now); if (e) { bus.publish(`arc:${g.id}`, { type: 'arc:event', gameId: g.id, event: e }); publish(g); } }
      if (g.def.done(g.world, now)) void finish(g);
    }, liveTiming.tickMs);
  } finally { g.starting = false; }
}

/** Places: ties share a place (1, 1, 3). */
export function placesFor(scores: Record<string, number>, lowerWins: boolean) {
  const list = Object.entries(scores).sort((a, b) => (lowerWins ? a[1] - b[1] : b[1] - a[1]));
  return list.map(([userId, score]) => ({ userId, score, place: 1 + list.filter(([, s]) => (lowerWins ? s < score : s > score)).length }));
}

async function finish(g: Game) {
  if (g.phase !== 'playing') return;
  g.phase = 'results';
  clearInterval(g.ticker);
  const placed = placesFor(g.def.scores(g.world), !!g.def.lowerWins);
  g.results = placed.map(r => ({ ...r, name: g.players.get(r.userId)?.name ?? '' }));
  const winners = placed.filter(r => r.place === 1);
  const winner = winners.length === 1 ? winners[0]!.userId : null;
  g.endsAt = Date.now();
  publish(g);
  await recordSession(g.id, g.allianceId, g.game, placed.map(r => r.userId), winner, Object.fromEntries(placed.map(r => [r.userId, r.score]))).catch(() => {});
  g.timer = setTimeout(() => cleanup(g), liveTiming.resultMs);
}

function cleanup(g: Game) {
  clearTimeout(g.timer); clearInterval(g.ticker);
  for (const p of g.players.values()) clearTimeout(p.graceTimer);
  if (openByAlliance.get(g.allianceId) === g.id) openByAlliance.delete(g.allianceId);
  games.delete(g.id);
}

// --- Socket frames: arc:join, arc:ready, arc:move, arc:leave ---

const Frame = z.discriminatedUnion('type', [
  z.object({ type: z.literal('arc:join'), gameId: z.string().uuid() }),
  z.object({ type: z.literal('arc:ready'), gameId: z.string().uuid() }),
  z.object({ type: z.literal('arc:start'), gameId: z.string().uuid() }),
  z.object({ type: z.literal('arc:move'), gameId: z.string().uuid(), move: z.unknown() }),
  z.object({ type: z.literal('arc:leave'), gameId: z.string().uuid() }),
]);

export async function liveFrame(userId: string, raw: unknown, ctx: SocketCtx) {
  const f = Frame.safeParse(raw);
  if (!f.success) return ctx.send({ type: 'arc:error', error: 'invalid_frame' });
  const m = f.data;
  const g = games.get(m.gameId);
  if (!g) return ctx.send({ type: 'arc:error', gameId: m.gameId, error: 'game_over' });
  let p = g.players.get(userId);
  if (m.type === 'arc:join') {
    const [member] = await sql`select 1 from alliance_members where alliance_id = ${g.allianceId} and user_id = ${userId} and status <> 'left'`;
    if (!member) return ctx.send({ type: 'arc:error', gameId: g.id, error: 'not_member' });
    if (!p) {
      const blocked = new Set(await blockedWith(sql, userId));
      const [u] = await sql`select display_name from users where id = ${userId}`;
      // Full, already playing, or someone they blocked is in: they can watch.
      const watching = g.phase !== 'lobby' || playing(g).length >= MAX_LIVE || [...g.players.keys()].some(id => blocked.has(id));
      p = { userId, name: firstName(u!.display_name), ready: false, links: 0, absent: false, watching };
      g.players.set(userId, p);
    }
    if (!ctx.games.has(`arc:${g.id}`)) { ctx.games.add(`arc:${g.id}`); ctx.watch(`arc:${g.id}`); p.links++; }
    clearTimeout(p.graceTimer);
    p.absent = false;
    ctx.send({ type: 'arc:state', game: view(g) });
    publish(g);
    return;
  }
  if (!p) return ctx.send({ type: 'arc:error', gameId: g.id, error: 'not_in_game' });
  switch (m.type) {
    case 'arc:ready': {
      if (g.phase !== 'lobby' || p.watching) return;
      p.ready = true;
      publish(g);
      const all = playing(g).filter(x => !x.absent);
      if (all.length >= g.def.meta.minPlayers && all.every(x => x.ready)) await start(g, false);
      return;
    }
    case 'arc:start': {
      // Anyone ready can start once enough are ready; the others watch.
      if (g.phase !== 'lobby' || !p.ready) return;
      if (playing(g).filter(x => x.ready && !x.absent).length < g.def.meta.minPlayers) return ctx.send({ type: 'arc:error', gameId: g.id, error: 'need_players' });
      await start(g, true);
      return;
    }
    case 'arc:move': {
      if (g.phase !== 'playing' || p.watching) return ctx.send({ type: 'arc:error', gameId: g.id, error: 'not_playing' });
      try {
        const out = g.def.move(g.world, userId, m.move, Date.now());
        if (out.reply !== undefined) ctx.send({ type: 'arc:you', gameId: g.id, reply: out.reply });
        if (out.event !== undefined) bus.publish(`arc:${g.id}`, { type: 'arc:event', gameId: g.id, event: out.event });
        publish(g);
        if (g.def.done(g.world, Date.now())) await finish(g);
      } catch (e) {
        if (e instanceof MoveError) return ctx.send({ type: 'arc:error', gameId: g.id, error: e.code });
        throw e;
      }
      return;
    }
    case 'arc:leave': {
      p.links = 0; ctx.games.delete(`arc:${g.id}`);
      gone(g, p);
      return;
    }
  }
}

function gone(g: Game, p: Player) {
  if (p.links > 0) return;
  if (g.phase === 'lobby') { g.players.delete(p.userId); publish(g); return; }
  p.absent = true;
  publish(g);
}

export function liveSocketClosed(userId: string, ctx: SocketCtx) {
  for (const key of ctx.games) {
    if (!key.startsWith('arc:')) continue;
    const g = games.get(key.slice(4)), p = g?.players.get(userId);
    if (!g || !p) continue;
    p.links = Math.max(0, p.links - 1);
    if (p.links === 0) { clearTimeout(p.graceTimer); p.graceTimer = setTimeout(() => gone(g, p), liveTiming.graceMs); }
  }
}

/** Wraps a game definition as an Arcade module. */
export function liveModule(def: LiveDef<any>): LiveModule {
  return {
    kind: 'live', meta: def.meta,
    openLobby: async allianceId => view(await openLiveLobby(def, allianceId)),
    openGame: allianceId => { const g = openLiveGame(allianceId); return g && g.game === def.meta.id ? view(g) : null; },
  };
}

export async function liveRoutes(app: FastifyInstance) {
  /** The open Everyone game in this alliance (Hoops, Mini Golf, Push Out), for the app to join. */
  app.get<{ Params: { id: string } }>('/v1/alliances/:id/live', { onRequest: auth }, async req => {
    const [m] = await sql`select 1 from alliance_members where alliance_id = ${req.params.id} and user_id = ${req.userId} and status <> 'left'`;
    if (!m) throw new HttpError(404, 'not_found');
    const g = games.get(openByAlliance.get(req.params.id) ?? '');
    return { game: g ? view(g) : null };
  });
}

/** Tests: end the game now. */
export const finishNow = (allianceId: string) => { const g = games.get(openByAlliance.get(allianceId) ?? ''); return g ? finish(g) : Promise.resolve(); };
