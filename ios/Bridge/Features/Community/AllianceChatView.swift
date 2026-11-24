import SwiftUI

/// Alliance chat: live while open, readable offline, and messages written offline send on reconnect.
struct AllianceChatScreen: View {
    let allianceId: String
    @Environment(SocialStore.self) private var social
    @Environment(Toaster.self) private var toaster
    @Environment(NetworkStatus.self) private var network
    @Environment(\.dismiss) private var dismiss
    @State private var input = ""
    @State private var reporting: ChatLine?
    @State private var challenging = false
    @Environment(SessionStore.self) private var session
    @Environment(AppRouter.self) private var router

    var body: some View {
        let _ = social.chatVersion                                   // re-read rows when they change
        AllianceChatContent(alliance: social.alliances.first { $0.id == allianceId }, lines: social.lines(allianceId), input: $input, actions: .init(
            back: { dismiss() },
            send: send,
            retry: { line in Task { report(await social.retry(line.id)) } },
            discard: { social.discard($0.id) },
            report: { reporting = $0 },
            openMatch: { router.today.append(Route.match($0)) },
            startGame: { challenging = true }
        ), me: session.user?.id.uuidString.lowercased() ?? "")
        .sheet(isPresented: $challenging) {
            ChallengeSheet(members: social.alliances.first { $0.id == allianceId }?.members ?? []) { game, who in challenge(game, who) }
        }
        .toolbar(.hidden, for: .navigationBar)
        .task { social.realtime.acquire(); try? await social.refreshMessages(allianceId) }
        .onDisappear { social.realtime.release() }
        .onChange(of: network.online) { _, now in if now { Task { await social.flushQueued(); try? await social.refreshMessages(allianceId) } } }
        .confirmationDialog(Text("Report this?"), isPresented: Binding(get: { reporting != nil }, set: { if !$0 { reporting = nil } }), titleVisibility: .visible, presenting: reporting) { line in
            Button("Report message", role: .destructive) {
                Task { do { try await social.reportMessage(line); toaster.show(String(localized: "Thanks. It's hidden for you."), .ok) } catch { toaster.show(String(localized: "You're offline. Try again when you reconnect."), .warn) } }
            }
        } message: { _ in Text("It's hidden for you now. Our team reviews reports; three reports hide it for everyone in the meantime.") }
    }

    private func send() {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        input = ""
        Task { report(await social.send(allianceId, text)) }
    }

    /// The controller button: challenge a member to a turn-based game. The card appears in the chat.
    private func challenge(_ game: String, _ who: String) {
        Task {
            do {
                let m = try await APIClient.shared.challenge(allianceId, game: game, opponentId: who)
                try? await social.refreshMessages(allianceId)
                router.today.append(Route.match(m.id))
            } catch APIError.server(_, "too_many_open", _, _) {
                toaster.show(String(localized: "You already have three games going with them."), .warn)
            } catch { toaster.show(String(localized: "Game Night needs a connection."), .warn) }
        }
    }

    private func report(_ r: SocialStore.SendResult) {
        if r == .blocked { toaster.show(String(localized: "Not sent. This message breaks the community rules."), .warn) }
    }
}

struct ChatActions {
    var back: () -> Void = {}, send: () -> Void = {}, retry: (ChatLine) -> Void = { _ in }, discard: (ChatLine) -> Void = { _ in }, report: (ChatLine) -> Void = { _ in }
    var openMatch: (String) -> Void = { _ in }, startGame: () -> Void = {}
}

