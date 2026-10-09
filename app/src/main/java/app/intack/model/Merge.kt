package app.intack.model

const val HISTORY_LIMIT = 32

/** Returns a new version of this item made by [deviceId] at [now], applying [change]. */
fun Item.edited(deviceId: String, deviceName: String, now: Long, change: Item.() -> Item): Item {
    // Keep versions of one item strictly increasing even if this device's clock is behind.
    val ts = maxOf(now, version.ts + 1)
    return change().copy(
        version = Version(ts, deviceId),
        editor = deviceName,
        history = (listOf(version) + history).take(HISTORY_LIMIT),
    )
}

/** Returns this item moved to [pos]; doesn't create a content version. */
fun Item.moved(pos: Double, deviceId: String, now: Long): Item =
    copy(pos = pos, posVersion = Version(maxOf(now, (posVersion?.ts ?: 0) + 1), deviceId))

private fun Item.sameContentAs(other: Item) =
    text == other.text && checked == other.checked && deleted == other.deleted && date == other.date && time == other.time

data class MergeResult(
    val list: TodoList,
    /** Remote items that replaced (or added to) local state. */
    val accepted: List<Item>,
    val conflicts: List<Conflict>,
)

/**
 * Merges items received from a peer. Newer descendants replace older versions; concurrent edits are
 * resolved by latest timestamp and reported as conflicts. The peer runs the same rules on our copy,
 * so both sides end up with the same item and both report the conflict.
 */
fun TodoList.merge(remote: Collection<Item>): MergeResult {
    val items = items.toMutableMap()
    val superseded = superseded.toMutableMap()
    val accepted = mutableListOf<Item>()
    val conflicts = mutableListOf<Conflict>()

    for (r in remote) {
        val l = items[r.id]
        if (l == null) {
            items[r.id] = r
            accepted += r
            continue
        }
        val lost = superseded[r.id].orEmpty()
        var merged = when {
            r.version == l.version || r.version in l.history || r.version in lost -> l
            l.version in r.history -> r
            else -> {
                val remoteWins = r.version > l.version
                val winner = if (remoteWins) r else l
                val loser = if (remoteWins) l else r
                superseded[r.id] = lost + loser.version
                // Both sides making the same change isn't worth telling anyone about.
                if (!winner.sameContentAs(loser)) conflicts += Conflict(id, name, winner, loser, localLost = remoteWins)
                winner
            }
        }
        // Position is merged on its own: the latest move wins, silently.
        val latestMove = maxOf(l, r, compareBy(nullsFirst()) { it.posVersion })
        merged = merged.copy(pos = latestMove.pos, posVersion = latestMove.posVersion)
        if (merged != l) {
            items[r.id] = merged
            accepted += merged
        }
    }
    return MergeResult(copy(items = items, superseded = superseded), accepted, conflicts)
}
