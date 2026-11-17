import { randomInt } from 'node:crypto';
import { z } from 'zod';
import { sql } from '../db.js';
import { uuidv7 } from '../util.js';
import { bus } from '../realtime.js';
import { push } from '../push.js';
import { blockedWith, localToday } from './progress.js';
import { unlockStatus } from './game.js';
import type { SocketCtx } from './game.js';

/**
 * Game Night #2, "Whose Day?". Arc shows one player's finished list for today with the name hidden; everyone else
 * guesses whose it is. Clues unlock every 5 seconds, so earlier right guesses score more; the owner scores for
 * everyone they fool.
 *
 * Server-authoritative like Who's Drifting?: the server picks the lists, strips private tasks before anything is
 * sent, releases each clue at its stage, stamps every guess with server time and keeps the owner secret until the
 * reveal. Only the owner is told it's their list (on their own user channel).
 */
export const wdTiming = { lobbyMs: 180_000, stageMs: 5_000, closeMs: 15_000, roundMs: 20_000, revealMs: 6_000, graceMs: 10_000, resultMs: 120_000 };
const MIN_PLAYERS = 3;
const MIN_RANKED = 4;
const MAX_ROUNDS = 5, SMALL_ROUNDS = 3;
const POINTS = [3, 2, 1] as const;

type Phase = 'lobby' | 'round' | 'reveal' | 'results' | 'cancelled';
type Priority = 'high' | 'med' | 'low';

/** A task as other players may see it. Private tasks keep only their length. */
export interface PublicTask { name: string | null; private: boolean; durationMin: number | null; priority: Priority; doneAt: string | null }
interface ListTask extends PublicTask { goalId: string; streak: number }
interface DayList {
  ownerId: string; tasks: ListTask[];
  goalHint: string; goalTitle: string | null; deadline: string | null; goalPrivate: boolean; allianceGoalId: string;
}
interface Guess { guessId: string; stage: 1 | 2 | 3; atMs: number; points: number; correct: boolean }
interface Round { list: DayList; startsAt: number; guesses: Map<string, Guess>; revealed: boolean; ownerPoints: number; arcLine: string }
interface Player {
  userId: string; name: string; absent: boolean; links: number; graceTimer?: NodeJS.Timeout;
  /** "Your list in the pool" (users.settings.whoseDayShareList, default on). */
  pool: boolean;
  /** "Your game is still on" was sent (once per game). */
  toldStillOn?: boolean;
  score: number; guessPoints: number; ownerPoints: number; right: number; early: number; guessed: number; lockMs: number[]; fooled: number;
}
interface Game {
  id: string; allianceId: string; phase: Phase; players: Map<string, Player>; rounds: Round[]; current: number;
  ranked: boolean; wantsRanked: boolean; localDate: string; endsAt: number; timer?: NodeJS.Timeout; stageTimers: NodeJS.Timeout[];
  done: { finished: number; eligible: number };
  results?: Results;
}
interface Results {
  scores: { userId: string; score: number; right: number; early: number }[];
  sharpest: { userId: string; points: number; right: number; of: number; early: number } | null;
  darkHorse: { userId: string; points: number; fooled: number; lists: number } | null;
  favourite: { task: string; userId: string; streak: number } | null;
}

const games = new Map<string, Game>();
const openByAlliance = new Map<string, string>();

export const isWhoseDay = (id: unknown) => typeof id === 'string' && games.has(id);
export const openWhoseDay = (allianceId: string) => {
  const g = games.get(openByAlliance.get(allianceId) ?? '');
  return g && !['results', 'cancelled'].includes(g.phase) ? g : undefined;
};

// --- Lists ---

const HINTS: Record<string, string> = {
  fitness: 'someone training for something', study: 'someone studying for something', language: 'someone learning a language',
  career: 'someone building their career', money: 'someone saving for something', creative: 'someone making something',
  health: 'someone looking after their health', habits: 'someone building a habit',
};
export const goalHint = (category: string | null, isPrivate: boolean) => (isPrivate ? 'a private goal' : HINTS[category ?? ''] ?? 'someone working on a goal');

