import { MoveError, type GameMeta } from './module.js';
import { simulate, type Body, type Recording } from './physics.js';
import type { LiveDef } from './live.js';

/**
 * Push Out: everyone's puck on a round sheet of ice. Each round everyone has 8 seconds to aim a flick, then all the
 * pucks slide at once and knock each other about; a puck that slides off the edge is out. Last one on wins. After 10
 * rounds, the pucks still on are ranked by how close they are to the middle.
 *
 * Arena units: radius 1, centred on (0, 0).
 */
export const AIM_MS = 8000, MAX_ROUNDS = 10, PUCK_R = 0.09, ARENA_R = 1;

interface Puck { userId: string; x: number; y: number; out: boolean; outRound: number | null; aim: { angle: number; power: number } | null }
export interface PushWorld { pucks: Puck[]; round: number; phase: 'aim' | 'slide'; endsAt: number; last: { round: number; rec: Recording; out: string[] } | null; over: boolean }

const round = (v: number) => Math.round(v * 1000) / 1000;
const meta: GameMeta = { id: 'push', name: 'Push Out', icon: 'trip_origin', mode: 'everyone', minPlayers: 3, maxPlayers: 15, avgMinutes: 2, tile: 1 };

/** Slides every puck at once. Index in the list is the id in the recording. */
export function slide(pucks: Puck[]) {
  const bodies: Body[] = pucks.map((p, i) => {
    const speed = p.out || !p.aim ? 0 : 0.4 + p.aim.power * 2.4;
    return { id: i, x: p.x, y: p.y, vx: p.aim ? Math.sin(p.aim.angle) * speed : 0, vy: p.aim ? Math.cos(p.aim.angle) * speed : 0, r: PUCK_R, gone: p.out };
  });
  const rec = simulate(bodies, { friction: 0.9, restitution: 0.92, maxSeconds: 6, bound: b => { if (Math.hypot(b.x, b.y) > ARENA_R) { b.gone = true; } } });
  return { rec, bodies };
}

function resolve(w: PushWorld, now: number) {
  const { rec, bodies } = slide(w.pucks);
  const out: string[] = [];
  w.pucks.forEach((p, i) => {
    const b = bodies[i]!;
    if (!p.out && b.gone) { p.out = true; p.outRound = w.round; out.push(p.userId); }
    p.x = round(b.x); p.y = round(b.y); p.aim = null;
  });
  w.last = { round: w.round, rec, out };
  w.phase = 'slide';
  // The recording plays for its length, then a beat before the next round.
  w.endsAt = now + Math.ceil(rec.frames.length / 30 * 1000) + 1200;
  const left = w.pucks.filter(p => !p.out).length;
  if (left <= 1 || w.round >= MAX_ROUNDS) w.over = true;
  return { round: w.round, rec, out, pucks: w.pucks.map(p => ({ userId: p.userId, x: p.x, y: p.y, out: p.out })) };
}

export const pushDef: LiveDef<PushWorld> = {
  meta,
  create: (players, _seed, _perks, now) => ({
    pucks: players.map((userId, i) => {
      const a = (2 * Math.PI * i) / players.length;
      return { userId, x: round(Math.sin(a) * 0.6), y: round(Math.cos(a) * 0.6), out: false, outRound: null, aim: null };
    }),
    round: 1, phase: 'aim', endsAt: now + AIM_MS, last: null, over: false,
  }),
  move: (w, playerId, move) => {
    const p = w.pucks.find(x => x.userId === playerId);
    if (!p) throw new MoveError('not_in_game');
    if (p.out) throw new MoveError('out');
    if (w.phase !== 'aim') throw new MoveError('not_aiming');
    const angle = Number(move?.angle), power = Number(move?.power);
    if (!Number.isFinite(angle) || !Number.isFinite(power) || power < 0 || power > 1) throw new MoveError('bad_move');
    p.aim = { angle, power };
    // Everyone still in has aimed: slide now.
    return { reply: { aimed: true } };
  },
  tick: (w, now) => {
    if (w.over && w.phase === 'slide' && now >= w.endsAt) return null;
    if (w.phase === 'aim' && (now >= w.endsAt || w.pucks.every(p => p.out || p.aim))) return resolve(w, now);
    if (w.phase === 'slide' && now >= w.endsAt && !w.over) { w.round++; w.phase = 'aim'; w.endsAt = now + AIM_MS; return { round: w.round, aim: true }; }
    return null;
  },
  done: (w, now) => w.over && w.phase === 'slide' && now >= w.endsAt,
  // Still on beats out; among those on, nearer the middle is better; among those out, later is better.
  scores: w => Object.fromEntries(w.pucks.map(p => [p.userId, p.out ? (p.outRound ?? 0) * 10 : 1000 - Math.round(Math.hypot(p.x, p.y) * 100)])),
  view: w => ({
    round: w.round, maxRounds: MAX_ROUNDS, phase: w.phase, endsAt: w.endsAt, arena: ARENA_R, puckR: PUCK_R,
    pucks: w.pucks.map(p => ({ userId: p.userId, x: p.x, y: p.y, out: p.out, aimed: !!p.aim })), last: w.last,
  }),
};
