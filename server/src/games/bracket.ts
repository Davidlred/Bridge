import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { sql } from '../db.js';
import { auth } from '../auth/tokens.js';
import { HttpError, uuidv7 } from '../util.js';
import { bus } from '../realtime.js';
import { push } from '../push.js';
import { blockedWith, firstName, localToday } from '../social/progress.js';
import { unlockStatus } from '../social/game.js';
import { LINEUP } from './registry.js';
import { seeded, type GameId } from './module.js';
import { createMatch, endMatch, recordSession } from './duel.js';

/**
 * 1v1 Game Night: Arc pairs the members into a knockout bracket. Rounds are made one at a time. An odd round gives one
 * member a bye ("Odd one out · plays the winner of Dami vs Lola"), never the same member twice when it can be helped, so
 * bigger alliances get byes in more than one round. The bracket must be over by midnight (the alliance lead's time
 * zone): an unplayed match goes to whoever made more moves, otherwise it's decided at random.
 */

import { firstRound, key, midnightAfter, nextRound, roundLabel, roundsFor } from './pairing.js';
export { firstRound, midnightAfter, nextRound, roundLabel, roundsFor } from './pairing.js';
type Pair = [string, string];

type Bye = { round: number; userId: string; vsMatch: string | null };

async function makeRound(bracketId: string, allianceId: string, game: GameId, round: number, pairs: Pair[], bye: string | null) {
  const ids: string[] = [];
  for (const [i, [a, b]] of pairs.entries()) {
    const m = await createMatch(sql, { allianceId, game, a, b, source: 'game_night', bracketId, round, slot: i, mode: 'duel_live' });
    ids.push(m.id);
  }
  if (bye) {
    const entry: Bye = { round, userId: bye, vsMatch: ids[ids.length - 1] ?? null };
    await sql`update game_brackets set byes = byes || ${sql.json([entry] as never)}::jsonb where id = ${bracketId}`;
  }
  return ids;
}

/** Who plays tonight: the members Game Night counts (away members sit out), or everyone when that's fewer than two. */
async function entrants(allianceId: string): Promise<string[]> {
  const s = await unlockStatus(allianceId);
  if (s.members.length >= 2) return s.members.map(m => m.userId);
  const rows = await sql`select m.user_id from alliance_members m join users u on u.id = m.user_id where m.alliance_id = ${allianceId} and m.status <> 'left' and u.deleted_at is null order by m.joined_at`;
  return rows.map(r => r.user_id as string);
}

export async function createBracket(allianceId: string, game: GameId, votes: number, voters: number): Promise<string> {
  const players = await entrants(allianceId);
  if (players.length < 2) throw new HttpError(409, 'need_players', { needs: 2 });
  const [a] = await sql`select a.owner_id, coalesce(u.home_timezone, 'UTC') as tz from alliances a join users u on u.id = a.owner_id where a.id = ${allianceId}`;
  const id = uuidv7();
  const blocked = new Set<string>();
  for (const p of players) for (const b of await blockedWith(sql, p)) blocked.add(key(p, b));
  // Last bracket's first-round pairs, so the same two don't meet again straight away.
  const prev = await sql`select player_a, player_b from game_matches where bracket_id = (select id from game_brackets where alliance_id = ${allianceId} order by created_at desc limit 1) and round = 1`;
  const avoid = new Set(prev.map(r => key(r.player_a, r.player_b)));
  const { pairs, bye } = firstRound(players, id, avoid, blocked);
  await sql`insert into game_brackets (id, alliance_id, game, local_date, deadline, rounds, votes, voters)
            values (${id}, ${allianceId}, ${game}, ${await localToday(sql, a!.owner_id)}, ${midnightAfter(Date.now(), a!.tz)}, ${roundsFor(players.length)}, ${votes}, ${voters})`;
  for (const [i, p] of players.entries()) await sql`insert into game_bracket_players (bracket_id, user_id, seed) values (${id}, ${p}, ${i})`;
  await makeRound(id, allianceId, game, 1, pairs, bye);
  bus.publish(`alliance:${allianceId}`, { type: 'bracket:created', bracketId: id, game });
  const name = LINEUP.find(g => g.id === game)?.name ?? game;
  for (const p of players) void push(p, { title: `${name} night: your match is ready`, data: { allianceId, bracketId: id }, collapseKey: `bracket-${id}` }, 'system');
  return id;
}

