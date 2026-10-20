import { sql } from '../db.js';

/**
 * Coaching styles. Balanced is free; the rest come with Plus. Whatever the style, the shared rules in the persona hold:
 * never shame missed days, one concrete next step, general guidance only on health, money and legal topics, and crisis
 * messages get the fixed supportive reply instead of any style.
 */
export const COACH_STYLES = ['balanced', 'strict', 'playful', 'efficient'] as const;
export type CoachStyle = (typeof COACH_STYLES)[number];
export const FREE_STYLES: readonly CoachStyle[] = ['balanced'];

export const STYLE_VOICE: Record<CoachStyle, string> = {
  balanced: 'Voice: warm, direct and short (90 words or fewer unless asked for more).',
  strict: 'Voice: firm and demanding, like a coach who expects the user to show up. Hold them to the plan they chose, name excuses plainly, and ask for a specific commitment with a time. High standards, never harshness: no insults, sarcasm or mockery. 70 words or fewer.',
  playful: 'Voice: warm, light and funny. A little wordplay or a gentle joke where it fits, never at the user\'s expense, and no jokes when they are struggling, unwell or upset. Still practical. 90 words or fewer.',
  efficient: 'Voice: concise and practical. No greeting, no praise, no filler. Up to three short bullet points when listing steps. 50 words or fewer.',
};

/** The style the coach uses now: the user's pick if it is free or they have Plus, otherwise balanced. */
export async function effectiveStyle(userId: string, picked: string | null | undefined): Promise<CoachStyle> {
  const style = (COACH_STYLES as readonly string[]).includes(picked ?? '') ? (picked as CoachStyle) : 'balanced';
  if (FREE_STYLES.includes(style)) return style;
  const [e] = await sql`select 1 from entitlements where user_id = ${userId} and entitlement = 'plus' and (expires_at is null or expires_at > now())`;
  return e ? style : 'balanced';
}
