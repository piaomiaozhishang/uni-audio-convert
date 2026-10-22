package com.musicconvert.decrypt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

/**
 * 解密器的端到端校验。
 *
 * 思路：用与解密**对称**的算法把一段已知明文加密成目标格式，再走完整的
 * `decrypt()` 流程解回来，要求逐字节相同。
 *
 * 这不能证明"和官方算法一致"（那要靠 unlock-music 的测试向量），但能证明：
 *  - 头部字段偏移解析正确（否则解出来是垃圾）；分块流式处理的 keystream 相位没写错
 *    （加密是一次性做的，解密是 64KB 分块的，两边不一致就会失败）。
 */
class DecryptRoundTripTest {

    private fun randomBytes(size: Int): ByteArray {
        val r = Random(42)
        return ByteArray(size) { r.nextInt(256).toByte() }
    }

    private fun temp(name: String): File =
        File.createTempFile(name, null).also { it.deleteOnExit() }

    // ── QMC 静态密码（旧版 QMCv1）────────────────────────────────

    @Test
    fun `qmc static cipher round trip`() {
        val plain = randomBytes(200_000) // 跨多个 64KB 分块
        val enc = ByteArray(plain.size)
        for (i in plain.indices) {
            var off = i.toLong()
            if (off > 0x7FFF) off %= 0x7FFF
            val o = off.toInt()
            val idx = (o * o + 27) and 0xFF
            enc[i] = (plain[i].toInt() xor (STATIC_BOX[idx].toInt() and 0xFF)).toByte()
        }

        val src = temp("s.qmc").apply { writeBytes(enc) } // 无尾部 → 静态密码
        val dst = temp("d.bin")
        QmcDecryptor.decrypt(src, dst, "s.qmc")
        assertArrayEquals(plain, dst.readBytes())
    }

    @Test
    fun `qmc wrong footer length throws`() {
        // 尾部是 "QTag"，前面 4 字节的 metaLen 远大于上限 → 必须拒绝
        val data = ByteArray(64)
        data[56] = 0x7F
        data[57] = 0xFF.toByte()
        data[58] = 0xFF.toByte()
        data[59] = 0xFF.toByte()
        data[60] = 'Q'.code.toByte()
        data[61] = 'T'.code.toByte()
        data[62] = 'a'.code.toByte()
        data[63] = 'g'.code.toByte()
        val src = temp("bad.qmc").apply { writeBytes(data) }
        val dst = temp("bad.bin")
        val error = runCatching { QmcDecryptor.decrypt(src, dst, "bad.qmc") }.exceptionOrNull()
        assertTrue(error is DecryptException)
    }

    // ── 酷狗 KGM v3 ─────────────────────────────────────────────

    @Test
    fun `kgm v3 round trip`() {
        val plain = randomBytes(150_000)
        val cryptoKey = randomBytes(16)
        val slotKey = byteArrayOf(0x6C, 0x2C, 0x2F, 0x27)

        val file = buildKgmFile(plain, cryptoKey, slotKey)
        val src = temp("t.kgm").apply { writeBytes(file) }
        val dst = temp("t.bin")
        KgmDecryptor.decrypt(src, dst)
        assertArrayEquals(plain, dst.readBytes())
    }

    @Test
    fun `kgm v5 rejected`() {
        val magic = byteArrayOf(
            0x7C, 0xD5.toByte(), 0x32, 0xEB.toByte(), 0x86.toByte(), 0x02, 0x7F, 0x4B,
            0xA8.toByte(), 0xAF.toByte(), 0xA6.toByte(), 0x8E.toByte(), 0x0F, 0xFF.toByte(),
            0x99.toByte(), 0x14
        )
        val header = ByteArray(0x3C)
        System.arraycopy(magic, 0, header, 0, 16)
        writeLe32(header, 0x10, 0x3C)
        writeLe32(header, 0x14, 5) // v5
        val src = temp("v5.kgm").apply { writeBytes(header + ByteArray(100)) }
        val dst = temp("v5.bin")
        val error = runCatching { KgmDecryptor.decrypt(src, dst) }.exceptionOrNull()
        assertTrue(error is DecryptException)
    }

