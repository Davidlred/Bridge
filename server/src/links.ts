import type { FastifyInstance } from 'fastify';
import { env } from './env.js';
import { sql } from './db.js';
import { page } from './auth/routes.js';

/**
 * Invite links: https://thebridge.app/join/CALC-2026 (docs/EDGE_CASES.md → Alliances).
 *
 * - With the app installed, Android App Links and iOS Universal Links open it straight on the invite preview. Both
 *   platforms check a file on this domain first: /.well-known/assetlinks.json and /.well-known/apple-app-site-association.
 * - Without the app, this page names the alliance, shows the code, and links to the stores. The Play link carries the code
 *   as an install referrer, so the Android app opens the preview after install (deferred deep link). The App Store has
 *   no equivalent, so the page asks iPhone users to enter the code in Community → Join.
 *
 * This route serves the website domain; point thebridge.app at the API as well as api.thebridge.app.
 */
const PACKAGE = 'app.thebridge';
const CODE = /^[A-Za-z]{4}-\d{4}$/;
const esc = (s: string) => s.replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!);

export const playUrl = (code?: string) =>
  `https://play.google.com/store/apps/details?id=${PACKAGE}${code ? `&referrer=${encodeURIComponent(`join=${code}`)}` : ''}`;

export async function linkRoutes(app: FastifyInstance) {
  // Android: which signing certificates may open thebridge.app links (upload key and Play app signing key, SHA-256).
  app.get('/.well-known/assetlinks.json', async (_req, reply) => {
    const fingerprints = env.ANDROID_CERT_SHA256;
    return reply.header('Cache-Control', 'public, max-age=3600').send(fingerprints.length
      ? [{ relation: ['delegate_permission/common.handle_all_urls'], target: { namespace: 'android_app', package_name: PACKAGE, sha256_cert_fingerprints: fingerprints } }]
      : []);
  });

  // iOS: the app (team id + bundle id) that handles /join/* on this domain.
  const aasa = async (_req: unknown, reply: import('fastify').FastifyReply) => {
    const appId = env.APNS_TEAM_ID ? `${env.APNS_TEAM_ID}.${env.APNS_BUNDLE_ID}` : null;
    return reply.type('application/json').header('Cache-Control', 'public, max-age=3600')
      .send({ applinks: { details: appId ? [{ appIDs: [appId], components: [{ '/': '/join/*', comment: 'Alliance invites' }] }] : [] } });
  };
  app.get('/.well-known/apple-app-site-association', aasa);
  app.get('/apple-app-site-association', aasa);

  // For people without the app (or on a computer). Shows only what the code holder needs: the alliance name and size.
  app.get<{ Params: { code: string } }>('/join/:code', { config: { rateLimit: { max: 60, timeWindow: '1 minute' } } }, async (req, reply) => {
    const code = req.params.code.trim().toUpperCase();
    const [a] = CODE.test(code)
      ? await sql`select a.name, a.plan_mode, a.archived_at, g.deadline::text, ((now() at time zone 'UTC')::date > g.deadline) as ended,
                         (select count(*)::int from alliance_members m where m.alliance_id = a.id and m.status <> 'left') as members
                  from alliances a join goals g on g.id = a.lead_goal_id where a.invite_code = ${code}`
      : [];
    reply.type('text/html').header('Cache-Control', 'no-store');
    if (!a || a.archived_at) return reply.code(404).send(page('Invite not found', '<p>This invite code doesn\'t exist any more. Ask for a new link.</p>'));
    if (a.plan_mode === 'shared' && a.ended) return reply.send(page('This plan has ended', `<p>${esc(a.name)} finished its shared plan. Ask the lead about the next one.</p>`));
    const appStore = env.APP_STORE_ID ? `https://apps.apple.com/app/id${env.APP_STORE_ID}` : null;
    return reply.send(page(esc(a.name), `<p>You're invited to join this alliance on The Bridge. ${a.members === 1 ? '1 member' : `${a.members} members`} so far.</p>
<p style="color:inherit;font:600 22px/1.2 ui-monospace,monospace;letter-spacing:.08em;margin-bottom:8px">${code}</p>
<p>Your invite code. In the app: <b>Community → Join an alliance</b>.</p>
<p><a href="${playUrl(code)}"><button type="button">Get it on Google Play</button></a></p>
${appStore ? `<p><a href="${appStore}"><button type="button">Download on the App Store</button></a></p>` : ''}`));
  });
}
