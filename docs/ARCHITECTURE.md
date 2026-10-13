# Architecture

## System
```
iOS (SwiftUI, SwiftData)         Android (Compose, Room)
        │  HTTPS + JSON (REST)      │
        │  WSS (chat, game, presence)
        ▼                           ▼
   ┌──────────────── Fly.io ─────────────────┐
   │ api (Fastify, stateless, N machines)    │──► Replicate (LLM + image), server-side only
   │ realtime (ws, sticky by alliance id)    │──► RevenueCat webhooks
   │ worker (jobs: push, moderation, scores) │──► APNs / FCM
   └───────┬──────────────┬──────────────────┘──► PostHog (server events)
       Postgres 16      Redis (pub/sub, rate limits, game state, job queue via BullMQ)
       (Fly Postgres or managed: Neon/Supabase-PG/Crunchy)
   Object storage: Tigris (S3 API) for avatars, goal covers, chat media
```
- **Regions:** primary `iad` (US). Add `fra` or `ams` read replica + compute for EU/UK, `jnb` for Africa, `sin` for Asia. Writes go to primary via Fly replay header. If EU data residency becomes a contractual need, run a separate EU primary and route by account region.
- **One API version prefix:** `/v1`. Clients send `X-App-Version` and `X-Platform`; server can return `426` with a minimum version to force upgrade.

## Auth
- Apple and Google: client gets an ID token natively, server verifies against Apple/Google JWKS (`jose`), upserts `auth_identities`.
- Email + password: argon2id (memory 64MB, t=3), email verification, rate-limited reset links (15 min TTL, single use).
- Sessions: 15-min access JWT (EdDSA) + rotating refresh token (opaque, hashed in DB, 60-day sliding, reuse detection revokes the family).
- Apple "Hide My Email": treat relay address as the email. Handle Apple's server-to-server revocation notifications (delete identity).
- Account linking: same verified email across providers links only after the user confirms by signing in to the existing account.
- **Account deletion** (in-app, required by both stores): soft-delete immediately (sign out everywhere, hide content), hard-delete after 30 days by worker; revoke Apple token; cancel nothing in stores (tell user to cancel subscription, link to store management).

