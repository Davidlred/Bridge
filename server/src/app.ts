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
import { PlanRequest } from './ai/planSchema.js';
import { generatePlan } from './ai/goalPlanner.js';
import { AIUnavailable } from './ai/replicate.js';
import { HttpError } from './util.js';

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
  await app.register(rateLimit, { max: env.NODE_ENV === 'test' ? 10_000 : 120, timeWindow: '1 minute', keyGenerator: req => (req.raw.socket ? req.ip : 'local') });

  app.addHook('onRequest', async (req, reply) => {
    const min = { ios: '1.0.0', android: '1.0.0' }[String(req.headers['x-platform'])];
    const v = String(req.headers['x-app-version'] ?? '');
    if (min && v && v.localeCompare(min, undefined, { numeric: true }) < 0) return reply.code(426).send({ error: 'upgrade_required', min });
  });

  app.get('/healthz', async () => { await sql`select 1`; return { ok: true }; });

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

  app.post('/v1/ai/plan', { onRequest: auth, config: { rateLimit: { max: 5, timeWindow: '1 day' } } }, async (req, reply) => {
    const body = PlanRequest.safeParse(req.body);
    if (!body.success) return reply.code(400).send({ error: 'invalid_request', issues: body.error.issues });
    const ctrl = new AbortController();
    req.raw.on('close', () => { if (!reply.sent) ctrl.abort(); });
    try {
      const plan = await generatePlan(body.data, ctrl.signal);
      return { plan, deadline: body.data.deadline };
    } catch (e) {
      if (e instanceof AIUnavailable) { req.log.warn({ err: e.message }, 'plan_unavailable'); return reply.code(503).send({ error: 'plan_unavailable' }); }
      throw e;
    }
  });

  return app;
}
