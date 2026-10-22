package com.musicconvert.decrypt

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.Base64

/**
 * QQ 音乐 / Moo 音乐 QMC 家族解密。
 *
 * 移植自 Brayn-Studio/freenote（MIT）的 MusicDecoder.kt，其实现逐字节对齐
 * unlock-music CLI v0.2.12 的 algo/qmc 包并通过其测试向量。
 *
 * 流水线：
 *   footer  → 音频长度 + 内嵌 EKey（base64）/ 或标记"密钥在客户端"
 *   deriveKey(ekey) → base64 →（可选 EncV2 双层 TEA）→ V1 TEA-CBC
 *   cipher: 密钥 >300 字节 → RC4；1..300 → map；0 → 静态（旧版 QMCv1）
 *
 * 三个 cipher 的 keystream 只跟绝对偏移有关，所以分块流式解密是安全的。
 */
object QmcDecryptor {

    private const val CHUNK = 65536
    private const val KEY_THRESHOLD = 300
    private const val MAX_TRAILER = 1 shl 20
    private const val MAX_EKEY = 0xFFFF

    const val MSG_NO_KEY =
        "此文件的密钥未嵌入文件（QQ音乐新版 musicex/STag 格式），需先导入密钥才能离线解密。"

    private class Footer(
        val audioLen: Long,
        val ekey: ByteArray?,
        val needsExternalKey: Boolean = false,
        val externalNames: List<String> = emptyList(),
    )

    /**
     * @param ekeyLookup 外部密钥查找：传入候选文件名，返回 base64 EKey 或 null
     */
    fun decrypt(
        input: File,
        output: File,
        fileName: String,
        ekeyLookup: ((List<String>) -> String?)? = null,
        onProgress: (Float) -> Unit = {},
    ): DecryptedAudio {
        RandomAccessFile(input, "r").use { raf ->
            val fileSize = raf.length()
            if (fileSize < 4) throw DecryptException("文件过小，不是有效的 QMC")

            val footer = parseFooter(raf, fileSize)
            val ekey = resolveEkey(footer, fileName, ekeyLookup)
            val realKey = if (ekey != null) deriveKey(ekey) else ByteArray(0)
            val cipher = cipherFor(realKey)

            output.parentFile?.mkdirs()
            var offset = 0L
            val buf = ByteArray(CHUNK)
            FileOutputStream(output).use { out ->
                raf.seek(0L)
                while (offset < footer.audioLen) {
                    val toRead = minOf(CHUNK.toLong(), footer.audioLen - offset).toInt()
                    val read = raf.read(buf, 0, toRead)
                    if (read <= 0) break
                    cipher.decrypt(buf, 0, read, offset)
                    out.write(buf, 0, read)
                    offset += read
                    if (footer.audioLen > 0) {
                        onProgress((offset.toFloat() / footer.audioLen).coerceIn(0f, 1f))
                    }
                }
            }
            if (offset == 0L) throw DecryptException("QMC 中没有音频数据")

            return DecryptedAudio(
                file = output,
                containerExt = AudioSniffer.detectExt(output),
            )
        }
    }

    // ── footer 解析 ───────────────────────────────────────────────

    private fun le32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    private fun be32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun trimTrailingNul(b: ByteArray): ByteArray {
        var end = b.size
        while (end > 0 && b[end - 1] == 0.toByte()) end--
        return if (end == b.size) b else b.copyOf(end)
    }

