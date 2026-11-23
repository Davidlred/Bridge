import { MoveError, seeded, type DuelModule, type GameMeta } from './module.js';

/**
 * Darts, 301: each player starts on 301 and throws three darts a turn, and the first to reach exactly 0 with a double
 * wins. Going below 0, to 1, or to 0 without a double is a bust: the turn's score is wiped. The player sends where they
 * aimed; the server adds the throw's wobble from the seed (the same for every replay) and scores where it lands.
 * Streak perk: one re-throw a game (the last dart of the current turn, before the turn ends).
 * After 10 turns each, the lower total wins (a draw if they're level), so a game stays short.
 */
export const START = 301, MAX_TURNS = 10, WOBBLE = 0.075;
/** Board units: 1 = the outside of the double ring. */
const BULL = 6.35 / 170, OUTER_BULL = 15.9 / 170, TREBLE_IN = 99 / 170, TREBLE_OUT = 107 / 170, DOUBLE_IN = 162 / 170;
export const SECTORS = [20, 1, 18, 4, 13, 6, 10, 15, 2, 17, 3, 19, 7, 16, 8, 11, 14, 9, 12, 5];

export interface Dart { x: number; y: number; score: number; mult: 0 | 1 | 2 | 3; label: string }
export interface DartsState {
  players: [string, string];
  remaining: [number, number];
  next: 0 | 1;
  darts: Dart[];
  turnStart: number;
  turns: [number, number];
  thrown: number;
  bust: boolean;
  /** The last finished turn, so the other player sees what happened. */
  lastTurn: { by: string; darts: Dart[]; bust: boolean } | null;
  winner: 0 | 1 | null;
  draw: boolean;
  perks: string[];
  perkUsed: string[];
}

/** Where a dart lands on the board and what it scores. x to the right, y up, from the centre. */
export function scoreAt(x: number, y: number): Omit<Dart, 'x' | 'y'> {
  const r = Math.hypot(x, y);
  if (r <= BULL) return { score: 50, mult: 2, label: 'BULL' };
  if (r <= OUTER_BULL) return { score: 25, mult: 1, label: '25' };
  if (r > 1) return { score: 0, mult: 0, label: 'MISS' };
  // Angle clockwise from straight up; 20 is centred on the top.
  const deg = ((Math.atan2(x, y) * 180) / Math.PI + 360 + 9) % 360;
  const n = SECTORS[Math.floor(deg / 18)]!;
  if (r >= TREBLE_IN && r <= TREBLE_OUT) return { score: n * 3, mult: 3, label: `T${n}` };
  if (r >= DOUBLE_IN) return { score: n * 2, mult: 2, label: `D${n}` };
  return { score: n, mult: 1, label: String(n) };
}

/** Two independent normal numbers from two uniform ones (Box-Muller). */
function wobble(rand: () => number): [number, number] {
  const u = Math.max(rand(), 1e-9), v = rand();
  const m = Math.sqrt(-2 * Math.log(u));
  return [m * Math.cos(2 * Math.PI * v) * WOBBLE, m * Math.sin(2 * Math.PI * v) * WOBBLE];
}

const meta: GameMeta = { id: 'darts', name: 'Darts', icon: 'adjust', mode: 'duel_turns', minPlayers: 2, maxPlayers: 2, avgMinutes: 3, tile: 2, scored: true };
const over = (s: DartsState) => s.winner !== null || s.draw;

function endTurn(s: DartsState, bust: boolean): DartsState {
  const remaining = [...s.remaining] as [number, number];
  if (bust) remaining[s.next] = s.turnStart;
  const turns = [...s.turns] as [number, number];
  turns[s.next]++;
  const next = (1 - s.next) as 0 | 1;
  const lastTurn = { by: s.players[s.next], darts: s.darts, bust };
  // Turn limit: once both have had their turns, the lower total wins.
  if (turns[0] >= MAX_TURNS && turns[1] >= MAX_TURNS) {
    const winner = remaining[0] === remaining[1] ? null : remaining[0] < remaining[1] ? 0 : 1;
    return { ...s, remaining, turns, darts: [], lastTurn, bust, winner, draw: winner === null };
  }
  return { ...s, remaining, turns, next, darts: [], turnStart: remaining[next], lastTurn, bust };
}

export const dartsModule: DuelModule<DartsState, { t: 'throw'; x: number; y: number } | { t: 'rethrow' }> = {
  kind: 'duel', meta,
  createState: (players, seed, ctx) => ({
    players, remaining: [START, START], next: seeded(`${seed}:first`)() < 0.5 ? 0 : 1, darts: [], turnStart: START, turns: [0, 0], thrown: 0,
    bust: false, lastTurn: null, winner: null, draw: false, perks: ctx.perks.filter(p => players.includes(p)), perkUsed: [],
  }),
  turn: s => (over(s) ? null : s.players[s.next]),
  applyMove: (s, playerId, move) => {
    if (over(s)) throw new MoveError('game_over');
    if (s.players[s.next] !== playerId) throw new MoveError('not_your_turn');
    if (move?.t === 'rethrow') {
      if (!s.perks.includes(playerId) || s.perkUsed.includes(playerId)) throw new MoveError('no_perk');
      const last = s.darts[s.darts.length - 1];
      if (!last) throw new MoveError('nothing_to_rethrow');
      const remaining = [...s.remaining] as [number, number];
      remaining[s.next] += last.score;
      return { ...s, remaining, darts: s.darts.slice(0, -1), perkUsed: [...s.perkUsed, playerId] };
    }
    if (move?.t !== 'throw') throw new MoveError('bad_move');
    const ax = Number(move.x), ay = Number(move.y);
    if (!Number.isFinite(ax) || !Number.isFinite(ay) || Math.hypot(ax, ay) > 1.4) throw new MoveError('bad_move');
    // A fresh wobble for every dart thrown in this match (re-throws included), always the same for the same match.
    const [dx, dy] = wobble(seeded(`darts:${s.players.join(':')}:${s.thrown}:${s.remaining.join(':')}`));
    const x = Math.round((ax + dx) * 1000) / 1000, y = Math.round((ay + dy) * 1000) / 1000;
    const dart: Dart = { x, y, ...scoreAt(x, y) };
    const left = s.remaining[s.next] - dart.score;
    const darts = [...s.darts, dart];
    const base = { ...s, darts, thrown: s.thrown + 1, bust: false };
    if (left < 0 || left === 1 || (left === 0 && dart.mult !== 2)) return endTurn({ ...base, darts }, true);
    const remaining = [...s.remaining] as [number, number];
    remaining[s.next] = left;
    if (left === 0) return { ...base, remaining, winner: s.next, lastTurn: { by: playerId, darts, bust: false } };
    return darts.length === 3 ? endTurn({ ...base, remaining }, false) : { ...base, remaining };
  },
  isOver: s => ({
    done: over(s), winnerId: s.winner !== null ? s.players[s.winner] : undefined,
    scores: { [s.players[0]]: START - s.remaining[0], [s.players[1]]: START - s.remaining[1] },
  }),
  viewFor: s => s,
};
