import type { FastifyInstance } from 'fastify';
import { randomInt } from 'node:crypto';
import { z } from 'zod';
import { sql, type Tx } from '../db.js';
import { auth } from '../auth/tokens.js';
import { HttpError, uuidv7 } from '../util.js';
import { bus } from '../realtime.js';
import { push } from '../push.js';
import { blockedWith, firstName, goalStreak, localToday } from '../social/progress.js';
import { unlockStatus, type SocketCtx } from '../social/game.js';
import { LINEUP, available, moduleFor } from './registry.js';
import { MoveError, seeded, type DuelModule, type GameId } from './module.js';

/**
 * 1v1 matches for every duel game, live or turn by turn. The game module owns the state and the rules; this file keeps
 * the match in Postgres (so a turn-based match survives restarts and days), checks whose turn it is, counts moves,
 * keeps the challenge card in the alliance chat up to date, sends "Your turn" pushes, and settles a finished match:
 * the result line in the chat, the Fun ranking, and the next round of a bracket.
 */
export const duelTiming = { nudgeMs: 24 * 3600_000, claimMs: 48 * 3600_000 };

type Row = Record<string, any>;
const nameOf = (m: GameId | string) => LINEUP.find(g => g.id === m)?.name ?? 'a game';
export const duelModule = (game: string) => { const m = moduleFor(game); return m?.kind === 'duel' ? (m as DuelModule) : undefined; };

/** Who is watching which match right now (an open match screen), so "Your turn" only pushes to someone who isn't. */
const watching = new Map<string, Set<string>>();
const isWatching = (matchId: string, userId: string) => watching.get(matchId)?.has(userId) ?? false;

async function names(ids: string[]): Promise<Map<string, string>> {
  const rows = ids.length ? await sql`select id, display_name from users where id = any(${ids}::uuid[])` : [];
  return new Map(rows.map(r => [r.id as string, firstName(r.display_name)]));
}

/** Words of 3 to 8 letters from this alliance's goal and task names (never private ones, never journal text). */
export async function planWords(db: Tx | typeof sql, allianceId: string, players: string[]): Promise<string[]> {
  const rows = await db`select g.statement, g.label, g.private, (select coalesce(array_agg(t.title), '{}') from tasks t where t.goal_id = g.id and t.deleted_at is null and not t.private) as tasks
                        from alliance_members m join goals g on g.id = m.goal_id
                        where m.alliance_id = ${allianceId} and m.user_id = any(${players}::uuid[]) and m.status <> 'left'`;
  const words = new Set<string>();
  for (const r of rows) {
    const text = [...(r.private ? [] : [r.statement ?? '', r.label ?? '']), ...(r.tasks as string[])].join(' ').toLowerCase();
    for (const w of text.split(/[^a-z]+/)) if (w.length >= 3 && w.length <= 8) words.add(w);
  }
  return [...words];
}

/** Players on a 7-day streak (or longer) on their goal in this alliance get one small perk per game. */
async function perks(db: Tx | typeof sql, allianceId: string, players: string[]): Promise<string[]> {
  const rows = await db`select user_id, goal_id from alliance_members where alliance_id = ${allianceId} and user_id = any(${players}::uuid[]) and status <> 'left'`;
  const out: string[] = [];
  for (const r of rows) if (await goalStreak(db, r.user_id, r.goal_id) >= 7) out.push(r.user_id);
  return out;
}

export interface NewMatch {
  allianceId: string; game: GameId; a: string; b: string; source: 'game_night' | 'chat_challenge';
  bracketId?: string; round?: number; slot?: number; mode?: 'duel_live' | 'duel_turns'; seed?: string;
}

