@file:OptIn(ExperimentalMaterial3Api::class)

package app.twodo.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.widget.Toast
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clip
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.twodo.R
import app.twodo.BuildConfig
import app.twodo.TwoDoApp
import app.twodo.net.SyncLog
import app.twodo.model.Invite
import app.twodo.model.Item
import app.twodo.model.ShareLink
import app.twodo.model.SpaceKind
import app.twodo.model.TodoList
import app.twodo.model.isJoining
import app.twodo.sync.ListEvent
import app.twodo.sync.Notifications
import app.twodo.sync.SyncStatus
import app.twodo.model.describe
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun TwoDoRoot(
    app: TwoDoApp,
    pendingInvite: Invite?,
    onInviteHandled: () -> Unit,
    openRequest: String? = null,
    onOpenHandled: () -> Unit = {},
    onScreenDark: (Boolean) -> Unit = {},
) {
    val appThemeId by app.appTheme.collectAsStateWithLifecycle()
    val appTheme = themeById(appThemeId)
    val lists by app.repo.lists.collectAsStateWithLifecycle()
    val status by app.sync.status.collectAsStateWithLifecycle()
    var openListId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    var askName by remember { mutableStateOf(!app.identity.hasName) }

    LaunchedEffect(Unit) { app.repo.conflicts.collect { snackbar.showSnackbar(it.describe()) } }
    // Joins and leaves get a pull-down notification too; this is the in-app version.
    LaunchedEffect(Unit) {
        app.sync.events.collect { event ->
            when (event) {
                is ListEvent.Joined -> "${event.who} joined “${event.listName}”"
                is ListEvent.Left -> "${event.who} left “${event.listName}”"
                is ListEvent.JoinedList -> "Joined “${event.listName}” with ${event.who}"
                is ListEvent.Changed -> null
            }?.let { snackbar.showSnackbar(it) }
        }
    }
    LaunchedEffect(openRequest) {
        if (openRequest != null) {
            openListId = openRequest
            onOpenHandled()
        }
    }
    LaunchedEffect(pendingInvite) {
        if (pendingInvite != null) {
            openListId = app.repo.joinList(pendingInvite).id
            onInviteHandled()
        }
    }

    if (askName) {
        TextPromptDialog(
            title = "What's your name?",
            label = "Your name",
            confirm = "Save",
            supporting = "Shown to others next to items you tick. You can change it in Settings.",
            onDismiss = { askName = false },
        ) { name ->
            app.setName(name)
            askName = false
        }
    }

    val open = openListId?.let { lists[it] }
    val screenTheme = open?.theme(appTheme) ?: appTheme
    LaunchedEffect(screenTheme.dark) { onScreenDark(screenTheme.dark) }
    if (open == null) {
        ListsScreen(app, lists.values.sortedBy { it.name.lowercase() }, status, snackbar, appTheme, onOpen = { openListId = it })
    } else {
        BackHandler { openListId = null }
        val openStatus = status[open.id] ?: SyncStatus()
        TwoDoTheme(screenTheme) {
            if (open.kind == SpaceKind.DIARY) {
                DiaryScreen(app, open, openStatus, snackbar, onBack = { openListId = null })
            } else {
                ListScreen(app, open, openStatus, snackbar, onBack = { openListId = null })
            }
        }
    }
}

