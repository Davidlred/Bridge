import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { sql } from '../db.js';
import { auth } from '../auth/tokens.js';
import { HttpError, uuidv7 } from '../util.js';
import { bus } from '../realtime.js';
import { push } from '../push.js';
import { firstName } from './progress.js';

/**
 * End-to-end encrypted direct messages (Olm via vodozemac on the devices). The server is a relay: it stores each
 * device's public identity keys and one-time keys, and ciphertext addressed to a device until that device takes it.
 * It can't read messages, so it can't moderate them; reports carry messages the reporter's device decrypted.
 *
 * v1: you can message people you share an active alliance with, unless either of you blocked the other.
 */
const MAX_ONE_TIME_KEYS = 100;
const ENVELOPE_DAYS = 30;
const b64 = z.string().min(20).max(200).regex(/^[A-Za-z0-9+/=_-]+$/);

export async function canMessage(a: string, b: string): Promise<boolean> {
  if (a === b) return false;
  const [r] = await sql`
    select exists(select 1 from alliance_members x join alliance_members y on y.alliance_id = x.alliance_id join alliances al on al.id = x.alliance_id
                  where x.user_id = ${a} and y.user_id = ${b} and x.status <> 'left' and y.status <> 'left' and al.archived_at is null) as shared,
           exists(select 1 from blocks where (blocker_id = ${a} and blocked_id = ${b}) or (blocker_id = ${b} and blocked_id = ${a})) as blocked,
           exists(select 1 from users where id = ${b} and deleted_at is null) as alive`;
  return r!.shared && !r!.blocked && r!.alive;
}

async function ownDevice(userId: string, deviceId: string) {
  if (!z.string().uuid().safeParse(deviceId).success) throw new HttpError(404, 'not_found');
  const [d] = await sql`select 1 from e2e_devices where device_id = ${deviceId} and user_id = ${userId}`;
  if (!d) throw new HttpError(404, 'device_unknown');
}