export async function createMatch(db: Tx | typeof sql, m: NewMatch): Promise<Row> {
  const mod = duelModule(m.game);
  if (!mod) throw new HttpError(409, 'game_unavailable');
  const id = uuidv7();
  const seed = m.seed ?? `${id}`;
  const players: [string, string] = [m.a, m.b];
  const state = mod.createState(players, seed, { planWords: await planWords(db, m.allianceId, players), perks: await perks(db, m.allianceId, players) });
  const [row] = await db`insert into game_matches (id, alliance_id, game, source, bracket_id, round, slot, player_a, player_b, mode, seed, state, turn)
                         values (${id}, ${m.allianceId}, ${m.game}, ${m.source}, ${m.bracketId ?? null}, ${m.round ?? null}, ${m.slot ?? null}, ${m.a}, ${m.b},
                                 ${m.mode ?? (m.source === 'chat_challenge' ? 'duel_turns' : 'duel_live')}, ${seed}, ${db.json(state as never)}, ${mod.turn(state)})
                         returning *`;
  return row!;
}

// --- Views ---

const scoresOf = (r: Row) => ({ [r.player_a]: r.score_a as number, [r.player_b]: r.score_b as number });

/** The challenge card: the same for everyone, each app words it for its own user ("Your turn", "Sam's turn"). */
export function card(r: Row, nm: Map<string, string>) {
  const mod = duelModule(r.game);
  return {
    matchId: r.id, game: r.game, source: r.source, bracketId: r.bracket_id ?? null,
    players: [r.player_a, r.player_b].map(id => ({ id, name: nm.get(id) ?? '' })),
    turn: r.status === 'done' ? null : r.turn, status: r.status, winnerId: r.winner_id ?? null, result: r.result ?? null,
    scores: scoresOf(r), scored: !!mod?.meta.scored,
    toBeat: r.status !== 'done' && mod?.toBeat ? mod.toBeat(r.state) : null,
    held: !!r.held_for && r.status !== 'done',
  };
}

export async function cardsFor(matchIds: string[]) {
  if (!matchIds.length) return new Map<string, ReturnType<typeof card>>();
  const rows = await sql`select * from game_matches where id = any(${matchIds}::uuid[])`;
  const nm = await names(rows.flatMap(r => [r.player_a, r.player_b]));
  return new Map(rows.map(r => [r.id as string, card(r, nm)]));
}

function canClaim(r: Row, viewer: string, now = Date.now()) {
  return r.status !== 'done' && !!r.turn && r.turn !== viewer && [r.player_a, r.player_b].includes(viewer) && now - (r.last_move_at as Date).getTime() >= duelTiming.claimMs;
}

export function matchView(r: Row, viewer: string, nm: Map<string, string>) {
  const mod = duelModule(r.game);
  return {
    ...card(r, nm), id: r.id, allianceId: r.alliance_id, mode: r.mode, round: r.round ?? null, version: r.version,
    you: viewer, known: !!mod, now: Date.now(),
    lastMoveAt: (r.last_move_at as Date).getTime(), canClaim: canClaim(r, viewer),
    view: mod ? mod.viewFor(r.state, viewer) : null,
  };
}

async function publishMatch(r: Row) {
  const nm = await names([r.player_a, r.player_b]);
  for (const p of [r.player_a, r.player_b]) bus.publish(`user:${p}`, { type: 'match:state', match: matchView(r, p, nm) });
  bus.publish(`match:${r.id}`, { type: 'match:card', match: card(r, nm) });
  bus.publish(`alliance:${r.alliance_id}`, { type: 'match:card', match: card(r, nm) });
  if (r.bracket_id) bus.publish(`alliance:${r.alliance_id}`, { type: 'bracket:update', bracketId: r.bracket_id });
}

// --- Turns, pushes and the card ---

async function turnPush(r: Row) {
  if (r.status === 'done' || !r.turn || isWatching(r.id, r.turn) || r.held_for === r.turn) return;
  const other = r.turn === r.player_a ? r.player_b : r.player_a;
  const nm = await names([other]);
  void push(r.turn, { title: `Your turn in ${nameOf(r.game)} vs ${nm.get(other)}`, data: { allianceId: r.alliance_id, matchId: r.id }, collapseKey: `match-${r.id}` }, 'system');
}

