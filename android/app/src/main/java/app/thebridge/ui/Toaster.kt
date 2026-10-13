package app.thebridge.ui

import app.thebridge.ui.components.ToastData
import app.thebridge.ui.components.ToastKind
import app.thebridge.ui.components.UiText
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** App-wide confirmations. ViewModels post; the root composable shows one at a time. */
@Singleton
class Toaster @Inject constructor() {
    private val _toasts = MutableSharedFlow<ToastData>(extraBufferCapacity = 4)
    val toasts = _toasts.asSharedFlow()
    fun show(text: UiText, kind: ToastKind = ToastKind.Info) { _toasts.tryEmit(ToastData(text, kind)) }
}
