import PhotosUI
import SwiftUI

/// Steps 1–4 and the plan review. `adding` = a second or third goal; `onExit` leaves without creating anything.
struct OnboardingFlow: View {
    let adding: Bool
    var onExit: (() -> Void)?
    var onFinished: () -> Void
    @Environment(GoalStore.self) private var store
    @Environment(SessionStore.self) private var session
    @Environment(Toaster.self) private var toaster
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var model: OnboardingModel?

    var body: some View {
        Group {
            if let model {
                Group {
                    switch model.draft.step {
                    case .goal: GoalStepView(model: model, onBack: onExit.map { exit in { model.discard(); exit() } })
                    case .routine: RoutineStepView(model: model)
                    case .photo: PhotoStepView(model: model)
                    case .generating: GeneratingStepView(model: model)
                    case .review: ReviewStepView(model: model) { if model.start() { onFinished() } }
                    }
                }
                .id(model.draft.step)
                .transition(reduceMotion ? .opacity : .asymmetric(insertion: .opacity.combined(with: .offset(x: 28)), removal: .opacity))
                .animation(.timingCurve(0.2, 0.8, 0.2, 1, duration: 0.38), value: model.draft.step)
            }
        }
        .background(Theme.bg)
        .toolbar(.hidden, for: .navigationBar)
        .task { if model == nil { model = OnboardingModel(adding: adding, store: store, session: session, toaster: toaster) } }
    }
}

// MARK: Shared pieces

struct StepHeader: View {
    let step: Int
    var onBack: (() -> Void)?
    var body: some View {
        HStack(spacing: 14) {
            if let onBack {
                Button(action: onBack) { Image(systemName: "arrow.left").font(.system(size: 22, weight: .regular)).foregroundStyle(Theme.ink).frame(width: 44, height: 44) }
                    .padding(.leading, -10).accessibilityLabel("Back")
            } else { Color.clear.frame(width: 34, height: 44) }
            HStack(spacing: 6) { ForEach(1...4, id: \.self) { i in Capsule().fill(i <= step ? Theme.acc : Theme.track).frame(height: 3) } }
                .padding(.trailing, 10)
                .accessibilityElement().accessibilityLabel(Text("Step \(step) of 4"))
        }
    }
}

struct StepTitle: View {
    let title: LocalizedStringKey
    var body: some View {
        Text(title).font(BFont.display(30)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct BigTextArea: View {
    @Binding var text: String
    let placeholder: LocalizedStringKey
    var large = false
    var minHeight: CGFloat = 118
    var limit = 1000
    @FocusState private var focused: Bool
    var body: some View {
        TextField(placeholder, text: Binding(get: { text }, set: { text = String($0.prefix(limit)) }), axis: .vertical)
            .font(large ? BFont.body(20, .medium) : BFont.body(16)).foregroundStyle(Theme.ink).lineSpacing(large ? 4 : 5)
            .focused($focused)
            .padding(18).frame(maxWidth: .infinity, minHeight: minHeight, alignment: .topLeading)
            .background(Theme.s1, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(focused ? Theme.mut : Theme.line, lineWidth: focused ? 1.5 : 1))
            .onTapGesture { focused = true }
    }
}

struct WarnNote: View {
    let text: LocalizedStringKey, icon: String
    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: icon).font(.system(size: 15)).accessibilityHidden(true)
            Text(text).font(BFont.body(13)).lineSpacing(2)
            Spacer(minLength: 0)
        }
        .foregroundStyle(Theme.warn).padding(.horizontal, 14).padding(.vertical, 12)
        .background(Theme.warnTint, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Theme.warn, lineWidth: 1))
    }
}

/// Date picker limited to [today + minDays, today + 5 years].
struct DateFieldView: View {
    let label: LocalizedStringKey?
    @Binding var value: String?
    let minDays: Int
    var compact = false
    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            if let label { Text(label).bridgeLabel(compact ? 10 : 11) }
            let today = LocalDay.today()
            let lower = Day.parse(Day.add(minDays, to: today))!, upper = Day.parse(Day.add(Deadlines.maxDays, to: today))!
            HStack {
                DatePicker("", selection: Binding(get: { value.flatMap(Day.parse) ?? lower }, set: { value = Day.string($0) }), in: lower...upper, displayedComponents: .date)
                    .labelsHidden().datePickerStyle(.compact).tint(Theme.acc)
                if value == nil { Text("Pick a date").font(BFont.body(14)).foregroundStyle(Theme.mut2) }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, compact ? 8 : 12).frame(minHeight: compact ? 48 : 52)
            .background(Theme.s1, in: RoundedRectangle(cornerRadius: compact ? 12 : Theme.Radius.input, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: compact ? 12 : Theme.Radius.input, style: .continuous).stroke(Theme.line, lineWidth: 1))
        }
    }
}