    // ── 酷我 KWM ────────────────────────────────────────────────

    @Test
    fun `kwm round trip`() {
        val plain = randomBytes(120_000)
        val key = randomBytes(8)
        val file = buildKwmFile(plain, key)
        val src = temp("t.kwm").apply { writeBytes(file) }
        val dst = temp("t.bin")
        KwmDecryptor.decrypt(src, dst)
        assertArrayEquals(plain, dst.readBytes())
    }

    // ── QQ 音乐 TM ──────────────────────────────────────────────

    @Test
    fun `tm faked header replaced`() {
        val body = randomBytes(5000)
        val file = "QQMU".toByteArray(Charsets.ISO_8859_1) + body
        val src = temp("t.tm2").apply { writeBytes(file) }
        val dst = temp("t.bin")
        TmDecryptor.decrypt(src, dst)
        val out = dst.readBytes()
        assertEquals(0x66.toByte(), out[4]) // 'f'
        assertEquals(0x74.toByte(), out[5]) // 't'
        assertEquals(0x79.toByte(), out[6]) // 'y'
        assertEquals(0x70.toByte(), out[7]) // 'p'
        assertEquals(file.size.toLong(), out.size.toLong()) // 只替换头，不截断
    }

    @Test
    fun `tm passthrough when no magic`() {
        val body = randomBytes(3000)
        val src = temp("t.tm0").apply { writeBytes(body) }
        val dst = temp("t.bin")
        TmDecryptor.decrypt(src, dst)
        assertArrayEquals(body, dst.readBytes())
    }

    // ── 嗅探 ────────────────────────────────────────────────────

    @Test
    fun `sniff by extension`() {
        assertEquals(EncryptedKind.NCM, MusicDecryptor.sniff("a.ncm", null))
        assertEquals(EncryptedKind.QMC, MusicDecryptor.sniff("a.mflac0.flac", null))
        assertEquals(EncryptedKind.QMC, MusicDecryptor.sniff("a.qmcflac", null))
        assertEquals(EncryptedKind.TM, MusicDecryptor.sniff("a.tm2", null))
        assertEquals(EncryptedKind.KGM, MusicDecryptor.sniff("a.kgm", null))
        assertEquals(EncryptedKind.KGM, MusicDecryptor.sniff("a.vpr", null))
        assertEquals(EncryptedKind.KWM, MusicDecryptor.sniff("a.kwm", null))
        assertEquals(EncryptedKind.KGG, MusicDecryptor.sniff("a.kgg", null))
        assertEquals(EncryptedKind.UNKNOWN, MusicDecryptor.sniff("a.mp3", null))
    }

    // ── 构造夹具 ────────────────────────────────────────────────

    private fun buildKgmFile(plain: ByteArray, cryptoKey: ByteArray, slotKey: ByteArray): ByteArray {
        val magic = byteArrayOf(
            0x7C, 0xD5.toByte(), 0x32, 0xEB.toByte(), 0x86.toByte(), 0x02, 0x7F, 0x4B,
            0xA8.toByte(), 0xAF.toByte(), 0xA6.toByte(), 0x8E.toByte(), 0x0F, 0xFF.toByte(),
            0x99.toByte(), 0x14
        )
        val header = ByteArray(0x3C)
        System.arraycopy(magic, 0, header, 0, 16)
        writeLe32(header, 0x10, 0x3C)
        writeLe32(header, 0x14, 3)
        writeLe32(header, 0x18, 1)
        System.arraycopy(cryptoKey, 0, header, 0x2C, 16)

        val slotBox = kugouMd5(slotKey)
        val fileBox = kugouMd5(cryptoKey) + 0x6b.toByte()

        val audio = ByteArray(plain.size)
        for (i in plain.indices) {
            // 加密 = 解密序列的逆序：collapse → slotBox → f(b) → fileBox
            var b = plain[i].toInt() and 0xFF
            b = b xor ((i xor (i ushr 8) xor (i ushr 16) xor (i ushr 24)) and 0xFF)
            b = b xor (slotBox[i % 16].toInt() and 0xFF)
            b = b xor ((b shl 4) and 0xFF)
            b = b xor (fileBox[i % 17].toInt() and 0xFF)
            audio[i] = b.toByte()
        }
        return header + audio
    }