/** Finishes the round once every match in it is done: the next round, or the champion. Safe to call twice. */
async function advance(bracketId: string) {
  const done = await sql.begin(async tx => {
    const [b] = await tx`select * from game_brackets where id = ${bracketId} for update`;
    if (!b || b.status === 'done') return null;
    const matches = await tx`select * from game_matches where bracket_id = ${bracketId} and round = ${b.round} order by slot`;
    if (matches.some(m => m.status !== 'done')) return null;
    const stillIn = new Set((await tx`select user_id from alliance_members where alliance_id = ${b.alliance_id} and status <> 'left'`).map(r => r.user_id as string));
    const winners = matches.map(m => m.winner_id as string | null).filter((w): w is string => !!w && stillIn.has(w));
    const byes = b.byes as Bye[];
    const thisBye = byes.find(x => x.round === b.round && stillIn.has(x.userId));
    const field = [...winners, ...(thisBye ? [thisBye.userId] : [])];
    if (field.length <= 1) {
      const champ = field[0] ?? null;
      await tx`update game_brackets set status = 'done', champion_id = ${champ}, ended_at = now() where id = ${bracketId}`;
      return { champion: champ, b };
    }
    const vsWinner = thisBye?.vsMatch ? matches.find(m => m.id === thisBye.vsMatch)?.winner_id : null;
    const { pairs, bye } = nextRound(winners, thisBye && vsWinner ? { userId: thisBye.userId, vsWinner } : thisBye ? { userId: thisBye.userId, vsWinner: '' } : null,
      new Set(byes.map(x => x.userId)));
    await tx`update game_brackets set round = round + 1 where id = ${bracketId}`;
    return { next: { pairs, bye, round: b.round + 1 }, b };
  });
  if (!done) return;
  const b = done.b;
  if ('next' in done && done.next) {
    const ids = await makeRound(bracketId, b.alliance_id, b.game, done.next.round, done.next.pairs, done.next.bye);
    bus.publish(`alliance:${b.alliance_id}`, { type: 'bracket:update', bracketId });
    const name = LINEUP.find(g => g.id === b.game)?.name ?? b.game;
    const final = done.next.round === b.rounds;
    for (const [a, c] of done.next.pairs) for (const p of [a, c]) void push(p, { title: final ? `You're in the ${name} final` : `${name} night: your next match is ready`, data: { allianceId: b.alliance_id, bracketId }, collapseKey: `bracket-${bracketId}` }, 'system');
    // Past the deadline already: settle the new round straight away too.
    if (Date.now() >= (b.deadline as Date).getTime()) for (const id of ids) await settleAtDeadline(id);
    return;
  }
  const champ = (done as { champion: string | null }).champion;
  const players = (await sql`select user_id from game_bracket_players where bracket_id = ${bracketId}`).map(r => r.user_id as string);
  const wins = await sql`select winner_id, count(*)::int as n from game_matches where bracket_id = ${bracketId} and winner_id is not null group by 1`;
  await recordSession(bracketId, b.alliance_id, b.game, players, champ, Object.fromEntries(wins.map(w => [w.winner_id, w.n]))).catch(() => {});
  if (champ) {
    const [u] = await sql`select display_name from users where id = ${champ}`;
    const name = LINEUP.find(g => g.id === b.game)?.name ?? b.game;
    const body = `${name} night: ${firstName(u!.display_name)} is the Alliance champ`;
    const id = uuidv7();
    const [m] = await sql`insert into alliance_messages (id, alliance_id, user_id, kind, body, client_id, moderation) values (${id}, ${b.alliance_id}, null, 'game', ${body}, ${bracketId}, 'allow')
                          on conflict (alliance_id, client_id) where client_id is not null do nothing returning created_at`;
    if (m) bus.publish(`alliance:${b.alliance_id}`, { type: 'message', message: { id, clientId: bracketId, userId: null, name: null, mine: false, kind: 'game', body, game: b.game, createdAt: (m.created_at as Date).getTime() } });
  }
  bus.publish(`alliance:${b.alliance_id}`, { type: 'bracket:champion', bracketId, championId: champ });
}


/** The deadline rule for one match: more moves wins, otherwise random (from the match's seed, so it can be replayed). */
async function settleAtDeadline(matchId: string) {
  const [m] = await sql`select * from game_matches where id = ${matchId}`;
  if (!m || m.status === 'done') return;
  const winner = m.moves_a !== m.moves_b ? (m.moves_a > m.moves_b ? m.player_a : m.player_b) : seeded(`${m.seed}:deadline`)() < 0.5 ? m.player_a : m.player_b;
  await endMatch(m.id, winner, 'deadline');
}

/** Every minute: brackets past their deadline settle their open matches, round after round, to a champion. */
export async function sweepBrackets() {
  const late = await sql`select id from game_brackets where status = 'active' and deadline < now()`;
  for (const b of late) {
    const open = await sql`select id from game_matches where bracket_id = ${b.id} and status <> 'done' order by round, slot`;
    for (const m of open) await settleAtDeadline(m.id);
    await advance(b.id);
  }
  // A draw in a bracket match (Four in a Row full board, equal scores) is replayed: a fresh match between the same two.
  const drawn = await sql`select m.* from game_matches m join game_brackets b on b.id = m.bracket_id
                          where b.status = 'active' and m.status = 'done' and m.winner_id is null and m.round = b.round
                            and not exists (select 1 from game_matches x where x.bracket_id = m.bracket_id and x.round = m.round and x.slot = m.slot and x.created_at > m.created_at)`;
  for (const m of drawn) await replayDraw(m);
}

