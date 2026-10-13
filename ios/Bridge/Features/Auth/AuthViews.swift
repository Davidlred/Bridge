import AuthenticationServices
import SwiftUI

/// Brand mark: an arch over a deck.
struct BridgeMark: View {
    var color: Color
    var width: CGFloat = 92
    var body: some View {
        let u = width / 92
        VStack(spacing: 7 * u) {
            Circle().trim(from: 0.5, to: 1).stroke(color, lineWidth: 12 * u)
                .frame(width: 60 * u, height: 60 * u).frame(width: 72 * u, height: 36 * u, alignment: .top).clipped()
            Capsule().fill(color).frame(width: width, height: 7 * u)
        }
        .accessibilityHidden(true)
    }
}

/// First frame: mark on black in both themes.
struct SplashView: View {
    var body: some View {
        Color.black.ignoresSafeArea().overlay(BridgeMark(color: .white)).accessibilityLabel("The Bridge")
    }
}

struct AuthFlow: View {
    enum Step: Hashable { case signUp, logIn }
    @Environment(AuthModel.self) private var auth
    @State private var path: [Step] = []

    var body: some View {
        NavigationStack(path: $path) {
            WelcomeView(onGetStarted: { go(.signUp) }, onLogIn: { go(.logIn) })
                .navigationDestination(for: Step.self) { step in
                    switch step {
                    case .signUp: SignUpView(onLogIn: { go(.logIn) })
                    case .logIn: ScrollView { LogInForm(onSignUp: { go(.signUp) }) }.scrollBounceBehavior(.basedOnSize).background(Theme.bg)
                    }
                }
        }
    }
    private func go(_ step: Step) { auth.clearErrors(); path = [step] }
}

struct WelcomeView: View {
    var onGetStarted: () -> Void
    var onLogIn: () -> Void
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        VStack(alignment: .leading, spacing: 22) {
            RoundedRectangle(cornerRadius: Theme.Radius.hero, style: .continuous).fill(Theme.s1)
                .overlay {
                    // The artwork is dark line art on white: inverted in dark mode so it sits on the surface colour.
                    GeometryReader { geo in
                        let art = Image("welcome-mark").resizable().scaledToFit().frame(width: min(geo.size.width * 0.62, 240))
                        Group { if scheme == .dark { art.colorInvert().blendMode(.screen) } else { art.blendMode(.multiply) } }.frame(maxWidth: .infinity, maxHeight: .infinity)
                    }
                }
                .overlay(RoundedRectangle(cornerRadius: Theme.Radius.hero, style: .continuous).stroke(Theme.line, lineWidth: 1.5))
                .overlay(alignment: .bottomTrailing) { Stamp().offset(x: 6, y: 30) }
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 10) {
                Text("See who you're becoming.").font(BFont.display(36)).tracking(-0.9).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                Text("Name one goal and pick a date. We'll build the daily plan and picture both of your futures.").font(BFont.body(15)).lineSpacing(4).foregroundStyle(Theme.mut)
            }
            .padding(.top, 8)
            VStack(spacing: 6) {
                Button(action: onGetStarted) {
                    Text("Get started").font(BFont.body(16, .semibold)).foregroundStyle(Theme.onAcc)
                        .frame(maxWidth: .infinity, minHeight: 56).background(Theme.acc, in: Capsule())
                }
                LinkRow(lead: "Already have an account?", link: "Log in", action: onLogIn).frame(minHeight: 48)
            }
        }
        .padding(.horizontal, 18).padding(.top, 12).padding(.bottom, 24)
        .background(Theme.bg)
        .toolbar(.hidden, for: .navigationBar)
    }
}

/// Rotating circular label with the mark in the middle (22s per turn; still when motion is reduced).
private struct Stamp: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var spinning = false
    private let text = Array(String(repeating: String(localized: "TWO FUTURES · ONE BRIDGE · "), count: 1))

    var body: some View {
        ZStack {
            Circle().fill(Theme.bg)
            ZStack {
                ForEach(Array(text.enumerated()), id: \.offset) { i, ch in
                    Text(String(ch)).font(BFont.label(8.5)).foregroundStyle(Theme.ink).dynamicTypeSize(.large)
                        .offset(y: -39).rotationEffect(.degrees(Double(i) / Double(text.count) * 360))
                }
            }
            .rotationEffect(.degrees(spinning ? 360 : 0))
            .animation(reduceMotion ? nil : .linear(duration: 22).repeatForever(autoreverses: false), value: spinning)
            Circle().stroke(Theme.ink, lineWidth: 1.5).frame(width: 46, height: 46).overlay(BridgeMark(color: Theme.ink, width: 26))
        }
        .frame(width: 96, height: 96)
        .onAppear { spinning = !reduceMotion }
    }
}

private struct LinkRow: View {
    let lead: LocalizedStringKey, link: LocalizedStringKey
    var action: () -> Void
    var body: some View {
        Button(action: action) {
            (Text(lead).foregroundStyle(Theme.mut) + Text(" ") + Text(link).font(BFont.body(14, .semibold)).foregroundStyle(Theme.ink))
                .font(BFont.body(14)).multilineTextAlignment(.center).frame(maxWidth: .infinity, minHeight: 44)
        }
    }
}

