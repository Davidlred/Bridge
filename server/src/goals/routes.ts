import type { FastifyBaseLogger, FastifyInstance } from 'fastify';
import { z } from 'zod';
import { env } from '../env.js';
import { sql } from '../db.js';
import { auth } from '../auth/tokens.js';
import { HttpError } from '../util.js';
import { goalImageKey, photoKey, signedUrl, storage, userPrefix, verifySignature } from '../storage.js';
import { AIUnavailable, runImage } from '../ai/replicate.js';
import { assessReality } from '../ai/goalPlanner.js';
import { RealityRequest } from '../ai/planSchema.js';

const MAX_PHOTO_BYTES = 6 * 1024 * 1024;
const MAX_IMAGE_BYTES = 12 * 1024 * 1024;
const IMAGE_DAILY_LIMIT = 8;       // 2 images per goal, a few retries
const REALITY_DAILY_LIMIT = 20;
const STALE_MS = 5 * 60 * 1000;    // a "running" image older than this was lost (e.g. a deploy) and counts as failed

type Kind = 'future' | 'reality';
const KINDS: Kind[] = ['future', 'reality'];

/** Counts one use of a daily AI allowance; throws 429 ai_quota when it is used up. */
export async function takeQuota(userId: string, kind: string, limit: number, n = 1) {
  const [row] = await sql`
    insert into ai_usage (user_id, day, kind, count) values (${userId}, current_date, ${kind}, ${n})
    on conflict (user_id, day, kind) do update set count = ai_usage.count + ${n}
    returning count`;
  if (row!.count > limit) {
    await sql`update ai_usage set count = count - ${n} where user_id = ${userId} and day = current_date and kind = ${kind}`;
    throw new HttpError(429, 'ai_quota', { resetAt: new Date(new Date().setUTCHours(24, 0, 0, 0)).toISOString() });
  }
}

// Prompts never ask the model to mock, exaggerate or sexualise the person. "If nothing changes" is honest, not cruel.
const STYLE = 'Photorealistic candid photograph, natural light, warm muted palette, shallow depth of field. No text, no logos, no watermarks.';
function prompt(kind: Kind, goal: string, routine: string, withFace: boolean): string {
  if (withFace) {
    return kind === 'future'
      ? `Keep this exact person: same face, same identity, same skin tone and features. Show them in an everyday scene a short while after achieving this goal: "${goal}". They look healthy, focused and quietly proud. ${STYLE}`
      : `Keep this exact person: same face, same identity, same skin tone and features. Show them in an ordinary moment a year from now if their routine stays exactly the same: "${routine.slice(0, 600)}". Honest and neutral, slightly tired, the goal "${goal}" still not reached. Respectful: no mockery, no exaggerated body changes. ${STYLE}`;
  }
  // No photo (or no consent): a scene that suggests the person without showing a face.
  return kind === 'future'
    ? `The moment this goal is achieved: "${goal}". Show the person doing or having just done the thing itself, with clear visual proof of the achievement in the scene (for example crossing the finish line, holding the certificate, on stage). Seen from behind or at a distance so their face is not visible. ${STYLE}`
    : `An ordinary weekday a year from now for someone whose routine has not changed: "${routine.slice(0, 600)}". The goal "${goal}" has not happened. Show the person in that routine, seen from behind or at a distance so their face is not visible. Honest and neutral, not bleak. ${STYLE}`;
}

async function download(url: string): Promise<Buffer> {
  if (url.startsWith('data:')) return Buffer.from(url.slice(url.indexOf(',') + 1), 'base64');
  const r = await fetch(url);
  if (!r.ok || !String(r.headers.get('content-type')).startsWith('image/')) throw new AIUnavailable(`image_download_${r.status}`);
  const body = Buffer.from(await r.arrayBuffer());
  if (body.length > MAX_IMAGE_BYTES) throw new AIUnavailable('image_too_large');
  return body;
}

