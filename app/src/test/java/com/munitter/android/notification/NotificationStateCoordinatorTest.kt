package com.munitter.android.notification

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class NotificationStateCoordinatorTest {
    private class Fixture(initial: NotificationState = NotificationState()) {
        var state = initial
        val nativeIds = initial.sourcesById.keys.toMutableSet()
        var badge = 0
        var alerts = 0
        var avatarUpdates = 0
        val coordinator = NotificationStateCoordinator({ state }, { state = it })
        fun publish(mutation: NotificationMutation) {
            if (mutation.clearAll) nativeIds.clear()
            nativeIds.removeAll(mutation.canceledIds)
            nativeIds.addAll(mutation.shown.map { it.id })
            badge = mutation.unreadCount
            alerts += mutation.alertIds.size
        }
        fun push(id: String, count: Int? = 1) = coordinator.applyPush(row(id), count, ::publish)
        fun refresh(vararg items: MunitterNotification, count: Int? = 1) =
            coordinator.applyRefresh(coordinator.beginSync(), items.toList(), count, ::publish)
    }

    @Test
    fun `FCM then HTTP for the same event renders one native child and one unread`() {
        val fixture = Fixture()
        fixture.push("user:42")
        fixture.refresh(row("user-42"))

        assertEquals(setOf("user-42"), fixture.nativeIds)
        assertEquals(1, fixture.badge)
        assertEquals(1, fixture.alerts)
    }

    @Test
    fun `HTTP then delayed FCM uses the same identity without a second alert`() {
        val fixture = Fixture()
        fixture.refresh(row("group-17"))
        fixture.push("group:17")

        assertEquals(setOf("group-17"), fixture.nativeIds)
        assertEquals(1, fixture.alerts)
    }

    @Test
    fun `upgrade removes legacy zero and colon aliases when the canonical row is observed`() {
        val fixture = Fixture(NotificationState(mapOf(
            "user:0" to setOf("user-0"),
            "user:1" to setOf("user-1"),
            "user-1" to setOf("user-1"),
        )))
        val update = fixture.refresh(row("user-1"))!!

        assertEquals(setOf("user:0", "user:1"), update.canceledIds)
        assertEquals(setOf("user-1"), fixture.nativeIds)
        assertEquals(1, fixture.badge)
    }

    @Test
    fun `valid push immediately removes impossible legacy zero cards before polling`() {
        val fixture = Fixture(NotificationState(mapOf("user:0" to setOf("user-0"))))
        fixture.push("user:1", 1)

        assertEquals(setOf("user-1"), fixture.nativeIds)
        assertEquals(1, fixture.badge)
    }

    @Test
    fun `synthetic concrete-source aggregate coalesces cards and retains delayed push mapping`() {
        val fixture = Fixture()
        fixture.push("user:1", 2)
        fixture.push("user:2", 2)
        fixture.refresh(row("muni-article-day", sources = setOf("user-1", "user-2")), count = 2)
        fixture.push("user:1", 2)

        assertEquals(setOf("muni-article-day"), fixture.nativeIds)
        assertEquals(setOf("user-1", "user-2"), fixture.state.sourcesById["muni-article-day"])
        assertEquals(2, fixture.badge)
        assertEquals(2, fixture.alerts)
    }

    @Test
    fun `read row cancels both source aliases while unrelated DM keeps server count`() {
        val fixture = Fixture()
        fixture.push("user:1", 2)
        fixture.push("dm:19:7", 2)
        fixture.refresh(row("user-1", read = true), count = 1)

        assertEquals(setOf("dm:19:7"), fixture.nativeIds)
        assertEquals(1, fixture.badge)
    }

    @Test
    fun `synthetic concrete-source read aggregate removes its card by source identity`() {
        val fixture = Fixture()
        fixture.push("user:1")
        fixture.refresh(row("muni-old", sources = setOf("user-1")))
        fixture.refresh(row("muni-new", read = true, sources = setOf("user-1")), count = 1)

        assertTrue(fixture.nativeIds.isEmpty())
        assertEquals(1, fixture.badge)
    }

    @Test
    fun `current server snapshot-only group updates summary without adding an uncorrelated child`() {
        val fixture = Fixture()
        fixture.push("user:12", 4)
        fixture.push("dm:19:7", 4)
        val page = NotificationPageParser.parse(
            javaClass.getResourceAsStream("/native-modern-grouped-notification.json")!!
                .bufferedReader().use { it.readText() },
        )
        fixture.coordinator.applyRefresh(fixture.coordinator.beginSync(), page.items, page.unreadCount, fixture::publish)

        assertEquals(setOf("user-12", "dm:19:7"), fixture.nativeIds)
        assertEquals(4, fixture.badge)
        assertEquals(2, fixture.alerts)
    }

    @Test
    fun `snapshot-only read with unrelated unread cannot infer source or DM read state`() {
        val fixture = Fixture()
        fixture.push("user:12", 3)
        fixture.push("dm:19:7", 3)
        val snapshot = "grouped-muni-17-20261122-12-639046000000000000"
        fixture.refresh(row(snapshot, read = true), count = 1)

        assertEquals(setOf("user-12", "dm:19:7"), fixture.nativeIds)
        assertEquals(1, fixture.badge)
        fixture.refresh(count = 0)
        assertTrue(fixture.nativeIds.isEmpty())
    }

    @Test
    fun `legacy unmapped snapshot cards are retired while summary and DM remain`() {
        val snapshot = "grouped-muni-17-20261122-12-639046000000000000"
        val fixture = Fixture(NotificationState(mapOf(
            snapshot to setOf(snapshot),
            "dm:19:7" to setOf("dm:19:7"),
        )))
        fixture.refresh(row(snapshot), count = 3)

        assertEquals(setOf("dm:19:7"), fixture.nativeIds)
        assertEquals(3, fixture.badge)
    }

    @Test
    fun `valid push retires obsolete snapshot-only presentation before its first render`() {
        val snapshot = "grouped-muni-17-20261122-12-639046000000000000"
        val fixture = Fixture(NotificationState(mapOf(snapshot to setOf(snapshot))))
        fixture.push("user:12", 1)

        assertEquals(setOf("user-12"), fixture.nativeIds)
        assertEquals(1, fixture.badge)
    }

    @Test
    fun `filtered empty page never treats missing DM or ordinary IDs as read`() {
        val fixture = Fixture()
        fixture.push("user:1", 4)
        fixture.push("dm:19:7", 4)
        fixture.refresh(count = 1)

        assertEquals(setOf("user-1", "dm:19:7"), fixture.nativeIds)
        assertEquals(1, fixture.badge)
    }

    @Test
    fun `authoritative HTTP zero clears every local card even omitted DMs`() {
        val fixture = Fixture()
        fixture.push("dm:19:7")
        fixture.refresh(count = 0)

        assertTrue(fixture.nativeIds.isEmpty())
        assertTrue(fixture.state.sourcesById.isEmpty())
        assertEquals(0, fixture.badge)
    }

    @Test
    fun `missing legacy count does not replace the last server total with local cardinality`() {
        val fixture = Fixture()
        fixture.refresh(count = 5)
        fixture.push("user:1", null)
        fixture.refresh(row("user-1"), count = null)

        assertEquals(5, fixture.badge)
    }

    @Test
    fun `zero or unpersisted push only requests refresh and cannot mutate current state`() {
        val fixture = Fixture()
        fixture.push("dm:newer", 1)
        assertNull(fixture.push("dm:older", 0))
        assertNull(fixture.push("user:0", 1))

        assertEquals(setOf("dm:newer"), fixture.nativeIds)
        assertEquals(1, fixture.badge)
    }

    @Test
    fun `older HTTP response cannot resurrect rows after a newer read reconciliation`() {
        val fixture = Fixture()
        fixture.push("user:1")
        val oldRequest = fixture.coordinator.beginSync()
        fixture.refresh(count = 0)
        val oldResponse = fixture.coordinator.applyRefresh(oldRequest, listOf(row("user-1")), 1, fixture::publish)

        assertNull(oldResponse)
        assertTrue(fixture.nativeIds.isEmpty())
        assertEquals(0, fixture.badge)
    }

    @Test
    fun `FCM arriving while HTTP is in flight invalidates its stale clear and unauthorized response`() {
        val fixture = Fixture()
        val oldRequest = fixture.coordinator.beginSync()
        fixture.push("user:2")

        assertNull(fixture.coordinator.applyRefresh(oldRequest, emptyList(), 0, fixture::publish))
        assertFalse(fixture.coordinator.clear(oldRequest, fixture::publish))
        assertEquals(setOf("user-2"), fixture.nativeIds)
    }

    @Test
    fun `late avatar cannot recreate notification or summary after it was read`() {
        val fixture = Fixture()
        val push = fixture.push("user:1")!!
        fixture.refresh(count = 0)
        val updated = fixture.coordinator.updateAvatar(push, "user-1") { fixture.avatarUpdates++ }

        assertFalse(updated)
        assertEquals(0, fixture.avatarUpdates)
        assertTrue(fixture.nativeIds.isEmpty())
    }

    @Test
    fun `late nonzero push for a source already read by HTTP cannot resurrect it`() {
        val fixture = Fixture()
        fixture.push("user:1")
        fixture.refresh(count = 0)

        assertNull(fixture.push("user:1", 1))
        assertTrue(fixture.nativeIds.isEmpty())
        assertEquals(0, fixture.badge)
        fixture.refresh(row("user-1"), count = 1)
        assertEquals(setOf("user-1"), fixture.nativeIds)
    }

    @Test
    fun `late avatar cannot restore old badge after another push changes the total`() {
        val fixture = Fixture()
        val first = fixture.push("user:1")!!
        fixture.push("user:2", 2)

        assertFalse(fixture.coordinator.updateAvatar(first, "user-1") { fixture.badge = it })
        assertEquals(2, fixture.badge)
    }

    @Test
    fun `simultaneous FCM transitions never lose an ID or render outside the transaction`() {
        val fixture = Fixture()
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val jobs = listOf("user:1", "user:2").map { id ->
                executor.submit { start.await(); fixture.push(id, 2) }
            }
            start.countDown()
            jobs.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(setOf("user-1", "user-2"), fixture.nativeIds)
            assertEquals(fixture.state.sourcesById.keys, fixture.nativeIds)
            assertEquals(2, fixture.badge)
        } finally {
            executor.shutdownNow()
        }
    }

    private companion object {
        fun row(id: String, read: Boolean = false, sources: Set<String> = setOf(NotificationIdentity.canonical(id))) =
            MunitterNotification(id, "title", "body", "/notifications", read, sourceNotificationIds = sources)
    }
}
