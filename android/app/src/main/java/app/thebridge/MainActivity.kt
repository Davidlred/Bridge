package app.thebridge

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import app.thebridge.data.Appearance
import app.thebridge.feature.auth.AuthViewModel
import app.thebridge.nav.AppRoot
import app.thebridge.ui.theme.BridgeTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val auth: AuthViewModel by viewModels()
    @Inject lateinit var appearance: Appearance
    @Inject lateinit var rhythm: app.thebridge.data.RhythmStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Until the setting is read the splash shows, which is black in both themes.
            val mode by appearance.mode.collectAsStateWithLifecycle(initialValue = "system")
            val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
            // Status and navigation bar icons follow the app's theme, not the system's.
            LaunchedEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            BridgeTheme(mode) { AppRoot(auth) }
        }
    }

    /** Only recorded when the user turned on "Let Arc learn my routine". */
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { rhythm.recordOpen() }
    }
}
