package app.thebridge.feature.auth

import android.graphics.Paint
import android.graphics.Path
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import app.thebridge.R
import app.thebridge.ui.components.BackButton
import app.thebridge.ui.components.BridgeField
import app.thebridge.ui.components.PrimaryButton
import app.thebridge.ui.components.UiText
import app.thebridge.ui.theme.BType
import app.thebridge.ui.theme.Geist
import app.thebridge.ui.theme.LocalBridgeColors
import app.thebridge.ui.theme.LocalReduceMotion
import app.thebridge.ui.theme.Radius

/** Brand mark: an arch over a deck. */
@Composable
fun BridgeMark(color: Color, modifier: Modifier = Modifier, width: Int = 92) {
    Canvas(modifier.size(width = width.dp, height = (width * 50 / 92).dp)) {
        val u = size.width / 92f
        val ring = 12f * u
        drawArc(color, 180f, 180f, false, topLeft = androidx.compose.ui.geometry.Offset(10f * u + ring / 2, ring / 2), size = androidx.compose.ui.geometry.Size(72f * u - ring, 72f * u - ring), style = Stroke(ring))
        drawLine(color, androidx.compose.ui.geometry.Offset(3.5f * u, 46.5f * u), androidx.compose.ui.geometry.Offset(88.5f * u, 46.5f * u), strokeWidth = 7f * u, cap = StrokeCap.Round)
    }
}

/** First frame: mark on black in both themes. */
@Composable
fun SplashScreen() {
    val label = stringResource(R.string.splash_logo)
    Box(Modifier.fillMaxSize().background(Color.Black).semantics { contentDescription = label }, contentAlignment = Alignment.Center) { BridgeMark(Color.White) }
}

@Composable
fun WelcomeScreen(onGetStarted: () -> Unit, onLogIn: () -> Unit) {
    val c = LocalBridgeColors.current
    Column(Modifier.fillMaxSize().background(c.bg).systemBarsPadding().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(Radius.hero)).background(c.s1).border(1.5.dp, c.line, RoundedCornerShape(Radius.hero)), contentAlignment = Alignment.Center) {
                // The artwork is dark line art on white. In dark mode the lines turn white and the white background becomes
                // transparent (alpha = 1 - red), which is what invert + screen blending does in the prototype.
                val invert = remember { ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(0f, 0f, 0f, 0f, 255f, 0f, 0f, 0f, 0f, 255f, 0f, 0f, 0f, 0f, 255f, -1f, 0f, 0f, 0f, 255f))) }
                Image(painterResource(R.drawable.welcome_mark), contentDescription = null, contentScale = ContentScale.Fit, colorFilter = if (c.isDark) invert else null,
                    modifier = Modifier.fillMaxWidth(.62f).widthIn(max = 240.dp))
            }
            Stamp(Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 30.dp))
        }
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.welcome_title), style = BType.display(36, 1.02f).copy(letterSpacing = (-0.025).em), color = c.ink, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.welcome_body), style = BType.body(15, lineHeight = 1.5f), color = c.mut)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Surface(onClick = onGetStarted, shape = RoundedCornerShape(28.dp), color = c.acc, contentColor = c.onAcc, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) { Text(stringResource(R.string.welcome_get_started), style = BType.body(16, FontWeight.SemiBold)) }
            }
            LinkRow(stringResource(R.string.welcome_have_account), stringResource(R.string.log_in), onLogIn, Modifier.heightIn(min = 48.dp))
        }
    }
}

/** Rotating circular label with the mark in the middle (22s per turn; still when motion is reduced). */
@Composable
private fun Stamp(modifier: Modifier = Modifier) {
    val c = LocalBridgeColors.current
    val text = stringResource(R.string.welcome_stamp)
    val typeface = LocalContext.current.let { ctx -> remember { runCatching { ResourcesCompat.getFont(ctx, R.font.geist_mono) }.getOrNull() } }
    val angle = if (LocalReduceMotion.current) 0f else {
        val spin by rememberInfiniteTransition(label = "stamp").animateFloat(0f, 360f, infiniteRepeatable(tween(22_000, easing = LinearEasing), RepeatMode.Restart), label = "angle")
        spin
    }
    Box(modifier.size(96.dp).background(c.bg, CircleShape).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().rotate(angle)) {
            val r = size.minDimension / 2 - 9.dp.toPx()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c.ink.toArgb(); textSize = 8.5.dp.toPx(); this.typeface = typeface; letterSpacing = 0.18f }
            val path = Path().apply { addCircle(center.x, center.y, r, Path.Direction.CW) }
            // Repeat the phrase to fill the circumference evenly.
            val repeats = maxOf(1, ((2 * Math.PI * r) / paint.measureText(text)).toInt())
            paint.letterSpacing += ((2 * Math.PI * r).toFloat() / repeats - paint.measureText(text)) / (text.length * paint.textSize)
            drawIntoCanvas { it.nativeCanvas.drawTextOnPath(text.repeat(repeats), path, 0f, 0f, paint) }
        }
        Box(Modifier.size(46.dp).border(1.5.dp, c.ink, CircleShape), contentAlignment = Alignment.Center) { BridgeMark(c.ink, width = 26) }
    }
}

@Composable
private fun LinkRow(lead: String, link: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalBridgeColors.current
    Box(modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(buildAnnotatedString { append(lead); append(" "); withStyle(SpanStyle(color = c.ink, fontWeight = FontWeight.SemiBold)) { append(link) } },
            style = BType.body(14), color = c.mut, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 10.dp))
    }
}

