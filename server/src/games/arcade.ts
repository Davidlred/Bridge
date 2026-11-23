import { sql } from '../db.js';
import { endMatch, sweepMatches } from './duel.js';
import { memberLeftBrackets, sweepBrackets } from './bracket.js';

/** A member left an alliance: each of their open matches there goes to the opponent; brackets move on without them. */
export async function afterLeave(allianceId: string, userId: string) {
  const open = await sql`select id, player_a, player_b from game_matches where alliance_id = ${allianceId} and status <> 'done' and (player_a = ${userId} or player_b = ${userId})`;
  for (const m of open) await endMatch(m.id, m.player_a === userId ? m.player_b : m.player_a, 'left');
  await memberLeftBrackets(allianceId);
}

/** Every minute. Also catches members who left some other way (a deleted goal or account). */
export async function sweepArcade() {
  const gone = await sql`select m.alliance_id, p.user_id from game_matches m cross join lateral (values (m.player_a), (m.player_b)) p(user_id)
                         where m.status <> 'done' and not exists (select 1 from alliance_members am where am.alliance_id = m.alliance_id and am.user_id = p.user_id and am.status <> 'left')`;
  for (const g of gone) await afterLeave(g.alliance_id, g.user_id);
  await sweepMatches();
  await sweepBrackets();
}