private struct AuthHeader: View {
    let title: LocalizedStringKey, subtitle: LocalizedStringKey
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(BFont.display(32)).tracking(-0.32).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
            Text(subtitle).font(BFont.body(15)).foregroundStyle(Theme.mut)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Mono label above a 52pt field; error text in `bad` underneath.
struct BridgeField: View {
    let label: LocalizedStringKey, placeholder: LocalizedStringKey
    @Binding var text: String
    var error: String?
    var secure = false
    var contentType: UITextContentType?
    var keyboard: UIKeyboardType = .default
    var capitalization: TextInputAutocapitalization = .never
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            Text(label).bridgeLabel(11)
            Group { if secure { SecureField(placeholder, text: $text) } else { TextField(placeholder, text: $text) } }
                .font(BFont.body(16)).foregroundStyle(Theme.ink)
                .textContentType(contentType).keyboardType(keyboard).textInputAutocapitalization(capitalization).autocorrectionDisabled()
                .focused($focused)
                .padding(.horizontal, 16).frame(minHeight: 52)
                .background(Theme.s1, in: RoundedRectangle(cornerRadius: Theme.Radius.input, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: Theme.Radius.input, style: .continuous).stroke(error != nil ? Theme.bad : focused ? Theme.acc : Theme.line, lineWidth: focused ? 1.5 : 1))
                .accessibilityLabel(label).accessibilityHint(error ?? "")
            if let error { Text(error).font(BFont.body(13)).foregroundStyle(Theme.bad) }
        }
    }
}

private struct FormError: View {
    let text: String?
    var body: some View {
        if let text {
            Text(text).font(BFont.body(13)).foregroundStyle(Theme.bad)
                .padding(.horizontal, 14).padding(.vertical, 10).frame(maxWidth: .infinity, alignment: .leading)
                .background(Theme.badTint, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
    }
}

struct SignUpView: View {
    var onLogIn: () -> Void
    @Environment(AuthModel.self) private var auth
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        @Bindable var auth = auth
        ScrollView {
            VStack(spacing: 22) {
                AuthHeader(title: "Create your account", subtitle: "Takes about two minutes, including your plan.")
                VStack(spacing: 10) {
                    // Apple first: required by App Review whenever another social sign-in is offered.
                    SignInWithAppleButton(.continue, onRequest: auth.prepareApple, onCompletion: auth.finishApple)
                        .signInWithAppleButtonStyle(scheme == .dark ? .white : .black)
                        .frame(height: 52).clipShape(RoundedRectangle(cornerRadius: Theme.Radius.input, style: .continuous))
                    Button(action: auth.google) {
                        Text("Continue with Google").font(BFont.body(15, .semibold)).foregroundStyle(Theme.ink).frame(maxWidth: .infinity, minHeight: 52)
                            .overlay(RoundedRectangle(cornerRadius: Theme.Radius.input, style: .continuous).stroke(Theme.line, lineWidth: 1))
                    }
                }
                .disabled(auth.busy)
                HStack(spacing: 12) {
                    Rectangle().fill(Theme.track).frame(height: 1)
                    Text("OR WITH EMAIL").bridgeLabel(11).fixedSize()
                    Rectangle().fill(Theme.track).frame(height: 1)
                }
                VStack(spacing: 14) {
                    BridgeField(label: "FIRST NAME", placeholder: "Sharon", text: $auth.name, error: auth.nameError, contentType: .givenName, capitalization: .words)
                    BridgeField(label: "EMAIL", placeholder: "you@example.com", text: $auth.email, error: auth.emailError, contentType: .emailAddress, keyboard: .emailAddress)
                    BridgeField(label: "PASSWORD", placeholder: "At least 8 characters", text: $auth.password, error: auth.passwordError, secure: true, contentType: .newPassword)
                }
                FormError(text: auth.formError)
                Button(action: auth.signUp) { if auth.busy { ProgressView().tint(Theme.onAcc) } else { Text("Create account") } }
                    .buttonStyle(PrimaryButtonStyle()).disabled(auth.busy)
                LinkRow(lead: "Already have an account?", link: "Log in", action: onLogIn)
            }
            .padding(.horizontal, 24).padding(.top, 8).padding(.bottom, 24)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(Theme.bg)
    }
}

/// Email + password. Used full-screen in the signed-out flow and inside the session-expired sheet.
struct LogInForm: View {
    var onSignUp: (() -> Void)?
    @Environment(AuthModel.self) private var auth

    var body: some View {
        @Bindable var auth = auth
        VStack(spacing: 22) {
            AuthHeader(title: "Welcome back", subtitle: "Your bridge is where you left it.")
            VStack(alignment: .trailing, spacing: 14) {
                BridgeField(label: "EMAIL", placeholder: "you@example.com", text: $auth.email, error: auth.emailError, contentType: .username, keyboard: .emailAddress)
                BridgeField(label: "PASSWORD", placeholder: "Your password", text: $auth.password, error: auth.passwordError, secure: true, contentType: .password)
                Button("Forgot password?", action: auth.forgot).font(BFont.body(14, .medium)).foregroundStyle(Theme.ink).frame(minHeight: 44)
            }
            FormError(text: auth.formError)
            Button(action: auth.logIn) { if auth.busy { ProgressView().tint(Theme.onAcc) } else { Text("Log in") } }
                .buttonStyle(PrimaryButtonStyle()).disabled(auth.busy)
            if let onSignUp { LinkRow(lead: "New here?", link: "Create an account", action: onSignUp) }
        }
        .padding(.horizontal, 24).padding(.top, 8).padding(.bottom, 24)
    }
}

/// Session expired: the app underneath keeps working on local data; nothing unsynced is dropped.
struct ReauthSheet: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                Text("Your session has expired.").font(BFont.body(14, .semibold)).foregroundStyle(Theme.warn)
                Text("Log in again to keep syncing. Everything you've done on this phone is saved.").font(BFont.body(13)).foregroundStyle(Theme.mut)
            }
            .padding(.horizontal, 24).padding(.top, 28).frame(maxWidth: .infinity, alignment: .leading)
            LogInForm(onSignUp: nil)
        }
        .background(Theme.bg)
        .presentationDetents([.large]).presentationDragIndicator(.hidden)
    }
}
