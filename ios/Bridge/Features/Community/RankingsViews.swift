import SwiftUI

/// Gold / silver / bronze tier colour and label.
enum Tier {
    static func color(_ t: String) -> Color { t == "gold" ? Theme.accInk : t == "silver" ? Theme.ink2 : Theme.bronze }
    static func label(_ t: String) -> String { t == "gold" ? String(localized: "GOLD") : t == "silver" ? String(localized: "SILVER") : String(localized: "BRONZE") }
}

/// Game night card: open when every active member has finished today's tasks for this goal. Names who's left, never their tasks.
struct GameCard: View {
    let status: GameStatusDTO?
    let goalLabel: String
    var onPlay: () -> Void
    var onNudge: () -> Void

    var body: some View {
        let open = status?.unlocked == true
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                Image(systemName: open ? "gamecontroller.fill" : "clock").font(.system(size: 22)).foregroundStyle(Theme.acc).frame(width: 44, height: 44)
                    .background(Theme.accTint, in: RoundedRectangle(cornerRadius: 14, style: .continuous)).accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 3) {
                    Text("Game night · Who's Drifting?").font(BFont.display(16)).foregroundStyle(Theme.ink)
                    statusText.font(BFont.body(12)).foregroundStyle(Theme.mut)
                }
            }
            .accessibilityElement(children: .combine)
            HStack(spacing: 8) {
                // Opens once everyone has finished; with fewer than 4 eligible (or after today's ranked game) it's a practice round.
                Button(action: onPlay) {
                    Group {
                        if !open { Text("Unlocks when everyone finishes") }
                        else if status!.rankedToday || status!.needsPlayers { Text("Play a practice round") }
                        else { Text("Play now · 2 min") }
                    }
                    .font(BFont.body(14, .semibold)).foregroundStyle(open ? Theme.onAcc : Theme.mut).frame(maxWidth: .infinity, minHeight: 46)
                    .background(open ? Theme.acc : Theme.track, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .disabled(!open)
                if !open, let s = status, !s.waiting.isEmpty {
                    Button(action: onNudge) {
                        Text("Nudge").font(BFont.body(14, .medium)).foregroundStyle(Theme.ink).padding(.horizontal, 16).frame(minHeight: 46)
                            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(Theme.line, lineWidth: 1))
                    }
                }
            }
        }
        .padding(16).background(Theme.s1, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(open ? Theme.accLine : Theme.line, lineWidth: 1))
    }

    private var statusText: Text {
        guard let s = status else { return Text("Unlocks when everyone finishes") }
        if s.unlocked && s.rankedToday { return Text("Today's ranked game is done. Rematches are just for fun.") }
        if s.unlocked && s.needsPlayers { return Text("Everyone finished today. Game night needs 4 players to count, so this one is practice.") }
        if s.unlocked { return Text("Everyone finished today. Game night is open.") }
        if let left = s.meLeft, left > 0 { return Text("\(s.finished) of \(s.eligible) finished · waiting on you (\(left) \(goalLabel) tasks left). Other goals don't count.") }
        return Text("\(s.finished) of \(s.eligible) finished · waiting on \(s.waiting.joined(separator: ", ")).")
    }
}

/// Overall score in a tier ring, rank on the board, and the three parts.
struct RatingCard: View {
    let rating: RatingDTO
    var onBoard: () -> Void

    var body: some View {
        let tone = Tier.color(rating.tier)
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 14) {
                Text("\(rating.overall)").font(BFont.display(22)).foregroundStyle(tone).frame(width: 64, height: 64).overlay(Circle().strokeBorder(tone, lineWidth: 2))
                VStack(alignment: .leading, spacing: 4) {
                    Text("\(Tier.label(rating.tier)) TIER").bridgeLabel(10, color: tone)
                    Text("Alliance rating").font(BFont.display(15)).foregroundStyle(Theme.ink)
                    if rating.rank > 0 { Text("#\(rating.rank) OF \(rating.of) ALLIANCES").bridgeLabel(10) }
                }
                .accessibilityElement(children: .combine)
                Spacer(minLength: 0)
                Button(action: onBoard) { Image(systemName: "list.number").foregroundStyle(Theme.ink).frame(width: 44, height: 44).overlay(Circle().strokeBorder(Theme.line, lineWidth: 1)) }
                    .accessibilityLabel(Text("Rankings"))
            }
            row("Productive", rating.productivity, "Share of daily tasks members complete")
            row("Efficient", rating.efficiency, "On-time tasks and streak consistency")
            row("Fun", rating.funScore, "Game nights, reactions and chat")
        }
        .padding(16).card(20)
    }

    private func row(_ k: LocalizedStringKey, _ v: Int, _ sub: LocalizedStringKey) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack { Text(k).font(BFont.body(13, .medium)).foregroundStyle(Theme.ink); Spacer(); Text("\(v)").font(BFont.label(12)).foregroundStyle(Theme.ink2) }
            Bar(fraction: Double(v) / 100, color: Theme.acc, height: 5)
            Text(sub).font(BFont.body(11)).foregroundStyle(Theme.mut2)
        }
        .accessibilityElement(children: .combine)
    }
}

