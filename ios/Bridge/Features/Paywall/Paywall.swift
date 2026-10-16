import RevenueCat
import SwiftUI

/// What a paywall sells: Plus (more than 3 goals) or Alliance+ (alliances past 15, paid by the founder).
enum PaidProduct: String { case plus, alliancePlus = "alliance_plus"
    var entitlement: String { rawValue }
    var offering: String { rawValue }
}

/// One price on the paywall, straight from the store (never hard-coded).
struct PriceOffer: Identifiable, Equatable {
    enum Period { case month, year, other }
    let id: String
    let price: String
    let period: Period
    let trialDays: Int?
}

enum PurchaseOutcome { case done, cancelled, pending, failed }

/**
 Purchases through RevenueCat. Entitlements the server honours come from RevenueCat webhooks; the SDK's customer info is
 only for an instant UI. Without a RevenueCat key (REVENUECAT_KEY build setting) the paywall shows, but buying is off.
 */
@MainActor @Observable
final class BillingStore {
    private(set) var active: Set<String> = []
    private(set) var available = false
    @ObservationIgnored private var packages: [String: Package] = [:]

    func configure() {
        let key = Bundle.main.object(forInfoDictionaryKey: "RevenueCatKey") as? String ?? ""
        guard !key.isEmpty, !available else { return }
        Purchases.configure(withAPIKey: key)
        available = true
    }

    /// After sign-in: purchases belong to this account (RevenueCat's app user id is our user id).
    func identify(_ userId: String) async {
        if available { _ = try? await Purchases.shared.logIn(userId) }
        await refresh()
    }
    func signedOut() async {
        if available { _ = try? await Purchases.shared.logOut() }
        active = []
    }

    /// Server first (authoritative); the SDK cache fills in right after a purchase, before the webhook lands.
    func refresh() async {
        var ids = Set((try? await APIClient.shared.entitlements()) ?? [])
        if available, let info = try? await Purchases.shared.customerInfo() { ids.formUnion(info.entitlements.active.keys) }
        active = ids
    }

    func offers(_ p: PaidProduct) async -> [PriceOffer] {
        guard available, let offering = try? await Purchases.shared.offerings()[p.offering] else { return [] }
        for pkg in offering.availablePackages { packages[pkg.identifier] = pkg }
        return offering.availablePackages.map { pkg in
            let trial: Int? = pkg.storeProduct.introductoryDiscount.flatMap { d in
                guard d.paymentMode == .freeTrial else { return nil }
                let p = d.subscriptionPeriod
                return p.value * (p.unit == .week ? 7 : p.unit == .month ? 30 : p.unit == .year ? 365 : 1)
            }
            return PriceOffer(id: pkg.identifier, price: pkg.storeProduct.localizedPriceString,
                         period: pkg.packageType == .monthly ? .month : pkg.packageType == .annual ? .year : .other, trialDays: trial)
        }
    }

    func purchase(_ id: String) async -> PurchaseOutcome {
        guard let pkg = packages[id] else { return .failed }
        do {
            let r = try await Purchases.shared.purchase(package: pkg)
            if r.userCancelled { return .cancelled }
            await refresh(); return .done
        } catch let e as ErrorCode where e == .paymentPendingError {
            return .pending                                    // Ask to Buy: unlocks when the store confirms
        } catch { return .failed }
    }

    /// Required by both stores: brings back purchases made with this store account.
    func restore() async -> Bool {
        guard available else { return false }
        do { _ = try await Purchases.shared.restorePurchases(); await refresh(); return true } catch { return false }
    }
}

/// Plus (more than three goals) or Alliance+ (alliances past 15).
struct PaywallSheet: View {
    let product: PaidProduct
    var onDone: () -> Void
    @Environment(BillingStore.self) private var billing
    @Environment(Toaster.self) private var toaster
    @State private var offers: [PriceOffer] = []
    @State private var loading = true
    @State private var selected: String?
    @State private var busy = false

    var body: some View {
        PaywallContent(product: product, available: billing.available, loading: loading, offers: offers, selected: $selected, busy: busy, buy: buy, restore: restore, dismiss: onDone)
            .task {
                offers = await billing.offers(product)
                selected = (offers.first { $0.period == .year } ?? offers.first)?.id          // annual first when there is one
                loading = false
            }
    }

