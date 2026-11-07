import AVFoundation
import Speech
import SwiftUI

/// Each step of "Arc, call Sharon". There is no countdown on iPhone: iOS asks "Call …?" itself before dialling.
enum CallStep: Equatable {
    /// First time: Arc explains, then asks for contacts. `denied`: only Settings can turn it on now.
    case needsContacts(String, denied: Bool)
    case searching(String)
    case notFound(String)
    case pick(String, [PhoneContact])
}

@MainActor @Observable final class CallFlow {
    var step: CallStep?

    func start(_ t: CallTarget) {
        switch t {
        case .number(let n): PhoneContacts.call(n); finish()
        case .name(let name): if PhoneContacts.allowed { search(name) } else { step = .needsContacts(name, denied: PhoneContacts.denied) }
        }
    }

    func allow() {
        guard case .needsContacts(let name, _) = step else { return }
        Task { if await PhoneContacts.requestAccess() { search(name) } else { step = .needsContacts(name, denied: true) } }
    }

    private func search(_ name: String) {
        step = .searching(name)
        Task {
            let found = await PhoneContacts.find(name)
            switch found.count {
            case 0: step = .notFound(name)
            case 1: PhoneContacts.call(found[0].number); finish()
            default: step = .pick(name, Array(found.prefix(6)))
            }
        }
    }

    func pick(_ c: PhoneContact) { PhoneContacts.call(c.number); finish() }
    func finish() { step = nil; ArcCalls.shared.request = nil }
}

/// Over the whole app, so a reminder's "Call" button works from anywhere.
struct CallSheetHost: ViewModifier {
    @State private var flow = CallFlow()
    func body(content: Content) -> some View {
        content
            .onChange(of: ArcCalls.shared.request) { _, t in if let t { flow.start(t) } }
            .onAppear { if let t = ArcCalls.shared.request { flow.start(t) } }
            .sheet(isPresented: Binding(get: { flow.step != nil }, set: { if !$0 { flow.finish() } })) {
                if let s = flow.step {
                    CallStepView(step: s, allow: flow.allow, pick: flow.pick, close: flow.finish)
                        .padding(24).presentationDetents([.medium]).presentationBackground(Theme.bg)
                }
            }
    }
}

extension View { func arcCallSheet() -> some View { modifier(CallSheetHost()) } }

struct CallStepView: View {
    let step: CallStep
    var allow: () -> Void = {}
    var pick: (PhoneContact) -> Void = { _ in }
    var close: () -> Void = {}

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            switch step {
            case .needsContacts(let name, let denied):
                Text("Call \(name)").font(BFont.display(20)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                Text("To call \(name), The Bridge looks them up in your contacts. Your contacts stay on this phone and are never uploaded.").font(BFont.body(15)).foregroundStyle(Theme.ink2)
                if denied {
                    Text("Contacts are turned off for The Bridge. You can turn them on in Settings.").font(BFont.body(13)).foregroundStyle(Theme.warn)
                    Button("Open Settings") { if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) } }.buttonStyle(PrimaryButtonStyle())
                } else {
                    Button("Allow contacts", action: allow).buttonStyle(PrimaryButtonStyle())
                }
                Button("Not now", action: close).buttonStyle(SecondaryButtonStyle())
            case .searching(let name):
                HStack(spacing: 12) { ProgressView().tint(Theme.acc); Text("Finding \(name)…").font(BFont.body(15)).foregroundStyle(Theme.ink) }
            case .notFound(let name):
                Text("No \(name) in your contacts").font(BFont.display(20)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                Text("Check how they're saved, or say their full name.").font(BFont.body(15)).foregroundStyle(Theme.ink2)
                Button("Close", action: close).buttonStyle(SecondaryButtonStyle())
            case .pick(let name, let options):
                Text("Which \(name)?").font(BFont.display(20)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                VStack(spacing: 0) {
                    ForEach(Array(options.enumerated()), id: \.element) { i, o in
                        if i > 0 { Divider().overlay(Theme.line).padding(.leading, 16) }
                        Button { pick(o) } label: {
                            HStack(spacing: 12) {
                                Initial(name: o.name, size: 36)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(o.name).font(BFont.body(15, .semibold)).foregroundStyle(Theme.ink)
                                    Text([o.label, o.number].compactMap { $0 }.joined(separator: " · ")).font(BFont.body(13)).foregroundStyle(Theme.mut)
                                }
                                Spacer()
                                Image(systemName: "phone.fill").foregroundStyle(Theme.ink2)
                            }
                            .padding(.horizontal, 16).padding(.vertical, 10).frame(minHeight: 60).contentShape(Rectangle())
                        }
                        .accessibilityLabel("Call \(o.name), \(o.label ?? "") \(o.number)")
                    }
                }
                .background(Theme.s1, in: RoundedRectangle(cornerRadius: 16, style: .continuous)).overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Theme.line, lineWidth: 1))
                Button("Cancel", action: close).buttonStyle(SecondaryButtonStyle())
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct Initial: View {
    let name: String; let size: CGFloat
    var body: some View {
        let ch = name.first(where: { $0.isLetter || $0.isNumber }).map { String($0).uppercased() } ?? "#"
        Text(ch).font(BFont.display(size > 48 ? 26 : 15)).foregroundStyle(Theme.accInk).frame(width: size, height: size)
            .background(Theme.accTint, in: Circle()).overlay(Circle().stroke(Theme.accLine, lineWidth: 1)).accessibilityHidden(true)
    }
}

