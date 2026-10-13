package app.thebridge.feature.auth

import android.content.Context
import android.net.Uri
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.thebridge.BuildConfig
import app.thebridge.R
import app.thebridge.data.remote.ApiClient
import app.thebridge.data.remote.ApiException
import app.thebridge.data.remote.AuthResponse
import app.thebridge.data.remote.OfflineException
import app.thebridge.data.session.SessionManager
import app.thebridge.ui.Toaster
import app.thebridge.ui.components.ToastKind
import app.thebridge.ui.components.UiText
import app.thebridge.util.UuidV7
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUiState(
    val name: String = "", val email: String = "", val password: String = "",
    val nameError: UiText? = null, val emailError: UiText? = null, val passwordError: UiText? = null,
    /** Errors that aren't about one field (offline, provider unavailable, account linked elsewhere). */
    val formError: UiText? = null,
    val busy: Boolean = false,
)

/** Pure input rules, shared by sign up, log in and the tests. Copy matches the prototype. */
object AuthValidation {
    private val email = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$")
    fun name(v: String): Int? = if (v.trim().length < 2) R.string.err_name else null
    fun email(v: String): Int? = if (!email.matches(v.trim())) R.string.err_email else null
    fun newPassword(v: String): Int? = if (v.length < 8) R.string.err_password_short else null
    /** A password shorter than 8 can't be right, so don't spend a request (or a lockout attempt) on it. */
    fun existingPassword(v: String): Int? = if (v.length < 8) R.string.err_credentials else null
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val saved: SavedStateHandle,
    private val api: ApiClient,
    private val session: SessionManager,
    private val toaster: Toaster,
) : ViewModel() {
    // Name and email survive process death; the password deliberately does not.
    private val _state = MutableStateFlow(AuthUiState(name = saved["name"] ?: "", email = saved["email"] ?: session.user.value?.email.orEmpty()))
    val state = _state.asStateFlow()

    fun onName(v: String) { saved["name"] = v; _state.update { it.copy(name = v.take(40), nameError = null, formError = null) } }
    fun onEmail(v: String) { saved["email"] = v; _state.update { it.copy(email = v.take(254), emailError = null, formError = null) } }
    fun onPassword(v: String) = _state.update { it.copy(password = v.take(128), passwordError = null, formError = null) }
    fun clearErrors() = _state.update { it.copy(nameError = null, emailError = null, passwordError = null, formError = null, password = "") }

    fun signUp() {
        val s = _state.value
        val errors = Triple(AuthValidation.name(s.name), AuthValidation.email(s.email), AuthValidation.newPassword(s.password))
        if (errors.toList().any { it != null }) {
            _state.update { it.copy(nameError = errors.first?.let(UiText::Res), emailError = errors.second?.let(UiText::Res), passwordError = errors.third?.let(UiText::Res)) }
            return
        }
        submit(R.string.err_signup_offline) { api.signUp(s.name.trim().substringBefore(' '), s.email.trim(), s.password, session.deviceId()) }
    }

    fun logIn() {
        val s = _state.value
        val emailErr = AuthValidation.email(s.email); val pwErr = AuthValidation.existingPassword(s.password)
        if (emailErr != null || pwErr != null) {
            _state.update { it.copy(emailError = emailErr?.let(UiText::Res), passwordError = pwErr?.let(UiText::Res)) }
            return
        }
        submit(R.string.err_login_offline, welcomeBack = true) { api.logIn(s.email.trim(), s.password, session.deviceId()) }
    }

    fun forgot() {
        val email = _state.value.email.trim()
        if (AuthValidation.email(email) != null) { _state.update { it.copy(emailError = UiText.Res(R.string.err_forgot_needs_email)) }; return }
        viewModelScope.launch {
            try {
                api.forgotPassword(email)
                toaster.show(UiText.Res(R.string.toast_reset_sent, listOf(email)), ToastKind.Ok)
            } catch (e: OfflineException) {
                _state.update { it.copy(formError = UiText.Res(R.string.error_offline)) }
            } catch (e: Exception) {
                _state.update { it.copy(formError = UiText.Res(R.string.error_generic)) }
            }
        }
    }

    /** Google One Tap / account picker through Credential Manager. [activity] must be an Activity context. */
    fun google(activity: Context) {
        if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isEmpty()) { _state.update { it.copy(formError = UiText.ResArg(R.string.err_provider_unavailable, R.string.provider_google)) }; return }
        submit(R.string.error_offline, provider = R.string.provider_google) {
            val nonce = UuidV7.next()
            val option = GetGoogleIdOption.Builder().setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID).setFilterByAuthorizedAccounts(false).setNonce(nonce).build()
            val result = try {
                CredentialManager.create(activity).getCredential(activity, GetCredentialRequest.Builder().addCredentialOption(option).build())
            } catch (e: GetCredentialCancellationException) {
                return@submit null                                        // user backed out: stay on the screen, no error
            } catch (e: GetCredentialException) {
                throw ApiException(503, "provider_not_configured", kotlinx.serialization.json.JsonObject(emptyMap()))
            }
            api.google(GoogleIdTokenCredential.createFrom(result.credential.data).idToken, nonce, session.deviceId())
        }
    }

    /** Where to send the browser tab for Sign in with Apple. The result comes back through [appleCallback]. */
    suspend fun appleStartUri(): Uri = session.appleStartUri()

    /** Handles thebridge://auth?code=… (or ?error=…) after the Apple browser flow. */
    fun appleCallback(uri: Uri) {
        val code = uri.getQueryParameter("code")
        val error = uri.getQueryParameter("error")
        if (code == null) {
            if (error != null && error != "cancelled") _state.update { it.copy(formError = message(ApiException(400, error, providerBody(uri.getQueryParameter("provider"))), R.string.provider_apple)) }
            return
        }
        submit(R.string.error_offline, provider = R.string.provider_apple) {
            val verifier = session.appleVerifier() ?: throw ApiException(401, "invalid_code", kotlinx.serialization.json.JsonObject(emptyMap()))
            api.exchange(code, verifier, session.deviceId())
        }
    }

    private fun providerBody(provider: String?) = kotlinx.serialization.json.buildJsonObject { if (provider != null) put("provider", kotlinx.serialization.json.JsonPrimitive(provider)) }

    private fun submit(offlineMessage: Int, welcomeBack: Boolean = false, provider: Int? = null, request: suspend () -> AuthResponse?) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, formError = null) }
        viewModelScope.launch {
            try {
                val r = request() ?: return@launch
                session.signedIn(r)
                when {
                    provider != null -> toaster.show(UiText.ResArg(R.string.toast_signed_in_with, provider), ToastKind.Ok)
                    welcomeBack -> toaster.show(UiText.Res(R.string.toast_welcome_back, listOf(r.user.displayName)), ToastKind.Ok)
                }
                _state.update { it.copy(password = "") }
            } catch (e: OfflineException) {
                _state.update { it.copy(formError = UiText.Res(offlineMessage)) }
            } catch (e: ApiException) {
                when (e.code) {
                    "invalid_credentials" -> _state.update { it.copy(passwordError = UiText.Res(R.string.err_credentials)) }
                    "email_taken" -> _state.update { it.copy(emailError = UiText.Res(R.string.err_email_taken)) }
                    else -> _state.update { it.copy(formError = message(e, provider)) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(formError = UiText.Res(R.string.error_generic)) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun message(e: ApiException, provider: Int?): UiText = when (e.code) {
        "email_linked" -> when (e.provider) {
            "google" -> UiText.ResArg(R.string.err_email_linked, R.string.provider_google)
            "apple" -> UiText.ResArg(R.string.err_email_linked, R.string.provider_apple)
            else -> UiText.Res(R.string.err_email_linked_password)
        }
        "too_many_attempts" -> UiText.Plural(R.plurals.err_too_many_attempts, ((e.retryAfterSec ?: 900) + 59) / 60)
        "provider_not_configured" -> UiText.ResArg(R.string.err_provider_unavailable, provider ?: R.string.provider_google)
        else -> UiText.Res(R.string.error_generic)
    }
}
