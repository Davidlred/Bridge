import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { jwtVerify } from 'jose';
import { env } from '../env.js';
import { sql } from '../db.js';
import { auth } from '../auth/tokens.js';
import { HttpError, uuidv7 } from '../util.js';
import { bus } from '../realtime.js';
import { push } from '../push.js';
import { runText } from '../ai/replicate.js';
import { blockedWith, firstName } from './progress.js';
import { gameFrame, socketClosed, type SocketCtx } from './game.js';

const PAGE = 50;
/** Obvious slurs never post; everything else posts at once and is checked by the moderation model right after. */
const BLOCKLIST = /\b(nigg|fag+ot|retard|kys|kill yourself|tranny|chink|spic)\b/i;

const MODERATION_PROMPT = `You moderate a small private group chat in a goal-coaching app. Reply with exactly one word:
"allow" for normal conversation (including mild swearing and disagreement), or "hide" for harassment, hate, sexual content involving anyone, threats, self-harm encouragement, spam or personal data such as phone numbers and addresses.`;

const isMember = async (allianceId: string, userId: string) =>
  (await sql`select 1 from alliance_members m join alliances a on a.id = m.alliance_id where m.alliance_id = ${allianceId} and m.user_id = ${userId} and m.status <> 'left' and a.archived_at is null`).length > 0;

const shape = (m: Record<string, unknown>, userId: string) => ({
  id: m.id, clientId: m.client_id, userId: m.user_id, name: m.display_name ? firstName(String(m.display_name)) : null, mine: m.user_id === userId,
  kind: m.kind, body: m.body, createdAt: (m.created_at as Date).getTime(),
});

async function moderate(log: FastifyInstance['log'], allianceId: string, id: string, body: string) {
  try {
    const verdict = (await runText(env.MODEL_FAST, { system_prompt: MODERATION_PROMPT, prompt: body, max_tokens: 3, temperature: 0 })).trim().toLowerCase();
    if (verdict.startsWith('hide')) {
      await sql`update alliance_messages set moderation = 'hidden' where id = ${id}`;
      bus.publish(`alliance:${allianceId}`, { type: 'hidden', id });
    }
  } catch (e) {
    log.warn({ err: e instanceof Error ? e.message : String(e) }, 'moderation_unavailable');   // message stays up; reports still work
  }
}

