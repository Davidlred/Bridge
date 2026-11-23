import { seeded } from './module.js';

/** Bracket pairing rules (pure, so they can be tested on their own). */
export type Pair = [string, string];
export const key = (a: string, b: string) => (a < b ? `${a}|${b}` : `${b}|${a}`);

export const roundsFor = (n: number) => (n <= 1 ? 0 : Math.ceil(Math.log2(n)));

/** "final", "semis", "quarters" or "round" for this round of a bracket with [rounds] rounds. */
export const roundLabel = (round: number, rounds: number) => (['final', 'semis', 'quarters'][rounds - round] ?? 'round');

/**
 * The first round: shuffled from the seed, trying a few orders to keep apart members who blocked each other and the
 * pairs that played last time. Odd numbers leave the last member out with a bye.
 */
export function firstRound(players: string[], seed: string, avoid: Set<string>, blocked: Set<string>): { pairs: Pair[]; bye: string | null } {
  const rand = seeded(seed);
  let best: { pairs: Pair[]; bye: string | null; cost: number } | null = null;
  for (let attempt = 0; attempt < 60 && (!best || best.cost > 0); attempt++) {
    const list = players.slice();
    for (let i = list.length - 1; i > 0; i--) { const j = Math.floor(rand() * (i + 1)); [list[i], list[j]] = [list[j]!, list[i]!]; }
    const bye = list.length % 2 ? list.pop()! : null;
    const pairs: Pair[] = [];
    for (let i = 0; i < list.length; i += 2) pairs.push([list[i]!, list[i + 1]!]);
    const cost = pairs.reduce((c, [a, b]) => c + (blocked.has(key(a, b)) ? 100 : 0) + (avoid.has(key(a, b)) ? 1 : 0), 0);
    if (!best || cost < best.cost) best = { pairs, bye, cost };
  }
  return { pairs: best!.pairs, bye: best!.bye };
}

/**
 * A later round. Last round's bye meets the winner of the match they were told about; the other winners pair up in
 * bracket order. An odd count gives the bye to someone who hasn't had one, from the bottom of the bracket.
 */
export function nextRound(winners: string[], lastBye: { userId: string; vsWinner: string } | null, hadBye: Set<string>): { pairs: Pair[]; bye: string | null } {
  let list = winners.slice();
  if (lastBye) {
    list = list.filter(u => u !== lastBye.vsWinner);
    list = [lastBye.userId, ...(winners.includes(lastBye.vsWinner) ? [lastBye.vsWinner] : []), ...list.filter(u => u !== lastBye.userId)];
  }
  let bye: string | null = null;
  if (list.length % 2) {
    let i = list.length - 1;
    while (i >= 0 && hadBye.has(list[i]!)) i--;
    bye = list.splice(i >= 0 ? i : list.length - 1, 1)[0]!;
  }
  const pairs: Pair[] = [];
  for (let i = 0; i < list.length; i += 2) pairs.push([list[i]!, list[i + 1]!]);
  return { pairs, bye };
}

/** The next midnight in [tz], at least an hour away (a bracket made at 23:30 runs to the following midnight). */
export function midnightAfter(now: number, tz: string): Date {
  const parts = (t: number) => Object.fromEntries(new Intl.DateTimeFormat('en-US', { timeZone: tz, hourCycle: 'h23', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' })
    .formatToParts(new Date(t)).filter(p => p.type !== 'literal').map(p => [p.type, Number(p.value)]));
  const offset = (t: number) => { const p = parts(t); return Date.UTC(p.year!, p.month! - 1, p.day!, p.hour!, p.minute!, p.second!) - Math.floor(t / 1000) * 1000; };
  const p = parts(now);
  let target = Date.UTC(p.year!, p.month! - 1, p.day! + 1);
  for (let i = 0; i < 2; i++) target = Date.UTC(p.year!, p.month! - 1, p.day! + 1) - offset(target);
  if (target - now < 3600_000) {
    const q = parts(target + 3 * 3600_000);
    let t2 = Date.UTC(q.year!, q.month! - 1, q.day! + 1);
    for (let i = 0; i < 2; i++) t2 = Date.UTC(q.year!, q.month! - 1, q.day! + 1) - offset(t2);
    target = t2;
  }
  return new Date(target);
}

