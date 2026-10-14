import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { uuidv7 } from '../src/util.js';

// The model is replaced by a scripted stream; the last prompt is kept so tests can check what the coach was told.
let script: string[] = [];
let lastPrompt = '';
vi.mock('../src/ai/replicate.js', async orig => {
  const real = await orig<typeof import('../src/ai/replicate.js')>();
  return {
    ...real,
    streamText: vi.fn(async function* (_m: string, input: Record<string, unknown>) {
      lastPrompt = String(input.prompt);
      for (const c of script) yield c;
    }),
  };
});

describe('SuggestionSplitter', async () => {
  const { SuggestionSplitter } = await import('../src/coach/routes.js');
  const run = (chunks: string[]) => {
    const s = new SuggestionSplitter();
    const visible = chunks.map(c => s.push(c)).join('');
    const end = s.finish();
    return { text: visible + end.rest, suggestion: end.suggestion };
  };

  it('never shows the JSON block, even when the fence arrives split across chunks', () => {
    const r = run(['Move breakfast prep to the evening. ', 'Try it tonight.\n`', '``json\n{"suggestTask":{"title":"Prep breakfast before 9 PM",', '"rationale":"Frees 15 minutes.","priority":"Medium","timeOfDay":"8:30 PM"}}\n```']);
    expect(r.text).toBe('Move breakfast prep to the evening. Try it tonight.\n');
    expect(r.suggestion).toEqual({ title: 'Prep breakfast before 9 PM', rationale: 'Frees 15 minutes.', priority: 'Medium', timeOfDay: '8:30 PM' });
  });

  it('handles a bare JSON suggestion and text with stray backticks', () => {
    expect(run(['Use `git` daily. ', '{"suggestTask":{"title":"Commit once a day","priority":"Low"}}'])).toEqual({ text: 'Use `git` daily. ', suggestion: { title: 'Commit once a day', rationale: '', priority: 'Low' } });
    expect(run(['No suggestion today.'])).toEqual({ text: 'No suggestion today.', suggestion: null });
    expect(run(['Broken: ```json {"suggestTask": nope'])).toEqual({ text: 'Broken: ', suggestion: null });
  });
});

const db = describe.skipIf(!process.env.TEST_DATABASE_URL);

