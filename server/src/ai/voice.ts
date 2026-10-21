/** How every model-written line should read: like a person, not a chatbot. Shared by the planner, Reality Check and the coach. */
export const PLAIN_VOICE = 'Write plainly, the way a person would: no em dashes, no emoji, no exclamation marks, and no filler words like journey, unlock, elevate, empower or game-changer.';

/** Models still slip in em and en dashes; short plan text reads better with a comma. */
export const plain = (s: string) => s.replace(/\s*[\u2014\u2013]\s*/g, ', ').replace(/,\s*,/g, ',');