/// Speaking to Arc: on-device when the phone supports it, and stops after a short pause. The words land in the
/// message box and are sent, so "Arc, call Sharon" works by voice too.
@MainActor @Observable final class SpeechListener {
    var listening = false
    var text = ""
    var problem: String?
    @ObservationIgnored private let engine = AVAudioEngine()
    @ObservationIgnored private var request: SFSpeechAudioBufferRecognitionRequest?
    @ObservationIgnored private var task: SFSpeechRecognitionTask?
    @ObservationIgnored private var silence: Task<Void, Never>?
    @ObservationIgnored private var onDone: ((String) -> Void)?

    static var available: Bool { SFSpeechRecognizer()?.isAvailable ?? false }

    func start(onDone: @escaping (String) -> Void) {
        guard !listening else { stop(); return }
        self.onDone = onDone; problem = nil; text = ""
        Task {
            let speech = await Self.speechPermission()
            let mic = await AVAudioApplication.requestRecordPermission()
            guard speech == .authorized, mic else { problem = String(localized: "Allow the microphone and speech recognition in Settings to talk to Arc."); return }
            begin()
        }
    }

    private func begin() {
        guard let recognizer = SFSpeechRecognizer(), recognizer.isAvailable else { return }
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.record, mode: .measurement, options: .duckOthers)
        try? session.setActive(true, options: .notifyOthersOnDeactivation)
        let req = SFSpeechAudioBufferRecognitionRequest()
        req.shouldReportPartialResults = true
        if recognizer.supportsOnDeviceRecognition { req.requiresOnDeviceRecognition = true }
        request = req
        let input = engine.inputNode
        input.removeTap(onBus: 0)
        input.installTap(onBus: 0, bufferSize: 1024, format: input.outputFormat(forBus: 0), block: Self.feed(req))
        engine.prepare()
        do { try engine.start() } catch { problem = String(localized: "The microphone isn't available right now."); return }
        listening = true
        task = recognizer.recognitionTask(with: req, resultHandler: Self.results(self))
    }

    // Audio and speech call back on their own threads, so these closures are made outside the main actor.
    nonisolated private static func speechPermission() async -> SFSpeechRecognizerAuthorizationStatus {
        await withCheckedContinuation { c in SFSpeechRecognizer.requestAuthorization { c.resume(returning: $0) } }
    }
    nonisolated private static func feed(_ req: SFSpeechAudioBufferRecognitionRequest) -> AVAudioNodeTapBlock { { buffer, _ in req.append(buffer) } }
    nonisolated private static func results(_ owner: SpeechListener) -> @Sendable (SFSpeechRecognitionResult?, Error?) -> Void {
        { [weak owner] result, error in
            let words = result?.bestTranscription.formattedString
            let final = (result?.isFinal ?? false) || error != nil
            Task { @MainActor in
                guard let owner else { return }
                if let words { owner.text = words; owner.armSilence() }
                if final { owner.stop() }
            }
        }
    }

    /// 1.5 s without new words ends it.
    private func armSilence() {
        silence?.cancel()
        silence = Task { try? await Task.sleep(for: .seconds(1.5)); if !Task.isCancelled { stop() } }
    }

    func stop() {
        guard listening else { return }
        listening = false
        silence?.cancel()
        engine.stop(); engine.inputNode.removeTap(onBus: 0)
        request?.endAudio(); task?.cancel(); request = nil; task = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        let said = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if !said.isEmpty { onDone?(said) }
        onDone = nil
    }
}
