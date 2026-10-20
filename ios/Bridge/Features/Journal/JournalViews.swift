import SwiftUI

@MainActor let moodLabels: [LocalizedStringKey] = ["Drained", "Low", "Steady", "Good", "Strong"]

enum SaveState { case idle, saving, saved, savedOffline }

/// Ring that fills from the bottom: 20% for Drained up to 100% for Strong.
struct MoodRing: View {
    let index: Int, on: Bool, size: CGFloat
    var body: some View {
        Circle().stroke(on ? Theme.acc : Theme.line, lineWidth: 1.5)
            .background(alignment: .bottom) { Rectangle().fill(on ? Theme.acc : Theme.mut2).frame(height: size * CGFloat(index + 1) * 0.2) }
            .clipShape(Circle()).frame(width: size, height: size)
    }
}

/// Journal tab, or the journal pushed from Today (`goalId` set, with a back link to Today).
struct JournalView: View {
    var goalId: UUID?
    @Environment(\.dynamicTypeSize) private var typeSize
    var onBack: (() -> Void)?
    @Environment(GoalStore.self) private var store
    @Environment(NetworkStatus.self) private var network
    @Environment(Toaster.self) private var toaster
    @State private var tab = 0
    @State private var selected: UUID?
    @State private var filter: UUID?
    @State private var mood: Int?
    @State private var text = ""
    @State private var save = SaveState.idle
    @State private var saveTask: Task<Void, Never>?
    @State private var openEntry: JournalEntity?
    @State private var today = LocalDay.today()