    private fun parseFooter(raf: RandomAccessFile, fileSize: Long): Footer {
        val tail = ByteArray(4)
        raf.seek(fileSize - 4)
        raf.readFully(tail)

        // "QTag" → [rawMeta][metaLen BE u32]["QTag"]，rawMeta = "ekey,songID,extra2"
        if (tail[0] == 'Q'.code.toByte() && tail[1] == 'T'.code.toByte() &&
            tail[2] == 'a'.code.toByte() && tail[3] == 'g'.code.toByte()
        ) {
            if (fileSize < 8) throw DecryptException(MSG_NO_KEY)
            val ml = ByteArray(4)
            raf.seek(fileSize - 8)
            raf.readFully(ml)
            val metaLen = be32(ml, 0)
            if (metaLen < 0 || metaLen > MAX_TRAILER) throw DecryptException("QMC 尾部元数据长度非法")
            val audioLen = fileSize - 8 - metaLen
            if (audioLen < 0) throw DecryptException("QMC 尾部元数据长度非法")
            val meta = ByteArray(metaLen)
            raf.seek(audioLen)
            raf.readFully(meta)
            val items = String(meta, Charsets.ISO_8859_1).split(",")
            if (items.isEmpty() || items[0].isEmpty()) throw DecryptException(MSG_NO_KEY)
            return Footer(audioLen, items[0].toByteArray(Charsets.ISO_8859_1))
        }

        // "STag" → 密钥不在文件里
        if (tail[0] == 'S'.code.toByte() && tail[1] == 'T'.code.toByte() &&
            tail[2] == 'a'.code.toByte() && tail[3] == 'g'.code.toByte()
        ) {
            if (fileSize < 8) throw DecryptException(MSG_NO_KEY)
            val ml = ByteArray(4)
            raf.seek(fileSize - 8)
            raf.readFully(ml)
            val metaLen = be32(ml, 0)
            if (metaLen < 0 || metaLen > MAX_TRAILER) throw DecryptException("QMC 尾部元数据长度非法")
            val audioLen = fileSize - 8 - metaLen
            if (audioLen < 0) throw DecryptException("QMC 尾部元数据长度非法")
            return Footer(audioLen, null, needsExternalKey = true)
        }

        // "cex\0"（musicex）→ 密钥在客户端 MMKV，尾部头在 fileSize-16
        if (tail[0] == 'c'.code.toByte() && tail[1] == 'e'.code.toByte() &&
            tail[2] == 'x'.code.toByte() && tail[3] == 0.toByte()
        ) {
            if (fileSize < 16) throw DecryptException(MSG_NO_KEY)
            val hdr = ByteArray(4)
            raf.seek(fileSize - 16)
            raf.readFully(hdr)
            val tagSize = le32(hdr, 0)
            val audioLen = fileSize - tagSize
            if (tagSize < 16 || tagSize > MAX_TRAILER || audioLen < 0) {
                throw DecryptException("QMC 尾部元数据长度非法")
            }
            val mediaName = try {
                readMusicExName(raf, fileSize, tagSize)
            } catch (_: Exception) {
                ""
            }
            val names = if (mediaName.isNotEmpty()) listOf(mediaName) else emptyList()
            return Footer(audioLen, null, needsExternalKey = true, externalNames = names)
        }

        // 数字：末 4 字节（LE）= 内嵌 EKey 的长度
        val keyLen = le32(tail, 0)
        if (keyLen in 1..MAX_EKEY.toLong() && keyLen < fileSize - 4) {
            val audioLen = fileSize - 4 - keyLen
            val ekey = ByteArray(keyLen.toInt())
            raf.seek(audioLen)
            raf.readFully(ekey)
            return Footer(audioLen, trimTrailingNul(ekey))
        }

        // 无尾部 → 整个文件都是音频，走静态密码（旧版 QMCv1）
        return Footer(fileSize, null)
    }

    /** musicex 标签体里的 MediaFileName（UTF-16-LE），用于按文件名找导入的密钥。 */
    private fun readMusicExName(raf: RandomAccessFile, fileSize: Long, tagSize: Long): String {
        val nameOff = fileSize - tagSize + 0x48
        if (nameOff < 0 || nameOff + 2 > fileSize) return ""
        val avail = (fileSize - 16 - nameOff).coerceAtMost(100L).toInt()
        if (avail <= 0) return ""
        val buf = ByteArray(avail)
        raf.seek(nameOff)
        raf.readFully(buf)
        val sb = StringBuilder()
        var i = 0
        while (i + 1 < buf.size) {
            val c = buf[i].toInt() and 0xFF
            if (c == 0) break
            sb.append(c.toChar())
            i += 2
        }
        return sb.toString()
    }

