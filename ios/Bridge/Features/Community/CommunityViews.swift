import SwiftUI

enum CommunityTab: Hashable { case feed, alliance }

/// Everything the Community screen shows, so the view stays stateless and snapshot-testable.
struct CommunityUI {
    var goal: GoalState?
    var community: CommunityDTO?
    var loading = true
    var feedError = false
    var previewing = false
    var tab = CommunityTab.feed
    var share = ShareLevel.streaks
    /// This goal's alliances.
    var alliances: [AllianceDTO] = []
    var selectedId: String?
    /// Game card and rating per alliance (online only; absent while loading or offline).
    var games: [String: GameStatusDTO] = [:]
    var ratings: [String: RatingDTO] = [:]

    /// Before the first response, the device's own streak decides (the server confirms it).
    var unlocked: Bool { community?.unlocked ?? ((goal?.progress.streak ?? 0) >= 7) }
    var streak: Int { community?.streak ?? goal?.progress.streak ?? 0 }
    var locked: Bool { !unlocked && !previewing }
    var selected: AllianceDTO? { alliances.first { $0.id == selectedId } ?? alliances.first }
}

struct CommunityActions {
    var back: () -> Void = {}, retry: () -> Void = {}, cycleShare: () -> Void = {}, startPreview: () -> Void = {}, endPreview: () -> Void = {}
    var showTab: (CommunityTab) -> Void = { _ in }, react: (PostDTO, Reaction) -> Void = { _, _ in }, postMenu: (PostDTO) -> Void = { _ in }
    var create: () -> Void = {}, join: () -> Void = {}, select: (String) -> Void = { _ in }, sampleInvite: () -> Void = {}
    var openChat: (AllianceDTO) -> Void = { _ in }, leave: (AllianceDTO) -> Void = { _ in }, applyNow: (AllianceDTO) -> Void = { _ in }
    var openDms: () -> Void = {}, playGame: (AllianceDTO) -> Void = { _ in }, nudge: (AllianceDTO) -> Void = { _ in }, openBoard: (AllianceDTO) -> Void = { _ in }
}

/// Sheet and form state for the Community screen.
@MainActor @Observable
final class CommunityModel {
    enum Sheet: Identifiable { case create, join; var id: Int { self == .create ? 0 : 1 } }
    // One property per line: @Observable can't track multi-variable declarations.
    var loading = true
    var feedError = false
    var previewing = false
    var tab = CommunityTab.feed
    var selectedId: String?
    var sheet: Sheet?
    var postMenu: PostDTO?
    var leaving: AllianceDTO?
    // Create
    var name = ""
    var shared = false
    var createError: String?
    var busy = false
    // Join
    var code = ""
    var joinError: String?
    var preview: InvitePreviewDTO?
    // Game card and rating
    var games: [String: GameStatusDTO] = [:]
    var ratings: [String: RatingDTO] = [:]

    func reset(_ s: Sheet) { name = ""; shared = false; createError = nil; code = ""; joinError = nil; preview = nil; busy = false; sheet = s }
}

/// Community inside a goal: Feed and Alliance. Wires `CommunityContent` to the stores.
struct CommunityScreen: View {
    let goalId: UUID
    var code: String?
    @Environment(SocialStore.self) private var social
    @Environment(GoalStore.self) private var goals
    @Environment(Toaster.self) private var toaster
    @Environment(NetworkStatus.self) private var network
    @Environment(AppRouter.self) private var router
    @Environment(SessionStore.self) private var session
    @Environment(\.dismiss) private var dismiss
    @State private var model = CommunityModel()

    private var ui: CommunityUI {
        CommunityUI(goal: goals.goals.first { $0.id == goalId }, community: social.community(goalId), loading: model.loading && social.community(goalId) == nil,
                    feedError: model.feedError, previewing: model.previewing, tab: model.tab, share: social.share,
                    alliances: social.alliances.filter { $0.goalId == goalId.uuidString.lowercased() }, selectedId: model.selectedId, games: model.games, ratings: model.ratings)
    }

    var body: some View {
        CommunityContent(ui: ui, actions: CommunityActions(
            back: { dismiss() }, retry: { Task { await refresh() } }, cycleShare: cycleShare,
            startPreview: { model.previewing = true; model.tab = .feed }, endPreview: { model.previewing = false },
            showTab: { t in withAnimation(.easeOut(duration: 0.2)) { model.tab = t } },
            react: { p, r in react(p, r) }, postMenu: { model.postMenu = $0 },
            create: { if !gated() { model.reset(.create) } }, join: { if !gated() { model.reset(.join) } }, select: { model.selectedId = $0; Task { await loadGame() } },
            sampleInvite: { toaster.show(String(localized: "Sample alliance. Invites unlock with Community.")) },
            openChat: { a in if let id = UUID(uuidString: a.id) { router.today.append(Route.allianceChat(id)) } },
            leave: { model.leaving = $0 }, applyNow: applyNow,
            playGame: { a in if let id = UUID(uuidString: a.id) { router.today.append(Route.gameNight(allianceId: id)) } },
            nudge: nudge,
            openBoard: { a in if let id = UUID(uuidString: a.id) { router.today.append(Route.allianceRankings(id)) } },
            openDms: { router.today.append(Route.messages) }
        ))
        .toolbar(.hidden, for: .navigationBar)
        .task {
            if let code { model.tab = .alliance; model.reset(.join); model.code = code; join() }
            await refresh()
        }
        .onChange(of: network.online) { _, now in if now && model.feedError { Task { await refresh() } } }
        .sheet(item: $model.sheet) { s in
            Group {
                if s == .create { CreateAllianceSheet(model: model, goal: ui.goal, onCreate: create) } else { JoinAllianceSheet(model: model, onJoin: join) }
            }
            .presentationDetents([.large]).presentationDragIndicator(.visible).presentationBackground(Theme.bg)
        }
        .confirmationDialog(Text("Report this?"), isPresented: Binding(get: { model.postMenu != nil }, set: { if !$0 { model.postMenu = nil } }), titleVisibility: .visible, presenting: model.postMenu) { p in
            Button("Spam") { report(p, "spam") }
            Button("Harassment or hate") { report(p, "harassment") }
            Button("Something else") { report(p, "other") }
            if p.userId != nil { Button("Block \(p.name)", role: .destructive) { block(p) } }
        } message: { _ in Text("It's hidden for you now. Our team reviews reports; three reports hide it for everyone in the meantime.") }
        .confirmationDialog(Text("Leave \(model.leaving?.name ?? "")?"), isPresented: Binding(get: { model.leaving != nil }, set: { if !$0 { model.leaving = nil } }), titleVisibility: .visible, presenting: model.leaving) { a in
            Button("Leave", role: .destructive) { leave(a) }
        } message: { a in
            if a.role == "owner" && a.memberCount > 1 { Text("The longest-standing member becomes the lead and takes over the plan.") }
            else if a.isShared && a.role != "owner" { Text("The goal and its tasks stay on your plan as a personal goal. You can rejoin with the code.") }
            else { Text("You can rejoin with the invite code while there's room.") }
        }
    }

