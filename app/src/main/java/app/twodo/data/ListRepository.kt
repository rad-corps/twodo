package app.twodo.data

import android.util.Log
import app.twodo.model.Conflict
import app.twodo.model.Invite
import app.twodo.model.Item
import app.twodo.model.ListKeys
import app.twodo.model.TodoList
import app.twodo.model.Version
import app.twodo.model.edited
import app.twodo.model.merge
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

/** A local edit that needs to be sent to peers. */
data class LocalEdit(val listId: String, val item: Item)

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
        val list = TodoList(UUID.randomUUID().toString(), name.trim().ifEmpty { "My list" }, ListKeys.newSecret())
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

    suspend fun addItem(listId: String, text: String) {
        val trimmed = text.trim().ifEmpty { return }
        val now = System.currentTimeMillis()
        val item = Item(
            id = UUID.randomUUID().toString(),
            text = trimmed,
            createdAt = now,
            version = Version(now, identity.deviceId),
            editor = identity.deviceName,
        )
        putLocal(listId, item)
    }

    suspend fun setChecked(listId: String, itemId: String, checked: Boolean) =
        editItem(listId, itemId) { copy(checked = checked) }

    suspend fun deleteItem(listId: String, itemId: String) =
        editItem(listId, itemId) { copy(deleted = true) }

    /** Applies items from a peer. Returns those that changed local state, so they can be forwarded. */
    suspend fun applyRemote(listId: String, items: List<Item>): List<Item> = mutex.withLock {
        val list = _lists.value[listId] ?: return emptyList()
        val result = list.merge(items)
        if (result.list != list) save(result.list)
        result.conflicts.forEach { _conflicts.tryEmit(it) }
        result.accepted
    }

    private suspend fun editItem(listId: String, itemId: String, change: Item.() -> Item) = putLocal(listId) { list ->
        list.items[itemId]?.edited(identity.deviceId, identity.deviceName, System.currentTimeMillis(), change)
    }

    private suspend fun putLocal(listId: String, item: Item): Unit = putLocal(listId) { item }

    private suspend fun putLocal(listId: String, makeItem: (TodoList) -> Item?): Unit = mutex.withLock {
        val list = _lists.value[listId] ?: return
        val item = makeItem(list) ?: return
        save(list.copy(items = list.items + (item.id to item)))
        _localEdits.tryEmit(LocalEdit(listId, item))
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
