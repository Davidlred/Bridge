package app.thebridge.feature.paywall

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.thebridge.R
import app.thebridge.data.BillingRepository
import app.thebridge.data.Offer
import app.thebridge.data.Period
import app.thebridge.data.Product
import app.thebridge.data.PurchaseOutcome
import app.thebridge.ui.Toaster
import app.thebridge.ui.components.BridgeSheet
import app.thebridge.ui.components.PrimaryButton
import app.thebridge.ui.components.SecondaryButton
import app.thebridge.ui.components.ToastKind
import app.thebridge.ui.components.UiText
import app.thebridge.ui.theme.BType
import app.thebridge.ui.theme.LocalBridgeColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PaywallUi(val product: Product = Product.Plus, val available: Boolean = true, val loading: Boolean = true, val offers: List<Offer> = emptyList(), val selected: String? = null, val busy: Boolean = false)

@HiltViewModel
class PaywallViewModel @Inject constructor(private val billing: BillingRepository, private val toaster: Toaster, private val analytics: app.thebridge.data.Analytics) : ViewModel() {
    private val _state = MutableStateFlow(PaywallUi())
    val state = _state.asStateFlow()

    fun load(p: Product) {
        if (_state.value.product == p && !_state.value.loading) return
        _state.value = PaywallUi(product = p, available = billing.available)
        analytics.track(app.thebridge.data.Event.PaywallViewed, "product" to p.entitlement)
        viewModelScope.launch {
            val offers = billing.offers(p)
            // Annual first when there is one (the better deal), as most paywalls do.
            _state.update { it.copy(loading = false, offers = offers, selected = (offers.find { o -> o.period == Period.Year } ?: offers.firstOrNull())?.id) }
        }
    }
    fun select(id: String) = _state.update { it.copy(selected = id) }

    fun buy(activity: Activity, onDone: () -> Unit) {
        val id = _state.value.selected ?: return
        _state.update { it.copy(busy = true) }
        analytics.track(app.thebridge.data.Event.PurchaseStarted, "product" to _state.value.product.entitlement)
        viewModelScope.launch {
            val outcome = billing.purchase(activity, id)
            _state.update { it.copy(busy = false) }
            when (outcome) {
                PurchaseOutcome.Done -> { analytics.track(app.thebridge.data.Event.PurchaseCompleted, "product" to _state.value.product.entitlement); toaster.show(UiText.Res(R.string.toast_pw_done), ToastKind.Ok); onDone() }
                PurchaseOutcome.Pending -> { toaster.show(UiText.Res(R.string.toast_pw_pending)); onDone() }
                PurchaseOutcome.Failed -> toaster.show(UiText.Res(R.string.toast_pw_failed), ToastKind.Warn)
                PurchaseOutcome.Cancelled -> Unit
            }
        }
    }

    fun restore() = viewModelScope.launch {
        val ok = billing.restore()
        val has = billing.active.value.contains(_state.value.product.entitlement)
        toaster.show(UiText.Res(if (ok && has) R.string.toast_pw_restored else R.string.toast_pw_nothing), if (ok && has) ToastKind.Ok else ToastKind.Info)
    }
}

/** Plus (more than three goals) or Alliance+ (alliances past 15). */
@Composable
fun PaywallSheet(product: Product, onDismiss: () -> Unit, vm: PaywallViewModel = hiltViewModel(key = product.name)) {
    LaunchedEffect(product) { vm.load(product) }
    val ui by vm.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity
    BridgeSheet(onDismiss) {
        PaywallContent(ui, select = vm::select, buy = { activity?.let { vm.buy(it, onDismiss) } }, restore = { vm.restore() }, dismiss = onDismiss)
    }
}

@Composable
fun PaywallContent(ui: PaywallUi, select: (String) -> Unit, buy: () -> Unit, restore: () -> Unit, dismiss: () -> Unit) {
    val c = LocalBridgeColors.current
    val plus = ui.product == Product.Plus
    val features = if (plus) listOf(R.string.pw_plus_f1, R.string.pw_plus_f4, R.string.pw_plus_f2, R.string.pw_plus_f3) else listOf(R.string.pw_alliance_f1, R.string.pw_alliance_f2, R.string.pw_alliance_f3)
    val icons = if (plus) listOf(R.drawable.ic_flag, R.drawable.ic_groups, R.drawable.ic_check_circle) else listOf(R.drawable.ic_groups, R.drawable.ic_shield, R.drawable.ic_fire)
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(if (plus) R.string.pw_plus_kicker else R.string.pw_alliance_kicker), style = BType.label(11f, 0.16f), color = c.accInk)
            Text(stringResource(if (plus) R.string.pw_plus_title else R.string.pw_alliance_title), style = BType.display(26, 1.15f), color = c.ink, modifier = Modifier.semantics { heading() })
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            features.zip(icons).forEach { (f, ic) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(painterResource(ic), null, tint = c.acc, modifier = Modifier.size(20.dp))
                    Text(stringResource(f), style = BType.body(15), color = c.ink)
                }
            }
        }
        when {
            !ui.available -> Text(stringResource(R.string.pw_unavailable), style = BType.body(14, lineHeight = 1.45f), color = c.mut,
                modifier = Modifier.fillMaxWidth().background(c.s1, RoundedCornerShape(16.dp)).border(1.dp, c.line, RoundedCornerShape(16.dp)).padding(16.dp))
            ui.loading -> Text(stringResource(R.string.pw_loading), style = BType.body(14), color = c.mut)
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ui.offers.forEach { o ->
                    val on = o.id == ui.selected
                    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(16.dp)).background(if (on) c.accTint else c.s1)
                        .border(if (on) 1.5.dp else 1.dp, if (on) c.acc else c.line, RoundedCornerShape(16.dp)).clickable(role = Role.RadioButton) { select(o.id) }
                        .semantics(mergeDescendants = true) { selected = on }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(when (o.period) { Period.Year -> stringResource(R.string.pw_per_year, o.price); Period.Month -> stringResource(R.string.pw_per_month, o.price); Period.Other -> o.price },
                                style = BType.display(18), color = c.ink)
                            if (!plus) Text(stringResource(R.string.pw_alliance_who), style = BType.body(12), color = c.mut)
                        }
                        Icon(painterResource(if (on) R.drawable.ic_radio_on else R.drawable.ic_radio_off), null, tint = if (on) c.acc else c.mut, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
        val trial = ui.offers.find { it.id == ui.selected }?.trialDays
        PrimaryButton(if (trial != null) stringResource(R.string.pw_trial, trial) else stringResource(R.string.pw_buy), buy, enabled = ui.available && ui.selected != null, loading = ui.busy)
        SecondaryButton(stringResource(if (plus) R.string.pw_plus_stay else R.string.pw_alliance_stay), dismiss)
        Text(stringResource(R.string.pw_restore), style = BType.body(13, FontWeight.SemiBold), color = c.ink2, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(enabled = ui.available, role = Role.Button, onClick = restore).padding(vertical = 12.dp))
        Text(stringResource(R.string.pw_terms), style = BType.body(11, lineHeight = 1.4f), color = c.mut2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}
