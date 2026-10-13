import Fastify from 'fastify';
import rateLimit from '@fastify/rate-limit';
import { env } from './env.js';
import { sql } from './db.js';
import { auth } from './auth/tokens.js';
import { authRoutes } from './auth/routes.js';
import { syncRoutes } from './sync/routes.js';
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
  await app.register(rateLimit, { max: env.NODE_ENV === 'test' ? 10_000 : 120, timeWindow: '1 minute' });

  app.addHook('onRequest', async (req, reply) => {
    const min = { ios: '1.0.0', android: '1.0.0' }[String(req.headers['x-platform'])];
    const v = String(req.headers['x-app-version'] ?? '');
    if (min && v && v.localeCompare(min, undefined, { numeric: true }) < 0) return reply.code(426).send({ error: 'upgrade_required', min });
  });

  app.get('/healthz', async () => { await sql`select 1`; return { ok: true }; });

  await app.register(authRoutes);
  await app.register(syncRoutes);

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

  // Invite preview: what a person sees before joining (shared-plan pace included)
  app.get<{ Params: { code: string } }>('/v1/alliances/preview/:code', { onRequest: auth }, async (req, reply) => {
    const [a] = await sql`
      select a.id, a.name, a.plan_mode, a.cap, a.start_date, g.deadline, g.statement as goal, u.display_name as lead,
             (select count(*)::int from alliance_members m where m.alliance_id = a.id and m.status <> 'left') as members,
             exists(select 1 from alliance_members m where m.alliance_id = a.id and m.user_id = ${req.userId} and m.status <> 'left') as already
      from alliances a join goals g on g.id = a.lead_goal_id join users u on u.id = a.owner_id
      where a.invite_code = ${req.params.code.toUpperCase()} and a.archived_at is null`;
    if (!a) return reply.code(404).send({ error: 'invalid_code' });
    if (a.already) return reply.code(409).send({ error: 'already_member', name: a.name });
    if (a.members >= a.cap) return reply.code(409).send({ error: 'alliance_full', cap: a.cap });
    const tasks = a.plan_mode === 'shared' ? await sql`
      select title, priority, time_of_day from shared_plan_tasks
      where alliance_id = ${a.id} and version = (select max(version) from shared_plan_tasks where alliance_id = ${a.id}) and deleted_at is null
      order by position` : [];
    return { ...a, tasks };
  });

  return app;
}