/** Posts the challenge card in the alliance chat (once per match). */
export async function postCard(r: Row, from: string | null): Promise<Row> {
  if (r.chat_message_id) return r;
  const nm = await names([r.player_a, r.player_b]);
  const other = from === r.player_a ? r.player_b : r.player_a;
  const body = from ? `${nm.get(from)} challenged ${nm.get(other)} to ${nameOf(r.game)}` : `${nameOf(r.game)}: ${nm.get(r.player_a)} vs ${nm.get(r.player_b)}`;
  const id = uuidv7();
  const [m] = await sql`insert into alliance_messages (id, alliance_id, user_id, kind, body, client_id, moderation, match_id)
                        values (${id}, ${r.alliance_id}, ${from}, 'game_challenge', ${body}, ${r.id}, 'allow', ${r.id})
                        on conflict (alliance_id, client_id) where client_id is not null do nothing returning created_at`;
  const [row] = await sql`update game_matches set chat_message_id = ${id} where id = ${r.id} and chat_message_id is null returning *`;
  if (m) bus.publish(`alliance:${r.alliance_id}`, { type: 'message', message: { id, clientId: r.id, userId: null, name: from ? nm.get(from) : null, mine: false, kind: 'game_challenge', body, createdAt: (m.created_at as Date).getTime(), match: card(row ?? r, nm) } });
  return row ?? r;
}

/** A result line in the chat: game icon + "Cup Pong night: Ada beat Kemi 6–4". */
async function postResult(r: Row, body: string) {
  const id = uuidv7();
  const [m] = await sql`insert into alliance_messages (id, alliance_id, user_id, kind, body, client_id, moderation, match_id)
                        values (${id}, ${r.alliance_id}, null, 'game', ${body}, ${uuidv7()}, 'allow', ${r.id}) returning created_at`;
  bus.publish(`alliance:${r.alliance_id}`, { type: 'message', message: { id, clientId: null, userId: null, name: null, mine: false, kind: 'game', body, game: r.game, createdAt: (m!.created_at as Date).getTime() } });
}

/** One finished game in the session history (the Fun ranking). The first of the day per alliance is the ranked one. */
export async function recordSession(id: string, allianceId: string, game: string, players: string[], winner: string | null, scores: Record<string, number>) {
  const [owner] = await sql`select owner_id from alliances where id = ${allianceId}`;
  if (!owner) return;
  const day = await localToday(sql, owner.owner_id);
  const [ranked] = await sql`insert into game_sessions (id, alliance_id, local_date, ranked, kind, winner_id, ended_at) values (${id}, ${allianceId}, ${day}, true, ${game}, ${winner}, now())
                             on conflict do nothing returning 1`;
  if (!ranked) await sql`insert into game_sessions (id, alliance_id, local_date, ranked, kind, winner_id, ended_at) values (${id}, ${allianceId}, ${day}, false, ${game}, ${winner}, now()) on conflict do nothing`;
  for (const p of players) await sql`insert into game_players (game_id, user_id, role, score) values (${id}, ${p}, 'player', ${scores[p] ?? 0}) on conflict do nothing`;
}


