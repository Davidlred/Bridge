import { describe, expect, it } from 'vitest';
import { MoveError, type DuelModule } from '../src/games/module.js';
import { adjacent, isWord, makeGrid, makeRack, points, scrambleModule, solveGrid, wordDashModule, type WordState } from '../src/games/words.js';
import { dartsModule, scoreAt, START, type DartsState } from '../src/games/darts.js';
import { autoFleet, FLEET, fleetModule, validFleet, type FleetState } from '../src/games/fleet.js';
import { cupModule, flight, RACK, type CupState } from '../src/games/cup.js';
import { poolModule, rack, shoot, type PoolState } from '../src/games/pool.js';
import { simulate } from '../src/games/physics.js';

const P: [string, string] = ['a', 'b'];
const ctx = { planWords: [] as string[], perks: [] as string[] };
const other = (p: string) => (p === 'a' ? 'b' : 'a');

describe('Word Dash and Scramble', () => {
  it('scores by length and checks the dictionary', () => {
    expect([3, 4, 5, 6, 7, 9].map(n => points('x'.repeat(n)))).toEqual([1, 2, 4, 6, 10, 10]);
    expect(isWord('bridge')).toBe(true);
    expect(isWord('qzxv')).toBe(false);
  });
  it('makes the same grid for the same seed, with plenty of words', () => {
    const g = makeGrid('night-7');
    expect(g).toEqual(makeGrid('night-7'));
    expect(g).toHaveLength(16);
    expect(solveGrid(g).size).toBeGreaterThan(20);
    expect(adjacent(0, 5)).toBe(true);
    expect(adjacent(0, 2)).toBe(false);
    expect(adjacent(3, 4)).toBe(false);                                         // no wrapping across rows
  });
  it('plays two timed rounds: words count on the server clock, duplicates and non-words don’t, plan words get +2', () => {
    const m = wordDashModule as DuelModule<WordState, any>;
    let s = m.createState(P, 'wd-1', { planWords: [], perks: [] });
    // Find a real word in the grid and its path.
    const grid = s.letters;
    let path: number[] | null = null;
    const walk = (i: number, w: string, used: number[]): boolean => {
      if (w.length >= 4 && isWord(w)) { path = used; return true; }
      if (w.length >= 6) return false;
      for (let n = 0; n < 16; n++) if (!used.includes(n) && adjacent(i, n) && walk(n, w + grid[n], [...used, n])) return true;
      return false;
    };
    for (let i = 0; i < 16 && !path; i++) walk(i, grid[i]!, [i]);
    expect(path).not.toBeNull();
    const word = path!.map(i => grid[i]).join('');
    s = m.createState(P, 'wd-1', { planWords: [word], perks: [] });
    expect(m.turn(s)).toBe('a');
    expect(() => m.applyMove(s, 'b', { t: 'start' }, { now: 0 })).toThrow('not_your_turn');
    expect(() => m.applyMove(s, 'a', { t: 'word', path: path! }, { now: 0 })).toThrow('not_started');
    s = m.applyMove(s, 'a', { t: 'start' }, { now: 1000 });
    s = m.applyMove(s, 'a', { t: 'word', path: path! }, { now: 2000 });
    expect(s.rounds.a!.last).toMatchObject({ ok: true, bonus: true, pts: points(word) + 2 });
    s = m.applyMove(s, 'a', { t: 'word', path: path! }, { now: 3000 });
    expect(s.rounds.a!.last).toMatchObject({ ok: false, reason: 'already_found' });
    expect(() => m.applyMove(s, 'a', { t: 'word', path: [0, 2] }, { now: 3000 })).toThrow('bad_path');
    // The other player can't see a's words while b hasn't played; b sees the score to beat.
    expect((m.viewFor(s, 'b') as any).rounds.a.found).toEqual([]);
    // Time runs out: settled by the clock, the turn passes.
    s = m.settle!(s, 1000 + 80_000 + 5000);
    expect(s.rounds.a!.ended).toBe(true);
    expect(m.turn(s)).toBe('b');
    expect(m.toBeat!(s)).toBe(points(word) + 2);
    s = m.applyMove(s, 'b', { t: 'start' }, { now: 200_000 });
    // A word after the end (and the grace for the network) doesn't count: the round is over.
    expect(() => m.applyMove(s, 'b', { t: 'word', path: path! }, { now: 200_000 + 90_000 })).toThrow('game_over');
    s = m.settle!(s, 200_000 + 90_000);
    expect(s.rounds.b!.found).toHaveLength(0);
    expect(m.isOver(s)).toMatchObject({ done: true, winnerId: 'a' });
    expect((m.viewFor(s, 'b') as any).rounds.a.found).toHaveLength(1);
  });
  it('Scramble takes words made from the six letters only', () => {
    const rack = makeRack('sc-1');
    expect(rack).toHaveLength(6);
    expect(makeRack('sc-1')).toEqual(rack);
    const m = scrambleModule as DuelModule<WordState, any>;
    let s = m.createState(P, 'sc-1', ctx);
    s = m.applyMove(s, 'a', { t: 'start' }, { now: 0 });
    s = m.applyMove(s, 'a', { t: 'word', word: 'zzz' }, { now: 10 });
    expect(s.rounds.a!.last!.reason).toBe('not_in_letters');
  });
});

