# Android (Jetpack Compose)

Requirements: Android Studio Ladybug+, JDK 17, minSdk 29 (Android 10).

Open the `android/` folder in Android Studio, or build from a terminal:
```
./gradlew :app:assembleDebug     # debug APK
./gradlew test                   # unit tests
```
Debug builds call `http://10.0.2.2:8080` (the dev server on your machine, from the emulator). For a phone on the same Wi-Fi: `-Pbridge.apiBase=http://<your-ip>:8080`.
Google sign-in needs the OAuth **web** client id: `-Pbridge.googleWebClientId=…` (or in `~/.gradle/gradle.properties`). Without it the button reports that Google sign-in is unavailable. Android offers Google and email sign-in only; Sign in with Apple is on iOS.

Purchases need the RevenueCat public SDK key: `-Pbridge.revenuecatKey=goog_…`. Without it the paywall still shows but buying is switched off.
Encrypted DMs use the Rust library in `../crypto`: Gradle builds it for each ABI, which needs rustup (targets aarch64-linux-android, armv7-linux-androideabi, x86_64-linux-android), `cargo install cargo-ndk --version 3.5.4` and the NDK.

Fonts are bundled as variable fonts in `app/src/main/res/font/` (Bricolage Grotesque, Geist, Geist Mono; SIL OFL). Tab icons are filled Material icons; swap in Material Symbols Outlined exports for the unselected state when the icon set is finalised.

Library versions in `gradle/libs.versions.toml` are a known-good set (AGP 8.7 / Kotlin 2.0 / Gradle 8.14). Bump together and rebuild.

## Layout
```
app/src/main/java/app/thebridge/
  BridgeApp.kt                    Hilt app, WorkManager config, sync on foreground
  MainActivity.kt                 edge-to-edge, theme, Apple sign-in return link
  di/AppModule.kt                 Room, OkHttp, DataStore, bindings
  ui/theme/Theme.kt               tokens, fonts, BridgeTheme (Material 3 mapped to our colors)
  ui/components/Components.kt     buttons, field, toast, sheet, UiText
  nav/AppNav.kt                   session phases → splash / auth flow / tabs, type-safe routes, bottom bar
  feature/auth/                   Welcome, Sign up, Log in, AuthViewModel (email, Google, Apple)
  feature/shell/                  tab roots, profile + account actions, offline banner, session-expired + delete sheets
  feature/onboarding/             goal → routine → photo (consent) → generating → plan review; drafts survive process death
  feature/today/                  goal cards, bridge arc, streak / days left / drift, tasks, Reality Check, sheets, celebration
  feature/coach/                  streaming coach chat, mascot states, Study Mode, CoachMascot.kt
  feature/journal/                mood + reflection with autosave, history, conflict resolution
  feature/community/              Community inside a goal (lock + preview, feed, reactions, report/block), alliances (create, join, shared plan), alliance chat
  domain/Progress.kt              progress, streak, drift, deadline rules (unit-tested; same cases as iOS)
  data/GoalRepository.kt, AiRepository.kt, JournalRepository.kt, CoachRepository.kt
  data/DmRepository.kt            encrypted DMs: Olm account and sessions (Rust, via uniffi.bridge_crypto), send/receive through the relay, safety numbers
  data/Billing.kt                 RevenueCat purchases (Plus, Alliance+); the server's entitlements are authoritative
  data/SocialRepository.kt        Community + alliances (cached in Room for offline reading), queued alliance chat, RealtimeClient (WebSocket)
  data/reminders/Reminders.kt     local reminders (daily, task times, streak at risk), re-armed after boot and time-zone changes
  data/remote/ApiClient.kt        all HTTP, token refresh (single flight), error codes
  data/session/                   SessionManager (phases, sign-in/out, delete), Keystore token vault
  data/local/Database.kt          Room entities, DAOs, outbox-in-transaction, local-day rule
  data/sync/SyncWorker.kt         WorkManager outbox push / change pull
```

## Conventions
- MVVM: `@HiltViewModel` per screen exposing `StateFlow<UiState>`; composables are stateless and previewable.
- Repositories own Room + network; ViewModels never touch OkHttp.
- Predictive back enabled (`android:enableOnBackInvokedCallback="true"`).
- App Links: `https://thebridge.app/join/{code}` with `assetlinks.json` on the domain.
- Paparazzi screenshot tests: light, dark, font scale 2.0.
- Baseline profile for Today and Coach startup paths.
