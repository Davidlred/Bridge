import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { MoveError, seeded, type DuelModule, type GameMeta } from './module.js';

/**
 * Word Dash (4×4 grid, 80 s, drag across touching letters) and Scramble (6 letters, 60 s). Both players get the same
 * letters from the seed and play one timed round each, one after the other; the higher score wins. The server keeps the
 * clock: a round runs from the "start" move, words after the end (plus a little grace for the network) don't count.
 *
 * Words come from SCOWL (data/WORDS-LICENSE.txt): words.txt to check answers, common.txt (more everyday words) to make
 * boards that have plenty to find. A word from this alliance's goal or task names scores +2 ("bonus word from your plan").
 */
const data = (f: string) => readFileSync(fileURLToPath(new URL(`../../data/${f}`, import.meta.url)), 'utf8').split('\n').filter(Boolean);
let dict: Set<string> | undefined, common: string[] | undefined, commonSet: Set<string> | undefined, prefixes: Set<string> | undefined;
const words = () => (dict ??= new Set(data('words.txt')));
const commonWords = () => (common ??= data('common.txt'));
const commonHas = (w: string) => (commonSet ??= new Set(commonWords())).has(w);
const prefixSet = () => {
  if (prefixes) return prefixes;
  prefixes = new Set();
  for (const w of words()) for (let i = 1; i < w.length; i++) prefixes.add(w.slice(0, i));
  return prefixes;
};
export const isWord = (w: string) => words().has(w);

/** 3 letters = 1, 4 = 2, 5 = 4, 6 = 6, 7 or more = 10. */
export const points = (w: string) => (w.length <= 2 ? 0 : w.length === 3 ? 1 : w.length === 4 ? 2 : w.length === 5 ? 4 : w.length === 6 ? 6 : 10);
export const PLAN_BONUS = 2;

// --- Word Dash grids ---

const SIZE = 4;
/** Letters by how often they help make words (no Q: it needs a U to be any use). */
const BAG = 'eeeeeeeeeeeeaaaaaaaaaiiiiiiiioooooooonnnnnnrrrrrrttttttllllssssssuuuuddddgggbbccmmppffhhvvwwyykjxz';

const neighbours = (i: number) => {
  const r = Math.floor(i / SIZE), c = i % SIZE, out: number[] = [];
  for (let dr = -1; dr <= 1; dr++) for (let dc = -1; dc <= 1; dc++) {
    if (!dr && !dc) continue;
    const rr = r + dr, cc = c + dc;
    if (rr >= 0 && rr < SIZE && cc >= 0 && cc < SIZE) out.push(rr * SIZE + cc);
  }
  return out;
};
export const adjacent = (a: number, b: number) => neighbours(a).includes(b);

/** Every dictionary word in a grid (3+ letters, each tile once per word). */
export function solveGrid(grid: string[]): Set<string> {
  const found = new Set<string>(), pre = prefixSet(), dict = words();
  const walk = (i: number, w: string, used: Set<number>) => {
    if (w.length >= 3 && dict.has(w)) found.add(w);
    if (!pre.has(w)) return;
    for (const n of neighbours(i)) if (!used.has(n)) { used.add(n); walk(n, w + grid[n], used); used.delete(n); }
  };
  for (let i = 0; i < grid.length; i++) walk(i, grid[i]!, new Set([i]));
  return found;
}

/** A grid with at least [min] everyday words in it, from the seed (both players get the same one). */
export function makeGrid(seed: string, min = 35): string[] {
  const rand = seeded(`${seed}:grid`);
  let best: { grid: string[]; n: number } | null = null;
  for (let attempt = 0; attempt < 40; attempt++) {
    const grid = Array.from({ length: SIZE * SIZE }, () => BAG[Math.floor(rand() * BAG.length)]!);
    const vowels = grid.filter(l => 'aeiou'.includes(l)).length;
    if (vowels < 4 || vowels > 8) continue;
    const n = [...solveGrid(grid)].filter(commonHas).length;
    if (!best || n > best.n) best = { grid, n };
    if (n >= min) break;
  }
  return best!.grid;
}

// --- Scramble racks ---

const countLetters = (w: string) => { const c: Record<string, number> = {}; for (const l of w) c[l] = (c[l] ?? 0) + 1; return c; };
export const fromRack = (w: string, rack: string[]) => {
  const have = countLetters(rack.join(''));
  for (const [l, n] of Object.entries(countLetters(w))) if ((have[l] ?? 0) < n) return false;
  return true;
};

/** Six letters from an everyday six-letter word with at least [min] words inside it, shuffled. */
export function makeRack(seed: string, min = 12): string[] {
  const rand = seeded(`${seed}:rack`);
  const six = commonWords().filter(w => w.length === 6);
  let best: { word: string; n: number } | null = null;
  for (let attempt = 0; attempt < 30; attempt++) {
    const word = six[Math.floor(rand() * six.length)]!;
    let n = 0;
    for (const w of commonWords()) if (w.length >= 3 && w.length <= 6 && fromRack(w, [...word])) n++;
    if (!best || n > best.n) best = { word, n };
    if (n >= min) break;
  }
  const rack = [...best!.word];
  for (let i = rack.length - 1; i > 0; i--) { const j = Math.floor(rand() * (i + 1)); [rack[i], rack[j]] = [rack[j]!, rack[i]!]; }
  return rack;
}

// --- The shared round-based state machine ---

