import { connect } from 'node:http2';
import { createPrivateKey } from 'node:crypto';
import { SignJWT, importPKCS8 } from 'jose';
import { z } from 'zod';
import type { FastifyInstance } from 'fastify';
import { env } from './env.js';
import { sql } from './db.js';
import { auth } from './auth/tokens.js';
import { HttpError } from './util.js';

export type Push = { title: string; body?: string; data?: Record<string, string>; collapseKey?: string };

const SOCIAL_DAILY_CAP = 3;

/** Tests capture pushes instead of sending them. */
let sink: ((userId: string, p: Push) => void) | null = null;
export const capturePushes = (fn: typeof sink) => { sink = fn; };

// --- FCM HTTP v1 (Android). Needs FCM_SERVICE_ACCOUNT_JSON. ---
let fcmToken: { value: string; exp: number } | null = null;
async function fcmAccessToken(): Promise<{ token: string; project: string } | null> {
  if (!env.FCM_SERVICE_ACCOUNT_JSON) return null;
  const sa = JSON.parse(env.FCM_SERVICE_ACCOUNT_JSON) as { client_email: string; private_key: string; project_id: string };
  if (!fcmToken || fcmToken.exp < Date.now() + 60_000) {
    const now = Math.floor(Date.now() / 1000);
    const assertion = await new SignJWT({ scope: 'https://www.googleapis.com/auth/firebase.messaging' }).setProtectedHeader({ alg: 'RS256' })
      .setIssuer(sa.client_email).setAudience('https://oauth2.googleapis.com/token').setIssuedAt(now).setExpirationTime(now + 3600).sign(await importPKCS8(sa.private_key, 'RS256'));
    const r = await fetch('https://oauth2.googleapis.com/token', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion }) });
    const j = (await r.json()) as { access_token: string; expires_in: number };
    fcmToken = { value: j.access_token, exp: Date.now() + j.expires_in * 1000 };
  }
  return { token: fcmToken.value, project: sa.project_id };
}

async function sendFcm(deviceToken: string, p: Push): Promise<boolean> {
  const a = await fcmAccessToken();
  if (!a) return false;
  const r = await fetch(`https://fcm.googleapis.com/v1/projects/${a.project}/messages:send`, {
    method: 'POST', headers: { Authorization: `Bearer ${a.token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ message: { token: deviceToken, notification: { title: p.title, body: p.body }, data: p.data, android: { collapse_key: p.collapseKey, priority: 'high', notification: { channel_id: 'updates_alert', default_vibrate_timings: false, vibrate_timings: ['0s', '0.6s', '0.25s', '0.6s', '0.25s', '0.6s'], notification_priority: 'PRIORITY_HIGH' } } } }),
  });
  return r.ok;
}

// --- APNs (iOS), token-based auth. Needs APNS_KEY_P8, APNS_KEY_ID, APNS_TEAM_ID, APNS_BUNDLE_ID. ---
let apnsJwt: { value: string; at: number } | null = null;
async function sendApns(deviceToken: string, p: Push): Promise<boolean> {
  if (!env.APNS_KEY_P8 || !env.APNS_KEY_ID || !env.APNS_TEAM_ID || !env.APNS_BUNDLE_ID) return false;
  if (!apnsJwt || Date.now() - apnsJwt.at > 50 * 60_000) {
    const key = createPrivateKey(env.APNS_KEY_P8.replace(/\\n/g, '\n'));
    apnsJwt = { value: await new SignJWT({}).setProtectedHeader({ alg: 'ES256', kid: env.APNS_KEY_ID }).setIssuer(env.APNS_TEAM_ID).setIssuedAt().sign(key), at: Date.now() };
  }
  const host = env.NODE_ENV === 'production' ? 'https://api.push.apple.com' : 'https://api.sandbox.push.apple.com';
  return new Promise(resolve => {
    const client = connect(host);
    client.on('error', () => resolve(false));
    const req = client.request({ ':method': 'POST', ':path': `/3/device/${deviceToken}`, authorization: `bearer ${apnsJwt!.value}`,
      'apns-topic': env.APNS_BUNDLE_ID!, 'apns-push-type': 'alert', ...(p.collapseKey ? { 'apns-collapse-id': p.collapseKey } : {}) });
    req.on('response', h => { resolve(h[':status'] === 200); client.close(); });
    req.on('error', () => { resolve(false); client.close(); });
    req.end(JSON.stringify({ aps: { alert: { title: p.title, body: p.body }, sound: 'default' }, ...p.data }));
  });
}

/**
 * Sends to every device of a user. Social pushes (mentions, nudges) are capped at 3 a day per user.
 * Without push credentials this does nothing (the event is still in the app).
 */
export async function push(userId: string, p: Push, kind: 'social' | 'system' = 'social'): Promise<void> {
  if (kind === 'social') {
    const [row] = await sql`insert into ai_usage (user_id, day, kind, count) values (${userId}, current_date, 'push_social', 1)
                            on conflict (user_id, day, kind) do update set count = ai_usage.count + 1 returning count`;
    if (row!.count > SOCIAL_DAILY_CAP) return;
  }
  if (sink) { sink(userId, p); return; }
  const devices = await sql`select platform, push_token from devices where user_id = ${userId} and push_token is not null`;
  for (const d of devices) {
    const ok = await (d.platform === 'ios' ? sendApns(d.push_token, p) : sendFcm(d.push_token, p)).catch(() => false);
    if (!ok && (env.FCM_SERVICE_ACCOUNT_JSON || env.APNS_KEY_P8)) await sql`update devices set push_token = null where push_token = ${d.push_token}`.catch(() => {});
  }
}

export async function deviceRoutes(app: FastifyInstance) {
  // Push token, platform, locale and time zone for this install. Called after sign-in and whenever the token changes.
  app.put('/v1/devices', { onRequest: auth }, async (req, reply) => {
    const b = z.object({ id: z.string().uuid(), platform: z.enum(['ios', 'android']), pushToken: z.string().min(10).max(4096).nullable(),
      appVersion: z.string().max(20).optional(), locale: z.string().max(20).optional(), timezone: z.string().max(64).optional() }).safeParse(req.body);
    if (!b.success) throw new HttpError(400, 'invalid_request');
    const d = b.data;
    const [other] = await sql`select user_id from devices where id = ${d.id}`;
    if (other && other.user_id !== req.userId) await sql`delete from devices where id = ${d.id}`;   // the install changed hands
    await sql`insert into devices (id, user_id, platform, push_token, app_version, locale, timezone, last_seen_at, updated_at)
              values (${d.id}, ${req.userId}, ${d.platform}, ${d.pushToken}, ${d.appVersion ?? null}, ${d.locale ?? null}, ${d.timezone ?? null}, now(), now())
              on conflict (id) do update set push_token = excluded.push_token, app_version = excluded.app_version, locale = excluded.locale,
                timezone = excluded.timezone, last_seen_at = now(), updated_at = now()`;
    if (d.timezone) await sql`update users set home_timezone = ${d.timezone} where id = ${req.userId}`;
    return reply.code(204).send();
  });
}
