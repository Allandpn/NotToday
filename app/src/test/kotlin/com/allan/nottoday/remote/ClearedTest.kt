package com.allan.nottoday.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class ClearedTest {
    private fun b(domain: String, last: Long) = BlockedItem(domain, true, "", false, 1, last)
    private fun r(id: Long, status: String) = UnblockRequest(id, status, emptyList())

    @Test
    fun clearedBlockedReappearOnlyWhenBlockedAgain() {
        val before = listOf(b("a.com", 100), b("b.com", 120))
        val mark = Cleared.newestSeen(before, 0)
        assertEquals(120L, mark)
        assertEquals(emptyList<BlockedItem>(), Cleared.blocked(before, mark))
        val after = listOf(b("a.com", 150), b("b.com", 120), b("c.com", 130))
        assertEquals(listOf("a.com", "c.com"), Cleared.blocked(after, mark).map { it.domain })
    }

    @Test
    fun answeredRequestsAreHiddenButOpenOnesAlwaysShow() {
        val all = listOf(r(9, "open"), r(8, "done"), r(7, "done"))
        val mark = Cleared.newestAnswered(all, 0)
        assertEquals(8L, mark)
        assertEquals(listOf(9L), Cleared.requests(all, mark).map { it.id })
        assertEquals(listOf(10L, 9L), Cleared.requests(listOf(r(10, "done"), r(9, "open"), r(8, "done")), mark).map { it.id })
    }

    @Test
    fun markNeverGoesBack() {
        assertEquals(500L, Cleared.newestSeen(listOf(b("a.com", 100)), 500))
        assertEquals(50L, Cleared.newestAnswered(listOf(r(3, "done")), 50))
    }
}
