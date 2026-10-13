# CLAUDE.md — The Bridge

Native goal-coaching app. Read this first, then `README.md` (screens + tokens), `docs/ARCHITECTURE.md`, `docs/AI.md`, `docs/EDGE_CASES.md`.

## Repo layout
- `ios/`: SwiftUI, iOS 17+, Swift 6, SwiftData. Generate project with `cd ios && xcodegen`.
- `android/`: Jetpack Compose, minSdk 29, Kotlin 2, Hilt, Room, WorkManager.
- `server/`: Node 20 + TypeScript (Fastify), Postgres 16, Redis. `npm run dev`. Deploy on Fly.io.
- `tokens/tokens.json`: single source of truth for colors, type, radii, motion. Theme files in both apps mirror it; update all three together.
- `design/The Bridge v4.dc.html`: hi-fi HTML prototype. **Reference only, never ship or port its code.** Open in a browser; the left panel jumps to every screen and edge state.

## How to work
- Build in the milestone order in `README.md` → "Build order". Finish a milestone on both platforms before starting the next.
- For each screen: open it in the prototype, match layout/copy/colors exactly, use platform-native navigation, sheets, haptics and back behavior.
- Every screen needs: light + dark, Dynamic Type / font scale 200%, VoiceOver/TalkBack labels, Reduce Motion, loading, empty, error and offline states (`docs/EDGE_CASES.md`).
- Add snapshot tests (iOS swift-snapshot-testing, Android Paparazzi) for each screen in light, dark and largest text size.
- Copy lives in String Catalogs (iOS) and `strings.xml` (Android). No hard-coded user-facing strings.

## Non-negotiable rules
- **AI keys never in the apps.** All Replicate calls go through `server/`. Model ids come from env vars.
- **The user-picked deadline is authoritative.** The AI never changes it; it may only suggest one in Reality Check.
- **Offline-first:** UI reads/writes the local DB only. Every mutation writes the row + an outbox record in one transaction. Ids are client-generated UUIDv7.
- **Local day** = device calendar date with the day closing at 03:00 local. Send `local_date` explicitly; never derive it on the server.
- **Community is inside a goal**, not a tab. Tabs: Today, Coach, Journal, Profile.
- **Game Night unlock** counts only the tasks for that alliance's goal.
- **Shared-plan tasks are read-only for members**: hide edit controls and the server must reject edits.
- Never log or send to analytics: goal text, task titles, journal, chat or DM content.
- Free limits: 3 active goals, 15 alliance members. Entitlements come from RevenueCat webhooks on the server; the client SDK cache is only for instant UI.
- Accounts must be deletable in-app.

## Open decisions (ask before implementing)
- E2E DMs: `libsignal` is AGPL-3.0. Confirm licensing, or use vodozemac / OpenMLS instead.
- Exact Claude model version on Replicate (pin the newest available) and image model after a style bake-off.
- Age-of-consent table per launch country (US, EU, UK, Africa, Asia).

## Commands
- Server: `npm run typecheck`, `npm test`, `npm run migrate`.
- iOS: `xcodegen`, then build/test the `Bridge` scheme.
- Android: `./gradlew :app:assembleDebug`, `./gradlew test`.