const PRIORITY: Record<string, Priority> = { High: 'high', Medium: 'med', Low: 'low' };

/** Days in a row, ending today, that a task was ticked. */
export function streakOf(days: string[], today: string): number {
  const set = new Set(days);
  let n = 0;
  const d = new Date(`${today}T00:00:00Z`);
  while (set.has(d.toISOString().slice(0, 10))) { n++; d.setUTCDate(d.getUTCDate() - 1); }
  return n;
}

/** "Private task": name gone, length kept. A private goal makes all of its tasks private too. */
export const strip = (t: { name: string; private: boolean; durationMin: number | null; priority: Priority; doneAt: string | null }, goalPrivate: boolean): PublicTask =>
  t.private || goalPrivate ? { name: null, private: true, durationMin: t.durationMin, priority: t.priority, doneAt: t.doneAt } : { ...t, private: false };

/** What a player finished today, across their active goals, in the order they ticked them. Null if they can't be in the pool. */
async function dayList(allianceId: string, userId: string): Promise<DayList | null> {
  const [m] = await sql`select m.goal_id, g.label, g.category, g.deadline::text, g.private, coalesce(u.home_timezone, 'UTC') as tz
                        from alliance_members m join goals g on g.id = m.goal_id join users u on u.id = m.user_id
                        where m.alliance_id = ${allianceId} and m.user_id = ${userId} and m.status <> 'left'`;
  if (!m) return null;
  const today = await localToday(sql, userId);
  const rows = await sql`select t.id, t.goal_id, t.title, t.priority, t.minutes, t.private, g.private as goal_private,
                                to_char(c.completed_at at time zone ${m.tz}, 'HH24:MI') as done_at
                         from task_completions c join tasks t on t.id = c.task_id join goals g on g.id = t.goal_id
                         where c.user_id = ${userId} and c.local_date = ${today} and t.deleted_at is null and g.status = 'active'
                         order by c.completed_at, t.position`;
  if (!rows.some(r => !r.private && !r.goal_private)) return null;           // nothing to guess from
  const history = await sql`select task_id, local_date::text as d from task_completions where task_id in ${sql(rows.map(r => r.id as string))} and local_date > ${today}::date - 60`;
  const byTask = new Map<string, string[]>();
  for (const h of history) byTask.set(h.task_id, [...(byTask.get(h.task_id) ?? []), h.d]);
  return {
    ownerId: userId, allianceGoalId: m.goal_id,
    tasks: rows.map(r => ({
      ...strip({ name: r.title, private: r.private, durationMin: r.minutes ?? null, priority: PRIORITY[r.priority] ?? 'med', doneAt: r.done_at }, r.goal_private),
      goalId: r.goal_id, streak: streakOf(byTask.get(r.id) ?? [], today),
    })),
    goalHint: goalHint(m.category, m.private), goalPrivate: m.private,
    goalTitle: m.private ? null : m.label, deadline: m.private ? null : m.deadline,
  };
}

/** "7:40am" from "07:40". */
const clock = (hhmm: string | null) => {
  if (!hhmm) return null;
  const [h, mm] = hhmm.split(':').map(Number) as [number, number];
  return `${h % 12 === 0 ? 12 : h % 12}:${String(mm).padStart(2, '0')}${h < 12 ? 'am' : 'pm'}`;
};

/** Arc's line at the reveal: one named task, when it was done, and who did it. */
export function arcLine(list: DayList, name: string): string {
  const named = list.tasks.filter(t => t.name);
  const t = [...named].sort((a, b) => b.streak - a.streak)[0];
  if (!t) return `${name} kept the whole list to themselves.`;
  const at = clock(t.doneAt);
  const runs = t.streak >= 3 ? ` ${t.streak} days running.` : '';
  return `${t.name}${at ? ` at ${at}` : ''}.${runs} ${name} means it.`;
}

// --- Names: first names, with a last initial where two players share one ---

