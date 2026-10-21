# AI: models, prompts, guardrails

All model calls go through our server. The Replicate token never ships in an app.

## Model choice
| Job | Model (Replicate) | Why |
|---|---|---|
| Goal → plan, Reality Check, plan edits | **Anthropic Claude, latest Opus or Sonnet-class model on Replicate** (pinned: `anthropic/claude-opus-4.7`, the newest Opus on Replicate as of 2026-10-19; `MODEL_FAST` for moderation is `anthropic/claude-4.5-haiku`) | Best at decomposing vague goals into realistic daily actions, respecting constraints (user deadline, routine), and returning strict JSON |
| Coach chat (streaming) | Same Sonnet-class Claude model | Consistent persona with the planner; streaming support |
| Daily nudges, journal prompts, moderation classification | Smaller/faster Claude (Haiku-class) if available on Replicate, else the same model with short max tokens | Cost and latency |
| Goal cover art, badge art | Top image model on Replicate: Google Imagen 4 / "nano-banana" (Gemini image), FLUX 1.1 Pro / FLUX Kontext, or Seedream. Pick one after a 20-prompt bake-off on your style | Quality + style consistency; Kontext-style editing models keep a consistent look from a reference image |
| Image moderation | A dedicated image safety model on Replicate, or the image model provider's safety flag | Required before showing generated or uploaded images publicly |

Model ids live in env vars (`MODEL_PLANNER`, `MODEL_COACH`, `MODEL_FAST`, `MODEL_IMAGE`), never in client code, so you can swap versions without an app release. Pin a version hash in production; upgrade behind a flag with an eval run.

## Plan generation contract
Request (client → `POST /v1/ai/plan`):
```json
{ "goalText": "Run a half marathon", "deadline": "2027-02-09", "today": "2026-10-13", "timezone": "Africa/Lagos",
  "routine": { "wake": "06:30", "sleep": "23:00", "busy": ["09:00-17:00"], "preferred": ["Morning"] },
  "locale": "en-NG", "existingGoals": 1 }
```
Rules baked into the system prompt:
1. The **deadline field is authoritative**. Ignore dates in the free text. If the free text contradicts the deadline, mention it in `feasibility.reason`, never change `deadline`.
2. 2–6 daily tasks, each doable in ≤ 90 minutes, scheduled outside busy blocks.
3. Every task has a one-sentence rationale.
4. No medical, legal or financial advice beyond general guidance; for health goals add "check with a professional" when relevant.
5. Output **only** JSON matching the schema (see `server/src/ai/planSchema.ts`).

Server pipeline: build prompt → call model (60s sync wait, poll fallback) → strip code fences → `JSON.parse` → zod validate → on failure, one repair retry with the zod error → on second failure return `503 plan_unavailable` (client shows "Generation fails" state with "Write my own tasks").

Latency target: p50 < 8s, p95 < 20s. Client shows the *thinking* mascot with rotating status lines; cancel aborts the request (server aborts the Replicate prediction via `POST /predictions/{id}/cancel`).

## Coach chat
- `POST /v1/coach/messages` → Server-Sent Events stream of tokens; client renders word-by-word, mascot `talking` until `done`.
- Context sent: active goal, today's tasks + completion state, last 20 messages, streak. Journal only if the user opted in.
- Tool-like suggestions: the model may append one fenced JSON block `{"suggestTask": {...}}`; server strips it from the visible text and sends it as a separate SSE event `suggestion` → client renders the "Add to plan" card after the stream ends.
- Persona: warm, direct, short (≤ 90 words unless asked), never shames missed days, always ends with one concrete next step.

### Coaching styles
The coach's voice line comes from `server/src/coach/styles.ts`: **Balanced** (free, the default), **Strict**, **Playful** and **Efficient** (Plus). Only the voice changes; every style keeps the shared rules (never shame a missed day, one concrete next step, general guidance only on health, money and legal topics), and crisis messages always get the fixed supportive reply. Without an active Plus entitlement the server uses Balanced whatever the user picked.

## Safety + cost guardrails
- Per-user quotas (Redis token bucket): free 30 coach messages/day, 5 plan generations/day; Plus higher. Return `429 ai_quota` with reset time; client shows a friendly limit message.
- Max tokens: plan 1,500, coach 600, nudges 120.
- Input moderation on user text before it reaches the model; refuse self-harm content with a crisis-resources reply (localized hotlines by country).
- Log prompts/outputs **without** PII for 30 days for quality review, opt-out respected; never use journal text in logs.
- Evals: keep 50 golden goals (fitness, study, language, career, money, creative) with expected properties (task count, no task during busy blocks, deadline untouched). Run on every model or prompt change.

## Image generation
- Goal cover art: generated once per goal from the goal *category* + chosen style keywords, never from the raw goal text (privacy, and avoids odd outputs). Cache in object storage, CDN-served; regenerate on request (rate limited).
- Fixed style prompt suffix for consistency, e.g. "soft risograph texture, warm muted palette, simple shapes, no text, no people's faces".
- Moderate output before saving. Fallback: one of 12 bundled static covers per category.
