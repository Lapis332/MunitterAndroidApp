package com.munitter.android.notification

internal object NotificationIdentity {
    // FCM uses ':'; the authenticated notifications API uses '-'. Aggregated
    // rows additionally expose the underlying IDs in sourceNotificationIds.
    fun canonical(id: String): String = when {
        id.startsWith("user:") -> "user-${id.removePrefix("user:")}"
        id.startsWith("group:") -> "group-${id.removePrefix("group:")}"
        else -> id
    }

    fun isUnpersisted(id: String): Boolean = canonical(id) in setOf("user-0", "group-0")

    fun isSnapshotOnlyAggregate(id: String, sources: Set<String>): Boolean =
        id.startsWith("grouped-muni-") && sources.none { source ->
            val normalized = canonical(source)
            (normalized.startsWith("user-") || normalized.startsWith("group-")) &&
                normalized.substringAfter('-').toLongOrNull()?.let { it > 0 } == true
        }
}

internal data class NotificationState(
    val sourcesById: Map<String, Set<String>> = emptyMap(),
    val unreadCount: Int? = null,
    val readSources: Set<String> = emptySet(),
)

internal data class NotificationSyncTicket(val request: Long, val revision: Long)

internal data class NotificationMutation(
    val state: NotificationState,
    val canceledIds: Set<String>,
    val shown: List<MunitterNotification>,
    val alertIds: Set<String>,
    val revision: Long,
    val clearAll: Boolean = false,
) {
    val unreadCount: Int
        get() = NotificationUnreadCount.resolve(state.unreadCount, state.sourcesById.size)
}

/** One instance per app process. State writes and native rendering share the same
 * monitor, while network/avatar work remains outside it. A response fetched
 * before a newer sync or push cannot overwrite that newer state. */