    private var goal: GoalState? { store.goals.first { $0.id == selected } ?? store.goals.first }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                if let onBack {
                    Button(action: onBack) { Label("Today", systemImage: "arrow.left").font(BFont.body(14, .medium)).foregroundStyle(Theme.ink2).frame(minHeight: 44) }.padding(.leading, -4)
                }
                HStack {
                    Text("Journal").font(BFont.display(25)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                    Spacer()
                    Picker("", selection: $tab) { Text("Today").tag(0); Text("History").tag(1) }.pickerStyle(.segmented).frame(width: 170)
                }
                if store.goals.count > 1 { goalChips }
                if tab == 0 { todayEntry } else { history }
            }
            .padding(.horizontal, 20).padding(.top, 6).padding(.bottom, 32)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(Theme.bg)
        .toolbar(.hidden, for: .navigationBar)
        .onAppear { load(goalId ?? goal?.id) }
        .sheet(item: $openEntry) { e in EntrySheet(entry: e, day: dayIndex(e)) { body in store.saveJournal(goalId: e.goalId, date: e.localDate, mood: e.mood, body: body); openEntry = nil; toaster.show(String(localized: "Entry saved."), .ok); if e.localDate == today && e.goalId == goal?.id { text = body } }.presentationDetents([.large]).presentationBackground(Theme.s1) }
    }

    private func load(_ id: UUID?) {
        selected = id; today = LocalDay.today()
        let e = store.journalEntry(goalId: id, date: today)
        mood = e?.mood; text = e?.body ?? ""; save = .idle
    }

    /// Autosave shortly after typing stops. Saving is local, so it works offline ("Saved on this phone").
    private func scheduleSave(_ wait: Double = 0.6) {
        save = .saving
        saveTask?.cancel()
        saveTask = Task {
            try? await Task.sleep(for: .seconds(wait))
            guard !Task.isCancelled else { return }
            store.saveJournal(goalId: goal?.id, date: today, mood: mood, body: text)
            save = network.online ? .saved : .savedOffline
        }
    }

    private var goalChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                if tab == 1 { chip(String(localized: "All goals"), on: filter == nil) { filter = nil } }
                ForEach(store.goals) { g in
                    chip(g.label, on: tab == 0 ? g.id == goal?.id : filter == g.id) { if tab == 0 { load(g.id) } else { filter = g.id } }
                }
            }
        }
    }

    private func chip(_ label: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label).font(BFont.body(13, .semibold)).foregroundStyle(on ? Theme.ink : Theme.ink2).padding(.horizontal, 14).frame(minHeight: 40)
                .background(on ? Theme.accTint : Theme.s1, in: Capsule()).overlay(Capsule().stroke(on ? Theme.acc : Theme.line, lineWidth: 1))
        }
        .accessibilityAddTraits(on ? [.isSelected] : [])
    }

    @ViewBuilder private var todayEntry: some View {
        let day = goal?.progress.day
        Text("\(Day.parse(today)!.formatted(.dateTime.weekday(.wide).month(.wide).day()).uppercased())\(day.map { " · DAY \($0)" } ?? "")").bridgeLabel(11)
        if let current = store.journalEntry(goalId: goal?.id, date: today), let other = store.journalConflict(of: current.id) { conflictBanner(current, other) }
        VStack(alignment: .leading, spacing: 10) {
            (Text("How are you feeling? ").foregroundColor(Theme.ink2) + Text("Optional").foregroundColor(Theme.mut2)).font(BFont.body(13, .medium))
            // Accessibility text sizes: two rows (3 + 2, same widths) so "Drained" and "Strong" aren't cut off.
            let rows: [[Int]] = typeSize.isAccessibilitySize ? [[0, 1, 2], [3, 4]] : [[0, 1, 2, 3, 4]]
            VStack(spacing: 6) {
                ForEach(rows, id: \.self) { row in
                    HStack(spacing: 6) {
                        ForEach(row, id: \.self) { i in
                            Button { mood = mood == i ? nil : i; scheduleSave(0) } label: {
                                VStack(spacing: 8) { MoodRing(index: i, on: mood == i, size: 24); Text(moodLabels[i]).font(BFont.body(11, .medium)).foregroundStyle(mood == i ? Theme.ink : Theme.mut).lineLimit(1) }
                                    .frame(maxWidth: .infinity, minHeight: 70).background(mood == i ? Theme.accTint : .clear, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                                    .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Theme.line, lineWidth: 1))
                            }
                            .accessibilityLabel(moodLabels[i]).accessibilityAddTraits(mood == i ? [.isSelected] : [])
                        }
                        ForEach(0..<(rows[0].count - row.count), id: \.self) { _ in Color.clear.frame(maxWidth: .infinity) }
                    }
                }
            }
        }
        TextField("How did today go? What did you learn? What will you do differently tomorrow?", text: Binding(get: { text }, set: { text = String($0.prefix(10_000)); scheduleSave() }), axis: .vertical)
            .font(BFont.body(16)).lineSpacing(6).foregroundStyle(Theme.ink).padding(20).frame(maxWidth: .infinity, minHeight: 300, alignment: .topLeading)
            .background(Theme.s1, in: RoundedRectangle(cornerRadius: 20, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(Theme.line, lineWidth: 1))
        HStack {
            Group {
                switch save {
                case .saving: Label("Saving…", systemImage: "arrow.triangle.2.circlepath").foregroundStyle(Theme.mut)
                case .saved: Label("Saved", systemImage: "checkmark.icloud").foregroundStyle(Theme.ok)
                case .savedOffline: Label("Saved on this phone · syncs later", systemImage: "icloud.slash").foregroundStyle(Theme.warn)
                case .idle: Label("Auto-saves as you type", systemImage: "icloud").foregroundStyle(Theme.mut)
                }
            }
            .font(BFont.body(12))
            Spacer()
            let words = text.split(whereSeparator: \.isWhitespace).count
            if text.count >= 9_000 { Text("\(text.count) / 10000").font(BFont.label(12)).foregroundStyle(Theme.warn) }
            else if words > 0 { Text("\(words) words").font(BFont.label(12)).foregroundStyle(Theme.mut) }
        }
        if !text.trimmingCharacters(in: .whitespaces).isEmpty {
            Button {
                saveTask?.cancel()
                store.saveJournal(goalId: goal?.id, date: today, mood: mood, body: text)
                toaster.show(String(localized: "Locked in. See you tomorrow."), .ok)
                onBack?()
            } label: { Label("Lock in today", systemImage: "lock.fill") }.buttonStyle(PrimaryButtonStyle())
        }
    }

    private func conflictBanner(_ current: JournalEntity, _ other: JournalEntity) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Two versions of this entry").font(BFont.display(16)).foregroundStyle(Theme.ink)
            Text("It was changed on another device too. Keep the version below, or the one you're editing.").font(BFont.body(13)).foregroundStyle(Theme.ink2)
            Text(other.body.isEmpty ? "—" : other.body).font(BFont.body(14)).foregroundStyle(Theme.ink).lineLimit(6).padding(12).frame(maxWidth: .infinity, alignment: .leading).background(Theme.s1, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            HStack(spacing: 8) {
                Button("Keep mine") { store.saveJournal(goalId: current.goalId, date: current.localDate, mood: mood, body: text, resolve: true) }.buttonStyle(SecondaryButtonStyle())
                Button("Keep this one") { store.saveJournal(goalId: current.goalId, date: current.localDate, mood: other.mood, body: other.body, resolve: true); load(current.goalId) }.buttonStyle(SecondaryButtonStyle())
            }
        }
        .padding(14).background(Theme.warnTint, in: RoundedRectangle(cornerRadius: 16, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Theme.warn, lineWidth: 1))
    }

    private func dayIndex(_ e: JournalEntity) -> Int? {
        store.goals.first { $0.id == e.goalId }.map { Day.between($0.startDate, e.localDate) + 1 }
    }

    @ViewBuilder private var history: some View {
        let items = store.journalHistory().filter { (filter == nil || $0.goalId == filter) && (!$0.body.isEmpty || $0.mood != nil) }
        if items.isEmpty {
            VStack(spacing: 16) {
                Image(systemName: "point.topleft.down.to.point.bottomright.curvepath").font(.system(size: 36)).foregroundStyle(Theme.accLine).accessibilityHidden(true)
                Text("Your story starts today. Log your first reflection.").font(BFont.display(20)).multilineTextAlignment(.center).foregroundStyle(Theme.ink)
                Button("Write today's entry") { tab = 0 }.font(BFont.body(14, .semibold)).foregroundStyle(Theme.onAcc).padding(.horizontal, 22).frame(minHeight: 48).background(Theme.acc, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            .padding(.vertical, 60).padding(.horizontal, 24).frame(maxWidth: .infinity)
        } else {
            VStack(spacing: 10) {
                ForEach(items) { e in
                    Button { openEntry = e } label: {
                        VStack(alignment: .leading, spacing: 8) {
                            HStack(spacing: 10) {
                                Text(e.localDate == today ? String(localized: "Today") : Day.parse(e.localDate)!.formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day())).font(BFont.display(15)).foregroundStyle(Theme.ink)
                                if let d = dayIndex(e) { Text("DAY \(d)").bridgeLabel(10) }
                                Spacer()
                                if let m = e.mood { HStack(spacing: 6) { MoodRing(index: m, on: false, size: 14); Text(moodLabels[m]).font(BFont.body(12)).foregroundStyle(Theme.mut) } }
                            }
                            if !e.body.isEmpty { Text(e.body).font(BFont.body(14)).foregroundStyle(Theme.ink2).lineLimit(2).multilineTextAlignment(.leading) }
                        }
                        .padding(16).frame(maxWidth: .infinity, alignment: .leading)
                        .background(Theme.s1, in: RoundedRectangle(cornerRadius: 18, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(Theme.line, lineWidth: 1))
                    }
                }
            }
        }
    }
}

/// Read a past entry, or edit it.
struct EntrySheet: View {
    let entry: JournalEntity
    let day: Int?
    var onSave: (String) -> Void
    @State private var editing = false
    @State private var draft = ""
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 10) {
                    Text(Day.parse(entry.localDate)!.formatted(date: .abbreviated, time: .omitted)).font(BFont.display(20)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                    if let day { Text("DAY \(day)").bridgeLabel(10) }
                }
                Group { if let m = entry.mood { Text(moodLabels[m]) } else { Text("No mood logged") } }
                    .font(BFont.body(12, .medium)).foregroundStyle(Theme.ink2).padding(.horizontal, 10).padding(.vertical, 5).background(Theme.track, in: Capsule())
                if editing {
                    TextField("", text: $draft, axis: .vertical).font(BFont.body(16)).padding(14).frame(minHeight: 180, alignment: .topLeading)
                        .background(Theme.bg, in: RoundedRectangle(cornerRadius: 14, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Theme.acc, lineWidth: 1))
                    Button("Save entry") { onSave(String(draft.prefix(10_000))) }.buttonStyle(PrimaryButtonStyle())
                } else {
                    Text(entry.body).font(BFont.body(16)).lineSpacing(6).foregroundStyle(Theme.ink)
                    Button { draft = entry.body; editing = true } label: { Label("Edit entry", systemImage: "pencil") }.buttonStyle(SecondaryButtonStyle())
                }
            }
            .padding(.horizontal, 20).padding(.top, 28).padding(.bottom, 24)
        }
    }
}
