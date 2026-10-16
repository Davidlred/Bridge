import type { FastifyInstance } from 'fastify';
import { sql } from './db.js';
import { auth } from './auth/tokens.js';
import { decryptText, journalKey } from './crypto.js';

const iso = (d: unknown) => (d instanceof Date ? d.toISOString() : d ?? null);

/**
 * "Download my data": everything the account owns, as JSON, with journal and coach text decrypted for the owner.
 * Other people's content (their chat messages, posts) is left out; only the user's own messages are included.
 * Nothing here is logged.
 */
export async function exportRoutes(app: FastifyInstance) {
  app.get('/v1/me/export', { onRequest: auth, config: { rateLimit: { max: 3, timeWindow: '1 day' } } }, async (req, reply) => {
    const id = req.userId;
    const [user] = await sql`select id, email, display_name, coach_name, home_timezone, settings, created_at from users where id = ${id}`;
    const key = await journalKey(sql, id);
    const goals = await sql`select id, label, statement, category, start_date::text, deadline::text, status, routine, reality_note, feasibility from goals where user_id = ${id} order by start_date`;
    const tasks = await sql`select id, goal_id, title, priority, time_of_day, minutes, rationale, notes, origin, position, deleted_at from tasks where user_id = ${id} order by goal_id, position`;
    const completions = await sql`select task_id, local_date::text, completed_at from task_completions where user_id = ${id} order by local_date`;
    const journal = await sql`select id, goal_id, local_date::text, mood, body_ciphertext, updated_at from journal_entries where user_id = ${id} and conflict_of is null order by local_date`;
    const coach = await sql`select id, conversation, role, body_ciphertext, created_at from coach_messages where user_id = ${id} order by created_at`;
    const alliances = await sql`select a.name, a.plan_mode, m.role, m.status, m.joined_at from alliance_members m join alliances a on a.id = m.alliance_id where m.user_id = ${id}`;
    const messages = await sql`select a.name as alliance, m.body, m.created_at from alliance_messages m join alliances a on a.id = m.alliance_id where m.user_id = ${id} order by m.created_at`;
    const posts = await sql`select kind, value, label, created_at from community_posts where user_id = ${id} order by created_at`;
    const badges = await sql`select badge, earned_at from badges where user_id = ${id}`;
    const games = await sql`select s.local_date::text, s.ranked, p.role, p.actions, s.builders_won from game_players p join game_sessions s on s.id = p.game_id where p.user_id = ${id} order by s.started_at`;
    const body = {
      exportedAt: new Date().toISOString(),
      account: { ...user, created_at: iso(user!.created_at) },
      goals,
      tasks: tasks.map(t => ({ ...t, deleted_at: iso(t.deleted_at) })),
      completions: completions.map(c => ({ ...c, completed_at: iso(c.completed_at) })),
      journal: journal.map(j => ({ id: j.id, goal_id: j.goal_id, local_date: j.local_date, mood: j.mood, body: decryptText(key, j.body_ciphertext), updated_at: iso(j.updated_at) })),
      coach: coach.map(c => {
        const b = JSON.parse(decryptText(key, c.body_ciphertext) || '{}') as { text?: string; suggestion?: unknown };
        return { conversation: c.conversation, role: c.role, text: b.text ?? '', suggestion: b.suggestion ?? null, created_at: iso(c.created_at) };
      }),
      alliances: alliances.map(a => ({ ...a, joined_at: iso(a.joined_at) })),
      allianceMessages: messages.map(m => ({ ...m, created_at: iso(m.created_at) })),
      communityPosts: posts.map(p => ({ ...p, created_at: iso(p.created_at) })),
      badges: badges.map(b => ({ badge: b.badge, earned_at: iso(b.earned_at) })),
      games,
    };
    return reply.header('Content-Disposition', 'attachment; filename="the-bridge-export.json"').header('Cache-Control', 'no-store').send(body);
  });
}
