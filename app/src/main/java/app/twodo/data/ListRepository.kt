package app.twodo.data

import android.util.Log
import app.twodo.model.AuditEntry
import app.twodo.model.Conflict
import app.twodo.model.GROUP_LOOK_ITEM
import app.twodo.model.GROUP_NAME_ITEM
import app.twodo.model.Look
import app.twodo.model.indexForSection
import app.twodo.model.isPhotoPart
import app.twodo.model.photoBase64
import app.twodo.model.photoPartId
import app.twodo.model.sharedLook
import app.twodo.model.splitPhoto
import app.twodo.model.toJson
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
import app.twodo.model.planGroup
import app.twodo.model.syncedGroupName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import java.util.Base64
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

    private val _photos = MutableStateFlow(0)

    /** Ticks whenever a group photo has been saved, so screens can show it. */
    val photos: StateFlow<Int> = _photos.asStateFlow()

    private val _conflicts = MutableSharedFlow<Conflict>(extraBufferCapacity = 64)
    val conflicts: SharedFlow<Conflict> = _conflicts

    suspend fun createList(name: String, kind: SpaceKind = SpaceKind.LIST): TodoList = mutex.withLock { newSpace(name, kind) }

    private suspend fun newSpace(name: String, kind: SpaceKind, groupId: String? = null): TodoList {
        val fallback = when (kind) {
            SpaceKind.DIARY -> "Calendar"
            SpaceKind.GROUP -> "Family"
            SpaceKind.LIST -> "My list"
        }
        val list = TodoList(
            UUID.randomUUID().toString(), name.trim().ifEmpty { fallback }, ListKeys.newSecret(),
            createdHere = true, kind = kind, groupId = groupId,
        ).let { if (kind == SpaceKind.DIARY) it.copy(items = birthEntry(it).let { e -> mapOf(e.id to e) }) else it }
        save(list)
        return list
    }

    /** A new calendar's first entry: when it was made ("Calendar created", today, at the time). */
    private fun birthEntry(calendar: TodoList): Item {
        val now = System.currentTimeMillis()
        val time = java.time.LocalTime.now()
        return Item(
            id = UUID.randomUUID().toString(),
            text = "${calendar.name} created",
            createdAt = now,
            version = Version(now, identity.deviceId),
            editor = identity.deviceName,
            date = LocalDate.now().toString(),
            time = "%02d:%02d".format(time.hour, time.minute),
        )
    }

    /**
     * Starts a group, ready to invite people to. Its calendar is the diary [calendarId] if given (someone
     * who already has one), otherwise a new one; [listIds] are existing lists to bring in. With none, it
     * starts with a shopping list.
     */
    suspend fun createGroup(name: String, calendarId: String? = null, listIds: List<String> = emptyList()): TodoList = withContext(NonCancellable) { createGroupLocked(name, calendarId, listIds) }

    private suspend fun createGroupLocked(name: String, calendarId: String?, listIds: List<String>): TodoList = mutex.withLock {
        val group = newSpace(name, SpaceKind.GROUP)
        putGroupItem(group.id, nameItem(group.name))
        val existing = (listOfNotNull(calendarId) + listIds).mapNotNull { _lists.value[it] }.filter { it.kind != SpaceKind.GROUP && it.groupId == null }
        if (existing.none { it.kind == SpaceKind.DIARY }) putGroupItem(group.id, spaceItem(newSpace("Calendar", SpaceKind.DIARY, group.id)))
        existing.forEach { list ->
            save(list.copy(groupId = group.id))
            putGroupItem(group.id, spaceItem(list))
        }
        if (existing.none { it.kind == SpaceKind.LIST }) putGroupItem(group.id, spaceItem(newSpace("Shopping", SpaceKind.LIST, group.id)))
        _lists.value.getValue(group.id)
    }

    /** Adds a new list to the group, for everyone in it. */
    suspend fun addToGroup(groupId: String, name: String, kind: SpaceKind = SpaceKind.LIST): TodoList? = withContext(NonCancellable) { addToGroupLocked(groupId, name, kind) }

    private suspend fun addToGroupLocked(groupId: String, name: String, kind: SpaceKind): TodoList? = mutex.withLock {
        if (_lists.value[groupId]?.kind != SpaceKind.GROUP) return null
        val space = newSpace(name, kind, groupId)
        putGroupItem(groupId, spaceItem(space))
        space
    }

    /** Moves an existing list or diary into the group; everyone in the group gets it. */
    suspend fun moveIntoGroup(groupId: String, listId: String): Unit = withContext(NonCancellable) { moveIntoGroupLocked(groupId, listId) }

    private suspend fun moveIntoGroupLocked(groupId: String, listId: String): Unit = mutex.withLock {
        if (_lists.value[groupId]?.kind != SpaceKind.GROUP) return
        val list = _lists.value[listId] ?: return
        save(list.copy(groupId = groupId))
        putGroupItem(groupId, spaceItem(list))
    }

    /** Takes a space out of its group for everyone in it, and removes it from this phone. */
    suspend fun deleteFromGroup(listId: String): Unit = withContext(NonCancellable) { deleteFromGroupLocked(listId) }

    private suspend fun deleteFromGroupLocked(listId: String): Unit = mutex.withLock {
        val list = _lists.value[listId] ?: return
        val groupId = list.groupId ?: return
        val ref = _lists.value[groupId]?.items?.values?.firstOrNull { it.spaceId == listId && !it.deleted }
        if (ref != null) putGroupItem(groupId, ref.edited(identity.deviceId, identity.deviceName, System.currentTimeMillis()) { copy(deleted = true) })
        _lists.value -= listId
        fileMutex.withLock { withContext(Dispatchers.IO) { file(listId).delete() } }
    }

    /** Renames a space; in a group the new name reaches everyone. */
    suspend fun rename(listId: String, name: String): Unit = withContext(NonCancellable) { renameLocked(listId, name) }

    private suspend fun renameLocked(listId: String, name: String): Unit = mutex.withLock {
        val trimmed = name.trim().ifEmpty { return }
        val list = _lists.value[listId] ?: return
        if (list.name == trimmed) return
        save(list.copy(name = trimmed))
        val now = System.currentTimeMillis()
        when {
            list.kind == SpaceKind.GROUP -> {
                val item = list.items[GROUP_NAME_ITEM]
                putGroupItem(listId, item?.edited(identity.deviceId, identity.deviceName, now) { copy(text = trimmed, deleted = false) } ?: nameItem(trimmed))
            }
            list.groupId != null -> {
                val ref = _lists.value[list.groupId]?.items?.values?.firstOrNull { it.spaceId == listId && !it.deleted } ?: return
                putGroupItem(list.groupId, ref.edited(identity.deviceId, identity.deviceName, now) { copy(text = trimmed) })
            }
        }
    }

    /**
     * Sets the group's look for everyone in it. [photoJpeg] replaces the photo; with [removePhoto] the
     * photo goes; otherwise the current one stays.
     */
    suspend fun setGroupLook(groupId: String, look: Look, photoJpeg: ByteArray? = null, removePhoto: Boolean = false): Unit = withContext(NonCancellable) { setGroupLookLocked(groupId, look, photoJpeg, removePhoto) }

    private suspend fun setGroupLookLocked(groupId: String, look: Look, photoJpeg: ByteArray?, removePhoto: Boolean): Unit = mutex.withLock {
        val group = _lists.value[groupId]?.takeIf { it.kind == SpaceKind.GROUP } ?: return
        val now = System.currentTimeMillis()
        val old = group.sharedLook
        var next = look.copy(photoId = old.photoId, photoParts = old.photoParts)
        if (photoJpeg != null || removePhoto) {
            // Clear the old photo's pieces so they don't linger in everyone's copy.
            group.items.values.filter { it.isPhotoPart && !it.deleted }.forEach { part ->
                putGroupItem(groupId, part.edited(identity.deviceId, identity.deviceName, now) { copy(text = "", deleted = true) })
            }
            next = next.copy(photoId = null, photoParts = 0)
        }
        if (photoJpeg != null) {
            val photoId = UUID.randomUUID().toString().take(8)
            val parts = splitPhoto(Base64.getEncoder().encodeToString(photoJpeg))
            parts.forEachIndexed { i, text ->
                putGroupItem(groupId, Item(photoPartId(photoId, i), text, createdAt = now, version = Version(now, identity.deviceId), editor = identity.deviceName))
            }
            withContext(Dispatchers.IO) { photoFile(photoId).apply { parentFile?.mkdirs() }.writeBytes(photoJpeg) }
            _photos.value++
            next = next.copy(photoId = photoId, photoParts = parts.size)
        }
        val current = _lists.value.getValue(groupId).items[GROUP_LOOK_ITEM]
        val item = current?.edited(identity.deviceId, identity.deviceName, now) { copy(text = next.toJson(), deleted = false) }
            ?: Item(GROUP_LOOK_ITEM, next.toJson(), createdAt = 0, version = Version(now, identity.deviceId), editor = identity.deviceName)
        putGroupItem(groupId, item)
    }

    /** Where a group photo is kept on this phone, once it has arrived. */
    fun photoFile(photoId: String): File = File(File(dir.parentFile, "looks"), "$photoId.jpg")

    /** Writes the group's photo to [photoFile] once all its pieces are here (caller holds [mutex]). */
    private suspend fun assemblePhoto(group: TodoList) {
        val look = group.sharedLook
        val photoId = look.photoId ?: return
        val file = photoFile(photoId)
        if (file.exists()) return
        val base64 = group.photoBase64(look) ?: return
        val bytes = runCatching { Base64.getDecoder().decode(base64) }.getOrNull() ?: return
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(file)
        }
        _photos.value++
    }

    private fun nameItem(name: String): Item {
        val now = System.currentTimeMillis()
        return Item(GROUP_NAME_ITEM, name, createdAt = 0, version = Version(now, identity.deviceId), editor = identity.deviceName)
    }

    /** The group entry standing for [space]. */
    private fun spaceItem(space: TodoList): Item {
        val now = System.currentTimeMillis()
        return Item(
            id = space.id, text = space.name, createdAt = now, version = Version(now, identity.deviceId), editor = identity.deviceName,
            spaceId = space.id, spaceSecret = space.secret, spaceKind = space.kind,
        )
    }

    /** Saves a group item made here and sends it to the group (caller holds [mutex]). */
    private suspend fun putGroupItem(groupId: String, item: Item) {
        val group = _lists.value[groupId] ?: return
        save(group.copy(items = group.items + (item.id to item)))
        _localEdits.tryEmit(LocalEdit(groupId, item, null))
    }

    /**
     * Brings this phone's spaces in line with the group: joins spaces others added, renames, and removes
     * spaces others deleted (caller holds [mutex]).
     */
    private suspend fun followGroup(groupId: String) {
        val group = _lists.value[groupId] ?: return
        group.syncedGroupName?.let { if (it != group.name) save(group.copy(name = it)) }
        assemblePhoto(group)
        val plan = planGroup(group, _lists.value)
        plan.join.forEach { save(TodoList(it.listId, it.name, it.secret, kind = it.kind, groupId = groupId)) }
        plan.update.forEach { save(it) }
        plan.remove.forEach { id ->
            _lists.value -= id
            fileMutex.withLock { withContext(Dispatchers.IO) { file(id).delete() } }
        }
    }

    /** Adds a shared list from an invite; returns the existing one if already joined. */
    suspend fun joinList(invite: Invite): TodoList = mutex.withLock {
        _lists.value[invite.listId] ?: TodoList(invite.listId, invite.name, invite.secret, kind = invite.kind).also { save(it) }
    }

    /** Removes the list from this device only; peers keep their copies. Leaving a group removes its spaces too. */
    suspend fun removeList(listId: String) = mutex.withLock {
        val inGroup = _lists.value.values.filter { it.groupId == listId }.map { it.id }
        for (id in inGroup + listId) {
            _lists.value -= id
            fileMutex.withLock { withContext(Dispatchers.IO) { file(id).delete() } }
        }
    }

    /** Adds an item (or, with [heading], a section heading) at the bottom of the list. */
    suspend fun addItem(listId: String, text: String, heading: Boolean = false) {
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
                heading = heading,
            )
        }
    }

    /** Changes an item's (or heading's) text. Does nothing if it's the same. */
    suspend fun editText(listId: String, itemId: String, text: String) {
        val trimmed = text.trim().ifEmpty { return }
        putLocal(listId) { list ->
            val item = list.items[itemId]?.takeIf { it.text != trimmed } ?: return@putLocal null
            item.edited(identity.deviceId, identity.deviceName, System.currentTimeMillis()) { copy(text = trimmed) }
        }
    }

    /** Moves an item to the end of [headingId]'s section, or above all headings with null. */
    suspend fun moveToSection(listId: String, itemId: String, headingId: String?) {
        val list = _lists.value[listId] ?: return
        moveItem(listId, itemId, indexForSection(list.visibleItems, itemId, headingId))
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
            if (merged.kind == SpaceKind.GROUP && result.accepted.isNotEmpty()) followGroup(listId)
            result.conflicts.forEach { _conflicts.tryEmit(it) }
            RemoteResult(result.accepted.map { list.items[it.id] to it }, newAudit)
        }

    /** Sets this phone's theme for the list (null: follow the app). Not synced. */
    suspend fun setTheme(listId: String, themeId: String?): Unit = mutex.withLock {
        val list = _lists.value[listId] ?: return
        save(list.copy(themeId = themeId))
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