    private func refresh() async {
        model.feedError = false
        do { try await social.refreshCommunity(goalId); try? await social.refreshAlliances(); model.loading = false; await loadGame() }
        catch { model.loading = false; model.feedError = true }
    }

    /// Game card and rating for the alliance on screen.
    private func loadGame() async {
        guard let a = ui.selected else { return }
        if let s = try? await social.gameStatus(a.id) { model.games[a.id] = s }
        if let r = try? await social.rating(a.id) { model.ratings[a.id] = r }
    }

    private func nudge(_ a: AllianceDTO) {
        Task {
            do { let n = try await social.nudge(a.id); toaster.show(n > 0 ? String(localized: "Nudged. They'll get one reminder today.") : String(localized: "Everyone waiting has already been nudged today."), .ok) }
            catch { toaster.show(offlineOr(error), .warn) }
        }
    }

    private func offlineOr(_ e: Error) -> String { (e as? APIError) == .offline ? String(localized: "You're offline. Try again when you reconnect.") : String(localized: "Something went wrong. Try again.") }

    private func cycleShare() {
        social.cycleShare(userId: session.user?.id, goals: goals)
        toaster.show(String(localized: "Sharing preference updated."), .ok)
    }

    /// Alliances need an open Community; the preview only explains them.
    private func gated() -> Bool {
        guard model.previewing else { return false }
        toaster.show(String(localized: "Alliances unlock at a 7-day streak. \(max(1, 7 - ui.streak)) days to go."))
        return true
    }

    private func react(_ p: PostDTO, _ r: Reaction) {
        guard !model.previewing else { return }
        Task { do { try await social.react(goalId, p, r) } catch { toaster.show(offlineOr(error), .warn) } }
    }
    private func report(_ p: PostDTO, _ reason: String) {
        Task { do { try await social.report(goalId, p, reason: reason); toaster.show(String(localized: "Thanks. It's hidden for you."), .ok) } catch { toaster.show(offlineOr(error), .warn) } }
    }
    private func block(_ p: PostDTO) {
        guard let userId = p.userId else { return }
        Task { do { try await social.block(goalId, userId: userId); toaster.show(String(localized: "\(p.name) is blocked."), .ok) } catch { toaster.show(offlineOr(error), .warn) } }
    }

    private func create() {
        guard !model.busy else { return }
        guard model.name.trimmingCharacters(in: .whitespaces).count >= 3 else { model.createError = String(localized: "Use at least 3 characters."); return }
        model.busy = true; model.createError = nil
        Task {
            defer { model.busy = false }
            do {
                let a = try await social.create(name: model.name, shared: model.shared, goalId: goalId)
                model.sheet = nil; model.tab = .alliance; model.selectedId = a.id
                toaster.show(model.shared ? String(localized: "Alliance created. Everyone who joins gets your goal, deadline and daily tasks.") : String(localized: "Alliance created. Invite up to 14 people."), .ok)
            } catch APIError.server(_, "name_not_allowed", _, _) { model.createError = String(localized: "Pick a different name.") }
            catch { model.createError = offlineOr(error) }
        }
    }