export async function dmRoutes(app: FastifyInstance) {
  /** Register (or re-register) this install's identity keys. New keys on the same device drop its old one-time keys. */
  app.put<{ Params: { deviceId: string } }>('/v1/e2e/devices/:deviceId', { onRequest: auth }, async (req, reply) => {
    const b = z.object({ curve25519: b64, ed25519: b64 }).safeParse(req.body);
    if (!b.success || !z.string().uuid().safeParse(req.params.deviceId).success) throw new HttpError(400, 'invalid_request');
    const [other] = await sql`select user_id, curve25519, ed25519 from e2e_devices where device_id = ${req.params.deviceId}`;
    if (other && other.user_id !== req.userId) throw new HttpError(409, 'device_taken');
    if (other && (other.curve25519 !== b.data.curve25519 || other.ed25519 !== b.data.ed25519)) {
      await sql`delete from e2e_one_time_keys where device_id = ${req.params.deviceId}`;
    }
    await sql`insert into e2e_devices (device_id, user_id, curve25519, ed25519) values (${req.params.deviceId}, ${req.userId}, ${b.data.curve25519}, ${b.data.ed25519})
              on conflict (device_id) do update set curve25519 = excluded.curve25519, ed25519 = excluded.ed25519, updated_at = now()`;
    return reply.code(204).send();
  });

  /** Publish one-time keys (and optionally a new fallback key). Returns how many unused ones the server holds. */
  app.post<{ Params: { deviceId: string } }>('/v1/e2e/devices/:deviceId/keys', { onRequest: auth }, async req => {
    await ownDevice(req.userId, req.params.deviceId);
    const Key = z.object({ id: z.string().min(1).max(64), key: b64 });
    const b = z.object({ oneTimeKeys: z.array(Key).max(MAX_ONE_TIME_KEYS).default([]), fallback: Key.optional() }).safeParse(req.body);
    if (!b.success) throw new HttpError(400, 'invalid_request');
    for (const k of b.data.oneTimeKeys) {
      await sql`insert into e2e_one_time_keys (device_id, key_id, curve25519) values (${req.params.deviceId}, ${k.id}, ${k.key}) on conflict do nothing`;
    }
    if (b.data.fallback) {
      await sql`delete from e2e_one_time_keys where device_id = ${req.params.deviceId} and fallback`;
      await sql`insert into e2e_one_time_keys (device_id, key_id, curve25519, fallback) values (${req.params.deviceId}, ${b.data.fallback.id}, ${b.data.fallback.key}, true) on conflict do nothing`;
    }
    const [n] = await sql`select count(*)::int as n from e2e_one_time_keys where device_id = ${req.params.deviceId} and not fallback`;
    return { oneTimeKeys: n!.n };
  });

  /** People you can message: everyone you share an active alliance with (blocked people excluded). */
  app.get('/v1/e2e/contacts', { onRequest: auth }, async req => {
    const rows = await sql`
      select distinct u.id, u.display_name, exists(select 1 from e2e_devices d where d.user_id = u.id) as ready
      from alliance_members x join alliance_members y on y.alliance_id = x.alliance_id join alliances al on al.id = x.alliance_id join users u on u.id = y.user_id
      where x.user_id = ${req.userId} and y.user_id <> ${req.userId} and x.status <> 'left' and y.status <> 'left' and al.archived_at is null and u.deleted_at is null
        and not exists (select 1 from blocks b where (b.blocker_id = ${req.userId} and b.blocked_id = u.id) or (b.blocker_id = u.id and b.blocked_id = ${req.userId}))
      order by u.display_name`;
    return { contacts: rows.map(r => ({ userId: r.id, name: firstName(r.display_name), ready: r.ready })) };
  });

  /** A person's devices (public identity keys), to start sessions with. Your own other devices come from ?self=1. */
  app.get<{ Params: { userId: string } }>('/v1/e2e/users/:userId/devices', { onRequest: auth }, async req => {
    const self = req.params.userId === req.userId;
    if (!self && !(await canMessage(req.userId, req.params.userId))) throw new HttpError(403, 'cannot_message');
    const rows = await sql`select device_id, curve25519, ed25519 from e2e_devices where user_id = ${req.params.userId} order by created_at`;
    return { devices: rows.map(r => ({ deviceId: r.device_id, curve25519: r.curve25519, ed25519: r.ed25519 })) };
  });

  /** Take one one-time key per device (the fallback key when none are left). Each one-time key is handed out once. */
  app.post('/v1/e2e/claim', { onRequest: auth, config: { rateLimit: { max: 60, timeWindow: '1 minute' } } }, async req => {
    const b = z.object({ deviceIds: z.array(z.string().uuid()).min(1).max(20) }).safeParse(req.body);
    if (!b.success) throw new HttpError(400, 'invalid_request');
    const out = [] as { deviceId: string; keyId: string; key: string; fallback: boolean }[];
    for (const id of b.data.deviceIds) {
      const [d] = await sql`select user_id from e2e_devices where device_id = ${id}`;
      if (!d || (d.user_id !== req.userId && !(await canMessage(req.userId, d.user_id)))) continue;
      const [k] = await sql`delete from e2e_one_time_keys where (device_id, key_id) = (
                              select device_id, key_id from e2e_one_time_keys where device_id = ${id} and not fallback limit 1 for update skip locked)
                            returning key_id, curve25519`;
      if (k) { out.push({ deviceId: id, keyId: k.key_id, key: k.curve25519, fallback: false }); continue; }
      const [f] = await sql`select key_id, curve25519 from e2e_one_time_keys where device_id = ${id} and fallback`;
      if (f) out.push({ deviceId: id, keyId: f.key_id, key: f.curve25519, fallback: true });
    }
    return { keys: out };
  });

  /**
   * Send: one envelope per device (the recipient's devices and the sender's other devices, so the conversation follows the account).
   * Idempotent by clientId. The push says only "New message".
   */
  app.post('/v1/e2e/messages', { onRequest: auth, config: { rateLimit: { max: 60, timeWindow: '1 minute' } } }, async (req, reply) => {
    const b = z.object({
      to: z.string().uuid(), fromDevice: z.string().uuid(), clientId: z.string().uuid(),
      envelopes: z.array(z.object({ deviceId: z.string().uuid(), type: z.union([z.literal(0), z.literal(1)]), body: z.string().min(1).max(64_000) })).min(1).max(40),
    }).safeParse(req.body);
    if (!b.success) throw new HttpError(400, 'invalid_request');
    await ownDevice(req.userId, b.data.fromDevice);
    if (!(await canMessage(req.userId, b.data.to))) throw new HttpError(403, 'cannot_message');
    const devices = await sql`select device_id, user_id from e2e_devices where user_id in (${b.data.to}, ${req.userId})`;
    const owner = new Map(devices.map(d => [d.device_id as string, d.user_id as string]));
    let toRecipient = 0;
    for (const e of b.data.envelopes) {
      const user = owner.get(e.deviceId);
      if (!user || e.deviceId === b.data.fromDevice) continue;                // unknown device or the sender's own: skipped
      const inserted = await sql`insert into e2e_envelopes (id, recipient_device, recipient_user, sender_user, sender_device, peer_user, client_id, msg_type, body)
                                 values (${uuidv7()}, ${e.deviceId}, ${user}, ${req.userId}, ${b.data.fromDevice}, ${user === b.data.to ? req.userId : b.data.to}, ${b.data.clientId}, ${e.type}, ${e.body})
                                 on conflict (recipient_device, client_id) do nothing returning 1`;
      if (inserted.length && user === b.data.to) toRecipient++;
    }
    if (toRecipient) {
      bus.publish(`user:${b.data.to}`, { type: 'dm' });
      void push(b.data.to, { title: 'New message', data: { kind: 'dm' }, collapseKey: 'dm' }, 'system');
    }
    bus.publish(`user:${req.userId}`, { type: 'dm' });                         // the sender's other devices
    return reply.code(201).send({ delivered: toRecipient });
  });

  /** Ciphertext waiting for this device, oldest first. */
  app.get<{ Querystring: { deviceId?: string } }>('/v1/e2e/inbox', { onRequest: auth }, async req => {
    await ownDevice(req.userId, String(req.query.deviceId ?? ''));
    await sql`delete from e2e_envelopes where created_at < now() - ${`${ENVELOPE_DAYS} days`}::interval`;
    const rows = await sql`select e.id, e.sender_user, e.sender_device, e.peer_user, e.client_id, e.msg_type, e.body, e.created_at, d.curve25519 as sender_key
                           from e2e_envelopes e left join e2e_devices d on d.device_id = e.sender_device
                           where e.recipient_device = ${req.query.deviceId!} order by e.created_at limit 200`;
    return { envelopes: rows.map(r => ({
      id: r.id, from: r.sender_user, fromDevice: r.sender_device, senderKey: r.sender_key, clientId: r.client_id, type: r.msg_type, body: r.body,
      // For copies of your own messages from another device: who the conversation is with.
      peer: r.peer_user, createdAt: (r.created_at as Date).getTime(),
    })) };
  });

  /** The device has decrypted and stored these; the server forgets them. */
  app.post('/v1/e2e/inbox/ack', { onRequest: auth }, async (req, reply) => {
    const b = z.object({ deviceId: z.string().uuid(), ids: z.array(z.string().uuid()).max(200) }).safeParse(req.body);
    if (!b.success) throw new HttpError(400, 'invalid_request');
    await ownDevice(req.userId, b.data.deviceId);
    if (b.data.ids.length) await sql`delete from e2e_envelopes where recipient_device = ${b.data.deviceId} and id in ${sql(b.data.ids)}`;
    return reply.code(204).send();
  });
}
