import SwiftUI

/// One row in the inbox.
struct DmRowData: Identifiable {
    let contact: DmContactDTO
    let last: DmMessageRow?
    let unread: Bool
    let verified: Bool
    var id: String { contact.userId }
}

struct DmInboxScreen: View {
    @Environment(DmStore.self) private var dms
    @Environment(SocialStore.self) private var social
    @Environment(NetworkStatus.self) private var network
    @Environment(AppRouter.self) private var router
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let _ = dms.version
        let last = dms.lastMessages()
        let rows = dms.contacts.map { c in DmRowData(contact: c, last: last[c.userId], unread: dms.unread(c.userId), verified: dms.verification(c.userId) == .verified) }
            .sorted { ($0.last?.createdAt ?? .distantPast) > ($1.last?.createdAt ?? .distantPast) }
        DmInboxContent(rows: rows, loaded: !dms.contacts.isEmpty || !last.isEmpty, onBack: { dismiss() }, onOpen: { id in if let u = UUID(uuidString: id) { router.today.append(Route.dmThread(u)) } })
            .toolbar(.hidden, for: .navigationBar)
            .task { social.realtime.acquire(); social.realtime.onDm = { Task { await dms.receive() } }; await dms.loadContacts(); await dms.receive(); await dms.flushQueued() }
            .onDisappear { social.realtime.release() }
            .onChange(of: network.online) { _, now in if now { Task { await dms.flushQueued(); await dms.receive() } } }
    }
}

struct DmInboxContent: View {
    let rows: [DmRowData]
    let loaded: Bool
    var onBack: () -> Void = {}
    var onOpen: (String) -> Void = { _ in }
    var now: Date = .now

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 4) {
                Button(action: onBack) { Image(systemName: "arrow.left").font(.system(size: 22)).foregroundStyle(Theme.ink).frame(width: 44, height: 44) }.accessibilityLabel(Text("Back"))
                Text("Messages").font(BFont.display(22)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                Spacer()
            }
            .padding(.leading, 10).padding(.vertical, 4)
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 6) {
                    E2eNote(text: "Personal messages are end-to-end encrypted. Only you and the person you're messaging can read them.").padding(.bottom, 8)
                    if loaded && rows.isEmpty {
                        VStack(spacing: 8) {
                            Text("No one to message yet").font(BFont.display(16)).foregroundStyle(Theme.ink)
                            Text("Join or create an alliance, then you can message its members here.").font(BFont.body(13)).foregroundStyle(Theme.mut).multilineTextAlignment(.center)
                        }
                        .frame(maxWidth: .infinity).padding(.vertical, 28)
                    }
                    ForEach(rows) { r in
                        Button { onOpen(r.contact.userId) } label: {
                            HStack(spacing: 12) {
                                Avatar(name: r.contact.name, size: 44)
                                VStack(alignment: .leading, spacing: 3) {
                                    HStack(spacing: 5) {
                                        Text(r.contact.name).font(BFont.body(15, .semibold)).foregroundStyle(Theme.ink)
                                        if r.verified { Image(systemName: "checkmark.shield.fill").font(.system(size: 13)).foregroundStyle(Theme.ok).accessibilityLabel(Text("Safety number verified")) }
                                    }
                                    preview(r).font(BFont.body(13)).foregroundStyle(r.unread ? Theme.ink2 : Theme.mut).lineLimit(1)
                                }
                                Spacer(minLength: 0)
                                VStack(alignment: .trailing, spacing: 6) {
                                    if let l = r.last { Text(ago(l.createdAt.timeIntervalSince1970 * 1000, now)).font(BFont.label(11)).foregroundStyle(Theme.mut2) }
                                    if r.unread { Circle().fill(Theme.acc).frame(width: 8, height: 8).accessibilityLabel(Text("Unread")) }
                                }
                            }
                            .padding(.horizontal, 12).padding(.vertical, 10).frame(minHeight: 64).contentShape(Rectangle())
                        }
                        .accessibilityElement(children: .combine)
                    }
                    Text("You can message people in your alliance.").font(BFont.body(12)).foregroundStyle(Theme.mut2).frame(maxWidth: .infinity).padding(.top, 12)
                }
                .padding(.horizontal, 16).padding(.top, 8).padding(.bottom, 40)
            }
        }
        .background(Theme.bg)
    }

    private func preview(_ r: DmRowData) -> Text {
        guard let l = r.last else { return Text("No messages yet. Say hi.") }
        if l.status == "undecryptable" { return Text("Message couldn't be decrypted") }
        return l.mine ? Text("You: \(l.body)") : Text(l.body)
    }
}