export function displayNames(rows: { id: string; displayName: string }[]): Map<string, string> {
  const first = (s: string) => s.trim().split(/\s+/)[0] ?? s;
  const count = new Map<string, number>();
  for (const r of rows) count.set(first(r.displayName).toLowerCase(), (count.get(first(r.displayName).toLowerCase()) ?? 0) + 1);
  return new Map(rows.map(r => {
    const parts = r.displayName.trim().split(/\s+/);
    const f = parts[0] ?? r.displayName;
    const last = parts.length > 1 ? parts[parts.length - 1]![0]!.toUpperCase() : '';
    return [r.id, (count.get(f.toLowerCase()) ?? 0) > 1 && last ? `${f} ${last}.` : f];
  }));
}

async function rename(g: Game) {
  const ids = [...g.players.keys()];
  const rows = await sql`select id, display_name from users where id in ${sql(ids)}`;
  const names = displayNames(rows.map(r => ({ id: r.id, displayName: r.display_name })));
  for (const p of g.players.values()) p.name = names.get(p.userId) ?? p.name;
}

// --- Stages and scoring ---

/** 1, 2 or 3 while guessing is open (by server time); 4 once it has closed. */
export const stageAt = (startsAt: number, now: number): 1 | 2 | 3 | 4 =>
  now < startsAt + wdTiming.stageMs ? 1 : now < startsAt + 2 * wdTiming.stageMs ? 2 : now < startsAt + wdTiming.closeMs ? 3 : 4;

/** Everyone who can guess this round: present players other than the owner. */
const guessers = (g: Game, r: Round) => [...g.players.values()].filter(p => !p.absent && p.userId !== r.list.ownerId);
const present = (g: Game) => [...g.players.values()].filter(p => !p.absent);

/** Final order: points, then right guesses at the 3-point stage, then the earliest average lock-in. */
export function rank<T extends { score: number; early: number; lockMs: number[]; name: string }>(ps: T[]): T[] {
  const avg = (p: T) => (p.lockMs.length ? p.lockMs.reduce((a, b) => a + b, 0) / p.lockMs.length : Infinity);
  return [...ps].sort((a, b) => b.score - a.score || b.early - a.early || avg(a) - avg(b) || a.name.localeCompare(b.name));
}

// --- Views ---

function roundView(g: Game, r: Round, index: number) {
  const now = Date.now();
  const stage = r.revealed ? 4 : stageAt(r.startsAt, now);
  const showMeta = stage >= 2;
  const tasks = r.list.tasks.map(t => ({
    name: t.name, private: t.private, durationMin: showMeta ? t.durationMin : null,
    ...(showMeta ? { priority: t.priority, doneAt: t.doneAt } : {}),
  }));
  const minutes = r.list.tasks.reduce((a, t) => a + (t.durationMin ?? 0), 0);
  const finishedBy = r.list.tasks.map(t => t.doneAt).filter((x): x is string => !!x).sort().at(-1) ?? null;
  const owner = g.players.get(r.list.ownerId);
  return {
    index, total: g.rounds.length, stage, startsAt: r.startsAt,
    stageEndsAt: stage === 1 ? r.startsAt + wdTiming.stageMs : stage === 2 ? r.startsAt + 2 * wdTiming.stageMs : stage === 3 ? r.startsAt + wdTiming.closeMs : r.startsAt + wdTiming.roundMs,
    closesAt: r.startsAt + wdTiming.closeMs, revealAt: r.startsAt + wdTiming.roundMs,
    tasks, count: tasks.length,
    ...(showMeta ? { minutes, finishedBy } : {}),
    ...(stage >= 3 ? { goalHint: r.list.goalHint } : {}),
    guessed: r.guesses.size, guessers: guessers(g, r).length,
    ...(r.revealed ? {
      reveal: {
        ownerId: r.list.ownerId, goalTitle: r.list.goalTitle, deadline: r.list.deadline, goalPrivate: r.list.goalPrivate,
        ownerPoints: r.ownerPoints, arcLine: r.arcLine,
        guesses: [...g.players.values()].filter(p => p.userId !== r.list.ownerId).map(p => {
          const x = r.guesses.get(p.userId);
          return { userId: p.userId, guessId: x?.guessId ?? null, correct: !!x?.correct, points: x?.points ?? 0, stage: x?.stage ?? null };
        }),
        ownerName: owner?.name ?? '',
      },
    } : {}),
  };
}

