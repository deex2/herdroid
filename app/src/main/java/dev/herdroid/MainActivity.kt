package dev.herdroid

import android.Manifest
import android.os.Bundle
import android.content.Intent
import android.os.Build
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.view.WindowManager
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.herdroid.core.ui.HerdroidTheme
import dagger.hilt.android.AndroidEntryPoint
import dev.herdroid.navigation.HerdroidNavHost
import dev.herdroid.navigation.NotificationOpenPayload
import dev.herdroid.navigation.consumeNotificationOpenIntent
import dev.herdroid.navigation.restoreNotificationOpenPayload
import dev.herdroid.navigation.saveNotificationOpenPayload
import dev.herdroid.session.api.ConnectionSession
import dev.herdroid.session.impl.ServiceBindingViewModel
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject internal lateinit var connectionSession: ConnectionSession
    private val serviceBinding by viewModels<ServiceBindingViewModel>()
    private var openTarget by mutableStateOf<NotificationOpenPayload?>(null)
    private val pendingConnection = PendingConnection()
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingConnection.complete(granted)?.let(connectionSession::connect)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState?.containsKey(PENDING_ROUTE_KEY) == true) {
            pendingConnection.restore(savedInstanceState.getLong(PENDING_ROUTE_KEY))
        }
        openTarget = consumeNotificationOpenIntent(intent) ?: restoreNotificationOpenPayload(savedInstanceState)
        setContent {
            HerdroidTheme {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    HerdroidNavHost(
                        connectionSession,
                        ::setSecureScreen,
                        openTarget,
                        { openTarget = null },
                        onConnect = ::connect,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeNotificationOpenIntent(intent)?.let { openTarget = it }
    }

    override fun onStart() {
        super.onStart()
        serviceBinding.onActivityStart()
    }

    override fun onStop() {
        serviceBinding.onActivityStop(isChangingConfigurations)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingConnection.snapshot()?.let { outState.putLong(PENDING_ROUTE_KEY, it) }
        saveNotificationOpenPayload(outState, openTarget)
        super.onSaveInstanceState(outState)
    }

    private fun connect(routeId: Long) {
        pendingConnection.begin(routeId)
        if (notificationsAllowed()) {
            pendingConnection.complete(true)?.let(connectionSession::connect)
        } else {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun notificationsAllowed() = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun setSecureScreen(secure: Boolean) {
        window.setSecureScreen(secure && (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0)
    }
}

internal class PendingConnection {
    private var routeId: Long? = null

    fun begin(routeId: Long) {
        this.routeId = routeId
    }

    fun snapshot(): Long? = routeId

    fun restore(routeId: Long?) {
        this.routeId = routeId
    }

    @Suppress("UNUSED_PARAMETER")
    fun complete(granted: Boolean): Long? = routeId.also { routeId = null }
}

private const val PENDING_ROUTE_KEY = "pending_connection_route"

internal fun Window.setSecureScreen(secure: Boolean) {
    if (secure) addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    else clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
}