    /// A code; a shared plan shows its preview before joining, own-plan alliances join with this goal at once.
    private func join() {
        guard !model.busy else { return }
        let code = model.code.trimmingCharacters(in: .whitespaces).uppercased()
        guard code.wholeMatch(of: #/[A-Z]{4}-\d{4}/#) != nil else { model.joinError = String(localized: "Codes look like DAWN-4821."); return }
        model.busy = true; model.joinError = nil
        let confirming = model.preview != nil
        Task {
            defer { model.busy = false }
            do {
                if !confirming {
                    let p = try await social.preview(code: code)
                    if p.planMode == "shared" { model.preview = p; return }
                }
                let label = model.preview?.label
                let a = try await social.join(code: code, goalId: confirming ? nil : goalId)
                model.sheet = nil; model.tab = .alliance; model.selectedId = a.id
                toaster.show(a.isShared ? String(localized: "Joined \(a.name). \(label ?? a.name) is now one of your goals.") : String(localized: "You joined \(a.name)."), .ok)
            } catch APIError.server(_, let code, _, _) {
                model.joinError = switch code {
                case "invalid_code_format": String(localized: "Codes look like DAWN-4821.")
                case "invalid_code": String(localized: "That code doesn't match an alliance. Check it with whoever invited you.")
                case "already_member": String(localized: "You're already in this alliance.")
                case "alliance_full": String(localized: "This alliance is full.")
                case "plan_ended": String(localized: "This shared plan has ended.")
                case "goal_limit": String(localized: "You have 3 goals running. Archive one first, or upgrade for more goals.")
                default: String(localized: "Something went wrong. Try again.")
                }
            } catch { model.joinError = offlineOr(error) }
        }
    }

    private func leave(_ a: AllianceDTO) {
        Task { do { try await social.leave(a); model.selectedId = nil; toaster.show(String(localized: "You left \(a.name)."), .ok) } catch { toaster.show(offlineOr(error), .warn) } }
    }
    private func applyNow(_ a: AllianceDTO) {
        Task { do { try await social.applyNow(a); toaster.show(String(localized: "Updated for everyone. Tasks already ticked today stay as they were."), .ok) } catch { toaster.show(offlineOr(error), .warn) } }
    }
}

// MARK: - Content

struct CommunityContent: View {
    let ui: CommunityUI
    let actions: CommunityActions
    var today: Date = .now
    var now: Date = .now

    var body: some View {
        let label = ui.goal?.label ?? ""
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Button(action: actions.back) { Label(label, systemImage: "chevron.left").font(BFont.body(15, .medium)).foregroundStyle(Theme.ink).frame(minHeight: 44) }
                    .padding(.leading, -2).accessibilityLabel(Text("Back"))
                HStack(alignment: .center, spacing: 8) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Community").font(BFont.display(25)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                        Text("People working toward the same goal · \(label)").font(BFont.body(13)).foregroundStyle(Theme.mut)
                    }
                    Spacer(minLength: 0)
                    if !ui.locked && !ui.previewing {
                        Button(action: actions.openDms) {
                            Image(systemName: "lock.fill").font(.system(size: 18)).foregroundStyle(Theme.ink).frame(width: 44, height: 44)
                                .background(Theme.s1, in: Circle()).overlay(Circle().strokeBorder(Theme.line, lineWidth: 1))
                        }
                        .accessibilityLabel(Text("Encrypted messages"))
                    }
                    if !ui.locked { shareChip }
                }
                if ui.locked { locked } else {
                    if ui.previewing { previewBanner }
                    segments
                    if ui.tab == .feed { FeedSection(ui: ui, actions: actions, label: label, now: now) }
                    else { AllianceSection(ui: ui, actions: actions, label: label, today: today, now: now) }
                }
            }
            .padding(.horizontal, Theme.Space.screen).padding(.top, 6).padding(.bottom, 40)
        }
        .background(Theme.bg)
    }

    private var shareChip: some View {
        Button(action: actions.cycleShare) {
            Label(ui.share.chipLabel, systemImage: "eye").font(BFont.body(12, .medium)).foregroundStyle(Theme.ink2).lineLimit(1)
                .padding(.horizontal, 12).frame(minHeight: 36).background(Theme.s1, in: Capsule()).overlay(Capsule().strokeBorder(Theme.line, lineWidth: 1))
                .frame(minHeight: 44)
        }
        .accessibilityLabel(Text("Community sharing: \(ui.share.chipLabel). Tap to change."))
    }

    private var locked: some View {
        ZStack(alignment: .top) {
            VStack(spacing: 10) { ForEach([120, 96, 120, 96], id: \.self) { h in RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Theme.s1).frame(height: CGFloat(h)) } }
                .blur(radius: 6).opacity(0.45).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 14) {
                IconTile(symbol: "lock.fill")
                Text("Community opens at a 7-day streak").font(BFont.display(22)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                Text("Alliances and the shared feed unlock once you've shown up for a week. Build the habit first, then bring people along.").font(BFont.body(14)).foregroundStyle(Theme.mut)
                VStack(alignment: .leading, spacing: 8) {
                    Bar(fraction: Double(min(ui.streak, 7)) / 7, color: Theme.acc, height: 6)
                    Text("\(min(ui.streak, 7)) of 7 days").font(BFont.label(12)).foregroundStyle(Theme.ink2)
                }
                .padding(.top, 4)
                Button(action: actions.startPreview) {
                    Label("Preview Community", systemImage: "eye").font(BFont.body(14, .medium)).foregroundStyle(Theme.ink).frame(maxWidth: .infinity, minHeight: 48)
                        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Theme.line, lineWidth: 1))
                }
                .padding(.top, 4)
            }
            .padding(24).background(Theme.s1, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(Theme.accLine, lineWidth: 1))
            .padding(.top, 60)
        }
        .frame(minHeight: 520, alignment: .top)
    }

    private var previewBanner: some View {
        HStack(spacing: 10) {
            Image(systemName: "eye").accessibilityHidden(true)
            Text("Preview. Posting and joining unlock at a 7-day streak.").font(BFont.body(13)).frame(maxWidth: .infinity, alignment: .leading)
            Button(action: actions.endPreview) {
                Text("Close").font(BFont.body(12, .semibold)).padding(.horizontal, 12).frame(minHeight: 36).overlay(Capsule().strokeBorder(Theme.accLine, lineWidth: 1)).frame(minHeight: 44)
            }
        }
        .foregroundStyle(Theme.accInk).padding(.leading, 14).padding(.trailing, 8).padding(.vertical, 4)
        .background(Theme.accTint, in: RoundedRectangle(cornerRadius: 14, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Theme.accLine, lineWidth: 1))
    }

    private var segments: some View {
        HStack(spacing: 4) {
            ForEach([(CommunityTab.feed, "Feed"), (CommunityTab.alliance, "Alliance")] as [(CommunityTab, LocalizedStringKey)], id: \.0) { t, label in
                let on = ui.tab == t
                Button { actions.showTab(t) } label: {
                    Text(label).font(BFont.body(14, .semibold)).foregroundStyle(on ? Theme.onAcc : Theme.mut).frame(maxWidth: .infinity, minHeight: 40)
                        .background(on ? Theme.acc : .clear, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
                .accessibilityAddTraits(on ? [.isSelected] : [])
            }
        }
        .padding(4).background(Theme.s0, in: RoundedRectangle(cornerRadius: 14, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Theme.line, lineWidth: 1))
    }
}

// MARK: - Feed

private struct FeedSection: View {
    let ui: CommunityUI, actions: CommunityActions, label: String, now: Date

    private var posts: [PostDTO] {
        guard ui.previewing else { return ui.community?.posts ?? [] }
        let t = now.timeIntervalSince1970 * 1000, h = 3_600_000.0
        return [PostDTO(id: "s1", name: "Jonas", kind: "streak", value: 30, createdAt: t - 2 * h, reactions: .init(respect: 12, keepGoing: 4, inspired: 6)),
                PostDTO(id: "s2", name: "Priya", kind: "day_complete", createdAt: t - 4 * h, reactions: .init(respect: 8, keepGoing: 11, inspired: 2), reacted: ["keepGoing"]),
                PostDTO(id: "s3", name: "Leo", kind: "milestone", value: 50, createdAt: t - 26 * h, reactions: .init(respect: 5, keepGoing: 2, inspired: 7))]
    }

    var body: some View {
        VStack(spacing: 10) {
            if ui.feedError && posts.isEmpty {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Couldn't load the feed.").font(BFont.body(14, .medium)).foregroundStyle(Theme.ink)
                    Button("Try again", action: actions.retry).buttonStyle(SecondaryButtonStyle())
                }
                .padding(20).frame(maxWidth: .infinity, alignment: .leading).card(18)
            } else if ui.loading && posts.isEmpty {
                ForEach(0..<3, id: \.self) { _ in RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Theme.s1).frame(height: 112) }.accessibilityHidden(true)
            } else if posts.isEmpty {
                VStack(spacing: 8) {
                    Text("Nothing here yet").font(BFont.display(16)).foregroundStyle(Theme.ink)
                    Text("Streaks and wins from people with a goal like yours show up here.").font(BFont.body(13)).foregroundStyle(Theme.mut).multilineTextAlignment(.center)
                }
                .padding(.vertical, 28).padding(.horizontal, 20).frame(maxWidth: .infinity)
                .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(Theme.line, lineWidth: 1))
            } else {
                if ui.community?.widened == true && !ui.previewing {
                    Text("It's quiet in this category, so you're seeing people with any goal.").font(BFont.body(12)).foregroundStyle(Theme.mut).frame(maxWidth: .infinity, alignment: .leading)
                }
                ForEach(posts) { PostCard(post: $0, label: label, now: now, sample: ui.previewing, actions: actions) }
            }
            Text("Reactions only. Everything you share is opt-in.").font(BFont.body(12)).foregroundStyle(Theme.mut2).multilineTextAlignment(.center).padding(6)
        }
    }
}