internal class NotificationStateCoordinator(
    private val read: () -> NotificationState,
    private val write: (NotificationState) -> Unit,
) {
    private var latestRequest = 0L
    private var revision = 0L

    @Synchronized
    fun beginSync(): NotificationSyncTicket = NotificationSyncTicket(++latestRequest, revision)

    @Synchronized
    fun invalidateForRefreshHint() { latestRequest += 1 }

    @Synchronized
    fun ordinarySources(): Set<String> = read().sourcesById.values.flatten()
        .filter { it.startsWith("user-") || it.startsWith("group-") }.toSet()

    @Synchronized
    fun applyRefresh(
        ticket: NotificationSyncTicket,
        items: List<MunitterNotification>,
        authoritativeCount: Int?,
        publish: (NotificationMutation) -> Unit,
    ): NotificationMutation? {
        if (!isCurrent(ticket)) return null
        val before = read()
        if (NotificationUnreadCount.isAuthoritativelyEmpty(authoritativeCount)) {
            return commit(
                NotificationState(unreadCount = 0, readSources = recentReads(before.readSources + before.sourcesById.values.flatten())),
                emptySet(), emptyList(), emptySet(), true, publish,
            )
        }
        val active = before.sourcesById.toMutableMap()
        val readSources = before.readSources.toMutableSet()
        val canceled = mutableSetOf<String>()
        val shown = mutableListOf<MunitterNotification>()
        val alerts = mutableSetOf<String>()
        // These legacy IDs were emitted before a database identity existed;
        // they cannot identify a real server row and must not survive refresh.
        active.keys.filter(NotificationIdentity::isUnpersisted).forEach {
            active.remove(it)
            canceled += it
        }
        // Older/native-migrated snapshot-only cards have no concrete source
        // mapping. Retire that presentation, keeping the authoritative summary
        // and all known source/DM cards. A snapshot token is not an event ID.
        active.filter { (id, sources) -> NotificationIdentity.isSnapshotOnlyAggregate(id, sources) }
            .keys.forEach {
                active.remove(it)
                canceled += it
            }
        for (item in items) {
            val id = NotificationIdentity.canonical(item.id)
            val sources = item.sourceNotificationIds.map(NotificationIdentity::canonical).toSet()
            val matchingRecords = active.filter { (activeId, activeSources) ->
                NotificationIdentity.canonical(activeId) == id || activeSources.any { it in sources }
            }
            val matches = matchingRecords.keys
            val wasActive = matches.isNotEmpty() || id in before.sourcesById
            if (!item.isRead && NotificationIdentity.isSnapshotOnlyAggregate(id, sources)) {
                // The current Web API emits a grouped read-snapshot token as
                // its sole source. It cannot correlate an FCM user:N event.
                // Summary-only rendering avoids adding an uncorrelated child.
                continue
            }
            for (match in matches) {
                active.remove(match)
                if (item.isRead || match != id) canceled += match
            }
            if (!item.isRead) {
                readSources.removeAll(sources)
                active[id] = sources
                shown += item.copy(id = id, sourceNotificationIds = sources)
                if (!wasActive) alerts += id
            } else {
                readSources += sources + matchingRecords.values.flatten()
            }
        }
        // The API filters ordinary rows, aggregates likes, caps history, and
        // omits DMs. Absence alone is not read evidence. Explicit source IDs and
        // the authoritative zero are the safe cancellation signals.
        return commit(
            NotificationState(active, authoritativeCount ?: before.unreadCount, recentReads(readSources)),
            canceled, shown, alerts, false, publish,
        )
    }

    @Synchronized
    fun applyPush(
        notification: MunitterNotification,
        authoritativeCount: Int?,
        publish: (NotificationMutation) -> Unit,
    ): NotificationMutation? {
        if (NotificationIdentity.isUnpersisted(notification.id) || authoritativeCount == 0) return null
        val before = read()
        val source = NotificationIdentity.canonical(notification.id)
        // HTTP read evidence is stronger than an out-of-order push for that
        // same source. A later HTTP unread row can explicitly restore it.
        if (source in before.readSources) return null
        val matches = before.sourcesById.filter { (id, sources) ->
            NotificationIdentity.canonical(id) == source || source in sources
        }
        // A delayed FCM for an already aggregated source updates that same
        // native card, rather than adding a second card beside the aggregate.
        val id = matches.keys.firstOrNull { it == NotificationIdentity.canonical(it) }
            ?: source
        val active = before.sourcesById.toMutableMap()
        val canceled = matches.keys.filter { it != id }.toSet() +
            before.sourcesById.keys.filter(NotificationIdentity::isUnpersisted) +
            before.sourcesById.filter { (activeId, activeSources) ->
                NotificationIdentity.isSnapshotOnlyAggregate(activeId, activeSources)
            }.keys
        canceled.forEach(active::remove)
        val sources = matches.values.flatten().toSet() + source
        active[id] = sources
        return commit(
            NotificationState(active, authoritativeCount ?: before.unreadCount?.coerceAtLeast(1), before.readSources),
            canceled,
            listOf(notification.copy(id = id, sourceNotificationIds = sources)),
            if (matches.isEmpty()) setOf(id) else emptySet(),
            false,
            publish,
        )
    }

    @Synchronized
    fun applyReadAcknowledgment(
        ticket: NotificationSyncTicket,
        acknowledgedIds: Set<String>,
        authoritativeCount: Int,
        publish: (NotificationMutation) -> Unit,
    ): NotificationMutation? {
        if (!isCurrent(ticket) || authoritativeCount < 0) return null
        val sources = acknowledgedIds.mapNotNull(NotificationTapPolicy::ordinaryId).toSet()
        val before = read()
        if (authoritativeCount == 0) {
            return commit(
                NotificationState(unreadCount = 0,
                    readSources = recentReads(before.readSources + sources + before.sourcesById.values.flatten())),
                emptySet(), emptyList(), emptySet(), true, publish,
            )
        }
        val active = before.sourcesById.toMutableMap()
        val canceled = mutableSetOf<String>()
        for ((id, existingSources) in before.sourcesById) {
            val remaining = existingSources - sources
            if (remaining.isEmpty() && existingSources.any { it in sources }) {
                active.remove(id)
                canceled += id
            } else if (remaining != existingSources) {
                // A grouped card can still represent unrelated unread sources.
                active[id] = remaining
            }
        }
        return commit(
            NotificationState(active, authoritativeCount, recentReads(before.readSources + sources)),
            canceled, emptyList(), emptySet(), false, publish,
        )
    }

    @Synchronized
    fun clear(ticket: NotificationSyncTicket, publish: (NotificationMutation) -> Unit): Boolean {
        if (!isCurrent(ticket)) return false
        commit(NotificationState(unreadCount = 0), emptySet(), emptyList(), emptySet(), true, publish)
        return true
    }

    @Synchronized
    fun updateAvatar(mutation: NotificationMutation, id: String, publish: (Int) -> Unit): Boolean {
        if (mutation.revision != revision || id !in read().sourcesById) return false
        publish(NotificationUnreadCount.resolve(read().unreadCount, read().sourcesById.size))
        return true
    }

    private fun isCurrent(ticket: NotificationSyncTicket): Boolean =
        ticket.request == latestRequest && ticket.revision == revision

    private fun recentReads(sources: Set<String>): Set<String> = sources.toList().takeLast(512).toSet()

    private fun commit(
        state: NotificationState,
        canceled: Set<String>,
        shown: List<MunitterNotification>,
        alerts: Set<String>,
        clearAll: Boolean,
        publish: (NotificationMutation) -> Unit,
    ): NotificationMutation {
        write(state)
        val mutation = NotificationMutation(state, canceled, shown, alerts, ++revision, clearAll)
        publish(mutation)
        return mutation
    }
}
