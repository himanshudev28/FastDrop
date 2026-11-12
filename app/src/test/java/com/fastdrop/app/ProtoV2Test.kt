package com.fastdrop.app

import com.fastdrop.app.ProtoV2.Ranges
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reference vectors, computed independently with python3 (hashlib / hmac / zlib). Other
 * implementations (Mac app, tools/fastdrop.py) must produce the same values.
 *
 * Inputs:
 *   id    = "123e4567-e89b-12d3-a456-426614174000"
 *   dev   = "dev-abc"
 *   ts    = 1731400000000
 *   files = [("a.txt", 5), ("dir/b.bin", 9000000)]
 *   text  = "hello"
 *   key   = bytes 0x00 .. 0x1f (32 bytes)
 *   mtime = 1731300000000
 *
 * Outputs (python3):
 *   code                          = "8249"   (int.from_bytes(sha256(id)[:4], "big") % 10000, zero-padded)
 *   filesDigest (files only)      = 1fb15c941126f6d16a33ee4f26296fa6338624bd8c78148b768722f203ff0184
 *   filesDigest (files + text)    = 0eb577122832125a5218ed7d05f8a968cf2bff8d7a37da1986c62aa90bd4d837
 *   canonical (files only)        = "fastdrop-auth-v1\n<id>\ndev-abc\n1731400000000\n<filesDigest files only>"
 *   auth (files only)             = 1a8208d2d36cb0a7bba5a7d93980341d48b292e3cc4d81f639fe848ec3fc2817
 *   auth (files + text)           = c1bad27d493dbc2b2b181256033d1b2a9ec2aa272d8cea467a4403f6b4586047
 *   resumeKey(dev, "dir/b.bin", 9000000, mtime) = 7a3cb9198e3e966d93e35370eba894f99649cbc6d4cd2135513a61e734331c92
 *   resumeKey(dev, "dir/b.bin", 9000000, none)  = 5d7b5f5b7cbddca6cfa3bdf974764861210c4d19e9f376dc7436615f30f91459
 *   crc32("123456789")            = cbf43926
 *   crc32("hello")               = 3610a686
 */
class ProtoV2Test {
    private val id = "123e4567-e89b-12d3-a456-426614174000"
    private val dev = "dev-abc"
    private val ts = 1731400000000L
    private val files = listOf("a.txt" to 5L, "dir/b.bin" to 9000000L)
    private val key = ByteArray(32) { it.toByte() }
    private val digestFiles = "1fb15c941126f6d16a33ee4f26296fa6338624bd8c78148b768722f203ff0184"
    private val digestText = "0eb577122832125a5218ed7d05f8a968cf2bff8d7a37da1986c62aa90bd4d837"
    private val authFiles = "1a8208d2d36cb0a7bba5a7d93980341d48b292e3cc4d81f639fe848ec3fc2817"
    private val authText = "c1bad27d493dbc2b2b181256033d1b2a9ec2aa272d8cea467a4403f6b4586047"

    @Test fun confirmationCode() {
        assertEquals("8249", ProtoV2.confirmationCode(id))
        assertEquals(4, ProtoV2.confirmationCode("anything").length)
    }

    @Test fun filesDigest() {
        assertEquals(digestFiles, ProtoV2.filesDigest(files, null))
        assertEquals(digestText, ProtoV2.filesDigest(files, "hello"))
    }

    @Test fun canonicalAndAuth() {
        assertEquals("fastdrop-auth-v1\n$id\n$dev\n$ts\n$digestFiles", ProtoV2.canonical(id, dev, ts, digestFiles))
        assertEquals(authFiles, ProtoV2.auth(key, id, dev, ts, files, null))
        assertEquals(authText, ProtoV2.auth(key, id, dev, ts, files, "hello"))
    }

    @Test fun verifyAuth() {
        assertTrue(ProtoV2.verifyAuth(key, id, dev, ts, files, null, authFiles))
        assertTrue(ProtoV2.verifyAuth(key, id, dev, ts, files, null, authFiles.uppercase()))
        assertFalse(ProtoV2.verifyAuth(key, id, dev, ts, files, "hello", authFiles))
        assertFalse(ProtoV2.verifyAuth(key, id, dev, ts + 1, files, null, authFiles))
        assertFalse(ProtoV2.verifyAuth(key, id, dev, ts, files, null, "zz"))
    }

    @Test fun timestampWindow() {
        assertTrue(ProtoV2.tsOk(ts, ts))
        assertTrue(ProtoV2.tsOk(ts + 24 * 3600 * 1000L, ts))
        assertFalse(ProtoV2.tsOk(ts + 24 * 3600 * 1000L + 1, ts))
        assertFalse(ProtoV2.tsOk(ts - 24 * 3600 * 1000L - 1, ts))
    }