/** After a match ends: the result line, the Fun ranking (chat challenges; a bracket counts once, at its champion), the bracket. */
async function settled(r: Row) {
  const nm = await names([r.player_a, r.player_b]);
  const mod = duelModule(r.game);
  const w = r.winner_id as string | null, l = w ? (w === r.player_a ? r.player_b : r.player_a) : null;
  const ws = w === r.player_a ? r.score_a : r.score_b, ls = w === r.player_a ? r.score_b : r.score_a;
  const label = r.bracket_id ? `${nameOf(r.game)} night` : nameOf(r.game);
  const how = r.result === 'resigned' ? ' (resigned)' : r.result === 'claimed' || r.result === 'deadline' ? ' (time ran out)' : r.result === 'left' ? ' (left the alliance)' : '';
  const body = w
    ? `${label}: ${nm.get(w)} beat ${nm.get(l!)}${mod?.meta.scored && r.result === 'played' ? ` ${ws}–${ls}` : ''}${how}`
    : `${label}: ${nm.get(r.player_a)} and ${nm.get(r.player_b)} drew`;
  if (r.result !== 'left') await postResult(r, body).catch(() => {});
  if (!r.bracket_id) await recordSession(r.id, r.alliance_id, r.game, [r.player_a, r.player_b], w, scoresOf(r)).catch(() => {});
  // Loaded on use: the bracket file imports this one.
  else await (await import('./bracket.js')).advanceNow(r.bracket_id);
}

/**
 * Ends a match outside the game's own rules (resign, claim, deadline, leaving). A draw at the bracket deadline can't
 * stand, so the bracket picks a winner before calling this.
 */
export async function endMatch(matchId: string, winnerId: string | null, result: 'resigned' | 'claimed' | 'deadline' | 'left'): Promise<Row | null> {
  const [r] = await sql`update game_matches set status = 'done', winner_id = ${winnerId}, result = ${result}, turn = null, ended_at = now(), version = version + 1
                        where id = ${matchId} and status <> 'done' returning *`;
  if (!r) return null;
  await publishMatch(r);
  await settled(r);
  return r;
}

/** Applies one move. Returns the updated row; throws HttpError for anything the rules don't allow. */
export async function moveMatch(matchId: string, userId: string, move: unknown, version?: number): Promise<Row> {
  const now = Date.now();
  const r = await sql.begin(async tx => {
    const [m] = await tx`select * from game_matches where id = ${matchId} for update`;
    if (!m || ![m.player_a, m.player_b].includes(userId)) throw new HttpError(404, 'not_found');
    if (m.status === 'done') throw new HttpError(409, 'game_over');
    if (version !== undefined && version !== m.version) throw new HttpError(409, 'stale');
    const mod = duelModule(m.game);
    if (!mod) throw new HttpError(409, 'game_unavailable');
    let state = mod.settle ? mod.settle(m.state, now) : m.state;
    try { state = mod.applyMove(state, userId, move, { now }); } catch (e) {
      if (e instanceof MoveError) throw new HttpError(409, e.code);
      throw e;
    }
    const out = mod.isOver(state);
    const mine = userId === m.player_a ? 'moves_a' : 'moves_b';
    const [row] = await tx`update game_matches set state = ${tx.json(state as never)}, turn = ${out.done ? null : mod.turn(state)}, ${tx(mine)} = ${tx(mine)} + 1,
                             status = ${out.done ? 'done' : 'active'}, winner_id = ${out.done ? out.winnerId ?? null : null}, result = ${out.done ? 'played' : null},
                             score_a = ${out.scores[m.player_a] ?? 0}, score_b = ${out.scores[m.player_b] ?? 0},
                             last_move_at = now(), nudged_at = null, version = version + 1, ended_at = ${out.done ? new Date() : null}
                           where id = ${matchId} returning *`;
    return row!;
  });
  await publishMatch(r);
  if (r.status === 'done') await settled(r);
  else await turnPush(r);
  return r;
}