@Composable
private fun ListsScreen(
    app: TwoDoApp,
    lists: List<TodoList>,
    status: Map<String, SyncStatus>,
    snackbar: SnackbarHostState,
    appTheme: AppTheme,
    onOpen: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var joining by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.brand_name), style = MaterialTheme.typography.titleLarge) },
                colors = flatBar(),
                actions = {
                    TextButton(onClick = { joining = true }) { Text("Join list") }
                    IconButton(onClick = { settings = true }) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("New") },
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
                Text(
                    "Create a list or a diary, or join one by scanning the QR code on another phone.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        LazyColumn(contentPadding = padding) {
            items(lists, key = { it.id }) { list ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(list.id) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SpaceBadge(list, appTheme)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(list.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${list.summary()} · ${status[list.id].summary()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SyncDot(status[list.id])
                }
                HorizontalDivider(Modifier.padding(start = 70.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }

    if (creating) {
        NewSpaceDialog(onDismiss = { creating = false }) { name, kind ->
            creating = false
            scope.launch { onOpen(app.repo.createList(name, kind).id) }
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
    var showHistory by rememberSaveable(list.id) { mutableStateOf(false) }
    val names by app.sync.names.collectAsStateWithLifecycle()
    val highlighted = rememberRemoteHighlights(app, list.id)
    if (showHistory) return HistoryScreen(list, app.identity.deviceId, names, onBack = { showHistory = false })

    fun add() {
        val text = newText
        newText = ""
        scope.launch { app.repo.addItem(list.id, text) }
    }

    Scaffold(
        topBar = { SpaceTopBar(app, list, status, onBack, onHistory = { showHistory = true }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            JoiningBanner(list, status)
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newText,
                    onValueChange = { newText = it },
                    placeholder = { Text("Add an item") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = ::add, enabled = newText.isNotBlank()) { Icon(Icons.Default.Add, "Add") }
            }
            // Local copy so dragging is smooth; committed to the repository when the drag ends.
            var ordered by remember(list.visibleItems) { mutableStateOf(list.visibleItems) }
            val listState = rememberLazyListState()
            val reorderState = rememberReorderableLazyListState(listState) { from, to ->
                ordered = ordered.toMutableList().apply { add(to.index, removeAt(from.index)) }
            }
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                items(ordered, key = { it.id }) { item ->
                    ReorderableItem(reorderState, key = item.id) {
                        val background by animateColorAsState(
                            if (item.id in highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            animationSpec = tween(600),
                            label = "highlight",
                        )
                        Row(
                            Modifier.fillMaxWidth()
                                .background(background)
                                .clickable { scope.launch { app.repo.setChecked(list.id, item.id, !item.checked) } }
                                .padding(start = 8.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = item.checked,
                                onCheckedChange = { scope.launch { app.repo.setChecked(list.id, item.id, it) } },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = MaterialTheme.colorScheme.primary,
                                    uncheckedColor = MaterialTheme.colorScheme.outline,
                                ),
                            )
                            Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                                Text(
                                    item.text,
                                    style = MaterialTheme.typography.bodyLarge,
                                    textDecoration = if (item.checked) TextDecoration.LineThrough else null,
                                    color = if (item.checked) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                                )
                                if (item.checked) {
                                    Text(
                                        tickedBy(item, app.identity.deviceId, names),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                            IconButton(onClick = { scope.launch { app.repo.deleteItem(list.id, item.id) } }) {
                                Icon(Icons.Default.Close, "Delete", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
                            }
                            Icon(
                                painterResource(R.drawable.ic_drag_handle),
                                "Reorder",
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.draggableHandle(onDragStopped = {
                                    val index = ordered.indexOfFirst { it.id == item.id }
                                    scope.launch { app.repo.moveItem(list.id, item.id, index) }
                                }).padding(12.dp),
                            )
                        }
                    }
                }
            }
        }
    }

}

/** Title with sync status, Share, and a menu with History and Remove — shared by lists and diaries. */
@Composable
internal fun SpaceTopBar(app: TwoDoApp, list: TodoList, status: SyncStatus, onBack: () -> Unit, onHistory: () -> Unit) {
    var sharing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var pickingTheme by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    TopAppBar(
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(list.kind.icon, list.kind.label, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(list.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SyncDot(status)
                    Spacer(Modifier.width(6.dp))
                    Text(status.summary(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        colors = flatBar(),
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        },
        actions = {
            IconButton(onClick = { sharing = true }) { Icon(Icons.Default.Share, "Share") }
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("History") }, onClick = { menu = false; onHistory() })
                DropdownMenuItem(text = { Text("Theme") }, onClick = { menu = false; pickingTheme = true })
                DropdownMenuItem(text = { Text("Remove from this phone") }, onClick = { menu = false; confirmRemove = true })
            }
        },
    )
    if (sharing) ShareDialog(app, list, onDismiss = { sharing = false })
    if (pickingTheme) {
        val appThemeId by app.appTheme.collectAsStateWithLifecycle()
        ThemePickerDialog(
            title = "Theme for “${list.name}”",
            selected = list.themeId,
            appTheme = themeById(appThemeId),
            icon = list.kind.icon,
            onDismiss = { pickingTheme = false },
        ) { id -> scope.launch { app.repo.setTheme(list.id, id) } }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove “${list.name}”?") },
            text = { Text("It's removed from this phone only. Other devices keep their copy, and you can rejoin with the QR code.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    onBack()
                    app.sync.leave(list.id)
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Item ids changed by others in the last moment, for a brief highlight. Also clears the list's
 * "changes" notification, since the user is now looking at it.
 */
@Composable
internal fun rememberRemoteHighlights(app: TwoDoApp, listId: String): Map<String, Long> {
    val context = LocalContext.current
    val highlighted = remember(listId) { mutableStateMapOf<String, Long>() }
    LaunchedEffect(listId) {
        Notifications.clearChanges(context, listId)
        app.sync.events.collect { event ->
            if (event !is ListEvent.Changed || event.listId != listId) return@collect
            val stamp = System.currentTimeMillis()
            event.itemIds.forEach { highlighted[it] = stamp }
            launch {
                delay(HIGHLIGHT_MS)
                event.itemIds.forEach { if (highlighted[it] == stamp) highlighted.remove(it) }
            }
        }
    }
    return highlighted
}

@Composable
internal fun ShareDialog(app: TwoDoApp, list: TodoList, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // Look for the newcomer every few seconds while the code is on screen.
    DisposableEffect(list.id) {
        app.sync.setSharing(list.id, true)
        onDispose { app.sync.setSharing(list.id, false) }
    }
    // Whoever connects to this list for the first time while the dialog is open.
    val membersBefore = remember(list.id) { list.members.keys }
    val newcomers = list.members.filterKeys { it !in membersBefore }.values
    val link = remember(list.id) { ShareLink.build(list) }
    val qr = remember(link) { BarcodeEncoder().encodeBitmap(link, BarcodeFormat.QR_CODE, 720, 720).asImageBitmap() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share “${list.name}”") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(qr, "QR code for this list", Modifier.size(260.dp))
                Spacer(Modifier.height(12.dp))
                Text("Scan this with ${stringResource(R.string.brand_name)} on the other phone (Join list). Anyone with this code can see and edit the list.")
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (newcomers.isEmpty()) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Waiting for someone to scan or open the link…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "✓ ${newcomers.joinToString(", ")} joined",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, ShareLink.message(list, context.getString(R.string.brand_name)))
                context.startActivity(Intent.createChooser(send, "Send link"))
            }) { Text("Send link") }
        },
    )
}

@Composable
private fun JoinDialog(onDismiss: () -> Unit, onJoin: (Invite) -> Unit) {
    val appName = stringResource(R.string.brand_name)
    var link by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@rememberLauncherForActivityResult
        ShareLink.parse(contents)?.let(onJoin) ?: run { error = "That QR code isn't a $appName list." }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            QrImage.decode(context, uri)?.let(ShareLink::parse)?.let(onJoin)
                ?: run { error = "Couldn't find a $appName QR code in that image." }
        }
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
                OutlinedButton(
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Choose screenshot") }
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
                ShareLink.parse(link)?.let(onJoin) ?: run { error = "That isn't a $appName link." }
            }, enabled = link.isNotBlank()) { Text("Join") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SettingsDialog(app: TwoDoApp, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(if (app.identity.hasName) app.identity.deviceName else "") }
    var background by remember { mutableStateOf(app.identity.backgroundSync) }
    var themeId by remember { mutableStateOf(app.identity.themeId) }
    var pickingTheme by remember { mutableStateOf(false) }
    if (pickingTheme) {
        return ThemePickerDialog(
            title = "App theme",
            selected = themeId,
            onDismiss = { pickingTheme = false },
        ) { id -> if (id != null) themeId = id }
    }
    var notifyChanges by remember { mutableStateOf(app.identity.notifyChanges) }
    var showLog by remember { mutableStateOf(false) }
    var direct by remember { mutableStateOf(app.identity.directConnections) }
    if (showLog) return ConnectionLogDialog(onDismiss = { showLog = false })
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Your name") },
                    supportingText = { Text("Shown to others next to items you tick") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { pickingTheme = true }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Theme")
                        Text(
                            "${themeById(themeId).name} · lists and diaries can have their own (⋮ › Theme)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val colors = themeById(themeId).colors
                    Box(
                        Modifier.size(28.dp).clip(CircleShape).background(colors.background)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Box(Modifier.size(12.dp).clip(CircleShape).background(colors.primary)) }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Notify me about changes")
                        Text(
                            "When others change a list while ${stringResource(R.string.brand_name)} is closed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = notifyChanges, onCheckedChange = { notifyChanges = it })
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Sync in background")
                        Text(
                            "Stay reachable while the app is closed. Shows a permanent notification and uses some battery.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = background, onCheckedChange = { background = it })
                }
                if (BuildConfig.DEBUG) {
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Direct connections (debug)", Modifier.weight(1f))
                        Switch(checked = direct, onCheckedChange = { direct = it })
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { showLog = true }, contentPadding = PaddingValues(0.dp)) { Text("Connection log") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) app.setName(name)
                app.setTheme(themeId)
                app.identity.notifyChanges = notifyChanges
                if (direct != app.identity.directConnections) {
                    app.identity.directConnections = direct
                    app.sync.refresh()
                }
                if (background != app.identity.backgroundSync) app.setBackgroundSync(background)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Recent connection events with timings, to see why connecting is slow; Copy to send them on. */
@Composable
private fun ConnectionLogDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var lines by remember { mutableStateOf(SyncLog.snapshot()) }
    // Keep it live while open.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            lines = SyncLog.snapshot()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connection log") },
        text = {
            if (lines.isEmpty()) {
                Text("Nothing yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val listState = rememberLazyListState()
                LaunchedEffect(lines.size) { listState.scrollToItem(lines.size - 1) }
                LazyColumn(Modifier.heightIn(max = 420.dp), state = listState) {
                    items(lines) {
                        Text(it, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            TextButton(onClick = {
                val header = "${context.getString(R.string.brand_name)} ${BuildConfig.VERSION_NAME} on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}"
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("Connection log", (listOf(header) + lines).joinToString("\n")))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            }) { Text("Copy") }
        },
    )
}

/** "Groceries: 3 to do" / "Family diary: 2 today". */
/** Progress while a newly joined list or diary is found and fetched. */
@Composable
internal fun JoiningBanner(list: TodoList, status: SyncStatus) {
    // The phone that shares also does a first sync with a newcomer; that side gets the share dialog's status instead.
    if (!list.isJoining && !(status.receiving && !list.createdHere)) return
    val what = if (list.kind == SpaceKind.DIARY) "diary" else "list"
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), trackColor = MaterialTheme.colorScheme.surfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                status.receiving || status.peerNames.isNotEmpty() || status.relayPeerNames.isNotEmpty() -> "Getting the $what…"
                !status.online -> "Waiting for an internet connection…"
                status.trackersOnline == 0 && status.relaysOnline == 0 -> "Connecting…"
                else -> "Looking for the other phone… ${stringResource(R.string.brand_name)} needs to be open there, or have Sync in background on."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun TodoList.summary(): String = if (isJoining) "Joining…" else when (kind) {
    SpaceKind.DIARY -> {
        val today = java.time.LocalDate.now().toString()
        val count = items.values.count { !it.deleted && it.date == today }
        if (count == 0) "Diary · nothing today" else "Diary · $count today"
    }
    SpaceKind.LIST -> "${visibleItems.count { !it.checked }} to do"
}

/** Name plus a List / Diary choice. */
@Composable
private fun NewSpaceDialog(onDismiss: () -> Unit, onCreate: (String, SpaceKind) -> Unit) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(SpaceKind.LIST) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (kind == SpaceKind.DIARY) "New diary" else "New list") },
        text = {
            Column {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SpaceKind.entries.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = kind == option,
                            onClick = { kind = option },
                            shape = SegmentedButtonDefaults.itemShape(index, SpaceKind.entries.size),
                            icon = { Icon(option.icon, null, Modifier.size(18.dp)) },
                        ) { Text(option.label) }
                    }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name, kind) }, enabled = name.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun TextPromptDialog(
    title: String,
    label: String,
    confirm: String,
    supporting: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                supportingText = supporting?.let { { Text(it) } },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** e.g. "Adam · 5 min. ago" — who ticked the item and when. */
private fun tickedBy(item: Item, myDeviceId: String, names: Map<String, String>): String {
    val who = if (item.version.by == myDeviceId) "You" else names[item.version.by] ?: item.editor
    val now = System.currentTimeMillis()
    val time = if (now - item.version.ts < DateUtils.MINUTE_IN_MILLIS) {
        "just now"
    } else {
        DateUtils.getRelativeTimeSpanString(item.version.ts, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
    }
    return "$who · $time"
}

@Composable
internal fun flatBar() = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.background,
    scrolledContainerColor = MaterialTheme.colorScheme.background,
)

/** Small status dot: accent when connected to another device, grey otherwise. */
@Composable
internal fun SyncDot(status: SyncStatus?) {
    val connected = !status?.peerNames.isNullOrEmpty() || !status?.relayPeerNames.isNullOrEmpty()
    Box(
        Modifier.size(8.dp).clip(CircleShape)
            .background(if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
    )
}

internal const val HIGHLIGHT_MS = 2_500L

internal fun SyncStatus?.summary(): String = when {
    this == null -> "Connecting…"
    !online && peerNames.isEmpty() -> "Offline"
    trackersOnline == 0 && relaysOnline == 0 && peerNames.isEmpty() -> "Connecting…"
    peerNames.size == 1 -> "Connected to ${peerNames.single()}"
    peerNames.size > 1 -> "Connected to ${peerNames.size} devices"
    relayPeerNames.isNotEmpty() -> "Syncing with ${relayPeerNames.joinToString(", ")} via relay"
    else -> "Looking for other devices…"
}