function view(g: Game) {
  const r = g.rounds[g.current];
  return {
    id: g.id, kind: 'whose_day', allianceId: g.allianceId, ranked: g.ranked, phase: g.phase, endsAt: g.endsAt, now: Date.now(),
    done: g.done,
    rounds: g.phase === 'lobby' ? plannedRounds(present(g).length) : g.rounds.length, current: g.current,
    players: [...g.players.values()].map(p => ({ userId: p.userId, name: p.name, absent: p.absent, score: p.score })),
    round: r && g.phase !== 'lobby' && g.phase !== 'results' ? roundView(g, r, g.current) : null,
    results: g.phase === 'results' ? g.results ?? null : null,
  };
}

const plannedRounds = (players: number) => (players <= 4 ? SMALL_ROUNDS : MAX_ROUNDS);

/** Only to this player: whether it's their list, their own guess, and in the lobby what others would see of their list. */
async function you(g: Game, p: Player) {
  const r = g.rounds[g.current];
  const base = { type: 'wd:you', gameId: g.id, round: g.current, pool: p.pool };
  if (g.phase === 'lobby') {
    const list = await dayList(g.allianceId, p.userId).catch(() => null);
    return { ...base, owner: false, guess: null, eligible: !!list, preview: list ? list.tasks.map(t => ({ name: t.name, private: t.private, durationMin: t.durationMin })) : [] };
  }
  const mine = r?.guesses.get(p.userId);
  return { ...base, owner: r?.list.ownerId === p.userId && !r.revealed, guess: mine ? { guessId: mine.guessId, stage: mine.stage } : null };
}

const publish = (g: Game) => bus.publish(`game:${g.id}`, { type: 'wd:state', game: view(g) });
const tell = async (g: Game, p: Player) => bus.publish(`user:${p.userId}`, await you(g, p));

function clearTimers(g: Game) { clearTimeout(g.timer); g.stageTimers.forEach(clearTimeout); g.stageTimers = []; }

// --- Lifecycle ---

export async function openLobby(allianceId: string): Promise<Game> {
  const open = openWhoseDay(allianceId);
  if (open) return open;
  const s = await unlockStatus(allianceId);
  const [owner] = await sql`select owner_id from alliances where id = ${allianceId}`;
  const g: Game = {
    id: uuidv7(), allianceId, phase: 'lobby', players: new Map(), rounds: [], current: 0, ranked: false, wantsRanked: s.ranked,
    localDate: await localToday(sql, owner!.owner_id), endsAt: Date.now() + wdTiming.lobbyMs, stageTimers: [],
    done: { finished: s.finished, eligible: s.eligible },
  };
  games.set(g.id, g); openByAlliance.set(allianceId, g.id);
  g.timer = setTimeout(() => { if (g.phase === 'lobby') { g.phase = 'cancelled'; publish(g); cleanup(g); } }, wdTiming.lobbyMs);
  return g;
}