/** Re-reads a match, closing a timed round that ran out (nobody sent "end"), and hands the turn on. */
export async function freshMatch(matchId: string): Promise<Row | null> {
  const [m] = await sql`select * from game_matches where id = ${matchId}`;
  if (!m || m.status === 'done') return m ?? null;
  const mod = duelModule(m.game);
  if (!mod?.settle) return m;
  const state = mod.settle(m.state, Date.now());
  if (state === m.state) return m;
  const out = mod.isOver(state);
  const [row] = await sql`update game_matches set state = ${sql.json(state as never)}, turn = ${out.done ? null : mod.turn(state)}, status = ${out.done ? 'done' : 'active'},
                            winner_id = ${out.done ? out.winnerId ?? null : null}, result = ${out.done ? 'played' : null},
                            score_a = ${out.scores[m.player_a] ?? 0}, score_b = ${out.scores[m.player_b] ?? 0}, last_move_at = now(), version = version + 1,
                            ended_at = ${out.done ? new Date() : null}
                          where id = ${matchId} and version = ${m.version} returning *`;
  if (!row) return (await sql`select * from game_matches where id = ${matchId}`)[0] ?? null;
  await publishMatch(row);
  if (row.status === 'done') await settled(row); else await turnPush(row);
  return row;
}



/** After the user's completions sync: "Your turn" pushes that waited until they finished today go out now. */
export async function releaseHeld(userId: string) {
  const held = await sql`select * from game_matches where held_for = ${userId} and status <> 'done'`;
  for (const m of held) {
    const s = await unlockStatus(m.alliance_id);
    const me = s.members.find(x => x.userId === userId);
    if (me && !me.done) continue;
    const [row] = await sql`update game_matches set held_for = null, version = version + 1 where id = ${m.id} returning *`;
    await publishMatch(row!);
    await turnPush(row!);
  }
}

/** Housekeeping, every minute: close timed rounds, nudge after 24 h without a move (once). */
export async function sweepMatches() {
  const timed = await sql`select id from game_matches where status = 'active' and game in ('word', 'scramble') and last_move_at < now() - interval '90 seconds'`;
  for (const m of timed) await freshMatch(m.id).catch(() => {});
  const stale = await sql`select * from game_matches where status <> 'done' and turn is not null and held_for is null and nudged_at is null
                          and last_move_at < ${new Date(Date.now() - duelTiming.nudgeMs)}`;
  for (const m of stale) {
    await sql`update game_matches set nudged_at = now() where id = ${m.id}`;
    const other = m.turn === m.player_a ? m.player_b : m.player_a;
    const nm = await names([other]);
    void push(m.turn, { title: `${nm.get(other)} is waiting on you in ${nameOf(m.game)}`, data: { allianceId: m.alliance_id, matchId: m.id }, collapseKey: `match-${m.id}` }, 'system');
  }
}

// --- Socket: watch a match screen (so pushes skip whoever is looking at it) ---

const Frame = z.discriminatedUnion('type', [
  z.object({ type: z.literal('match:watch'), matchId: z.string().uuid() }),
  z.object({ type: z.literal('match:leave'), matchId: z.string().uuid() }),
]);

export async function matchFrame(userId: string, raw: unknown, ctx: SocketCtx) {
  const f = Frame.safeParse(raw);
  if (!f.success) return ctx.send({ type: 'match:error', error: 'invalid_frame' });
  const { matchId } = f.data;
  if (f.data.type === 'match:leave') { watching.get(matchId)?.delete(userId); ctx.games.delete(`match:${matchId}`); return; }
  const r = await freshMatch(matchId);
  if (!r || ![r.player_a, r.player_b].includes(userId)) {
    // Anyone in the alliance can follow the card; only the two players get the match itself.
    if (r) { const [m] = await sql`select 1 from alliance_members where alliance_id = ${r.alliance_id} and user_id = ${userId} and status <> 'left'`; if (m) return ctx.send({ type: 'match:card', match: card(r, await names([r.player_a, r.player_b])) }); }
    return ctx.send({ type: 'match:error', matchId, error: 'not_found' });
  }
  let set = watching.get(matchId);
  if (!set) watching.set(matchId, (set = new Set()));
  set.add(userId);
  ctx.games.add(`match:${matchId}`);
  ctx.send({ type: 'match:state', match: matchView(r, userId, await names([r.player_a, r.player_b])) });
}

