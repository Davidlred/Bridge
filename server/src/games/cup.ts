import { MoveError, seeded, type DuelModule, type GameMeta } from './module.js';

/**
 * Cup Pong: ten cups each in a 4-3-2-1 triangle; two throws a turn; first to clear the other side wins. The app sends
 * the swipe (angle and power); the server flies the ball on a simple 2D arc (no wind, the same for everyone) and decides
 * the hit: a clean hit inside the cup, or a rim hit near the edge that drops in or bounces out (from the seed).
 * Streak perk: one re-rack a game, pulling the other side's remaining cups into a tight group before a throw.
 * After 10 turns each, whoever sank more cups wins.
 *
 * Table units: the thrower stands at y = 0, x runs -0.5…0.5 across, the far cups sit around y = 1.75.
 */
export const CUP_R = 0.045, MAX_TURNS = 10, RIM = 1.5;
const GAP = CUP_R * 2.1;

/** Cup centres for the far end: row of 1 nearest the thrower, then 2, 3, 4. */
export const RACK: [number, number][] = [];
for (let row = 0; row < 4; row++) for (let i = 0; i <= row; i++) RACK.push([(i - row / 2) * GAP, 1.55 + row * GAP * 0.87]);

export interface Throw { by: string; angle: number; power: number; land: [number, number]; cup: number | null; rim: boolean; path: [number, number, number][] }
export interface CupState {
  players: [string, string];
  /** Cups still standing on each player's side (indices into that side's spots), keyed by the side's owner. */
  cups: Record<string, number[]>;
  /** Where each side's cups stand (moved by a re-rack). */
  spots: Record<string, [number, number][]>;
  next: 0 | 1;
  throwsLeft: number;
  turns: [number, number];
  thrown: number;
  last: Throw | null;
  winner: 0 | 1 | null;
  draw: boolean;
  perks: string[];
  perkUsed: string[];
}
type CupMove = { t: 'throw'; angle: number; power: number } | { t: 'rerack' };

/** Where the ball lands for a swipe: 0 rad is straight down the table, power 0…1. */
export function flight(angle: number, power: number): { land: [number, number]; path: [number, number, number][] } {
  const d = 0.9 + power * 1.2;
  const land: [number, number] = [Math.sin(angle) * d, Math.cos(angle) * d];
  const path: [number, number, number][] = [];
  for (let i = 0; i <= 20; i++) {
    const t = i / 20;
    path.push([round(land[0] * t), round(land[1] * t), round(4 * 0.35 * t * (1 - t) + 0.1 * (1 - t))]);
  }
  return { land, path };
}
const round = (v: number) => Math.round(v * 1000) / 1000;

const meta: GameMeta = { id: 'cup', name: 'Cup Pong', icon: 'local_drink', mode: 'duel_turns', liveToo: true, minPlayers: 2, maxPlayers: 2, avgMinutes: 3, tile: 4, scored: true };
const over = (s: CupState) => s.winner !== null || s.draw;
const sunkBy = (s: CupState, i: 0 | 1) => 10 - s.cups[s.players[1 - i]!]!.length;

export const cupModule: DuelModule<CupState, CupMove> = {
  kind: 'duel', meta,
  createState: (players, seed, ctx) => ({
    players, cups: { [players[0]]: RACK.map((_, i) => i), [players[1]]: RACK.map((_, i) => i) }, spots: { [players[0]]: RACK, [players[1]]: RACK },
    next: seeded(`${seed}:first`)() < 0.5 ? 0 : 1, throwsLeft: 2, turns: [0, 0], thrown: 0, last: null, winner: null, draw: false,
    perks: ctx.perks.filter(p => players.includes(p)), perkUsed: [],
  }),
  turn: s => (over(s) ? null : s.players[s.next]),
  applyMove: (s, playerId, move) => {
    if (over(s)) throw new MoveError('game_over');
    if (s.players[s.next] !== playerId) throw new MoveError('not_your_turn');
    const them = s.players[1 - s.next]!;
    if (move?.t === 'rerack') {
      if (!s.perks.includes(playerId) || s.perkUsed.includes(playerId)) throw new MoveError('no_perk');
      if (s.throwsLeft !== 2) throw new MoveError('rerack_before_throwing');
      // The remaining cups move into the front spots of the triangle (closest together, nearest the thrower).
      const left = s.cups[them]!;
      const spots = s.spots[them]!.slice();
      left.forEach((cup, k) => { spots[cup] = RACK[k]!; });
      return { ...s, spots: { ...s.spots, [them]: spots }, perkUsed: [...s.perkUsed, playerId] };
    }
    if (move?.t !== 'throw') throw new MoveError('bad_move');
    const angle = Number(move.angle), power = Number(move.power);
    if (!Number.isFinite(angle) || !Number.isFinite(power) || Math.abs(angle) > 0.6 || power < 0 || power > 1) throw new MoveError('bad_move');
    const { land, path } = flight(angle, power);
    const standing = s.cups[them]!;
    let cup: number | null = null, rim = false, best = Infinity;
    for (const c of standing) {
      const [cx, cy] = s.spots[them]![c]!;
      const d = Math.hypot(land[0] - cx, land[1] - cy);
      if (d < best) { best = d; if (d <= CUP_R * RIM) cup = c; }
    }
    if (cup !== null && best > CUP_R) {
      rim = true;
      // Off the rim: in or out, from the seed (the same throw in the same match always does the same thing).
      if (seeded(`cup:${s.players.join(':')}:${s.thrown}`)() >= 0.4) cup = null;
    }
    const cups = cup === null ? s.cups : { ...s.cups, [them]: standing.filter(c => c !== cup) };
    const last: Throw = { by: playerId, angle, power, land: [round(land[0]), round(land[1])], cup, rim, path };
    let next: CupState = { ...s, cups, last, thrown: s.thrown + 1, throwsLeft: s.throwsLeft - 1 };
    if (cups[them]!.length === 0) return { ...next, winner: s.next };
    if (next.throwsLeft > 0) return next;
    const turns = [...s.turns] as [number, number];
    turns[s.next]++;
    next = { ...next, turns, throwsLeft: 2, next: (1 - s.next) as 0 | 1 };
    if (turns[0] >= MAX_TURNS && turns[1] >= MAX_TURNS) {
      const a = sunkBy(next, 0), b = sunkBy(next, 1);
      return { ...next, winner: a === b ? null : a > b ? 0 : 1, draw: a === b };
    }
    return next;
  },
  isOver: s => ({
    done: over(s), winnerId: s.winner !== null ? s.players[s.winner] : undefined,
    scores: { [s.players[0]]: sunkBy(s, 0), [s.players[1]]: sunkBy(s, 1) },
  }),
  viewFor: s => s,
};
