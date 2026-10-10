@file:OptIn(ExperimentalMaterial3Api::class)

package app.twodo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.twodo.TwoDoApp
import app.twodo.data.ALL_CALENDARS
import app.twodo.model.SpaceKind
import app.twodo.model.TodoList
import app.twodo.model.calendarRef
import app.twodo.model.isJoining
import app.twodo.model.listRefs
import app.twodo.sync.SyncStatus
import kotlinx.coroutines.launch

enum class GroupTab(val label: String, val icon: ImageVector) {
    CALENDAR("Calendar", Icons.Outlined.CalendarMonth),
    LISTS("Lists", Icons.Outlined.Checklist),
    /** The group's people, inviting, and its look, name and leaving. */
    SETTINGS("Group settings", Icons.Outlined.Settings),
}

/** Where to go from the group switcher on the title. */
class GroupNavigation(
    val groups: List<TodoList>,
    val hasOtherLists: Boolean,
    /** Offered only with more than one calendar: with just one, it would be the same thing twice. */
    val hasAllCalendars: Boolean,
    val onSwitch: (String) -> Unit,
    val onAllCalendars: () -> Unit,
    val onOtherLists: () -> Unit,
    val onNewGroup: () -> Unit,
    val onJoin: () -> Unit,
    val onSettings: () -> Unit,
)

/**
 * A group's home: its calendar, its lists and its people, one tab each. Everyone in the group sees the
 * same calendar and lists, and the same look.
 */