    private fun buildKwmFile(plain: ByteArray, key: ByteArray): ByteArray {
        val header = ByteArray(0x400)
        val magic = "yeelion-kuwo-tme".toByteArray(Charsets.ISO_8859_1)
        System.arraycopy(magic, 0, header, 0, magic.size)
        System.arraycopy(key, 0, header, 0x18, 8)

        val predef = "MoOtOiTvINGwd2E6n0E1i7L5t2IoOoNk".toByteArray(Charsets.ISO_8859_1)
        var keyInt = 0L
        for (i in 0 until 8) keyInt = keyInt or ((key[i].toLong() and 0xFF) shl (8 * i))
        val keyStr = java.lang.Long.toUnsignedString(keyInt)
        val padded = StringBuilder()
        for (i in 0 until 32) padded.append(keyStr[i % keyStr.length])
        val mask = ByteArray(32) { i -> (predef[i].toInt() xor padded[i].code).toByte() }

        val audio = ByteArray(plain.size)
        for (i in plain.indices) {
            audio[i] = (plain[i].toInt() xor (mask[i and 0x1F].toInt() and 0xFF)).toByte()
        }
        return header + audio
    }

    private fun kugouMd5(b: ByteArray): ByteArray {
        val d = MessageDigest.getInstance("MD5").digest(b)
        val ret = ByteArray(16)
        var i = 0
        while (i < 16) {
            ret[i] = d[14 - i]
            ret[i + 1] = d[14 - i + 1]
            i += 2
        }
        return ret
    }

