package app.twodo

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.twodo.model.Invite
import app.twodo.model.ShareLink
import app.twodo.sync.Notifications
import app.twodo.sync.SyncService
import app.twodo.ui.QrImage
import app.twodo.ui.TwoDoRoot
import kotlinx.coroutines.launch
import app.twodo.ui.TwoDoTheme

class MainActivity : ComponentActivity() {
    private val app get() = application as TwoDoApp
    private val pendingInvite = mutableStateOf<Invite?>(null)
    private val pendingOpen = mutableStateOf<String?>(null)

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
                TwoDoRoot(
                    app,
                    pendingInvite.value,
                    onInviteHandled = { pendingInvite.value = null },
                    openRequest = pendingOpen.value,
                    onOpenHandled = { pendingOpen.value = null },
                )
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
        intent?.getStringExtra(Notifications.EXTRA_LIST_ID)?.let { pendingOpen.value = it }
        intent?.dataString?.let(ShareLink::parse)?.let { pendingInvite.value = it }
        // A screenshot of someone's QR code shared to TwoDo from the gallery.
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return
            lifecycleScope.launch {
                QrImage.decode(this@MainActivity, uri)?.let(ShareLink::parse)?.let { pendingInvite.value = it }
                    ?: Toast.makeText(this@MainActivity, "Couldn't find a TwoDo QR code in that image.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
