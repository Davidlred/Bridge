import { describe, expect, it } from 'vitest';
import { verifyResources, webSearch, youtubeSearch } from '../src/ai/links.js';

/** A pretend internet: known pages answer 200, YouTube oEmbed only knows one video, one host doesn't exist. */
const fakeFetch = (async (input: string | URL | Request, init?: RequestInit) => {
  const url = String(input);
  if (url.startsWith('https://www.youtube.com/oembed')) return new Response('{}', { status: url.includes('REALVIDEO') ? 200 : 404 });
  if (url.includes('no-such-host.example')) throw new TypeError('fetch failed');
  if (url.includes('blocks-bots.example')) return new Response('', { status: 403 });
  if (url.includes('head-not-allowed.example')) return new Response('', { status: init?.method === 'HEAD' ? 405 : 200 });
  if (url.includes('gone.example')) return new Response('', { status: 404 });
  return new Response('', { status: 200 });
}) as typeof fetch;

describe('resource links', () => {
  it('keeps pages that exist and turns invented ones into searches', async () => {
    const [links] = await verifyResources([[
      { title: 'The Rust Book', url: 'https://doc.rust-lang.org/book/', search: null },
      { title: 'Ownership video', url: 'https://www.youtube.com/watch?v=REALVIDEO', search: null },
      { title: 'Made-up video', url: 'https://www.youtube.com/watch?v=FAKE123', search: 'rust ownership explained' },
      { title: 'Dead page', url: 'https://gone.example/page', search: null },
    ]], { fetch: fakeFetch });
    expect(links).toEqual([
      { title: 'The Rust Book', url: 'https://doc.rust-lang.org/book/' },
      { title: 'Ownership video', url: 'https://www.youtube.com/watch?v=REALVIDEO' },
      { title: 'Made-up video', url: youtubeSearch('rust ownership explained') },
      { title: 'Dead page', url: webSearch('Dead page') },
    ]);
  });

  it('gives sites that turn bots away the benefit of the doubt, and retries with GET when HEAD is refused', async () => {
    const [links] = await verifyResources([[
      { title: 'Blocks bots', url: 'https://blocks-bots.example/', search: null },
      { title: 'No HEAD', url: 'https://head-not-allowed.example/', search: null },
      { title: 'Invented site', url: 'https://no-such-host.example/', search: null },
      { title: 'Search only', url: null, search: 'couch to 5k week 1' },
    ]], { fetch: fakeFetch });
    expect(links!.map(l => l.url)).toEqual(['https://blocks-bots.example/', 'https://head-not-allowed.example/', webSearch('Invented site'), youtubeSearch('couch to 5k week 1')]);
  });

  it('checks each address once', async () => {
    let calls = 0;
    const counting = (async (...args: Parameters<typeof fetch>) => { calls++; return fakeFetch(...args); }) as typeof fetch;
    const r = { title: 'Book', url: 'https://doc.rust-lang.org/book/', search: null };
    await verifyResources([[r], [r], [r]], { fetch: counting });
    expect(calls).toBe(1);
  });
});