private struct E2eNote: View {
    let text: LocalizedStringKey
    var body: some View {
        Label(text, systemImage: "lock.fill").font(BFont.body(12)).foregroundStyle(Theme.ok)
            .padding(.horizontal, 12).padding(.vertical, 10).frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.okTint, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

// MARK: - Thread

struct DmThreadScreen: View {
    let peer: String
    @Environment(DmStore.self) private var dms
    @Environment(SocialStore.self) private var social
    @Environment(Toaster.self) private var toaster
    @Environment(NetworkStatus.self) private var network
    @Environment(\.dismiss) private var dismiss
    @State private var input = ""
    @State private var safety: [[String]]?
    @State private var menu = false

    var body: some View {
        let _ = dms.version
        let name = dms.peer(peer)?.name ?? ""
        DmThreadContent(name: name, messages: dms.messages(peer), verification: dms.verification(peer), input: $input, actions: .init(
            back: { dismiss() },
            send: { let t = input.trimmingCharacters(in: .whitespacesAndNewlines); guard !t.isEmpty else { return }; input = ""; Task { await dms.send(to: peer, t) } },
            retry: { id in Task { await dms.retry(id) } }, discard: { dms.discard($0) },
            safety: { Task { do { safety = try await dms.safetyNumbers(peer) } catch { toaster.show(String(localized: "You're offline. Try again when you reconnect."), .warn) } } },
            menu: { menu = true }
        ))
        .toolbar(.hidden, for: .navigationBar).toolbar(.hidden, for: .tabBar)
        .task { social.realtime.acquire(); social.realtime.onDm = { Task { await dms.receive(); dms.markRead(peer) } }; await dms.receive(); dms.markRead(peer) }
        .onDisappear { social.realtime.release() }
        .onChange(of: network.online) { _, now in if now { Task { await dms.flushQueued(); await dms.receive() } } }
        .sheet(isPresented: Binding(get: { safety != nil }, set: { if !$0 { safety = nil } })) {
            SafetySheet(name: name, numbers: safety ?? [], onVerify: { dms.markVerified(peer); safety = nil; toaster.show(String(localized: "Safety number verified."), .ok) }, onDismiss: { safety = nil })
                .presentationDetents([.large]).presentationDragIndicator(.visible).presentationBackground(Theme.bg)
        }
        .confirmationDialog(Text("Report \(name)"), isPresented: $menu, titleVisibility: .visible) {
            Button("Report \(name)", role: .destructive) {
                Task { do { try await dms.report(peer); toaster.show(String(localized: "Reported. Thanks for telling us."), .ok) } catch { toaster.show(String(localized: "You're offline. Try again when you reconnect."), .warn) } }
            }
            Button("Block \(name)", role: .destructive) {
                Task { do { try await APIClient.shared.block(userId: peer); toaster.show(String(localized: "\(name) is blocked."), .ok); dismiss() } catch { toaster.show(String(localized: "You're offline. Try again when you reconnect."), .warn) } }
            }
        } message: { Text("Your phone will send the last messages they sent you, so our team can review them. The Bridge can't read your messages otherwise.") }
    }
}

struct ThreadActions {
    var back: () -> Void = {}, send: () -> Void = {}, retry: (String) -> Void = { _ in }, discard: (String) -> Void = { _ in }, safety: () -> Void = {}, menu: () -> Void = {}
}

struct DmThreadContent: View {
    let name: String
    let messages: [DmMessageRow]
    let verification: Verification
    @Binding var input: String
    var actions = ThreadActions()
    var now: Date = .now
    @FocusState private var focused: Bool

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Button(action: actions.back) { Image(systemName: "arrow.left").font(.system(size: 22)).foregroundStyle(Theme.ink).frame(width: 44, height: 44) }.accessibilityLabel(Text("Back"))
                Avatar(name: name, size: 36)
                VStack(alignment: .leading, spacing: 2) {
                    Text(name).font(BFont.display(17)).foregroundStyle(Theme.ink).lineLimit(1).accessibilityAddTraits(.isHeader)
                    Button(action: actions.safety) { Label("END-TO-END ENCRYPTED", systemImage: "lock.fill").bridgeLabel(9.5, color: Theme.ok) }
                }
                Spacer()
                Button(action: actions.menu) { Image(systemName: "ellipsis").rotationEffect(.degrees(90)).foregroundStyle(Theme.mut).frame(width: 44, height: 44) }.accessibilityLabel(Text("Block or report"))
            }
            .padding(.leading, 6).padding(.trailing, 8).padding(.top, 4).padding(.bottom, 10)
            Divider().overlay(Theme.line)
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 10) {
                        VStack(alignment: .leading, spacing: 6) {
                            Label("Messages are end-to-end encrypted. The Bridge can't read them.", systemImage: "lock.fill").font(BFont.body(12)).foregroundStyle(Theme.ok)
                            if verification == .verified { Text("Safety number verified").font(BFont.body(12, .semibold)).foregroundStyle(Theme.ok) }
                            else { Button("Verify safety number", action: actions.safety).font(BFont.body(12, .semibold)).foregroundStyle(Theme.ok).frame(minHeight: 32) }
                        }
                        .padding(12).frame(maxWidth: .infinity, alignment: .leading).background(Theme.okTint, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                        if verification == .changed {
                            Button(action: actions.safety) {
                                Label("\(name)'s safety number changed. They may have reinstalled the app or added a device. Verify it before sharing anything sensitive.", systemImage: "exclamationmark.triangle.fill")
                                    .font(BFont.body(12)).foregroundStyle(Theme.warn).multilineTextAlignment(.leading)
                                    .padding(12).frame(maxWidth: .infinity, alignment: .leading).background(Theme.warnTint, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                            }
                        }
                        ForEach(messages, id: \.id) { m in bubble(m).id(m.id) }
                    }
                    .padding(16)
                }
                .onChange(of: messages.count) { _, _ in if let l = messages.last { withAnimation { proxy.scrollTo(l.id, anchor: .bottom) } } }
                .onAppear { if let l = messages.last { proxy.scrollTo(l.id, anchor: .bottom) } }
            }
            composer
        }
        .background(Theme.bg)
    }

    @ViewBuilder private func bubble(_ m: DmMessageRow) -> some View {
        if m.status == "undecryptable" {
            Text("This message couldn't be decrypted on this device.").font(BFont.body(13)).foregroundStyle(Theme.mut).padding(10).frame(maxWidth: .infinity)
                .background(Theme.s0, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        } else if !m.mine {
            VStack(alignment: .leading, spacing: 3) {
                Text(m.body).font(BFont.body(15)).foregroundStyle(Theme.ink).padding(.horizontal, 13).padding(.vertical, 10)
                    .background(Theme.s1, in: UnevenRoundedRectangle(topLeadingRadius: 16, bottomLeadingRadius: 5, bottomTrailingRadius: 16, topTrailingRadius: 16, style: .continuous))
                    .overlay(UnevenRoundedRectangle(topLeadingRadius: 16, bottomLeadingRadius: 5, bottomTrailingRadius: 16, topTrailingRadius: 16, style: .continuous).strokeBorder(Theme.line, lineWidth: 1))
                Text(ago(m.createdAt.timeIntervalSince1970 * 1000, now)).font(BFont.body(11)).foregroundStyle(Theme.mut2).padding(.leading, 4)
            }
            .padding(.trailing, 50)
        } else {
            VStack(alignment: .trailing, spacing: 3) {
                Text(m.body).font(BFont.body(15)).foregroundStyle(Theme.ink).padding(.horizontal, 13).padding(.vertical, 10)
                    .background(Theme.bubble, in: UnevenRoundedRectangle(topLeadingRadius: 16, bottomLeadingRadius: 16, bottomTrailingRadius: 5, topTrailingRadius: 16, style: .continuous))
                switch m.status {
                case "queued": Text("Queued · sends when online").font(BFont.body(11, .medium)).foregroundStyle(Theme.warn)
                case "no_devices": Text("\(name) needs to update the app to receive encrypted messages.").font(BFont.body(11, .medium)).foregroundStyle(Theme.warn).multilineTextAlignment(.trailing)
                case "failed":
                    HStack(spacing: 12) {
                        Button("Discard") { actions.discard(m.id) }.font(BFont.body(12, .medium)).foregroundStyle(Theme.mut).frame(minHeight: 44)
                        Button("Not sent. Tap to retry.") { actions.retry(m.id) }.font(BFont.body(12, .medium)).foregroundStyle(Theme.bad).frame(minHeight: 44)
                    }
                default: Label("Sent", systemImage: "checkmark").font(BFont.body(11)).foregroundStyle(Theme.mut)
                }
            }
            .padding(.leading, 60).frame(maxWidth: .infinity, alignment: .trailing)
        }
    }

    private var composer: some View {
        let canSend = !input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        return HStack(spacing: 8) {
            TextField(text: $input, axis: .vertical) { Label("Encrypted message", systemImage: "lock.fill") }.lineLimit(1...4)
                .font(BFont.body(15)).foregroundStyle(Theme.ink).focused($focused).submitLabel(.send).onSubmit(actions.send)
                .padding(.horizontal, 18).padding(.vertical, 12).frame(minHeight: 48)
                .background(Theme.s1, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(focused ? Theme.ok : Theme.line, lineWidth: 1))
            Button(action: actions.send) {
                Image(systemName: "arrow.up").font(.system(size: 20, weight: .semibold)).foregroundStyle(Theme.onAcc).frame(width: 48, height: 48)
                    .background(Theme.acc.opacity(canSend ? 1 : 0.35), in: Circle())
            }
            .disabled(!canSend).accessibilityLabel(Text("Send"))
        }
        .padding(.horizontal, 16).padding(.top, 8).padding(.bottom, 16)
    }
}

/// 12 groups of 5 digits per device of theirs, compared in person or on a call.
struct SafetySheet: View {
    let name: String
    let numbers: [[String]]
    var onVerify: () -> Void
    var onDismiss: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("Verify safety number").font(BFont.display(20)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                Text("Compare these numbers with \(name) in person or on a call. If they match, nobody is intercepting your messages.").font(BFont.body(14)).foregroundStyle(Theme.mut)
                ForEach(Array(numbers.enumerated()), id: \.offset) { i, groups in
                    if numbers.count > 1 { Text("Device \(i + 1)").bridgeLabel(10) }
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 4), spacing: 10) {
                        ForEach(Array(groups.enumerated()), id: \.offset) { _, g in Text(g).font(BFont.label(17)).foregroundStyle(Theme.ink) }
                    }
                    .padding(16).card(16)
                    .accessibilityElement(children: .ignore).accessibilityLabel(Text("Safety number: \(groups.joined(separator: " "))"))
                }
                Button(action: onVerify) { Label("Mark as verified", systemImage: "checkmark.shield.fill") }.buttonStyle(PrimaryButtonStyle())
                Button("Not now", action: onDismiss).buttonStyle(SecondaryButtonStyle())
            }
            .padding(24)
        }
    }
}
