package com.fastdrop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegrationsModelTest {
    private fun entry(id: String, at: Long, out: Boolean = false) = HistoryEntry(
        id, out, "Pixel", "dev-1",
        listOf(HFile("a.jpg", 10, "content://x/1"), HFile("app.apk", 99, "file:///d/app.apk", "App", "com.x", "1.2")),
        2, 109, 1234.5, 800, at, HistoryCodec.DONE, "Received")

    @Test fun historyRoundTrip() {
        val l = listOf(entry("1", 5), entry("2", 9, true).copy(text = "hello", files = emptyList(), peerId = null, state = HistoryCodec.FAILED))
        val back = HistoryCodec.decode(HistoryCodec.encode(l))
        assertEquals(l, back)
        assertTrue(back[0].files[1].isApp)
        assertNull(back[1].peerId)
    }

    @Test fun decodeGarbageIsEmpty() {
        assertTrue(HistoryCodec.decode("not json").isEmpty())
        assertEquals(1, HistoryCodec.decode("[{\"bad\":1}," + HistoryCodec.encode(listOf(entry("1", 1))).trim('[', ']') + "]").size)
    }

    @Test fun capKeepsNewestAndUnique() {
        val l = (1..600).map { entry("e$it", it.toLong()) } + entry("e600", 1)
        val c = HistoryCodec.capped(l)
        assertEquals(HistoryCodec.MAX_ENTRIES, c.size)
        assertEquals("e600", c[0].id)
        assertEquals(c.size, c.map { it.id }.toSet().size)
    }

    @Test fun namesCappedAt50() {
        val e = entry("1", 1).copy(files = (1..80).map { HFile("f$it", 1) }, count = 80)
        assertEquals(50, HistoryCodec.decode(HistoryCodec.encode(listOf(e)))[0].files.size)
    }

    private fun dev(id: String, t: Long, name: String = id) = RecentDevice(id, name, "phone", "192.168.1.5", 53530, t)

    @Test fun recentRanking() {
        var l = emptyList<RecentDevice>()
        for (i in 1..6) l = RecentRank.touch(l, dev("d$i", i.toLong()))
        assertEquals(listOf("d6", "d5", "d4", "d3"), RecentRank.top(l).map { it.id })
        l = RecentRank.touch(l, dev("d2", 10, "renamed"))
        assertEquals("d2", RecentRank.top(l)[0].id)
        assertEquals("renamed", l[0].name)
        assertEquals(1, l.count { it.id == "d2" })
        for (i in 7..30) l = RecentRank.touch(l, dev("d$i", i.toLong()))
        assertEquals(RecentRank.KEEP, l.size)
        assertEquals(l, RecentRank.decode(RecentRank.encode(l)))
    }

    @Test fun throttle() {
        val t = UpdateThrottle(2000)
        assertTrue(t.tryAcquire(0))
        assertFalse(t.tryAcquire(1500))
        assertTrue(t.tryAcquire(1500, force = true))
        assertFalse(t.tryAcquire(3000))
        assertTrue(t.tryAcquire(3500))
    }

    @Test fun widgetStatus() {
        assertEquals("Ready", WidgetStatus.text(false, true, -1, false))
        assertEquals("Connecting…", WidgetStatus.text(false, true, -1, true))
        assertEquals("Sending 42 %", WidgetStatus.text(true, true, 42, false))
        assertEquals("Receiving 7 %", WidgetStatus.text(true, false, 7, false))
        assertEquals("Sending…", WidgetStatus.text(true, true, -1, false))
    }
}