private struct PostCard: View {
    let post: PostDTO, label: String, now: Date, sample: Bool, actions: CommunityActions

    private var what: String {
        switch post.kind {
        case "streak": String(localized: "reached a \(post.value ?? 0)-day streak")
        case "milestone": String(localized: "is \(post.value ?? 0)% across the bridge")
        default: String(localized: "completed every task today")
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 12) {
                HStack(alignment: .top, spacing: 12) {
                    Avatar(name: post.name, size: 40)
                    VStack(alignment: .leading, spacing: 3) {
                        (Text(post.name).font(BFont.body(14, .semibold)).foregroundColor(Theme.ink) + Text(" " + what).font(BFont.body(14)).foregroundColor(Theme.ink2))
                        Text("\(label) community · \(ago(post.createdAt, now))").font(BFont.body(12)).foregroundStyle(Theme.mut)
                    }
                    Spacer(minLength: 0)
                    if post.kind == "streak" || post.kind == "milestone" {
                        VStack(alignment: .trailing, spacing: 2) {
                            Text(post.kind == "milestone" ? "\(post.value ?? 0)%" : "\(post.value ?? 0)").font(BFont.display(24)).foregroundStyle(Theme.accInk)
                            Text(post.kind == "milestone" ? "ACROSS" : "DAYS").bridgeLabel(9)
                        }
                    }
                }
                .accessibilityElement(children: .combine)
                if !post.mine && !sample {
                    Button { actions.postMenu(post) } label: { Image(systemName: "ellipsis").rotationEffect(.degrees(90)).foregroundStyle(Theme.mut).frame(width: 44, height: 44) }
                        .padding(.top, -12).padding(.trailing, -12).accessibilityLabel(Text("Post options"))
                }
            }
            FlowLayout(spacing: 6) {
                ForEach(Reaction.allCases, id: \.self) { r in
                    let on = post.reacted.contains(r.rawValue)
                    let n = r == .respect ? post.reactions.respect : r == .keepGoing ? post.reactions.keepGoing : post.reactions.inspired
                    Button { actions.react(post, r) } label: {
                        HStack(spacing: 6) { Text(r.label).font(BFont.body(12, .medium)); Text("\(n)").font(BFont.label(12)) }
                            .foregroundStyle(on ? Theme.accInk : Theme.ink2).padding(.horizontal, 12).frame(minHeight: 36)
                            .background(on ? Theme.accTint : .clear, in: Capsule()).overlay(Capsule().strokeBorder(on ? Theme.acc : Theme.line, lineWidth: 1))
                            .frame(minHeight: 44)
                    }
                    .accessibilityLabel(Text("\(r.label), \(n)")).accessibilityAddTraits(on ? [.isSelected] : [])
                }
            }
        }
        .padding(16).frame(maxWidth: .infinity, alignment: .leading).card(18)
    }
}