export function matchSocketClosed(userId: string, ctx: SocketCtx) {
  for (const key of ctx.games) if (key.startsWith('match:')) watching.get(key.slice(6))?.delete(userId);
}

// --- REST ---

async function playerRow(matchId: string, userId: string) {
  if (!z.string().uuid().safeParse(matchId).success) throw new HttpError(404, 'not_found');
  const r = await freshMatch(matchId);
  if (!r || ![r.player_a, r.player_b].includes(userId)) throw new HttpError(404, 'not_found');
  return r;
}

export async function duelRoutes(app: FastifyInstance) {
  app.get<{ Params: { id: string } }>('/v1/matches/:id', { onRequest: auth }, async req => {
    const r = await playerRow(req.params.id, req.userId);
    return { match: matchView(r, req.userId, await names([r.player_a, r.player_b])) };
  });

  /** The user's open matches (everything waiting on them first). */
  app.get('/v1/me/matches', { onRequest: auth }, async req => {
    const rows = await sql`select m.* from game_matches m join alliance_members am on am.alliance_id = m.alliance_id and am.user_id = ${req.userId} and am.status <> 'left'
                           where m.status <> 'done' and (m.player_a = ${req.userId} or m.player_b = ${req.userId}) order by (m.turn = ${req.userId}) desc, m.last_move_at desc limit 50`;
    const nm = await names(rows.flatMap(r => [r.player_a, r.player_b]));
    return { matches: rows.map(r => card(r, nm)) };
  });

  app.post<{ Params: { id: string } }>('/v1/matches/:id/move', { onRequest: auth, config: { rateLimit: { max: 240, timeWindow: '1 minute' } } }, async req => {
    const b = z.object({ move: z.unknown(), version: z.number().int().optional() }).safeParse(req.body);
    if (!b.success) throw new HttpError(400, 'invalid_request');
    await playerRow(req.params.id, req.userId);
    const r = await moveMatch(req.params.id, req.userId, b.data.move, b.data.version);
    return { match: matchView(r, req.userId, await names([r.player_a, r.player_b])) };
  });

  /**
   * "Start my match" (live) or "Play later" (turn by turn: the card goes in the alliance chat and the other player gets
   * a push when it's their turn).
   */
  app.post<{ Params: { id: string } }>('/v1/matches/:id/mode', { onRequest: auth }, async req => {
    const b = z.object({ mode: z.enum(['duel_live', 'duel_turns']) }).safeParse(req.body);
    if (!b.success) throw new HttpError(400, 'invalid_request');
    let r = await playerRow(req.params.id, req.userId);
    if (r.status === 'done') throw new HttpError(409, 'game_over');
    [r] = await sql`update game_matches set mode = ${b.data.mode}, version = version + 1 where id = ${r.id} returning *`;
    if (b.data.mode === 'duel_turns') { r = await postCard(r!, null); await turnPush(r); }
    await publishMatch(r!);
    return { match: matchView(r!, req.userId, await names([r!.player_a, r!.player_b])) };
  });

  app.post<{ Params: { id: string } }>('/v1/matches/:id/resign', { onRequest: auth }, async req => {
    const r = await playerRow(req.params.id, req.userId);
    if (r.status === 'done') throw new HttpError(409, 'game_over');
    const done = await endMatch(r.id, r.player_a === req.userId ? r.player_b : r.player_a, 'resigned');
    return { match: matchView(done ?? r, req.userId, await names([r.player_a, r.player_b])) };
  });

  /** No move from the other player for 48 hours: the one waiting can take the win. */
  app.post<{ Params: { id: string } }>('/v1/matches/:id/claim', { onRequest: auth }, async req => {
    const r = await playerRow(req.params.id, req.userId);
    if (!canClaim(r, req.userId)) throw new HttpError(409, 'too_soon');
    const done = await endMatch(r.id, req.userId, 'claimed');
    return { match: matchView(done ?? r, req.userId, await names([r.player_a, r.player_b])) };
  });

  /** Rematch: a new chat challenge between the same two, the other player going first where the game lets them choose. */
  app.post<{ Params: { id: string } }>('/v1/matches/:id/rematch', { onRequest: auth, config: { rateLimit: { max: 20, timeWindow: '1 hour' } } }, async (req, reply) => {
    const r = await playerRow(req.params.id, req.userId);
    const other = r.player_a === req.userId ? r.player_b : r.player_a;
    const m = await challenge(r.alliance_id, req.userId, other, r.game);
    return reply.code(201).send({ match: matchView(m, req.userId, await names([m.player_a, m.player_b])) });
  });

  /** The chat's controller button: challenge another member to a turn-based game. */
  app.post<{ Params: { id: string } }>('/v1/alliances/:id/challenges', { onRequest: auth, config: { rateLimit: { max: 20, timeWindow: '1 hour' } } }, async (req, reply) => {
    const b = z.object({ game: z.string(), opponentId: z.string().uuid() }).safeParse(req.body);
    if (!b.success) throw new HttpError(400, 'invalid_request');
    const m = await challenge(req.params.id, req.userId, b.data.opponentId, b.data.game);
    return reply.code(201).send({ match: matchView(m, req.userId, await names([m.player_a, m.player_b])) });
  });
}

