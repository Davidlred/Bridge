# Edge cases

Format: **case** → expected behavior.

## Onboarding & auth
- **No network on first launch** → Welcome and sign-up screens render; submit shows "You're offline. We'll need a connection to create your account." Inputs kept.
- **Apple sign-in cancelled** → stay on screen, no error toast.
- **Apple returns no email (second sign-in)** → use stored identity; never require email for Apple users.
- **Email already used by a Google/Apple account** → "This email is linked to Google sign-in. Continue with Google?"
- **Wrong password ×5** → 15-min cooldown, offer reset.
- **Session expired while offline** → keep working locally; queue changes; prompt re-auth when back online; never drop the outbox.
- **App killed mid-onboarding** → resume at last completed step with inputs restored (persist drafts locally).
- **Goal text contains a date that conflicts with Target date** → Target date wins; Review shows a note from Reality Check.
- **Deadline in the past or today** → picker disallows; minimum is tomorrow. Deadline > 3 years → allowed with Reality Check "consider milestones".
- **Very short or nonsense goal** ("asdf") → coach asks one clarifying question instead of generating.
- **Harmful goal** (self-harm, extreme dieting, illegal) → refuse plan, show supportive message and resources.
- **AI generation fails / times out** → "Generation fails" state; "Try again" (keeps inputs) or "Write my own tasks".
- **User leaves Generating screen** → request continues server-side for 2 min; result available on return; push if backgrounded.
- **Under-age user** → age gate rules per country (see Architecture → Compliance).

## Goals & tasks
- **4th goal on free plan** → limit sheet: archive one or upgrade. Archived goals keep history.
- **All tasks deleted** → empty state with "Ask the coach" and "Add task".
- **Task completed after midnight** → counts toward the previous day until 03:00 local.
- **Timezone change (travel)** → day boundaries follow the device's current timezone; never lose or double a day; reminders rescheduled; if a day is skipped due to travel east, don't break the streak.
- **DST shift** → reminders fire at wall-clock time.
- **Same task completed on two devices offline** → completion set merges; one completion.
- **Edited on two devices offline** → per-field last-writer-wins; deletes win.
- **Deadline reached, goal unfinished** → recap screen: extend deadline, mark complete, or archive. No guilt copy.
- **Goal completed early** → celebration, Hall of Fame badge, suggest next goal.
- **Missed 3+ days** → gentle re-entry: "Pick up where you left off" with today's plan only; offer Reality Check to adjust.
- **Clock tampering** (device date moved forward to fake streaks) → server validates completion `local_date` against server receipt time ± 36h; outliers don't count toward public streaks.

## Coach & Study Mode
- **Stream drops mid-reply** → keep partial text, show "Reply interrupted · Retry".
- **Send fails** → message stays with retry inline ("Chat send fails" state).
- **Quota hit** → friendly limit message with reset time; Plus upsell is secondary.
- **User renames coach to empty / offensive name** → default "Arc"; name passes moderation.
- **Study Mode interrupted** (call, app backgrounded) → timer pauses after 10s in background; resume prompt.
- **Reduce Motion on** → mascot static (state still shown through face + status text).

## Journal
- **Back from journal without saving** → autosave already stored; "Lock it in" shows Journaled ✓ only if text or mood exists.
- **Edited on two devices** → both versions kept, user picks.
- **Very long entry** → 10,000 char cap with counter after 9,000.
- **Journal while offline** → saved locally, "Saved on this phone" badge until synced.

## Community
- **Streak < 7** → locked with preview; preview never shows real usernames to the user (sample data) or only first names.
- **Streak breaks after unlock** → stays unlocked.
- **Post reported** → hidden for the reporter immediately; queue for review; auto-hide at 3 independent reports.
- **Blocked user** → invisible across feed, alliances, game lobbies, DMs.
- **Empty feed for a niche goal** → widen to the parent category with a label.

## Alliances
- **Join code invalid / expired** → inline error. **Already a member** → "You're already in …". **Alliance full** → "This alliance is full (15/15)"; if owner has Alliance+, cap 50.
- **Join a shared plan at the 3-goal limit** → blocked with archive/upgrade.
- **Joining late** → invite preview shows the pace; member starts day 1, same deadline; pre-join days don't count.
- **Joining after the deadline passed** → code shows "This plan has ended."
- **Lead edits shared tasks mid-day** → applied next local morning; "apply now" only touches incomplete tasks.
- **Member tries to edit a shared task** → edit controls hidden; server rejects anyway. Members may add personal extras.
- **Lead leaves / deletes account** → transfer ownership; if no members, alliance archived. Member goals detach into personal goals.
- **Member inactive 3+ days** → marked away; excluded from game unlock and alliance streak; can rejoin by completing a day.
- **Member in multiple alliances for the same goal** → one completion counts for all of them.
- **Invite link opened without the app** → universal link / app link → store → deferred deep link restores the join preview after install.
- **Invite link opened while signed out** → sign in, then return to the preview.
- **Name collision** → alliance names aren't unique; invite code is the identity.

## Game Night
- **Not everyone finished** → card shows who's left (names, not tasks), "Nudge" sends one push per member per day.
- **Member finished tasks for another goal only** → doesn't count; only the alliance goal's tasks matter.
- **Fewer than 4 eligible** → "Game Night needs 4 players"; can still play practice mode (no ranking points).
- **Player disconnects** → 10s grace, then absent; game continues with ≥ 3.
- **Drifter disconnects** → Builders win automatically.
- **Vote tie** → no one ejected, Drifter wins.
- **App backgrounded mid-round** → treated as disconnect; push "Your game is still on" once.
- **Late completion after game started** → can join the next game; one ranked game per day.
- **Different timezones** → unlock evaluated per member's local day; game window open until the earliest member's local 23:59.

## DMs (E2E)
- **Recipient has no prekeys (old app)** → "This person needs to update the app to receive encrypted messages."
- **Identity key changed** → inline warning, verify safety number.
- **New device / reinstall** → old messages are not recoverable (say so before logout); optional encrypted backup is a v2 feature.
- **Push for DM** → content-free; decrypted on device.
- **Minor ↔ adult** → blocked per policy.

## Payments
- **Purchase pending (Ask to Buy, slow card)** → "Waiting for approval" state; no entitlement until webhook.
- **Restore on new device** → restores via store account.
- **Trial ends / lapses** → keep data; above-limit goals become read-only (user picks which 3 stay active); alliance over 15 keeps members but stops new joins.
- **Refund** → webhook removes entitlement; same downgrade rules.
- **Purchased on iOS, signed in on Android** → entitlement follows the account (RevenueCat app user id).

## Platform
- **Notifications denied** → in-app reminder banner on Today; settings deep link. Never re-prompt automatically.
- **Low storage** → photo upload fails gracefully ("Photo upload fails" state).
- **Large font sizes** → cards grow vertically; chips scroll horizontally; no truncated buttons.
- **Small screens (iPhone SE, 360dp Android)** → all screens scroll; primary action stays reachable above the keyboard.
- **Android back / predictive back** → closes sheet → pops screen → exits from a tab root only from Today.
- **Process death (Android)** → navigation and form drafts restored via SavedStateHandle.
- **Forced upgrade** → `426` → blocking "Update The Bridge" screen with store link; local data untouched.