struct DeadlineMessage: View {
    let deadline: String?
    let minDays: Int
    var body: some View {
        if let deadline {
            let today = LocalDay.today()
            if let p = Deadlines.check(deadline, today: today, minDays: minDays) {
                Text(Deadlines.message(p)).font(BFont.body(13)).foregroundStyle(Theme.bad)
            } else {
                let n = Day.between(today, deadline)
                Text("\(n) days from today").font(BFont.body(13)).foregroundStyle(Theme.mut)
            }
        }
    }
}

private struct StepPage<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) { content }
                .padding(.horizontal, 24).padding(.top, 8).padding(.bottom, 24)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(Theme.bg)
    }
}

private struct ExampleRow: View {
    let left: Int, max: Int
    var onExample: () -> Void
    var body: some View {
        HStack {
            Button("Use an example", action: onExample).font(BFont.body(13, .medium)).foregroundStyle(Theme.ink).frame(minHeight: 44)
            Spacer()
            if left <= max / 10 { Text("\(left)").font(BFont.label(12)).foregroundStyle(Theme.warn) }
        }
    }
}

// MARK: Step 1 · Goal

struct GoalStepView: View {
    @Bindable var model: OnboardingModel
    var onBack: (() -> Void)?
    var body: some View {
        StepPage {
            StepHeader(step: 1, onBack: onBack)
            StepTitle(title: "What is the one goal you are committed to achieving?")
            VStack(spacing: 10) {
                BigTextArea(text: $model.draft.goal, placeholder: "Type your goal", large: true, limit: GoalText.maxLength).accessibilityLabel("Your goal")
                ExampleRow(left: GoalText.maxLength - model.draft.goal.count, max: GoalText.maxLength) { model.draft.goal = String(localized: "Run a half marathon in under 2 hours.") }
            }
            Text("Make it specific and measurable.").font(BFont.body(14)).lineSpacing(3).foregroundStyle(Theme.mut)
            if GoalText.hasDate(model.draft.goal) { WarnNote(text: "Leave the timing out of your goal. We'll use the target date below.", icon: "calendar.badge.exclamationmark") }
            else if GoalText.needsNumber(model.draft.goal) { WarnNote(text: "Add a number (a distance, a time, an amount) so progress is measurable.", icon: "ruler") }
            VStack(alignment: .leading, spacing: 10) {
                DateFieldView(label: "TARGET DATE", value: $model.draft.deadline, minDays: Deadlines.minDaysNew)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach([(1, "1 month"), (3, "3 months"), (6, "6 months"), (12, "1 year")] as [(Int, LocalizedStringKey)], id: \.0) { months, label in
                            let value = Day.addMonths(months, to: model.today)
                            let on = model.draft.deadline == value
                            Button { model.draft.deadline = value } label: {
                                Text(label).font(BFont.body(12, .medium)).foregroundStyle(on ? Theme.ink : Theme.ink2).padding(.horizontal, 12).frame(minHeight: 36)
                                    .background(on ? Theme.accTint : .clear, in: Capsule()).overlay(Capsule().stroke(on ? Theme.accLine : Theme.line, lineWidth: 1))
                            }
                        }
                    }
                }
                DeadlineMessage(deadline: model.draft.deadline, minDays: Deadlines.minDaysNew)
            }
            Button("Continue", action: model.toRoutine).buttonStyle(PrimaryButtonStyle()).disabled(!model.goalReady).opacity(model.goalReady ? 1 : 0.35)
        }
    }
}

// MARK: Step 2 · Routine

