package com.musicconvert.decrypt

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * 酷狗 KGM / KGMA / VPR 解密（crypto v3）。
 *
 * 移植自 Brayn-Studio/freenote（MIT）的 MusicDecoder.kt，其算法比对自 unlock-music 的
 * `algo/kgm`。v5（.kgg）的密钥在客户端 KGMusicV3.db 里，离线无解——按需求不做。
 *
 * 头部布局（16 字节魔数之后）：
 *   0x10 AudioOffset(u32 LE)  0x14 CryptoVersion  0x18 CryptoSlot
 *   0x1C testData(16)         0x2C CryptoKey(16)
 */
object KgmDecryptor {

    private const val CHUNK = 65536

    private val KGM_MAGIC = byteArrayOf(
        0x7C, 0xD5.toByte(), 0x32, 0xEB.toByte(), 0x86.toByte(), 0x02, 0x7F, 0x4B,
        0xA8.toByte(), 0xAF.toByte(), 0xA6.toByte(), 0x8E.toByte(), 0x0F, 0xFF.toByte(), 0x99.toByte(), 0x14
    )
    private val VPR_MAGIC = byteArrayOf(
        0x05, 0x28, 0xBC.toByte(), 0x96.toByte(), 0xE9.toByte(), 0xE4.toByte(), 0x5A, 0x43,
        0x91.toByte(), 0xAA.toByte(), 0xBD.toByte(), 0xD0.toByte(), 0x7A, 0xF5.toByte(), 0x36, 0x31
    )
    private val V3_SLOT_KEYS = mapOf(1 to byteArrayOf(0x6C, 0x2C, 0x2F, 0x27))

    fun matchesMagic(head: ByteArray): Boolean =
        matches(head, KGM_MAGIC) || matches(head, VPR_MAGIC)

    fun decrypt(input: File, output: File, onProgress: (Float) -> Unit = {}): DecryptedAudio {
        RandomAccessFile(input, "r").use { raf ->
            val fileSize = raf.length()
            if (fileSize < 0x3C) throw DecryptException("KGM 头部过短")

            val header = ByteArray(0x3C)
            raf.readFully(header)
            if (!matchesMagic(header)) throw DecryptException("不是有效的 KGM/VPR 文件")

            val audioOffset = le32(header, 0x10)
            val cryptoVersion = le32(header, 0x14).toInt()
            val cryptoSlot = le32(header, 0x18).toInt()
            if (cryptoVersion != 3) {
                throw DecryptException("酷狗 .kgg / KGM v5 的密钥保存在客户端数据库，无法离线解密")
            }
            val slotKey = V3_SLOT_KEYS[cryptoSlot]
                ?: throw DecryptException("不支持的 KGM 密钥槽 $cryptoSlot")
            if (audioOffset < 0x3C || audioOffset > fileSize) {
                throw DecryptException("KGM 音频偏移非法")
            }

            val cipher = KgmV3Cipher(slotKey, header.copyOfRange(0x2C, 0x3C))

            output.parentFile?.mkdirs()
            val total = fileSize - audioOffset
            var offset = 0L
            val buf = ByteArray(CHUNK)
            raf.seek(audioOffset)
            FileOutputStream(output).use { out ->
                while (offset < total) {
                    val toRead = minOf(CHUNK.toLong(), total - offset).toInt()
                    val read = raf.read(buf, 0, toRead)
                    if (read <= 0) break
                    cipher.decrypt(buf, 0, read, offset)
                    out.write(buf, 0, read)
                    offset += read
                    if (total > 0) onProgress((offset.toFloat() / total).coerceIn(0f, 1f))
                }
            }
            if (offset == 0L) throw DecryptException("KGM 中没有音频数据")

            return DecryptedAudio(file = output, containerExt = AudioSniffer.detectExt(output))
        }
    }

    private class KgmV3Cipher(slotKey: ByteArray, cryptoKey: ByteArray) {
        private val slotBox = kugouMd5(slotKey)                       // 16 字节
        private val fileBox = kugouMd5(cryptoKey) + 0x6b.toByte()     // 17 字节

        fun decrypt(buf: ByteArray, off: Int, len: Int, fileOffset: Long) {
            for (i in 0 until len) {
                val p = fileOffset + i
                var b = buf[off + i].toInt() and 0xFF
                b = b xor (fileBox[(p % 17).toInt()].toInt() and 0xFF)
                b = b xor ((b shl 4) and 0xFF)
                b = b xor (slotBox[(p % 16).toInt()].toInt() and 0xFF)
                val x = p.toInt()
                b = b xor ((x xor (x ushr 8) xor (x ushr 16) xor (x ushr 24)) and 0xFF)
                buf[off + i] = b.toByte()
            }
        }
    }

    /** 酷狗魔改版 MD5：每 2 字节一组，把 8 组顺序反转。 */
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

    private fun matches(data: ByteArray, magic: ByteArray): Boolean {
        if (data.size < magic.size) return false
        return magic.indices.all { data[it] == magic[it] }
    }

    private fun le32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)
}