    private fun resolveEkey(
        footer: Footer,
        fileName: String,
        ekeyLookup: ((List<String>) -> String?)?,
    ): ByteArray? {
        if (footer.ekey != null) return footer.ekey
        if (footer.needsExternalKey) {
            val names = (footer.externalNames + fileName).filter { it.isNotBlank() }.distinct()
            val resolved = ekeyLookup?.invoke(names) ?: throw DecryptException(MSG_NO_KEY)
            return resolved.toByteArray(Charsets.ISO_8859_1)
        }
        return null // 静态密码
    }

    // ── cipher 选择与实现 ─────────────────────────────────────────

    private fun cipherFor(realKey: ByteArray): QmcCipher = when {
        realKey.size > KEY_THRESHOLD -> QmcRc4Cipher(realKey)
        realKey.isNotEmpty() -> QmcMapCipher(realKey)
        else -> QmcStaticCipher
    }

    private interface QmcCipher {
        fun decrypt(buf: ByteArray, bufOff: Int, len: Int, fileOffset: Long)
    }

    private object QmcStaticCipher : QmcCipher {
        override fun decrypt(buf: ByteArray, bufOff: Int, len: Int, fileOffset: Long) {
            for (i in 0 until len) {
                var off = fileOffset + i
                if (off > 0x7FFF) off %= 0x7FFF
                val o = off.toInt()
                val idx = (o * o + 27) and 0xFF
                buf[bufOff + i] = (buf[bufOff + i].toInt() xor (STATIC_BOX[idx].toInt() and 0xFF)).toByte()
            }
        }
    }

    private class QmcMapCipher(private val key: ByteArray) : QmcCipher {
        private val size = key.size

        private fun mask(fileOffset: Long): Int {
            var off = fileOffset
            if (off > 0x7FFF) off %= 0x7FFF
            val o = off.toInt()
            val idx = ((o.toLong() * o + 71214) % size).toInt()
            val v = key[idx].toInt() and 0xFF
            val r = ((idx and 0x07) + 4) % 8
            return ((v shl r) and 0xFF) or (v ushr r)
        }

        override fun decrypt(buf: ByteArray, bufOff: Int, len: Int, fileOffset: Long) {
            for (i in 0 until len) {
                buf[bufOff + i] =
                    (buf[bufOff + i].toInt() xor mask(fileOffset + i)).toByte()
            }
        }
    }

    private class QmcRc4Cipher(private val key: ByteArray) : QmcCipher {
        private val n = key.size
        private val box = ByteArray(n)
        private val hash: Long

        init {
            for (i in 0 until n) box[i] = i.toByte()
            var j = 0
            for (i in 0 until n) {
                j = (j + (box[i].toInt() and 0xFF) + (key[i % n].toInt() and 0xFF)) % n
                val t = box[i]; box[i] = box[j]; box[j] = t
            }
            var h = 1L
            for (i in 0 until n) {
                val v = key[i].toInt() and 0xFF
                if (v == 0) continue
                val next = (h * v) and 0xFFFFFFFFL
                if (next == 0L || next <= h) break
                h = next
            }
            hash = h
        }

        private fun segmentSkip(id: Int): Int {
            val seed = key[id % n].toInt() and 0xFF
            if (seed == 0) return 0
            val idx = (hash.toDouble() / ((id + 1).toLong() * seed).toDouble() * 100.0).toLong()
            return (idx % n).toInt()
        }

