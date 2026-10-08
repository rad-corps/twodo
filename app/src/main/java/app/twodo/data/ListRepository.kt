package app.twodo.data

import android.util.Log
import app.twodo.model.AuditEntry
import app.twodo.model.Conflict
import app.twodo.model.Invite
import app.twodo.model.Item
import app.twodo.model.ListKeys
import app.twodo.model.SpaceKind
import app.twodo.model.TodoList
import app.twodo.model.Version
import app.twodo.model.auditEntryFor
import app.twodo.model.edited
import app.twodo.model.merge
import app.twodo.model.mergeAudit
import app.twodo.model.moved
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import java.time.LocalDate
import java.util.UUID

/** A local edit that needs to be sent to peers, with its audit entry if it's worth recording. */
data class LocalEdit(val listId: String, val item: Item, val audit: AuditEntry?)

/** Result of applying a peer's data: items that changed (with their previous state) and new audit entries. */
data class RemoteResult(val changes: List<Pair<Item?, Item>>, val newAudit: List<AuditEntry>)

/** All lists on this device, one JSON file each. The single source of truth for UI and sync. */
class ListRepository(private val dir: File, private val identity: Identity) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    /** Serialises file writes and deletes. */
    private val fileMutex = Mutex()
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val unsaved = mutableSetOf<String>()
    private var pendingWrite: Job? = null

    private val _lists = MutableStateFlow(load())
    val lists: StateFlow<Map<String, TodoList>> = _lists.asStateFlow()

    private val _localEdits = MutableSharedFlow<LocalEdit>(extraBufferCapacity = 256)
    val localEdits: SharedFlow<LocalEdit> = _localEdits

    private val _conflicts = MutableSharedFlow<Conflict>(extraBufferCapacity = 64)
    val conflicts: SharedFlow<Conflict> = _conflicts

    suspend fun createList(name: String, kind: SpaceKind = SpaceKind.LIST): TodoList = mutex.withLock {
        val fallback = if (kind == SpaceKind.DIARY) "Diary" else "My list"
        val list = TodoList(UUID.randomUUID().toString(), name.trim().ifEmpty { fallback }, ListKeys.newSecret(), createdHere = true, kind = kind)
        save(list)
        list
    }

    /** Adds a shared list from an invite; returns the existing one if already joined. */
    suspend fun joinList(invite: Invite): TodoList = mutex.withLock {
        _lists.value[invite.listId] ?: TodoList(invite.listId, invite.name, invite.secret, kind = invite.kind).also { save(it) }
    }

    /** Removes the list from this device only; peers keep their copies. */
    suspend fun removeList(listId: String) = mutex.withLock {
        _lists.value -= listId
        fileMutex.withLock { withContext(Dispatchers.IO) { file(listId).delete() } }
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

    /** Adds a diary entry on [date], optionally at [time] ("HH:mm"). */
    suspend fun addEntry(listId: String, date: LocalDate, text: String, time: String? = null) {
        val trimmed = text.trim().ifEmpty { return }
        putLocal(listId) {
            val now = System.currentTimeMillis()
            Item(
                id = UUID.randomUUID().toString(),
                text = trimmed,
                createdAt = now,
                version = Version(now, identity.deviceId),
                editor = identity.deviceName,
                date = date.toString(),
                time = time,
            )
        }
    }

    /** Changes a diary entry's text, day and/or time. Does nothing if nothing changed. */
    suspend fun editEntry(listId: String, itemId: String, text: String, date: LocalDate, time: String?) {
        val trimmed = text.trim().ifEmpty { return }
        putLocal(listId) { list ->
            val item = list.items[itemId] ?: return@putLocal null
            if (item.text == trimmed && item.date == date.toString() && item.time == time) return@putLocal null
            item.edited(identity.deviceId, identity.deviceName, System.currentTimeMillis()) {
                copy(text = trimmed, date = date.toString(), time = time)
            }
        }
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
            // Synced data can be re-fetched, so its writes are batched: a big sync arrives in many chunks.
            if (merged != list) saveSoon(merged)
            result.conflicts.forEach { _conflicts.tryEmit(it) }
            RemoteResult(result.accepted.map { list.items[it.id] to it }, newAudit)
        }

    /** Records that this device now has a complete copy of the list. */
    suspend fun markFullSynced(listId: String): Unit = mutex.withLock {
        val list = _lists.value[listId] ?: return
        if (!list.fullSynced) saveSoon(list.copy(fullSynced = true))
    }

    /** Remembers the newest relay event seen, so the next start fetches only what's new. */
    suspend fun setRelaySince(listId: String, createdAt: Long): Unit = mutex.withLock {
        val list = _lists.value[listId] ?: return
        if (createdAt > list.relaySince) saveSoon(list.copy(relaySince = createdAt))
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

    /** Updates the list and writes it to disk now (local edits). */
    private suspend fun save(list: TodoList) {
        _lists.value += list.id to list
        write(list.id)
    }

    /** Updates the list now and writes it once changes stop arriving for a moment. */
    private fun saveSoon(list: TodoList) {
        _lists.value += list.id to list
        synchronized(unsaved) {
            unsaved += list.id
            if (pendingWrite != null) return
            pendingWrite = writer.launch {
                // Keep going until nothing is left: changes can arrive while a write is in progress.
                while (true) {
                    delay(SAVE_DELAY_MS)
                    val ids = synchronized(unsaved) {
                        if (unsaved.isEmpty()) {
                            pendingWrite = null
                            return@launch
                        }
                        unsaved.toList().also { unsaved.clear() }
                    }
                    ids.forEach { write(it) }
                }
            }
        }
    }

    /** Writes the list's current state, unless it has been removed meanwhile. */
    private suspend fun write(listId: String) = fileMutex.withLock {
        val list = _lists.value[listId] ?: return@withLock
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

    private companion object {
        const val SAVE_DELAY_MS = 300L
    }

    private fun load(): Map<String, TodoList> {
        dir.mkdirs()
        return dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
            runCatching { json.decodeFromString<TodoList>(f.readText()) }
                .onFailure { Log.w("TwoDo", "Skipping unreadable list ${f.name}", it) }
                .getOrNull()
        }.associateBy { it.id }
    }
}