/** Any player can start once there are 3. Lists come from present players who are done today and in the pool. */
async function start(g: Game, ctx: SocketCtx) {
  if (g.phase !== 'lobby') return;
  const ps = present(g);
  if (ps.length < MIN_PLAYERS) return ctx.send({ type: 'wd:error', gameId: g.id, error: 'need_players' });
  const status = await unlockStatus(g.allianceId);
  const done = new Set(status.members.filter(m => m.done).map(m => m.userId));
  const lists: DayList[] = [];
  for (const p of ps) {
    if (!p.pool || !done.has(p.userId)) continue;
    const l = await dayList(g.allianceId, p.userId);
    if (l) lists.push(l);
  }
  if (!lists.length) return ctx.send({ type: 'wd:error', gameId: g.id, error: 'no_lists' });
  // Owners at random, no repeats.
  for (let i = lists.length - 1; i > 0; i--) { const j = randomInt(i + 1); [lists[i], lists[j]] = [lists[j]!, lists[i]!]; }
  const n = Math.min(plannedRounds(ps.length), lists.length);
  g.rounds = lists.slice(0, n).map(list => ({ list, startsAt: 0, guesses: new Map(), revealed: false, ownerPoints: 0, arcLine: '' }));
  clearTimers(g);
  // One ranked game night per alliance per day (either game); replays are practice.
  g.ranked = false;
  if (g.wantsRanked && ps.length >= MIN_RANKED) {
    const [row] = await sql`insert into game_sessions (id, alliance_id, local_date, ranked, kind) values (${g.id}, ${g.allianceId}, ${g.localDate}, true, 'whose_day') on conflict do nothing returning 1`;
    g.ranked = !!row;
  }
  if (!g.ranked) await sql`insert into game_sessions (id, alliance_id, local_date, ranked, kind) values (${g.id}, ${g.allianceId}, ${g.localDate}, false, 'whose_day')`;
  // Only players at the start play; anyone still joining waits for the next game.
  g.current = 0;
  beginRound(g);
}

function beginRound(g: Game) {
  const r = g.rounds[g.current];
  if (!r) return void finish(g);
  g.phase = 'round';
  r.startsAt = Date.now();
  g.endsAt = r.startsAt + wdTiming.roundMs;
  clearTimers(g);
  g.stageTimers = [wdTiming.stageMs, 2 * wdTiming.stageMs, wdTiming.closeMs].map(ms => setTimeout(() => publish(g), ms));
  g.timer = setTimeout(() => reveal(g), wdTiming.roundMs);
  publish(g);
  for (const p of present(g)) void tell(g, p);
}

function reveal(g: Game) {
  const r = g.rounds[g.current];
  if (g.phase !== 'round' || !r || r.revealed) return;
  clearTimers(g);
  r.revealed = true;
  const owner = g.players.get(r.list.ownerId);
  // The owner gets a point for each present player who guessed wrong or didn't guess.
  const fooled = guessers(g, r).filter(p => !r.guesses.get(p.userId)?.correct).length;
  r.ownerPoints = fooled;
  if (owner && !owner.absent) { owner.score += fooled; owner.ownerPoints += fooled; owner.fooled += fooled; }
  for (const [id, x] of r.guesses) {
    const p = g.players.get(id);
    if (!p || p.absent) continue;
    p.score += x.points; p.guessPoints += x.points; p.guessed++;
    if (x.correct) { p.right++; if (x.stage === 1) p.early++; }
    p.lockMs.push(x.atMs - r.startsAt);
  }
  r.arcLine = arcLine(r.list, owner?.name ?? '');
  g.phase = 'reveal';
  g.endsAt = Date.now() + wdTiming.revealMs;
  g.timer = setTimeout(() => { g.current++; if (g.current < g.rounds.length) beginRound(g); else void finish(g); }, wdTiming.revealMs);
  publish(g);
  for (const p of present(g)) void tell(g, p);
}

/** Sharpest Eye, Dark Horse and Arc's favourite task of the night. */
export function results(players: Player[], rounds: Round[]): Results {
  const ranked = rank(players);
  const by = (f: (p: Player) => number) => rank(players.filter(p => f(p) > 0).map(p => ({ ...p, score: f(p) })))[0] ?? null;
  const eye = by(p => p.guessPoints), horse = by(p => p.ownerPoints);
  const shown = rounds.filter(r => r.revealed);
  const ownLists = (id: string) => shown.filter(r => r.list.ownerId === id).length;
  // Prefer a "life" task (not for the alliance goal), then the longest streak.
  const pool = shown.flatMap(r => r.list.tasks.filter(t => t.name).map(t => ({ t, ownerId: r.list.ownerId, life: t.goalId !== r.list.allianceGoalId })));
  const fav = pool.sort((a, b) => Number(b.life) - Number(a.life) || b.t.streak - a.t.streak)[0];
  return {
    scores: ranked.map(p => ({ userId: p.userId, score: p.score, right: p.right, early: p.early })),
    sharpest: eye ? { userId: eye.userId, points: eye.guessPoints, right: eye.right, of: shown.length - ownLists(eye.userId), early: eye.early } : null,
    darkHorse: horse ? { userId: horse.userId, points: horse.ownerPoints, fooled: horse.fooled, lists: ownLists(horse.userId) } : null,
    favourite: fav ? { task: fav.t.name!, userId: fav.ownerId, streak: fav.t.streak } : null,
  };
}

