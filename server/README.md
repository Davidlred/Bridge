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

Sync rules enforced here: per-field last-writer-wins for tasks, deletes win, completions are a set (an uncomplete only wins if newer), 3 active goals without `plus` (`goal_limit`), shared-plan tasks are read-only (`shared_task_readonly`), the goal deadline is only ever what the client sent.

## Before launch
- **Email provider**: `src/mailer.ts` prints links in development and throws in production. Wire a transactional sender (Resend, Postmark, SES).
- **Apple token revocation** on account deletion needs the Sign in with Apple private key (not configured yet).
- **Age gate** at sign-up is waiting on the per-country age-of-consent table (open decision in `CLAUDE.md`).

Next to build: journal sync (per-user encryption key), coach SSE route, alliances create/join/leave + shared-plan versioning, WebSocket realtime + game engine, RevenueCat webhook, moderation + push workers, account export.