    private fun writeLe32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte()
        b[o + 1] = ((v ushr 8) and 0xFF).toByte()
        b[o + 2] = ((v ushr 16) and 0xFF).toByte()
        b[o + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    companion object {
        private val STATIC_BOX = byteArrayOf(
            0x77, 0x48, 0x32, 0x73, 0xDE.toByte(), 0xF2.toByte(), 0xC0.toByte(), 0xC8.toByte(),
            0x95.toByte(), 0xEC.toByte(), 0x30, 0xB2.toByte(), 0x51, 0xC3.toByte(), 0xE1.toByte(), 0xA0.toByte(),
            0x9E.toByte(), 0xE6.toByte(), 0x9D.toByte(), 0xCF.toByte(), 0xFA.toByte(), 0x7F, 0x14, 0xD1.toByte(),
            0xCE.toByte(), 0xB8.toByte(), 0xDC.toByte(), 0xC3.toByte(), 0x4A, 0x67, 0x93.toByte(), 0xD6.toByte(),
            0x28, 0xC2.toByte(), 0x91.toByte(), 0x70, 0xCA.toByte(), 0x8D.toByte(), 0xA2.toByte(), 0xA4.toByte(),
            0xF0.toByte(), 0x08, 0x61, 0x90.toByte(), 0x7E, 0x6F, 0xA2.toByte(), 0xE0.toByte(),
            0xEB.toByte(), 0xAE.toByte(), 0x3E, 0xB6.toByte(), 0x67, 0xC7.toByte(), 0x92.toByte(), 0xF4.toByte(),
            0x91.toByte(), 0xB5.toByte(), 0xF6.toByte(), 0x6C, 0x5E, 0x84.toByte(), 0x40, 0xF7.toByte(),
            0xF3.toByte(), 0x1B, 0x02, 0x7F, 0xD5.toByte(), 0xAB.toByte(), 0x41, 0x89.toByte(),
            0x28, 0xF4.toByte(), 0x25, 0xCC.toByte(), 0x52, 0x11, 0xAD.toByte(), 0x43,
            0x68, 0xA6.toByte(), 0x41, 0x8B.toByte(), 0x84.toByte(), 0xB5.toByte(), 0xFF.toByte(), 0x2C,
            0x92.toByte(), 0x4A, 0x26, 0xD8.toByte(), 0x47, 0x6A, 0x7C, 0x95.toByte(),
            0x61, 0xCC.toByte(), 0xE6.toByte(), 0xCB.toByte(), 0xBB.toByte(), 0x3F, 0x47, 0x58,
            0x89.toByte(), 0x75, 0xC3.toByte(), 0x75, 0xA1.toByte(), 0xD9.toByte(), 0xAF.toByte(), 0xCC.toByte(),
            0x08, 0x73, 0x17, 0xDC.toByte(), 0xAA.toByte(), 0x9A.toByte(), 0xA2.toByte(), 0x16,
            0x41, 0xD8.toByte(), 0xA2.toByte(), 0x06, 0xC6.toByte(), 0x8B.toByte(), 0xFC.toByte(), 0x66,
            0x34, 0x9F.toByte(), 0xCF.toByte(), 0x18, 0x23, 0xA0.toByte(), 0x0A, 0x74,
            0xE7.toByte(), 0x2B, 0x27, 0x70, 0x92.toByte(), 0xE9.toByte(), 0xAF.toByte(), 0x37,
            0xE6.toByte(), 0x8C.toByte(), 0xA7.toByte(), 0xBC.toByte(), 0x62, 0x65, 0x9C.toByte(), 0xC2.toByte(),
            0x08, 0xC9.toByte(), 0x88.toByte(), 0xB3.toByte(), 0xF3.toByte(), 0x43, 0xAC.toByte(), 0x74,
            0x2C, 0x0F, 0xD4.toByte(), 0xAF.toByte(), 0xA1.toByte(), 0xC3.toByte(), 0x01, 0x64,
            0x95.toByte(), 0x4E, 0x48, 0x9F.toByte(), 0xF4.toByte(), 0x35, 0x78, 0x95.toByte(),
            0x7A, 0x39, 0xD6.toByte(), 0x6A, 0xA0.toByte(), 0x6D, 0x40, 0xE8.toByte(),
            0x4F, 0xA8.toByte(), 0xEF.toByte(), 0x11, 0x1D, 0xF3.toByte(), 0x1B, 0x3F,
            0x3F, 0x07, 0xDD.toByte(), 0x6F, 0x5B, 0x19, 0x30, 0x19,
            0xFB.toByte(), 0xEF.toByte(), 0x0E, 0x37, 0xF0.toByte(), 0x0E, 0xCD.toByte(), 0x16,
            0x49, 0xFE.toByte(), 0x53, 0x47, 0x13, 0x1A, 0xBD.toByte(), 0xA4.toByte(),
            0xF1.toByte(), 0x40, 0x19, 0x60, 0x0E, 0xED.toByte(), 0x68, 0x09,
            0x06, 0x5F, 0x4D, 0xCF.toByte(), 0x3D, 0x1A, 0xFE.toByte(), 0x20,
            0x77, 0xE4.toByte(), 0xD9.toByte(), 0xDA.toByte(), 0xF9.toByte(), 0xA4.toByte(), 0x2B, 0x76,
            0x1C, 0x71, 0xDB.toByte(), 0x00, 0xBC.toByte(), 0xFD.toByte(), 0x0C, 0x6C,
            0xA5.toByte(), 0x47, 0xF7.toByte(), 0xF6.toByte(), 0x00, 0x79, 0x4A, 0x11
        )
    }
}
