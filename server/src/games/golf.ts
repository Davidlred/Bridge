import { MoveError, seeded, type GameMeta } from './module.js';
import { insideRect, outsideRect, simulate, type Body, type Rect } from './physics.js';
import type { LiveDef } from './live.js';

/**
 * Mini Golf: six holes, drag to putt, fewest strokes wins. Everyone plays the same holes (from the seed) at the same
 * time, each on their own ball. The server runs each putt and sends back the recording. Six strokes on a hole and the
 * ball is picked up for a 7. Streak perk: one mulligan a game (take the last stroke again). Four minutes at most.
 *
 * Course units: 1 wide by 2 long, tee near the bottom (y small), cup near the top.
 */
export const HOLES = 6, MAX_STROKES = 6, PICKUP = 7, GOLF_MS = 240_000, BALL_R = 0.03, CUP_R = 0.055;
const COURSE: Rect = { x0: 0, y0: 0, x1: 1, y1: 2 };
const TEE: [number, number] = [0.5, 0.15];

export interface Hole { cup: [number, number]; blocks: Rect[] }
interface Golfer { hole: number; strokes: number[]; ball: [number, number]; done: boolean; prev: { ball: [number, number]; hole: number; strokes: number[] } | null; mulligan: boolean }
export interface GolfWorld { holes: Hole[]; startsAt: number; endsAt: number; golfers: Record<string, Golfer>; perks: string[] }

const round = (v: number) => Math.round(v * 1000) / 1000;

export function makeHoles(seed: string): Hole[] {
  const rand = seeded(`${seed}:holes`);
  return Array.from({ length: HOLES }, (_, h) => {
    const cup: [number, number] = [round(0.2 + rand() * 0.6), round(1.62 + rand() * 0.22)];
    const blocks: Rect[] = [];
    const count = h === 0 ? 0 : Math.min(3, 1 + Math.floor(rand() * 3));
    for (let i = 0; i < count; i++) {
      const w = 0.15 + rand() * 0.35, hgt = 0.06 + rand() * 0.08;
      const x0 = rand() * (1 - w), y0 = 0.45 + i * 0.35 + rand() * 0.15;
      blocks.push({ x0: round(x0), y0: round(y0), x1: round(x0 + w), y1: round(y0 + hgt) });
    }
    return { cup, blocks };
  });
}

/** One putt from [from] on [hole]. */
export function putt(hole: Hole, from: [number, number], angle: number, power: number) {
  const ball: Body = { id: 0, x: from[0], y: from[1], vx: 0, vy: 0, r: BALL_R };
  const speed = 0.15 + power * 2.6;
  ball.vx = Math.sin(angle) * speed; ball.vy = Math.cos(angle) * speed;
  let sunk = false;
  const rec = simulate([ball], {
    friction: 0.85, restitution: 1, maxSeconds: 8,
    bound: b => {
      const d = Math.hypot(b.x - hole.cup[0], b.y - hole.cup[1]);
      // Over the cup slowly enough: in. Too fast and it runs over the hole.
      if (d < CUP_R && Math.hypot(b.vx, b.vy) < 1.3) { sunk = true; b.gone = true; b.x = hole.cup[0]; b.y = hole.cup[1]; b.vx = 0; b.vy = 0; return; }
      for (const r of hole.blocks) outsideRect(b, r, 0.7);
      insideRect(b, COURSE, 0.7);
    },
  });
  return { rec, sunk, at: [round(ball.x), round(ball.y)] as [number, number] };
}

const meta: GameMeta = { id: 'golf', name: 'Mini Golf', icon: 'golf_course', mode: 'everyone', minPlayers: 2, maxPlayers: 15, avgMinutes: 3, tile: 3 };
/** Strokes so far; a player the clock caught takes a 7 on the hole they're on and on every hole after it. */
const total = (g: Golfer) => g.done ? g.strokes.reduce((a, b) => a + b, 0) : g.strokes.slice(0, g.hole).reduce((a, b) => a + b, 0) + (HOLES - g.hole) * PICKUP;

export const golfDef: LiveDef<GolfWorld> = {
  meta,
  lowerWins: true,
  create: (players, seed, perks, now) => ({
    holes: makeHoles(seed), startsAt: now, endsAt: now + GOLF_MS, perks,
    golfers: Object.fromEntries(players.map(p => [p, { hole: 0, strokes: [0], ball: TEE, done: false, prev: null, mulligan: false }])),
  }),
  move: (w, playerId, move, now) => {
    const g = w.golfers[playerId];
    if (!g) throw new MoveError('not_in_game');
    if (g.done) throw new MoveError('finished');
    if (now > w.endsAt) throw new MoveError('time_up');
    if (move?.t === 'mulligan') {
      if (!w.perks.includes(playerId) || g.mulligan || !g.prev) throw new MoveError('no_perk');
      g.ball = g.prev.ball; g.hole = g.prev.hole; g.strokes = g.prev.strokes; g.mulligan = true; g.prev = null; g.done = false;
      return { reply: { mulligan: true, hole: g.hole, ball: g.ball, strokes: g.strokes[g.hole] } };
    }
    const angle = Number(move?.angle), power = Number(move?.power);
    if (!Number.isFinite(angle) || !Number.isFinite(power) || power < 0 || power > 1) throw new MoveError('bad_move');
    g.prev = { ball: g.ball, hole: g.hole, strokes: g.strokes.slice() };
    const hole = w.holes[g.hole]!;
    const r = putt(hole, g.ball, angle, power);
    g.strokes[g.hole] = (g.strokes[g.hole] ?? 0) + 1;
    const holeDone = r.sunk || g.strokes[g.hole]! >= MAX_STROKES;
    if (!r.sunk && holeDone) g.strokes[g.hole] = PICKUP;
    const played = g.hole;
    if (holeDone) {
      if (g.hole === HOLES - 1) g.done = true;
      else { g.hole++; g.strokes[g.hole] = 0; }
      g.ball = TEE;
    } else g.ball = r.at;
    return { reply: { hole: played, rec: r.rec, sunk: r.sunk, picked: holeDone && !r.sunk, strokes: g.strokes[played], next: { hole: g.hole, ball: g.ball }, done: g.done } };
  },
  done: (w, now) => now > w.endsAt || Object.values(w.golfers).every(g => g.done),
  scores: w => Object.fromEntries(Object.entries(w.golfers).map(([p, g]) => [p, total(g)])),
  view: w => ({
    holes: w.holes, tee: TEE, course: COURSE, cupR: CUP_R, ballR: BALL_R, maxStrokes: MAX_STROKES, endsAt: w.endsAt, perks: w.perks,
    players: Object.entries(w.golfers).map(([userId, g]) => ({ userId, hole: g.hole, strokes: g.strokes, total: g.strokes.reduce((a, b) => a + b, 0), ball: g.ball, done: g.done, mulligan: g.mulligan })),
  }),
};
