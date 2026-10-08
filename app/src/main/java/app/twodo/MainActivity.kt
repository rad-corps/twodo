package app.twodo

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.twodo.model.Invite
import app.twodo.model.ShareLink
import app.twodo.sync.SyncService
import app.twodo.ui.TwoDoRoot
import app.twodo.ui.TwoDoTheme

class MainActivity : ComponentActivity() {
    private val app get() = application as TwoDoApp
    private val pendingInvite = mutableStateOf<Invite?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (app.identity.backgroundSync) SyncService.setEnabled(this, true)
        setContent {
            val dark by app.darkMode.collectAsStateWithLifecycle()
            LaunchedEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(bars, bars)
            }
            TwoDoTheme(dark) {
                TwoDoRoot(app, pendingInvite.value, onInviteHandled = { pendingInvite.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        app.visibleActivities++
        app.sync.acquire()
    }

    override fun onStop() {
        app.sync.release()
        app.visibleActivities--
        super.onStop()
    }

    private fun handleIntent(intent: Intent?) {
        intent?.dataString?.let(ShareLink::parse)?.let { pendingInvite.value = it }
    }
}
