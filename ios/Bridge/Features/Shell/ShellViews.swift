import SwiftUI

// Tab roots for milestone 1: real chrome and empty states. Goal cards, chat and entries arrive with milestones 2 and 3.

private struct TabPage<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Theme.Space.section) { content }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, Theme.Space.screen).padding(.vertical, 16)
        }
        .background(Theme.bg)
        .toolbar(.hidden, for: .navigationBar)
    }
}

private struct EmptyCard: View {
    let title: LocalizedStringKey, message: LocalizedStringKey
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(BFont.display(20)).foregroundStyle(Theme.ink)
            Text(message).font(BFont.body(14)).lineSpacing(3).foregroundStyle(Theme.mut)
        }
        .padding(Theme.Space.card).frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.s1, in: RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous).stroke(Theme.line, lineWidth: 1))
    }
}

struct ProfileView: View {
    @Environment(SessionStore.self) private var session
    @Environment(Toaster.self) private var toaster
    @State private var sheet: Sheet?
    @State private var unsynced = 0
    @State private var reminders = ReminderSettings.load()
    @State private var renaming = false
    @State private var coachName = ""
    @AppStorage("coachReadsJournal") private var coachReadsJournal = false
    @Environment(SocialStore.self) private var social
    @Environment(GoalStore.self) private var goals
    enum Sheet: String, Identifiable { case delete, logOut; var id: String { rawValue } }

    var body: some View {
        let name = session.user?.displayName ?? ""
        TabPage {
            HStack(spacing: 14) {
                Circle().fill(Theme.goalCards[0]).frame(width: 64, height: 64)
                    .overlay(Text(name.prefix(1).uppercased()).font(BFont.display(25)).foregroundStyle(Theme.goalInk)).accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 4) {
                    Text(name).font(BFont.display(22)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                    if let ms = session.user?.createdAt, ms > 0 {
                        Text("MEMBER SINCE \(Date(timeIntervalSince1970: ms / 1000).formatted(.dateTime.month(.abbreviated).year()))").bridgeLabel(10)
                    }
                    if let email = session.user?.email { Text(email).font(BFont.body(13)).foregroundStyle(Theme.mut) }
                }
            }
            SettingsSection(title: "Notifications") {
                SettingsValue(label: "Daily reminder", value: Self.clock(reminders.daily)) { reminders.daily = Self.next(reminders.daily, [7, 8, 9]); saveReminders() }
                Divider().overlay(Theme.line)
                SettingsValue(label: "Streak at risk", value: Self.clock(reminders.streakRisk)) { reminders.streakRisk = Self.next(reminders.streakRisk, [19, 20, 21]); saveReminders() }
            }
            SettingsSection(title: "Coach") {
                SettingsValue(label: "Coach name", value: session.user?.coachName ?? "Arc") { coachName = session.user?.coachName ?? "Arc"; renaming = true }
                Divider().overlay(Theme.line)
                Toggle(isOn: Binding(get: { coachReadsJournal }, set: { on in
                    coachReadsJournal = on
                    if let id = session.user?.id { goals.saveSetting(userId: id, "coachReadsJournal", on) }
                })) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Let my coach read my journal").font(BFont.body(15, .medium)).foregroundStyle(Theme.ink)
                        Text("Your last three entries are shared with the coach for context. Never used for training.").font(BFont.body(12)).foregroundStyle(Theme.mut)
                    }
                }
                .tint(Theme.okFill).padding(.horizontal, Theme.Space.card).padding(.vertical, 12)
            }
            SettingsSection(title: "Privacy") {
                SettingsValue(label: "Community sharing", value: social.share.settingsLabel) { social.cycleShare(userId: session.user?.id, goals: goals) }
            }
            if session.user?.photoUrl != nil {
                SettingsSection(title: "Photo") { SettingsLink(label: "Remove photo and its images", danger: true, action: removePhoto) }
            }
            VStack(alignment: .leading, spacing: 8) {
                Text("Account").bridgeLabel(10).accessibilityAddTraits(.isHeader)
                VStack(spacing: 0) {
                    SettingsLink(label: "Log out", danger: false) {
                        unsynced = Persistence.outboxCount()
                        if unsynced > 0 { sheet = .logOut } else { logOut() }
                    }
                    Divider().overlay(Theme.line)
                    SettingsLink(label: "Delete account", danger: true) { sheet = .delete }
                }
                .background(Theme.s1, in: RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous).stroke(Theme.line, lineWidth: 1))
            }
        }
        .alert("Rename your coach", isPresented: $renaming) {
            TextField("Arc", text: $coachName)
            Button("Save") { Task { _ = try? await APIClient.shared.updateMe(coachName: String(coachName.prefix(20))); await session.refreshUser() } }
            Button("Cancel", role: .cancel) {}
        }
        .sheet(item: $sheet) { which in
            Group {
                switch which {
                case .delete: DeleteAccountSheet()
                case .logOut: LogOutSheet(unsynced: unsynced, onConfirm: logOut)
                }
            }
            .presentationDetents([.medium]).presentationDragIndicator(.visible).presentationBackground(Theme.bg)
        }
    }

    // Reminder times cycle through a few sensible choices, as in the prototype; "Off" is the last stop.
    private static func next(_ c: DateComponents?, _ hours: [Int]) -> DateComponents? {
        let all: [DateComponents?] = hours.map { DateComponents(hour: $0, minute: 0) } + [nil]
        let i = all.firstIndex { $0?.hour == c?.hour && ($0 == nil) == (c == nil) } ?? -1
        return all[(i + 1) % all.count]
    }
    private static func clock(_ c: DateComponents?) -> String {
        guard let c, let d = Calendar.current.date(from: DateComponents(hour: c.hour, minute: c.minute)) else { return String(localized: "Off") }
        return d.formatted(date: .omitted, time: .shortened)
    }
    private func saveReminders() { reminders.save(); Task { await NotificationScheduler.shared.reschedule() } }

    /// Deletes the photo and every image generated from it (server side), then refreshes the profile.
    private func removePhoto() {
        Task {
            do { try await APIClient.shared.deletePhoto(); await session.refreshUser(); toaster.show(String(localized: "Photo and its images deleted."), .ok) }
            catch APIError.offline { toaster.show(String(localized: "You're offline. Try again when you reconnect."), .warn) }
            catch { toaster.show(String(localized: "Something went wrong. Try again."), .warn) }
        }
    }

    private func logOut() {
        sheet = nil
        Task { await session.logOut(); toaster.show(String(localized: "You've been logged out.")) }
    }
}

