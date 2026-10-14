import AuthenticationServices
import CryptoKit
import SwiftUI

/// Pure input rules, shared by sign up, log in and the tests. Copy matches the prototype.
enum AuthValidation {
    static func name(_ v: String) -> String? { v.trimmingCharacters(in: .whitespaces).count < 2 ? String(localized: "Enter your first name.") : nil }
    static func email(_ v: String) -> String? {
        v.trimmingCharacters(in: .whitespaces).wholeMatch(of: /[^\s@]+@[^\s@]+\.[^\s@]{2,}/) == nil ? String(localized: "Enter a valid email address.") : nil
    }
    static func newPassword(_ v: String) -> String? { v.count < 8 ? String(localized: "Use at least 8 characters.") : nil }
    /// A password shorter than 8 can't be right, so don't spend a request (or a lockout attempt) on it.
    static func existingPassword(_ v: String) -> String? { v.count < 8 ? String(localized: "Incorrect email or password.") : nil }
}

@MainActor @Observable
final class AuthModel {
    // Name and email are restored after the app is killed mid-form; the password deliberately is not.
    var name: String = UserDefaults.standard.string(forKey: "draft.name") ?? "" { didSet { UserDefaults.standard.set(name, forKey: "draft.name"); nameError = nil; formError = nil } }
    var email: String = UserDefaults.standard.string(forKey: "draft.email") ?? "" { didSet { UserDefaults.standard.set(email, forKey: "draft.email"); emailError = nil; formError = nil } }
    var password = "" { didSet { passwordError = nil; formError = nil } }
    var nameError: String?
    var emailError: String?
    var passwordError: String?
    /// Errors that aren't about one field (offline, provider unavailable, account linked elsewhere).
    var formError: String?
    var busy = false

    @ObservationIgnored private weak var session: SessionStore?
    @ObservationIgnored private weak var toaster: Toaster?
    @ObservationIgnored private var appleNonce: String?

    func attach(session: SessionStore, toaster: Toaster) {
        self.session = session; self.toaster = toaster
        if email.isEmpty, let known = session.user?.email { email = known }
    }

    func clearErrors() { nameError = nil; emailError = nil; passwordError = nil; formError = nil; password = "" }

    func signUp() {
        nameError = AuthValidation.name(name); emailError = AuthValidation.email(email); passwordError = AuthValidation.newPassword(password)
        guard nameError == nil, emailError == nil, passwordError == nil else { return }
        let first = name.trimmingCharacters(in: .whitespaces).split(separator: " ").first.map(String.init) ?? name
        let email = email.trimmingCharacters(in: .whitespaces), password = password
        submit(offline: String(localized: "You're offline. We'll need a connection to create your account.")) {
            try await APIClient.shared.signUp(name: first, email: email, password: password)
        }
    }

    func logIn() {
        emailError = AuthValidation.email(email); passwordError = AuthValidation.existingPassword(password)
        guard emailError == nil, passwordError == nil else { return }
        let email = email.trimmingCharacters(in: .whitespaces), password = password
        submit(offline: String(localized: "You're offline. We'll need a connection to log you in."), welcomeBack: true) {
            try await APIClient.shared.logIn(email: email, password: password)
        }
    }

    func forgot() {
        let email = email.trimmingCharacters(in: .whitespaces)
        guard AuthValidation.email(email) == nil else { emailError = String(localized: "Enter your email first, then tap Forgot."); return }
        Task {
            do {
                try await APIClient.shared.forgotPassword(email: email)
                toaster?.show(String(localized: "Reset link sent to \(email)"), .ok)
            } catch APIError.offline {
                formError = String(localized: "You're offline. Try again when you reconnect.")
            } catch {
                formError = String(localized: "Something went wrong. Try again.")
            }
        }
    }

    // MARK: Sign in with Apple

    /// Apple receives the SHA-256 of a fresh nonce; the server gets the raw value and compares.
    func prepareApple(_ request: ASAuthorizationAppleIDRequest) {
        let nonce = UUID().uuidString + UUID().uuidString
        appleNonce = nonce
        request.requestedScopes = [.fullName, .email]
        request.nonce = SHA256.hash(data: Data(nonce.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    func finishApple(_ result: Result<ASAuthorization, Error>) {
        guard case .success(let auth) = result,
              let credential = auth.credential as? ASAuthorizationAppleIDCredential,
              let token = credential.identityToken.flatMap({ String(data: $0, encoding: .utf8) }) else {
            // Cancelled: stay on the screen, no error. Anything else: say so.
            if case .failure(let e) = result, (e as? ASAuthorizationError)?.code != .canceled { formError = unavailable("Apple") }
            return
        }
        let nonce = appleNonce, given = credential.fullName?.givenName     // Apple sends the name on first sign-in only
        submit(offline: String(localized: "You're offline. Try again when you reconnect."), provider: "Apple") {
            try await APIClient.shared.social("apple", idToken: token, nonce: nonce, name: given)
        }
    }

    // MARK: Shared

    private func unavailable(_ provider: String) -> String { String(localized: "\(provider) sign-in isn't available right now. Use email instead.") }

    private func submit(offline: String, welcomeBack: Bool = false, provider: String? = nil, _ request: @escaping @MainActor () async throws -> AuthResponse?) {
        guard !busy else { return }
        busy = true; formError = nil
        Task {
            defer { busy = false }
            do {
                guard let r = try await request() else { return }
                session?.signedIn(r)
                if let provider { toaster?.show(String(localized: "Signed in with \(provider)"), .ok) }
                else if welcomeBack { toaster?.show(String(localized: "Welcome back, \(r.user.displayName)."), .ok) }
                password = ""
            } catch APIError.offline {
                formError = offline
            } catch APIError.server(_, let code, let linked, let retry) {
                switch code {
                case "invalid_credentials": passwordError = String(localized: "Incorrect email or password.")
                case "email_taken": emailError = String(localized: "An account with this email already exists. Log in instead.")
                case "email_linked":
                    formError = linked == "apple" ? String(localized: "This email is linked to Apple sign-in. Continue with Apple?")
                        : linked == "google" ? String(localized: "This email belongs to an account created with Google on Android. Use a different email here.")
                        : String(localized: "This email already has an account. Log in with your password.")
                case "too_many_attempts":
                    let minutes = ((retry ?? 900) + 59) / 60
                    formError = String(localized: "Too many attempts. Try again in \(minutes) minutes, or reset your password.")
                case "provider_not_configured": formError = unavailable(provider ?? "Apple")
                default: formError = String(localized: "Something went wrong. Try again.")
                }
            } catch {
                formError = String(localized: "Something went wrong. Try again.")
            }
        }
    }
}