interface Found { w: string; pts: number; bonus: boolean }
interface Round { startedAt: number | null; endsAt: number | null; ended: boolean; found: Found[]; score: number; last: { w: string; ok: boolean; reason: string; pts: number; bonus: boolean } | null }
export interface WordState {
  game: 'word' | 'scramble';
  players: [string, string];
  letters: string[];
  seconds: number;
  /** players[0] plays first (the challenger), then players[1] tries to beat the score. */
  rounds: Record<string, Round>;
  plan: string[];
}
type WordMove = { t: 'start' } | { t: 'end' } | { t: 'word'; path?: number[]; word?: string };

const GRACE_MS = 3000;
const newRound = (): Round => ({ startedAt: null, endsAt: null, ended: false, found: [], score: 0, last: null });

function wordModule(meta: GameMeta, seconds: number, letters: (seed: string) => string[]): DuelModule<WordState, WordMove> {
  const turn = (s: WordState) => s.players.find(p => !s.rounds[p]!.ended) ?? null;
  const settle = (s: WordState, now: number): WordState => {
    let changed = false;
    const rounds = { ...s.rounds };
    for (const p of s.players) {
      const r = rounds[p]!;
      if (!r.ended && r.endsAt !== null && now > r.endsAt + GRACE_MS) { rounds[p] = { ...r, ended: true }; changed = true; }
    }
    return changed ? { ...s, rounds } : s;
  };
  return {
    kind: 'duel',
    meta,
    createState: (players, seed, ctx) => {
      const ls = letters(seed);
      return { game: meta.id as 'word' | 'scramble', players, letters: ls, seconds, rounds: { [players[0]]: newRound(), [players[1]]: newRound() }, plan: ctx.planWords.filter(isWord) };
    },
    turn,
    settle,
    applyMove: (s0, playerId, move, { now }) => {
      const s = settle(s0, now);
      if (turn(s) === null) throw new MoveError('game_over');
      if (turn(s) !== playerId) throw new MoveError('not_your_turn');
      const r = s.rounds[playerId]!;
      const put = (next: Round) => ({ ...s, rounds: { ...s.rounds, [playerId]: next } });
      if (move?.t === 'start') {
        if (r.startedAt !== null) return s;                                   // already going (a retried request)
        return put({ ...r, startedAt: now, endsAt: now + seconds * 1000 });
      }
      if (r.startedAt === null) throw new MoveError('not_started');
      if (move?.t === 'end') return put({ ...r, ended: true });
      if (move?.t !== 'word') throw new MoveError('bad_move');
      if (now > r.endsAt! + GRACE_MS) return put({ ...r, ended: true });
      let w: string;
      if (meta.id === 'word') {
        const path = move.path;
        if (!Array.isArray(path) || path.length < 1 || path.length > 16 || new Set(path).size !== path.length
          || path.some(i => !Number.isInteger(i) || i < 0 || i >= s.letters.length)
          || path.some((i, k) => k > 0 && !adjacent(path[k - 1]!, i))) throw new MoveError('bad_path');
        w = path.map(i => s.letters[i]).join('');
      } else {
        w = String(move.word ?? (Array.isArray(move.path) && new Set(move.path).size === move.path.length ? move.path.map(i => s.letters[i] ?? '').join('') : '')).toLowerCase();
        if (!/^[a-z]{1,6}$/.test(w)) throw new MoveError('bad_move');
      }
      const verdict = (reason: string) => put({ ...r, last: { w, ok: false, reason, pts: 0, bonus: false } });
      if (w.length < 3) return verdict('too_short');
      if (meta.id === 'scramble' && !fromRack(w, s.letters)) return verdict('not_in_letters');
      if (r.found.some(f => f.w === w)) return verdict('already_found');
      if (!isWord(w)) return verdict('not_a_word');
      const bonus = s.plan.includes(w);
      const pts = points(w) + (bonus ? PLAN_BONUS : 0);
      return put({ ...r, found: [...r.found, { w, pts, bonus }], score: r.score + pts, last: { w, ok: true, reason: 'ok', pts, bonus } });
    },
    isOver: s => {
      const [a, b] = s.players;
      const sa = s.rounds[a]!.score, sb = s.rounds[b]!.score;
      const done = s.rounds[a]!.ended && s.rounds[b]!.ended;
      return { done, winnerId: done && sa !== sb ? (sa > sb ? a : b) : undefined, scores: { [a]: sa, [b]: sb } };
    },
    toBeat: s => (s.rounds[s.players[0]]!.ended && !s.rounds[s.players[1]]!.ended ? s.rounds[s.players[0]]!.score : null),
    viewFor: (s, viewer) => {
      const both = s.players.every(p => s.rounds[p]!.ended);
      return {
        game: s.game, letters: s.letters, seconds: s.seconds, players: s.players,
        rounds: Object.fromEntries(s.players.map(p => {
          const r = s.rounds[p]!;
          // The other player's words stay hidden until both have played (no copying their list).
          const open = p === viewer || both;
          return [p, { started: r.startedAt !== null, endsAt: r.endsAt, ended: r.ended, score: r.score, count: r.found.length, found: open ? r.found : [], last: p === viewer ? r.last : null }];
        })),
      };
    },
  };
}

const wordMeta: GameMeta = { id: 'word', name: 'Word Dash', icon: 'abc', mode: 'duel_turns', minPlayers: 2, maxPlayers: 2, avgMinutes: 2, tile: 2, scored: true };
const scrambleMeta: GameMeta = { id: 'scramble', name: 'Scramble', icon: 'shuffle', mode: 'duel_turns', minPlayers: 2, maxPlayers: 2, avgMinutes: 2, tile: 3, scored: true };
export const wordDashModule = wordModule(wordMeta, 80, seed => makeGrid(seed));
export const scrambleModule = wordModule(scrambleMeta, 60, seed => makeRack(seed));