// MARK: - Alliances

private struct AllianceSection: View {
    let ui: CommunityUI, actions: CommunityActions, label: String, today: Date, now: Date

    private var sample: Bool { ui.previewing && ui.alliances.isEmpty }
    private var ally: AllianceDTO? {
        guard sample else { return ui.selected }
        let t = now.timeIntervalSince1970 * 1000, h = 3_600_000.0
        return AllianceDTO(id: "sample", name: "Dawn Runners", planMode: "own", inviteCode: "DAWN-4821", startDate: LocalDay.today(now: today.addingTimeInterval(-20 * 86_400)), streak: 14,
                           members: ["Sam", "Mia", "Ana", "Rui", "Tom", "Kai"].enumerated().map { MemberDTO(userId: "m\($0.offset)", name: $0.element, role: $0.offset == 0 ? "owner" : "member") },
                           memberCount: 6, feed: [AllianceFeedDTO(name: "Sam", what: "completed", title: String(localized: "Long run — 12 km"), at: t - h),
                                                  AllianceFeedDTO(name: "Ana", what: "completed", at: t - 3 * h), AllianceFeedDTO(what: "completed", title: String(localized: "Easy run — 5 km"), at: t - 6 * h)])
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let ally {
                if !ui.alliances.isEmpty { chips(ally) }
                if sample { Text("SAMPLE ALLIANCE · HOW YOURS WILL WORK").bridgeLabel(10, color: Theme.accInk) }
                AllianceCard(a: ally, ui: ui, actions: actions, sample: sample, today: today)
                if !sample {
                    GameCard(status: ui.games[ally.id], goalLabel: label, onPlay: { actions.playGame(ally) }, onNudge: { actions.nudge(ally) })
                    if let r = ui.ratings[ally.id] { RatingCard(rating: r) { actions.openBoard(ally) } }
                }
                if sample { tiles }
                Text("SHARED FEED").bridgeLabel(11).padding(.top, 4).accessibilityAddTraits(.isHeader)
                if ally.feed.isEmpty { Text("Completions from members show up here.").font(BFont.body(13)).foregroundStyle(Theme.mut) }
                ForEach(Array(ally.feed.enumerated()), id: \.offset) { _, f in FeedRow(f: f, now: now) }
            } else {
                VStack(alignment: .leading, spacing: 14) {
                    IconTile(symbol: "shield.lefthalf.filled")
                    Text("Find your alliance").font(BFont.display(21)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                    Text("A private group of up to 15 people who keep each other honest. You share a feed and a collective streak. Invite-only.").font(BFont.body(14)).foregroundStyle(Theme.mut)
                    VStack(spacing: 8) {
                        FillButton(title: "Create an alliance", height: 50, action: actions.create)
                        Button("Join with a code", action: actions.join).buttonStyle(SecondaryButtonStyle())
                    }
                    .padding(.top, 4)
                }
                .padding(24).frame(maxWidth: .infinity, alignment: .leading).card(22)
            }
        }
    }

    private func chips(_ ally: AllianceDTO) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("YOUR ALLIANCES · \(label.uppercased()) · \(ui.alliances.count)").bridgeLabel(10)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(ui.alliances) { a in
                        let on = a.id == ally.id
                        Button { actions.select(a.id) } label: {
                            Label(a.name, systemImage: a.isShared ? "list.clipboard" : "shield.lefthalf.filled").font(BFont.body(13, .medium)).foregroundStyle(on ? Theme.onAcc : Theme.ink2).lineLimit(1)
                                .padding(.horizontal, 14).frame(minHeight: 40).background(on ? Theme.acc : Theme.s1, in: Capsule()).overlay(Capsule().strokeBorder(on ? Theme.acc : Theme.line, lineWidth: 1))
                                .frame(minHeight: 44)
                        }
                        .accessibilityAddTraits(on ? [.isSelected] : [])
                    }
                    dashedChip("New", "plus", actions.create)
                    dashedChip("Join", "key", actions.join)
                }
                .padding(2)
            }
        }
    }

    private func dashedChip(_ title: LocalizedStringKey, _ symbol: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: symbol).font(BFont.body(13, .medium)).foregroundStyle(Theme.ink2)
                .padding(.horizontal, 14).frame(minHeight: 40).overlay(Capsule().strokeBorder(Theme.line2, style: StrokeStyle(lineWidth: 1, dash: [5, 4]))).frame(minHeight: 44)
        }
    }

    private var tiles: some View {
        let items: [(String, LocalizedStringKey, LocalizedStringKey)] = [
            ("flame.fill", "Collective streak", "Grows on days every member completes a task."), ("lock.fill", "Private", "Invite-only by link or code. Max 15."),
            ("text.bubble.fill", "Shared feed", "Completions and milestones from members."), ("eye", "You choose", "Only what your sharing setting allows."),
        ]
        return LazyVGrid(columns: [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)], spacing: 8) {
            ForEach(0..<4, id: \.self) { i in
                VStack(alignment: .leading, spacing: 6) {
                    Image(systemName: items[i].0).foregroundStyle(Theme.acc).accessibilityHidden(true)
                    Text(items[i].1).font(BFont.body(13, .semibold)).foregroundStyle(Theme.ink)
                    Text(items[i].2).font(BFont.body(12)).foregroundStyle(Theme.mut)
                }
                .padding(12).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .background(Theme.tiles[i], in: RoundedRectangle(cornerRadius: 14, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Theme.line, lineWidth: 1))
            }
        }
    }
}

