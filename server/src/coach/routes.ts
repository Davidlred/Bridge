import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { env } from '../env.js';
import { sql } from '../db.js';
import { auth } from '../auth/tokens.js';
import { HttpError } from '../util.js';
import { decryptText, encryptText, journalKey } from '../crypto.js';
import { AIUnavailable, streamText } from '../ai/replicate.js';
import { takeQuota } from '../goals/routes.js';

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
});
type CoachRequest = z.infer<typeof CoachRequest>;

export const Suggestion = z.object({
  title: z.string().trim().min(3).max(80),
  rationale: z.string().trim().max(200).default(''),
  priority: z.enum(['High', 'Medium', 'Low']).catch('Medium'),
  timeOfDay: z.string().max(24).nullish(),
});

const persona = (name: string, mode: string) => `You are ${name}, the coach inside The Bridge, a goal app. You know the user's goals, today's tasks and their streak.
Voice: warm, direct and short (90 words or fewer unless asked for more). Never shame missed days. Always end with one concrete next step.
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
  private static markers = ['```', '{"suggestTask"'];

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

  finish(): { rest: string; suggestion: z.infer<typeof Suggestion> | null } {
    if (this.block === null) return { rest: this.pending, suggestion: null };
    try {
      const raw = this.block.replace(/```(?:json)?/gi, '').trim();
      const json = JSON.parse(raw.slice(raw.indexOf('{'), raw.lastIndexOf('}') + 1));
      const s = Suggestion.safeParse(json.suggestTask);
      return { rest: '', suggestion: s.success ? s.data : null };
    } catch {
      return { rest: '', suggestion: null };
    }
  }
}

function prompt(req: CoachRequest, journal: string[]): string {
  const goals = req.goals.map(g => {
    const focus = g.id && g.id === req.focusGoalId ? ' (in focus)' : '';
    const tasks = g.tasks.map(t => `  - [${t.done ? 'x' : ' '}] ${t.title} (${t.priority}${t.timeOfDay ? `, ${t.timeOfDay}` : ''})`).join('\n');
    return `Goal${focus}: ${g.statement} — "${g.label}", deadline ${g.deadline}, day ${g.day} of ${g.total}, streak ${g.streak} days.\nToday's tasks:\n${tasks || '  (none)'}`;
  }).join('\n\n');
  const history = req.history.map(m => `${m.role === 'user' ? 'User' : 'Coach'}: ${m.text}`).join('\n');
  return [
    `Today is ${req.localDate}.`,
    goals || 'The user has no active goals yet.',
    journal.length ? `Recent journal entries (the user allowed you to read these):\n${journal.join('\n')}` : '',
    history ? `Conversation so far:\n${history}` : '',
    `User: ${req.message}\nCoach:`,
  ].filter(Boolean).join('\n\n');
}

/** Stores one message (encrypted with the user's key) and logs it for sync. */
export async function saveMessage(userId: string, id: string, conversation: string, role: 'user' | 'coach', text: string,
  suggestion: z.infer<typeof Suggestion> | null, status: 'sent' | 'done' | 'interrupted', at: Date) {
  await sql.begin(async tx => {
    const [other] = await tx`select user_id from coach_messages where id = ${id}`;
    if (other && other.user_id !== userId) return;                 // never touch another user's row
    const key = await journalKey(tx, userId);
    const body = encryptText(key, JSON.stringify({ text, suggestion }));
    await tx`insert into coach_messages (id, user_id, conversation, role, body_ciphertext, status, suggestion_state, created_at)
             values (${id}, ${userId}, ${conversation}, ${role}, ${body}, ${status}, ${suggestion ? 'pending' : null}, ${at})
             on conflict (id) do update set body_ciphertext = excluded.body_ciphertext, status = excluded.status,
               suggestion_state = coalesce(coach_messages.suggestion_state, excluded.suggestion_state)`;
    await tx`insert into changes (user_id, entity, entity_id, op) values (${userId}, 'chat', ${id}, 'upsert')`;
  });
}

export async function coachRoutes(app: FastifyInstance) {
  // Server-Sent Events: "token" {text}* → "suggestion" {…}? → "done" {} | "error" {error}
  app.post('/v1/coach/messages', { onRequest: auth, config: { rateLimit: { max: 60, timeWindow: '1 minute' } } }, async (req, reply) => {
    const parsed = CoachRequest.safeParse(req.body);
    if (!parsed.success) throw new HttpError(400, 'invalid_request');
    const r = parsed.data;
    const [u] = await sql`select coach_name, settings from users where id = ${req.userId}`;
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
    req.raw.on('close', () => ctrl.abort());

    // The question is stored before the model runs, so it reaches the user's other devices even if the reply fails.
    const store = r.conversation && r.messageId && r.replyId;
    const sentAt = new Date(r.sentAt ?? Date.now());
    if (store) await saveMessage(req.userId, r.messageId!, r.conversation!, 'user', r.message, null, 'sent', sentAt);
    let replyText = '';
    let suggestion: z.infer<typeof Suggestion> | null = null;
    let status: 'done' | 'interrupted' = 'interrupted';
    try {
      if (crisis) {
        for (const word of CRISIS_REPLY.split(/(?<= )/)) { replyText += word; send('token', { text: word }); }
      } else {
        const splitter = new SuggestionSplitter();
        const input = { system_prompt: persona(String(u?.coach_name ?? 'Arc'), r.mode), prompt: prompt(r, journal), max_tokens: 600, temperature: 0.6 };
        for await (const chunk of streamText(env.MODEL_COACH, input, ctrl.signal)) {
          const text = splitter.push(chunk);
          if (text) { replyText += text; send('token', { text }); }
        }
        const end = splitter.finish();
        if (end.rest) { replyText += end.rest; send('token', { text: end.rest }); }
        suggestion = end.suggestion;
        if (suggestion) send('suggestion', suggestion);
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
        await saveMessage(req.userId, r.replyId!, r.conversation!, 'coach', replyText, suggestion, status, new Date(sentAt.getTime() + 1))
          .catch(e => req.log.error({ err: e.message }, 'coach_save_failed'));
      }
      out.end();
    }
  });
}
