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

Coach conversations sync across devices (sync entity `chat`, encrypted like the journal). The coach route stores both the message and the reply, including a partial reply when the stream is interrupted.

## API (milestone 4)
| Route | Notes |
|---|---|
| `GET /v1/community?goalId&before` | Feed for the goal's category. Locked until a 7-day streak on that goal (computed here from completions, never trusted from the device); stays open after. A quiet category widens to everyone (`widened: true`). First names only. |
| `POST /v1/community/posts` | `{goalId, kind: streak\|milestone\|day_complete, value?}`. Respects `settings.share` (`nothing`, `streaks` = default, `all`): 204 when not shared. Streak claims above the real streak get `streak_mismatch`. One post per streak value / milestone / day. |
| `PUT\|DELETE /v1/community/posts/:id/reactions/:kind` | `respect`, `keepGoing`, `inspired`. |
| `POST /v1/reports` · `PUT\|DELETE /v1/blocks/:userId` | Reported items disappear for the reporter at once and for everyone after 3 reporters. Blocks hide both ways (feed, members, chat). |
| `POST /v1/alliances` | `{name, planMode: own\|shared, goalId}`. Needs Community open on that goal. Invite code `DAWN-4821`. Cap 15 (50 with the owner's `alliance_plus`). |
| `GET /v1/alliances` · `GET /v1/alliances/:id` | Members, `away` (no completion in 3 days), collective streak (days every active member who had joined did something), shared-plan progress, activity feed (only shared-plan task titles are named). |
| `GET /v1/alliances/preview/:code` · `POST /v1/alliances/join` | Errors: `invalid_code`, `already_member`, `alliance_full`, `plan_ended` (410), `goal_limit`, `goal_required` (own plan needs one of your goals). Joining a shared plan creates the goal (same deadline) and its read-only tasks; late joiners start at day 1. |
| `POST /v1/alliances/:id/leave` | A leaving lead hands over to the longest-standing member; leaving members keep the goal as a personal one. |
| `POST /v1/alliances/:id/apply-now` | Lead only. The lead's task edits otherwise reach members on their next local morning; this applies them now to tasks not yet ticked today. |
| `GET\|POST /v1/alliances/:id/messages` | Alliance chat (not end-to-end encrypted, `notEncrypted: true`). Idempotent by `clientId`. Slurs are refused (`message_blocked`); a fast model moderates just after posting and hides what it flags. `@name` mentions push, at most 3 a day per person. |
| `GET /v1/rt` (WebSocket) | First frame `{type:"auth", token}` (close 4401 if bad). Then `{type:"message", allianceId, message}` events for the user's alliances; send `{type:"subscribe"}` after joining one. Redis pub/sub carries events between machines. |
| `PUT /v1/devices` | `{id, platform, pushToken?, timezone?}`. Push goes through FCM v1 / APNs when their keys are set; otherwise it's skipped. |

Images are generated in the API process after the request returns. That is fine for one machine; move it to the worker queue (BullMQ) before running several.

Sync rules enforced here: per-field last-writer-wins for tasks, deletes win, completions are a set (an uncomplete only wins if newer), 3 active goals without `plus` (`goal_limit`); when `plus` ends (expiration or refund webhook, or the next pull) the goals beyond 3 become `paused`, keeping the 3 worked on most recently, and paused goals reject task, completion and field edits (`goal_paused`) until the user resumes them within the limit; shared-plan tasks are read-only (`shared_task_readonly`), the goal deadline is only ever what the client sent.

## Before launch
- **Email provider**: `src/mailer.ts` prints links in development and throws in production. Wire a transactional sender (Resend, Postmark, SES).
- **Apple token revocation** on account deletion needs the Sign in with Apple private key (not configured yet).
- **Age gate** at sign-up is waiting on the per-country age-of-consent table (open decision in `CLAUDE.md`).

Storage: set `S3_BUCKET` (+ `S3_ENDPOINT`, credentials) in production, e.g. Fly Tigris. Without it files go to `STORAGE_DIR` on local disk.

## API (milestone 5)
| Route | Notes |
|---|---|
| `GET /v1/alliances/:id/game` | Game card: `unlocked` (every active member finished today's tasks for the alliance goal; away members excluded), `finished`/`eligible`, `waiting` (first names, never tasks), `needsPlayers` (< 4 eligible → practice only), `rankedToday`, open lobby. |
| `POST /v1/alliances/:id/game` | Opens the lobby (or returns the open one). Ranked only when unlocked with 4+ eligible and no ranked game yet today. |
| WebSocket frames | `game:join`, `game:ready`, `game:begin`, `game:act {plank}`, `game:vote {target}`, `game:leave`. Server sends `game:state` (never roles before the result) and `game:role` to each player alone. Starts when 4+ are all ready, or at 60s with 3+. 15s round, 20s vote, ties eject nobody, 10s grace before absent, a missing Drifter loses. |
| `POST /v1/alliances/:id/games/:gameId/share` | Posts the result to the alliance chat once. |
| `POST /v1/alliances/:id/game/nudge` | One push per waiting member per day. |
| `GET /v1/alliances/:id/rating` · `GET /v1/alliances/:id/rankings` | Weekly score (productivity, efficiency, fun; formula in `src/social/rankings.ts`), tier, rank on the board for the same goal category and size band (2–5, 6–15, 16+). |
| `GET /v1/me/badges` | Hall of Fame: earned badges (kept once earned), progress toward streak and journal badges, `public` from `settings.badgesPublic`. |

Game state lives in the API process. With more than one machine, route each alliance's game to one machine or move the state to Redis (results are already in Postgres).

Next to build: RevenueCat webhook and paywall, E2E DMs, account export (milestone 6). Chat moderation and pushes run in the API process; move them to the worker queue with the image jobs.