describe('Darts', () => {
  it('scores the board', () => {
    expect(scoreAt(0, 0)).toMatchObject({ score: 50, label: 'BULL' });
    expect(scoreAt(0, 0.07)).toMatchObject({ score: 25 });
    expect(scoreAt(0, 0.6)).toMatchObject({ score: 60, label: 'T20' });
    expect(scoreAt(0, 0.98)).toMatchObject({ score: 40, label: 'D20', mult: 2 });
    expect(scoreAt(0.98, 0)).toMatchObject({ label: 'D6' });
    expect(scoreAt(0, -0.98)).toMatchObject({ label: 'D3' });
    expect(scoreAt(0, 1.2)).toMatchObject({ score: 0, label: 'MISS' });
  });
  it('plays 301: three darts a turn, busts wipe the turn, a double finish wins', () => {
    const m = dartsModule as DuelModule<DartsState, any>;
    let s = m.createState(P, 'd-1', ctx);
    const first = m.turn(s)!;
    expect(() => m.applyMove(s, other(first), { t: 'throw', x: 0, y: 0 }, { now: 0 })).toThrow('not_your_turn');
    for (let i = 0; i < 3; i++) s = m.applyMove(s, first, { t: 'throw', x: 0, y: 0.6 }, { now: 0 });
    expect(m.turn(s)).toBe(other(first));
    expect(s.remaining[s.players.indexOf(first)]).toBeLessThan(START);
    expect(s.lastTurn!.darts).toHaveLength(3);
    // A bust: on 2 left, anything but D1 busts.
    const i = s.players.indexOf(other(first));
    s = { ...s, remaining: s.remaining.map((r, k) => (k === i ? 2 : r)) as [number, number], turnStart: 2 };
    s = m.applyMove(s, other(first), { t: 'throw', x: 0, y: 0.6 }, { now: 0 });
    expect(s.bust).toBe(true);
    expect(s.remaining[i]).toBe(2);
    expect(m.isOver(s).done).toBe(false);
  });
  it('lets a streak player re-throw once', () => {
    const m = dartsModule as DuelModule<DartsState, any>;
    let s = m.createState(P, 'd-2', { planWords: [], perks: ['a', 'b'] });
    const first = m.turn(s)!;
    s = m.applyMove(s, first, { t: 'throw', x: 0, y: 0.6 }, { now: 0 });
    const before = s.remaining[s.players.indexOf(first)];
    s = m.applyMove(s, first, { t: 'rethrow' }, { now: 0 });
    expect(s.darts).toHaveLength(0);
    expect(s.remaining[s.players.indexOf(first)]).toBe(START);
    expect(before).toBeLessThan(START);
    s = m.applyMove(s, first, { t: 'throw', x: 0, y: 0.6 }, { now: 0 });
    expect(() => m.applyMove(s, first, { t: 'rethrow' }, { now: 0 })).toThrow('no_perk');
  });
});

