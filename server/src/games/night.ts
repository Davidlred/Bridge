import type { FastifyInstance } from 'fastify';
import { randomInt } from 'node:crypto';
import { z } from 'zod';
import { sql } from '../db.js';
import { auth } from '../auth/tokens.js';
import { HttpError, uuidv7 } from '../util.js';
import { bus } from '../realtime.js';
import { localToday } from '../social/progress.js';
import type { SocketCtx } from '../social/game.js';
import { LINEUP, available, liveGameOpen, moduleFor } from './registry.js';
import type { GameId, LiveModule } from './module.js';

/**
 * Game Night voting: "Pick tonight's game". Any member opens a 30-second vote; each member has one vote and can change
 * or remove it. The vote closes at 30 s or when every member has voted. Most votes wins; a tie (or no votes at all) is
 * broken at random, skipping the game this alliance played last time when another choice exists. The winner's lobby
 * opens and everyone in the picker is sent to it.
 *
 * Like the games, the vote lives in this process (one API machine per alliance).
 */
export const nightTiming = { voteMs: 30_000, keepMs: 15_000 };

type Phase = 'voting' | 'decided';
interface Night {
  id: string; allianceId: string; phase: Phase; endsAt: number; timer?: NodeJS.Timeout;
  votes: Map<string, GameId>;
  /** Members of the alliance when the vote opened: the "of 7" in "6 of 7 voted", and who can be waited on. */
  total: number;
  /** Games that need more players than the alliance has are greyed out ("Needs 3"). */
  players: number;
  lastGame: GameId | null;
  chosen?: GameId;
  /** Set while the winner's lobby is opening, so two last votes can't decide twice. */
  deciding?: boolean;
}

const nights = new Map<string, Night>();
const nightByAlliance = new Map<string, string>();

const KIND_TO_ID: Record<string, GameId> = { drifting: 'drift', whose_day: 'whose' };

/** Built, switched on, and enough people in the alliance. Duel games join once brackets exist (Arcade step 3). */
export const votable = (id: GameId, players: number) => {
  const m = LINEUP.find(g => g.id === id)!;
  return available(id) && moduleFor(id)?.kind === 'live' && players >= m.minPlayers;
};

function counts(n: Night) {
  const c: Partial<Record<GameId, number>> = {};
  for (const g of n.votes.values()) c[g] = (c[g] ?? 0) + 1;
  return c;
}

/** The games with the most votes (empty when nobody has voted). */
export function leaders(c: Partial<Record<GameId, number>>): GameId[] {
  const top = Math.max(0, ...Object.values(c).map(v => v ?? 0));
  return top === 0 ? [] : (Object.keys(c) as GameId[]).filter(g => c[g] === top);
}

/** Most votes wins; ties and "nobody voted" are random, skipping last time's game when there's another choice. */
export function pick(c: Partial<Record<GameId, number>>, pool: GameId[], lastGame: GameId | null, rand: (n: number) => number = randomInt): GameId | null {
  const top = leaders(c);
  const from = top.length ? top : pool;
  if (!from.length) return null;
  if (from.length === 1) return from[0]!;
  const fresh = from.filter(g => g !== lastGame);
  const list = fresh.length ? fresh : from;
  return list[rand(list.length)]!;
}

export function nightView(n: Night) {
  const c = counts(n);
  const top = leaders(c);
  return {
    id: n.id, allianceId: n.allianceId, phase: n.phase, endsAt: n.endsAt, now: Date.now(),
    total: n.total, voted: n.votes.size, counts: c,
    leader: top.length === 1 ? { game: top[0], votes: c[top[0]!] } : null,
    tied: top.length > 1 ? { games: top, votes: c[top[0]!] } : null,
    chosen: n.chosen ?? null,
    games: LINEUP.map(m => ({
      ...m, built: !!moduleFor(m.id), available: available(m.id),
      votable: votable(m.id, n.players), needs: n.players < m.minPlayers ? m.minPlayers : null,
    })),
  };
}

const publish = (n: Night) => bus.publish(`night:${n.id}`, { type: 'vote:state', night: nightView(n) });

const memberCount = async (allianceId: string) =>
  (await sql`select count(*)::int as n from alliance_members m join users u on u.id = m.user_id where m.alliance_id = ${allianceId} and m.status <> 'left' and u.deleted_at is null`)[0]!.n as number;

async function lastGameOf(allianceId: string, userId: string): Promise<GameId | null> {
  const today = await localToday(sql, userId);
  const [row] = await sql`select kind from game_sessions where alliance_id = ${allianceId} and local_date < ${today} order by local_date desc, started_at desc limit 1`;
  return row ? KIND_TO_ID[row.kind as string] ?? null : null;
}

export const openNight = (allianceId: string) => {
  const n = nights.get(nightByAlliance.get(allianceId) ?? '');
  return n && n.phase === 'voting' ? n : null;
};