    @Test fun resumeKey() {
        assertEquals("7a3cb9198e3e966d93e35370eba894f99649cbc6d4cd2135513a61e734331c92",
            ProtoV2.resumeKey(dev, "dir/b.bin", 9000000, 1731300000000L))
        val noMtime = "5d7b5f5b7cbddca6cfa3bdf974764861210c4d19e9f376dc7436615f30f91459"
        assertEquals(noMtime, ProtoV2.resumeKey(dev, "dir/b.bin", 9000000, null))
        assertEquals(noMtime, ProtoV2.resumeKey(dev, "dir/b.bin", 9000000, 0L))
    }

    @Test fun crc32() {
        assertEquals(0xCBF43926L, ProtoV2.crc32("123456789".toByteArray()))
        assertEquals(0x3610a686L, ProtoV2.crc32("hello".toByteArray()))
        assertEquals(0x3610a686L, ProtoV2.crc32("xhellox".toByteArray(), 1, 5))
    }

    private fun r(vararg p: Int): List<IntArray> = p.toList().chunked(2).map { intArrayOf(it[0], it[1]) }
    private fun same(a: List<IntArray>, b: List<IntArray>) {
        assertEquals(a.size, b.size)
        a.indices.forEach { assertArrayEquals(a[it], b[it]) }
    }

    @Test fun rangesMerge() {
        same(r(0, 12, 15, 20), Ranges.merge(r(15, 20, 0, 5, 3, 12)))
        same(r(0, 20), Ranges.merge(r(0, 10, 10, 20)))            // adjacent ranges join
        same(r(2, 3), Ranges.merge(r(5, 5, 2, 3, 9, 4)))          // empty / inverted dropped
        same(emptyList(), Ranges.merge(emptyList()))
    }

    @Test fun rangesEncodeDecode() {
        assertEquals("[[0,12],[15,20]]", Ranges.encode(r(0, 12, 15, 20)))
        assertEquals("[]", Ranges.encode(emptyList()))
        same(r(0, 12, 15, 20), Ranges.decode("[[0,12],[15,20]]"))
        same(r(0, 20), Ranges.decode("[[0, 12], [10, 20]]"))
        same(emptyList(), Ranges.decode("garbage"))
        same(emptyList(), Ranges.decode("[]"))
    }

    @Test fun rangesMath() {
        assertTrue(Ranges.contains(r(0, 12, 15, 20), 11))
        assertFalse(Ranges.contains(r(0, 12, 15, 20), 12))
        assertEquals(17, Ranges.count(r(0, 12, 15, 20)))
        // 9,000,000 bytes = 2 chunks: 8 MiB + 611,392 bytes
        assertEquals(2, ProtoV2.chunkCount(9000000))
        assertEquals(8388608L, ProtoV2.chunkLen(0, 9000000))
        assertEquals(611392L, ProtoV2.chunkLen(1, 9000000))
        assertEquals(9000000L, Ranges.bytes(r(0, 2), 9000000))
        assertEquals(611392L, Ranges.bytes(r(1, 5), 9000000))     // beyond the file is ignored
        assertEquals(8388608L, Ranges.maxEnd(r(0, 1), 9000000))
        assertEquals(9000000L, Ranges.maxEnd(r(0, 9), 9000000))
        same(r(0, 2), Ranges.clamp(r(0, 9), 2))
    }

    @Test fun chunkSet() {
        val s = ProtoV2.ChunkSet()
        assertTrue(s.add(3)); assertFalse(s.add(3))
        assertTrue(s.add(4)); assertTrue(s.add(0))
        same(r(0, 1, 3, 5), s.snapshot())
        assertEquals(3, s.count())
    }

    @Test fun hexAndUrl() {
        assertEquals("000102ff", ProtoV2.hex(byteArrayOf(0, 1, 2, -1)))
        assertArrayEquals(byteArrayOf(0, 1, 2, -1), ProtoV2.unhex("000102FF"))
        assertEquals(null, ProtoV2.unhex("0g"))
        assertTrue(ProtoV2.isHttpUrl("https://example.com/a?b=1"))
        assertTrue(ProtoV2.isHttpUrl(" http://example.com \n"))
        assertFalse(ProtoV2.isHttpUrl("see https://example.com"))
        assertFalse(ProtoV2.isHttpUrl("ftp://example.com"))
        assertFalse(ProtoV2.isHttpUrl("https://"))
    }
}