        private fun encFirstSegment(buf: ByteArray, bufOff: Int, len: Int, offset: Int) {
            for (i in 0 until len) {
                buf[bufOff + i] = (buf[bufOff + i].toInt() xor
                    (key[segmentSkip(offset + i)].toInt() and 0xFF)).toByte()
            }
        }

        private fun encASegment(buf: ByteArray, bufOff: Int, len: Int, offset: Int) {
            val b = box.copyOf()
            var j = 0
            var k = 0
            val skipLen = (offset % SEG) + segmentSkip(offset / SEG)
            var i = -skipLen
            while (i < len) {
                j = (j + 1) % n
                k = ((b[j].toInt() and 0xFF) + k) % n
                val t = b[j]; b[j] = b[k]; b[k] = t
                if (i >= 0) {
                    val ks = b[((b[j].toInt() and 0xFF) + (b[k].toInt() and 0xFF)) % n].toInt() and 0xFF
                    buf[bufOff + i] = (buf[bufOff + i].toInt() xor ks).toByte()
                }
                i++
            }
        }

        override fun decrypt(buf: ByteArray, bufOff: Int, len: Int, fileOffset: Long) {
            var offset = fileOffset.toInt()
            var toProcess = len
            var processed = 0

            if (offset < FIRST) {
                val bs = minOf(toProcess, FIRST - offset)
                encFirstSegment(buf, bufOff + processed, bs, offset)
                offset += bs; toProcess -= bs; processed += bs
                if (toProcess == 0) return
            }
            if (offset % SEG != 0) {
                val bs = minOf(toProcess, SEG - offset % SEG)
                encASegment(buf, bufOff + processed, bs, offset)
                offset += bs; toProcess -= bs; processed += bs
                if (toProcess == 0) return
            }
            while (toProcess > SEG) {
                encASegment(buf, bufOff + processed, SEG, offset)
                offset += SEG; toProcess -= SEG; processed += SEG
            }
            if (toProcess > 0) encASegment(buf, bufOff + processed, toProcess, offset)
        }

        companion object {
            private const val SEG = 5120
            private const val FIRST = 128
        }
    }

    // ── EKey 派生：base64 → 可选 EncV2 → V1 TEA-CBC ────────────────

    private val SIMPLE_KEY = byteArrayOf(0x69, 0x56, 0x46, 0x38, 0x2B, 0x20, 0x15, 0x0B)

    private val DERIVE_V2_KEY1 = byteArrayOf(
        0x33, 0x38, 0x36, 0x5A, 0x4A, 0x59, 0x21, 0x40,
        0x23, 0x2A, 0x24, 0x25, 0x5E, 0x26, 0x29, 0x28
    )
    private val DERIVE_V2_KEY2 = byteArrayOf(
        0x2A, 0x2A, 0x23, 0x21, 0x28, 0x23, 0x24, 0x25,
        0x26, 0x5E, 0x61, 0x31, 0x63, 0x5A, 0x2C, 0x54
    )
    private val V2_PREFIX = "QQMusic EncV2,Key:".toByteArray(Charsets.ISO_8859_1)

    private fun base64Decode(b: ByteArray): ByteArray = try {
        Base64.getDecoder().decode(b)
    } catch (_: Exception) {
        throw DecryptException("QQ 音乐密钥格式无效")
    }

    fun deriveKey(rawKey: ByteArray): ByteArray {
        var dec = base64Decode(rawKey)
        if (dec.size >= V2_PREFIX.size && V2_PREFIX.indices.all { dec[it] == V2_PREFIX[it] }) {
            dec = deriveKeyV2(dec.copyOfRange(V2_PREFIX.size, dec.size))
        }
        return deriveKeyV1(dec)
    }

