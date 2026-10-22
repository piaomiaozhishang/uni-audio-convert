package com.musicconvert.decrypt

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * 酷我 KWM / KWMA 解密。
 *
 * 移植自 Brayn-Studio/freenote（MIT）的 MusicDecoder.kt（比对自 unlock-music `algo/kwm`）。
 *
 * 头部 0x400 字节：
 *   0x00 magic "yeelion-kuwo-tme" 或 "yeelion-kuwo\0\0\0\0"
 *   0x18 8 字节密钥（小端 u64 转十进制字符串，循环填充到 32 位后与预置串异或）
 * 音频从 0x400 开始，逐字节 ^= mask[offset & 0x1F]
 */
object KwmDecryptor {

    private const val CHUNK = 65536
    private const val HEADER = 0x400

    private val MAGIC1 = "yeelion-kuwo-tme".toByteArray(Charsets.ISO_8859_1)
    private val MAGIC2 = "yeelion-kuwo\u0000\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1)
    private val PREDEF = "MoOtOiTvINGwd2E6n0E1i7L5t2IoOoNk".toByteArray(Charsets.ISO_8859_1)

    fun matchesMagic(head: ByteArray): Boolean =
        matches(head, MAGIC1) || matches(head, MAGIC2)

    fun decrypt(input: File, output: File, onProgress: (Float) -> Unit = {}): DecryptedAudio {
        RandomAccessFile(input, "r").use { raf ->
            val fileSize = raf.length()
            if (fileSize < HEADER) throw DecryptException("KWM 文件过短")

            val header = ByteArray(HEADER)
            raf.readFully(header)
            if (!matchesMagic(header)) throw DecryptException("不是有效的 KWM 文件")

            val cipher = KwmCipher(header.copyOfRange(0x18, 0x20))

            output.parentFile?.mkdirs()
            val total = fileSize - HEADER
            var offset = 0L
            val buf = ByteArray(CHUNK)
            raf.seek(HEADER.toLong())
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
            if (offset == 0L) throw DecryptException("KWM 中没有音频数据")

            return DecryptedAudio(file = output, containerExt = AudioSniffer.detectExt(output))
        }
    }

    private class KwmCipher(key: ByteArray) {
        private val mask = generate(key)

        private fun generate(key: ByteArray): ByteArray {
            var keyInt = 0L
            for (i in 0 until 8) keyInt = keyInt or ((key[i].toLong() and 0xFF) shl (8 * i))
            val keyStr = java.lang.Long.toUnsignedString(keyInt)
            val trimmed = padOrTruncate(keyStr, 32)
            val m = ByteArray(32)
            for (i in 0 until 32) {
                m[i] = ((PREDEF[i].toInt() and 0xFF) xor trimmed[i].code).toByte()
            }
            return m
        }

        private fun padOrTruncate(raw: String, length: Int): String {
            if (raw.isEmpty()) return " ".repeat(length)
            if (raw.length >= length) return raw.substring(0, length)
            val sb = StringBuilder(length)
            for (i in 0 until length) sb.append(raw[i % raw.length])
            return sb.toString()
        }

        fun decrypt(buf: ByteArray, off: Int, len: Int, fileOffset: Long) {
            for (i in 0 until len) {
                val mi = ((fileOffset + i) and 0x1FL).toInt()
                buf[off + i] = (buf[off + i].toInt() xor (mask[mi].toInt() and 0xFF)).toByte()
            }
        }
    }

    private fun matches(data: ByteArray, magic: ByteArray): Boolean {
        if (data.size < magic.size) return false
        return magic.indices.all { data[it] == magic[it] }
    }
}