/** Generates one image and records the outcome. Runs after the request returns; the client polls. */
async function generate(log: FastifyBaseLogger, userId: string, goalId: string, kind: Kind, goal: string, routine: string, withFace: boolean) {
  try {
    const input: Record<string, unknown> = { prompt: prompt(kind, goal, routine, withFace), aspect_ratio: '3:4', output_format: 'jpg' };
    let model = env.MODEL_IMAGE;
    if (withFace) {
      const photo = await storage.get(photoKey(userId));
      if (!photo) throw new AIUnavailable('photo_missing');
      input.input_image = `data:image/jpeg;base64,${photo.toString('base64')}`;
      model = env.MODEL_IMAGE_EDIT;
    }
    const bytes = await download(await runImage(model, input));
    await storage.put(goalImageKey(userId, goalId, kind), bytes, 'image/jpeg');
    await sql`update goal_images set status = 'done', storage_key = ${goalImageKey(userId, goalId, kind)}, error = null, updated_at = now()
              where goal_id = ${goalId} and kind = ${kind} and user_id = ${userId}`;
  } catch (e) {
    log.warn({ err: e instanceof Error ? e.message : String(e), kind }, 'goal_image_failed');
    await sql`update goal_images set status = 'failed', error = ${e instanceof Error ? e.message.slice(0, 200) : 'failed'}, updated_at = now()
              where goal_id = ${goalId} and kind = ${kind} and user_id = ${userId}`.catch(() => {});
  }
}

async function imagesFor(userId: string, goalId: string) {
  const rows = await sql`select kind, status, storage_key, with_face, updated_at from goal_images where goal_id = ${goalId} and user_id = ${userId}`;
  return KINDS.map(kind => {
    const r = rows.find(x => x.kind === kind);
    if (!r) return { kind, status: 'none' as const, url: null, withFace: false };
    const stale = r.status === 'running' && Date.now() - (r.updated_at as Date).getTime() > STALE_MS;
    return {
      kind, status: stale ? 'failed' : (r.status as string), withFace: r.with_face as boolean,
      url: r.status === 'done' && r.storage_key ? signedUrl(r.storage_key, (r.updated_at as Date).getTime()) : null,
    };
  });
}

/** Removes a goal's images (called when the goal is deleted). */
export async function deleteGoalImages(userId: string, goalId: string) {
  await sql`delete from goal_images where goal_id = ${goalId} and user_id = ${userId}`;
  await storage.deletePrefix(`${userPrefix(userId)}goals/${goalId}/`).catch(() => {});
}

/** Removes the photo and everything generated from it. Used by "Remove photo" and account deletion. */
export async function deletePhotoAndFaceImages(userId: string) {
  const faces = await sql`delete from goal_images where user_id = ${userId} and with_face returning storage_key`;
  await sql`update users set photo_key = null, photo_consent_at = null where id = ${userId}`;
  await storage.delete(photoKey(userId)).catch(() => {});
  for (const f of faces) if (f.storage_key) await storage.delete(f.storage_key).catch(() => {});
}