    private fun deriveKeyV1(dec: ByteArray): ByteArray {
        if (dec.size < 16) throw DecryptException("无效的 QQ 音乐密钥")
        val teaKey = ByteArray(16)
        for (i in 0 until 8) {
            teaKey[i shl 1] = SIMPLE_KEY[i]
            teaKey[(i shl 1) + 1] = dec[i]
        }
        val rs = decryptTencentTea(dec.copyOfRange(8, dec.size), teaKey)
        val out = ByteArray(8 + rs.size)
        System.arraycopy(dec, 0, out, 0, 8)
        System.arraycopy(rs, 0, out, 8, rs.size)
        return out
    }

    private fun deriveKeyV2(raw: ByteArray): ByteArray {
        var buf = decryptTencentTea(raw, DERIVE_V2_KEY1)
        buf = decryptTencentTea(buf, DERIVE_V2_KEY2)
        return base64Decode(buf)
    }

    // ── 腾讯 TEA（CBC，对齐 TarsCpp oi_symmetry_decrypt2）──────────

    private fun beReadInt(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun beWriteInt(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 24).toByte()
        b[o + 1] = (v ushr 16).toByte()
        b[o + 2] = (v ushr 8).toByte()
        b[o + 3] = v.toByte()
    }

    private fun teaDecryptBlock(block: ByteArray, k: IntArray) {
        var v0 = beReadInt(block, 0)
        var v1 = beReadInt(block, 4)
        val delta = 0x9E3779B9.toInt()
        var sum = 0xE3779B90.toInt()
        repeat(16) {
            v1 -= ((v0 shl 4) + k[2]) xor (v0 + sum) xor ((v0 ushr 5) + k[3])
            v0 -= ((v1 shl 4) + k[0]) xor (v1 + sum) xor ((v1 ushr 5) + k[1])
            sum -= delta
        }
        beWriteInt(block, 0, v0)
        beWriteInt(block, 4, v1)
    }

    private fun decryptTencentTea(inBuf: ByteArray, key: ByteArray): ByteArray {
        val saltLen = 2
        val zeroLen = 7
        if (inBuf.size % 8 != 0) throw DecryptException("TEA 输入长度非法")
        if (inBuf.size < 16) throw DecryptException("TEA 输入过短")
        val tk = intArrayOf(
            beReadInt(key, 0), beReadInt(key, 4), beReadInt(key, 8), beReadInt(key, 12)
        )

        val destBuf = inBuf.copyOfRange(0, 8)
        teaDecryptBlock(destBuf, tk)
        val padLen = destBuf[0].toInt() and 0x07
        val outLen = inBuf.size - 1 - padLen - saltLen - zeroLen
        if (outLen < 0) throw DecryptException("TEA 解密失败")
        val out = ByteArray(outLen)

        val ivPrev = ByteArray(8)
        val ivCur = inBuf.copyOfRange(0, 8)
        var inPos = 8
        var destIdx = 1 + padLen

        fun cryptBlock() {
            System.arraycopy(ivCur, 0, ivPrev, 0, 8)
            System.arraycopy(inBuf, inPos, ivCur, 0, 8)
            for (i in 0 until 8) {
                destBuf[i] = (destBuf[i].toInt() xor inBuf[inPos + i].toInt()).toByte()
            }
            teaDecryptBlock(destBuf, tk)
            inPos += 8
            destIdx = 0
        }

        var i = 1
        while (i <= saltLen) {
            if (destIdx < 8) {
                destIdx++; i++
            } else {
                cryptBlock()
            }
        }
        var outPos = 0
        while (outPos < outLen) {
            if (destIdx < 8) {
                out[outPos] = (destBuf[destIdx].toInt() xor ivPrev[destIdx].toInt()).toByte()
                destIdx++; outPos++
            } else {
                cryptBlock()
            }
        }
        for (z in 1..zeroLen) {
            if (destIdx < 8 && destBuf[destIdx] != ivPrev[destIdx]) {
                throw DecryptException("QQ 音乐密钥校验失败")
            }
        }
        return out
    }

    // QMCv1 静态密码盒（unlock-music cipher_static.go）
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
