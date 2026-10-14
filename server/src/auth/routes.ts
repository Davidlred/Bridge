import type { FastifyInstance } from 'fastify';
import argon2 from 'argon2';
import { z } from 'zod';
import { env } from '../env.js';
import { sql, type Tx } from '../db.js';
import { sendMail } from '../mailer.js';
import { HttpError, randomToken, sha256, uuidv7 } from '../util.js';
import { auth, issueSession, revokeAllSessions, rotateSession } from './tokens.js';
import { verifyIdToken, type Identity } from './oidc.js';
import { signedUrl, storage, userPrefix } from '../storage.js';
import { deletePhotoAndFaceImages } from '../goals/routes.js';

const ARGON = { type: argon2.argon2id, memoryCost: 64 * 1024, timeCost: 3 } as const;
const MAX_FAILED_LOGINS = 5;
const LOCK_MINUTES = 15;

const Email = z.string().trim().toLowerCase().email().max(254);
const Password = z.string().min(8).max(128);
const Name = z.string().trim().min(2).max(40);
const Timezone = z.string().min(2).max(64).default('UTC');
const DeviceId = z.string().uuid().optional();

const SignUp = z.object({ email: Email, password: Password, displayName: Name, timezone: Timezone, deviceId: DeviceId });
const LogIn = z.object({ email: Email, password: z.string().min(1).max(128), deviceId: DeviceId });
const Social = z.object({ idToken: z.string().min(20).max(4096), nonce: z.string().max(128).optional(), displayName: Name.optional(), timezone: Timezone, deviceId: DeviceId });

const parse = <T extends z.ZodTypeAny>(schema: T, body: unknown): z.infer<T> => {
  const r = schema.safeParse(body);
  if (!r.success) throw new HttpError(400, 'invalid_request', { issues: r.error.issues.map(i => ({ path: i.path.join('.'), code: i.code })) });
  return r.data;
};

const userJson = (u: Record<string, unknown>) => ({
  id: u.id, email: u.email, emailVerified: u.email_verified, displayName: u.display_name,
  coachName: u.coach_name, homeTimezone: u.home_timezone, settings: u.settings, createdAt: (u.created_at as Date).getTime(),
  photoUrl: u.photo_key && u.photo_consent_at ? signedUrl(u.photo_key as string, (u.photo_consent_at as Date).getTime()) : null,
});
const loadUser = async (db: Tx | typeof sql, id: string) => {
  const [u] = await db`select id, email, email_verified, display_name, coach_name, home_timezone, settings, created_at, photo_key, photo_consent_at from users where id = ${id} and deleted_at is null`;
  if (!u) throw new HttpError(401, 'unauthorized');
  return userJson(u);
};

/** Which sign-in method already owns this email (for "This email is linked to Google sign-in. Continue with Google?"). */
const emailOwner = async (db: Tx | typeof sql, email: string) => {
  const [row] = await db`select u.id, (select provider from auth_identities i where i.user_id = u.id order by created_at limit 1) as provider from users u where u.email = ${email}`;
  return row ? { id: row.id as string, provider: (row.provider as string | null) ?? 'email' } : null;
};

async function emailLink(db: Tx | typeof sql, userId: string, kind: 'reset' | 'verify', minutes: number): Promise<string> {
  const token = randomToken();
  await db`insert into email_tokens (token_hash, user_id, kind, expires_at) values (${sha256(token)}, ${userId}, ${kind}, now() + make_interval(mins => ${minutes}))`;
  return `${env.PUBLIC_URL}/${kind}?token=${token}`;
}

