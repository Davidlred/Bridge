import { describe, expect, it } from 'vitest';
import { seeded } from '../src/games/module.js';
import { LINEUP, catalog, moduleFor } from '../src/games/registry.js';

describe('Arcade registry', () => {
  it('lists all 12 games in order with tile colours cycling through the five fills', () => {
    expect(LINEUP.map(g => g.id)).toEqual(['drift', 'whose', 'word', 'scramble', 'cup', 'pool', 'hoops', 'darts', 'golf', 'four', 'fleet', 'push']);
    expect(LINEUP.map(g => g.tile)).toEqual([0, 1, 2, 3, 4, 0, 1, 2, 3, 4, 0, 1]);
    expect(new Set(LINEUP.map(g => g.id)).size).toBe(12);
    for (const g of LINEUP) expect(g.minPlayers).toBeLessThanOrEqual(g.maxPlayers);
  });
  it("wraps Who's Drifting? and Whose Day? as the first live modules; the rest are listed but not playable yet", () => {
    expect(moduleFor('drift')).toMatchObject({ kind: 'live', meta: { name: "Who's Drifting?", mode: 'everyone' } });
    expect(moduleFor('whose')).toMatchObject({ kind: 'live', meta: { name: 'Whose Day?' } });
    expect(moduleFor('four')).toBeUndefined();
    const c = catalog();
    expect(c.filter(g => g.available).map(g => g.id)).toEqual(['drift', 'whose']);
    expect(c.find(g => g.id === 'cup')).toMatchObject({ built: false, available: false, liveToo: true });
  });
  it('seeds randomness so both players get the same board', () => {
    const a = seeded('night-1'), b = seeded('night-1'), c = seeded('night-2');
    const xs = Array.from({ length: 5 }, a), ys = Array.from({ length: 5 }, b), zs = Array.from({ length: 5 }, c);
    expect(xs).toEqual(ys);
    expect(xs).not.toEqual(zs);
    for (const x of xs) expect(x >= 0 && x < 1).toBe(true);
  });
});
