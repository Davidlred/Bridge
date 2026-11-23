import { MoveError, seeded, type GameMeta } from './module.js';
import type { LiveDef } from './live.js';

/**
 * Hoops: 30 seconds, swipe to shoot, most baskets wins. Everyone shoots at the same time on their own phone. The hoop
 * moves along the rim line between shots, and everyone gets the same hoop positions in the same order (from the seed).
 * The app sends the swipe (angle, power); the server decides: clean in, off the rim (in or out from the seed), or a miss.
 *
 * Court units: the shooter at (0, 0), the hoop 1.0 ahead and up to 0.3 to either side.
 */
export const HOOPS_MS = 30_000, COOLDOWN_MS = 550, HOOP_Y = 1, CLEAN = 0.07, RIM = 0.12;

interface Shooter { made: number; shots: number; nextAt: number }
export interface HoopsWorld { seed: string; startsAt: number; endsAt: number; players: string[]; shooters: Record<string, Shooter> }

export const hoopX = (seed: string, i: number) => (seeded(`${seed}:hoop:${i}`)() - 0.5) * 0.6;
const round = (v: number) => Math.round(v * 1000) / 1000;

export function shot(angle: number, power: number) {
  const d = 0.5 + power * 1.0;
  const land: [number, number] = [Math.sin(angle) * d, Math.cos(angle) * d];
  const path: [number, number, number][] = [];
  for (let i = 0; i <= 16; i++) { const t = i / 16; path.push([round(land[0] * t), round(land[1] * t), round(4 * 0.5 * t * (1 - t) + 0.2 * (1 - t))]); }
  return { land: [round(land[0]), round(land[1])] as [number, number], path };
}

const meta: GameMeta = { id: 'hoops', name: 'Hoops', icon: 'sports_basketball', mode: 'everyone', minPlayers: 2, maxPlayers: 15, avgMinutes: 1, tile: 1 };

export const hoopsDef: LiveDef<HoopsWorld> = {
  meta,
  create: (players, seed, _perks, now) => ({
    // A three-second count-in before the clock starts.
    seed, startsAt: now + 3000, endsAt: now + 3000 + HOOPS_MS, players,
    shooters: Object.fromEntries(players.map(p => [p, { made: 0, shots: 0, nextAt: 0 }])),
  }),
  move: (w, playerId, move, now) => {
    const s = w.shooters[playerId];
    if (!s) throw new MoveError('not_in_game');
    if (now < w.startsAt) throw new MoveError('not_started');
    if (now > w.endsAt) throw new MoveError('time_up');
    if (now < s.nextAt) throw new MoveError('slow_down');
    const angle = Number(move?.angle), power = Number(move?.power);
    if (!Number.isFinite(angle) || !Number.isFinite(power) || Math.abs(angle) > 0.8 || power < 0 || power > 1) throw new MoveError('bad_move');
    const hx = hoopX(w.seed, s.shots);
    const { land, path } = shot(angle, power);
    const d = Math.hypot(land[0] - hx, land[1] - HOOP_Y);
    const rim = d > CLEAN && d <= RIM;
    const made = d <= CLEAN || (rim && seeded(`${w.seed}:${playerId}:${s.shots}`)() < 0.45);
    s.shots++; if (made) s.made++;
    s.nextAt = now + COOLDOWN_MS;
    return { reply: { i: s.shots - 1, hoopX: round(hx), land, path, made, rim, next: round(hoopX(w.seed, s.shots)), made_total: s.made } };
  },
  done: (w, now) => now > w.endsAt + 500,
  scores: w => Object.fromEntries(Object.entries(w.shooters).map(([p, s]) => [p, s.made])),
  view: w => ({ startsAt: w.startsAt, endsAt: w.endsAt, players: Object.entries(w.shooters).map(([userId, s]) => ({ userId, made: s.made, shots: s.shots, hoopX: round(hoopX(w.seed, s.shots)) })) }),
};