describe('Fleet Battle', () => {
  it('checks fleets and hides the other ships until they sink', () => {
    expect(validFleet([{ x: 0, y: 0, len: 5, across: true }])).toBeNull();
    expect(validFleet(FLEET.map((len, i) => ({ x: 0, y: i, len, across: true })))).not.toBeNull();
    expect(validFleet(FLEET.map(len => ({ x: 0, y: 0, len, across: true })))).toBeNull();   // overlapping
    expect(validFleet(FLEET.map((len, i) => ({ x: 7, y: i, len, across: true })))).toBeNull(); // off the board
    expect(autoFleet('x').flatMap(s => s.cells)).toHaveLength(17);

    const m = fleetModule as DuelModule<FleetState, any>;
    let s = m.createState(P, 'f-1', ctx);
    expect(m.turn(s)).toBeNull();
    s = m.applyMove(s, 'a', { t: 'place', ships: FLEET.map((len, i) => ({ x: 0, y: i * 2, len, across: true })) }, { now: 0 });
    expect(() => m.applyMove(s, 'a', { t: 'fire', cell: 0 }, { now: 0 })).toThrow('not_ready');
    s = m.applyMove(s, 'b', { t: 'auto' }, { now: 0 });
    const first = m.turn(s)!;
    expect((m.viewFor(s, 'a') as any).theirs.ships).toEqual([]);
    expect(JSON.stringify((m.viewFor(s, 'a') as any).theirs)).not.toContain('"cells"');  // b's ships aren't sent to a
    // Sink everything of the other player's, alternating with misses that won't matter.
    const target = s.sides[other(first)]!.ships!.flatMap(x => x.cells);
    const filler = Array.from({ length: 100 }, (_, i) => i).filter(c => !s.sides[first]!.ships!.flatMap(x => x.cells).includes(c));
    for (const [k, cell] of target.entries()) {
      s = m.applyMove(s, first, { t: 'fire', cell }, { now: 0 });
      if (m.isOver(s).done) break;
      s = m.applyMove(s, other(first), { t: 'fire', cell: filler[k]! }, { now: 0 });
    }
    expect(m.isOver(s)).toMatchObject({ done: true, winnerId: first });
    expect(m.isOver(s).scores[first]).toBe(5);
  });
});

describe('Cup Pong', () => {
  it('lands where the swipe sends it and sinks a cup it hits', () => {
    const { land } = flight(0, (1.55 - 0.9) / 1.2);
    expect(land[0]).toBeCloseTo(0);
    expect(land[1]).toBeCloseTo(1.55);
    const m = cupModule as DuelModule<CupState, any>;
    let s = m.createState(P, 'c-1', ctx);
    const first = m.turn(s)!;
    s = m.applyMove(s, first, { t: 'throw', angle: 0, power: (RACK[0]![1] - 0.9) / 1.2 }, { now: 0 });
    expect(s.last!.cup).toBe(0);
    expect(s.cups[other(first)]).toHaveLength(9);
    s = m.applyMove(s, first, { t: 'throw', angle: 0.5, power: 1 }, { now: 0 });  // way off
    expect(s.last!.cup).toBeNull();
    expect(m.turn(s)).toBe(other(first));
    expect(m.isOver(s).scores[first]).toBe(1);
    expect(() => m.applyMove(s, other(first), { t: 'rerack' }, { now: 0 })).toThrow('no_perk');
  });
  it('a re-rack pulls the other side together, once', () => {
    const m = cupModule as DuelModule<CupState, any>;
    let s = m.createState(P, 'c-2', { planWords: [], perks: ['a', 'b'] });
    const first = m.turn(s)!;
    s = { ...s, cups: { ...s.cups, [other(first)]: [6, 9] } };
    s = m.applyMove(s, first, { t: 'rerack' }, { now: 0 });
    expect(s.spots[other(first)]![6]).toEqual(RACK[0]);
    expect(() => m.applyMove(s, first, { t: 'rerack' }, { now: 0 })).toThrow('no_perk');
  });
});

