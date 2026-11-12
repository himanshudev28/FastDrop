package com.fastdrop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HardeningTest {
    private val day = 24L * 3600 * 1000

    // ---- replay cache

    @Test fun replayRejectsSecondUse() {
        val c = ReplayCache()
        assertTrue(c.add("a", 1000))
        assertFalse(c.add("a", 2000))
        assertTrue(c.add("b", 2000))
    }

    @Test fun replaySurvivesRestartViaEncodeDecode() {
        val c = ReplayCache()
        c.add("a", 1000); c.add("b", 2000)
        val r = ReplayCache.decode(c.encode(), 3000)
        assertTrue(r.contains("a")); assertTrue(r.contains("b"))
        assertFalse(r.add("a", 3000))
    }

    @Test fun replayPrunesBeyond24h() {
        val c = ReplayCache()
        c.add("old", 1000); c.add("new", 1000 + 20 * 3600 * 1000L)
        val now = 1000 + day + 1
        val r = ReplayCache.decode(c.encode(), now)
        assertFalse(r.contains("old"))
        assertTrue(r.contains("new"))
        assertEquals(1, r.size())
    }

    @Test fun replayCapDropsOldest() {
        val c = ReplayCache(max = 5)
        for (i in 1..8) c.add("id$i", i * 1000L)
        assertEquals(5, c.size())
        assertFalse(c.contains("id1")); assertFalse(c.contains("id3"))
        assertTrue(c.contains("id8")); assertTrue(c.contains("id4"))
        val big = ReplayCache.decode(ReplayCache(max = 5000).also { for (i in 1..2500) it.add("x$i", 1000L + i) }.encode(), 5000)
        assertEquals(ReplayCache.MAX, big.size())
    }

    @Test fun replayDecodeToleratesGarbage() {
        assertEquals(0, ReplayCache.decode("not json", 1).size())
        assertEquals(0, ReplayCache.decode(null, 1).size())
        assertEquals(0, ReplayCache.decode("[{\"id\":\"\",\"t\":5},{\"x\":1}]", 10).size())
    }

    // ---- history text cap

    private fun entry(text: String?) = HistoryEntry("1", false, "p", null, emptyList(), 0, 0, 0.0, 0, 1L, HistoryCodec.DONE, "", text)

    @Test fun shortTextUntouched() {
        val (t, cut) = HistoryCodec.capText("hello")
        assertEquals("hello", t); assertFalse(cut)
    }

    @Test fun longTextCappedWithEllipsisAndFlag() {
        val (t, cut) = HistoryCodec.capText("a".repeat(10_000))
        assertTrue(cut)
        assertEquals(HistoryCodec.MAX_TEXT_CHARS + 1, t.length)
        assertTrue(t.endsWith("…"))
    }

    @Test fun capDoesNotSplitSurrogatePair() {
        val s = "a".repeat(HistoryCodec.MAX_TEXT_CHARS - 1) + "😀" + "tail"
        val (t, cut) = HistoryCodec.capText(s)
        assertTrue(cut)
        assertFalse(Character.isHighSurrogate(t[t.length - 2]))
    }

    @Test fun cappedTextRoundTripsAndOldFilesGetCapped() {
        val e = HistoryCodec.withCappedText(entry("b".repeat(9000)))
        assertTrue(e.textTruncated)
        val back = HistoryCodec.decode(HistoryCodec.encode(listOf(e))).single()
        assertTrue(back.textTruncated); assertEquals(e.text, back.text)
        // A file written by an older build (no flag, long text) is capped on load.
        val legacy = "[{\"id\":\"1\",\"text\":\"" + "c".repeat(9000) + "\",\"at\":1}]"
        val l = HistoryCodec.decode(legacy).single()
        assertTrue(l.textTruncated); assertEquals(HistoryCodec.MAX_TEXT_CHARS + 1, l.text!!.length)
        assertNull(HistoryCodec.withCappedText(entry(null)).text)
    }

    // ---- text length by code points, mtime resume rule

    @Test fun textLimitCountsCodePoints() {
        val emoji = "😀"                       // 1 code point, 2 UTF-16 units
        assertTrue(ProtoV2.textLenOk(emoji.repeat(ProtoV2.MAX_TEXT)))
        assertFalse(ProtoV2.textLenOk(emoji.repeat(ProtoV2.MAX_TEXT + 1)))
        assertTrue(ProtoV2.textLenOk("a".repeat(ProtoV2.MAX_TEXT)))
        assertFalse(ProtoV2.textLenOk(""))
    }

    @Test fun resumeOnlyWithPositiveMtime() {
        assertNull(ProtoV2.resumeKeyOrNull("d", "n", 10, null))
        assertNull(ProtoV2.resumeKeyOrNull("d", "n", 10, 0))
        assertNull(ProtoV2.resumeKeyOrNull("d", "n", 10, -5))
        assertNotNull(ProtoV2.resumeKeyOrNull("d", "n", 10, 1731400000000))
        assertEquals(ProtoV2.resumeKey("d", "n", 10, 1731400000000), ProtoV2.resumeKeyOrNull("d", "n", 10, 1731400000000))
    }
}
