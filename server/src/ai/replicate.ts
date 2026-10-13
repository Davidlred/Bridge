import { env } from '../env.js';

const BASE = 'https://api.replicate.com/v1';
const headers = () => ({ Authorization: `Bearer ${env.REPLICATE_API_TOKEN}`, 'Content-Type': 'application/json' });
const sleep = (ms: number) => new Promise(r => setTimeout(r, ms));

export class AIUnavailable extends Error { constructor(msg: string, readonly retryable = true) { super(msg); } }

type Prediction = { id: string; status: 'starting' | 'processing' | 'succeeded' | 'failed' | 'canceled'; output?: unknown; error?: string; urls: { get: string; cancel: string; stream?: string } };

/** Runs a language model to completion. Uses Prefer: wait, then polls. Aborts and cancels on signal. */
export async function runText(model: string, input: Record<string, unknown>, signal?: AbortSignal): Promise<string> {
  const res = await fetch(`${BASE}/models/${model}/predictions`, {
    method: 'POST', headers: { ...headers(), Prefer: 'wait=60' }, body: JSON.stringify({ input }), signal,
  });
  if (res.status === 429 || res.status >= 500) throw new AIUnavailable(`replicate ${res.status}`);
  if (!res.ok) throw new AIUnavailable(`replicate ${res.status}: ${await res.text()}`, false);
  let p = (await res.json()) as Prediction;
  const deadline = Date.now() + 90_000;
  try {
    while (p.status === 'starting' || p.status === 'processing') {
      if (Date.now() > deadline) throw new AIUnavailable('timeout');
      await sleep(800);
      p = (await (await fetch(p.urls.get, { headers: headers(), signal })).json()) as Prediction;
    }
  } catch (e) {
    fetch(p.urls.cancel, { method: 'POST', headers: headers() }).catch(() => {});
    throw e;
  }
  if (p.status !== 'succeeded') throw new AIUnavailable(p.error ?? p.status);
  return Array.isArray(p.output) ? p.output.join('') : String(p.output ?? '');
}

/** Streams tokens from a language model via Replicate's SSE stream URL. */
export async function* streamText(model: string, input: Record<string, unknown>, signal?: AbortSignal): AsyncGenerator<string> {
  const res = await fetch(`${BASE}/models/${model}/predictions`, { method: 'POST', headers: headers(), body: JSON.stringify({ input, stream: true }), signal });
  if (!res.ok) throw new AIUnavailable(`replicate ${res.status}`, res.status >= 500 || res.status === 429);
  const p = (await res.json()) as Prediction;
  if (!p.urls.stream) throw new AIUnavailable('model does not support streaming', false);
  const s = await fetch(p.urls.stream, { headers: { Accept: 'text/event-stream', 'Cache-Control': 'no-store' }, signal });
  if (!s.body) throw new AIUnavailable('no stream body');
  const reader = s.body.pipeThrough(new TextDecoderStream()).getReader();
  let buf = '';
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) return;
      buf += value;
      let i: number;
      while ((i = buf.indexOf('\n\n')) >= 0) {
        const block = buf.slice(0, i); buf = buf.slice(i + 2);
        const event = /^event: (.*)$/m.exec(block)?.[1];
        const data = block.split('\n').filter(l => l.startsWith('data: ')).map(l => l.slice(6)).join('\n');
        if (event === 'output') yield data;
        else if (event === 'error') throw new AIUnavailable(data);
        else if (event === 'done') return;
      }
    }
  } finally {
    reader.releaseLock();
    if (signal?.aborted) fetch(p.urls.cancel, { method: 'POST', headers: headers() }).catch(() => {});
  }
}