/// Weekly board for the alliance's goal category and size band.
struct RankingsScreen: View {
    let allianceId: String
    @Environment(SocialStore.self) private var social
    @Environment(\.dismiss) private var dismiss
    @State private var board: BoardDTO?
    @State private var failed = false

    var body: some View {
        RankingsContent(board: board, failed: failed, onBack: { dismiss() }, onRetry: { Task { await load() } })
            .toolbar(.hidden, for: .navigationBar)
            .task { await load() }
    }

    private func load() async {
        failed = false
        do { board = try await social.rankings(allianceId) } catch { failed = true }
    }
}

struct RankingsContent: View {
    let board: BoardDTO?
    let failed: Bool
    var onBack: () -> Void = {}
    var onRetry: () -> Void = {}

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 4) {
                Button(action: onBack) { Image(systemName: "arrow.left").font(.system(size: 22)).foregroundStyle(Theme.ink).frame(width: 44, height: 44) }.accessibilityLabel(Text("Back"))
                Text("Alliance rankings").font(BFont.display(22)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                Spacer()
            }
            .padding(.leading, 10).padding(.vertical, 4)
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Scored weekly on how productive, efficient and fun each alliance is, against alliances with the same kind of goal and a similar size. Resets every Monday.")
                        .font(BFont.body(13)).foregroundStyle(Theme.mut).padding(.horizontal, 4).padding(.bottom, 6)
                    if failed && board == nil {
                        Text("Couldn't load the rankings.").font(BFont.body(14, .medium)).foregroundStyle(Theme.ink)
                        Button("Try again", action: onRetry).buttonStyle(SecondaryButtonStyle())
                    }
                    ForEach(board?.rows ?? [], id: \.rank) { b in
                        let tone = Tier.color(b.tier)
                        HStack(spacing: 12) {
                            Text("#\(b.rank)").font(BFont.label(14)).fontWeight(.semibold).foregroundStyle(Theme.mut).frame(width: 40, alignment: .leading)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(b.name).font(BFont.body(15, .semibold)).foregroundStyle(Theme.ink)
                                Text("\(b.members) members").font(BFont.body(12)).foregroundStyle(Theme.mut)
                            }
                            Spacer(minLength: 0)
                            VStack(alignment: .trailing, spacing: 3) {
                                Text("\(b.score)").font(BFont.display(20)).fontWeight(.bold).foregroundStyle(tone)
                                Text(Tier.label(b.tier)).bridgeLabel(9.5, color: tone)
                            }
                        }
                        .padding(.horizontal, 14).padding(.vertical, 12)
                        .background(b.mine ? Theme.accTint : Theme.s1, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(b.mine ? Theme.acc : Theme.line, lineWidth: 1))
                        .accessibilityElement(children: .combine)
                    }
                }
                .padding(.horizontal, 16).padding(.top, 8).padding(.bottom, 40)
            }
        }
        .background(Theme.bg)
    }
}

// MARK: - Hall of Fame

struct HallOfFameScreen: View {
    @Environment(SocialStore.self) private var social
    @Environment(SessionStore.self) private var session
    @Environment(GoalStore.self) private var goals
    @Environment(\.dismiss) private var dismiss
    @State private var badges: BadgesDTO?

    var body: some View {
        HallOfFameContent(badges: badges, onBack: { dismiss() }, onPublic: { on in
            badges?.public = on
            if let id = session.user?.id { goals.saveSetting(userId: id, "badgesPublic", on) }
        })
        .toolbar(.hidden, for: .navigationBar)
        .task { badges = try? await social.badges() }
    }
}

struct HallOfFameContent: View {
    let badges: BadgesDTO?
    var onBack: () -> Void = {}
    var onPublic: (Bool) -> Void = { _ in }