struct RoutineStepView: View {
    @Bindable var model: OnboardingModel
    var body: some View {
        StepPage {
            StepHeader(step: 2, onBack: model.back)
            StepTitle(title: "Walk us through a typical day, from morning to night.")
            if model.draft.routineFromSetup {
                HStack(alignment: .top, spacing: 10) {
                    Image(systemName: "clock.arrow.circlepath").foregroundStyle(Theme.mut).accessibilityHidden(true)
                    Text("We kept your routine from setup. Update anything this goal touches.").font(BFont.body(13)).foregroundStyle(Theme.ink2)
                }
                .padding(.horizontal, 14).padding(.vertical, 12).frame(maxWidth: .infinity, alignment: .leading)
                .background(Theme.s1, in: RoundedRectangle(cornerRadius: 14, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Theme.line, lineWidth: 1))
            }
            VStack(spacing: 10) {
                BigTextArea(text: $model.draft.routine, placeholder: "I wake up at…", minHeight: 200).accessibilityLabel("Your current routine")
                ExampleRow(left: 1000 - model.draft.routine.count, max: 1000) {
                    model.draft.routine = String(localized: "Up at 7:30, phone in bed until 8. Coffee, no breakfast. Desk job 9 to 6, mostly sitting. I run maybe twice a week if I'm not tired. Dinner around 9, usually takeaway. Scroll until 12:30, asleep by 1. Weekends have no structure.")
                }
            }
            Text("Include when you sleep, work, exercise and eat, and any habits you want to change.").font(BFont.body(14)).lineSpacing(3).foregroundStyle(Theme.mut)
            Button("Continue", action: model.toPhoto).buttonStyle(PrimaryButtonStyle()).disabled(!model.routineReady).opacity(model.routineReady ? 1 : 0.35)
        }
    }
}

// MARK: Step 3 · Photo

struct PhotoStepView: View {
    @Bindable var model: OnboardingModel
    @State private var consentFor: Source?
    @State private var picker = false
    @State private var camera = false
    @State private var item: PhotosPickerItem?
    enum Source: Identifiable { case library, camera; var id: Self { self } }

    var body: some View {
        StepPage {
            StepHeader(step: 3, onBack: model.back)
            VStack(alignment: .leading, spacing: 10) {
                StepTitle(title: "Add a photo of yourself")
                Text("One clear photo, facing the camera. It's used to make your two images.").font(BFont.body(15)).foregroundStyle(Theme.mut)
            }
            frame.frame(maxWidth: .infinity).frame(height: 320)
                .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(Theme.line, style: StrokeStyle(lineWidth: 1.5, dash: [6, 5])))
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "lock").foregroundStyle(Theme.mut).accessibilityHidden(true)
                Text("Your photo is only used for your images and is never shared.").font(BFont.body(13)).foregroundStyle(Theme.mut)
            }
            VStack(spacing: 8) {
                Button("Make my plan", action: model.generate).buttonStyle(PrimaryButtonStyle()).disabled(model.draft.photo != .done).opacity(model.draft.photo == .done ? 1 : 0.35)
                Button(action: model.generate) {
                    VStack(spacing: 2) {
                        Text("Skip for now").font(BFont.body(14, .medium)).foregroundStyle(Theme.ink2)
                        Text("Your images won't show your face").font(BFont.body(12)).foregroundStyle(Theme.mut)
                    }
                    .frame(maxWidth: .infinity, minHeight: 44)
                }
            }
        }
        .sheet(item: $consentFor) { source in
            ConsentSheet { consentFor = nil; if source == .camera { camera = true } else { picker = true } } onDecline: { consentFor = nil }
                .presentationDetents([.medium, .large]).presentationBackground(Theme.bg)
        }
        .photosPicker(isPresented: $picker, selection: $item, matching: .images)
        .onChange(of: item) { _, new in
            guard let new else { return }
            Task { if let data = try? await new.loadTransferable(type: Data.self) { model.uploadPhoto(data) }; item = nil }
        }
        .fullScreenCover(isPresented: $camera) { CameraPicker { if let data = $0 { model.uploadPhoto(data) } }.ignoresSafeArea() }
    }

    @ViewBuilder private var frame: some View {
        switch model.draft.photo {
        case .none:
            VStack(spacing: 14) {
                Button { consentFor = .library } label: {
                    VStack(spacing: 14) {
                        Circle().fill(Theme.accTint).frame(width: 64, height: 64).overlay(Image(systemName: "camera").font(.system(size: 26)).foregroundStyle(Theme.ink2))
                        Text("Take or upload a photo").font(BFont.display(16)).foregroundStyle(Theme.ink)
                        Text("JPG or PNG, face clearly visible").font(BFont.body(13)).foregroundStyle(Theme.mut)
                    }
                }
                if UIImagePickerController.isSourceTypeAvailable(.camera) {
                    Button("Use the camera") { consentFor = .camera }.font(BFont.body(13, .semibold)).foregroundStyle(Theme.ink).frame(minHeight: 44)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity).background(Theme.bg)
        case .uploading:
            VStack(spacing: 16) { Text("UPLOADING").bridgeLabel(11); ProgressView().tint(Theme.acc) }.frame(maxWidth: .infinity, maxHeight: .infinity).background(Theme.s1)
        case .done:
            ZStack {
                RemoteImage(url: model.draft.photoUrl).accessibilityLabel("Your uploaded photo")
                VStack {
                    HStack(alignment: .top) {
                        Label("UPLOADED", systemImage: "checkmark").font(BFont.label(11)).foregroundStyle(Theme.ok).padding(.horizontal, 10).frame(height: 28).background(Theme.okTint, in: Capsule())
                        Spacer()
                        Button(action: model.removePhoto) { Image(systemName: "xmark").foregroundStyle(Theme.ink).frame(width: 44, height: 44).background(Theme.s1.opacity(0.8), in: Circle()) }
                            .accessibilityLabel("Remove photo")
                    }
                    Spacer()
                    Button("Replace photo") { consentFor = .library }.font(BFont.body(13, .medium)).foregroundStyle(Theme.ink).padding(.horizontal, 16).frame(minHeight: 40)
                        .background(Theme.s1, in: Capsule()).overlay(Capsule().stroke(Theme.line, lineWidth: 1))
                }
                .padding(16)
            }
        case .failed:
            VStack(spacing: 14) {
                Image(systemName: "exclamationmark.circle.fill").font(.system(size: 30)).foregroundStyle(Theme.bad)
                Text("Photo upload failed. Please try again.").font(BFont.display(17)).foregroundStyle(Theme.ink).multilineTextAlignment(.center)
                Text("Check your connection, or continue without a photo.").font(BFont.body(13)).foregroundStyle(Theme.mut).multilineTextAlignment(.center)
                Button("Retry upload") { consentFor = .library }.font(BFont.body(14, .semibold)).foregroundStyle(Theme.ink).padding(.horizontal, 22).frame(minHeight: 44).overlay(Capsule().stroke(Theme.line, lineWidth: 1))
            }
            .padding(28).frame(maxWidth: .infinity, maxHeight: .infinity).background(Theme.s1)
        }
    }
}