export async function goalRoutes(app: FastifyInstance) {
  app.addContentTypeParser(['image/jpeg', 'image/png'], { parseAs: 'buffer', bodyLimit: MAX_PHOTO_BYTES }, (_req, body, done) => done(null, body));

  // Signed, short-lived links so plain image loaders can fetch private files.
  app.get<{ Params: { '*': string }; Querystring: { exp?: string; sig?: string } }>('/v1/media/*', async (req, reply) => {
    const key = req.params['*'];
    if (!/^u\/[0-9a-f-]{36}\/[\w./-]+$/.test(key) || key.includes('..') || !verifySignature(key, req.query.exp, req.query.sig)) throw new HttpError(404, 'not_found');
    const body = await storage.get(key);
    if (!body) throw new HttpError(404, 'not_found');
    return reply.header('Cache-Control', 'private, max-age=3600').type(key.endsWith('.png') ? 'image/png' : 'image/jpeg').send(body);
  });

  // Upload (or replace) the profile photo. The client shows the consent text and sends X-Photo-Consent: 1.
  app.put('/v1/me/photo', { onRequest: auth, config: { rateLimit: { max: 10, timeWindow: '1 hour' } } }, async req => {
    if (req.headers['x-photo-consent'] !== '1') throw new HttpError(400, 'consent_required');
    const body = req.body;
    if (!Buffer.isBuffer(body) || body.length < 1024) throw new HttpError(400, 'invalid_image');
    const jpeg = body[0] === 0xff && body[1] === 0xd8, png = body.subarray(0, 4).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47]));
    if (!jpeg && !png) throw new HttpError(400, 'invalid_image');
    await storage.put(photoKey(req.userId), body, jpeg ? 'image/jpeg' : 'image/png');
    const [u] = await sql`update users set photo_key = ${photoKey(req.userId)}, photo_consent_at = now() where id = ${req.userId} returning photo_consent_at`;
    return { photoUrl: signedUrl(photoKey(req.userId), (u!.photo_consent_at as Date).getTime()) };
  });

  app.delete('/v1/me/photo', { onRequest: auth }, async (req, reply) => {
    await deletePhotoAndFaceImages(req.userId);
    return reply.code(204).send();
  });

  const ImagesBody = z.object({
    goalId: z.string().uuid(),
    goalText: z.string().trim().min(3).max(500),
    routine: z.string().trim().max(1000).default(''),
    kinds: z.array(z.enum(['future', 'reality'])).min(1).max(2).default(['future', 'reality']),
  });

  // Starts (or retries) image generation for a goal. Returns at once; poll GET /v1/ai/images/:goalId.
  app.post('/v1/ai/images', { onRequest: auth, config: { rateLimit: { max: 20, timeWindow: '1 hour' } } }, async req => {
    const parsed = ImagesBody.safeParse(req.body);
    if (!parsed.success) throw new HttpError(400, 'invalid_request');
    const b = parsed.data;
    const [owner] = await sql`select user_id from goal_images where goal_id = ${b.goalId} and user_id <> ${req.userId} limit 1`;
    if (owner) throw new HttpError(403, 'forbidden');
    const current = await imagesFor(req.userId, b.goalId);
    const todo = b.kinds.filter(k => !['running', 'done'].includes(current.find(c => c.kind === k)!.status));
    if (todo.length) {
      await takeQuota(req.userId, 'image', IMAGE_DAILY_LIMIT, todo.length);
      const [u] = await sql`select photo_key is not null and photo_consent_at is not null as face from users where id = ${req.userId}`;
      const withFace = !!u?.face;
      for (const kind of todo) {
        await sql`insert into goal_images (goal_id, user_id, kind, status, with_face) values (${b.goalId}, ${req.userId}, ${kind}, 'running', ${withFace})
                  on conflict (goal_id, kind) do update set status = 'running', with_face = ${withFace}, error = null, updated_at = now()`;
        void generate(req.log, req.userId, b.goalId, kind, b.goalText, b.routine, withFace);
      }
    }
    return { images: await imagesFor(req.userId, b.goalId) };
  });

  app.get<{ Params: { goalId: string } }>('/v1/ai/images/:goalId', { onRequest: auth }, async req => {
    if (!z.string().uuid().safeParse(req.params.goalId).success) throw new HttpError(400, 'invalid_request');
    return { images: await imagesFor(req.userId, req.params.goalId) };
  });

  // Reality Check verdict for an existing goal (e.g. after the deadline changed). Never changes the deadline.
  app.post('/v1/ai/reality', { onRequest: auth, config: { rateLimit: { max: 30, timeWindow: '1 hour' } } }, async (req, reply) => {
    const parsed = RealityRequest.safeParse(req.body);
    if (!parsed.success) throw new HttpError(400, 'invalid_request');
    await takeQuota(req.userId, 'reality', REALITY_DAILY_LIMIT);
    try {
      return { feasibility: await assessReality(parsed.data) };
    } catch (e) {
      if (e instanceof AIUnavailable) { req.log.warn({ err: e.message }, 'reality_unavailable'); return reply.code(503).send({ error: 'reality_unavailable' }); }
      throw e;
    }
  });
}
