package app.thebridge.ui.components

import androidx.annotation.DrawableRes
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.thebridge.R
import app.thebridge.ui.theme.BType
import app.thebridge.ui.theme.LocalBridgeColors
import app.thebridge.ui.theme.LocalReduceMotion
import app.thebridge.ui.theme.Motion
import app.thebridge.ui.theme.Radius

/** Text that a ViewModel can hold without a Context. */
@Immutable
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Plural(@PluralsRes val id: Int, val count: Int) : UiText
    data class ResArg(@StringRes val id: Int, @StringRes val arg: Int) : UiText

    @Composable
    fun resolve(): String = when (this) {
        is Res -> stringResource(id, *args.toTypedArray())
        is Plural -> pluralStringResource(id, count, count)
        is ResArg -> stringResource(id, stringResource(arg))
    }
}

/** 54dp, radius 16, Bricolage 600 16 on the accent. Grows with font scale instead of clipping. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false, container: Color = LocalBridgeColors.current.acc, content: Color = LocalBridgeColors.current.onAcc) {
    Surface(
        onClick = onClick, enabled = enabled && !loading, modifier = modifier.fillMaxWidth().heightIn(min = 54.dp),
        shape = RoundedCornerShape(Radius.button), color = container.copy(alpha = if (enabled) 1f else .5f), contentColor = content,
    ) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            if (loading) CircularProgressIndicator(Modifier.size(22.dp), color = content, strokeWidth = 2.dp)
            else Text(text, style = BType.display(16), textAlign = TextAlign.Center)
        }
    }
}

/** 48dp, radius 14, 1dp line. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = LocalBridgeColors.current
    Surface(
        onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(Radius.input), color = Color.Transparent, contentColor = c.ink, border = BorderStroke(1.dp, c.line),
    ) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(text, style = BType.body(15, FontWeight.Medium), textAlign = TextAlign.Center)
        }
    }
}

/** Mono label above a 52dp field; error text in `bad` underneath. */
@Composable
fun BridgeField(
    label: String, value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
    error: String? = null, keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val c = LocalBridgeColors.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(label, style = BType.label(11f, 0.12f), color = c.mut)
        BasicTextField(
            value = value, onValueChange = onValueChange, singleLine = true, textStyle = BType.body(16).copy(color = c.ink),
            cursorBrush = SolidColor(c.acc), interactionSource = interaction, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label; if (error != null) error(error) },
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).background(c.s1, RoundedCornerShape(Radius.input))
                        .border(if (focused) 1.5.dp else 1.dp, if (error != null) c.bad else if (focused) c.acc else c.line, RoundedCornerShape(Radius.input))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) Text(placeholder, style = BType.body(16), color = c.mut2)
                    inner()
                }
            },
        )
        if (error != null) Text(error, style = BType.body(13), color = c.bad, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

/** 44dp back arrow used at the top of pushed screens. */
@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.size(44.dp).clickable(onClickLabel = stringResource(R.string.back), role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back), tint = LocalBridgeColors.current.ink, modifier = Modifier.size(26.dp))
    }
}

enum class ToastKind { Ok, Warn, Info }
@Immutable data class ToastData(val text: UiText, val kind: ToastKind = ToastKind.Info, val id: Long = System.nanoTime())

/** Confirmation toast: s2 surface, 1dp line, radius 16. The host positions it above the tab bar. */
@Composable
fun BridgeToast(toast: ToastData?, modifier: Modifier = Modifier) {
    val c = LocalBridgeColors.current
    val reduce = LocalReduceMotion.current
    AnimatedVisibility(
        visible = toast != null, modifier = modifier,
        enter = fadeIn(tween(Motion.toast)) + if (reduce) fadeIn(tween(Motion.toast)) else slideInVertically(tween(Motion.toast)) { it / 4 },
        exit = fadeOut(tween(Motion.toast)),
    ) {
        // Keep the last toast while it fades out.
        val last = remember { mutableStateOf(toast) }
        if (toast != null) last.value = toast
        val shown = last.value ?: return@AnimatedVisibility
        @DrawableRes val icon = if (shown.kind == ToastKind.Warn) R.drawable.ic_warning else R.drawable.ic_check_circle
        Row(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().background(c.s2, RoundedCornerShape(16.dp)).border(1.dp, c.line, RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = when (shown.kind) { ToastKind.Ok -> c.ok; ToastKind.Warn -> c.warn; ToastKind.Info -> c.mut }, modifier = Modifier.size(20.dp))
            Text(shown.text.resolve(), style = BType.body(14, FontWeight.Medium, 1.35f), color = c.ink)
        }
    }
}

/** Bottom sheet with a grabber on the app background. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BridgeSheet(onDismiss: () -> Unit, dismissible: Boolean = true, content: @Composable () -> Unit) {
    val c = LocalBridgeColors.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { dismissible })
    ModalBottomSheet(
        onDismissRequest = { if (dismissible) onDismiss() }, sheetState = state, containerColor = c.bg, contentColor = c.ink,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 40.dp, height = 4.dp).background(c.line2, RoundedCornerShape(2.dp))) },
    ) {
        Box(Modifier.padding(PaddingValues(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 28.dp))) { content() }
    }
}

@Composable
fun SheetTitle(text: String) = Text(text, style = BType.display(20), color = LocalBridgeColors.current.ink, modifier = Modifier.semantics { heading() })