export async function chatRoutes(app: FastifyInstance) {
  app.get<{ Params: { id: string }; Querystring: { before?: string } }>('/v1/alliances/:id/messages', { onRequest: auth }, async req => {
    if (!(await isMember(req.params.id, req.userId))) throw new HttpError(404, 'not_found');
    const blocked = await blockedWith(sql, req.userId);
    const before = req.query.before ? new Date(Number(req.query.before)) : new Date(Date.now() + 1000);
    const rows = await sql`select m.id, m.client_id, m.user_id, m.kind, m.body, m.created_at, u.display_name from alliance_messages m left join users u on u.id = m.user_id
                           where m.alliance_id = ${req.params.id} and m.moderation = 'allow' and m.created_at < ${before}
                             and (m.user_id is null or not (m.user_id = any(${blocked}::uuid[])))
                             and not exists (select 1 from reports r where r.reporter_id = ${req.userId} and r.target_kind = 'message' and r.target_id = m.id)
                           order by m.created_at desc limit ${PAGE}`;
    return { messages: rows.reverse().map(m => shape(m, req.userId)), notEncrypted: true };
  });

  app.post<{ Params: { id: string } }>('/v1/alliances/:id/messages', { onRequest: auth, config: { rateLimit: { max: 30, timeWindow: '1 minute' } } }, async (req, reply) => {
    const b = z.object({ clientId: z.string().uuid(), body: z.string().trim().min(1).max(2000) }).safeParse(req.body);
    if (!b.success) throw new HttpError(400, 'invalid_request');
    if (!(await isMember(req.params.id, req.userId))) throw new HttpError(404, 'not_found');
    if (BLOCKLIST.test(b.data.body)) throw new HttpError(422, 'message_blocked');
    // Idempotent by the client's id, so a retried send never posts twice.
    const [row] = await sql`insert into alliance_messages (id, alliance_id, user_id, kind, body, client_id) values (${uuidv7()}, ${req.params.id}, ${req.userId}, 'text', ${b.data.body}, ${b.data.clientId})
                            on conflict (alliance_id, client_id) where client_id is not null do update set client_id = excluded.client_id
                            returning id, client_id, user_id, kind, body, created_at, (xmax <> 0) as existed`;
    const [me] = await sql`select display_name from users where id = ${req.userId}`;
    const msg = shape({ ...row, display_name: me!.display_name }, req.userId);
    if (!row!.existed) {
      bus.publish(`alliance:${req.params.id}`, { type: 'message', message: { ...msg, mine: false } });
      void moderate(app.log, req.params.id, row!.id, b.data.body);
      // @mentions notify the person (content-free: the chat isn't end-to-end encrypted, but lock screens are public).
      const names = [...b.data.body.matchAll(/@(\p{L}[\p{L}'-]*)/gu)].map(m => m[1]!.toLowerCase());
      if (names.length) {
        const [a] = await sql`select name from alliances where id = ${req.params.id}`;
        const members = await sql`select m.user_id, u.display_name from alliance_members m join users u on u.id = m.user_id where m.alliance_id = ${req.params.id} and m.status <> 'left' and m.user_id <> ${req.userId}`;
        for (const m of members) if (names.includes(firstName(m.display_name).toLowerCase())) {
          void push(m.user_id, { title: `${firstName(me!.display_name)} mentioned you in ${a!.name}`, data: { allianceId: req.params.id }, collapseKey: `alliance-${req.params.id}` });
        }
      }
    }
    return reply.code(row!.existed ? 200 : 201).send({ message: msg });
  });

  /**
   * Realtime: one WebSocket per app session. The first frame must be {type:"auth", token} (tokens never go in URLs, which get logged).
   * Then the socket receives alliance events for every alliance the user belongs to; {type:"subscribe"} refreshes that list after joining one.
   */
  const pub = await import('node:crypto').then(c => c.createPublicKey(c.createPrivateKey(env.JWT_PRIVATE_KEY_PEM.replace(/\\n/g, '\n'))));
  app.get('/v1/rt', { websocket: true }, (socket) => {
    let userId: string | null = null;
    let offs: (() => void)[] = [];
    const watched: (() => void)[] = [];
    const send = (e: unknown) => { if (socket.readyState === 1) socket.send(JSON.stringify(e)); };
    const subscribe = async () => {
      offs.forEach(f => f()); offs = [];
      const rows = await sql`select alliance_id from alliance_members where user_id = ${userId} and status <> 'left'`;
      const blocked = new Set(await blockedWith(sql, userId!));
      for (const r of rows) offs.push(bus.subscribe(`alliance:${r.alliance_id}`, e => {
        const m = (e as { message?: { userId?: string } }).message;
        if (m?.userId && (m.userId === userId || blocked.has(m.userId))) return;     // own messages come back from the POST; blocked users stay invisible
        send({ ...e, allianceId: r.alliance_id });
      }));
      offs.push(bus.subscribe(`user:${userId}`, send));                            // direct events, e.g. your game role
      send({ type: 'ready', alliances: rows.map(r => r.alliance_id) });
    };
    const timer = setTimeout(() => { if (!userId) socket.close(4401, 'auth_timeout'); }, 5_000);
    const game: SocketCtx = { send, watch: channel => watched.push(bus.subscribe(channel, send)), games: new Set() };
    socket.on('message', async (raw: Buffer) => {
      let msg: { type?: string; token?: string };
      try { msg = JSON.parse(raw.toString()); } catch { return; }
      if (msg.type === 'auth' && !userId && msg.token) {
        try {
          const { payload } = await jwtVerify(msg.token, pub, { issuer: env.JWT_ISSUER, algorithms: ['EdDSA'] });
          const [live] = await sql`select 1 from sessions where id = ${String(payload.sid)} and revoked_at is null`;
          if (!live) throw new Error('revoked');
          userId = String(payload.sub);
          clearTimeout(timer);
          await subscribe();
        } catch { socket.close(4401, 'unauthorized'); }
      } else if (msg.type === 'subscribe' && userId) await subscribe();
      else if (msg.type?.startsWith('game:') && userId) await gameFrame(userId, msg, game);
      else if (msg.type === 'ping') send({ type: 'pong' });
    });
    socket.on('close', () => { clearTimeout(timer); offs.forEach(f => f()); watched.forEach(f => f()); if (userId) socketClosed(userId, game); });
  });
}