async function challenge(allianceId: string, from: string, to: string, game: string): Promise<Row> {
  if (from === to) throw new HttpError(400, 'invalid_request');
  const both = await sql`select m.user_id from alliance_members m join alliances a on a.id = m.alliance_id
                         where m.alliance_id = ${allianceId} and m.user_id = any(${[from, to]}::uuid[]) and m.status <> 'left' and a.archived_at is null`;
  if (!both.some(r => r.user_id === from)) throw new HttpError(404, 'not_found');
  if (!both.some(r => r.user_id === to)) throw new HttpError(404, 'opponent_not_found');
  if ((await blockedWith(sql, from)).includes(to)) throw new HttpError(404, 'opponent_not_found');
  const meta = LINEUP.find(g => g.id === game);
  if (!meta || meta.mode === 'everyone' || !duelModule(game) || !available(game as GameId)) throw new HttpError(409, 'game_unavailable');
  const [open] = await sql`select count(*)::int as n from game_matches where alliance_id = ${allianceId} and status <> 'done' and source = 'chat_challenge'
                           and ((player_a = ${from} and player_b = ${to}) or (player_a = ${to} and player_b = ${from}))`;
  if (open!.n >= 3) throw new HttpError(409, 'too_many_open');
  // The challenger goes first; for games that pick the first player themselves, the seed is fixed so it's the challenger.
  let r = await createMatch(sql, { allianceId, game: game as GameId, a: from, b: to, source: 'chat_challenge', seed: firstSeed(game, from, [from, to]) });
  // "They'll see it when they're done today": no push until the opponent has finished today's tasks.
  const s = await unlockStatus(allianceId);
  const them = s.members.find(x => x.userId === to);
  if (them && !them.done) [r] = await sql`update game_matches set held_for = ${to} where id = ${r.id} returning *`;
  r = await postCard(r!, from);
  if (r.turn !== from) await turnPush(r);
  return r;
}

/** A seed whose "who goes first" draw lands on [first]. The board itself still comes from the seed. */
function firstSeed(game: string, first: string, players: [string, string]): string {
  const mod = duelModule(game);
  for (let i = 0; i < 64; i++) {
    const seed = `${uuidv7()}:${i}`;
    const s = mod!.createState(players, seed, { planWords: [], perks: [] });
    if (mod!.turn(s) === first || mod!.turn(s) === null) return seed;
  }
  return `${uuidv7()}`;
}

/** Tests: a random number from a match's seed. */
export const seededPick = (seed: string, n: number) => Math.floor(seeded(seed)() * n);
export const randomPick = (n: number) => randomInt(n);
