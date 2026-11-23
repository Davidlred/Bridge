import { MoveError, seeded, type DuelModule, type GameMeta } from './module.js';
import { insideRect, simulate, withHits, type Body, type Recording } from './physics.js';

/**
 * 8-Ball, simplified: no called pockets. The first ball potted decides who has solids (1–7) and who has stripes (9–15).
 * Pot one of yours and you go again. A foul (the cue ball potted, nothing hit, or the other group hit first) passes the
 * turn, and a potted cue ball goes back on the spot. Pot the 8 after clearing your group to win; pot it early, or on a
 * foul, and you lose. After 60 shots, whoever has fewer balls left wins.
 *
 * Table units: 2 long (x) by 1 wide (y). The app sends the aim angle and power; the server runs the shot and sends back
 * the recording.
 */
export const R = 0.03, MAX_SHOTS = 60;
const TABLE = { x0: 0, y0: 0, x1: 2, y1: 1 };
export const POCKETS: [number, number, number][] = [[0, 0, 0.07], [1, 0, 0.06], [2, 0, 0.07], [0, 1, 0.07], [1, 1, 0.06], [2, 1, 0.07]];
const HEAD: [number, number] = [0.5, 0.5];

type Group = 'solids' | 'stripes';
export interface Ball { id: number; x: number; y: number; in: boolean }
export interface PoolState {
  players: [string, string];
  balls: Ball[];
  groups: Record<string, Group | null>;
  next: 0 | 1;
  shots: number;
  last: { by: string; angle: number; power: number; rec: Recording; potted: number[]; foul: string | null; firstHit: number | null; again: boolean } | null;
  winner: 0 | 1 | null;
  draw: boolean;
  /** "8 potted early" and the like, for the result screen. */
  how: string | null;
}

const groupOf = (id: number): Group | null => (id >= 1 && id <= 7 ? 'solids' : id >= 9 ? 'stripes' : null);
const left = (s: PoolState, g: Group | null) => (g ? s.balls.filter(b => !b.in && groupOf(b.id) === g).length : 7);

export function rack(seed: string): Ball[] {
  const rand = seeded(`${seed}:rack`);
  const others = [1, 2, 3, 4, 5, 6, 7, 9, 10, 11, 12, 13, 14, 15];
  for (let i = others.length - 1; i > 0; i--) { const j = Math.floor(rand() * (i + 1)); [others[i], others[j]] = [others[j]!, others[i]!]; }
  const spots: [number, number][] = [];
  for (let row = 0; row < 5; row++) for (let i = 0; i <= row; i++) spots.push([1.5 + row * R * 2 * 0.87, 0.5 + (i - row / 2) * R * 2.02]);
  const ids: number[] = [];
  let k = 0;
  for (let s = 0; s < 15; s++) ids.push(s === 4 ? 8 : others[k++]!);
  // The back corners hold one solid and one stripe.
  const c1 = 10, c2 = 14;
  if (groupOf(ids[c1]!) === groupOf(ids[c2]!)) {
    const swap = ids.findIndex((id, i) => i !== c1 && i !== c2 && id !== 8 && groupOf(id) !== groupOf(ids[c1]!));
    [ids[c2], ids[swap]] = [ids[swap]!, ids[c2]!];
  }
  return [{ id: 0, x: HEAD[0], y: HEAD[1], in: false }, ...spots.map(([x, y], i) => ({ id: ids[i]!, x, y, in: false }))];
}

/** The cue ball back on the head spot, or the nearest free place along the line. */
function respot(balls: Ball[]): [number, number] {
  for (let k = 0; k < 40; k++) {
    const y = 0.5 + (k % 2 ? 1 : -1) * Math.ceil(k / 2) * R * 2.2;
    if (balls.every(b => b.in || b.id === 0 || Math.hypot(b.x - HEAD[0], b.y - y) > R * 2.05)) return [HEAD[0], y];
  }
  return HEAD;
}