db('coach and journal', () => {
  let app: FastifyInstance;
  let sql: typeof import('../src/db.js').sql;

  beforeAll(async () => {
    ({ sql } = await import('../src/db.js'));
    await sql.unsafe('drop schema public cascade; create schema public;');
    await (await import('../src/migrate.js')).migrate();
    (await import('../src/mailer.js')).setMailer(async () => {});
    app = await (await import('../src/app.js')).buildApp();
  });
  afterAll(async () => { await app?.close(); await sql?.end(); });

  const signUp = async () => (await app.inject({ method: 'POST', url: '/v1/auth/email/signup', payload: { email: `${uuidv7()}@example.com`, password: 'correct horse', displayName: 'Sharon' } })).json() as { user: { id: string }; accessToken: string };
  const h = (t: string) => ({ authorization: `Bearer ${t}` });
  const goal = { label: 'Half marathon', statement: 'Run a half marathon in under 2 hours', deadline: '2027-02-09', day: 24, total: 113, streak: 7, tasks: [{ title: 'Easy run 5 km', done: true, priority: 'High', timeOfDay: '7:00 AM' }] };
  const ask = (t: string, message: string, extra: object = {}) => app.inject({ method: 'POST', url: '/v1/coach/messages', headers: h(t), payload: { message, goals: [goal], localDate: '2026-10-14', ...extra } });
  const events = (body: string) => body.split('\n\n').filter(Boolean).map(b => ({ event: /^event: (.*)$/m.exec(b)![1], data: JSON.parse(/^data: (.*)$/m.exec(b)![1]!) }));
  const push = (t: string, mutations: object[]) => app.inject({ method: 'POST', url: '/v1/sync/push', headers: h(t), payload: { mutations } }).then(r => r.json() as { accepted: string[]; rejected: Record<string, string> });
  const pull = (t: string, since = 0) => app.inject({ method: 'GET', url: `/v1/sync?since=${since}`, headers: h(t) }).then(r => r.json());
  const journal = (id: string, body: string, baseVersion: number, extra: object = {}) => ({ id: uuidv7(), entity: 'journal', entityId: id, op: 'upsert', clientTs: Date.now(), patch: { goalId: null, localDate: '2026-10-14', mood: 4, body, baseVersion, ...extra } });

  it('streams the reply word by word, then the suggestion, then done', async () => {
    const a = await signUp();
    script = ['Sleep is your bottleneck. ', 'Lay out your kit tonight.', '\n```json\n{"suggestTask":{"title":"Lay out running kit","rationale":"Removes the first decision.","priority":"Low","timeOfDay":"9:00 PM"}}\n```'];
    const r = await ask(a.accessToken, 'Make tomorrow easier', { history: [{ role: 'coach', text: 'Morning, Sharon.' }] });
    expect(r.headers['content-type']).toContain('text/event-stream');
    const ev = events(r.body);
    expect(ev.filter(e => e.event === 'token').map(e => e.data.text).join('')).toBe('Sleep is your bottleneck. Lay out your kit tonight.\n');
    expect(ev.find(e => e.event === 'suggestion')!.data).toMatchObject({ title: 'Lay out running kit', priority: 'Low' });
    expect(ev.at(-1)!.event).toBe('done');
    expect(lastPrompt).toContain('Run a half marathon in under 2 hours');
    expect(lastPrompt).toContain('[x] Easy run 5 km');
    expect(lastPrompt).toContain('Coach: Morning, Sharon.');
  });

  it('answers self-harm messages with support and resources, without the model', async () => {
    const a = await signUp();
    script = ['should not be used'];
    const ev = events((await ask(a.accessToken, "honestly I want to die")).body);
    const text = ev.filter(e => e.event === 'token').map(e => e.data.text).join('');
    expect(text).toContain('findahelpline.com');
    expect(text).not.toContain('should not be used');
  });

  it('enforces the daily coach allowance before streaming', async () => {
    const a = await signUp();
    script = ['ok'];
    await sql`insert into ai_usage (user_id, day, kind, count) values (${a.user.id}, current_date, 'coach', 30)`;
    const r = await ask(a.accessToken, 'hello');
    expect([r.statusCode, r.json().error]).toEqual([429, 'ai_quota']);
    expect(r.json().resetAt).toBeTruthy();
  });

  it('reads the journal only when the user opted in', async () => {
    const a = await signUp();
    await push(a.accessToken, [journal(uuidv7(), 'Legs were heavy at km 9.', 0)]);
    script = ['ok'];
    await ask(a.accessToken, 'How am I doing?');
    expect(lastPrompt).not.toContain('Legs were heavy');
    await push(a.accessToken, [{ id: uuidv7(), entity: 'settings', entityId: a.user.id, op: 'upsert', clientTs: 1, patch: { coachReadsJournal: true } }]);
    await ask(a.accessToken, 'How am I doing?');
    expect(lastPrompt).toContain('Legs were heavy at km 9.');
  });

  it('renames the coach, falling back to Arc for empty or offensive names', async () => {
    const a = await signUp();
    const rename = (coachName: string) => app.inject({ method: 'PATCH', url: '/v1/me', headers: h(a.accessToken), payload: { coachName } }).then(r => r.json().user.coachName);
    expect(await rename('  Nova ')).toBe('Nova');
    expect(await rename('   ')).toBe('Arc');
    expect(await rename('shithead')).toBe('Arc');
  });

  it('stores journal bodies encrypted and returns them decrypted', async () => {
    const a = await signUp();
    const id = uuidv7();
    expect((await push(a.accessToken, [journal(id, 'Three weeks without missing a run.', 0)])).rejected).toEqual({});
    const [row] = await sql`select body_ciphertext from journal_entries where id = ${id}`;
    expect(Buffer.from(row!.body_ciphertext).toString('utf8')).not.toContain('Three weeks');
    const p = await pull(a.accessToken);
    expect(p.journal).toEqual([expect.objectContaining({ id, body: 'Three weeks without missing a run.', mood: 4, version: 1, conflictOf: null, localDate: '2026-10-14' })]);
  });

  it('keeps both versions when two devices edit the same day, until the user picks', async () => {
    const a = await signUp();
    const id = uuidv7();
    await push(a.accessToken, [journal(id, 'v1', 0)]);
    await push(a.accessToken, [journal(id, 'phone edit', 1)]);           // phone saw v1 → version 2
    await push(a.accessToken, [journal(id, 'tablet edit', 1)]);          // tablet also edited v1: conflict
    let p = await pull(a.accessToken);
    const current = p.journal.find((j: { conflictOf: string | null }) => !j.conflictOf);
    const copy = p.journal.find((j: { conflictOf: string | null }) => j.conflictOf);
    expect(current).toMatchObject({ id, body: 'tablet edit', version: 3 });
    expect(copy).toMatchObject({ conflictOf: id, body: 'phone edit' });
    await push(a.accessToken, [journal(id, 'phone edit, merged', 3, { resolve: true })]);
    p = await pull(a.accessToken, p.cursor);
    expect(p.removedJournal).toEqual([copy.id]);
    expect(p.journal).toEqual([expect.objectContaining({ id, body: 'phone edit, merged', version: 4 })]);
  });

  it('turns a second entry for the same day (made offline elsewhere) into a conflict copy', async () => {
    const a = await signUp();
    const first = uuidv7(), second = uuidv7();
    await push(a.accessToken, [journal(first, 'written on the phone', 0)]);
    expect((await push(a.accessToken, [journal(second, 'written on the tablet', 0)])).rejected).toEqual({});
    const p = await pull(a.accessToken);
    expect(p.journal.find((j: { id: string }) => j.id === second)).toMatchObject({ conflictOf: null, version: 2 });
    expect(p.journal.find((j: { id: string }) => j.id === first)).toMatchObject({ conflictOf: second, body: 'written on the phone' });
  });

  it('syncs the conversation to the account\'s other devices, encrypted at rest', async () => {
    const a = await signUp();
    const conversation = uuidv7(), messageId = uuidv7(), replyId = uuidv7();
    script = ['Lay out your kit tonight.', '\n```json\n{"suggestTask":{"title":"Lay out running kit","priority":"Low"}}\n```'];
    await ask(a.accessToken, 'Make tomorrow easier', { conversation, messageId, replyId, sentAt: 1_000 });
    // A second phone signs in and pulls from scratch
    const p = await pull(a.accessToken);
    expect(p.chat).toEqual([
      expect.objectContaining({ id: messageId, conversation, role: 'user', text: 'Make tomorrow easier', status: 'sent', createdAt: 1_000 }),
      expect.objectContaining({ id: replyId, conversation, role: 'coach', text: 'Lay out your kit tonight.\n', status: 'done', suggestionState: 'pending',
        suggestion: expect.objectContaining({ title: 'Lay out running kit' }) }),
    ]);
    const [row] = await sql`select body_ciphertext from coach_messages where id = ${replyId}`;
    expect(Buffer.from(row!.body_ciphertext).toString('utf8')).not.toContain('kit');
    // Accepting the suggestion on one phone shows as added on the other
    await push(a.accessToken, [{ id: uuidv7(), entity: 'chat', entityId: replyId, op: 'upsert', clientTs: 2, patch: { suggestionState: 'added' } }]);
    const p2 = await pull(a.accessToken, p.cursor);
    expect(p2.chat).toEqual([expect.objectContaining({ id: replyId, suggestionState: 'added' })]);
    // Deleting (retrying an interrupted reply) removes it everywhere
    await push(a.accessToken, [{ id: uuidv7(), entity: 'chat', entityId: replyId, op: 'delete', clientTs: 3, patch: {} }]);
    expect((await pull(a.accessToken, p2.cursor)).removedChat).toEqual([replyId]);
  });

  it('keeps a partial reply as interrupted, and never lets another user touch the conversation', async () => {
    const a = await signUp(), b = await signUp();
    const conversation = uuidv7(), messageId = uuidv7(), replyId = uuidv7();
    const { streamText } = await import('../src/ai/replicate.js');
    vi.mocked(streamText).mockImplementationOnce(async function* () { yield 'Half a tho'; throw new Error('connection reset'); });
    await ask(a.accessToken, 'Plan my week', { conversation, messageId, replyId });
    expect((await pull(a.accessToken)).chat.find((c: { id: string }) => c.id === replyId)).toMatchObject({ text: 'Half a tho', status: 'interrupted' });
    // b reuses a's ids: nothing of a's changes, and b's pull shows none of a's messages
    script = ['ok'];
    await ask(b.accessToken, 'Hijack', { conversation, messageId, replyId });
    expect(Object.values((await push(b.accessToken, [{ id: uuidv7(), entity: 'chat', entityId: replyId, op: 'delete', clientTs: 1, patch: {} }])).rejected)).toEqual(['forbidden']);
    expect((await pull(b.accessToken)).chat).toEqual([]);
    expect((await pull(a.accessToken)).chat.find((c: { id: string }) => c.id === messageId)).toMatchObject({ text: 'Plan my week' });
  });

  it('deletes the journal and the conversation at once when the account is deleted', async () => {
    const a = await signUp();
    await push(a.accessToken, [journal(uuidv7(), 'private', 0)]);
    script = ['ok'];
    await ask(a.accessToken, 'private question', { conversation: uuidv7(), messageId: uuidv7(), replyId: uuidv7() });
    await app.inject({ method: 'DELETE', url: '/v1/account', headers: h(a.accessToken) });
    expect((await sql`select 1 from journal_entries where user_id = ${a.user.id}`).length).toBe(0);
    expect((await sql`select 1 from coach_messages where user_id = ${a.user.id}`).length).toBe(0);
    expect((await sql`select journal_dek from users where id = ${a.user.id}`)[0]!.journal_dek).toBeNull();
  });
});