private struct SettingsSection<Rows: View>: View {
    let title: LocalizedStringKey
    @ViewBuilder var rows: Rows
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).bridgeLabel(10).accessibilityAddTraits(.isHeader)
            VStack(spacing: 0) { rows }
                .background(Theme.s1, in: RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: Theme.Radius.card, style: .continuous).stroke(Theme.line, lineWidth: 1))
        }
    }
}

private struct SettingsValue: View {
    let label: LocalizedStringKey, value: String
    var action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack {
                Text(label).font(BFont.body(15, .medium)).foregroundStyle(Theme.ink)
                Spacer()
                Text(value).font(BFont.body(14)).foregroundStyle(Theme.mut)
            }
            .padding(.horizontal, Theme.Space.card).frame(minHeight: 52).contentShape(Rectangle())
        }
    }
}

private struct SettingsLink: View {
    let label: LocalizedStringKey, danger: Bool
    var action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack {
                Text(label).font(BFont.body(15, .medium)).foregroundStyle(danger ? Theme.bad : Theme.ink)
                Spacer()
                Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.mut2).accessibilityHidden(true)
            }
            .padding(.horizontal, Theme.Space.card).frame(minHeight: 52).contentShape(Rectangle())
        }
    }
}

struct DeleteAccountSheet: View {
    @Environment(SessionStore.self) private var session
    @Environment(Toaster.self) private var toaster
    @Environment(\.dismiss) private var dismiss
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("Delete your account?").font(BFont.display(20)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                Text("Your plan, both AI images, your photo and every journal entry will be permanently removed.").font(BFont.body(15)).lineSpacing(4).foregroundStyle(Theme.ink2)
                Text("Deleting your account doesn't cancel a subscription. Manage it in your Apple ID settings.").font(BFont.body(13)).foregroundStyle(Theme.mut)
                if let error { Text(error).font(BFont.body(13)).foregroundStyle(Theme.bad) }
                Button(action: delete) {
                    Group { if busy { ProgressView().tint(Theme.ink) } else { Text("Delete permanently") } }
                        .font(BFont.display(16)).foregroundStyle(Theme.ink).frame(maxWidth: .infinity, minHeight: 54)
                        .background(Theme.bad, in: RoundedRectangle(cornerRadius: Theme.Radius.button, style: .continuous))
                }
                Button("Keep my account") { dismiss() }.buttonStyle(SecondaryButtonStyle())
            }
            .disabled(busy)
            .padding(.horizontal, 24).padding(.top, 28).padding(.bottom, 24)
        }
        .interactiveDismissDisabled(busy)
    }

    private func delete() {
        busy = true; error = nil
        Task {
            defer { busy = false }
            do {
                try await session.deleteAccount()
                toaster.show(String(localized: "Your account has been deleted."))
            } catch APIError.offline {
                error = String(localized: "You're offline. Connect to delete your account.")
            } catch APIError.unauthorized {
                dismiss(); session.expired()        // must prove who they are before deleting
            } catch {
                self.error = String(localized: "Something went wrong. Try again.")
            }
        }
    }
}

