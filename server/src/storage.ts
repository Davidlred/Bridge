import { mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { createHmac, timingSafeEqual } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { DeleteObjectsCommand, GetObjectCommand, ListObjectsV2Command, PutObjectCommand, S3Client } from '@aws-sdk/client-s3';
import { env } from './env.js';

/** Private object storage for photos and generated images. Keys always start with `u/<userId>/`. */
export interface Storage {
  put(key: string, body: Buffer, contentType: string): Promise<void>;
  get(key: string): Promise<Buffer | null>;
  deletePrefix(prefix: string): Promise<void>;
  delete(key: string): Promise<void>;
}

class LocalStorage implements Storage {
  constructor(private root: string) {}
  private path(key: string) {
    const p = resolve(this.root, key);
    if (!p.startsWith(resolve(this.root))) throw new Error('bad key');
    return p;
  }
  async put(key: string, body: Buffer) { const p = this.path(key); await mkdir(dirname(p), { recursive: true }); await writeFile(p, body); }
  async get(key: string) { try { return await readFile(this.path(key)); } catch { return null; } }
  async delete(key: string) { await rm(this.path(key), { force: true }); }
  async deletePrefix(prefix: string) { await rm(this.path(prefix), { recursive: true, force: true }); }
}

class S3Storage implements Storage {
  private s3 = new S3Client({ region: env.S3_REGION, endpoint: env.S3_ENDPOINT, forcePathStyle: !!env.S3_ENDPOINT });
  constructor(private bucket: string) {}
  async put(key: string, body: Buffer, contentType: string) { await this.s3.send(new PutObjectCommand({ Bucket: this.bucket, Key: key, Body: body, ContentType: contentType })); }
  async get(key: string) {
    try {
      const r = await this.s3.send(new GetObjectCommand({ Bucket: this.bucket, Key: key }));
      return r.Body ? Buffer.from(await r.Body.transformToByteArray()) : null;
    } catch (e) {
      if ((e as { name?: string }).name === 'NoSuchKey') return null;
      throw e;
    }
  }
  async delete(key: string) { await this.s3.send(new DeleteObjectsCommand({ Bucket: this.bucket, Delete: { Objects: [{ Key: key }] } })); }
  async deletePrefix(prefix: string) {
    for (;;) {
      const list = await this.s3.send(new ListObjectsV2Command({ Bucket: this.bucket, Prefix: prefix }));
      const keys = (list.Contents ?? []).flatMap(o => (o.Key ? [{ Key: o.Key }] : []));
      if (!keys.length) return;
      await this.s3.send(new DeleteObjectsCommand({ Bucket: this.bucket, Delete: { Objects: keys } }));
    }
  }
}

export let storage: Storage = env.S3_BUCKET ? new S3Storage(env.S3_BUCKET) : new LocalStorage(env.STORAGE_DIR);
/** Tests swap in a temp directory. */
export const setStorage = (s: Storage) => { storage = s; };
export const localStorage = (root: string): Storage => new LocalStorage(root);

export const userPrefix = (userId: string) => `u/${userId}/`;
export const photoKey = (userId: string) => `u/${userId}/photo.jpg`;
export const goalImageKey = (userId: string, goalId: string, kind: string) => `u/${userId}/goals/${goalId}/${kind}.jpg`;

// --- Signed media links: plain URLs that image loaders on both platforms can fetch, valid for an hour ---
const MEDIA_TTL_S = 60 * 60;
const secret = env.MEDIA_SIGNING_SECRET ?? createHmac('sha256', env.JWT_PRIVATE_KEY_PEM).update('media').digest('hex');
const sign = (key: string, exp: number) => createHmac('sha256', secret).update(`${key}\n${exp}`).digest('base64url');

/** `v` (e.g. the row's updated_at) busts client caches when an image is regenerated. */
export function signedUrl(key: string, v?: number | string, now = Date.now()): string {
  // Round the expiry so the URL is stable for a while and clients can cache it.
  const exp = Math.ceil(now / 1000 / 600) * 600 + MEDIA_TTL_S;
  const q = new URLSearchParams({ exp: String(exp), sig: sign(key, exp) });
  if (v !== undefined) q.set('v', String(v));
  return `${env.PUBLIC_URL}/v1/media/${key}?${q}`;
}

export function verifySignature(key: string, exp: string | undefined, sig: string | undefined, now = Date.now()): boolean {
  const e = Number(exp);
  if (!sig || !Number.isInteger(e) || e < now / 1000) return false;
  const want = Buffer.from(sign(key, e)), got = Buffer.from(sig);
  return want.length === got.length && timingSafeEqual(want, got);
}