    private func buy() {
        guard let id = selected else { return }
        busy = true
        Task {
            let outcome = await billing.purchase(id)
            busy = false
            switch outcome {
            case .done: toaster.show(String(localized: "You're all set. Thanks for supporting The Bridge."), .ok); onDone()
            case .pending: toaster.show(String(localized: "Waiting for approval. It unlocks as soon as the store confirms.")); onDone()
            case .failed: toaster.show(String(localized: "The purchase didn't go through. You haven't been charged."), .warn)
            case .cancelled: break
            }
        }
    }

    private func restore() {
        Task {
            let ok = await billing.restore(), has = billing.active.contains(product.entitlement)
            toaster.show(ok && has ? String(localized: "Purchases restored.") : String(localized: "No purchases to restore on this store account."), ok && has ? .ok : .info)
        }
    }
}

struct PaywallContent: View {
    let product: PaidProduct
    let available: Bool
    let loading: Bool
    let offers: [PriceOffer]
    @Binding var selected: String?
    var busy = false
    var buy: () -> Void = {}
    var restore: () -> Void = {}
    var dismiss: () -> Void = {}

    var body: some View {
        let plus = product == .plus
        let features: [(String, LocalizedStringKey)] = plus
            ? [("flag.fill", "Run more goals side by side"), ("person.3.fill", "Join more shared-plan alliances"), ("checkmark.circle.fill", "Everything you have now stays the same")]
            : [("person.3.fill", "Up to 50 members per alliance"), ("gamecontroller.fill", "Bigger game nights"), ("trophy.fill", "Seasonal leagues against other alliances")]
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(plus ? "THE BRIDGE PLUS" : "ALLIANCE+").bridgeLabel(11, color: Theme.accInk)
                    Text(plus ? "More than three goals" : "Grow past 15 members" as LocalizedStringKey).font(BFont.display(26)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                }
                VStack(alignment: .leading, spacing: 10) {
                    ForEach(features.indices, id: \.self) { i in
                        Label { Text(features[i].1).font(BFont.body(15)).foregroundStyle(Theme.ink) } icon: { Image(systemName: features[i].0).foregroundStyle(Theme.acc) }
                    }
                }
                if !available {
                    Text("Purchases aren't available in this build yet.").font(BFont.body(14)).foregroundStyle(Theme.mut).padding(16).frame(maxWidth: .infinity, alignment: .leading).card(16)
                } else if loading {
                    Text("Loading prices…").font(BFont.body(14)).foregroundStyle(Theme.mut)
                } else {
                    ForEach(offers) { o in
                        let on = o.id == selected
                        Button { selected = o.id } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Group {
                                        switch o.period {
                                        case .year: Text("\(o.price) / year")
                                        case .month: Text("\(o.price) / month")
                                        case .other: Text(o.price)
                                        }
                                    }
                                    .font(BFont.display(18)).foregroundStyle(Theme.ink)
                                    if !plus { Text("paid by the founder").font(BFont.body(12)).foregroundStyle(Theme.mut) }
                                }
                                Spacer()
                                Image(systemName: on ? "largecircle.fill.circle" : "circle").font(.system(size: 20)).foregroundStyle(on ? Theme.acc : Theme.mut)
                            }
                            .padding(.horizontal, 16).padding(.vertical, 12).frame(minHeight: 60)
                            .background(on ? Theme.accTint : Theme.s1, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(on ? Theme.acc : Theme.line, lineWidth: on ? 1.5 : 1))
                        }
                        .accessibilityAddTraits(on ? [.isSelected] : [])
                    }
                }
                Button(action: buy) {
                    if busy { ProgressView().tint(Theme.onAcc) }
                    else if let t = offers.first(where: { $0.id == selected })?.trialDays { Text("Start \(t)-day free trial") }
                    else { Text("Subscribe") }
                }
                .buttonStyle(PrimaryButtonStyle()).disabled(!available || selected == nil || busy).opacity(available && selected != nil ? 1 : 0.5)
                Button(plus ? "Not now" : "Stay at 15", action: dismiss).buttonStyle(SecondaryButtonStyle())
                Button("Restore purchases", action: restore).font(BFont.body(13, .semibold)).foregroundStyle(Theme.ink2).frame(maxWidth: .infinity, minHeight: 44).disabled(!available)
                Text("Renews automatically until you cancel in your store account. Cancel at least 24 hours before renewal.").font(BFont.body(11)).foregroundStyle(Theme.mut2).multilineTextAlignment(.center).frame(maxWidth: .infinity)
            }
            .padding(24)
        }
    }
}