## Data model (summary; full SQL in `server/db/001_init.sql`)
- `users`, `auth_identities`, `sessions`, `devices` (push tokens, platform, locale, timezone)
- `goals` (≤3 active free; `source_alliance_id` when created from a shared plan)
- `tasks` (`origin`: ai | user | shared; `shared_task_id` links to lead's task)
- `task_completions` (unique per task + `local_date`)
- `journal_entries` (unique per user + goal + `local_date`; body encrypted at rest with per-user DEK)
- `alliances` (`plan_mode`: own | shared, `cap`, `invite_code`), `alliance_members` (`goal_id`, `role`, `joined_at`, `start_date`, `status`)
- `shared_plan_tasks` (versioned; lead edits create a new version effective next local morning per member)
- `alliance_messages`, `community_posts`, `reactions`, `reports`, `blocks`
- `game_sessions`, `game_players`, `alliance_scores_weekly`
- `entitlements` (mirror of RevenueCat), `ai_usage` (quota)
- Signal: `signal_identity_keys`, `signal_prekeys`, `dm_envelopes` (opaque ciphertext)
- `changes` (per-user change log with monotonically increasing `server_seq`, used by sync pull)

All ids are **UUIDv7, generated on the client** for user-created rows so offline creates never need a round trip.

## Offline-first sync
Scope offline: goals, tasks, completions, journal, settings. Online-only: community, alliance chat (cached read-only), game, DMs send (queued), AI.

**Client**
- Local DB is the source of truth for UI (SwiftData / Room). UI never waits on network.
- Every mutation writes the row **and** an `outbox` record `{id, entity, entityId, op, patch, clientTs, attempt}` in the same transaction.
- `SyncEngine` drains the outbox on: app foreground, network regained, after each mutation (debounced 1.5s), background task (BGAppRefreshTask / WorkManager periodic 15 min, constraints: network).
- Pull: `GET /v1/sync?since=<server_seq>` returns changed rows; apply in a transaction; store new cursor.

**Server**
- `POST /v1/sync/push` accepts a batch (≤200), idempotent by outbox `id` (stored 7 days).
- Conflict rules:
  - Task fields: last-writer-wins **per field** using server-receipt order; deletes win over edits.
  - Completions: set semantics (`complete` / `uncomplete` per task+date); uncomplete only if newer than complete.
  - Journal: optimistic `version`. On conflict the server keeps both: newer becomes current, older stored as `conflict_of`. Client shows "Two versions of this entry" with pick/merge.
  - Shared-plan tasks: members can't edit lead's task fields (server rejects with `403 shared_task_readonly`; client hides edit controls). Members may add personal tasks to a shared goal.
- **Dates:** a "day" is the user's local calendar date (`local_date`), computed on device from the device timezone at completion time and sent explicitly. Store the user's `home_timezone` for server jobs (reminders, streak cutoffs). Streak day closes at local 03:00, not midnight, so late-night completions count for the day the user perceives.

## Realtime
- One WebSocket per app session: `wss://…/v1/rt`, auth with access token, resubscribe on reconnect with last event id per channel.
- Channels: `alliance:{id}` (chat, presence, game unlock, member progress), `game:{id}`, `user:{id}` (DM envelopes, notifications).
- Redis pub/sub fans out between machines. Messages persisted first, then published (at-least-once; clients dedupe by message id).
- Backoff: 1s → 2s → 5s → 15s → 30s cap with jitter; pause when backgrounded; rely on push while backgrounded.

## Game Night (server-authoritative)
- Unlock check runs on every completion for members of alliances on that goal: all `active` members have finished today's tasks for **that alliance's goal** (per their own `local_date` and their own day index for late joiners). `away` members (opted out today, or inactive 3+ days) are excluded; at least 4 eligible members required.
- Session: lead or any member opens lobby; starts at ≥4 ready players or 60s timeout. Server picks Drifter with crypto RNG, sends role only to that player.
- Tick 1s server-side for 15s; plank state lives in Redis; inputs are intents (`repair i`, `loosen i`) validated server-side (rate limit 4/s). Clue generation is server-side.
- Vote phase 20s; ties → nobody ejected → Drifter wins. Disconnect >10s → player marked absent, game continues (min 3). Results persisted to `game_sessions`; Fun points added.
- One game per alliance per local day counts for ranking (replays are just for fun).

## Rankings
Weekly season (Mon 00:00 alliance-majority timezone). Per alliance, normalized per active member:
- **Productivity:** completed tasks / scheduled tasks for the alliance goal.
- **Efficiency:** share of tasks completed within their scheduled time window, plus streak continuity.
- **Fun:** game nights played (cap 7/week), participation rate, chat activity (capped to avoid spam farming), reactions.
- Score = 0.45·Productivity + 0.35·Efficiency + 0.20·Fun, Bayesian-smoothed toward the global mean for alliances with < 5 members or < 3 active days, so tiny alliances can't top boards by luck.
- Leaderboards by goal category and size band (2–5, 6–15, 16+).

## Alliances, shared plans and pace
- `plan_mode = shared`: on join, server creates a `goal` for the member with `source_alliance_id`, the lead's `deadline`, and tasks mirrored from the current `shared_plan_tasks` version. Member `start_date` = join date; their day index counts from their own start. The group's day index counts from the alliance start. Streaks are per member; the alliance streak counts days where all active members (who had joined by that day) finished.
- Lead edits: new version; members receive it at their next local 04:00 (never mid-day, so today's checklist doesn't change under them). Lead can push "apply now" for corrections, which updates only incomplete tasks.
- Lead leaves: ownership transfers to the longest-standing member (prompt lead to choose). Lead deletes alliance: member goals become personal goals (detached), nothing is deleted.
- Deadline passes: alliance goes to "complete" state with recap; members can extend personally (detaches) or the lead can extend for all.
- Cap: free 15 (including lead). `alliance_plus` entitlement on the **owner** raises cap to 50. If the owner lapses, existing members stay but no new joins above 15.
- Free goal limit 3 active. Joining a shared-plan alliance creates a goal, so it is blocked at the limit with "Archive one first, or upgrade."

## Notifications
- **Local (device-scheduled):** daily reminder, per-task time reminders, evening journal nudge. Re-scheduled after every sync and on timezone change. iOS limit 64 pending: schedule the next 3 days only.
- **Server push (APNs/FCM):** alliance chat mentions, game night unlocked, DMs (content-free payload: "New message"; decrypt via Notification Service Extension on iOS / FCM data message on Android), streak at risk (only if the server has no completion for today by user's local 20:00 *and* the device hasn't synced in 2h; client cancels its own local streak reminder when tasks are done).
- Quiet hours from settings; collapse keys per alliance to avoid floods; max 3 social pushes/day by default.
- Android 13+: request `POST_NOTIFICATIONS` after the first plan is created (value moment), not on launch. iOS: provisional authorization first, full prompt after day 1.

## E2E DMs
- **License note:** `libsignal` is **AGPL-3.0**. Shipping it in a closed-source app requires either open-sourcing the client or a commercial arrangement. Get legal sign-off. Alternatives with permissive licenses: **vodozemac** (Matrix Olm/Megolm, Apache-2.0) or **OpenMLS** (MIT). Architecture below fits either.
- Each device has an identity key; server stores public prekey bundles only. Sessions per device pair; multi-device fan-out (encrypt to each recipient device).
- Server stores only `dm_envelopes` (ciphertext, recipient device, created_at), deleted on ack or after 30 days.
- Local message store encrypted (SQLCipher on Android, Data Protection Complete + Keychain key on iOS).
- Safety numbers screen; key change → inline warning in thread.
- Reporting: reporter's device decrypts and submits selected messages with a signed attestation; server can't moderate DMs otherwise (say so in the privacy policy).
- Minors: DMs off by default for users under 18; adult ↔ minor DMs disabled unless both are in the same shared-plan alliance led by a verified educator account (or keep fully disabled for v1).
- Alliance chat is **not** E2E in v1 (needs AI moderation and server history for late joiners). State this clearly in UI copy.

## Moderation
- Community posts, alliance chat, alliance names, avatars, goal covers → moderation job before or right after publish (text: fast LLM classification; images: image moderation model). Severity: allow / soft-hide pending review / block.
- Reports + blocks on every user-generated surface (App Store guideline 1.2). Blocked users disappear from feeds, chats and game lobbies for the blocker.
- Admin console (internal web) for the report queue; actions: hide, warn, suspend, ban device.

## Payments (RevenueCat)
- Entitlements: `plus` (more than 3 goals, more alliances features) and `alliance_plus` (cap 50, bigger game lobbies, seasonal leaderboards). Products: monthly + annual; 7-day trial on first purchase.
- App identifies RevenueCat user with our `user_id` after sign-in. Server trusts **webhooks**, not the client; mirror into `entitlements`. Client uses RevenueCat SDK cache for instant UI.
- Restore purchases button on paywall and in settings. Handle grace period / billing retry: keep access, show banner.
- Regional pricing per store; local currency formatting from the SDK, never hard-coded "$3.99".

## Analytics (PostHog)
- EU/UK users → PostHog EU cloud; others → US cloud (choose host at runtime from account region).
- Consent: EU/UK opt-in before any non-essential event; elsewhere opt-out in settings. Respect iOS ATT (we don't track across apps, so no ATT prompt needed; don't use IDFA).
- **Never** send goal text, task titles, journal content, chat or DM content. Events carry ids, counts and enums only.
- Core events: `onboarding_step`, `plan_generated` (latency, retries), `plan_edited`, `task_completed`, `day_completed`, `journal_saved`, `coach_message_sent`, `community_unlocked`, `alliance_created` (mode), `alliance_joined` (late_days), `game_played`, `paywall_viewed` (trigger), `purchase_started/completed`.

## Compliance (launch regions)
- **GDPR / UK GDPR:** lawful basis per data type, DPA with every processor (Replicate, Fly, PostHog, RevenueCat), export (JSON) and delete in-app, EU representative if no EU entity.
- **Children:** age gate at sign-up. Under 13 (US COPPA) → block or require verified parental consent. EU varies (13–16) → apply 16 unless country table says lower. Teens: no public Community profile by default, DMs off. Teacher/class alliances are the main path for minors, so treat that as a designed flow, not an accident.
- **Africa:** Nigeria NDPA, Kenya DPA, South Africa POPIA (registration/notification duties). **Asia:** India DPDP Act, Singapore PDPA, Japan APPI. Keep a country config table for age of consent and notice text.
- Journal = sensitive personal data: encrypted at rest with per-user key; not used for AI training; excluded from AI prompts unless the user opts in ("Let my coach read my journal").
- AI disclosure: label AI-generated plans and coach replies; keep a "Report a reply" action.

## Engineering practices
- **iOS:** Swift 6 strict concurrency, SwiftUI + Observation, SwiftData, async/await networking (URLSession), Keychain for tokens, String Catalogs for localization, XcodeGen project, SwiftLint, snapshot tests (swift-snapshot-testing), XCUITest for golden paths.
- **Android:** Kotlin 2, Compose + Material 3 (themed with our tokens, not default M3 colors), Navigation Compose type-safe routes, Hilt, Room, WorkManager, DataStore, Retrofit/OkHttp or Ktor, EncryptedSharedPreferences/Keystore, Paparazzi screenshot tests, baseline profiles.
- **Server:** TypeScript strict, zod at every boundary, OpenAPI generated from zod schemas → generate Swift/Kotlin clients, Postgres migrations (node-pg-migrate), structured logs (pino) with request ids, OpenTelemetry traces, Sentry on all three apps.
- **CI:** GitHub Actions: lint, unit, snapshot, build; Fastlane for TestFlight + Play internal track; staged rollouts (1% → 10% → 50% → 100%) with crash-free gate ≥ 99.5%.
- **Feature flags:** PostHog flags for Community, Game Night, E2E DMs, paywall copy.
- **Accessibility:** VoiceOver/TalkBack labels on all controls, Dynamic Type/font scale to 200% without truncating critical text, contrast ≥ 4.5:1 (tokens already pass for body text), Reduce Motion respected, haptics optional.
- **Localization:** all copy in string catalogs; RTL-ready layouts (leading/trailing, not left/right); locale-aware dates and plural rules. Ship English first; plan for French, Portuguese, Spanish, Hindi, Swahili given launch regions.
