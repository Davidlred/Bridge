import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { env } from '../env.js';
import { sql } from '../db.js';
import { auth } from '../auth/tokens.js';
import { HttpError } from '../util.js';
import { decryptText, encryptText, journalKey } from '../crypto.js';
import { AIUnavailable, streamText } from '../ai/replicate.js';
import { STYLE_VOICE, effectiveStyle, type CoachStyle } from './styles.js';
import { takeQuota } from '../goals/routes.js';
import { PLAIN_VOICE } from '../ai/voice.js';
import { suggestAlliances, type AllianceOption } from '../social/alliances.js';
import { communityUnlocked } from '../social/progress.js';

const COACH_DAILY_LIMIT = 30;

const Task = z.object({ title: z.string().max(120), done: z.boolean(), priority: z.string().max(10), timeOfDay: z.string().max(24).nullish() });
const GoalCtx = z.object({
  id: z.string().uuid().optional(),
  label: z.string().max(24), statement: z.string().max(500), deadline: z.string().max(10),
  day: z.number().int(), total: z.number().int(), streak: z.number().int(), tasks: z.array(Task).max(20),
});
export const CoachRequest = z.object({
  message: z.string().trim().min(1).max(2000),
  history: z.array(z.object({ role: z.enum(['user', 'coach']), text: z.string().max(4000) })).max(20).default([]),
  goals: z.array(GoalCtx).max(3).default([]),
  focusGoalId: z.string().uuid().nullish(),
  mode: z.enum(['chat', 'study']).default('chat'),
  localDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  // Ids for syncing the exchange to the user's other devices (optional for older clients).
  conversation: z.string().uuid().optional(),
  messageId: z.string().uuid().optional(),
  replyId: z.string().uuid().optional(),
  sentAt: z.number().int().optional(),
  // "Let Arc learn my routine" (opt-in, computed on the phone): hours only and a Wi-Fi share, never raw usage or location.
  rhythm: z.object({
    days: z.number().int().min(1).max(28),
    openHour: z.number().int().min(0).max(23),
    doneHour: z.number().int().min(0).max(23).optional(),
    finishedBy: z.number().int().min(1).max(27).optional(),
    wifiShare: z.number().min(0).max(1).optional(),
  }).optional(),
});
type CoachRequest = z.infer<typeof CoachRequest>;

export const Suggestion = z.object({
  title: z.string().trim().min(3).max(80),
  rationale: z.string().trim().max(200).default(''),
  priority: z.enum(['High', 'Medium', 'Low']).catch('Medium'),
  timeOfDay: z.string().max(24).nullish(),
});

/** The coach's pick among the alliances it was shown, with why; the server fills in the rest from its own list. */
const AlliancePick = z.object({ id: z.string().uuid(), reason: z.string().trim().max(200).default('') });
/** What the app shows and stores: enough to open the alliance's join preview. */
export type AllianceCard = { id: string; name: string; about: string | null; planMode: 'own' | 'shared'; members: number; cap: number; code: string; reason: string };

const persona = (name: string, mode: string, style: CoachStyle = 'balanced') => `You are ${name}, the coach inside The Bridge, a goal app. You know the user's goals, today's tasks and their streak.
${STYLE_VOICE[style]} Never shame missed days. Always end with one concrete next step.
${PLAIN_VOICE}
No medical, legal or financial advice beyond general guidance; for health topics suggest checking with a professional.
${mode === 'study' ? 'The user is in Study Mode, focused on work. Keep replies especially brief (40 words or fewer).\n' : ''}If one new daily task would clearly help, append it after your reply as a fenced JSON block, exactly:
\`\`\`json
{"suggestTask":{"title":"...","rationale":"one sentence","priority":"High|Medium|Low","timeOfDay":"e.g. 7:00 AM or Evening"}}
\`\`\`
At most one suggestion. Never suggest a task that is already on the plan.`;