/** Opens the vote, or returns the one already open. Throws when no game can be played with this many members. */
export async function startNight(allianceId: string, userId: string): Promise<Night> {
  const open = openNight(allianceId);
  if (open) return open;
  const players = await memberCount(allianceId);
  if (!LINEUP.some(m => votable(m.id, players))) {
    const needs = Math.min(...LINEUP.filter(m => available(m.id) && moduleFor(m.id)?.kind === 'live').map(m => m.minPlayers));
    throw new HttpError(409, 'need_players', { needs: Number.isFinite(needs) ? needs : 3 });
  }
  const n: Night = { id: uuidv7(), allianceId, phase: 'voting', endsAt: Date.now() + nightTiming.voteMs, votes: new Map(), total: players, players, lastGame: await lastGameOf(allianceId, userId) };
  nights.set(n.id, n); nightByAlliance.set(allianceId, n.id);
  n.timer = setTimeout(() => void decide(n), nightTiming.voteMs);
  bus.publish(`alliance:${allianceId}`, { type: 'night:open', nightId: n.id });
  return n;
}

async function decide(n: Night) {
  if (n.phase !== 'voting' || n.deciding) return;
  n.deciding = true;
  clearTimeout(n.timer);
  try {
    // Someone opened a game directly meanwhile (an older app): that one is tonight's game.
    const running = liveGameOpen(n.allianceId);
    const pool = LINEUP.filter(m => votable(m.id, n.players)).map(m => m.id);
    n.chosen = running ?? pick(counts(n), pool, n.lastGame) ?? undefined;
    if (n.chosen && !running) await (moduleFor(n.chosen) as LiveModule).openLobby(n.allianceId);
  } finally {
    n.phase = 'decided'; n.deciding = false; n.endsAt = Date.now();
    publish(n);
    n.timer = setTimeout(() => cleanup(n), nightTiming.keepMs);
  }
}

function cleanup(n: Night) {
  clearTimeout(n.timer);
  if (nightByAlliance.get(n.allianceId) === n.id) nightByAlliance.delete(n.allianceId);
  nights.delete(n.id);
}

// --- Socket frames: vote:join, vote:cast ---

const Frame = z.discriminatedUnion('type', [
  z.object({ type: z.literal('vote:join'), nightId: z.string().uuid() }),
  z.object({ type: z.literal('vote:cast'), nightId: z.string().uuid(), game: z.string().nullable() }),
]);

export async function voteFrame(userId: string, raw: unknown, ctx: SocketCtx) {
  const f = Frame.safeParse(raw);
  if (!f.success) return ctx.send({ type: 'vote:error', error: 'invalid_frame' });
  const m = f.data;
  const n = nights.get(m.nightId);
  if (!n) return ctx.send({ type: 'vote:error', nightId: m.nightId, error: 'closed' });
  const [member] = await sql`select 1 from alliance_members where alliance_id = ${n.allianceId} and user_id = ${userId} and status <> 'left'`;
  if (!member) return ctx.send({ type: 'vote:error', nightId: n.id, error: 'not_member' });

  if (m.type === 'vote:join') {
    const key = `night:${n.id}`;
    if (!ctx.games.has(key)) { ctx.games.add(key); ctx.watch(key); }
    ctx.send({ type: 'vote:state', night: nightView(n) });
    ctx.send({ type: 'vote:you', nightId: n.id, game: n.votes.get(userId) ?? null });
    return;
  }
  if (n.phase !== 'voting') return ctx.send({ type: 'vote:error', nightId: n.id, error: 'closed' });
  const game = m.game as GameId | null;
  // Tap the same tile again (or send null) to take the vote back.
  if (game === null || n.votes.get(userId) === game) n.votes.delete(userId);
  else if (!LINEUP.some(g => g.id === game) || !votable(game, n.players)) return ctx.send({ type: 'vote:error', nightId: n.id, error: 'not_votable' });
  else n.votes.set(userId, game);
  ctx.send({ type: 'vote:you', nightId: n.id, game: n.votes.get(userId) ?? null });
  publish(n);
  if (n.votes.size >= n.total) await decide(n);
}

// --- REST ---

export async function nightRoutes(app: FastifyInstance) {
  /**
   * Opens "Pick tonight's game" (or returns the open vote). A game already running in the alliance is a 409
   * `other_game_open` naming it, so the app goes straight to that game.
   */
  app.post<{ Params: { id: string } }>('/v1/alliances/:id/night', { onRequest: auth }, async (req, reply) => {
    const [m] = await sql`select 1 from alliance_members m join alliances a on a.id = m.alliance_id where m.alliance_id = ${req.params.id} and m.user_id = ${req.userId} and m.status <> 'left' and a.archived_at is null`;
    if (!m) throw new HttpError(404, 'not_found');
    const running = liveGameOpen(req.params.id);
    if (running) return reply.code(409).send({ error: 'other_game_open', game: running, kind: running === 'whose' ? 'whose_day' : 'drifting' });
    return { night: nightView(await startNight(req.params.id, req.userId)) };
  });
}

/** Tests: end the vote now. */
export const decideNow = (allianceId: string) => { const n = openNight(allianceId); return n ? decide(n) : Promise.resolve(); };