struct AllianceChatContent: View {
    let alliance: AllianceDTO?
    let lines: [ChatLine]
    @Binding var input: String
    var actions = ChatActions()
    var me = ""
    var now: Date = .now
    @FocusState private var focused: Bool

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 6) {
                Button(action: actions.back) { Image(systemName: "arrow.left").font(.system(size: 22)).foregroundStyle(Theme.ink).frame(width: 44, height: 44) }.accessibilityLabel(Text("Back"))
                VStack(alignment: .leading, spacing: 3) {
                    Text(alliance?.name ?? "").font(BFont.display(18)).foregroundStyle(Theme.ink).lineLimit(1).accessibilityAddTraits(.isHeader)
                    if let a = alliance { Text("\(a.memberCount) MEMBERS · STREAK \(a.streak)").bridgeLabel(10) }
                }
                Spacer()
                Image(systemName: "flame.fill").foregroundStyle(Theme.acc).accessibilityHidden(true)
            }
            .padding(.leading, 6).padding(.trailing, 16).padding(.top, 4).padding(.bottom, 12)
            Divider().overlay(Theme.line)
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 12) {
                        Label("Alliance chat isn't end-to-end encrypted. Members and our moderation can read it.", systemImage: "lock")
                            .font(BFont.body(12)).foregroundStyle(Theme.mut).padding(.horizontal, 12).padding(.vertical, 9)
                            .frame(maxWidth: .infinity, alignment: .leading).background(Theme.s0, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                        if lines.isEmpty { Text("Say hello. Messages from members show up here.").font(BFont.body(13)).foregroundStyle(Theme.mut).padding(.top, 8) }
                        ForEach(lines) { line in (line.card != nil ? AnyView(card(line)) : line.mine ? AnyView(mine(line)) : line.system ? AnyView(systemLine(line)) : AnyView(theirs(line))).id(line.id) }
                    }
                    .padding(16)
                }
                .onChange(of: lines.count) { _, _ in if let last = lines.last { withAnimation { proxy.scrollTo(last.id, anchor: .bottom) } } }
                .onAppear { if let last = lines.last { proxy.scrollTo(last.id, anchor: .bottom) } }
            }
            composer
        }
        .background(Theme.bg)
    }

    /// A challenge card, on the sender's side of the chat.
    private func card(_ m: ChatLine) -> some View {
        ChallengeCard(card: m.card!, me: me) { actions.openMatch(m.card!.matchId) }
            .frame(maxWidth: .infinity, alignment: m.authorId == me && !me.isEmpty ? .trailing : .leading)
    }

    private func systemLine(_ m: ChatLine) -> some View {
        // Arcade results carry their game's icon.
        Label(m.body, systemImage: m.game.map { gameSymbol(gameIconName($0)) } ?? "checkmark.circle.fill").font(BFont.body(12, .medium)).foregroundStyle(Theme.ok)
            .padding(.horizontal, 12).frame(minHeight: 28).background(Theme.okTint, in: Capsule()).frame(maxWidth: .infinity)
    }

    private func theirs(_ m: ChatLine) -> some View {
        HStack(alignment: .bottom, spacing: 8) {
            Avatar(name: m.name, size: 30)
            VStack(alignment: .leading, spacing: 4) {
                Text("\(m.name ?? "") · \(ago(m.createdAt.timeIntervalSince1970 * 1000, now))").font(BFont.body(11, .medium)).foregroundStyle(Theme.mut).padding(.leading, 4)
                Text(m.body).font(BFont.body(15)).foregroundStyle(Theme.ink).padding(.horizontal, 13).padding(.vertical, 10)
                    .background(Theme.s1, in: UnevenRoundedRectangle(topLeadingRadius: 16, bottomLeadingRadius: 5, bottomTrailingRadius: 16, topTrailingRadius: 16, style: .continuous))
                    .overlay(UnevenRoundedRectangle(topLeadingRadius: 16, bottomLeadingRadius: 5, bottomTrailingRadius: 16, topTrailingRadius: 16, style: .continuous).strokeBorder(Theme.line, lineWidth: 1))
                    .contextMenu { Button("Report message", systemImage: "flag", role: .destructive) { actions.report(m) } }
            }
            Spacer(minLength: 40)
        }
        .accessibilityElement(children: .combine)
        .accessibilityAction(named: Text("Report message")) { actions.report(m) }
    }

    private func mine(_ m: ChatLine) -> some View {
        VStack(alignment: .trailing, spacing: 4) {
            Text(m.body).font(BFont.body(15)).foregroundStyle(Theme.ink).padding(.horizontal, 13).padding(.vertical, 10)
                .background(Theme.bubble, in: UnevenRoundedRectangle(topLeadingRadius: 16, bottomLeadingRadius: 16, bottomTrailingRadius: 5, topTrailingRadius: 16, style: .continuous))
            if m.status == "queued" { Text("Queued · sends when online").font(BFont.body(11, .medium)).foregroundStyle(Theme.warn) }
            if m.status == "failed" {
                HStack(spacing: 12) {
                    Button("Discard") { actions.discard(m) }.font(BFont.body(12, .medium)).foregroundStyle(Theme.mut).frame(minHeight: 44)
                    Button("Not sent. Tap to retry.") { actions.retry(m) }.font(BFont.body(12, .medium)).foregroundStyle(Theme.bad).frame(minHeight: 44)
                }
            }
        }
        .padding(.leading, 60).frame(maxWidth: .infinity, alignment: .trailing)
    }

    private var composer: some View {
        let canSend = !input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        return HStack(spacing: 8) {
            Button(action: actions.startGame) {
                Image(systemName: "gamecontroller").font(.system(size: 19)).foregroundStyle(Theme.ink).frame(width: 48, height: 48).overlay(Circle().strokeBorder(Theme.line, lineWidth: 1))
            }
            .accessibilityLabel(Text("Start a game"))
            TextField("Message the alliance", text: $input, axis: .vertical).lineLimit(1...4)
                .font(BFont.body(15)).foregroundStyle(Theme.ink).focused($focused).submitLabel(.send).onSubmit(actions.send)
                .padding(.horizontal, 18).padding(.vertical, 12).frame(minHeight: 48)
                .background(Theme.s1, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(focused ? Theme.mut : Theme.line, lineWidth: 1))
            Button(action: actions.send) {
                Image(systemName: "arrow.up").font(.system(size: 20, weight: .semibold)).foregroundStyle(Theme.onAcc).frame(width: 48, height: 48)
                    .background(Theme.acc.opacity(canSend ? 1 : 0.35), in: Circle())
            }
            .disabled(!canSend).accessibilityLabel(Text("Send"))
        }
        .padding(.horizontal, 16).padding(.top, 8).padding(.bottom, 16)
    }
}
