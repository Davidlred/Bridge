# Server (Node + TypeScript)

```
cp .env.example .env            # then: npm run keygen  → paste into JWT_PRIVATE_KEY_PEM
docker compose up -d            # Postgres 16 + Redis 7 (or point DATABASE_URL at a local Postgres)
npm i && npm run migrate && npm run dev
```
Tests need a real Postgres and **wipe the database they are pointed at**:
```
TEST_DATABASE_URL=postgres://bridge:bridge@localhost:5432/bridge_test npm test
```
Without `TEST_DATABASE_URL` only the unit tests run.

Deploy: `fly launch --no-deploy`, `fly secrets set …` (every key in `.env.example`), `fly deploy`. Migrations run as the release command (`node dist/migrate.js`).

## Layout
- `src/app.ts`: Fastify app (error handler, rate limit, forced-upgrade check, AI plan route, alliance preview). `src/index.ts` listens and runs housekeeping.
- `src/auth/`: `tokens.ts` (EdDSA access JWT, rotating refresh tokens with reuse detection, `auth` hook), `oidc.ts` (Apple/Google ID-token verification), `routes.ts` (endpoints below).
- `src/sync/routes.ts`: `POST /v1/sync/push`, `GET /v1/sync?since=`.
- `src/ai/`: Replicate client, planner prompt, plan schema.
- `src/migrate.ts`: applies `db/*.sql` once each, in filename order. Add a new numbered file for every schema change.

## API (milestone 1)
| Route | Notes |
|---|---|
| `POST /v1/auth/email/signup` · `login` | argon2id. 5 wrong passwords → `429 too_many_attempts` for 15 min. `409 email_taken` / `email_linked {provider}`. |
| `POST /v1/auth/apple` · `google` | Apple is offered on iOS only, Google on Android only. Body `{idToken, nonce?, displayName?, timezone}`. Verified against the provider JWKS. Never links accounts by email silently. |
| `POST /v1/auth/refresh` | Rotates. Replaying an old refresh token revokes the whole family. |
| `POST /v1/auth/logout`, `GET /v1/me`, `DELETE /v1/account` | Delete = soft-delete now (signed out everywhere, email released), hard-delete after 30 days. |
| `POST /v1/auth/password/forgot` · `reset`, `GET /reset`, `GET /verify` | Single-use emailed links (reset 15 min, verify 24 h). |
| `POST /v1/sync/push` | ≤200 mutations `{id, entity, entityId, op, patch, clientTs}`; idempotent by `id`. Returns `{accepted, rejected:{id: code}}`. |
| `GET /v1/sync?since=` | Changed goals, tasks, completions, removals and settings since the cursor. |

## API (milestone 2)
| Route | Notes |
|---|---|
| `POST /v1/ai/plan` | Goal + deadline + routine (free text from onboarding, or the structured form) → tasks, label, feasibility. The deadline is never changed. |
| `POST /v1/ai/reality` | Feasibility only (`realistic` / `stretch` / `unrealistic`, reason, optional suggested date). Used when the deadline changes. 20/day. |
| `PUT /v1/me/photo` · `DELETE /v1/me/photo` | JPEG/PNG ≤ 6 MB, requires `X-Photo-Consent: 1` (the app shows the consent sheet first). Delete removes the photo **and every image made from it**. |
| `POST /v1/ai/images` · `GET /v1/ai/images/:goalId` | Starts / polls the "Future Self" and "If nothing changes" images. With a consented photo they are made from it (`MODEL_IMAGE_EDIT`); without one they are conceptual scenes with no faces (`MODEL_IMAGE`). 8/day. Retries only redo failed images. |
| `GET /v1/media/<key>?exp&sig` | Private files through signed links valid for an hour. |

Goals also sync `routine`, `realityNote` ("Why I started") and `feasibility`. Deleting a goal deletes its images; deleting the account deletes the photo and all images at once (the rest of the account after 30 days).

## API (milestone 3)
| Route | Notes |
|---|---|
| `POST /v1/coach/messages` | Server-Sent Events: `token` {text}… then `suggestion` {title, rationale, priority, timeOfDay}? then `done` (or `error`). Context: goals, today's tasks, streak, last 20 messages; the journal only if `settings.coachReadsJournal` is true. 30/day. Self-harm messages get a supportive reply with resources instead of the model. |
| `PATCH /v1/me` | `{coachName?, displayName?}`. Empty or offensive coach names become "Arc". |
| sync entity `journal` | `{goalId, localDate, mood, body, baseVersion, resolve?}`. Bodies are encrypted at rest with a per-user key wrapped by `JOURNAL_KEK`. A stale `baseVersion` (or a second entry for the same day) keeps both versions: the incoming one is current, the other is returned with `conflictOf` until the client sends `resolve: true`. |

Coach conversations are stored on the device, not the server.

Images are generated in the API process after the request returns. That is fine for one machine; move it to the worker queue (BullMQ) before running several.

Sync rules enforced here: per-field last-writer-wins for tasks, deletes win, completions are a set (an uncomplete only wins if newer), 3 active goals without `plus` (`goal_limit`), shared-plan tasks are read-only (`shared_task_readonly`), the goal deadline is only ever what the client sent.

## Before launch
- **Email provider**: `src/mailer.ts` prints links in development and throws in production. Wire a transactional sender (Resend, Postmark, SES).
- **Apple token revocation** on account deletion needs the Sign in with Apple private key (not configured yet).
- **Age gate** at sign-up is waiting on the per-country age-of-consent table (open decision in `CLAUDE.md`).

Storage: set `S3_BUCKET` (+ `S3_ENDPOINT`, credentials) in production, e.g. Fly Tigris. Without it files go to `STORAGE_DIR` on local disk.

Next to build: journal sync (per-user encryption key), coach SSE route, alliances create/join/leave + shared-plan versioning, WebSocket realtime + game engine, RevenueCat webhook, moderation + push workers, account export.
