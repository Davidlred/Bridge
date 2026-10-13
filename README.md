# Handoff: The Bridge — native iOS + Android

## Overview
The Bridge turns a goal into a daily plan. An AI coach drafts the plan against a deadline the user picks, the user works it day by day, journals each evening, and does it alongside people with the same goal (goal-specific Community, private Alliances, Game Night, encrypted DMs).

Decisions locked for v1:
- **Clients:** SwiftUI (iOS 17+) and Jetpack Compose (Android 10 / API 29+). Two native codebases, shared design tokens.
- **Backend:** Node 20 + TypeScript (Fastify), Postgres 16, Redis for realtime fan-out. Hosted on Fly.io.
- **AI:** Replicate, server-side only. Frontier Claude model for planning and coach; image model for goal cover art. See `docs/AI.md`.
- **Payments:** RevenueCat. **Analytics:** PostHog (EU cloud for EU/UK users). **E2E DMs:** Signal protocol (read the license note in `docs/ARCHITECTURE.md` first).
- **Sign-in:** Apple, Google, email + password. **Reminders:** local + server push. **Moderation:** AI + user reports.
- **Offline-first:** tasks, completions and journal work fully offline and sync later.
- **Launch regions:** US, EU, UK, Africa, Asia.

## About the design files
`design/The Bridge v4.dc.html` is a **design reference built in HTML**: a clickable prototype showing intended look, copy and behavior. It is not production code. Recreate it natively in SwiftUI and Compose using platform conventions. Open the file in a browser; the left panel jumps to every screen and edge state, and the Tweaks panel switches Light/Dark and iOS/Android chrome.

## Fidelity
**High-fidelity.** Colors, type, spacing, copy and motion are final. Match them. Where a platform convention conflicts (back gesture, sheet detents, system share sheet, haptics), follow the platform.

## Package contents
```
README.md                 this file: screens, tokens, motion, build order
docs/ARCHITECTURE.md      system design, data model, sync, realtime, E2E, payments, compliance
docs/AI.md                models, prompts, output schemas, cost + safety guardrails
docs/EDGE_CASES.md        edge cases by feature, with expected behavior
tokens/tokens.json        single source of truth for color/type/radius/motion
server/                   Fastify + Postgres scaffold (auth hook, AI plan route, alliance preview, SQL schema)
ios/                      SwiftUI scaffold (XcodeGen project.yml, theme, navigation, SwiftData models, sync engine, animated coach)
android/                  Compose scaffold (Gradle, theme, type-safe nav, Room entities, WorkManager sync, animated coach)
design/                   the HTML prototype + assets
```

## Build order (milestones)
1. **Foundation:** tokens, fonts, theme, navigation shell, auth (Apple/Google/email), account deletion, local DB + outbox sync.
2. **Goals core:** onboarding → AI plan → review → Today; multiple goals (max 3 free); task CRUD; Reality Check; local reminders.
3. **Coach + Journal:** streaming coach chat with mascot states; Study Mode; journal loop after finishing the day.
4. **Social:** goal-scoped Community (unlock at 7-day streak), Alliances (own plan / shared plan), alliance chat, moderation, push.
5. **Game Night, rankings, Hall of Fame.**
6. **E2E DMs** (Signal protocol), **paywall** (RevenueCat), analytics, store submission.

Ship 1–3 to TestFlight / internal track before starting 4.

## Navigation
Bottom tabs: **Today · Coach · Journal · Profile**. Community is **not** a tab: it lives inside each goal (goal-specific), reached from the goal card's detail.
- Today → goal card (one card per goal) → Goal detail: tasks, Reality Check, **Community** (Feed / Alliance segments), Study Mode.
- Alliance → chat, Game Night, rankings, members, invite.
- Profile → Hall of Fame, notifications, settings, encrypted messages (DM inbox), subscription.
- Pushed screens slide in from the right (iOS push / Android shared-axis X). Sheets rise from the bottom with a grabber.

## Screens
All sizes in pt/dp. Screen side padding **20**. Section gap **18**. Card padding **16**. Minimum hit target **44**.

### Onboarding
- **Splash:** brand mark centered on `bg`. Fade to Welcome after first-launch checks (≤1.2s; never block on network).
- **Welcome:** static, not interactive beyond the two buttons. Full-width image card (radius 30, 1.5 border `line`, no dashed frame) filling the space above the copy, using `assets/welcome-mark.png`. Title Bricolage 600 30/1.1. Buttons: primary "Create account" (54h, radius 16, `acc` bg, `on-acc` text, Bricolage 600 16), secondary "Log in" (48h, radius 14, 1px `line`).
- **Sign up / Log in:** Apple button first (required by App Review when Google is offered), Google, then email + password. Inline field errors in `bad`, 13px Geist.
- **1 · Goal:** free-text goal field. **The field never asks for a date.** The deadline is chosen only in the "Target date" picker below it. If the user types a date in the text, the picker value wins and the text is sent to the AI as-is (the model is instructed not to override the picker).
- **2 · Routine:** wake/sleep times, busy blocks, preferred task times (chips).
- **3 · Photo (optional):** user photo for profile. Skip is visible and secondary.
- **Generating:** coach mascot in *thinking* state, rotating status lines. Cancel returns to step 1 with input preserved.
- **Review plan:** AI tasks as cards (title, priority pill, time, rationale). Each card: keep / edit / remove. "Add task" row. Primary "Start my plan".
- **Generation fails:** keep inputs, offer "Try again" and "Write my own tasks" (empty plan with 3 blank rows).

