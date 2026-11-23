import { createHash } from 'node:crypto';

/**
 * Game Night Arcade: every game plugs in through one interface, keyed by GameId.
 *
 * Two shapes:
 * - Live games (everyone plays at once in a realtime room): Who's Drifting?, Whose Day?, and later Hoops, Mini Golf,
 *   Push Out. Each keeps its own server-authoritative engine; the module opens a lobby for the alliance.
 * - Duel games (1v1, live or turn by turn): pure state machines. The server creates the state from a seed, validates
 *   every move and decides the result; clients send moves, never results. `viewFor` strips anything the other player
 *   mustn't see (Fleet Battle ships, for example).
 */
export type GameId = 'drift' | 'whose' | 'word' | 'scramble' | 'cup' | 'pool' | 'hoops' | 'darts' | 'golf' | 'four' | 'fleet' | 'push';
export type GameMode = 'everyone' | 'duel_live' | 'duel_turns';

export interface GameMeta {
  id: GameId; name: string;
  /** Material Symbols name; the apps map it to their own icon set. */
  icon: string;
  mode: GameMode;
  /** Also playable live when both players are around (Cup Pong). */
  liveToo?: boolean;
  minPlayers: number; maxPlayers: number; avgMinutes: number;
  /** Points mean something to show ("6–4"); otherwise a result is just who won. */
  scored?: boolean;
  /** Index into the avatar fills, cycled in line-up order. */
  tile: number;
}

export interface Outcome { done: boolean; winnerId?: string; scores: Record<string, number> }

/** What the server knows when a match starts: words from the alliance's plan, and who has a 7-day streak. */
export interface CreateCtx { planWords: string[]; perks: string[] }
/** Server time for each move, so timed rounds (Word Dash, Scramble) are judged by the server's clock. */
export interface MoveCtx { now: number }

/** A 1v1 game as a pure state machine. Throws MoveError for an illegal move. */
export interface DuelModule<S = unknown, Move = unknown> {
  kind: 'duel';
  meta: GameMeta;
  createState(players: [string, string], seed: string, ctx: CreateCtx): S;
  applyMove(state: S, playerId: string, move: Move, ctx: MoveCtx): S;
  isOver(state: S): Outcome;
  /** Whose move it is; null when both may move (Fleet Battle setup) or the game is over. */
  turn(state: S): string | null;
  /** Closes anything the clock has ended (a timed round nobody closed). Returns the same object when nothing changed. */
  settle?(state: S, now: number): S;
  viewFor(state: S, playerId: string): unknown;
  /** The score the next player has to beat, for "Sam scored 42. Beat it?" on the chat card. */
  toBeat?(state: S): number | null;
}

/** A live game for the whole alliance, run by its own engine in the realtime room. */
export interface LiveModule {
  kind: 'live';
  meta: GameMeta;
  /** Opens (or returns) the alliance's lobby and returns the view clients start from. */
  openLobby(allianceId: string): Promise<unknown>;
  /** The open game, if one is running in this alliance. */
  openGame(allianceId: string): unknown | null;
}

export type GameModule = DuelModule | LiveModule;

export class MoveError extends Error {
  constructor(readonly code: string) { super(code); }
}

/**
 * Deterministic randomness from a seed string, so both players get the same board and a replay is identical.
 * mulberry32 over the first 4 bytes of sha256(seed).
 */
export function seeded(seed: string): () => number {
  let a = createHash('sha256').update(seed).digest().readUInt32LE(0);
  return () => {
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
