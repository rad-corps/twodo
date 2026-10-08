package app.twodo.data

import android.util.Log
import app.twodo.model.AuditEntry
import app.twodo.model.Conflict
import app.twodo.model.Invite
import app.twodo.model.Item
import app.twodo.model.ListKeys
import app.twodo.model.TodoList
import app.twodo.model.Version
import app.twodo.model.auditEntryFor
import app.twodo.model.edited
import app.twodo.model.merge
import app.twodo.model.mergeAudit
import app.twodo.model.moved
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** A local edit that needs to be sent to peers, with its audit entry if it's worth recording. */
data class LocalEdit(val listId: String, val item: Item, val audit: AuditEntry?)

/** Result of applying a peer's data: items that changed (with their previous state) and new audit entries. */
data class RemoteResult(val changes: List<Pair<Item?, Item>>, val newAudit: List<AuditEntry>)

/** All lists on this device, one JSON file each. The single source of truth for UI and sync. */
class ListRepository(private val dir: File, private val identity: Identity) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    private val _lists = MutableStateFlow(load())
    val lists: StateFlow<Map<String, TodoList>> = _lists.asStateFlow()

    private val _localEdits = MutableSharedFlow<LocalEdit>(extraBufferCapacity = 256)
    val localEdits: SharedFlow<LocalEdit> = _localEdits

    private val _conflicts = MutableSharedFlow<Conflict>(extraBufferCapacity = 64)
    val conflicts: SharedFlow<Conflict> = _conflicts

    suspend fun createList(name: String): TodoList = mutex.withLock {
        val list = TodoList(UUID.randomUUID().toString(), name.trim().ifEmpty { "My list" }, ListKeys.newSecret(), createdHere = true)
        save(list)
        list
    }

    /** Adds a shared list from an invite; returns the existing one if already joined. */
    suspend fun joinList(invite: Invite): TodoList = mutex.withLock {
        _lists.value[invite.listId] ?: TodoList(invite.listId, invite.name, invite.secret).also { save(it) }
    }

    /** Removes the list from this device only; peers keep their copies. */
    suspend fun removeList(listId: String) = mutex.withLock {
        withContext(Dispatchers.IO) { file(listId).delete() }
        _lists.value -= listId
    }

    /** Adds an item at the bottom of the list. */
    suspend fun addItem(listId: String, text: String) {
        val trimmed = text.trim().ifEmpty { return }
        putLocal(listId) { list ->
            val now = System.currentTimeMillis()
            Item(
                id = UUID.randomUUID().toString(),
                text = trimmed,
                createdAt = now,
                version = Version(now, identity.deviceId),
                editor = identity.deviceName,
                pos = (list.visibleItems.maxOfOrNull { it.position } ?: 0.0) + 1,
                posVersion = Version(now, identity.deviceId),
            )
        }
    }

    /** Moves an item so it ends up at [toIndex] among the visible items. */
    suspend fun moveItem(listId: String, itemId: String, toIndex: Int) = putLocal(listId) { list ->
        val others = list.visibleItems.filter { it.id != itemId }
        val item = list.items[itemId] ?: return@putLocal null
        val index = toIndex.coerceIn(0, others.size)
        val before = others.getOrNull(index - 1)?.position
        val after = others.getOrNull(index)?.position
        val pos = when {
            before == null && after == null -> item.position
            before == null -> after!! - 1
            after == null -> before + 1
            else -> (before + after) / 2
        }
        item.moved(pos, identity.deviceId, System.currentTimeMillis())
    }

    suspend fun setChecked(listId: String, itemId: String, checked: Boolean) =
        editItem(listId, itemId) { copy(checked = checked) }

    suspend fun deleteItem(listId: String, itemId: String) =
        editItem(listId, itemId) { copy(deleted = true) }

    /**
     * Applies items and audit entries from a peer. Returns what changed locally, so it can be
     * forwarded and announced.
     */
    suspend fun applyRemote(listId: String, items: List<Item>, audit: List<AuditEntry> = emptyList()): RemoteResult =
        mutex.withLock {
            val list = _lists.value[listId] ?: return RemoteResult(emptyList(), emptyList())
            val result = list.merge(items)
            val (merged, newAudit) = result.list.mergeAudit(audit)
            if (merged != list) save(merged)
            result.conflicts.forEach { _conflicts.tryEmit(it) }
            RemoteResult(result.accepted.map { list.items[it.id] to it }, newAudit)
        }

    data class MemberUpdate(val isNew: Boolean, val firstContact: Boolean, val worthAnnouncing: Boolean)

    /** Records that [deviceId] (called [name]) is on the list. */
    suspend fun recordMember(listId: String, deviceId: String, name: String): MemberUpdate = mutex.withLock {
        val list = _lists.value[listId] ?: return MemberUpdate(false, false, false)
        val isNew = deviceId !in list.members
        val firstContact = list.members.isEmpty()
        if (list.members[deviceId] != name) save(list.copy(members = list.members + (deviceId to name)))
        // Someone new is only news on a list we created or have already been sharing.
        MemberUpdate(isNew, firstContact, worthAnnouncing = isNew && (!firstContact || list.createdHere))
    }

    /** Forgets a device that left the list; returns its name. */
    suspend fun removeMember(listId: String, deviceId: String): String? = mutex.withLock {
        val list = _lists.value[listId] ?: return null
        val name = list.members[deviceId] ?: return null
        save(list.copy(members = list.members - deviceId))
        name
    }

    private suspend fun editItem(listId: String, itemId: String, change: Item.() -> Item) = putLocal(listId) { list ->
        list.items[itemId]?.edited(identity.deviceId, identity.deviceName, System.currentTimeMillis(), change)
    }

    private suspend fun putLocal(listId: String, item: Item): Unit = putLocal(listId) { item }

    private suspend fun putLocal(listId: String, makeItem: (TodoList) -> Item?): Unit = mutex.withLock {
        val list = _lists.value[listId] ?: return
        val item = makeItem(list) ?: return
        val entry = auditEntryFor(list.items[item.id], item)
        val audit = if (entry != null) list.audit + (entry.id to entry) else list.audit
        save(list.copy(items = list.items + (item.id to item), audit = audit))
        _localEdits.tryEmit(LocalEdit(listId, item, entry))
    }

    private suspend fun save(list: TodoList) {
        _lists.value += list.id to list
        withContext(Dispatchers.IO) {
            val tmp = File(dir, "${list.id}.json.tmp")
            tmp.writeText(json.encodeToString(list))
            if (!tmp.renameTo(file(list.id))) {
                file(list.id).delete()
                tmp.renameTo(file(list.id))
            }
        }
    }

    private fun file(listId: String) = File(dir, "$listId.json")

    private fun load(): Map<String, TodoList> {
        dir.mkdirs()
        return dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
            runCatching { json.decodeFromString<TodoList>(f.readText()) }
                .onFailure { Log.w("TwoDo", "Skipping unreadable list ${f.name}", it) }
                .getOrNull()
        }.associateBy { it.id }
    }
}