    private static let defs: [(key: String, glyph: String, name: LocalizedStringKey, hint: LocalizedStringKey)] = [
        ("first", "I", "First Step", "Complete your first task"), ("s7", "7", "7-Day Streak", "Complete a 7-day streak to earn this"),
        ("s21", "21", "21-Day Streak", "Keep a 21-day streak"), ("arch", "A", "Plan Architect", "Edit your AI plan"),
        ("s30", "30", "30-Day Streak", "Keep a 30-day streak"), ("week", "W", "Perfect Week", "Finish every task 7 days running"),
        ("ref", "J", "Reflector", "Write 30 journal entries"), ("ally", "Δ", "Alliance Founder", "Create an alliance"), ("goal", "∞", "Crossed", "Achieve your goal"),
    ]

    var body: some View {
        let byKey = Dictionary(uniqueKeysWithValues: (badges?.badges ?? []).map { ($0.key, $0) })
        VStack(spacing: 0) {
            HStack(spacing: 4) {
                Button(action: onBack) { Image(systemName: "arrow.left").font(.system(size: 22)).foregroundStyle(Theme.ink).frame(width: 44, height: 44) }.accessibilityLabel(Text("Back"))
                Text("Hall of Fame").font(BFont.display(22)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                Spacer()
            }
            .padding(.leading, 10).padding(.vertical, 4)
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    Text("\(byKey.values.filter(\.earned).count) EARNED").bridgeLabel(11, color: Theme.acc)
                    Toggle(isOn: Binding(get: { badges?.public ?? false }, set: onPublic)) {
                        VStack(alignment: .leading, spacing: 3) {
                            Text("Public Hall of Fame").font(BFont.body(15, .medium)).foregroundStyle(Theme.ink)
                            Text(badges?.public == true ? "Visible to the community" : "Only you can see this" as LocalizedStringKey).font(BFont.body(12)).foregroundStyle(Theme.mut)
                        }
                    }
                    .tint(Theme.okFill).disabled(badges == nil).padding(.horizontal, 16).frame(minHeight: 60).card(16)
                    // Top-aligned: tiles with a progress bar are taller, and badges in a row should line up.
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10, alignment: .top), count: 3), spacing: 18) {
                        ForEach(Self.defs.indices, id: \.self) { i in tile(Self.defs[i], byKey[Self.defs[i].key]) }
                    }
                }
                .padding(.horizontal, 20).padding(.top, 8).padding(.bottom, 40)
            }
        }
        .background(Theme.bg)
    }

    private func tile(_ d: (key: String, glyph: String, name: LocalizedStringKey, hint: LocalizedStringKey), _ b: BadgeDTO?) -> some View {
        let earned = b?.earned == true
        return VStack(spacing: 8) {
            Hexagon().fill(earned ? Theme.acc : Theme.track).frame(width: 76, height: 86)
                .overlay(Hexagon().fill(Theme.s1).padding(2.5))
                .overlay(Text(d.glyph).font(BFont.display(22)).foregroundStyle(earned ? Theme.accInk : Theme.mut2))
                .accessibilityHidden(true)
            Text(d.name).font(BFont.body(13, .semibold)).foregroundStyle(earned ? Theme.ink : Theme.mut).multilineTextAlignment(.center)
            Group {
                if earned { Text("Earned") }
                else if let goal = b?.goal, d.key == "ref" { Text("\(max(0, goal - (b?.progress ?? 0))) entries to go") }
                else if let goal = b?.goal { Text("\(max(0, goal - (b?.progress ?? 0))) days to go") }
                else { Text(d.hint) }
            }
            .font(BFont.body(11)).foregroundStyle(Theme.mut).multilineTextAlignment(.center).frame(minHeight: 28, alignment: .top)
            if !earned, let goal = b?.goal { Bar(fraction: Double(b?.progress ?? 0) / Double(goal), color: Theme.mut, height: 3).frame(width: 60) }
        }
        .accessibilityElement(children: .combine)
    }
}

/// The prototype's hexagon badge outline.
struct Hexagon: Shape {
    func path(in r: CGRect) -> Path {
        Path { p in
            p.move(to: CGPoint(x: r.midX, y: r.minY)); p.addLine(to: CGPoint(x: r.maxX, y: r.minY + r.height * 0.25))
            p.addLine(to: CGPoint(x: r.maxX, y: r.minY + r.height * 0.75)); p.addLine(to: CGPoint(x: r.midX, y: r.maxY))
            p.addLine(to: CGPoint(x: r.minX, y: r.minY + r.height * 0.75)); p.addLine(to: CGPoint(x: r.minX, y: r.minY + r.height * 0.25)); p.closeSubpath()
        }
    }
}