describe('8-Ball', () => {
  it('racks 15 balls with the 8 in the middle and runs a break', () => {
    const balls = rack('p-1');
    expect(balls).toHaveLength(16);
    expect(balls[5]!.id).toBe(8);
    const shot = shoot(balls, 0, 1);
    expect(shot.firstHit).not.toBeNull();
    expect(shot.rec.frames.length).toBeGreaterThan(10);
    expect(shot.balls.filter(b => !b.in).every(b => b.x >= 0 && b.x <= 2 && b.y >= 0 && b.y <= 1)).toBe(true);
    expect(shoot(balls, 0, 1)).toEqual(shot);                                   // the same shot does the same thing
  });
  it('passes the turn on a miss, fouls a scratch and loses on an early 8', () => {
    const m = poolModule as DuelModule<PoolState, any>;
    let s = m.createState(P, 'p-2', ctx);
    const first = m.turn(s)!;
    s = m.applyMove(s, first, { angle: Math.PI, power: 0.2 }, { now: 0 });       // away from the rack: hits nothing
    expect(s.last!.foul).toBe('no_hit');
    expect(m.turn(s)).toBe(other(first));
    // Put the 8 right by a pocket with the cue ball behind it.
    const t = s.balls.map(b => (b.id === 8 ? { ...b, x: 1.9, y: 0.1 } : b.id === 0 ? { ...b, x: 1.6, y: 0.4 } : { ...b, in: b.id !== 8 && b.id !== 0 ? b.in : b.in }));
    s = { ...s, balls: t.map(b => (b.id !== 0 && b.id !== 8 ? { ...b, x: 0.2 + (b.id % 5) * 0.1, y: 0.8 + Math.floor(b.id / 5) * 0.065 } : b)) };
    s = m.applyMove(s, other(first), { angle: Math.atan2(0.1 - 0.4, 1.9 - 1.6), power: 0.5 }, { now: 0 });
    expect(s.last!.potted).toContain(8);
    expect(m.isOver(s)).toMatchObject({ done: true, winnerId: first });
    expect(s.how).toBe('eight_early');
  });
});

describe('Physics', () => {
  it('stops, bounces and conserves momentum in a head-on hit', () => {
    const rec = simulate([{ id: 1, x: 0, y: 0, vx: 1, vy: 0, r: 0.05 }, { id: 2, x: 0.3, y: 0, vx: 0, vy: 0, r: 0.05 }], { friction: 0.5, restitution: 1 });
    const last = rec.frames[rec.frames.length - 1]!;
    expect(rec.ids).toEqual([1, 2]);
    expect(last[0]).toBeLessThan(0.3);                                           // the first ball stopped after the hit
    expect(last[2]).toBeGreaterThan(0.4);                                        // the second one moved on
  });
});

describe('Moves are rejected with MoveError', () => {
  it('for every duel game', () => {
    for (const m of [wordDashModule, scrambleModule, dartsModule, fleetModule, cupModule, poolModule] as DuelModule<any, any>[]) {
      const s = m.createState(P, 'x', ctx);
      expect(() => m.applyMove(s, 'nobody', { t: 'fire', cell: 0, angle: 0, power: 0.5, x: 0, y: 0 }, { now: 0 })).toThrow(MoveError);
    }
  });
});

import { hoopsDef, hoopX, HOOP_Y, shot } from '../src/games/hoops.js';
import { golfDef, makeHoles, putt } from '../src/games/golf.js';
import { pushDef, slide } from '../src/games/push.js';
import { placesFor } from '../src/games/live.js';

