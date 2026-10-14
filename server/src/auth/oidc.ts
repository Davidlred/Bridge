import { createRemoteJWKSet, jwtVerify } from 'jose';
import { env } from '../env.js';
import { HttpError, sha256 } from '../util.js';

const appleKeys = createRemoteJWKSet(new URL('https://appleid.apple.com/auth/keys'));
const googleKeys = createRemoteJWKSet(new URL('https://www.googleapis.com/oauth2/v3/certs'));

export type Identity = { provider: 'apple' | 'google'; subject: string; email: string | null; emailVerified: boolean; name: string | null };

/** Verifies a native ID token against the provider's JWKS. `nonce` is the raw value the client generated. */
export async function verifyIdToken(provider: 'apple' | 'google', idToken: string, nonce?: string): Promise<Identity> {
  const aud = provider === 'apple' ? env.APPLE_CLIENT_IDS : env.GOOGLE_CLIENT_IDS;
  if (!aud.length) throw new HttpError(503, 'provider_not_configured');
  const { payload } = await jwtVerify(idToken, provider === 'apple' ? appleKeys : googleKeys, {
    issuer: provider === 'apple' ? 'https://appleid.apple.com' : ['https://accounts.google.com', 'accounts.google.com'],
    audience: aud,
  }).catch(() => { throw new HttpError(401, 'invalid_id_token'); });
  // iOS sends Apple the SHA-256 of the nonce; Google echoes it as given.
  if (nonce && payload.nonce !== nonce && payload.nonce !== sha256(nonce).toString('hex')) throw new HttpError(401, 'invalid_id_token');
  const verified = payload.email_verified === true || payload.email_verified === 'true';
  return {
    provider,
    subject: String(payload.sub),
    email: typeof payload.email === 'string' ? payload.email.toLowerCase() : null,   // Apple relay addresses are used as-is
    emailVerified: verified,
    name: typeof payload.given_name === 'string' ? payload.given_name : null,
  };
}