@Composable
internal fun GroupScreen(
    app: TwoDoApp,
    group: TodoList,
    lists: Map<String, TodoList>,
    status: Map<String, SyncStatus>,
    snackbar: SnackbarHostState,
    navigation: GroupNavigation,
    onOpenSpace: (String) -> Unit,
) {
    var tab by rememberSaveable(group.id) { mutableStateOf(GroupTab.CALENDAR) }
    var inviting by remember { mutableStateOf(false) }
    val groupStatus = status[group.id] ?: SyncStatus()
    val calendar = group.calendarRef?.spaceId?.let { lists[it] }
    var calendarHistory by remember { mutableStateOf(false) }
    val names by app.sync.names.collectAsStateWithLifecycle()
    if (calendarHistory && calendar != null) {
        return HistoryScreen(calendar, app.identity.deviceId, names, onBack = { calendarHistory = false })
    }

    Scaffold(
        topBar = {
            GroupTopBar(group, groupStatus, navigation, onInvite = { inviting = true },
                onHistory = if (tab == GroupTab.CALENDAR && calendar != null) ({ calendarHistory = true }) else null)
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f)) {
                GroupTab.entries.forEach { option ->
                    NavigationBarItem(
                        selected = tab == option,
                        onClick = { tab = option },
                        icon = { Icon(option.icon, null) },
                        label = { Text(option.label) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer),
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (group.isJoining || (groupStatus.receiving && !group.createdHere)) {
                JoiningBanner(group, groupStatus)
            }
            when (tab) {
                GroupTab.CALENDAR -> if (calendar != null) {
                    DiaryBody(app, calendar, status[calendar.id] ?: SyncStatus(), Modifier.weight(1f))
                } else {
                    EmptyState(if (group.isJoining) "The calendar will appear once you've joined." else "No calendar yet.")
                }
                GroupTab.LISTS -> ListsTab(app, group, lists, onOpenSpace)
                GroupTab.SETTINGS -> GroupSettingsTab(app, group, groupStatus, names, onInvite = { inviting = true }, onLeft = navigation.onOtherLists)
            }
        }
    }
    if (inviting) ShareDialog(app, group, onDismiss = { inviting = false })
}

/** Group name (tap to switch group) with its sync state underneath; Invite on the right. */
@Composable
private fun GroupTopBar(group: TodoList, status: SyncStatus, navigation: GroupNavigation, onInvite: () -> Unit, onHistory: (() -> Unit)?) {
    var switching by remember { mutableStateOf(false) }
    TopAppBar(
        colors = flatBar(),
        title = {
            Box {
                Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable { switching = true }.padding(horizontal = 4.dp, vertical = 2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(group.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Default.ArrowDropDown, "Switch group")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SyncDot(status)
                        Spacer(Modifier.width(6.dp))
                        val alone = group.members.isEmpty() && status.online().isEmpty() && !group.isJoining
                        Text(
                            if (alone) "Just you so far · invite someone" else status.summary(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                GroupSwitcher(switching, group.id, navigation, onDismiss = { switching = false })
            }
        },
        actions = {
            if (onHistory != null) IconButton(onClick = onHistory) { Icon(Icons.Outlined.History, "Calendar history") }
            IconButton(onClick = onInvite) { Icon(Icons.Default.PersonAdd, "Invite someone") }
        },
    )
}

/** Every group, the lists outside groups, and ways to start or join another group. */
@Composable
internal fun GroupSwitcher(expanded: Boolean, currentId: String?, navigation: GroupNavigation, onDismiss: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        navigation.groups.forEach { g ->
            DropdownMenuItem(
                text = { Text(g.name) },
                leadingIcon = { Icon(Icons.Outlined.Groups, null) },
                trailingIcon = if (g.id == currentId) ({ Icon(Icons.Default.Check, "Current") }) else null,
                onClick = { onDismiss(); navigation.onSwitch(g.id) },
            )
        }
        if (navigation.hasAllCalendars) {
            DropdownMenuItem(
                text = { Text("All calendars") },
                leadingIcon = { Icon(Icons.Outlined.CalendarMonth, null) },
                trailingIcon = if (currentId == ALL_CALENDARS) ({ Icon(Icons.Default.Check, "Current") }) else null,
                onClick = { onDismiss(); navigation.onAllCalendars() },
            )
        }
        if (navigation.hasOtherLists) {
            DropdownMenuItem(
                text = { Text("Other lists") },
                leadingIcon = { Icon(Icons.Outlined.Checklist, null) },
                trailingIcon = if (currentId == null) ({ Icon(Icons.Default.Check, "Current") }) else null,
                onClick = { onDismiss(); navigation.onOtherLists() },
            )
        }
        HorizontalDivider()
        DropdownMenuItem(text = { Text("Start a new group") }, leadingIcon = { Icon(Icons.Default.Add, null) }, onClick = { onDismiss(); navigation.onNewGroup() })
        DropdownMenuItem(text = { Text("Join with an invite") }, leadingIcon = { Icon(Icons.Default.PersonAdd, null) }, onClick = { onDismiss(); navigation.onJoin() })
        DropdownMenuItem(text = { Text("App settings") }, leadingIcon = { Icon(Icons.Outlined.Settings, null) }, onClick = { onDismiss(); navigation.onSettings() })
    }
}

@Composable
private fun ListsTab(app: TwoDoApp, group: TodoList, lists: Map<String, TodoList>, onOpenSpace: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    val others = lists.values.filter { it.groupId == null && it.kind == SpaceKind.LIST }.sortedBy { it.name.lowercase() }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(group.listRefs, key = { it.id }) { ref ->
                val list = ref.spaceId?.let { lists[it] }
                Card(
                    onClick = { list?.let { onOpenSpace(it.id) } },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.9f)),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Outlined.Checklist, null, tint = MaterialTheme.colorScheme.primary) }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(ref.text, style = MaterialTheme.typography.titleMedium)
                            Text(
                                list?.let { toDoSummary(it) } ?: "Getting it…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
        // At the bottom, within thumb reach, like the add bars in lists and the calendar.
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 14.dp)) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("New list")
            }
            if (others.isNotEmpty()) {
                TextButton(onClick = { moving = true }) { Text("Move a list into ${group.name}") }
            }
        }
    }
    if (creating) {
        TextPromptDialog(
            title = "New list",
            label = "Name",
            confirm = "Create",
            supporting = "Everyone in ${group.name} will have it. For example: To do, Holiday packing, Meal ideas.",
            onDismiss = { creating = false },
        ) { name ->
            creating = false
            scope.launch { app.repo.addToGroup(group.id, name)?.let { onOpenSpace(it.id) } }
        }
    }
    if (moving) {
        AlertDialog(
            onDismissRequest = { moving = false },
            title = { Text("Move a list into ${group.name}") },
            text = {
                Column {
                    Text("Everyone in ${group.name} will get it. Anyone you shared it with before keeps it too.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    others.forEach { list ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .clickable { moving = false; scope.launch { app.repo.moveIntoGroup(group.id, list.id) } }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.DriveFileMove, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Text(list.name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { moving = false }) { Text("Cancel") } },
        )
    }
}

private fun toDoSummary(list: TodoList): String {
    if (list.isJoining) return "Getting it…"
    val left = list.visibleItems.count { !it.checked && !it.heading }
    return when (left) {
        0 -> "All done"
        1 -> "1 thing to do"
        else -> "$left things to do"
    }
}

@Composable
private fun GroupSettingsTab(
    app: TwoDoApp,
    group: TodoList,
    status: SyncStatus,
    names: Map<String, String>,
    onInvite: () -> Unit,
    onLeft: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var editingLook by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    val online = status.online().toSet()
    val people = group.members.map { (id, name) -> id to (names[id] ?: name) }.sortedBy { it.second.lowercase() }
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item { SectionTitle("People in ${group.name}") }
        item { PersonRow(app.identity.deviceId, "${app.identity.deviceName} (you)", "This phone", true) }
        items(people, key = { it.first }) { (id, name) ->
            val here = name in online
            PersonRow(id, name, if (here) "In sync now" else "Not online right now", here)
        }
        item {
            if (people.isEmpty()) {
                Text(
                    "Nobody else yet. Invite the people you want to share the calendar and lists with.",
                    Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = onInvite,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                contentPadding = PaddingValues(vertical = 14.dp),
            ) {
                Icon(Icons.Default.PersonAdd, null)
                Spacer(Modifier.width(8.dp))
                Text("Invite someone")
            }
            Spacer(Modifier.height(12.dp))
            SectionTitle("This group")
            SettingRow(Icons.Outlined.Palette, "Look", "Colours and a background photo, for everyone in ${group.name}") { editingLook = true }
            SettingRow(Icons.Outlined.Edit, "Rename group", group.name) { renaming = true }
            SettingRow(Icons.AutoMirrored.Outlined.Logout, "Leave group", "Removes ${group.name} from this phone", danger = true) { leaving = true }
        }
    }
    if (editingLook) LookEditorDialog(app, group, onDismiss = { editingLook = false })
    if (renaming) {
        TextPromptDialog(title = "Rename group", label = "Name", confirm = "Save", initial = group.name, onDismiss = { renaming = false }) { name ->
            renaming = false
            scope.launch { app.repo.rename(group.id, name) }
        }
    }
    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text("Leave ${group.name}?") },
            text = { Text("Its calendar and lists are removed from this phone. Everyone else keeps them, and you can rejoin with an invite.") },
            confirmButton = {
                TextButton(onClick = {
                    leaving = false
                    onLeft()
                    app.sync.leave(group.id)
                }) { Text("Leave", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { leaving = false }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        Modifier.padding(top = 4.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun PersonRow(id: String, name: String, detail: String, here: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(id, name)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(8.dp).clip(CircleShape)
                        .background(if (here) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                )
                Spacer(Modifier.width(6.dp))
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A coloured circle with the person's initial; the colour is fixed per device so people are easy to spot. */
@Composable
internal fun Avatar(id: String, name: String, size: Int = 44) {
    val color = personColor(id)
    Box(Modifier.size(size.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(
            name.trim().firstOrNull()?.uppercase() ?: "?",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, detail: String, danger: Boolean = false, onClick: () -> Unit) {
    val tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (danger) tint else MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = tint)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** A big, friendly primary action, for screens aimed at first-time users. */
@Composable
internal fun BigButton(text: String, icon: ImageVector? = null, outlined: Boolean = false, onClick: () -> Unit) {
    val padding = PaddingValues(vertical = 16.dp)
    val content: @Composable () -> Unit = {
        if (icon != null) {
            Icon(icon, null)
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
    if (outlined) {
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), contentPadding = padding) { content() }
    } else {
        Button(onClick = onClick, modifier = Modifier.fillMaxWidth(), contentPadding = padding, colors = ButtonDefaults.buttonColors()) { content() }
    }
}