/// Shown before any photo leaves the device: what happens to it, and how to take it back.
struct ConsentSheet: View {
    var onAgree: () -> Void
    var onDecline: () -> Void
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("Use your photo for AI images?").font(BFont.display(20)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                Text("To picture your future, we send this photo to our AI image provider. It creates two images of you: one where you've reached your goal, and one where nothing changes.").font(BFont.body(15)).lineSpacing(3).foregroundStyle(Theme.ink2)
                ForEach(["The photo and the images are stored privately and only you can see them.", "They are never used to train AI models or shared with other users.", "Remove the photo any time in Profile. Every image made from it is deleted with it."], id: \.self) { point in
                    HStack(alignment: .top, spacing: 10) {
                        Image(systemName: "checkmark").foregroundStyle(Theme.ok).accessibilityHidden(true)
                        Text(LocalizedStringKey(point)).font(BFont.body(14)).foregroundStyle(Theme.ink)
                    }
                }
                Button("Agree and upload", action: onAgree).buttonStyle(PrimaryButtonStyle())
                Button("Not now", action: onDecline).buttonStyle(SecondaryButtonStyle())
            }
            .padding(.horizontal, 24).padding(.top, 28).padding(.bottom, 24)
        }
    }
}

struct CameraPicker: UIViewControllerRepresentable {
    var onDone: (Data?) -> Void
    func makeUIViewController(context: Context) -> UIImagePickerController {
        let c = UIImagePickerController(); c.sourceType = .camera; c.cameraDevice = .front; c.delegate = context.coordinator; return c
    }
    func updateUIViewController(_ vc: UIImagePickerController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(onDone) }
    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let onDone: (Data?) -> Void
        init(_ f: @escaping (Data?) -> Void) { onDone = f }
        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            onDone((info[.originalImage] as? UIImage)?.jpegData(compressionQuality: 0.9)); picker.dismiss(animated: true)
        }
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { onDone(nil); picker.dismiss(animated: true) }
    }
}

