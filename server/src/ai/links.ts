import { env } from '../env.js';
import type { Resource } from './planSchema.js';
import { plain } from './voice.js';

/** What the apps get: a name and a link that opens. */
export type Link = { title: string; url: string };
type Fetch = typeof fetch;

const YOUTUBE = /^https?:\/\/(www\.|m\.)?(youtube\.com\/(watch\?|shorts\/|playlist\?)|youtu\.be\/)/i;
export const youtubeSearch = (q: string) => `https://www.youtube.com/results?search_query=${encodeURIComponent(q)}`;
export const webSearch = (q: string) => `https://www.google.com/search?q=${encodeURIComponent(q)}`;

/**
 * True unless the page is clearly not there. Models invent plausible links (YouTube ids especially), so a 404/410, a host
 * that doesn't resolve or a YouTube id oEmbed doesn't know counts as missing. Sites that turn bots away (403, 429) or are
 * slow are given the benefit of the doubt.
 */
async function exists(url: string, f: Fetch, timeoutMs: number): Promise<boolean> {
  try {
    if (YOUTUBE.test(url)) {
      const r = await f(`https://www.youtube.com/oembed?format=json&url=${encodeURIComponent(url)}`, { signal: AbortSignal.timeout(timeoutMs) });
      return r.ok;
    }
    let r = await f(url, { method: 'HEAD', redirect: 'follow', signal: AbortSignal.timeout(timeoutMs) });
    if (r.status === 405 || r.status === 501) r = await f(url, { method: 'GET', redirect: 'follow', signal: AbortSignal.timeout(timeoutMs) });
    return r.status !== 404 && r.status !== 410;
  } catch (e) {
    return (e as Error)?.name === 'TimeoutError';
  }
}

/**
 * Turns the model's resources into links that open: a checked url, else a YouTube search (for a video or a search
 * phrase), else a web search for the title. All checks run in parallel; the same url is checked once.
 * In tests nothing goes to the network unless a fetch is passed in.
 */
export async function verifyResources(lists: Resource[][], opts: { fetch?: Fetch; timeoutMs?: number } = {}): Promise<Link[][]> {
  const f = opts.fetch ?? (env.NODE_ENV === 'test' ? null : fetch);
  const timeoutMs = opts.timeoutMs ?? 4000;
  const checks = new Map<string, Promise<boolean>>();
  const check = (url: string) => {
    if (!f) return Promise.resolve(true);
    if (!checks.has(url)) checks.set(url, exists(url, f, timeoutMs));
    return checks.get(url)!;
  };
  return Promise.all(lists.map(list => Promise.all(list.map(async (r): Promise<Link> => {
    const title = plain(r.title);
    if (r.url && await check(r.url)) return { title, url: r.url };
    if (r.search || (r.url && YOUTUBE.test(r.url))) return { title, url: youtubeSearch(r.search ?? title) };
    return { title, url: webSearch(title) };
  }))));
}
