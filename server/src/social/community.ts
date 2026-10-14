import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { sql } from '../db.js';
import { auth } from '../auth/tokens.js';
import { HttpError, uuidv7 } from '../util.js';
import { blockedWith, communityUnlocked, firstName, goalStreak, localToday } from './progress.js';

const REACTIONS = ['respect', 'keep_going', 'inspired'] as const;
const FEED_PAGE = 30;
const WIDEN_BELOW = 5;               // fewer posts than this in a niche category: show everyone, labelled
const AUTO_HIDE_REPORTS = 3;

/** Sharing setting from Profile → Privacy. Default: streaks only. */
type Share = 'nothing' | 'streaks' | 'all';
const shareOf = (settings: unknown): Share => {
  const v = (settings as Record<string, unknown> | null)?.share;
  return v === 'nothing' || v === 'all' ? v : 'streaks';
};

export async function communityRoutes(app: FastifyInstance) {
  // Feed for one goal's category. Locked until a 7-day streak on that goal (the app shows sample data as a preview).
  app.get<{ Querystring: { goalId?: string; before?: string } }>('/v1/community', { onRequest: auth }, async req => {
    const goalId = z.string().uuid().safeParse(req.query.goalId);
    if (!goalId.success) throw new HttpError(400, 'invalid_request');
    const [g] = await sql`select category, label from goals where id = ${goalId.data} and user_id = ${req.userId}`;
    if (!g) throw new HttpError(404, 'not_found');
    const { unlocked, streak } = await communityUnlocked(sql, req.userId, goalId.data);
    if (!unlocked) return { unlocked, streak, needed: 7, posts: [], widened: false };
    const category = (g.category as string | null) ?? 'other';
    const blocked = await blockedWith(sql, req.userId);
    const before = req.query.before ? new Date(Number(req.query.before)) : new Date(Date.now() + 1000);
    const page = (anyCategory: boolean) => sql`
      select p.id, p.user_id, p.kind, p.value, p.label, p.created_at, u.display_name,
             (select count(*)::int from reactions r where r.post_id = p.id and r.kind = 'respect') as respect,
             (select count(*)::int from reactions r where r.post_id = p.id and r.kind = 'keep_going') as keep_going,
             (select count(*)::int from reactions r where r.post_id = p.id and r.kind = 'inspired') as inspired,
             coalesce((select array_agg(r.kind) from reactions r where r.post_id = p.id and r.user_id = ${req.userId}), '{}') as mine
      from community_posts p join users u on u.id = p.user_id
      where p.moderation = 'allow' and u.deleted_at is null and p.created_at < ${before}
        and (${anyCategory} or p.category = ${category})
        and not (p.user_id = any(${blocked}::uuid[]))
        and not exists (select 1 from reports r where r.reporter_id = ${req.userId} and r.target_kind = 'post' and r.target_id = p.id)
      order by p.created_at desc limit ${FEED_PAGE}`;
    let rows = await page(false);
    let widened = false;
    if (!req.query.before && rows.length < WIDEN_BELOW) { rows = await page(true); widened = true; }
    return {
      unlocked, streak, category, widened,
      posts: rows.map(p => ({
        id: p.id, userId: p.user_id, mine: p.user_id === req.userId, name: firstName(p.display_name), kind: p.kind, value: p.value, label: p.label,
        createdAt: (p.created_at as Date).getTime(), reactions: { respect: p.respect, keepGoing: p.keep_going, inspired: p.inspired },
        reacted: (p.mine as string[]).map(k => (k === 'keep_going' ? 'keepGoing' : k)),
      })),
    };
  });

  // The app announces progress; the server checks it against the completions it has and the sharing setting.
  app.post('/v1/community/posts', { onRequest: auth }, async (req, reply) => {
    const b = z.object({ goalId: z.string().uuid(), kind: z.enum(['streak', 'milestone', 'day_complete']), value: z.number().int().min(1).max(10_000).optional() }).safeParse(req.body);
    if (!b.success) throw new HttpError(400, 'invalid_request');
    const [g] = await sql`select g.category, g.label, u.settings from goals g join users u on u.id = g.user_id where g.id = ${b.data.goalId} and g.user_id = ${req.userId}`;
    if (!g) throw new HttpError(404, 'not_found');
    if (!(await communityUnlocked(sql, req.userId, b.data.goalId)).unlocked) throw new HttpError(403, 'community_locked');
    const share = shareOf(g.settings);
    if (share === 'nothing' || (share === 'streaks' && b.data.kind !== 'streak')) return reply.code(204).send();
    let value = b.data.value ?? null;
    if (b.data.kind === 'streak') {
      const real = await goalStreak(sql, req.userId, b.data.goalId);
      if (!value || value > real) throw new HttpError(422, 'streak_mismatch');   // clock tampering or a stale device
    }
    const today = await localToday(sql, req.userId);
    const [p] = await sql`insert into community_posts (id, user_id, category, kind, goal_id, label, value, local_date)
                          values (${uuidv7()}, ${req.userId}, ${g.category ?? 'other'}, ${b.data.kind}, ${b.data.goalId}, ${g.label}, ${value}, ${today})
                          on conflict do nothing returning id`;
    return reply.code(p ? 201 : 200).send({ id: p?.id ?? null });
  });

  app.put<{ Params: { id: string; kind: string } }>('/v1/community/posts/:id/reactions/:kind', { onRequest: auth }, async (req, reply) => {
    const kind = { respect: 'respect', keepGoing: 'keep_going', inspired: 'inspired' }[req.params.kind];
    if (!kind || !z.string().uuid().safeParse(req.params.id).success) throw new HttpError(400, 'invalid_request');
    // Reacting needs Community open on at least one goal (opened lazily, as the feed does).
    const goals = await sql`select id from goals where user_id = ${req.userId} and status = 'active' order by community_unlocked_at nulls last`;
    let open = false;
    for (const g of goals) if ((open = (await communityUnlocked(sql, req.userId, g.id)).unlocked)) break;
    if (!open) throw new HttpError(403, 'community_locked');
    await sql`insert into reactions (post_id, user_id, kind) select ${req.params.id}, ${req.userId}, ${kind} where exists (select 1 from community_posts where id = ${req.params.id}) on conflict do nothing`;
    return reply.code(204).send();
  });

  app.delete<{ Params: { id: string; kind: string } }>('/v1/community/posts/:id/reactions/:kind', { onRequest: auth }, async (req, reply) => {
    const kind = { respect: 'respect', keepGoing: 'keep_going', inspired: 'inspired' }[req.params.kind];
    if (!kind || !z.string().uuid().safeParse(req.params.id).success) throw new HttpError(400, 'invalid_request');
    await sql`delete from reactions where post_id = ${req.params.id} and user_id = ${req.userId} and kind = ${kind}`;
    return reply.code(204).send();
  });

  // Reports: hidden for the reporter at once; three independent reports hide a post or message for everyone pending review.
  app.post('/v1/reports', { onRequest: auth, config: { rateLimit: { max: 30, timeWindow: '1 hour' } } }, async (req, reply) => {
    const b = z.object({ targetKind: z.enum(['post', 'message', 'user']), targetId: z.string().uuid(), reason: z.string().trim().min(1).max(200), evidence: z.record(z.unknown()).optional() }).safeParse(req.body);
    if (!b.success) throw new HttpError(400, 'invalid_request');
    await sql`insert into reports (id, reporter_id, target_kind, target_id, reason, evidence) values (${uuidv7()}, ${req.userId}, ${b.data.targetKind}, ${b.data.targetId}, ${b.data.reason}, ${b.data.evidence ? sql.json(b.data.evidence as never) : null})
              on conflict do nothing`;
    const [n] = await sql`select count(distinct reporter_id)::int as n from reports where target_kind = ${b.data.targetKind} and target_id = ${b.data.targetId}`;
    if (n!.n >= AUTO_HIDE_REPORTS) {
      if (b.data.targetKind === 'post') await sql`update community_posts set moderation = 'hidden' where id = ${b.data.targetId}`;
      if (b.data.targetKind === 'message') await sql`update alliance_messages set moderation = 'hidden' where id = ${b.data.targetId}`;
    }
    return reply.code(204).send();
  });

  app.put<{ Params: { userId: string } }>('/v1/blocks/:userId', { onRequest: auth }, async (req, reply) => {
    if (!z.string().uuid().safeParse(req.params.userId).success || req.params.userId === req.userId) throw new HttpError(400, 'invalid_request');
    await sql`insert into blocks (blocker_id, blocked_id) select ${req.userId}, ${req.params.userId} where exists (select 1 from users where id = ${req.params.userId}) on conflict do nothing`;
    return reply.code(204).send();
  });
  app.delete<{ Params: { userId: string } }>('/v1/blocks/:userId', { onRequest: auth }, async (req, reply) => {
    await sql`delete from blocks where blocker_id = ${req.userId} and blocked_id = ${req.params.userId}`;
    return reply.code(204).send();
  });
}
