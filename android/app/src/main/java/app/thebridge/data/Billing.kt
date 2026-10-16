package app.thebridge.data

import android.app.Activity
import android.app.Application
import app.thebridge.BuildConfig
import app.thebridge.data.remote.ApiClient
import com.revenuecat.purchases.LogLevel
import com.revenuecat.purchases.Package
import com.revenuecat.purchases.PackageType
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import com.revenuecat.purchases.PurchasesException
import com.revenuecat.purchases.PurchasesErrorCode
import com.revenuecat.purchases.PurchasesTransactionException
import com.revenuecat.purchases.awaitCustomerInfo
import com.revenuecat.purchases.awaitLogIn
import com.revenuecat.purchases.awaitOfferings
import com.revenuecat.purchases.awaitPurchase
import com.revenuecat.purchases.awaitRestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** What a paywall sells: Plus (more than 3 goals) or Alliance+ (alliances past 15, paid by the founder). */
enum class Product(val entitlement: String, val offering: String) { Plus("plus", "plus"), AlliancePlus("alliance_plus", "alliance_plus") }

/** One price on the paywall, straight from the store (never hard-coded). */
data class Offer(val id: String, val price: String, val period: Period, val trialDays: Int?)
enum class Period { Month, Year, Other }

sealed interface PurchaseOutcome {
    data object Done : PurchaseOutcome
    data object Cancelled : PurchaseOutcome
    /** Ask to Buy / slow card: no entitlement until the store confirms (the server hears it from RevenueCat). */
    data object Pending : PurchaseOutcome
    data object Failed : PurchaseOutcome
}

/**
 * Purchases through RevenueCat. Entitlements the server honours come from RevenueCat webhooks; the SDK's
 * customer info is only for an instant UI. Without a RevenueCat key (-Pbridge.revenuecatKey) purchases are off.
 */
@Singleton
class BillingRepository @Inject constructor(private val api: ApiClient) {
    private var configured = false
    private var packages: Map<String, Package> = emptyMap()
    private val _active = MutableStateFlow<Set<String>>(emptySet())
    /** Active entitlement ids (`plus`, `alliance_plus`). */
    val active = _active.asStateFlow()

    val available: Boolean get() = configured

    fun configure(app: Application) {
        if (BuildConfig.REVENUECAT_KEY.isBlank() || configured) return
        Purchases.logLevel = if (BuildConfig.DEBUG) LogLevel.INFO else LogLevel.WARN
        Purchases.configure(PurchasesConfiguration.Builder(app, BuildConfig.REVENUECAT_KEY).build())
        configured = true
    }

    /** After sign-in: purchases belong to this account (RevenueCat's app user id is our user id). */
    suspend fun identify(userId: String) {
        if (configured) runCatching { Purchases.sharedInstance.awaitLogIn(userId) }
        refresh()
    }
    fun signedOut() { if (configured) Purchases.sharedInstance.logOut() ; _active.value = emptySet() }

    /** Server first (authoritative); the SDK cache fills in right after a purchase, before the webhook lands. */
    suspend fun refresh() {
        val server = runCatching { api.entitlements() }.getOrNull()
        val local = if (configured) runCatching { Purchases.sharedInstance.awaitCustomerInfo().entitlements.active.keys }.getOrNull() else null
        _active.value = (server.orEmpty() + local.orEmpty()).toSet()
    }

    suspend fun offers(p: Product): List<Offer> {
        if (!configured) return emptyList()
        val offering = runCatching { Purchases.sharedInstance.awaitOfferings()[p.offering] }.getOrNull() ?: return emptyList()
        packages = packages + offering.availablePackages.associateBy { it.identifier }
        return offering.availablePackages.map { pkg ->
            val sp = pkg.product
            Offer(pkg.identifier, sp.price.formatted,
                when (pkg.packageType) { PackageType.MONTHLY -> Period.Month; PackageType.ANNUAL -> Period.Year; else -> Period.Other },
                sp.defaultOption?.freePhase?.billingPeriod?.let { it.value * when (it.unit.name) { "WEEK" -> 7; "MONTH" -> 30; "YEAR" -> 365; else -> 1 } })
        }
    }

    suspend fun purchase(activity: Activity, offerId: String): PurchaseOutcome {
        val pkg = packages[offerId] ?: return PurchaseOutcome.Failed
        return try {
            Purchases.sharedInstance.awaitPurchase(PurchaseParams.Builder(activity, pkg).build())
            refresh(); PurchaseOutcome.Done
        } catch (e: PurchasesTransactionException) {
            when {
                e.userCancelled -> PurchaseOutcome.Cancelled
                e.code == PurchasesErrorCode.PaymentPendingError -> PurchaseOutcome.Pending
                else -> PurchaseOutcome.Failed
            }
        }
    }

    /** Required by both stores: brings back purchases made with this store account. */
    suspend fun restore(): Boolean = try {
        if (configured) Purchases.sharedInstance.awaitRestore(); refresh(); true
    } catch (e: PurchasesException) { false }
}