### Today (home)
- Header: greeting + date (Geist Mono 10.5 uppercase `mut`).
- **One card per active goal** (max 3 free). Card bg rotates `gc1/gc2/gc3`, radius 24, padding 20, `gc-ink` text. Shows goal label, day X of Y, today's progress bar (`gc-track` track), streak. Tapping opens Goal detail; goal content is only visible inside the card.
- "Add a goal" tile (dashed `line2`). At the limit → paywall/limit sheet.

### Goal detail
- Goal statement, deadline, Reality Check entry, today's task list, Community entry, Study Mode entry.
- **Task row:** checkbox (24, radius 8), title Geist 500 15, meta row = **priority pill** + time. Priority pill: 22h, radius 11, dot 6, Geist 600 11. Colors: High `p-hi` on `p-hi-t`, Medium `p-md` on `p-md-t`, Low `p-lo` on `p-lo-t`. Done tasks: title `mut`, strike-through.
- **Finishing the last task** → celebration → "Lock it in" prompts the journal. The journal screen has a back button; on return the "Lock it in" tab shows *Journaled ✓* for today.
- **Reality Check:** AI feasibility verdict (realistic / stretch / unrealistic) with reason and optional suggested deadline. The user decides; never auto-change the deadline.
- **Edit goal & deadline:** changing the deadline re-runs Reality Check, not the whole plan, unless the user asks.

### Coach
- Header (centered): **animated coach mascot** 112pt, name (default "Arc", user-renamable) Bricolage 600 21, status line with 6pt status dot, context label (Geist Mono 9.5).
- Mascot states: `idle` (breathe 3.2s, blink every 4.2s) · `listening` (user typing; eyes shift up 4) · `thinking` (awaiting first token; tilt ±5° 1.6s; eyes up-right) · `talking` (streaming; mouth open/close 0.24s; breathe 1.1s) · `happy` (suggested task added; squint eyes, smile, 2 hops of 14). Reference implementations: `ios/Bridge/Features/Coach/CoachMascot.swift`, `android/.../feature/coach/CoachMascot.kt`.
- Status copy: "Here when you need me" / "Listening…" / "Thinking it through…" / "Talking" / "Added. Nice one." Dot colors: `mut2` / `acc` / `warn` / `ok` / `ok`.
- Replies stream word-by-word. A suggestion card ("Add to plan") appears only after the stream finishes.
- **Study Mode:** full-screen focus timer for one task, with a mini 34pt mascot in the bottom chat strip.

### Journal
- Today's entry: mood (5 options), free text, autosave (states: idle / saving / saved / offline-saved).
- History: by day, filter by goal.

### Community (inside a goal)
- Segments: **Feed** / **Alliance** (segmented control, `acc` fill on active).
- **Locked** until a 7-day streak on that goal. A preview is allowed ("See what it looks like").
- Feed: posts from people with the same goal category. Sharing level follows Privacy setting (Nothing / Streaks only / Streaks & milestones).

### Alliances
- A user can be in **multiple alliances**, including several for the same goal. Alliance switcher: horizontal chips (40h, radius 20, `white-space: nowrap`), active chip `acc` bg. Trailing chips "New" and "Join" (dashed `line2`).
- **Create sheet:** name, plan type radio:
  - *Everyone keeps their own plan*: members share a goal category, each keeps their own tasks/deadline.
  - *One shared plan*: joiners receive the creator's goal, deadline and daily tasks. Only the creator (lead) edits tasks; edits reach members the next local morning.
- **Join with a code** → **invite preview** (shared plan): lead, members/spots left, goal, due date, daily tasks, and the pace note: late joiners start at day 1 with the same deadline; days before joining don't count against them or the alliance streak.
- Alliance card: "INVITE ONLY" chip, streak, members, Shared Plan panel ("SHARED PLAN" left / "Set by …" right; You vs Group progress bars), Game Night card, rankings link, chat.
- **Cap:** 15 members free. Above 15 → **Alliance+** paywall ($3.99/mo, 7-day free trial; "Stay at 15" secondary).
- **Rankings:** Fun, Efficiency, Productivity (definitions in `docs/ARCHITECTURE.md`).

