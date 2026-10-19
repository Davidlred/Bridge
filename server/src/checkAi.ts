/**
 * `npm run check-ai`: checks the Replicate token and that every configured model accepts the inputs this server sends.
 * Reads model schemas only (free). `npm run check-ai -- --live` also runs one tiny prompt on MODEL_FAST (costs a fraction of a cent).
 */
const BASE = 'https://api.replicate.com/v1';
const token = process.env.REPLICATE_API_TOKEN ?? '';
const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };

/** Inputs each role sends (src/ai/goalPlanner.ts, src/coach/routes.ts, src/social/chat.ts, src/goals/routes.ts). */
const ROLES: [env: string, inputs: string[]][] = [
  ['MODEL_PLANNER', ['prompt', 'system_prompt', 'max_tokens']],
  ['MODEL_COACH', ['prompt', 'system_prompt', 'max_tokens']],
  ['MODEL_FAST', ['prompt', 'system_prompt', 'max_tokens']],
  ['MODEL_IMAGE', ['prompt', 'aspect_ratio']],
  ['MODEL_IMAGE_EDIT', ['prompt', 'input_image']],
];

type Prop = { minimum?: number; enum?: string[]; allOf?: { $ref: string }[] };

async function main() {
  if (!token) { console.log('✗ REPLICATE_API_TOKEN is empty in server/.env'); process.exit(1); }
  const acct = await fetch(`${BASE}/account`, { headers });
  if (!acct.ok) { console.log(`✗ Replicate rejected the token (${acct.status}). Create a new one at https://replicate.com/account/api-tokens`); process.exit(1); }
  console.log(`✓ token works (account: ${(await acct.json() as { username: string }).username})`);

  let bad = 0;
  for (const [name, inputs] of ROLES) {
    const model = process.env[name];
    if (!model) { console.log(`✗ ${name} is not set`); bad++; continue; }
    const r = await fetch(`${BASE}/models/${model}`, { headers });
    if (!r.ok) { console.log(`✗ ${name}=${model}: not found on Replicate (${r.status})`); bad++; continue; }
    const m = await r.json() as { latest_version?: { openapi_schema?: { components?: { schemas?: { Input?: { properties?: Record<string, Prop> } } } } } };
    const props = m.latest_version?.openapi_schema?.components?.schemas?.Input?.properties ?? {};
    const missing = inputs.filter(i => !(i in props));
    // Moderation asks for a one-word answer (max_tokens 3); some models set a higher floor.
    const floor = name === 'MODEL_FAST' ? props.max_tokens?.minimum : undefined;
    if (missing.length) { console.log(`✗ ${name}=${model}: doesn't accept ${missing.join(', ')}`); bad++; }
    else if (floor !== undefined && floor > 3) { console.log(`✗ ${name}=${model}: max_tokens must be at least ${floor}; src/social/chat.ts asks for 3`); bad++; }
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
  }
  process.exit(bad ? 1 : 0);
}

await main();