/// Logging out wipes local data, so warn first when something hasn't reached the server.
struct LogOutSheet: View {
    let unsynced: Int
    var onConfirm: () -> Void
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("Log out").font(BFont.display(20)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                Text("\(unsynced) changes haven't synced yet and will be lost if you log out now.").font(BFont.body(15)).lineSpacing(4).foregroundStyle(Theme.ink2)
                Button("Stay logged in") { dismiss() }.buttonStyle(PrimaryButtonStyle())
                Button("Log out anyway", action: onConfirm).buttonStyle(SecondaryButtonStyle())
            }
            .padding(.horizontal, 24).padding(.top, 28).padding(.bottom, 24)
        }
    }
}

/// 36pt strip under the status bar while there is no connection.
struct OfflineBanner: View {
    let queued: Int
    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "icloud.slash").font(.system(size: 13, weight: .medium)).accessibilityHidden(true)
            Text("Offline. Showing saved data.").font(BFont.body(12, .medium))
            Spacer()
            Group { if queued > 0 { Text("\(queued) QUEUED") } else { Text("CACHED") } }.font(BFont.label(11)).tracking(0.66)
        }
        .foregroundStyle(Theme.warn)
        .padding(.horizontal, Theme.Space.screen).frame(minHeight: 36)
        .background(Theme.warnTint.ignoresSafeArea(edges: .top))
        .accessibilityElement(children: .combine)
    }
}

struct ToastView: View {
    let toast: Toaster.Toast
    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: toast.kind == .warn ? "exclamationmark.triangle.fill" : "checkmark.circle.fill")
                .foregroundStyle(toast.kind == .ok ? Theme.ok : toast.kind == .warn ? Theme.warn : Theme.mut).accessibilityHidden(true)
            Text(toast.text).font(BFont.body(14, .medium)).foregroundStyle(Theme.ink)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16).padding(.vertical, 14)
        .background(Theme.s2, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Theme.line, lineWidth: 1))
    }
}

/// Blocking screen after the server answers 426. Local data is untouched.
struct UpgradeView: View {
    @Environment(\.openURL) private var openURL
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Update The Bridge").font(BFont.display(30)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
            Text("This version is no longer supported. Your data on this phone is safe.").font(BFont.body(15)).foregroundStyle(Theme.mut)
            Button("Open App Store") { openURL(URL(string: "itms-apps://apps.apple.com/app/the-bridge")!) }.buttonStyle(PrimaryButtonStyle())
        }
        .padding(24).frame(maxWidth: .infinity, maxHeight: .infinity).background(Theme.bg)
    }
}
