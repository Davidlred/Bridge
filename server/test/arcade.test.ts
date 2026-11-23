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
  it("wraps Who's Drifting? and Whose Day? as live modules and the 1v1 games as duel modules, each matching its line-up entry", () => {
    expect(moduleFor('drift')).toMatchObject({ kind: 'live', meta: { name: "Who's Drifting?", mode: 'everyone' } });
    expect(moduleFor('whose')).toMatchObject({ kind: 'live', meta: { name: 'Whose Day?' } });
    expect(moduleFor('four')).toMatchObject({ kind: 'duel', meta: { name: 'Four in a Row' } });
    for (const g of catalog()) {
      expect(g.available).toBe(g.built);
      const m = moduleFor(g.id);
      if (!m) continue;
      expect(m.kind).toBe(g.mode === 'everyone' ? 'live' : 'duel');
      expect(m.meta).toMatchObject({ id: g.id, name: g.name, mode: g.mode, minPlayers: g.minPlayers });
    }
    expect(catalog().find(g => g.id === 'cup')).toMatchObject({ liveToo: true });
  });
  it('seeds randomness so both players get the same board', () => {
    const a = seeded('night-1'), b = seeded('night-1'), c = seeded('night-2');
    const xs = Array.from({ length: 5 }, a), ys = Array.from({ length: 5 }, b), zs = Array.from({ length: 5 }, c);
    expect(xs).toEqual(ys);
    expect(xs).not.toEqual(zs);
    for (const x of xs) expect(x >= 0 && x < 1).toBe(true);
  });
});