// MARK: Step 4 · Generating

struct GeneratingStepView: View {
    @Bindable var model: OnboardingModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var scan = false

    var body: some View {
        let d = model.draft
        let planFailed = d.plan == .failed || d.plan == .quota
        let failed = planFailed || model.imagesFailed
        let done = d.plan == .done && !model.imagesRunning && !model.imagesFailed
        if d.plan == .clarify {
            StepPage {
                StepHeader(step: 4)
                Text("One question first").bridgeLabel(11, color: Theme.mut).padding(.top, 24)
                Text(d.clarification ?? "").font(BFont.display(26)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                Button("Edit my goal", action: model.cancelGeneration).buttonStyle(PrimaryButtonStyle())
            }
        } else {
            TimelineView(.periodic(from: .now, by: 0.5)) { tl in
                let elapsed = tl.date.timeIntervalSince(d.genStartedAt ?? tl.date)
                let pct = done ? 1 : failed ? 0.66 : min(0.96, elapsed / 54)
                StepPage {
                    StepHeader(step: 4)
                    RoundedRectangle(cornerRadius: 24, style: .continuous).fill(Theme.s0).frame(width: 178, height: 216)
                        .overlay(alignment: .top) {
                            if !done && !failed {
                                Rectangle().fill(Theme.prog).frame(height: 2).offset(y: 10 + (scan ? 190 : 0))
                                    .animation(reduceMotion ? nil : .easeInOut(duration: 2.1).repeatForever(autoreverses: true), value: scan)
                            }
                        }
                        .overlay(alignment: .bottom) { Text(d.photo == .done ? "YOUR PHOTO" : "CONCEPTUAL · NO FACE").bridgeLabel(10).padding(.bottom, 14) }
                        .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).stroke(failed ? Theme.bad : done ? Theme.ok : Theme.ink, lineWidth: 1.5))
                        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                        .frame(maxWidth: .infinity).padding(.top, 8).accessibilityHidden(true)
                        .onAppear { scan = !reduceMotion }
                    VStack(spacing: 10) {
                        Text(failed ? "We couldn't generate your plan right now." : done ? "Your plan is ready." : "Making your plan").font(BFont.display(26)).multilineTextAlignment(.center).foregroundStyle(Theme.ink)
                        Group {
                            if d.plan == .quota { Text("You've reached today's limit for new plans. Try again tomorrow, or write your own tasks.") }
                            else if failed { Text("One part failed. The rest is saved, so you can retry just that part.") }
                            else if done { Text("Opening your plan…") }
                            else { Text("About \(max(1, Int((20 * (1 - pct)).rounded()))) seconds left") }
                        }
                        .font(BFont.body(14)).foregroundStyle(Theme.mut).multilineTextAlignment(.center).frame(maxWidth: 300)
                    }
                    .frame(maxWidth: .infinity)
                    GeometryReader { g in Capsule().fill(Theme.track).overlay(alignment: .leading) { Capsule().fill(Theme.acc).frame(width: g.size.width * pct) } }.frame(height: 3)
                    VStack(spacing: 0) {
                        GenRow(label: "Daily action plan", sub: "Built from your routine", status: { switch d.plan { case .done: "done"; case .running: "running"; case .pending: "pending"; default: "failed" } }())
                        Divider().overlay(Theme.line)
                        GenRow(label: "Future Self image", sub: d.photo == .done ? "Made from your photo" : "Without your face", status: model.imageStatus("future"))
                        Divider().overlay(Theme.line)
                        GenRow(label: "Reality Check image", sub: "Where your current routine leads", status: model.imageStatus("reality"))
                    }
                    .background(Theme.s1, in: RoundedRectangle(cornerRadius: 18, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(Theme.line, lineWidth: 1))
                    if failed {
                        Button { model.retry() } label: { Label("Retry", systemImage: "arrow.clockwise") }.buttonStyle(PrimaryButtonStyle())
                        if planFailed { Button("Write my own tasks", action: model.writeOwnTasks).buttonStyle(SecondaryButtonStyle()) }
                        else if d.plan == .done { Button("Continue without images", action: model.continueWithoutImages).buttonStyle(SecondaryButtonStyle()) }
                    }
                }
            }
        }
    }
}

