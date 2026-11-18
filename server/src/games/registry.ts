import type { FastifyInstance } from 'fastify';
import { auth } from '../auth/tokens.js';
import { env } from '../env.js';
import { driftModule } from '../social/game.js';
import { whoseModule } from '../social/whoseday.js';
import type { GameId, GameMeta, GameModule } from './module.js';

/** The Arcade line-up, in order. Tile colours cycle through the avatar fills in this order. */
export const LINEUP: GameMeta[] = [
  { id: 'drift', name: "Who's Drifting?", icon: 'visibility_off', mode: 'everyone', minPlayers: 3, maxPlayers: 15, avgMinutes: 2 },
  { id: 'whose', name: 'Whose Day?', icon: 'person_search', mode: 'everyone', minPlayers: 3, maxPlayers: 15, avgMinutes: 2 },
  { id: 'word', name: 'Word Dash', icon: 'abc', mode: 'duel_turns', minPlayers: 2, maxPlayers: 2, avgMinutes: 2 },
  { id: 'scramble', name: 'Scramble', icon: 'shuffle', mode: 'duel_turns', minPlayers: 2, maxPlayers: 2, avgMinutes: 2 },
  { id: 'cup', name: 'Cup Pong', icon: 'local_drink', mode: 'duel_turns', liveToo: true, minPlayers: 2, maxPlayers: 2, avgMinutes: 3 },
  { id: 'pool', name: '8-Ball', icon: 'radio_button_checked', mode: 'duel_turns', minPlayers: 2, maxPlayers: 2, avgMinutes: 3 },
  { id: 'hoops', name: 'Hoops', icon: 'sports_basketball', mode: 'everyone', minPlayers: 2, maxPlayers: 15, avgMinutes: 1 },
  { id: 'darts', name: 'Darts', icon: 'adjust', mode: 'duel_turns', minPlayers: 2, maxPlayers: 2, avgMinutes: 3 },
  { id: 'golf', name: 'Mini Golf', icon: 'golf_course', mode: 'everyone', minPlayers: 2, maxPlayers: 15, avgMinutes: 3 },
  { id: 'four', name: 'Four in a Row', icon: 'view_column', mode: 'duel_turns', minPlayers: 2, maxPlayers: 2, avgMinutes: 2 },
  { id: 'fleet', name: 'Fleet Battle', icon: 'sailing', mode: 'duel_turns', minPlayers: 2, maxPlayers: 2, avgMinutes: 3 },
  { id: 'push', name: 'Push Out', icon: 'trip_origin', mode: 'everyone', minPlayers: 3, maxPlayers: 15, avgMinutes: 2 },
].map((m, i) => ({ ...m, tile: i % 5 }) as GameMeta);

/** Games that are built. The others are listed (so the picker can show what's coming) but can't be picked yet. */
// Built on first use: the game files import this one (through Game Night voting), so the map can't be filled at load.
let built: Map<GameId, GameModule> | undefined;
const MODULES = () => (built ??= new Map<GameId, GameModule>([[driftModule.meta.id, driftModule], [whoseModule.meta.id, whoseModule]]));

export const moduleFor = (id: string): GameModule | undefined => MODULES().get(id as GameId);

/** The live game running in this alliance right now, if any (one at a time). */
export function liveGameOpen(allianceId: string): GameId | null {
  for (const m of MODULES().values()) if (m.kind === 'live' && m.openGame(allianceId)) return m.meta.id;
  return null;
}

/**
 * Whether a game can be played, from server config. Every built game is free for now; GAMES_OFF (comma-separated
 * ids) switches games off without an app update, so they can be gated later.
 */
export function available(id: GameId): boolean {
  const off = new Set(env.GAMES_OFF.split(',').map(s => s.trim()).filter(Boolean));
  return MODULES().has(id) && !off.has(id);
}

export const catalog = () => LINEUP.map(m => ({ ...m, built: MODULES().has(m.id), available: available(m.id) }));

export async function arcadeRoutes(app: FastifyInstance) {
  /** The line-up for the picker: every game, whether it's built and whether it can be played right now. */
  app.get('/v1/games', { onRequest: auth }, async () => ({ games: catalog() }));
}
