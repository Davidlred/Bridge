/**
 * Small deterministic 2D physics for the ball games (8-Ball, Mini Golf, Push Out). The server runs every shot and sends
 * the apps a recording to play back (positions at 30 frames a second), so the result never depends on a phone.
 * Fixed time step, no randomness: the same shot from the same position always does the same thing.
 */
export interface Body { id: number; x: number; y: number; vx: number; vy: number; r: number; gone?: boolean }
export interface Rect { x0: number; y0: number; x1: number; y1: number }

export interface SimOptions {
  /** Slowing down, in units per second per second. */
  friction: number;
  /** Bounce between balls (1 = perfectly elastic). */
  restitution: number;
  maxSeconds?: number;
  /** Called after each step for every body still in play: walls, pockets, holes, falling off. */
  bound?: (b: Body, step: number) => void;
}

export interface Recording {
  /** Which bodies the frames describe, in order. */
  ids: number[];
  /** One entry per frame: x, y for each id (rounded to 3 decimals); a body that's gone keeps its last place. */
  frames: number[][];
  /** Frame index at which each body went out of play (pocketed, sunk, fell off), if it did. */
  goneAt: Record<number, number>;
}

const DT = 1 / 240, PER_FRAME = 8, STOP = 0.004;
const round = (v: number) => Math.round(v * 1000) / 1000;

export function simulate(bodies: Body[], opt: SimOptions): Recording {
  const startPos = new Map(bodies.map(b => [b.id, [b.x, b.y]]));
  const raw: number[][] = [];
  const goneAt: Record<number, number> = {};
  const snapshot = () => raw.push(bodies.flatMap(b => [round(b.x), round(b.y)]));
  snapshot();
  const steps = Math.ceil((opt.maxSeconds ?? 12) / DT);
  for (let step = 1; step <= steps; step++) {
    for (const b of bodies) {
      if (b.gone) continue;
      b.x += b.vx * DT; b.y += b.vy * DT;
      const v = Math.hypot(b.vx, b.vy);
      if (v > 0) {
        const nv = Math.max(0, v - opt.friction * DT);
        if (nv < STOP) { b.vx = 0; b.vy = 0; } else { b.vx *= nv / v; b.vy *= nv / v; }
      }
    }
    collide(bodies, opt.restitution);
    if (opt.bound) for (const b of bodies) if (!b.gone) {
      opt.bound(b, step);
      if (b.gone && goneAt[b.id] === undefined) goneAt[b.id] = Math.ceil(step / PER_FRAME);
    }
    if (step % PER_FRAME === 0) snapshot();
    if (bodies.every(b => b.gone || (b.vx === 0 && b.vy === 0))) { if (step % PER_FRAME) snapshot(); break; }
  }
  // Only the bodies that moved go in the recording.
  const moved = bodies.filter(b => { const [x, y] = startPos.get(b.id)!; return b.gone || Math.abs(b.x - x!) > 1e-6 || Math.abs(b.y - y!) > 1e-6; });
  const idx = moved.map(b => bodies.indexOf(b));
  return { ids: moved.map(b => b.id), frames: raw.map(f => idx.flatMap(i => [f[i * 2]!, f[i * 2 + 1]!])), goneAt };
}

/** Ball against ball: equal masses, impulse along the line between centres, then pushed apart. */
function collide(bodies: Body[], e: number) {
  for (let i = 0; i < bodies.length; i++) {
    const a = bodies[i]!;
    if (a.gone) continue;
    for (let j = i + 1; j < bodies.length; j++) {
      const b = bodies[j]!;
      if (b.gone) continue;
      const dx = b.x - a.x, dy = b.y - a.y, min = a.r + b.r;
      const d2 = dx * dx + dy * dy;
      if (d2 >= min * min || d2 === 0) continue;
      const d = Math.sqrt(d2), nx = dx / d, ny = dy / d;
      const vn = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny;
      if (vn < 0) {
        const jn = (-(1 + e) * vn) / 2;
        a.vx -= jn * nx; a.vy -= jn * ny; b.vx += jn * nx; b.vy += jn * ny;
        onHit?.(a.id, b.id);
      }
      const push = (min - d) / 2;
      a.x -= nx * push; a.y -= ny * push; b.x += nx * push; b.y += ny * push;
    }
  }
}

/** Set for the length of one simulation, to learn which ball the cue ball touched first (8-Ball fouls). */
let onHit: ((a: number, b: number) => void) | null = null;
export function withHits<T>(fn: (a: number, b: number) => void, run: () => T): T {
  onHit = fn;
  try { return run(); } finally { onHit = null; }
}

/** Bounce off the inside of a rectangle. */
export function insideRect(b: Body, r: Rect, e: number) {
  if (b.x - b.r < r.x0) { b.x = r.x0 + b.r; b.vx = Math.abs(b.vx) * e; }
  if (b.x + b.r > r.x1) { b.x = r.x1 - b.r; b.vx = -Math.abs(b.vx) * e; }
  if (b.y - b.r < r.y0) { b.y = r.y0 + b.r; b.vy = Math.abs(b.vy) * e; }
  if (b.y + b.r > r.y1) { b.y = r.y1 - b.r; b.vy = -Math.abs(b.vy) * e; }
}

/** Bounce off the outside of a solid block. */
export function outsideRect(b: Body, r: Rect, e: number) {
  const cx = Math.max(r.x0, Math.min(b.x, r.x1)), cy = Math.max(r.y0, Math.min(b.y, r.y1));
  const dx = b.x - cx, dy = b.y - cy;
  const d = Math.hypot(dx, dy);
  if (d >= b.r) return;
  let nx: number, ny: number;
  if (d === 0) {
    // Centre inside the block (a very fast ball): out through the nearest side.
    const opts: [number, number, number][] = [[b.x - r.x0, -1, 0], [r.x1 - b.x, 1, 0], [b.y - r.y0, 0, -1], [r.y1 - b.y, 0, 1]];
    [, nx, ny] = opts.reduce((m, o) => (o[0] < m[0] ? o : m));
    if (nx) b.x = (nx < 0 ? r.x0 : r.x1) + nx * b.r; else b.y = (ny < 0 ? r.y0 : r.y1) + ny * b.r;
  } else {
    nx = dx / d; ny = dy / d;
    b.x = cx + nx * b.r; b.y = cy + ny * b.r;
  }
  const vn = b.vx * nx + b.vy * ny;
  if (vn < 0) { b.vx -= (1 + e) * vn * nx; b.vy -= (1 + e) * vn * ny; }
}