/** Crude but conservative: anything that reads like self-harm gets a supportive reply with resources instead of coaching. */
const CRISIS = /\b(kill(ing)? myself|suicid|end (it all|my life)|self[- ]?harm|hurt(ing)? myself|want to die|don'?t want to (live|be alive)|cut(ting)? myself)\b/i;
export const CRISIS_REPLY = "I'm really sorry you're feeling this way, and I'm glad you said it. You deserve support right now from a person, not an app. If you might act on these thoughts, please call your local emergency number. You can find a free, confidential helpline in your country at findahelpline.com. Talking to someone you trust can help too. I'm here for your goals whenever you're ready.";

/**
 * Holds back anything that might be the start of a suggestion block, so the visible stream never shows JSON.
 * The suggestion itself is parsed when the stream ends.
 */
export class SuggestionSplitter {
  private pending = '';
  private block: string | null = null;
  private static markers = ['```', '{"suggestTask"', '{"suggestAlliance"'];

  push(chunk: string): string {
    if (this.block !== null) { this.block += chunk; return ''; }
    this.pending += chunk;
    let cut = -1;
    for (const m of SuggestionSplitter.markers) { const i = this.pending.indexOf(m); if (i >= 0 && (cut < 0 || i < cut)) cut = i; }
    if (cut >= 0) {
      const out = this.pending.slice(0, cut);
      this.block = this.pending.slice(cut); this.pending = '';
      return out;
    }
    // Keep a tail that could still grow into a marker.
    let keep = 0;
    for (const m of SuggestionSplitter.markers) for (let n = Math.min(m.length - 1, this.pending.length); n > keep; n--) if (this.pending.endsWith(m.slice(0, n))) { keep = n; break; }
    const out = this.pending.slice(0, this.pending.length - keep);
    this.pending = this.pending.slice(this.pending.length - keep);
    return out;
  }

  finish(): { rest: string; suggestion: z.infer<typeof Suggestion> | null; alliance: z.infer<typeof AlliancePick> | null } {
    if (this.block === null) return { rest: this.pending, suggestion: null, alliance: null };
    try {
      const raw = this.block.replace(/```(?:json)?/gi, '').trim();
      const json = JSON.parse(raw.slice(raw.indexOf('{'), raw.lastIndexOf('}') + 1));
      const s = Suggestion.safeParse(json.suggestTask);
      const a = AlliancePick.safeParse(json.suggestAlliance);
      return { rest: '', suggestion: s.success ? s.data : null, alliance: a.success ? a.data : null };
    } catch {
      return { rest: '', suggestion: null, alliance: null };
    }
  }
}

/**
 * Each goal has its own conversation. The goal in focus gets full detail; the others only appear by name, so the coach can
 * avoid clashes (time, energy) without drifting into them. Without a focus (older apps) every goal is described.
 */
const clock = (h: number) => { const x = h % 24; return x === 0 ? 'midnight' : x === 12 ? 'noon' : x < 12 ? `${x} AM` : `${x - 12} PM`; };

/** One line on when the user actually uses the app, so suggestions fit their day. */
export function routineLine(r: NonNullable<CoachRequest['rhythm']>): string {
  const parts = [`usually opens the app around ${clock(r.openHour)}`];
  if (r.doneHour !== undefined) parts.push(`gets tasks done around ${clock(r.doneHour)}`);
  if (r.finishedBy !== undefined) parts.push(`is done by ${clock(r.finishedBy)} on most days`);
  if (r.wifiShare !== undefined) parts.push(r.wifiShare >= 0.7 ? 'mostly on Wi-Fi (home or work)' : r.wifiShare <= 0.3 ? 'mostly on mobile data (out and about)' : 'on Wi-Fi about half the time');
  return `Their routine over the last ${r.days} days (they allowed the app to learn it): ${parts.join(', ')}. Use it to time suggestions; don't mention that it is tracked unless they ask.`;
}

/** Findable alliances that fit the goal in focus, and how the coach may recommend one. */
export function alliancesBlock(options: AllianceOption[]): string {
  if (!options.length) return '';
  const lines = options.map(o => `- id ${o.id}: "${o.name}", ${o.planMode === 'shared' ? `one shared plan ("${o.goal}")` : 'everyone keeps their own plan'}, ${o.members} of ${o.cap} members${o.about ? `. About: "${o.about}"` : ''}`);
  return [
    'Alliances in The Bridge the user could join (small groups that keep each other going; their leads made them findable):',
    ...lines,
    'If the user asks about groups, company or accountability, or one of these clearly fits what they are working on and they would benefit, you may recommend one: say in a sentence why it fits, then append a fenced JSON block exactly like this, in place of a task suggestion:',
    '```json\n{"suggestAlliance":{"id":"<an id from this list>","reason":"one sentence"}}\n```',
    'At most one, and not in every reply. Never invent an alliance or an id.',
  ].join('\n');
}

function prompt(req: CoachRequest, journal: string[], alliances: AllianceOption[] = []): string {
  const focus = req.focusGoalId ? req.goals.find(g => g.id === req.focusGoalId) : undefined;
  const detail = (g: CoachRequest['goals'][number]) => {
    const tasks = g.tasks.map(t => `  - [${t.done ? 'x' : ' '}] ${t.title} (${t.priority}${t.timeOfDay ? `, ${t.timeOfDay}` : ''})`).join('\n');
    return `Goal: ${g.statement} — "${g.label}", deadline ${g.deadline}, day ${g.day} of ${g.total}, streak ${g.streak} days.\nToday's tasks:\n${tasks || '  (none)'}`;
  };
  const others = focus ? req.goals.filter(g => g !== focus).map(g => `"${g.label}"`) : [];
  const goals = focus
    ? [`This conversation is only about this goal:\n${detail(focus)}`,
       others.length ? `The user also has other goals (${others.join(', ')}). They have their own conversations: don't bring them up unless the user does.` : '']
        .filter(Boolean).join('\n\n')
    : req.goals.map(detail).join('\n\n');
  const history = req.history.map(m => `${m.role === 'user' ? 'User' : 'Coach'}: ${m.text}`).join('\n');
  return [
    `Today is ${req.localDate}.`,
    goals || 'The user has no active goals yet.',
    req.rhythm ? routineLine(req.rhythm) : '',
    alliancesBlock(alliances),
    journal.length ? `Recent journal entries (the user allowed you to read these):\n${journal.join('\n')}` : '',
    history ? `Conversation so far:\n${history}` : '',
    `User: ${req.message}\nCoach:`,
  ].filter(Boolean).join('\n\n');
}

/** Stores one message (encrypted with the user's key) and logs it for sync. */
export async function saveMessage(userId: string, id: string, conversation: string, role: 'user' | 'coach', text: string,
  suggestion: z.infer<typeof Suggestion> | null, status: 'sent' | 'done' | 'interrupted', at: Date, goalId: string | null = null, alliance: AllianceCard | null = null) {
  await sql.begin(async tx => {
    const [other] = await tx`select user_id from coach_messages where id = ${id}`;
    if (other && other.user_id !== userId) return;                 // never touch another user's row
    const key = await journalKey(tx, userId);
    const body = encryptText(key, JSON.stringify({ text, suggestion, alliance }));
    await tx`insert into coach_messages (id, user_id, conversation, role, body_ciphertext, status, suggestion_state, created_at, goal_id)
             values (${id}, ${userId}, ${conversation}, ${role}, ${body}, ${status}, ${suggestion || alliance ? 'pending' : null}, ${at}, ${goalId})
             on conflict (id) do update set body_ciphertext = excluded.body_ciphertext, status = excluded.status,
               suggestion_state = coalesce(coach_messages.suggestion_state, excluded.suggestion_state)`;
    await tx`insert into changes (user_id, entity, entity_id, op) values (${userId}, 'chat', ${id}, 'upsert')`;
  });
}

export async function coachRoutes(app: FastifyInstance) {
  // Server-Sent Events: "token" {text}* → "suggestion" {…}? | "alliance" {…}? → "done" {} | "error" {error}
  app.post('/v1/coach/messages', { onRequest: auth, config: { rateLimit: { max: 60, timeWindow: '1 minute' } } }, async (req, reply) => {
    const parsed = CoachRequest.safeParse(req.body);
    if (!parsed.success) throw new HttpError(400, 'invalid_request');
    const r = parsed.data;
    const [u] = await sql`select coach_name, coach_style, settings from users where id = ${req.userId}`;
    const crisis = CRISIS.test(r.message);
    if (!crisis) await takeQuota(req.userId, 'coach', COACH_DAILY_LIMIT);

    // The journal is only read when the user opted in ("Let my coach read my journal"), and never logged.
    let journal: string[] = [];
    if (!crisis && (u?.settings as Record<string, unknown> | undefined)?.coachReadsJournal === true) {
      const rows = await sql`select local_date::text, mood, body_ciphertext from journal_entries where user_id = ${req.userId} and conflict_of is null order by local_date desc limit 3`;
      if (rows.length) {
        const key = await journalKey(sql, req.userId);
        journal = rows.map(j => `${j.local_date}${j.mood ? ` (mood ${j.mood}/5)` : ''}: ${decryptText(key, j.body_ciphertext).slice(0, 600)}`);
      }
    }

    reply.hijack();
    const out = reply.raw;
    out.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8', 'Cache-Control': 'no-store', Connection: 'keep-alive', 'X-Accel-Buffering': 'no' });
    const send = (event: string, data: unknown) => out.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
    const ctrl = new AbortController();
    // The app left mid-reply: stop the model (the response's 'close', not the request's, which fires once the body is read).
    out.on('close', () => { if (!out.writableFinished) ctrl.abort(); });

    // The question is stored before the model runs, so it reaches the user's other devices even if the reply fails.
    const store = r.conversation && r.messageId && r.replyId;
    const sentAt = new Date(r.sentAt ?? Date.now());
    // The goal the conversation is about, if it is one of the user's own (never trust an id blindly).
    const [owned] = r.focusGoalId ? await sql`select id from goals where id = ${r.focusGoalId} and user_id = ${req.userId}` : [];
    const goalId = owned ? String(owned.id) : null;
    if (store) await saveMessage(req.userId, r.messageId!, r.conversation!, 'user', r.message, null, 'sent', sentAt, goalId);
    let replyText = '';
    let suggestion: z.infer<typeof Suggestion> | null = null;
    let alliance: AllianceCard | null = null;
    // Alliances to recommend: only once Community is open for this goal (joining needs it), never in a crisis.
    const options = goalId && !crisis && (await communityUnlocked(sql, req.userId, goalId)).unlocked ? await suggestAlliances(req.userId, goalId) : [];
    let status: 'done' | 'interrupted' = 'interrupted';
    try {
      if (crisis) {
        for (const word of CRISIS_REPLY.split(/(?<= )/)) { replyText += word; send('token', { text: word }); }
      } else {
        const splitter = new SuggestionSplitter();
        const style = await effectiveStyle(req.userId, u?.coach_style as string | undefined);
        const input = { system_prompt: persona(String(u?.coach_name ?? 'Arc'), r.mode, style), prompt: prompt(r, journal, options), max_tokens: 1024, temperature: 0.6 };
        for await (const chunk of streamText(env.MODEL_COACH, input, ctrl.signal)) {
          const text = splitter.push(chunk);
          if (text) { replyText += text; send('token', { text }); }
        }
        const end = splitter.finish();
        if (end.rest) { replyText += end.rest; send('token', { text: end.rest }); }
        suggestion = end.suggestion;
        if (suggestion) send('suggestion', suggestion);
        // Only an alliance the coach was actually shown, filled in from the server's own list.
        const pick = !suggestion && end.alliance ? options.find(o => o.id === end.alliance!.id) : undefined;
        if (pick) {
          alliance = { id: pick.id, name: pick.name, about: pick.about, planMode: pick.planMode, members: pick.members, cap: pick.cap, code: pick.code, reason: end.alliance!.reason };
          send('alliance', alliance);
        }
      }
      status = 'done';
      send('done', {});
    } catch (e) {
      if (!ctrl.signal.aborted) {
        req.log.warn({ err: e instanceof Error ? e.message : String(e) }, 'coach_stream_failed');
        send('error', { error: e instanceof AIUnavailable ? 'coach_unavailable' : 'internal' });
      }
    } finally {
      // Saved before the stream closes, so the reply is already there when the app syncs right after.
      // A partial reply is kept as "interrupted" (the app shows "Reply interrupted · Retry"); an empty one isn't stored.
      if (store && replyText) {
        await saveMessage(req.userId, r.replyId!, r.conversation!, 'coach', replyText.trimEnd(), suggestion, status, new Date(sentAt.getTime() + 1), goalId, alliance)
          .catch(e => req.log.error({ err: e.message }, 'coach_save_failed'));
      }
      out.end();
    }
  });
}