private struct AllianceCard: View {
    let a: AllianceDTO, ui: CommunityUI, actions: CommunityActions, sample: Bool, today: Date

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .top, spacing: 4) {
                VStack(alignment: .leading, spacing: 5) {
                    Text(a.name).font(BFont.display(22)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                    Label("INVITE ONLY", systemImage: "lock.fill").bridgeLabel(10)
                }
                Spacer(minLength: 0)
                inviteButton
                if !sample {
                    Menu {
                        Button("Leave alliance", systemImage: "rectangle.portrait.and.arrow.right", role: .destructive) { actions.leave(a) }
                    } label: { Image(systemName: "ellipsis").rotationEffect(.degrees(90)).foregroundStyle(Theme.mut).frame(width: 44, height: 44) }
                    .accessibilityLabel(Text("Alliance options"))
                }
            }
            if let sh = a.shared { SharedPanel(a: a, sh: sh, ui: ui, actions: actions, today: today) }
            FillButton(title: "Open alliance chat", symbol: "bubble.left.and.bubble.right.fill") { if !sample { actions.openChat(a) } }
            HStack(alignment: .top, spacing: 10) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("COLLECTIVE STREAK").bridgeLabel(9.5)
                    (Text("\(a.streak)").font(BFont.display(28)).foregroundColor(Theme.accInk) + Text(" " + String(localized: "days")).font(BFont.body(13)).foregroundColor(Theme.mut))
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                VStack(alignment: .leading, spacing: 4) {
                    Text("MEMBERS").bridgeLabel(9.5)
                    Text("\(a.memberCount) of \(a.cap)").font(BFont.display(28)).foregroundStyle(Theme.ink)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            HStack(spacing: -8) {
                ForEach(Array(a.members.prefix(10).enumerated()), id: \.offset) { _, m in
                    Avatar(name: m.name, size: 30, ink: Theme.ink2).opacity(m.away ? 0.45 : 1).padding(2).background(Theme.s1, in: Circle())
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text(a.members.map { $0.away ? String(localized: "\($0.name), away") : $0.name }.joined(separator: ", ")))
        }
        .padding(20).card(22)
    }

    @ViewBuilder private var inviteButton: some View {
        let chip = Label("Invite", systemImage: "link").font(BFont.body(13, .semibold)).foregroundStyle(Theme.accInk)
            .padding(.horizontal, 14).frame(minHeight: 40).overlay(Capsule().strokeBorder(Theme.accLine, lineWidth: 1)).frame(minHeight: 44)
        if sample { Button(action: actions.sampleInvite) { chip } }
        else if let url = URL(string: "https://thebridge.app/join/\(a.inviteCode)") {
            // The system share sheet: the invite is a link with the code, nothing else about the goal.
            ShareLink(item: url, message: Text("Join my alliance \(a.name) on The Bridge (code \(a.inviteCode))")) { chip }
        }
    }
}

private struct SharedPanel: View {
    let a: AllianceDTO, sh: SharedPlanDTO, ui: CommunityUI, actions: CommunityActions, today: Date

    var body: some View {
        let todayStr = LocalDay.today(now: today)
        let groupTotal = max(1, Self.days(a.startDate, sh.deadline) + 1)
        let groupDay = min(max(1, Self.days(a.startDate, todayStr) + 1), groupTotal)
        let myDay = ui.goal?.progress.day ?? groupDay, myTotal = ui.goal?.progress.total ?? groupTotal
        let late = !sh.isLead && myDay < groupDay
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                Label("SHARED PLAN", systemImage: "list.clipboard").bridgeLabel(10, color: Theme.accInk).lineLimit(1)
                Spacer(minLength: 0)
                Text(sh.isLead ? String(localized: "Set by you") : String(localized: "Set by \(sh.lead)")).font(BFont.body(12, .medium)).foregroundStyle(Theme.ink2).multilineTextAlignment(.trailing)
            }
            Text(sh.goal).font(BFont.body(14, .medium)).foregroundStyle(Theme.ink)
            Text("DUE \(Self.dueText(sh.deadline)) · \(ui.goal?.tasks.count ?? sh.me.total) DAILY TASKS").bridgeLabel(10)
            line(String(localized: "You"), String(localized: "Day \(myDay) of \(myTotal)"), max(0.02, Double(myDay) / Double(myTotal)), Theme.acc)
            if late { line(String(localized: "Group"), String(localized: "Day \(groupDay) of \(groupTotal)"), Double(groupDay) / Double(groupTotal), Theme.ink2) }
            Group {
                if sh.isLead { Text("You set this plan. Task changes reach every member the next morning.") }
                else if late { Text("You joined \(groupDay - myDay) days after the group. Same tasks and deadline, tracked on your own timeline.") }
                else { Text("Plan set by \(sh.lead). Only they can change the tasks.") }
            }
            .font(BFont.body(12.5)).foregroundStyle(Theme.mut)
            if sh.isLead && a.memberCount > 1 {
                Button("Apply today's changes now") { actions.applyNow(a) }.buttonStyle(SecondaryButtonStyle())
            }
        }
        .padding(14).background(Theme.bg, in: RoundedRectangle(cornerRadius: 16, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(Theme.line, lineWidth: 1))
    }

    private func line(_ who: String, _ value: String, _ fraction: Double, _ color: Color) -> some View {
        VStack(spacing: 5) {
            HStack { Text(who).font(BFont.body(13, .medium)).foregroundStyle(Theme.ink); Spacer(); Text(value).font(BFont.label(12)).foregroundStyle(Theme.ink2) }
            Bar(fraction: fraction, color: color, height: 5)
        }
        .accessibilityElement(children: .combine)
    }

    static func days(_ from: String, _ to: String) -> Int {
        let f = DateFormatter(); f.calendar = Calendar(identifier: .gregorian); f.dateFormat = "yyyy-MM-dd"; f.timeZone = TimeZone(identifier: "UTC")
        guard let a = f.date(from: from), let b = f.date(from: to) else { return 0 }
        return Int((b.timeIntervalSince(a) / 86_400).rounded())
    }
    static func dueText(_ iso: String) -> String {
        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"; f.timeZone = TimeZone(identifier: "UTC")
        guard let d = f.date(from: iso) else { return iso }
        return d.formatted(Date.FormatStyle(timeZone: TimeZone(identifier: "UTC")!).month(.abbreviated).day().year()).uppercased()
    }
}

