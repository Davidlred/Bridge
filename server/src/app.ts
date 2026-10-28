import Fastify from 'fastify';
import rateLimit from '@fastify/rate-limit';
import { env } from './env.js';
import { sql } from './db.js';
import { auth } from './auth/tokens.js';
import { authRoutes } from './auth/routes.js';
import { syncRoutes } from './sync/routes.js';
import { goalRoutes } from './goals/routes.js';
import { coachRoutes } from './coach/routes.js';
import websocket from '@fastify/websocket';
import { communityRoutes } from './social/community.js';
import { allianceRoutes } from './social/alliances.js';
import { chatRoutes } from './social/chat.js';
import { deviceRoutes } from './push.js';
import { gameRoutes } from './social/game.js';
import { rankingRoutes } from './social/rankings.js';
import { badgeRoutes } from './social/badges.js';
import { billingRoutes } from './billing.js';
import { exportRoutes } from './export.js';
import { dmRoutes } from './social/dm.js';
import { linkRoutes } from './links.js';
import { PlanRequest, StepsRequest } from './ai/planSchema.js';
import { generatePlan, generateSteps } from './ai/goalPlanner.js';
import { AIUnavailable } from './ai/replicate.js';
import { HttpError } from './util.js';
import { requestOrigin } from './storage.js';

const PLAN_TIMEOUT_MS = 75_000;

export async function buildApp() {
  const app = Fastify({
    logger: env.NODE_ENV === 'test' ? false : { level: env.NODE_ENV === 'production' ? 'info' : 'debug', redact: ['req.headers.authorization'] },
    genReqId: () => crypto.randomUUID(),
    trustProxy: true,   // behind the Fly proxy; rate limits key on the real client IP
  });

  // Registered before the route plugins so they inherit it.
  app.setErrorHandler((err, req, reply) => {
    const code = (err as { statusCode?: number }).statusCode ?? 500;
    if (code >= 500) req.log.error(err);
    const extra = err instanceof HttpError ? err.extra : {};
    reply.code(code).send({ error: code >= 500 && !(err instanceof HttpError) ? 'internal' : err.message, ...extra, requestId: req.id });
  });

  // Global limit keys by IP (runs before auth). Per-user AI quotas are enforced in Redis inside the AI routes.
  // (Injected WebSocket upgrades in tests have no socket, so there's no IP to read.)
  // Limits count per account once signed in (they run after the auth check), and per IP otherwise. Mobile carriers put
  // thousands of people behind one IP (very common across Africa and Asia), so per-IP limits on signed-in routes would
  // let strangers use up each other's daily plans.
  await app.register(rateLimit, {
    hook: 'preHandler', max: env.NODE_ENV === 'test' ? 10_000 : 120, timeWindow: '1 minute',
    keyGenerator: req => (req.userId ? `u:${req.userId}` : req.raw.socket ? req.ip : 'local'),
  });

  if (env.NODE_ENV === 'development') app.addHook('onRequest', (req, _reply, done) => { requestOrigin.run(`${req.protocol}://${req.headers.host}`, done); });
  app.addHook('onRequest', async (req, reply) => {
    const min = { ios: '1.0.0', android: '1.0.0' }[String(req.headers['x-platform'])];
    const v = String(req.headers['x-app-version'] ?? '');
    if (min && v && v.localeCompare(min, undefined, { numeric: true }) < 0) return reply.code(426).send({ error: 'upgrade_required', min });
  });

  // "service" lets development builds of the apps find this server on the Wi-Fi when the PC's address changes.
  app.get('/healthz', async () => { await sql`select 1`; return { ok: true, service: 'the-bridge' }; });

  await app.register(authRoutes);
  await app.register(syncRoutes);
  await app.register(goalRoutes);
  await app.register(coachRoutes);
  await app.register(websocket);
  await app.register(communityRoutes);
  await app.register(allianceRoutes);
  await app.register(chatRoutes);
  await app.register(deviceRoutes);
  await app.register(gameRoutes);
  await app.register(rankingRoutes);
  await app.register(badgeRoutes);
  await app.register(billingRoutes);
  await app.register(exportRoutes);
  await app.register(dmRoutes);
  await app.register(linkRoutes);

  app.post('/v1/ai/plan', { onRequest: auth, config: { rateLimit: { max: 5, timeWindow: '1 day' } } }, async (req, reply) => {
    const body = PlanRequest.safeParse(req.body);
    if (!body.success) return reply.code(400).send({ error: 'invalid_request', issues: body.error.issues });
    const ctrl = new AbortController();
    // The app gave up (closed the connection) before the plan was ready: stop the model. The request's own 'close'
    // event fires as soon as its body has been read, so listen on the response instead.
    reply.raw.on('close', () => { if (!reply.raw.writableFinished) ctrl.abort(); });
    try {
      // At most 75s in total (retries included), inside the apps' 90s wait, so the user gets "Try again" rather than a hang.
      const plan = await generatePlan(body.data, AbortSignal.any([ctrl.signal, AbortSignal.timeout(PLAN_TIMEOUT_MS)]));
      return { plan, deadline: body.data.deadline };
    } catch (e) {
      if (e instanceof AIUnavailable || (e as Error)?.name === 'TimeoutError') { req.log.warn({ err: (e as Error).message }, 'plan_unavailable'); return reply.code(503).send({ error: 'plan_unavailable' }); }
      throw e;
    }
  });

  // The next week of steps for a goal under way (the first week comes with the plan).
  app.post('/v1/ai/steps', { onRequest: auth, config: { rateLimit: { max: 10, timeWindow: '1 day' } } }, async (req, reply) => {
    const body = StepsRequest.safeParse(req.body);
    if (!body.success) return reply.code(400).send({ error: 'invalid_request', issues: body.error.issues });
    const ctrl = new AbortController();
    reply.raw.on('close', () => { if (!reply.raw.writableFinished) ctrl.abort(); });
    try {
      return { steps: await generateSteps(body.data, AbortSignal.any([ctrl.signal, AbortSignal.timeout(PLAN_TIMEOUT_MS)])) };
    } catch (e) {
      if (e instanceof AIUnavailable || (e as Error)?.name === 'TimeoutError') { req.log.warn({ err: (e as Error).message }, 'steps_unavailable'); return reply.code(503).send({ error: 'steps_unavailable' }); }
      throw e;
    }
  });

  return app;
}