/** Finds or creates the account behind a verified Apple/Google identity. Never links by email silently. */
async function accountFor(tx: Tx, id: Identity, displayName: string | undefined, timezone: string): Promise<{ id: string; created: boolean }> {
  const [known] = await tx`select u.id from auth_identities i join users u on u.id = i.user_id where i.provider = ${id.provider} and i.subject = ${id.subject} and u.deleted_at is null`;
  if (known) return { id: known.id as string, created: false };
  if (id.email) {
    const owner = await emailOwner(tx, id.email);
    if (owner) throw new HttpError(409, 'email_linked', { provider: owner.provider });
  }
  const userId = uuidv7();
  const name = (displayName ?? id.name ?? id.email?.split('@')[0] ?? 'Friend').slice(0, 40).padEnd(2, ' ').trim() || 'Friend';
  await tx`insert into users (id, email, email_verified, display_name, home_timezone) values (${userId}, ${id.email}, ${id.emailVerified}, ${name}, ${timezone})`;
  await tx`insert into auth_identities (provider, subject, user_id) values (${id.provider}, ${id.subject}, ${userId})`;
  return { id: userId, created: true };
}

const page = (title: string, body: string) => `<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${title} · The Bridge</title>
<style>body{margin:0;background:#FBFAF7;color:#23262B;font:400 16px/1.5 system-ui,sans-serif}main{max-width:380px;margin:12vh auto;padding:0 24px}h1{font-size:28px;line-height:1.1;margin:0 0 8px}p{color:#7A7D83;margin:0 0 20px}
input{box-sizing:border-box;width:100%;height:52px;border-radius:14px;border:1px solid #E3E0D8;padding:0 16px;font:inherit;margin-bottom:14px}button{width:100%;height:54px;border-radius:16px;border:0;background:#2F3A33;color:#fff;font:600 16px system-ui;cursor:pointer}
@media(prefers-color-scheme:dark){body{background:#0C0C0C;color:#F2F2F2}p{color:#8C8C8C}input{background:#151515;color:#F2F2F2;border-color:rgba(255,255,255,.12)}button{background:#E8643A;color:#1A0C06}}</style><main><h1>${title}</h1>${body}</main></html>`;