private struct FeedRow: View {
    let f: AllianceFeedDTO, now: Date
    var body: some View {
        let who = f.name ?? String(localized: "You")
        let what = f.what == "joined" ? String(localized: "joined the alliance") : f.title.map { String(localized: "completed \($0)") } ?? String(localized: "completed a task")
        HStack(spacing: 12) {
            Avatar(name: who, size: 34)
            (Text(who).font(BFont.body(14, .semibold)).foregroundColor(Theme.ink) + Text(" " + what).font(BFont.body(14)).foregroundColor(Theme.ink2)).frame(maxWidth: .infinity, alignment: .leading)
            Text(ago(f.at, now)).font(BFont.label(11)).foregroundStyle(Theme.mut2)
        }
        .padding(.horizontal, 14).padding(.vertical, 12).card(16).accessibilityElement(children: .combine)
    }
}

// MARK: - Sheets

struct CreateAllianceSheet: View {
    @Bindable var model: CommunityModel
    let goal: GoalState?
    var onCreate: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Create an alliance").font(BFont.display(20)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                BridgeField(label: "NAME", placeholder: "e.g. Early Risers", text: $model.name, error: model.createError, capitalization: .words)
                VStack(alignment: .leading, spacing: 8) {
                    Text("PLAN").bridgeLabel(11)
                    option(!model.shared, "Everyone keeps their own plan", "Members work toward the same kind of goal with their own tasks and deadlines.") { model.shared = false }
                    option(model.shared, "One shared plan", "Everyone who joins gets your goal, deadline and daily tasks. Good for a teacher and a class, a coach and a team, or a study group.") { model.shared = true }
                }
                if model.shared, let goal {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("MEMBERS WILL GET · \(goal.label.uppercased())").bridgeLabel(10, color: Theme.accInk)
                        Text(goal.statement).font(BFont.body(14, .medium)).foregroundStyle(Theme.ink)
                        Text("Due \(SharedPanel.dueText(goal.deadline).capitalized) · \(goal.tasks.count) daily tasks").font(BFont.body(12)).foregroundStyle(Theme.ink2)
                        Text("Only you can change the tasks. People who join late start at day 1 with the same deadline.").font(BFont.body(12)).foregroundStyle(Theme.mut)
                    }
                    .padding(14).frame(maxWidth: .infinity, alignment: .leading).background(Theme.accTint, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
                Label("Private by default. Up to 15 members, invite-only by link or code.", systemImage: "lock.fill").font(BFont.body(12)).foregroundStyle(Theme.mut)
                Button(action: onCreate) {
                    if model.busy { ProgressView().tint(Theme.onAcc) } else { Text(model.shared ? "Create and share plan" : "Create alliance") }
                }
                .buttonStyle(PrimaryButtonStyle()).disabled(model.busy)
            }
            .padding(24)
        }
    }

    private func option(_ on: Bool, _ title: LocalizedStringKey, _ sub: LocalizedStringKey, _ pick: @escaping () -> Void) -> some View {
        Button(action: pick) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: on ? "largecircle.fill.circle" : "circle").font(.system(size: 20)).foregroundStyle(on ? Theme.acc : Theme.mut)
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(BFont.body(15, .semibold)).foregroundStyle(Theme.ink)
                    Text(sub).font(BFont.body(13)).foregroundStyle(Theme.mut).multilineTextAlignment(.leading)
                }
                Spacer(minLength: 0)
            }
            .padding(14).overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(on ? Theme.acc : Theme.line, lineWidth: on ? 1.5 : 1))
        }
        .accessibilityAddTraits(on ? [.isSelected] : [])
    }
}