/** Runs one shot. */
export function shoot(balls: Ball[], angle: number, power: number) {
  const bodies: Body[] = balls.filter(b => !b.in).map(b => ({ id: b.id, x: b.x, y: b.y, vx: 0, vy: 0, r: R }));
  const cue = bodies.find(b => b.id === 0)!;
  const speed = 0.3 + power * 4.2;
  cue.vx = Math.cos(angle) * speed; cue.vy = Math.sin(angle) * speed;
  let firstHit: number | null = null;
  const potted: number[] = [];
  const rec = withHits((a, b) => { if (firstHit === null && (a === 0 || b === 0)) firstHit = a === 0 ? b : a; }, () => simulate(bodies, {
    friction: 0.55, restitution: 0.95, maxSeconds: 10,
    bound: b => {
      for (const [px, py, pr] of POCKETS) if (Math.hypot(b.x - px, b.y - py) < pr) { b.gone = true; b.vx = 0; b.vy = 0; b.x = px; b.y = py; potted.push(b.id); return; }
      insideRect(b, TABLE, 0.78);
    },
  }));
  const after = balls.map(b => { const body = bodies.find(x => x.id === b.id); return body ? { ...b, x: Math.round(body.x * 1e4) / 1e4, y: Math.round(body.y * 1e4) / 1e4, in: !!body.gone } : b; });
  return { balls: after, rec, potted, firstHit: firstHit as number | null };
}

const meta: GameMeta = { id: 'pool', name: '8-Ball', icon: 'radio_button_checked', mode: 'duel_turns', minPlayers: 2, maxPlayers: 2, avgMinutes: 3, tile: 0, scored: true };
const over = (s: PoolState) => s.winner !== null || s.draw;

export const poolModule: DuelModule<PoolState, { angle: number; power: number }> = {
  kind: 'duel', meta,
  createState: (players, seed) => ({ players, balls: rack(seed), groups: { [players[0]]: null, [players[1]]: null }, next: seeded(`${seed}:first`)() < 0.5 ? 0 : 1, shots: 0, last: null, winner: null, draw: false, how: null }),
  turn: s => (over(s) ? null : s.players[s.next]),
  applyMove: (s, playerId, move) => {
    if (over(s)) throw new MoveError('game_over');
    if (s.players[s.next] !== playerId) throw new MoveError('not_your_turn');
    const angle = Number(move?.angle), power = Number(move?.power);
    if (!Number.isFinite(angle) || !Number.isFinite(power) || power < 0 || power > 1) throw new MoveError('bad_move');
    const me = s.next, them = (1 - me) as 0 | 1;
    const mine = s.groups[playerId] ?? null;
    const shot = shoot(s.balls, angle, power);
    let balls = shot.balls;
    const scratch = shot.potted.includes(0);
    const clearedBefore = mine !== null && left(s, mine) === 0;
    let foul: string | null = scratch ? 'scratch' : shot.firstHit === null ? 'no_hit'
      : mine && groupOf(shot.firstHit) !== mine && !(shot.firstHit === 8 && clearedBefore) ? 'wrong_ball' : null;
    if (scratch) { const [x, y] = respot(balls); balls = balls.map(b => (b.id === 0 ? { ...b, x, y, in: false } : b)); }
    const groups = { ...s.groups };
    const pots = shot.potted.filter(id => id !== 0 && id !== 8);
    if (!mine && !foul && pots.length) {
      const g = groupOf(pots[0]!)!;
      groups[playerId] = g; groups[s.players[them]] = g === 'solids' ? 'stripes' : 'solids';
    }
    const myGroup = groups[playerId] ?? null;
    let next: PoolState = { ...s, balls, groups, shots: s.shots + 1 };
    if (shot.potted.includes(8)) {
      const wins = !foul && clearedBefore;
      const how = foul ? 'eight_on_foul' : clearedBefore ? null : 'eight_early';
      return { ...next, winner: wins ? me : them, how, last: { by: playerId, angle, power, rec: shot.rec, potted: shot.potted, foul, firstHit: shot.firstHit, again: false } };
    }
    const again = !foul && pots.some(id => groupOf(id) === myGroup || myGroup === null);
    if (!foul && !again && pots.length === 0) foul = null;
    next = { ...next, next: again ? me : them, last: { by: playerId, angle, power, rec: shot.rec, potted: shot.potted, foul, firstHit: shot.firstHit, again } };
    if (next.shots >= MAX_SHOTS) {
      const a = left(next, groups[s.players[0]] ?? null), b = left(next, groups[s.players[1]] ?? null);
      return { ...next, winner: a === b ? null : a < b ? 0 : 1, draw: a === b, how: 'shot_limit' };
    }
    return next;
  },
  isOver: s => ({
    done: over(s), winnerId: s.winner !== null ? s.players[s.winner] : undefined,
    scores: Object.fromEntries(s.players.map(p => [p, s.groups[p] ? 7 - left(s, s.groups[p]!) : 0])),
  }),
  viewFor: s => s,
};
