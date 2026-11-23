import { MoveError, seeded, type DuelModule, type GameMeta } from './module.js';

/**
 * Fleet Battle: each player hides five ships (5, 4, 3, 3, 2) on a 10×10 grid, then they take turns firing. Sink all of
 * the other fleet to win. Ships are placed by hand or by the server ("Place for me"). The other player's ships are never
 * sent to an app until they're sunk or the game is over; only the shots and what they hit.
 */
export const N = 10, FLEET = [5, 4, 3, 3, 2];

interface Ship { cells: number[] }
interface Side { ships: Ship[] | null; shots: number[] }
export interface FleetState {
  players: [string, string];
  /** Keyed by the owner of the ships; `shots` are the cells the other player fired at on this grid. */
  sides: Record<string, Side>;
  seed: string;
  next: 0 | 1 | null;
  winner: 0 | 1 | null;
  last: { by: string; cell: number; hit: boolean; sunk: number[] | null } | null;
}
type Placement = { x: number; y: number; len: number; across: boolean };
type FleetMove = { t: 'place'; ships: Placement[] } | { t: 'auto' } | { t: 'fire'; cell: number };

const cellsOf = (p: Placement) => Array.from({ length: p.len }, (_, i) => (p.across ? p.y * N + p.x + i : (p.y + i) * N + p.x));
const fits = (p: Placement) => p.x >= 0 && p.y >= 0 && (p.across ? p.x + p.len <= N && p.y < N : p.y + p.len <= N && p.x < N);

export function validFleet(ps: Placement[]): Ship[] | null {
  if (!Array.isArray(ps) || ps.length !== FLEET.length) return null;
  const lens = ps.map(p => Number(p?.len)).sort((a, b) => b - a);
  if (lens.join() !== FLEET.join()) return null;
  const used = new Set<number>(), ships: Ship[] = [];
  for (const raw of ps) {
    const p = { x: Number(raw.x), y: Number(raw.y), len: Number(raw.len), across: !!raw.across };
    if (![p.x, p.y].every(Number.isInteger) || !fits(p)) return null;
    const cells = cellsOf(p);
    if (cells.some(c => used.has(c))) return null;
    cells.forEach(c => used.add(c));
    ships.push({ cells });
  }
  return ships;
}

export function autoFleet(seed: string): Ship[] {
  const rand = seeded(seed);
  for (;;) {
    const ps: Placement[] = [];
    const used = new Set<number>();
    let ok = true;
    for (const len of FLEET) {
      let placed = false;
      for (let tries = 0; tries < 200 && !placed; tries++) {
        const p = { x: Math.floor(rand() * N), y: Math.floor(rand() * N), len, across: rand() < 0.5 };
        if (!fits(p) || cellsOf(p).some(c => used.has(c))) continue;
        cellsOf(p).forEach(c => used.add(c)); ps.push(p); placed = true;
      }
      if (!placed) { ok = false; break; }
    }
    if (ok) return validFleet(ps)!;
  }
}

const meta: GameMeta = { id: 'fleet', name: 'Fleet Battle', icon: 'sailing', mode: 'duel_turns', minPlayers: 2, maxPlayers: 2, avgMinutes: 3, tile: 0 };
const sunk = (ship: Ship, shots: number[]) => ship.cells.every(c => shots.includes(c));
const allSunk = (side: Side) => !!side.ships && side.ships.every(s => sunk(s, side.shots));

export const fleetModule: DuelModule<FleetState, FleetMove> = {
  kind: 'duel', meta,
  createState: (players, seed) => ({ players, seed, sides: { [players[0]]: { ships: null, shots: [] }, [players[1]]: { ships: null, shots: [] } }, next: null, winner: null, last: null }),
  // While ships are being placed both may move (null); then turns alternate.
  turn: s => (s.winner !== null || s.next === null ? null : s.players[s.next]),
  applyMove: (s, playerId, move) => {
    if (s.winner !== null) throw new MoveError('game_over');
    const me = s.players.indexOf(playerId) as 0 | 1;
    if (move?.t === 'place' || move?.t === 'auto') {
      if (s.sides[playerId]!.ships) throw new MoveError('already_placed');
      const ships = move.t === 'auto' ? autoFleet(`${s.seed}:${playerId}`) : validFleet(move.ships);
      if (!ships) throw new MoveError('bad_fleet');
      const sides = { ...s.sides, [playerId]: { ...s.sides[playerId]!, ships } };
      const ready = s.players.every(p => sides[p]!.ships);
      return { ...s, sides, next: ready ? (seeded(`${s.seed}:first`)() < 0.5 ? 0 : 1) : null };
    }
    if (move?.t !== 'fire') throw new MoveError('bad_move');
    if (s.next === null) throw new MoveError('not_ready');
    if (s.next !== me) throw new MoveError('not_your_turn');
    const cell = Number(move.cell);
    const them = s.players[1 - me]!;
    const side = s.sides[them]!;
    if (!Number.isInteger(cell) || cell < 0 || cell >= N * N || side.shots.includes(cell)) throw new MoveError('bad_move');
    const shots = [...side.shots, cell];
    const ship = side.ships!.find(x => x.cells.includes(cell));
    const next = { ...side, shots };
    const won = allSunk(next);
    return {
      ...s, sides: { ...s.sides, [them]: next }, next: (1 - me) as 0 | 1, winner: won ? me : null,
      last: { by: playerId, cell, hit: !!ship, sunk: ship && sunk(ship, shots) ? ship.cells : null },
    };
  },
  isOver: s => ({
    done: s.winner !== null, winnerId: s.winner !== null ? s.players[s.winner] : undefined,
    // Ships sunk by each player.
    scores: Object.fromEntries(s.players.map((p, i) => { const them = s.sides[s.players[1 - i]!]!; return [p, them.ships ? them.ships.filter(x => sunk(x, them.shots)).length : 0]; })),
  }),
  viewFor: (s, viewer) => {
    const other = s.players.find(p => p !== viewer)!;
    const mine = s.sides[viewer]!, theirs = s.sides[other]!;
    const open = s.winner !== null;
    return {
      players: s.players, next: s.next, winner: s.winner, last: s.last, size: N, fleet: FLEET,
      placed: { [viewer]: !!mine.ships, [other]: !!theirs.ships },
      mine: { ships: mine.ships, shots: mine.shots },
      theirs: {
        shots: theirs.shots.map(c => ({ cell: c, hit: !!theirs.ships?.some(x => x.cells.includes(c)) })),
        // Only sunk ships are shown, until the game is over.
        ships: (theirs.ships ?? []).filter(x => open || sunk(x, theirs.shots)),
        left: (theirs.ships ?? []).filter(x => !sunk(x, theirs.shots)).length,
      },
    };
  },
};