async function finish(g: Game) {
  if (g.phase === 'results' || g.phase === 'cancelled') return;
  clearTimers(g);
  g.results = results([...g.players.values()], g.rounds);
  g.phase = 'results';
  g.endsAt = Date.now();
  const winner = g.results.scores[0]?.userId ?? null;
  await sql`update game_sessions set winner_id = ${winner}, ended_at = now() where id = ${g.id}`;
  for (const p of g.players.values()) {
    await sql`insert into game_players (game_id, user_id, role, actions, score, absent) values (${g.id}, ${p.userId}, 'player', ${p.right}, ${p.score}, ${p.absent})
              on conflict (game_id, user_id) do nothing`;
  }
  publish(g);
  g.timer = setTimeout(() => cleanup(g), wdTiming.resultMs);
}

function cleanup(g: Game) {
  clearTimers(g);
  for (const p of g.players.values()) clearTimeout(p.graceTimer);
  if (openByAlliance.get(g.allianceId) === g.id) openByAlliance.delete(g.allianceId);
  games.delete(g.id);
}

/** Gone past the grace period: out of the lobby, or absent from the game (their list stays). Below 3 players the game ends. */
function markAbsent(g: Game, p: Player) {
  if (p.links > 0 || p.absent || g.phase === 'results' || g.phase === 'cancelled') return;
  if (g.phase === 'lobby') { g.players.delete(p.userId); publish(g); return; }
  p.absent = true;
  if (present(g).length < MIN_PLAYERS) { void finish(g); return; }
  publish(g);
  const r = g.rounds[g.current];
  if (g.phase === 'round' && r && guessers(g, r).every(x => r.guesses.has(x.userId))) reveal(g);
}

// --- Intents from the socket ---

const Frame = z.discriminatedUnion('type', [
  z.object({ type: z.literal('wd:join'), gameId: z.string().uuid() }),
  z.object({ type: z.literal('wd:start'), gameId: z.string().uuid() }),
  z.object({ type: z.literal('wd:pool'), gameId: z.string().uuid(), on: z.boolean() }),
  z.object({ type: z.literal('wd:guess'), gameId: z.string().uuid(), target: z.string().uuid() }),
  z.object({ type: z.literal('wd:leave'), gameId: z.string().uuid() }),
]);

const poolSetting = async (userId: string) => {
  const [u] = await sql`select settings from users where id = ${userId}`;
  return (u?.settings as Record<string, unknown> | null)?.whoseDayShareList !== false;
};

