import { createPrivateKey, createPublicKey, timingSafeEqual } from 'node:crypto';
import type { FastifyRequest } from 'fastify';
import { SignJWT, jwtVerify } from 'jose';
import { env } from '../env.js';
import { sql, type Tx } from '../db.js';
import { HttpError, randomToken, sha256, uuidv7 } from '../util.js';

const priv = createPrivateKey(env.JWT_PRIVATE_KEY_PEM.replace(/\\n/g, '\n'));
const pub = createPublicKey(priv);

const ACCESS_TTL_S = 15 * 60;
const REFRESH_TTL_DAYS = 60;

export type Tokens = { accessToken: string; refreshToken: string; expiresIn: number };

const signAccess = (userId: string, sid: string) =>
  new SignJWT({ sid }).setProtectedHeader({ alg: 'EdDSA' }).setSubject(userId).setIssuer(env.JWT_ISSUER).setIssuedAt().setExpirationTime(`${ACCESS_TTL_S}s`).sign(priv);

/** Opens a session row and returns an access JWT + opaque refresh token (`<sessionId>.<secret>`, only the hash is stored). */
export async function issueSession(db: Tx | typeof sql, userId: string, family: string = uuidv7(), deviceId: string | null = null): Promise<Tokens> {
  const sid = uuidv7();
  const secret = randomToken();
  await db`insert into sessions (id, user_id, family, refresh_hash, device_id, expires_at)
           values (${sid}, ${userId}, ${family}, ${sha256(secret)}, ${deviceId}, now() + make_interval(days => ${REFRESH_TTL_DAYS}))`;
  return { accessToken: await signAccess(userId, sid), refreshToken: `${sid}.${secret}`, expiresIn: ACCESS_TTL_S };
}

/** Rotates a refresh token. Presenting an already-rotated token revokes the whole family (theft detection). */
export async function rotateSession(refreshToken: string): Promise<Tokens> {
  const [sid, secret] = refreshToken.split('.');
  if (!sid || !secret || !/^[0-9a-f-]{36}$/.test(sid)) throw new HttpError(401, 'invalid_refresh');
  const tokens = await sql.begin(async tx => {
    const [s] = await tx`select s.user_id, s.family, s.refresh_hash, s.device_id, s.revoked_at, s.expires_at < now() as expired, u.deleted_at
                         from sessions s join users u on u.id = s.user_id where s.id = ${sid} for update of s`;
    if (!s || !timingSafeEqual(s.refresh_hash, sha256(secret))) return null;
    if (s.revoked_at) { await tx`update sessions set revoked_at = now() where family = ${s.family} and revoked_at is null`; return null; }
    if (s.expired || s.deleted_at) return null;
    await tx`update sessions set revoked_at = now() where id = ${sid}`;
    return issueSession(tx, s.user_id, s.family, s.device_id);
  });
  if (!tokens) throw new HttpError(401, 'invalid_refresh');
  return tokens;
}

export const revokeAllSessions = (db: Tx | typeof sql, userId: string) =>
  db`update sessions set revoked_at = now() where user_id = ${userId} and revoked_at is null`;

declare module 'fastify' { interface FastifyRequest { userId: string; sessionId: string } }

/** onRequest hook: verifies the access token and that its session is still live (log out / delete take effect at once). */
export async function auth(req: FastifyRequest) {
  const h = req.headers.authorization;
  if (!h?.startsWith('Bearer ')) throw new HttpError(401, 'unauthorized');
  const { payload } = await jwtVerify(h.slice(7), pub, { issuer: env.JWT_ISSUER, algorithms: ['EdDSA'] }).catch(() => { throw new HttpError(401, 'token_expired'); });
  const [live] = await sql`select 1 from sessions where id = ${String(payload.sid)} and user_id = ${String(payload.sub)} and revoked_at is null`;
  if (!live) throw new HttpError(401, 'token_expired');
  req.userId = String(payload.sub);
  req.sessionId = String(payload.sid);
}
