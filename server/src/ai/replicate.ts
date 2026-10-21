import { env } from '../env.js';

const BASE = 'https://api.replicate.com/v1';
const headers = () => ({ Authorization: `Bearer ${env.REPLICATE_API_TOKEN}`, 'Content-Type': 'application/json' });
const sleep = (ms: number) => new Promise(r => setTimeout(r, ms));

export class AIUnavailable extends Error { constructor(msg: string, readonly retryable = true) { super(msg); } }

type Prediction = { id: string; status: 'starting' | 'processing' | 'succeeded' | 'failed' | 'canceled'; output?: unknown; error?: string; urls: { get: string; cancel: string; stream?: string } };

/**
 * Claude on Replicate rejects max_tokens below 1024 (422), although its published schema says 1: moderation asked for 3
 * and failed on every message. It is only a ceiling (prompts keep replies short), so raise it for Claude models here.
 */
export const textInput = (model: string, input: Record<string, unknown>) =>
  model.startsWith('anthropic/') && typeof input.max_tokens === 'number' && input.max_tokens < 1024 ? { ...input, max_tokens: 1024 } : input;

/** Runs a language model to completion. Uses Prefer: wait, then polls. Aborts and cancels on signal. */
export async function runText(model: string, input: Record<string, unknown>, signal?: AbortSignal): Promise<string> {
  const output = await runPrediction(model, textInput(model, input), signal);
  return Array.isArray(output) ? output.join('') : String(output ?? '');
}

/** Runs an image model to completion and returns the URL of the first image. */
export async function runImage(model: string, input: Record<string, unknown>, signal?: AbortSignal): Promise<string> {
  const output = await runPrediction(model, input, signal, 180_000);
  const url = Array.isArray(output) ? output[0] : output;
  if (typeof url !== 'string' || !/^https?:|^data:/.test(url)) throw new AIUnavailable('image_output_missing');
  return url;
}

/**
 * Creates a prediction. Replicate answers 429 when the account is throttled (accounts under $5 of credit allow about one
 * request at a time), so wait as long as it asks, at most 10s, and try up to twice more.
 */
async function create(model: string, body: object, signal?: AbortSignal, extra: Record<string, string> = {}): Promise<Response> {
  for (let attempt = 0; ; attempt++) {
    const res = await fetch(`${BASE}/models/${model}/predictions`, { method: 'POST', headers: { ...headers(), ...extra }, body: JSON.stringify(body), signal });
    if (res.status !== 429 || attempt === 2) return res;
    const wait = Math.min(10, Number(res.headers.get('retry-after')) || 2 * (attempt + 1));
    await res.body?.cancel();
    await sleep(wait * 1000);
    if (signal?.aborted) return res;
  }
}

async function runPrediction(model: string, input: Record<string, unknown>, signal?: AbortSignal, timeoutMs = 90_000): Promise<unknown> {
  const res = await create(model, { input }, signal, { Prefer: 'wait=60' });
  if (res.status === 429 || res.status >= 500) throw new AIUnavailable(`replicate ${res.status}`);
  if (!res.ok) throw new AIUnavailable(`replicate ${res.status}: ${await res.text()}`, false);
  let p = (await res.json()) as Prediction;
  const deadline = Date.now() + timeoutMs;
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
  return p.output;
}

/**
 * Turns Replicate's output events into new text only. Some models (Claude on Replicate) restart the stream partway:
 * a chunk repeats the reply from its first character, re-split differently, before carrying on. Seen 2026-10-20:
 * "1. B" · "ridges connect … They come" · " in many designs" · "1. Bridges conn" · "ect places …" · …
 */
export class StreamJoiner {
  private emitted = '';
  private seg = '';

  push(chunk: string): string {
    const e = this.emitted;
    // A replay from the start: a prefix of what was already sent, or a snapshot that runs past it.
    if (e.length >= 3 && chunk.length >= 3 && (e.startsWith(chunk) || chunk.startsWith(e))) this.seg = chunk;
    else this.seg += chunk;
    if (this.seg.startsWith(e)) {
      const out = this.seg.slice(e.length);
      this.emitted = this.seg;
      return out;
    }
    if (e.startsWith(this.seg)) return '';           // still catching up with what was already sent
    // Diverged: the "restart" was an ordinary delta that happened to match the opening. Send everything since then.
    const out = this.seg;
    this.emitted = e + out; this.seg = this.emitted;
    return out;
  }
}

/** Streams new text from a language model via Replicate's SSE stream URL. */
export async function* streamText(model: string, input: Record<string, unknown>, signal?: AbortSignal): AsyncGenerator<string> {
  const res = await create(model, { input: textInput(model, input), stream: true }, signal);
  if (!res.ok) throw new AIUnavailable(`replicate ${res.status}`, res.status >= 500 || res.status === 429);
  const p = (await res.json()) as Prediction;
  if (!p.urls.stream) throw new AIUnavailable('model does not support streaming', false);
  const s = await fetch(p.urls.stream, { headers: { Accept: 'text/event-stream', 'Cache-Control': 'no-store' }, signal });
  if (!s.body) throw new AIUnavailable('no stream body');
  const reader = s.body.pipeThrough(new TextDecoderStream()).getReader();
  const joiner = new StreamJoiner();
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
        if (event === 'output') { const text = joiner.push(data); if (text) yield text; }
        else if (event === 'error') throw new AIUnavailable(data);
        else if (event === 'done') return;
      }
    }
  } finally {
    reader.releaseLock();
    if (signal?.aborted) fetch(p.urls.cancel, { method: 'POST', headers: headers() }).catch(() => {});
  }
}
