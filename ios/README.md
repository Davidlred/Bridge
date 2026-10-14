# iOS (SwiftUI)

Requirements: Xcode 16+, iOS 17 deployment target, [XcodeGen](https://github.com/yonaskolb/XcodeGen).

```
brew install xcodegen
cd ios && xcodegen && open Bridge.xcodeproj
```

Fonts are bundled as variable fonts in `Bridge/Resources/Fonts/` (Bricolage Grotesque, Geist, Geist Mono; SIL OFL).
Debug builds call `http://localhost:8080`. Sign-in on iOS is Apple or email; Google sign-in is Android-only.

> There is no Mac in the loop yet: every push that touches ios/ is built and unit-tested on a GitHub macOS runner (.github/workflows/ios.yml), and the result is published to the ci-logs branch (ios.txt). Snapshot tests (FoundationSnapshotTests) have no reference images yet; record them on a Mac or in CI before relying on them.

## Layout
```
Bridge/
  App/BridgeApp.swift            app entry, session phases, root view
  DesignSystem/Theme.swift       tokens, fonts, buttons, priority pill
  Navigation/AppRouter.swift     tabs + typed routes + universal links
  Features/Coach/CoachMascot.swift  animated coach (idle/listening/thinking/talking/happy)
  Core/Persistence/Models.swift  SwiftData models + outbox + local-day rule
  Core/Sync/SyncEngine.swift     outbox push / change pull
```
Also: `Core/Networking/APIClient.swift` (HTTP, token refresh, Keychain), `Core/Sync/ChangeApplier.swift`, `Features/Auth/` (Welcome, Sign up, Log in, re-auth sheet, AuthModel), `Features/Shell/ShellViews.swift` (tab roots, profile, delete account, offline banner).
Still to write: goal onboarding, `GoalDetailView`, coach chat, journal, `CommunityView`, `StudyModeView`, `GameNightView`. Build each from the matching screen in `design/The Bridge v4.dc.html`.

## Conventions
- One `@Observable` model per feature, injected via `.environment`. Views stay dumb.
- No business logic in views; no network calls from views (go through feature models → repositories).
- All copy in `Localizable.xcstrings`.
- Snapshot tests for every screen in light, dark and XXL Dynamic Type.