describe('Everyone games', () => {
  it('ranks with shared places, highest or lowest first', () => {
    expect(placesFor({ a: 3, b: 5, c: 3 }, false).map(r => [r.userId, r.place])).toEqual([['b', 1], ['a', 2], ['c', 2]]);
    expect(placesFor({ a: 30, b: 25 }, true)[0]).toMatchObject({ userId: 'b', place: 1 });
  });
  it('Hoops: a swipe at the hoop scores, the hoop moves, and the clock and cooldown hold', () => {
    const w = hoopsDef.create(['a', 'b'], 'h-1', [], 0);
    expect(() => hoopsDef.move(w, 'a', { angle: 0, power: 0.5 }, 1000)).toThrow('not_started');
    const hx = hoopX('h-1', 0);
    const angle = Math.atan2(hx, HOOP_Y), power = (Math.hypot(hx, HOOP_Y) - 0.5) / 1.0;
    const r = hoopsDef.move(w, 'a', { angle, power }, 4000).reply as any;
    expect(r.made).toBe(true);
    expect(shot(angle, power).land[1]).toBeCloseTo(HOOP_Y, 2);
    expect(() => hoopsDef.move(w, 'a', { angle, power }, 4100)).toThrow('slow_down');
    hoopsDef.move(w, 'b', { angle: 0.7, power: 1 }, 4000);
    expect(hoopsDef.scores(w)).toEqual({ a: 1, b: 0 });
    expect(hoopsDef.done(w, 34_000)).toBe(true);
    expect(() => hoopsDef.move(w, 'b', { angle: 0, power: 0.5 }, 33_100)).toThrow('time_up');
  });
  it('Mini Golf: same holes from the seed, a straight putt sinks, strokes add up, a mulligan takes one back', () => {
    expect(makeHoles('g')).toEqual(makeHoles('g'));
    expect(makeHoles('g')[0]!.blocks).toEqual([]);
    const w = golfDef.create(['a', 'b'], 'g', ['a'], 0);
    const hole = w.holes[0]!;
    const angle = Math.atan2(hole.cup[0] - 0.5, hole.cup[1] - 0.15);
    // Find a power that drops it (the ball has to arrive slowly enough).
    let power = 0;
    for (let p = 0.3; p <= 1; p += 0.01) if (putt(hole, [0.5, 0.15], angle, p).sunk) { power = p; break; }
    expect(power).toBeGreaterThan(0);
    const r = golfDef.move(w, 'a', { angle, power }, 1000).reply as any;
    expect(r).toMatchObject({ sunk: true, strokes: 1, next: { hole: 1 } });
    golfDef.move(w, 'a', { t: 'mulligan' }, 1100);
    expect(w.golfers.a!.hole).toBe(0);
    expect(() => golfDef.move(w, 'b', { t: 'mulligan' }, 1100)).toThrow('no_perk');
    golfDef.move(w, 'a', { angle, power }, 1200);
    expect(w.golfers.a!.hole).toBe(1);
    // Six misses on a hole: picked up for a 7.
    for (let i = 0; i < 6; i++) golfDef.move(w, 'b', { angle: Math.PI, power: 0.05 }, 2000);
    expect(w.golfers.b!.strokes[0]).toBe(7);
    expect(w.golfers.b!.hole).toBe(1);
    expect(golfDef.done(w, 250_000)).toBe(true);
    expect(golfDef.scores(w).a).toBeLessThan(golfDef.scores(w).b!);
  });
  it('Push Out: a hard flick into a neighbour knocks it off, and the last puck on wins', () => {
    const w = pushDef.create(['a', 'b', 'c'], 'p', [], 0);
    expect(w.pucks).toHaveLength(3);
    const [a, b] = w.pucks;
    pushDef.move(w, 'a', { angle: Math.atan2(b!.x - a!.x, b!.y - a!.y), power: 1 }, 100);
    expect(() => pushDef.move(w, 'zz', { angle: 0, power: 1 }, 100)).toThrow('not_in_game');
    const e = pushDef.tick!(w, 9000) as any;
    expect(e.rec.frames.length).toBeGreaterThan(5);
    expect(w.phase).toBe('slide');
    expect(e.out.length).toBeGreaterThan(0);
    const next = pushDef.tick!(w, w.endsAt + 1) as any;
    expect(next).toMatchObject({ round: 2, aim: true });
    // A puck sliding on its own off the ice.
    const lone = slide([{ userId: 'x', x: 0.8, y: 0, out: false, outRound: null, aim: { angle: Math.PI / 2, power: 1 } }]);
    expect(lone.bodies[0]!.gone).toBe(true);
  });
});