@Composable
private fun AuthHeader(title: String, subtitle: String) {
    val c = LocalBridgeColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = BType.display(32).copy(letterSpacing = (-0.01).em), color = c.ink, modifier = Modifier.semantics { heading() })
        Text(subtitle, style = BType.body(15), color = c.mut)
    }
}

@Composable
private fun SocialButton(text: String, filled: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = LocalBridgeColors.current
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(Radius.input), color = if (filled) c.ink else Color.Transparent, contentColor = if (filled) c.bg else c.ink,
        border = if (filled) null else androidx.compose.foundation.BorderStroke(1.dp, c.line), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) { Text(text, style = BType.body(15, FontWeight.SemiBold), textAlign = TextAlign.Center) }
    }
}

@Composable
private fun FormError(error: UiText?) {
    if (error == null) return
    val c = LocalBridgeColors.current
    Text(error.resolve(), style = BType.body(13), color = c.bad,
        modifier = Modifier.fillMaxWidth().background(c.badTint, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp).semantics { liveRegion = LiveRegionMode.Assertive })
}

@Composable
fun SignUpScreen(
    state: AuthUiState, onName: (String) -> Unit, onEmail: (String) -> Unit, onPassword: (String) -> Unit,
    onSubmit: () -> Unit, onApple: () -> Unit, onGoogle: () -> Unit, onBack: () -> Unit, onLogIn: () -> Unit,
) {
    val c = LocalBridgeColors.current
    val focus = LocalFocusManager.current
    Column(
        Modifier.fillMaxSize().background(c.bg).systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        BackButton(onBack, Modifier.offset(x = (-10).dp))
        AuthHeader(stringResource(R.string.signup_title), stringResource(R.string.signup_subtitle))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SocialButton(stringResource(R.string.continue_apple), filled = true, enabled = !state.busy, onClick = onApple)
            SocialButton(stringResource(R.string.continue_google), filled = false, enabled = !state.busy, onClick = onGoogle)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f).height(1.dp).background(c.track))
            Text(stringResource(R.string.or_with_email), style = BType.label(11f, 0.14f), color = c.mut)
            Box(Modifier.weight(1f).height(1.dp).background(c.track))
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            BridgeField(stringResource(R.string.field_first_name), state.name, onName, stringResource(R.string.field_first_name_hint), error = state.nameError?.resolve(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next))
            BridgeField(stringResource(R.string.field_email), state.email, onEmail, stringResource(R.string.field_email_hint), error = state.emailError?.resolve(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false))
            BridgeField(stringResource(R.string.field_password), state.password, onPassword, stringResource(R.string.field_password_hint_new), error = state.passwordError?.resolve(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); onSubmit() }),
                visualTransformation = PasswordVisualTransformation())
        }
        FormError(state.formError)
        PrimaryButton(stringResource(R.string.create_account), onSubmit, loading = state.busy)
        LinkRow(stringResource(R.string.welcome_have_account), stringResource(R.string.log_in), onLogIn)
    }
}

@Composable
fun LogInScreen(
    state: AuthUiState, onEmail: (String) -> Unit, onPassword: (String) -> Unit,
    onSubmit: () -> Unit, onForgot: () -> Unit, onBack: (() -> Unit)?, onSignUp: (() -> Unit)?, modifier: Modifier = Modifier,
) {
    val c = LocalBridgeColors.current
    val focus = LocalFocusManager.current
    // Scrolls on small screens and with the keyboard up, so the primary action stays reachable.
    Column(modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        if (onBack != null) BackButton(onBack, Modifier.offset(x = (-10).dp))
        AuthHeader(stringResource(R.string.login_title), stringResource(R.string.login_subtitle))
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            BridgeField(stringResource(R.string.field_email), state.email, onEmail, stringResource(R.string.field_email_hint), error = state.emailError?.resolve(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false))
            BridgeField(stringResource(R.string.field_password), state.password, onPassword, stringResource(R.string.field_password_hint), error = state.passwordError?.resolve(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); onSubmit() }),
                visualTransformation = PasswordVisualTransformation())
            Text(stringResource(R.string.forgot_password), style = BType.body(14, FontWeight.Medium), color = c.ink,
                modifier = Modifier.align(Alignment.End).heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onForgot).padding(vertical = 12.dp))
        }
        FormError(state.formError)
        Spacer(Modifier.height(8.dp))
        PrimaryButton(stringResource(R.string.log_in), onSubmit, loading = state.busy)
        if (onSignUp != null) LinkRow(stringResource(R.string.new_here), stringResource(R.string.create_an_account), onSignUp)
    }
}

/** Full-screen wrapper for [LogInScreen] in the signed-out flow. */
@Composable
fun LogInPage(state: AuthUiState, onEmail: (String) -> Unit, onPassword: (String) -> Unit, onSubmit: () -> Unit, onForgot: () -> Unit, onBack: () -> Unit, onSignUp: () -> Unit) {
    Box(Modifier.fillMaxSize().background(LocalBridgeColors.current.bg).systemBarsPadding().imePadding()) {
        LogInScreen(state, onEmail, onPassword, onSubmit, onForgot, onBack, onSignUp, Modifier.fillMaxSize())
    }
}
