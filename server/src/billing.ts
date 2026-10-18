import type { FastifyInstance } from 'fastify';
import { timingSafeEqual } from 'node:crypto';
import { z } from 'zod';
import { env } from './env.js';
import { sql } from './db.js';
import { auth } from './auth/tokens.js';
import { HttpError } from './util.js';

/**
 * Entitlements come from RevenueCat webhooks; the app's SDK cache is only for instant UI.
 * RevenueCat's app_user_id is our user id (the apps call `logIn(userId)` after sign-in).
 * Entitlement identifiers in the RevenueCat dashboard must be `plus` and `alliance_plus`.
 */
const KNOWN = new Set(['plus', 'alliance_plus']);
/** Events that grant or extend access until expiration_at_ms. */
const GRANT = new Set(['INITIAL_PURCHASE', 'RENEWAL', 'PRODUCT_CHANGE', 'UNCANCELLATION', 'NON_RENEWING_PURCHASE', 'SUBSCRIPTION_EXTENDED', 'TEMPORARY_ENTITLEMENT_GRANT']);
/** A cancellation keeps access until the period ends; an expiration ends it now. Billing issues keep the grace period RevenueCat reports. */
const END = new Set(['EXPIRATION']);

const Event = z.object({
  event: z.object({
    id: z.string().min(1),
    type: z.string(),
    app_user_id: z.string(),
    transferred_to: z.array(z.string()).optional(),
    transferred_from: z.array(z.string()).optional(),
    entitlement_ids: z.array(z.string()).nullable().optional(),
    entitlement_id: z.string().nullable().optional(),
    expiration_at_ms: z.number().nullable().optional(),
    grace_period_expiration_at_ms: z.number().nullable().optional(),
    event_timestamp_ms: z.number().optional(),
    store: z.string().optional(),
    cancel_reason: z.string().nullable().optional(),
  }).passthrough(),
});

const uuid = z.string().uuid();
const safeEqual = (a: string, b: string) => { const x = Buffer.from(a), y = Buffer.from(b); return x.length === y.length && timingSafeEqual(x, y); };

async function setEntitlement(userId: string, entitlement: string, expiresAt: Date | null, store: string | null, at: Date) {
  const [u] = await sql`select 1 from users where id = ${userId}`;
  if (!u) return;
  // Events can arrive out of order: an older event never overwrites a newer one.
  await sql`insert into entitlements (user_id, entitlement, expires_at, store, updated_at) values (${userId}, ${entitlement}, ${expiresAt}, ${store}, ${at})
            on conflict (user_id, entitlement) do update set expires_at = excluded.expires_at, store = excluded.store, updated_at = excluded.updated_at
            where entitlements.updated_at <= excluded.updated_at`;
}

export async function billingRoutes(app: FastifyInstance) {
  app.post('/v1/webhooks/revenuecat', { config: { rateLimit: { max: 600, timeWindow: '1 minute' } } }, async (req, reply) => {
    const secret = env.REVENUECAT_WEBHOOK_SECRET;
    const given = String(req.headers.authorization ?? '').replace(/^Bearer\s+/i, '');
    if (!secret || !safeEqual(given, secret)) throw new HttpError(401, 'unauthorized');
    const p = Event.safeParse(req.body);
    if (!p.success) throw new HttpError(400, 'invalid_request');
    const e = p.data.event;
    // RevenueCat retries until it gets a 200; each event is applied once.
    const [fresh] = await sql`insert into billing_events (id, type) values (${e.id}, ${e.type}) on conflict do nothing returning 1`;
    if (!fresh) return reply.send({ ok: true, duplicate: true });
    const at = new Date(e.event_timestamp_ms ?? Date.now());
    const entitlements = [...(e.entitlement_ids ?? []), ...(e.entitlement_id ? [e.entitlement_id] : [])].filter(x => KNOWN.has(x));

    if (e.type === 'TRANSFER') {
      // Purchases moved to another account (restore on a new login): the old user loses them, the new one gains them.
      for (const from of (e.transferred_from ?? []).filter(x => uuid.safeParse(x).success)) await sql`delete from entitlements where user_id = ${from}`;
      return reply.send({ ok: true });
    }
    if (!uuid.safeParse(e.app_user_id).success) return reply.send({ ok: true, ignored: 'anonymous_user' });
    const grace = e.grace_period_expiration_at_ms ?? null;
    for (const ent of entitlements) {
      // A refund arrives as a cancellation with reason CUSTOMER_SUPPORT: access ends now, not at the period end.
      const refund = e.type === 'CANCELLATION' && e.cancel_reason === 'CUSTOMER_SUPPORT';
      if (refund) {
        await setEntitlement(e.app_user_id, ent, at, e.store ?? null, at);
      } else if (GRANT.has(e.type) || e.type === 'CANCELLATION' || e.type === 'BILLING_ISSUE') {
        const until = Math.max(e.expiration_at_ms ?? 0, grace ?? 0);
        await setEntitlement(e.app_user_id, ent, until ? new Date(until) : null, e.store ?? null, at);
      } else if (END.has(e.type)) {
        await setEntitlement(e.app_user_id, ent, at, e.store ?? null, at);
      }
    }
    return reply.send({ ok: true });
  });

  /** What the server will honour. The app shows its SDK cache first and reconciles with this. */
  app.get('/v1/me/entitlements', { onRequest: auth }, async req => {
    const rows = await sql`select entitlement, expires_at from entitlements where user_id = ${req.userId} and (expires_at is null or expires_at > now())`;
    return { entitlements: rows.map(r => ({ id: r.entitlement, expiresAt: r.expires_at ? (r.expires_at as Date).getTime() : null })) };
  });
}