export async function authRoutes(app: FastifyInstance) {
  const strict = { config: { rateLimit: { max: 20, timeWindow: '1 minute' } } };

  app.post('/v1/auth/email/signup', strict, async req => {
    const b = parse(SignUp, req.body);
    const hash = await argon2.hash(b.password, ARGON);
    return sql.begin(async tx => {
      const owner = await emailOwner(tx, b.email);
      if (owner) throw new HttpError(409, owner.provider === 'email' ? 'email_taken' : 'email_linked', { provider: owner.provider });
      const id = uuidv7();
      await tx`insert into users (id, email, password_hash, display_name, home_timezone) values (${id}, ${b.email}, ${hash}, ${b.displayName}, ${b.timezone})`;
      await tx`insert into auth_identities (provider, subject, user_id) values ('email', ${b.email}, ${id})`;
      const link = await emailLink(tx, id, 'verify', 24 * 60);
      sendMail({ to: b.email, subject: 'Confirm your email for The Bridge', text: `Confirm your email:\n${link}\n\nThis link works for 24 hours.` }).catch(e => req.log.error({ err: e.message }, 'mail_failed'));
      return { user: await loadUser(tx, id), isNew: true, ...(await issueSession(tx, id, undefined, b.deviceId ?? null)) };
    });
  });

  app.post('/v1/auth/email/login', strict, async req => {
    const b = parse(LogIn, req.body);
    const [u] = await sql`select id, password_hash, locked_until, greatest(0, extract(epoch from locked_until - now()))::int as wait from users where email = ${b.email} and deleted_at is null`;
    if (u && u.wait > 0) throw new HttpError(429, 'too_many_attempts', { retryAfterSec: u.wait });
    if (u && !u.password_hash) {
      const owner = await emailOwner(sql, b.email);
      throw new HttpError(409, 'email_linked', { provider: owner?.provider ?? 'email' });
    }
    // Hash even for unknown emails so the response time doesn't reveal which emails exist.
    const ok = u ? await argon2.verify(u.password_hash, b.password) : (await argon2.hash(b.password, ARGON), false);
    if (!ok) {
      if (u) {
        const [r] = await sql`update users set failed_logins = case when failed_logins + 1 >= ${MAX_FAILED_LOGINS} then 0 else failed_logins + 1 end,
                                locked_until = case when failed_logins + 1 >= ${MAX_FAILED_LOGINS} then now() + make_interval(mins => ${LOCK_MINUTES}) else locked_until end
                              where id = ${u.id} returning locked_until > now() as locked`;
        if (r?.locked) throw new HttpError(429, 'too_many_attempts', { retryAfterSec: LOCK_MINUTES * 60 });
      }
      throw new HttpError(401, 'invalid_credentials');
    }
    await sql`update users set failed_logins = 0, locked_until = null where id = ${u!.id}`;
    return { user: await loadUser(sql, u!.id), isNew: false, ...(await issueSession(sql, u!.id, undefined, b.deviceId ?? null)) };
  });

  for (const provider of ['apple', 'google'] as const) {
    app.post(`/v1/auth/${provider}`, strict, async req => {
      const b = parse(Social, req.body);
      const identity = await verifyIdToken(provider, b.idToken, b.nonce);
      return sql.begin(async tx => {
        // isNew sends a first-time user to goal onboarding instead of an empty Today.
        const { id, created } = await accountFor(tx, identity, b.displayName, b.timezone);
        return { user: await loadUser(tx, id), isNew: created, ...(await issueSession(tx, id, undefined, b.deviceId ?? null)) };
      });
    });
  }

  app.post('/v1/auth/refresh', strict, async req => rotateSession(parse(z.object({ refreshToken: z.string().min(40).max(200) }), req.body).refreshToken));

  app.post('/v1/auth/logout', { onRequest: auth }, async (req, reply) => {
    await sql`update sessions set revoked_at = now() where family = (select family from sessions where id = ${req.sessionId}) and revoked_at is null`;
    return reply.code(204).send();
  });

  app.get('/v1/me', { onRequest: auth }, async req => ({ user: await loadUser(sql, req.userId) }));

  // Display name and coach name. An empty or offensive coach name falls back to "Arc".
  app.patch('/v1/me', { onRequest: auth }, async req => {
    const b = parse(z.object({ displayName: Name.optional(), coachName: z.string().max(40).optional() }).strict(), req.body);
    if (b.displayName) await sql`update users set display_name = ${b.displayName} where id = ${req.userId}`;
    if (b.coachName !== undefined) await sql`update users set coach_name = ${coachName(b.coachName)} where id = ${req.userId}`;
    return { user: await loadUser(sql, req.userId) };
  });

  // Soft-delete now (signed out everywhere, sign-in methods released, content hidden); hard-delete after 30 days by purgeDeletedAccounts().
  app.delete('/v1/account', { onRequest: auth }, async (req, reply) => {
    // The photo, every image made from it and the journal go now, not after 30 days.
    await deletePhotoAndFaceImages(req.userId);
    await sql`delete from journal_entries where user_id = ${req.userId}`;
    await sql`delete from coach_messages where user_id = ${req.userId}`;
    await sql`update users set journal_dek = null where id = ${req.userId}`;
    await sql`delete from goal_images where user_id = ${req.userId}`;
    await storage.deletePrefix(userPrefix(req.userId)).catch(e => req.log.error({ err: e.message }, 'storage_delete_failed'));
    await sql.begin(async tx => {
      await tx`update users set deleted_at = now(), email = null, password_hash = null where id = ${req.userId}`;
      await tx`delete from auth_identities where user_id = ${req.userId}`;
      await tx`delete from devices where user_id = ${req.userId}`;
      await tx`delete from email_tokens where user_id = ${req.userId}`;
      await revokeAllSessions(tx, req.userId);
    });
    return reply.code(204).send();
  });

  // --- Password reset + email verification (links open small pages served here, so they work on any device) ---
  app.post('/v1/auth/password/forgot', { config: { rateLimit: { max: 5, timeWindow: '1 hour' } } }, async (req, reply) => {
    const { email } = parse(z.object({ email: Email }), req.body);
    const [u] = await sql`select id from users where email = ${email} and password_hash is not null and deleted_at is null`;
    if (u) {
      const [recent] = await sql`select count(*)::int as n from email_tokens where user_id = ${u.id} and kind = 'reset' and expires_at > now()`;
      if (recent!.n < 3) {
        const link = await emailLink(sql, u.id, 'reset', 15);
        sendMail({ to: email, subject: 'Reset your Bridge password', text: `Reset your password:\n${link}\n\nThis link works once, for 15 minutes. If you didn't ask for it, ignore this email.` }).catch(e => req.log.error({ err: e.message }, 'mail_failed'));
      }
    }
    return reply.code(204).send();   // same answer whether or not the email exists
  });

  const useToken = (tx: Tx, token: string, kind: 'reset' | 'verify') =>
    tx`update email_tokens set used_at = now() where token_hash = ${sha256(token)} and kind = ${kind} and used_at is null and expires_at > now() returning user_id`;

  app.post('/v1/auth/password/reset', strict, async (req, reply) => {
    const b = parse(z.object({ token: z.string().min(20).max(128), password: Password }), req.body);
    const hash = await argon2.hash(b.password, ARGON);
    const ok = await sql.begin(async tx => {
      const [t] = await useToken(tx, b.token, 'reset');
      if (!t) return false;
      await tx`update users set password_hash = ${hash}, failed_logins = 0, locked_until = null, email_verified = true where id = ${t.user_id}`;
      await revokeAllSessions(tx, t.user_id);
      return true;
    });
    if (!ok) throw new HttpError(400, 'invalid_token');
    return reply.code(204).send();
  });

  app.get<{ Querystring: { token?: string } }>('/reset', async (req, reply) => reply.type('text/html').send(page('Choose a new password',
    `<p>At least 8 characters. You'll be signed out on your other devices.</p><form id="f"><input id="p" type="password" minlength="8" maxlength="128" autocomplete="new-password" placeholder="New password" required><button>Save password</button></form><p id="m" role="status" style="margin-top:16px"></p>
<script>f.onsubmit=async e=>{e.preventDefault();const r=await fetch('/v1/auth/password/reset',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({token:${JSON.stringify(String(req.query.token ?? '')).replace(/</g, '\\u003c')},password:p.value})});m.textContent=r.ok?'Password saved. Open The Bridge and log in.':'This link has expired or was already used. Ask for a new one in the app.';if(r.ok)f.remove()}</script>`)));

  app.get<{ Querystring: { token?: string } }>('/verify', async (req, reply) => {
    const ok = await sql.begin(async tx => {
      const [t] = await useToken(tx, String(req.query.token ?? ''), 'verify');
      if (t) await tx`update users set email_verified = true where id = ${t.user_id}`;
      return !!t;
    });
    return reply.code(ok ? 200 : 400).type('text/html').send(ok ? page('Email confirmed', '<p>You can go back to The Bridge.</p>') : page('Link expired', '<p>This link has expired or was already used.</p>'));
  });
}

// A short list is enough for a coach nickname; anything flagged simply becomes "Arc".
const BLOCKED = /\b(fuck|shit|bitch|cunt|nigg|fag|retard|whore|slut|nazi|hitler|rape|porn|sex|dick|cock|pussy)/i;
export function coachName(raw: string): string {
  const name = raw.replace(/[\u0000-\u001f]/g, '').trim().slice(0, 20);
  return name.length < 1 || BLOCKED.test(name) ? 'Arc' : name;
}

/** Worker job: hard-delete accounts 30 days after the user deleted them. */
export async function purgeDeletedAccounts(): Promise<number> {
  const gone = await sql`delete from users where deleted_at < now() - interval '30 days' returning id`;
  await sql`delete from processed_mutations where processed_at < now() - interval '7 days'`;
  await sql`delete from email_tokens where expires_at < now() - interval '7 days'`;
  await sql`delete from sessions where expires_at < now() - interval '7 days'`;
  return gone.length;
}
