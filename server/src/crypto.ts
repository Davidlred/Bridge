import { createCipheriv, createDecipheriv, createHash, randomBytes } from 'node:crypto';
import { env } from './env.js';
import { sql, type Tx } from './db.js';

/**
 * Journal bodies are encrypted at rest with a per-user data key (DEK). The DEK is stored wrapped with the
 * server key (KEK, env JOURNAL_KEK). Format of every sealed value: iv(12) | tag(16) | ciphertext.
 */
const kek = env.JOURNAL_KEK ? Buffer.from(env.JOURNAL_KEK, 'base64') : createHash('sha256').update(env.JWT_PRIVATE_KEY_PEM).update('journal-kek').digest();
if (kek.length !== 32) throw new Error('JOURNAL_KEK must be 32 bytes, base64');

export function seal(key: Buffer, plain: Buffer): Buffer {
  const iv = randomBytes(12);
  const c = createCipheriv('aes-256-gcm', key, iv);
  const ct = Buffer.concat([c.update(plain), c.final()]);
  return Buffer.concat([iv, c.getAuthTag(), ct]);
}

export function open(key: Buffer, sealed: Buffer): Buffer {
  const d = createDecipheriv('aes-256-gcm', key, sealed.subarray(0, 12));
  d.setAuthTag(sealed.subarray(12, 28));
  return Buffer.concat([d.update(sealed.subarray(28)), d.final()]);
}

/** The user's journal key, created on first use. */
export async function journalKey(db: Tx | typeof sql, userId: string): Promise<Buffer> {
  const [u] = await db`select journal_dek from users where id = ${userId} for update`;
  if (u?.journal_dek) return open(kek, u.journal_dek as Buffer);
  const dek = randomBytes(32);
  await db`update users set journal_dek = ${seal(kek, dek)} where id = ${userId}`;
  return dek;
}

export const encryptText = (key: Buffer, text: string) => seal(key, Buffer.from(text, 'utf8'));
export const decryptText = (key: Buffer, sealed: Buffer | null) => (sealed ? open(key, sealed).toString('utf8') : '');
