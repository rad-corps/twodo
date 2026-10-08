@file:OptIn(ExperimentalMaterial3Api::class)

package app.twodo.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.twodo.TwoDoApp
import app.twodo.model.Invite
import app.twodo.model.ShareLink
import app.twodo.model.TodoList
import app.twodo.sync.SyncStatus
import app.twodo.sync.describe
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

@Composable
fun TwoDoRoot(app: TwoDoApp, pendingInvite: Invite?, onInviteHandled: () -> Unit) {
    val lists by app.repo.lists.collectAsStateWithLifecycle()
    val status by app.sync.status.collectAsStateWithLifecycle()
    var openListId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { app.repo.conflicts.collect { snackbar.showSnackbar(it.describe()) } }
    LaunchedEffect(pendingInvite) {
        if (pendingInvite != null) {
            openListId = app.repo.joinList(pendingInvite).id
            onInviteHandled()
        }
    }

    val open = openListId?.let { lists[it] }
    if (open == null) {
        ListsScreen(app, lists.values.sortedBy { it.name.lowercase() }, status, snackbar, onOpen = { openListId = it })
    } else {
        BackHandler { openListId = null }
        ListScreen(app, open, status[open.id] ?: SyncStatus(), snackbar, onBack = { openListId = null })
    }
}

@Composable
private fun ListsScreen(
    app: TwoDoApp,
    lists: List<TodoList>,
    status: Map<String, SyncStatus>,
    snackbar: SnackbarHostState,
    onOpen: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var joining by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TwoDo") },
                actions = {
                    TextButton(onClick = { joining = true }) { Text("Join list") }
                    IconButton(onClick = { settings = true }) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("New list") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (lists.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No lists yet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("Create a list, or join one by scanning the QR code on another phone.")
            }
        }
        LazyColumn(contentPadding = padding) {
            items(lists, key = { it.id }) { list ->
                val items = list.visibleItems
                ListItem(
                    headlineContent = { Text(list.name) },
                    supportingContent = {
                        Text("${items.count { !it.checked }} to do · ${status[list.id].summary()}")
                    },
                    modifier = Modifier.clickable { onOpen(list.id) },
                )
                HorizontalDivider()
            }
        }
    }

    if (creating) {
        TextPromptDialog("New list", "Name", confirm = "Create", onDismiss = { creating = false }) { name ->
            creating = false
            scope.launch { onOpen(app.repo.createList(name).id) }
        }
    }
    if (joining) {
        JoinDialog(onDismiss = { joining = false }) { invite ->
            joining = false
            scope.launch { onOpen(app.repo.joinList(invite).id) }
        }
    }
    if (settings) SettingsDialog(app, onDismiss = { settings = false })
}

@Composable
private fun ListScreen(app: TwoDoApp, list: TodoList, status: SyncStatus, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var newText by rememberSaveable(list.id) { mutableStateOf("") }
    var sharing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    fun add() {
        val text = newText
        newText = ""
        scope.launch { app.repo.addItem(list.id, text) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(list.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(status.summary(), style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { sharing = true }) { Icon(Icons.Default.Share, "Share") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Remove from this phone") },
                            onClick = { menu = false; confirmRemove = true },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newText,
                    onValueChange = { newText = it },
                    placeholder = { Text("Add an item") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = ::add, enabled = newText.isNotBlank()) { Icon(Icons.Default.Add, "Add") }
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(list.visibleItems, key = { it.id }) { item ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { scope.launch { app.repo.setChecked(list.id, item.id, !item.checked) } }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = item.checked,
                            onCheckedChange = { scope.launch { app.repo.setChecked(list.id, item.id, it) } },
                        )
                        Text(
                            item.text,
                            modifier = Modifier.weight(1f),
                            textDecoration = if (item.checked) TextDecoration.LineThrough else null,
                            color = if (item.checked) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                        )
                        IconButton(onClick = { scope.launch { app.repo.deleteItem(list.id, item.id) } }) {
                            Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }
    }

    if (sharing) ShareDialog(list, onDismiss = { sharing = false })
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove “${list.name}”?") },
            text = { Text("It's removed from this phone only. Other devices keep their copy, and you can rejoin with the QR code.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    onBack()
                    scope.launch { app.repo.removeList(list.id) }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ShareDialog(list: TodoList, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val link = remember(list.id) { ShareLink.build(list) }
    val qr = remember(link) { BarcodeEncoder().encodeBitmap(link, BarcodeFormat.QR_CODE, 720, 720).asImageBitmap() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share “${list.name}”") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(qr, "QR code for this list", Modifier.size(260.dp))
                Spacer(Modifier.height(12.dp))
                Text("Scan this with TwoDo on the other phone (Join list). Anyone with this code can see and edit the list.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
                context.startActivity(Intent.createChooser(send, "Send link"))
            }) { Text("Send link") }
        },
    )
}

@Composable
private fun JoinDialog(onDismiss: () -> Unit, onJoin: (Invite) -> Unit) {
    var link by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@rememberLauncherForActivityResult
        ShareLink.parse(contents)?.let(onJoin) ?: run { error = "That QR code isn't a TwoDo list." }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join a list") },
        text = {
            Column {
                OutlinedButton(
                    onClick = {
                        scanner.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt("Scan the list's QR code")
                                .setBeepEnabled(false)
                                .setOrientationLocked(false),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Scan QR code") }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it; error = null },
                    label = { Text("…or paste a link") },
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                ShareLink.parse(link)?.let(onJoin) ?: run { error = "That isn't a TwoDo link." }
            }, enabled = link.isNotBlank()) { Text("Join") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SettingsDialog(app: TwoDoApp, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(app.identity.deviceName) }
    var background by remember { mutableStateOf(app.identity.backgroundSync) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("This phone's name") },
                    supportingText = { Text("Shown to other devices, e.g. in conflict messages") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Sync in background")
                        Text(
                            "Stay reachable while the app is closed. Shows a permanent notification and uses some battery.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = background, onCheckedChange = { background = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                app.identity.deviceName = name
                if (background != app.identity.backgroundSync) app.setBackgroundSync(background)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TextPromptDialog(title: String, label: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun SyncStatus?.summary(): String = when {
    this == null || trackersOnline == 0 && peerNames.isEmpty() -> "Offline"
    peerNames.isEmpty() -> "Looking for other devices…"
    peerNames.size == 1 -> "Connected to ${peerNames.single()}"
    else -> "Connected to ${peerNames.size} devices"
}
