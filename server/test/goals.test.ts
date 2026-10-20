import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import type { FastifyInstance } from 'fastify';
import { uuidv7 } from '../src/util.js';

// The AI provider is replaced: images come back as tiny JPEG data URIs, text models answer with fixed JSON.
const calls: { model: string; input: Record<string, unknown> }[] = [];
let failImages = false;
vi.mock('../src/ai/replicate.js', async orig => {
  const real = await orig<typeof import('../src/ai/replicate.js')>();
  return {
    ...real,
    runImage: vi.fn(async (model: string, input: Record<string, unknown>) => {
      calls.push({ model, input });
      if (failImages) throw new real.AIUnavailable('boom');
      return `data:image/jpeg;base64,${JPEG.toString('base64')}`;
    }),
    runText: vi.fn(async (_model: string, input: Record<string, unknown>, signal?: AbortSignal) => {
      // Like the real client: a cancelled request stops the model.
      await new Promise(r => setTimeout(r, 50));
      if (signal?.aborted) throw new DOMException('aborted', 'AbortError');
      const system = String(input.system_prompt);
      if (system.includes('Reality Check')) return '{"verdict":"stretch","reason":"Possible with four runs a week.","suggestedDeadline":"2020-01-01"}';
      return JSON.stringify({ needsClarification: null, label: 'Half marathon', goal: 'Run a half marathon in under 2 hours', feasibility: { verdict: 'realistic', reason: 'Plenty of time.', suggestedDeadline: null },
        tasks: [{ title: 'Easy run 5 km', priority: 'High', timeOfDay: '07:00', minutes: 35, rationale: 'Builds the aerobic base.' }, { title: 'Mobility 10 min', priority: 'Medium', timeOfDay: 'After run', minutes: 10, rationale: 'Protects your hips.' }] });
    }),
  };
});

const JPEG = Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), Buffer.alloc(2048, 7)]);
const run = describe.skipIf(!process.env.TEST_DATABASE_URL);

