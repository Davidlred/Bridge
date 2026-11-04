import { sql, type Tx } from './db.js';
import { HttpError } from './util.js';

/** The Bridge is for people 12 and over. Under 18 is a minor: no DMs with adults, not findable, no AI images from a photo. */
export const MIN_AGE = 12;
export const ADULT_AGE = 18;

/** Full years between a birth date and a day (both YYYY-MM-DD). */
export function ageOn(birth: string, day: string): number {
  const [by, bm, bd] = birth.split('-').map(Number) as [number, number, number];
  const [y, m, d] = day.split('-').map(Number) as [number, number, number];
  return y - by - (m < bm || (m === bm && d < bd) ? 1 : 0);
}

/** A new account's date of birth: rejected under 12, and when it can't be real. Older app versions don't send one. */
export function checkBirthDate(birth: string | undefined, today = new Date().toISOString().slice(0, 10)): string | null {
  if (!birth) return null;
  const age = ageOn(birth, today);
  if (birth > today || age > 120) throw new HttpError(400, 'invalid_birth_date');
  if (age < MIN_AGE) throw new HttpError(403, 'too_young', { minAge: MIN_AGE });
  return birth;
}

export const isMinorAge = (birth: string | null | undefined, today = new Date().toISOString().slice(0, 10)) => !!birth && ageOn(birth, today) < ADULT_AGE;

/** Under 18 (accounts without a date of birth count as adults). */
export async function isMinor(db: Tx | typeof sql, userId: string): Promise<boolean> {
  const [u] = await db`select birth_date::text as b from users where id = ${userId}`;
  return isMinorAge(u?.b as string | null | undefined);
}