/// Code entry, then (for a shared plan) the invite preview with the pace note.
struct JoinAllianceSheet: View {
    @Bindable var model: CommunityModel
    var onJoin: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let p = model.preview { preview(p) } else {
                    Text("Join with a code").font(BFont.display(20)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                    BridgeField(label: "INVITE CODE", placeholder: "DAWN-4821", text: $model.code, error: model.joinError, capitalization: .characters)
                    Button(action: onJoin) { if model.busy { ProgressView().tint(Theme.onAcc) } else { Text("Join alliance") } }.buttonStyle(PrimaryButtonStyle()).disabled(model.busy)
                }
            }
            .padding(24)
        }
    }

    @ViewBuilder private func preview(_ p: InvitePreviewDTO) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("SHARED PLAN").bridgeLabel(10, color: Theme.accInk)
            Text(p.name).font(BFont.display(20)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
            Text("Set by \(p.lead) · \(p.members) members · \(p.spotsLeft) spots left").font(BFont.body(13)).foregroundStyle(Theme.mut)
        }
        VStack(alignment: .leading, spacing: 10) {
            Text(p.goal ?? "").font(BFont.body(15, .medium)).foregroundStyle(Theme.ink)
            if let d = p.deadline { Label("Due \(SharedPanel.dueText(d).capitalized)", systemImage: "calendar").font(BFont.body(13)).foregroundStyle(Theme.ink2) }
            Divider().overlay(Theme.line)
            Text("DAILY TASKS").bridgeLabel(10)
            ForEach(Array(p.tasks.enumerated()), id: \.offset) { _, t in
                HStack(alignment: .firstTextBaseline) {
                    Text(t.title).font(BFont.body(14)).foregroundStyle(Theme.ink)
                    Spacer()
                    if let time = t.timeOfDay { Text(time).font(BFont.label(11)).foregroundStyle(Theme.mut) }
                }
            }
        }
        .padding(16).frame(maxWidth: .infinity, alignment: .leading).card(18)
        if let pace = p.pace {
            Label {
                Group {
                    if pace.groupDay > 1 { Text("The group started \(pace.groupDay - 1) days ago and is on day \(pace.groupDay) of \(pace.groupTotal). You start at day 1 with the same deadline, which gives you \(pace.yourTotal) days. Days before you joined don't count against you or the group's streak.") }
                    else { Text("You start at day 1 with the same deadline, which gives you \(pace.yourTotal) days.") }
                }
                .font(BFont.body(13))
            } icon: { Image(systemName: "clock") }
            .foregroundStyle(Theme.accInk).padding(12).frame(maxWidth: .infinity, alignment: .leading).background(Theme.accTint, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        if let e = model.joinError { Text(e).font(BFont.body(13)).foregroundStyle(Theme.bad) }
        Button(action: onJoin) { if model.busy { ProgressView().tint(Theme.onAcc) } else { Text("Join and add this goal") } }.buttonStyle(PrimaryButtonStyle()).disabled(model.busy)
        Button("Use a different code") { model.preview = nil; model.code = ""; model.joinError = nil }.buttonStyle(SecondaryButtonStyle())
    }
}

// MARK: - Pieces

struct Avatar: View {
    let name: String?
    let size: CGFloat
    var ink: Color = Theme.accInk
    var body: some View {
        Circle().fill(Theme.av).frame(width: size, height: size)
            .overlay(Text((name ?? "").prefix(1).uppercased()).font(BFont.display(size * 0.38)).foregroundStyle(ink))
            .accessibilityHidden(true)
    }
}

private struct IconTile: View {
    let symbol: String
    var body: some View {
        Image(systemName: symbol).font(.system(size: 22)).foregroundStyle(Theme.acc).frame(width: 48, height: 48)
            .background(Theme.accTint, in: RoundedRectangle(cornerRadius: 14, style: .continuous)).accessibilityHidden(true)
    }
}

struct Bar: View {
    let fraction: Double, color: Color, height: CGFloat
    var body: some View {
        GeometryReader { g in
            Capsule().fill(Theme.track).overlay(alignment: .leading) { Capsule().fill(color).frame(width: g.size.width * min(1, max(0, fraction))) }
        }
        .frame(height: height)
    }
}

struct FillButton: View {
    let title: LocalizedStringKey
    var symbol: String?
    var height: CGFloat = 48
    var action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let symbol { Image(systemName: symbol).accessibilityHidden(true) }
                Text(title).font(BFont.body(height > 48 ? 15 : 14, .semibold))
            }
            .foregroundStyle(Theme.onAcc).frame(maxWidth: .infinity, minHeight: height)
            .background(Theme.acc, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
    }
}

/// Wraps chips onto new lines when they don't fit.
struct FlowLayout: Layout {
    var spacing: CGFloat = 6
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, row: CGFloat = 0, widest: CGFloat = 0
        for s in subviews {
            let size = s.sizeThatFits(.unspecified)
            if x > 0 && x + size.width > width { y += row + spacing; x = 0; row = 0 }
            x += size.width + spacing; row = max(row, size.height); widest = max(widest, x - spacing)
        }
        return CGSize(width: min(widest, width), height: y + row)
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, row: CGFloat = 0
        for s in subviews {
            let size = s.sizeThatFits(.unspecified)
            if x > bounds.minX && x + size.width > bounds.maxX { y += row + spacing; x = bounds.minX; row = 0 }
            s.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing; row = max(row, size.height)
        }
    }
}

extension View {
    /// s1 surface, 1pt line, continuous corners.
    func card(_ radius: CGFloat) -> some View {
        background(Theme.s1, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: radius, style: .continuous).strokeBorder(Theme.line, lineWidth: 1))
    }
}

/// "now", "5m", "3h", "2d". `ms` is epoch milliseconds.
func ago(_ ms: Double, _ now: Date) -> String {
    let m = max(0, Int((now.timeIntervalSince1970 * 1000 - ms) / 60_000))
    if m < 1 { return String(localized: "now") }
    if m < 60 { return String(localized: "\(m)m") }
    if m < 60 * 24 { return String(localized: "\(m / 60)h") }
    return String(localized: "\(m / 1440)d")
}