private struct GenRow: View {
    let label: LocalizedStringKey, sub: LocalizedStringKey, status: String
    var body: some View {
        HStack(spacing: 14) {
            Group {
                switch status {
                case "running": ProgressView().tint(Theme.acc)
                case "done": Image(systemName: "checkmark.circle.fill").foregroundStyle(Theme.ok).font(.system(size: 22))
                case "failed": Image(systemName: "exclamationmark.circle.fill").foregroundStyle(Theme.bad).font(.system(size: 22))
                default: Circle().stroke(Theme.line, lineWidth: 2).frame(width: 18, height: 18)
                }
            }
            .frame(width: 28, height: 28)
            VStack(alignment: .leading, spacing: 3) {
                Text(label).font(BFont.body(15, .medium)).foregroundStyle(Theme.ink)
                Text(status == "failed" ? "Generation failed" : sub).font(BFont.body(12)).foregroundStyle(status == "failed" ? Theme.bad : Theme.mut)
            }
            Spacer()
        }
        .padding(.horizontal, 16).padding(.vertical, 14)
        .accessibilityElement(children: .combine)
    }
}

// MARK: Review

struct ReviewStepView: View {
    @Bindable var model: OnboardingModel
    var onStart: () -> Void
    var body: some View {
        let d = model.draft
        let total = model.otherTasks + d.tasks.count
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ZStack(alignment: .bottomLeading) {
                    RemoteImage(url: model.images["future"]?.url).frame(height: 340).clipped()
                    LinearGradient(stops: [.init(color: .clear, location: 0.45), .init(color: Theme.bg, location: 1)], startPoint: .top, endPoint: .bottom)
                    VStack(alignment: .leading, spacing: 8) {
                        Text(d.goal).font(BFont.display(24)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                    }
                    .padding(.horizontal, 24).padding(.bottom, 22)
                }
                .frame(height: 340)
                VStack(alignment: .leading, spacing: 14) {
                    HStack(alignment: .top, spacing: 10) {
                        VStack(alignment: .leading, spacing: 7) {
                            Text("GOAL NAME").bridgeLabel(10)
                            TextField("Short name", text: Binding(get: { model.draft.label }, set: { model.draft.label = String($0.prefix(24)); model.labelError = nil }))
                                .font(BFont.body(15, .medium)).padding(.horizontal, 12).frame(minHeight: 48)
                                .background(Theme.s1, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                                .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(model.labelError != nil ? Theme.bad : Theme.line, lineWidth: 1))
                        }
                        DateFieldView(label: "TARGET DATE", value: $model.draft.deadline, minDays: Deadlines.minDaysNew, compact: true)
                    }
                    if let e = model.labelError { Text(e).font(BFont.body(13)).foregroundStyle(Theme.bad) }
                    if model.deadlineProblem() != nil { DeadlineMessage(deadline: d.deadline, minDays: Deadlines.minDaysNew) }
                    if let f = d.feasibility { FeasibilityCard(f: f, deadline: d.deadline, compact: true) }
                    HStack(alignment: .lastTextBaseline) {
                        Text("Your daily plan").font(BFont.display(20)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                        Spacer()
                        Text("\(d.tasks.count) TASKS").bridgeLabel(11)
                    }
                    .padding(.top, 8)
                    Text("Edit anything now or later.").font(BFont.body(14)).foregroundStyle(Theme.mut)
                    VStack(spacing: 8) {
                        ForEach(Array($model.draft.tasks.enumerated()), id: \.element.id) { i, $task in
                            HStack(spacing: 6) {
                                Text(String(format: "%02d", i + 1)).font(BFont.label(11)).foregroundStyle(Theme.mut).frame(width: 20)
                                TextField("New task", text: Binding(get: { task.title }, set: { task.title = String($0.prefix(80)) })).font(BFont.body(15, .medium)).frame(minHeight: 48)
                                    .accessibilityLabel("Task title")
                                Button { model.deleteTask(task.id) } label: { Image(systemName: "trash").foregroundStyle(Theme.mut).frame(width: 44, height: 44) }.accessibilityLabel("Delete task")
                            }
                            .padding(.leading, 14).padding(.trailing, 4).padding(.vertical, 4)
                            .background(Theme.s1, in: RoundedRectangle(cornerRadius: 14, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Theme.line, lineWidth: 1))
                        }
                    }
                    if d.tasks.isEmpty {
                        Text("Add at least one task.").font(BFont.body(13)).foregroundStyle(Theme.warn).padding(14).frame(maxWidth: .infinity, alignment: .leading)
                            .background(Theme.warnTint, in: RoundedRectangle(cornerRadius: 14, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Theme.warn, lineWidth: 1))
                    }
                    if d.adding && total > 9 { WarnNote(text: "That's \(total) tasks a day across \(model.otherGoals + 1) goals. Consider trimming a few.", icon: "scalemass") }
                    Button(action: model.addTask) {
                        Label("Add a task", systemImage: "plus").font(BFont.body(14, .medium)).foregroundStyle(Theme.ink2).frame(maxWidth: .infinity, minHeight: 52)
                            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Theme.line, style: StrokeStyle(lineWidth: 1, dash: [5, 4])))
                    }
                }
                .padding(.horizontal, 20).padding(.top, 8).padding(.bottom, 110)
            }
        }
        .ignoresSafeArea(edges: .top)
        .background(Theme.bg)
        .safeAreaInset(edge: .bottom) {
            Button(d.adding ? "Add goal" : "Start", action: onStart).buttonStyle(PrimaryButtonStyle()).disabled(!model.canStart).opacity(model.canStart ? 1 : 0.35)
                .padding(.horizontal, 20).padding(.top, 20).padding(.bottom, 8).background(Theme.bg)
        }
    }
}