run('goals core', () => {
  let app: FastifyInstance;
  let sql: typeof import('../src/db.js').sql;
  const dir = mkdtempSync(join(tmpdir(), 'bridge-media-'));

  beforeAll(async () => {
    ({ sql } = await import('../src/db.js'));
    await sql.unsafe('drop schema public cascade; create schema public;');
    await (await import('../src/migrate.js')).migrate();
    (await import('../src/mailer.js')).setMailer(async () => {});
    const st = await import('../src/storage.js');
    st.setStorage(st.localStorage(dir));
    app = await (await import('../src/app.js')).buildApp();
  });
  afterAll(async () => { await app?.close(); await sql?.end(); rmSync(dir, { recursive: true, force: true }); });

  const signUp = async () => {
    const r = await app.inject({ method: 'POST', url: '/v1/auth/email/signup', payload: { email: `${uuidv7()}@example.com`, password: 'correct horse', displayName: 'Sharon' } });
    return r.json() as { user: { id: string }; accessToken: string };
  };
  const h = (t: string, extra: Record<string, string> = {}) => ({ authorization: `Bearer ${t}`, ...extra });
  const uploadPhoto = (t: string, consent = true) =>
    app.inject({ method: 'PUT', url: '/v1/me/photo', payload: JPEG, headers: h(t, { 'content-type': 'image/jpeg', ...(consent ? { 'x-photo-consent': '1' } : {}) }) });
  const startImages = (t: string, goalId: string, kinds?: string[]) =>
    app.inject({ method: 'POST', url: '/v1/ai/images', headers: h(t), payload: { goalId, goalText: 'Run a half marathon in under 2 hours', routine: 'Up at 7:30, desk job 9 to 6, asleep by 1.', ...(kinds ? { kinds } : {}) } });
  const images = async (t: string, goalId: string) => {
    for (let i = 0; i < 50; i++) {
      const r = (await app.inject({ method: 'GET', url: `/v1/ai/images/${goalId}`, headers: h(t) })).json() as { images: { kind: string; status: string; url: string | null; withFace: boolean }[] };
      if (!r.images.some(x => x.status === 'running')) return r.images;
      await new Promise(res => setTimeout(res, 20));
    }
    throw new Error('images still running');
  };
  const fetchMedia = (url: string) => app.inject({ method: 'GET', url: url.replace(/^https?:\/\/[^/]+/, '') });

  it('needs explicit consent before storing a photo', async () => {
    const a = await signUp();
    expect((await uploadPhoto(a.accessToken, false)).json().error).toBe('consent_required');
    const bad = await app.inject({ method: 'PUT', url: '/v1/me/photo', payload: Buffer.alloc(4096, 1), headers: h(a.accessToken, { 'content-type': 'image/jpeg', 'x-photo-consent': '1' }) });
    expect(bad.json().error).toBe('invalid_image');
    const ok = await uploadPhoto(a.accessToken);
    expect(ok.statusCode).toBe(200);
    const me = (await app.inject({ method: 'GET', url: '/v1/me', headers: h(a.accessToken) })).json();
    expect(me.user.photoUrl).toMatch(/\/v1\/media\/u\/.+\/photo\.jpg\?exp=/);
    const media = await fetchMedia(me.user.photoUrl);
    expect(media.statusCode).toBe(200);
    expect(media.rawPayload.equals(JPEG)).toBe(true);
  });

  it('serves media only with a valid signature', async () => {
    const a = await signUp();
    const { photoUrl } = (await uploadPhoto(a.accessToken)).json();
    expect((await fetchMedia(photoUrl.replace(/sig=[^&]+/, 'sig=forged'))).statusCode).toBe(404);
    expect((await fetchMedia(photoUrl.replace(/exp=\d+/, 'exp=1'))).statusCode).toBe(404);
    expect((await fetchMedia(photoUrl.replace('/photo.jpg', '/../photo.jpg'))).statusCode).toBe(404);
  });

  it('makes face images from the photo, and conceptual ones without it', async () => {
    const withPhoto = await signUp(), without = await signUp();
    await uploadPhoto(withPhoto.accessToken);
    const g1 = uuidv7(), g2 = uuidv7();
    calls.length = 0;
    expect((await startImages(withPhoto.accessToken, g1)).statusCode).toBe(200);
    expect((await startImages(without.accessToken, g2)).statusCode).toBe(200);
    const a = await images(withPhoto.accessToken, g1), b = await images(without.accessToken, g2);
    expect(a.map(x => [x.kind, x.status, x.withFace])).toEqual([['future', 'done', true], ['reality', 'done', true]]);
    expect(b.map(x => [x.kind, x.status, x.withFace])).toEqual([['future', 'done', false], ['reality', 'done', false]]);
    const faceCalls = calls.filter(c => c.input.input_image), plain = calls.filter(c => !c.input.input_image);
    expect(faceCalls).toHaveLength(2);
    expect(faceCalls.every(c => c.model === 'black-forest-labs/flux-kontext-pro')).toBe(true);
    expect(plain.every(c => String(c.input.prompt).includes('face is not visible'))).toBe(true);
    expect((await fetchMedia(a[0]!.url!)).statusCode).toBe(200);
    // Another user can't read or regenerate them
    expect((await app.inject({ method: 'GET', url: `/v1/ai/images/${g1}`, headers: h(without.accessToken) })).json().images.every((x: { status: string }) => x.status === 'none')).toBe(true);
    expect((await startImages(without.accessToken, g1)).statusCode).toBe(403);
  });

  it('retries only the failed image and enforces the daily image allowance', async () => {
    const a = await signUp();
    const g = uuidv7();
    failImages = true;
    await startImages(a.accessToken, g);
    expect((await images(a.accessToken, g)).map(x => x.status)).toEqual(['failed', 'failed']);
    failImages = false;
    calls.length = 0;
    await startImages(a.accessToken, g, ['reality']);
    expect((await images(a.accessToken, g)).map(x => x.status)).toEqual(['failed', 'done']);
    expect(calls).toHaveLength(1);
    await startImages(a.accessToken, g);                       // only "future" is redone; "reality" is already done
    expect(calls).toHaveLength(2);
    // 2 + 1 + 1 used; 4 more are allowed today, the next one is refused
    for (let i = 0; i < 2; i++) await startImages(a.accessToken, uuidv7());
    const over = await startImages(a.accessToken, uuidv7());
    expect([over.statusCode, over.json().error]).toEqual([429, 'ai_quota']);
  });

  it('removing the photo deletes it and every image made from it', async () => {
    const a = await signUp();
    const { photoUrl } = (await uploadPhoto(a.accessToken)).json();
    const g = uuidv7();
    await startImages(a.accessToken, g);
    const before = await images(a.accessToken, g);
    expect((await app.inject({ method: 'DELETE', url: '/v1/me/photo', headers: h(a.accessToken) })).statusCode).toBe(204);
    expect((await fetchMedia(photoUrl)).statusCode).toBe(404);
    expect((await fetchMedia(before[0]!.url!)).statusCode).toBe(404);
    expect((await images(a.accessToken, g)).map(x => x.status)).toEqual(['none', 'none']);
    expect((await app.inject({ method: 'GET', url: '/v1/me', headers: h(a.accessToken) })).json().user.photoUrl).toBeNull();
  });

  it('deleting the account removes stored files at once', async () => {
    const a = await signUp();
    const { photoUrl } = (await uploadPhoto(a.accessToken)).json();
    const g = uuidv7();
    await startImages(a.accessToken, g);
    const imgs = await images(a.accessToken, g);
    expect((await app.inject({ method: 'DELETE', url: '/v1/account', headers: h(a.accessToken) })).statusCode).toBe(204);
    for (const u of [photoUrl, imgs[0]!.url!, imgs[1]!.url!]) expect((await fetchMedia(u)).statusCode).toBe(404);
  });

  it('deleting a goal through sync removes its images', async () => {
    const a = await signUp();
    const g = uuidv7();
    const push = (mutations: object[]) => app.inject({ method: 'POST', url: '/v1/sync/push', headers: h(a.accessToken), payload: { mutations } });
    await push([{ id: uuidv7(), entity: 'goal', entityId: g, op: 'upsert', clientTs: Date.now(), patch: { label: 'Half marathon', statement: 'Run it', startDate: '2026-10-14', deadline: '2027-02-09' } }]);
    await startImages(a.accessToken, g);
    const imgs = await images(a.accessToken, g);
    await push([{ id: uuidv7(), entity: 'goal', entityId: g, op: 'delete', clientTs: Date.now(), patch: {} }]);
    expect((await fetchMedia(imgs[0]!.url!)).statusCode).toBe(404);
    expect((await images(a.accessToken, g)).map(x => x.status)).toEqual(['none', 'none']);
  });

  it('syncs routine, "why I started" and the feasibility verdict on goals', async () => {
    const a = await signUp();
    const g = uuidv7();
    const feasibility = { verdict: 'stretch', reason: 'Tight but doable.', suggestedDeadline: '2027-03-01' };
    const r = await app.inject({ method: 'POST', url: '/v1/sync/push', headers: h(a.accessToken), payload: { mutations: [
      { id: uuidv7(), entity: 'goal', entityId: g, op: 'upsert', clientTs: 1, patch: { label: 'Half marathon', statement: 'Run it', startDate: '2026-10-14', deadline: '2027-02-09', routine: 'Up at 7:30', feasibility } },
      { id: uuidv7(), entity: 'goal', entityId: g, op: 'upsert', clientTs: 2, patch: { realityNote: "Because I'm done starting over every January." } },
    ] } });
    expect(r.json().rejected).toEqual({});
    const pulled = (await app.inject({ method: 'GET', url: '/v1/sync?since=0', headers: h(a.accessToken) })).json();
    expect(pulled.goals[0]).toMatchObject({ routine: 'Up at 7:30', realityNote: "Because I'm done starting over every January.", feasibility, deadline: '2027-02-09' });
  });

  it('keeps planning while the app waits on a real connection', async () => {
    // Over a real socket the request's 'close' event fires once its body is read; that must not cancel the model.
    const a = await signUp();
    const base = await app.listen({ port: 0, host: '127.0.0.1' });
    const r = await fetch(`${base}/v1/ai/plan`, { method: 'POST', headers: { ...h(a.accessToken), 'content-type': 'application/json' }, body: JSON.stringify({
      goalText: 'Run a half marathon in under 2 hours', deadline: '2027-02-09', today: '2026-10-14', timezone: 'Africa/Lagos', routine: 'Up at 7:30, desk job 9 to 6.' }) });
    expect(r.status).toBe(200);
    expect((await r.json()).plan.label).toBe('Half marathon');
  });

  it('plans from a free-text routine and gives a Reality Check without touching the deadline', async () => {
    const a = await signUp();
    const plan = await app.inject({ method: 'POST', url: '/v1/ai/plan', headers: h(a.accessToken), payload: {
      goalText: 'Run a half marathon in under 2 hours', deadline: '2027-02-09', today: '2026-10-14', timezone: 'Africa/Lagos',
      routine: "Up at 7:30, phone in bed until 8. Desk job 9 to 6. Asleep by 1." } });
    expect(plan.statusCode).toBe(200);
    expect(plan.json()).toMatchObject({ deadline: '2027-02-09', plan: { label: 'Half marathon' } });
    const rc = await app.inject({ method: 'POST', url: '/v1/ai/reality', headers: h(a.accessToken), payload: { goalText: 'Run a half marathon', deadline: '2027-02-09', today: '2026-10-14', routine: 'Up at 7:30' } });
    // A suggested date in the past is dropped rather than shown
    expect(rc.json()).toEqual({ feasibility: { verdict: 'stretch', reason: 'Possible with four runs a week.', suggestedDeadline: null } });
    expect((await app.inject({ method: 'POST', url: '/v1/ai/reality', headers: h(a.accessToken), payload: { goalText: 'Run', deadline: '2026-10-01', today: '2026-10-14' } })).statusCode).toBe(400);
  });
});
