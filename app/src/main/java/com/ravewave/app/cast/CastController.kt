package com.ravewave.app.cast

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.mediarouter.app.MediaRouteChooserDialogFragment
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CastController(context: Context) {
    private val appContext = context.applicationContext
    private val castContext: CastContext? by lazy {
        runCatching { CastContext.getSharedInstance(appContext) }.getOrNull()
    }

    private val listener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = updateState("Cast connected")
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = updateState("Cast resumed")
        override fun onSessionEnded(session: CastSession, error: Int) = updateState("Cast ended")
        override fun onSessionStarting(session: CastSession) = updateState("Connecting to cast")
        override fun onSessionStartFailed(session: CastSession, error: Int) = updateState("Cast failed")
        override fun onSessionEnding(session: CastSession) = updateState("Disconnecting cast")
        override fun onSessionResuming(session: CastSession, sessionId: String) = updateState("Resuming cast")
        override fun onSessionResumeFailed(session: CastSession, error: Int) = updateState("Cast resume failed")
        override fun onSessionSuspended(session: CastSession, reason: Int) = updateState("Cast suspended")
    }

    private val _uiState = MutableStateFlow(CastUiState())
    val uiState: StateFlow<CastUiState> = _uiState.asStateFlow()

    fun onStart() {
        val manager = castContext?.sessionManager ?: return
        manager.addSessionManagerListener(listener, CastSession::class.java)
        updateState("Cast ready")
    }

    fun onStop() {
        castContext?.sessionManager?.removeSessionManagerListener(listener, CastSession::class.java)
    }

    fun showRouteChooser(activity: FragmentActivity) {
        val context = castContext ?: run {
            _uiState.value = _uiState.value.copy(message = "Cast framework unavailable")
            return
        }
        val selector = context.mergedSelector ?: run {
            _uiState.value = _uiState.value.copy(message = "Cast route selector unavailable")
            return
        }

        val fragment = MediaRouteChooserDialogFragment()
        fragment.routeSelector = selector
        fragment.show(activity.supportFragmentManager, "cast_route_chooser")
    }

    fun currentSession(): CastSession? = castContext?.sessionManager?.currentCastSession

    private fun updateState(message: String) {
        val session = castContext?.sessionManager?.currentCastSession
        _uiState.value = CastUiState(
            isAvailable = castContext != null,
            isConnected = session?.isConnected == true,
            routeName = session?.castDevice?.friendlyName,
            message = message
        )
    }
}
