package app.thebridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.thebridge.feature.auth.AuthUiState
import app.thebridge.feature.auth.LogInPage
import app.thebridge.feature.auth.SignUpScreen
import app.thebridge.feature.auth.SplashScreen
import app.thebridge.feature.auth.WelcomeScreen
import app.thebridge.feature.shell.CoachScreen
import app.thebridge.feature.shell.JournalScreen
import app.thebridge.feature.shell.OfflineBanner
import app.thebridge.feature.shell.ProfileScreen
import app.thebridge.feature.shell.TodayScreen
import app.thebridge.feature.shell.UpgradeScreen
import app.thebridge.ui.components.UiText
import app.thebridge.ui.theme.BridgeTheme
import app.thebridge.ui.theme.LocalBridgeColors
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** Every milestone-1 screen in light, dark and font scale 2.0. Record: `./gradlew recordPaparazziDebug`; check: `verifyPaparazziDebug`. */
class ScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    private fun snap(name: String, content: @Composable () -> Unit) {
        for ((variant, theme, scale) in listOf(Triple("light", "light", 1f), Triple("dark", "dark", 1f), Triple("font200", "light", 2f))) {
            paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_5.copy(fontScale = scale))
            paparazzi.snapshot("$name-$variant") {
                BridgeTheme(theme) { Box(Modifier.fillMaxSize().background(LocalBridgeColors.current.bg)) { content() } }
            }
        }
    }

    private val filled = AuthUiState(name = "Sharon", email = "sharon@example.com", password = "correct horse")
    private val errors = AuthUiState(name = "S", email = "sharon@", nameError = UiText.Res(R.string.err_name), emailError = UiText.Res(R.string.err_email),
        passwordError = UiText.Res(R.string.err_password_short), formError = UiText.Res(R.string.err_signup_offline))

    @Test fun splash() = snap("splash") { SplashScreen() }
    @Test fun welcome() = snap("welcome") { WelcomeScreen({}, {}) }
    @Test fun signUp() = snap("signup") { SignUpScreen(filled, {}, {}, {}, {}, {}, {}, {}) }
    @Test fun signUpErrors() = snap("signup-errors") { SignUpScreen(errors, {}, {}, {}, {}, {}, {}, {}) }
    @Test fun logIn() = snap("login") { LogInPage(AuthUiState(email = "sharon@example.com"), {}, {}, {}, {}, {}, {}) }
    @Test fun logInWrongPassword() = snap("login-error") { LogInPage(filled.copy(passwordError = UiText.Res(R.string.err_credentials)), {}, {}, {}, {}, {}, {}) }
    @Test fun logInLocked() = snap("login-locked") { LogInPage(filled.copy(formError = UiText.Plural(R.plurals.err_too_many_attempts, 15)), {}, {}, {}, {}, {}, {}) }
    @Test fun today() = snap("today") { TodayScreen("Sharon", LocalTime.of(9, 41), LocalDate.of(2026, 10, 13)) }
    @Test fun coach() = snap("coach") { CoachScreen("Arc") }
    @Test fun journal() = snap("journal") { JournalScreen() }
    @Test fun profile() = snap("profile") { ProfileScreen("Sharon", "sharon@example.com", LocalDate.of(2026, 10, 1), {}, {}) }
    @Test fun offlineBanner() = snap("offline") { OfflineBanner(3) }
    @Test fun upgrade() = snap("upgrade") { UpgradeScreen {} }
}