/// Reality Check verdict. A suggested date is only ever offered; `onUseDate` lets the user take it.
struct FeasibilityCard: View {
    let f: Feasibility
    let deadline: String?
    var compact = false
    var onUseDate: ((String) -> Void)?
    var note: String?
    var body: some View {
        // A plain card: only the verdict word carries its colour.
        let (fg, label): (Color, LocalizedStringKey) = switch f.verdict {
            case "realistic": (Theme.ok, "REALISTIC")
            case "unrealistic": (Theme.bad, "UNREALISTIC AS PLANNED")
            default: (Theme.warn, "A STRETCH")
        }
        VStack(alignment: .leading, spacing: 8) {
            if !compact, let deadline, let date = Day.parse(deadline) {
                Text("CAN YOU MAKE IT BY \(date.formatted(date: .abbreviated, time: .omitted).uppercased())?").bridgeLabel(10)
            }
            Text(label).bridgeLabel(11, color: fg)
            Text(f.reason).font(BFont.body(14)).lineSpacing(3).foregroundStyle(Theme.ink2)
            if let s = f.suggestedDeadline, s != deadline, let onUseDate, let date = Day.parse(s) {
                HStack(spacing: 10) {
                    Button("Use \(date.formatted(date: .abbreviated, time: .omitted))") { onUseDate(s) }.font(BFont.body(13, .semibold)).foregroundStyle(Theme.ink)
                        .padding(.horizontal, 14).frame(minHeight: 40).background(Theme.bg, in: Capsule()).overlay(Capsule().stroke(Theme.line2, lineWidth: 1))
                }
            }
            if let note { Text(note).font(BFont.body(12)).foregroundStyle(Theme.mut) }
        }
        .padding(compact ? 14 : 16).frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.s1, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Theme.line, lineWidth: 1))
    }
}

/// Loads a signed image link, caching the bytes on disk under a stable key (the link itself changes as it is re-signed).
struct RemoteImage: View {
    let url: String?
    @State private var image: UIImage?
    var body: some View {
        ZStack {
            Theme.s0
            if let image { Image(uiImage: image).resizable().scaledToFill().transition(.opacity) }
        }
        .task(id: url) { image = await ImageCache.load(url) }
    }
}

enum ImageCache {
    private static let dir: URL = {
        let d = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("goal-images", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }()
    static func load(_ url: String?) async -> UIImage? {
        guard let url, let u = URL(string: url) else { return nil }
        let version = URLComponents(string: url)?.queryItems?.first { $0.name == "v" }?.value ?? ""
        let key = (u.path + "#" + version).data(using: .utf8)!.base64EncodedString().replacingOccurrences(of: "/", with: "_")
        let file = dir.appendingPathComponent(key)
        if let data = try? Data(contentsOf: file), let img = UIImage(data: data) { return img }
        guard let (data, response) = try? await URLSession.shared.data(from: u), (response as? HTTPURLResponse)?.statusCode == 200, let img = UIImage(data: data) else { return nil }
        try? data.write(to: file, options: .completeFileProtection)
        return img
    }
}