export async function whoseDayFrame(userId: string, raw: unknown, ctx: SocketCtx) {
  const f = Frame.safeParse(raw);
  if (!f.success) return ctx.send({ type: 'wd:error', error: 'invalid_frame' });
  const m = f.data;
  const g = games.get(m.gameId);
  if (!g) return ctx.send({ type: 'wd:error', gameId: m.gameId, error: 'game_over' });
  let p = g.players.get(userId);

  if (m.type === 'wd:join') {
    const [member] = await sql`select 1 from alliance_members where alliance_id = ${g.allianceId} and user_id = ${userId} and status <> 'left'`;
    if (!member) return ctx.send({ type: 'wd:error', gameId: g.id, error: 'not_member' });
    if (!p) {
      if (g.phase !== 'lobby') return ctx.send({ type: 'wd:error', gameId: g.id, error: 'already_started' });
      const blocked = new Set(await blockedWith(sql, userId));
      if ([...g.players.keys()].some(id => blocked.has(id))) return ctx.send({ type: 'wd:error', gameId: g.id, error: 'unavailable' });
      const [u] = await sql`select display_name from users where id = ${userId}`;
      p = { userId, name: u!.display_name, absent: false, links: 0, pool: await poolSetting(userId),
        score: 0, guessPoints: 0, ownerPoints: 0, right: 0, early: 0, guessed: 0, lockMs: [], fooled: 0 };
      g.players.set(userId, p);
      await rename(g);
      const s = await unlockStatus(g.allianceId);
      g.done = { finished: s.finished, eligible: s.eligible };
    }
    if (!ctx.games.has(g.id)) { ctx.games.add(g.id); ctx.watch(`game:${g.id}`); p.links++; }
    clearTimeout(p.graceTimer);
    // Coming back (reconnect or foreground): the current stage from server state, plus what only this player may know.
    ctx.send({ type: 'wd:state', game: view(g) });
    if (!p.absent) ctx.send(await you(g, p));
    publish(g);
    return;
  }
  if (!p || p.absent) return ctx.send({ type: 'wd:error', gameId: g.id, error: 'not_in_game' });

  switch (m.type) {
    case 'wd:start': return start(g, ctx);
    case 'wd:pool': {
      p.pool = m.on;
      // Saved as a synced setting so Settings → Privacy shows the same switch.
      await sql.begin(async tx => {
        await tx`update users set settings = settings || ${tx.json({ whoseDayShareList: m.on })} where id = ${userId}`;
        await tx`insert into changes (user_id, entity, entity_id, key, op) values (${userId}, 'settings', ${userId}, null, 'upsert')`;
      });
      ctx.send(await you(g, p));
      return;
    }
    case 'wd:guess': {
      const r = g.rounds[g.current];
      if (g.phase !== 'round' || !r) return;
      const now = Date.now();
      const stage = stageAt(r.startsAt, now);
      // Owners can't guess their own list; one guess each; nothing after 15 seconds; only players in this game.
      if (userId === r.list.ownerId) return ctx.send({ type: 'wd:error', gameId: g.id, error: 'own_list' });
      if (r.guesses.has(userId)) return ctx.send({ type: 'wd:error', gameId: g.id, error: 'already_guessed' });
      if (stage === 4) return ctx.send({ type: 'wd:error', gameId: g.id, error: 'closed' });
      if (m.target === userId || !g.players.has(m.target)) return ctx.send({ type: 'wd:error', gameId: g.id, error: 'invalid_guess' });
      const correct = m.target === r.list.ownerId;
      r.guesses.set(userId, { guessId: m.target, stage, atMs: now, correct, points: correct ? POINTS[stage - 1]! : 0 });
      ctx.send(await you(g, p));
      // Everyone else learns only how many have locked in.
      if (guessers(g, r).every(x => r.guesses.has(x.userId))) reveal(g);
      else publish(g);
      return;
    }
    case 'wd:leave': {
      p.links = 0; ctx.games.delete(g.id);
      markAbsent(g, p);
      return;
    }
  }
}

export function whoseDaySocketClosed(userId: string, ctx: SocketCtx) {
  for (const id of ctx.games) {
    const g = games.get(id), p = g?.players.get(userId);
    if (!g || !p) continue;
    p.links = Math.max(0, p.links - 1);
    if (p.links > 0) continue;
    clearTimeout(p.graceTimer);
    p.graceTimer = setTimeout(() => markAbsent(g, p), wdTiming.graceMs);
    if (['round', 'reveal'].includes(g.phase) && !p.toldStillOn) {
      p.toldStillOn = true;
      void push(userId, { title: 'Your game is still on', body: 'Come back in the next few seconds to keep playing.', data: { allianceId: g.allianceId }, collapseKey: `game-${g.id}` }, 'system');
    }
  }
}

export const whoseDayView = (g: Game) => view(g);
