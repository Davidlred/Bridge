package app.thebridge

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.thebridge.feature.auth.AuthViewModel
import app.thebridge.nav.AppRoot
import app.thebridge.ui.theme.BridgeTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val auth: AuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleAuthReturn(intent)
        setContent { BridgeTheme { AppRoot(auth) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAuthReturn(intent)
    }

    /** thebridge://auth?code=… comes back from the Sign in with Apple browser tab. */
    private fun handleAuthReturn(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "thebridge" && uri.host == "auth") auth.appleCallback(uri)
    }
}
