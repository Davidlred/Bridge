/**
 * `npm run check-ai`: checks the Replicate token and that every configured model accepts the inputs this server sends.
 * Reads model schemas only (free). `npm run check-ai -- --live` also runs one tiny prompt on MODEL_FAST (costs a fraction of a cent).
 */
const BASE = 'https://api.replicate.com/v1';
const token = process.env.REPLICATE_API_TOKEN ?? '';
const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };

/** Inputs each role sends (src/ai/goalPlanner.ts, src/coach/routes.ts, src/social/chat.ts, src/goals/routes.ts). */
// maxTokens: every max_tokens value the server sends to that model (plan 1500, Reality Check 1024; coach 1024; moderation 3).
const ROLES: [env: string, inputs: string[], maxTokens: number[]][] = [
  ['MODEL_PLANNER', ['prompt', 'system_prompt', 'max_tokens'], [1500, 1024]],
  ['MODEL_COACH', ['prompt', 'system_prompt', 'max_tokens'], [1024]],
  ['MODEL_FAST', ['prompt', 'system_prompt', 'max_tokens'], [3]],
  ['MODEL_IMAGE', ['prompt', 'aspect_ratio'], []],
  ['MODEL_IMAGE_EDIT', ['prompt', 'input_image'], []],
];

type Prop = { minimum?: number; maximum?: number; enum?: string[]; allOf?: { $ref: string }[] };

async function main() {
  if (!token) { console.log('✗ REPLICATE_API_TOKEN is empty in server/.env'); process.exit(1); }
  const acct = await fetch(`${BASE}/account`, { headers });
  if (!acct.ok) { console.log(`✗ Replicate rejected the token (${acct.status}). Create a new one at https://replicate.com/account/api-tokens`); process.exit(1); }
  console.log(`✓ token works (account: ${(await acct.json() as { username: string }).username})`);

  let bad = 0;
  for (const [name, inputs, maxTokens] of ROLES) {
    const model = process.env[name];
    if (!model) { console.log(`✗ ${name} is not set`); bad++; continue; }
    const r = await fetch(`${BASE}/models/${model}`, { headers });
    if (!r.ok) { console.log(`✗ ${name}=${model}: not found on Replicate (${r.status})`); bad++; continue; }
    const m = await r.json() as { latest_version?: { openapi_schema?: { components?: { schemas?: { Input?: { properties?: Record<string, Prop> } } } } } };
    const props = m.latest_version?.openapi_schema?.components?.schemas?.Input?.properties ?? {};
    const missing = inputs.filter(i => !(i in props));
    // Some models set a floor (Opus 4.7 on Replicate: 1024) or a ceiling on max_tokens.
    const lo = props.max_tokens?.minimum ?? 0, hi = props.max_tokens?.maximum ?? Infinity;
    const outOfRange = maxTokens.filter(t => t < lo || t > hi);
    if (missing.length) { console.log(`✗ ${name}=${model}: doesn't accept ${missing.join(', ')}`); bad++; }
    else if (outOfRange.length) { console.log(`✗ ${name}=${model}: max_tokens must be ${lo}–${hi}; the server sends ${outOfRange.join(', ')}`); bad++; }
    else console.log(`✓ ${name}=${model}`);
  }

  if (process.argv.includes('--live') && !bad) {
    const res = await fetch(`${BASE}/models/${process.env.MODEL_FAST}/predictions`, {
      method: 'POST', headers: { ...headers, Prefer: 'wait=60' },
      body: JSON.stringify({ input: { system_prompt: 'Answer with one word.', prompt: 'Reply with the word ok.', max_tokens: 3, temperature: 0 } }),
    });
    const p = await res.json() as { status?: string; output?: unknown; error?: string; detail?: string };
    const out = Array.isArray(p.output) ? p.output.join('') : String(p.output ?? '');
    if (res.ok && p.status === 'succeeded') console.log(`✓ live call answered: "${out.trim()}"`);
    else { console.log(`✗ live call failed: ${p.error ?? p.detail ?? p.status ?? res.status}${res.status === 402 ? ' (add billing at https://replicate.com/account/billing)' : ''}`); bad++; }

    // The coach streams its replies (src/coach/routes.ts → streamText).
    if (!bad) {
      try {
        const start = await fetch(`${BASE}/models/${process.env.MODEL_COACH}/predictions`, {
          method: 'POST', headers, body: JSON.stringify({ stream: true, input: { system_prompt: 'Answer in at most five words.', prompt: 'Say hello.', max_tokens: 20, temperature: 0 } }),
        });
        const s = await start.json() as { urls?: { stream?: string }; detail?: string };
        if (!start.ok || !s.urls?.stream) throw new Error(s.detail ?? 'this model does not offer a stream');
        const body = await (await fetch(s.urls.stream, { headers: { Accept: 'text/event-stream' } })).text();   // ends at the "done" event
        if (/^event: error$/m.test(body)) throw new Error(body.split('\n').find(l => l.startsWith('data: '))?.slice(6) ?? 'stream error');
        const text = body.split('\n\n').filter(b => /^event: output$/m.test(b))
          .map(b => b.split('\n').filter(l => l.startsWith('data: ')).map(l => l.slice(6)).join('\n')).join('');
        console.log(`✓ coach streams: "${text.trim()}"`);
      } catch (e) { console.log(`✗ coach streaming failed: ${(e as Error).message}`); bad++; }
    }
  }
  process.exit(bad ? 1 : 0);
}

await main();
