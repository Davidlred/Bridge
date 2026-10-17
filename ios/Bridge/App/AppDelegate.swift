import UIKit

/// APNs device token: kept locally and sent with the device registration (`PUT /v1/devices`) so the server can push
/// alliance mentions, game night, nudges and "New message" (content-free; DMs are decrypted on the phone).
final class AppDelegate: NSObject, UIApplicationDelegate {
    static let tokenKey = "apnsToken"

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        let hex = deviceToken.map { String(format: "%02x", $0) }.joined()
        guard UserDefaults.standard.string(forKey: Self.tokenKey) != hex else { return }
        UserDefaults.standard.set(hex, forKey: Self.tokenKey)
        Task { try? await APIClient.shared.registerDevice(pushToken: hex) }
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        // Simulator, or no aps-environment entitlement in this build: local reminders still work.
    }
}