### Game Night — "The Drifter"
- Unlocks for an alliance when **every active member has finished today's tasks for that alliance's goal** (other goals don't count).
- ~2 minutes, 4–15 players. One hidden **Drifter**, the rest **Builders**. Flow: lobby → role reveal (tap to reveal) → 15s round on an 8-plank bridge (Builders repair cracked planks, Drifter loosens solid ones; clue feed shows who was nearby) → council vote → result (votes tally, who the Drifter was, MVP, +Fun) → share to alliance chat / play again.
- Server-authoritative. Clients never learn the Drifter's identity unless they are the Drifter or the game has ended.

### Encrypted messages (DMs)
- 1:1 end-to-end encrypted. Lock indicator + "Messages are end-to-end encrypted" header note. Safety-number screen per contact. Report forwards selected decrypted messages from the reporter's device.

### Profile & settings
- Hall of Fame (badges grid 3 columns; public toggle), notifications list, settings: Daily reminder time, Streak-at-risk, Privacy (Community sharing, Public Hall of Fame), Accessibility (Reduce motion), Account (Log out, Delete account — in-app, required by both stores).

### System states (all must exist)
All tasks done · Day 1 empty states · AI generation fails · Photo upload fails · Chat send fails (retry inline) · Session expired (re-auth sheet, unsynced data kept) · Offline banner · Goal limit reached · Already in alliance · Alliance full.

## Interactions & motion
| Name | Duration / easing | Use |
|---|---|---|
| fade | 200ms ease-out | tab content, state swaps |
| push | 320ms cubic(.2,.8,.2,1) | pushed screens (use native transitions) |
| sheet | 300ms cubic(.2,.8,.2,1) | bottom sheets (native detents) |
| toast | 220ms ease-out, 2.6s hold | confirmations, bottom-center above tab bar |
| check pop | 1 → 1.3 → 1, 280ms | task completion + light haptic |
| streak glow | 1 → 1.08 → 1, 600ms | streak increment + success haptic |
| mascot | see Coach | coach states |

Reduce Motion (system setting or in-app toggle) disables mascot animation, pops and slides (cross-fade only).

## State (client)
- `session`: signedOut / onboarding(step) / ready / expired.
- `goals[]` (≤3 active free): id, label, statement, startDate, deadline, status, sourceAllianceId?, tasks[], today's completions.
- `activeGoalId`, `journal[goalId][localDate]`, `alliances[]` (id, goalId, planMode, sharedPlan?, members, cap, streak, inviteCode), `activeAllianceId` per goal.
- `coach`: messages[], streaming message id, mood (derived: typing → listening, awaiting → thinking, streaming → talking, card added → happy for 1.3s).
- `outbox[]`: pending mutations (see sync).
- `entitlements`: from RevenueCat (`plus`, `alliance_plus`).

## Design tokens
Full set in `tokens/tokens.json`. Key values:

| Token | Light | Dark |
|---|---|---|
| bg | #FBFAF7 | #0C0C0C |
| surface (s1) | #FFFFFF | #151515 |
| ink / ink2 | #23262B / #4A4E55 | #F2F2F2 / #C8C8C8 |
| muted / muted2 | #7A7D83 / #A7A9AD | #8C8C8C / #5E5E5E |
| line / line2 | #E3E0D8 / #D6D2C8 | white 12% / 22% |
| acc / on-acc | #2F3A33 / #FFFFFF | #E8643A / #1A0C06 |
| acc-ink | #4F8A3A | #F08A62 |
| ok / bad / warn | #4F8A3A / #D2553F / #B8741C | #8FD17A / #E5533D / #E9A23B |
| priority hi / md / lo | #B9452E / #8F6410 / #3F7262 | #F2937A / #E9BC5C / #8DBFAE |
| goal cards gc1/gc2/gc3 | #FCE7D9 / #E4F2D9 / #DFECF6 | #7E3A20 / #24564F / #5A4A1C |
| mascot body / ink / cheek | #F5BE98 / #23262B / #E07A62 | #E8875A / #1A0C06 / #B83A22 |

**Type:** Display Bricolage Grotesque 600 (30, 25, 22, 21, 20). Body Geist 400/500/600 (16, 15, 14, 13, 12). Labels Geist Mono 500 (11, 10, 9.5), uppercase, tracking 0.12–0.14em. Support Dynamic Type / font scale: map sizes to text styles and cap display sizes at 1.3×.
**Radii:** 8 (checkbox), 11 (pill), 14 (secondary button, input), 16 (primary button, inner card), 20 (card, chip), 24 (goal card), 30 (hero image).
**Borders:** 1px `line` default, 1.5px for emphasis/selected (`acc`). No drop shadows; depth comes from surface color.
**Dark mode texture:** subtle grain overlay on `bg` (dark only).

## Assets
- `design/assets/welcome-mark.png`: Welcome image (to be replaced by your final art; keep 30 radius card).
- Fonts (SIL OFL, bundle in both apps): Bricolage Grotesque, Geist, Geist Mono.
- Icons: Material Symbols Outlined (Apache 2.0). Android: native. iOS: bundle the variable font, or map to SF Symbols if you prefer native feel; keep one set per platform.
- Goal cover art: generated server-side (see `docs/AI.md`), cached in object storage.

## Files
- `design/The Bridge v4.dc.html`: full prototype (open in a browser; screens panel on the left).
- `design/support.js`, `design/image-slot.js`: prototype runtime only, not for production.
#   B r i d g e  
 