/** Brackets need a winner: a drawn match is played again (same two, new board). */
export async function replayDraw(m: Record<string, any>) {
  await createMatch(sql, { allianceId: m.alliance_id, game: m.game, a: m.player_b, b: m.player_a, source: 'game_night', bracketId: m.bracket_id, round: m.round, slot: m.slot, mode: m.mode });
  bus.publish(`alliance:${m.alliance_id}`, { type: 'bracket:update', bracketId: m.bracket_id });
}

// --- Views ---

export async function bracketView(bracketId: string, viewer: string) {
  const [b] = await sql`select * from game_brackets where id = ${bracketId}`;
  if (!b) return null;
  const players = await sql`select p.user_id, u.display_name from game_bracket_players p join users u on u.id = p.user_id where p.bracket_id = ${bracketId} order by p.seed`;
  const nm = new Map(players.map(p => [p.user_id as string, firstName(p.display_name)]));
  const person = (id: string | null) => (id ? { id, name: nm.get(id) ?? '' } : null);
  // A replayed draw leaves the old match behind; only the newest one per slot is shown.
  const matches = await sql`select distinct on (round, slot) * from game_matches where bracket_id = ${bracketId} order by round, slot, created_at desc`;
  const byes = b.byes as Bye[];
  const rounds = [];
  for (let r = 1; r <= b.round; r++) {
    const bye = byes.find(x => x.round === r);
    const vs = bye?.vsMatch ? matches.find(m => m.id === bye.vsMatch) : null;
    rounds.push({
      round: r, label: roundLabel(r, b.rounds),
      matches: matches.filter(m => m.round === r).map(m => ({
        id: m.id, slot: m.slot, a: person(m.player_a), b: person(m.player_b), status: m.status, mode: m.mode, turn: m.turn,
        winnerId: m.winner_id, result: m.result, scores: [m.score_a, m.score_b], mine: m.player_a === viewer || m.player_b === viewer,
      })),
      bye: bye ? { user: person(bye.userId), vs: vs ? [person(vs.player_a), person(vs.player_b)] : null } : null,
    });
  }
  const meta = LINEUP.find(g => g.id === b.game)!;
  const mine = matches.filter(m => m.round === b.round && (m.player_a === viewer || m.player_b === viewer))[0];
  return {
    id: b.id, allianceId: b.alliance_id, game: b.game, gameName: meta?.name ?? b.game, scored: false,
    status: b.status, round: b.round, roundCount: b.rounds, deadline: (b.deadline as Date).getTime(), votes: b.votes, voters: b.voters,
    champion: person(b.champion_id), players: players.map(p => person(p.user_id)), stages: Array.from({ length: b.rounds }, (_, i) => roundLabel(i + 1, b.rounds)),
    rounds, myMatchId: mine?.id ?? null, playing: players.some(p => p.user_id === viewer),
  };
}

/** Today's bracket in this alliance (the open one, or the one that finished today), for the game card. */
export async function bracketToday(allianceId: string, userId: string) {
  const day = await localToday(sql, userId);
  const [b] = await sql`select id, game, status, champion_id from game_brackets where alliance_id = ${allianceId} and (status = 'active' or local_date >= ${day}::date - 1)
                        order by created_at desc limit 1`;
  return b ? { id: b.id as string, game: b.game as string, status: b.status as string, championId: (b.champion_id as string | null) ?? null } : null;
}

export async function bracketRoutes(app: FastifyInstance) {
  app.get<{ Params: { id: string } }>('/v1/brackets/:id', { onRequest: auth }, async req => {
    if (!z.string().uuid().safeParse(req.params.id).success) throw new HttpError(404, 'not_found');
    const [b] = await sql`select alliance_id from game_brackets where id = ${req.params.id}`;
    if (!b) throw new HttpError(404, 'not_found');
    const [m] = await sql`select 1 from alliance_members where alliance_id = ${b.alliance_id} and user_id = ${req.userId} and status <> 'left'`;
    if (!m) throw new HttpError(404, 'not_found');
    return { bracket: await bracketView(req.params.id, req.userId) };
  });
}

/** A member left: their matches go to the opponent, and the bracket moves on without them. */
export async function memberLeftBrackets(allianceId: string) {
  const open = await sql`select id from game_brackets where alliance_id = ${allianceId} and status = 'active'`;
  for (const b of open) await advance(b.id);
}

/** Tests. */
export const advanceNow = advance;
export const settleMatchAtDeadline = settleAtDeadline;
