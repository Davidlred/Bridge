import Foundation
import Network
import SwiftData

/// Drains the outbox and pulls server changes. Safe to call from anywhere; runs serially.
actor SyncEngine {
    static let shared = SyncEngine()
    enum Reason { case foreground, mutation, networkRegained, background }

    private let monitor = NWPathMonitor()
    private var isOnline = true
    private var running = false
    private var container: ModelContainer?
    private var api = APIClient.shared
    private var debounce: Task<Void, Never>?

    func configure(container: ModelContainer) {
        guard self.container == nil else { return }
        self.container = container
        monitor.pathUpdateHandler = { [weak self] path in
            Task { await self?.setOnline(path.status == .satisfied) }
        }
        monitor.start(queue: .global(qos: .utility))
    }

    private func setOnline(_ online: Bool) async {
        let regained = online && !isOnline
        isOnline = online
        if regained { await drain(reason: .networkRegained) }
    }

    /// Call after every local mutation; coalesces bursts into one sync 1.5s later.
    func mutated() {
        debounce?.cancel()
        debounce = Task { [weak self] in
            try? await Task.sleep(for: .seconds(1.5))
            if !Task.isCancelled { await self?.drain(reason: .mutation) }
        }
    }

    func drain(reason: Reason) async {
        guard isOnline, !running, let container, TokenStore.read() != nil else { return }
        running = true
        defer { running = false }
        let ctx = ModelContext(container)
        do {
            // Push in batches of 200, oldest first. Server is idempotent by outbox id.
            while true {
                var d = FetchDescriptor<OutboxEntity>(sortBy: [SortDescriptor(\.createdAt)])
                d.fetchLimit = 200
                let batch = try ctx.fetch(d)
                guard !batch.isEmpty else { break }
                let result = try await api.push(batch.map(Mutation.init))
                for m in batch where result.accepted.contains(m.id) || result.rejected.keys.contains(m.id) { ctx.delete(m) }
                // Rejected mutations (e.g. shared_task_readonly) are dropped and the row is re-pulled below.
                try ctx.save()
                if result.accepted.isEmpty && result.rejected.isEmpty { break }
            }
            // Pulling over unsent local edits would overwrite them, so only pull once the outbox is empty.
            guard try ctx.fetchCount(FetchDescriptor<OutboxEntity>()) == 0 else { return }
            var more = true
            while more {
                let changes = try await api.pull(since: UserDefaults.standard.integer(forKey: "syncCursor"))
                try ChangeApplier.apply(changes, in: ctx)
                try ctx.save()
                UserDefaults.standard.set(changes.cursor, forKey: "syncCursor")
                more = changes.hasMore
            }
            await NotificationScheduler.shared.reschedule()
        } catch APIError.unauthorized {
            await MainActor.run { NotificationCenter.default.post(name: .sessionExpired, object: nil) }
        } catch {
            // Leave the outbox intact; next trigger retries.
        }
    }
}

extension Notification.Name { static let sessionExpired = Notification.Name("sessionExpired") }
