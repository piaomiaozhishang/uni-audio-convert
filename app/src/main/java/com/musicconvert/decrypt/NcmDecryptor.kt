package com.musicconvert.decrypt

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 解密结果：一个已经落盘的普通音频文件 + 从加密容器里捞出来的标签信息。
 */
data class DecryptedAudio(
    val file: File,
    val containerExt: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val cover: ByteArray? = null,
)

class DecryptException(message: String) : Exception(message)

/**
 * 网易云 .ncm 解密。
 *
 * 算法移植自 Brayn-Studio/freenote（MIT）的 MusicDecoder.kt，其实现逐字节对齐
 * unlock-music / ncmdump。注意两点容易被写错的地方：
 *  1. keyBox 不是标准 RC4 KSA，而是网易自创的 lastByte 递推；
 *  2. 音频 XOR 也不是 RC4 PRGA，而是 keyBox 的三重索引。
 * 不要凭印象改写这两段。
 */
object NcmDecryptor {

    private const val CHUNK = 65536 // 256 的整数倍，保证分块后 (i+1)&0xFF 相位连续
    private const val MAX_KEY_LEN = 1 shl 20
    private const val MAX_META_LEN = 4 shl 20
    private const val MAX_IMAGE_LEN = 32 shl 20

    private val SCORE_KEY = byteArrayOf(
        0x68, 0x7A, 0x48, 0x52, 0x41, 0x6D, 0x73, 0x6F,
        0x35, 0x6B, 0x49, 0x6E, 0x62, 0x61, 0x78, 0x57
    )

    private val META_KEY = byteArrayOf(
        0x23, 0x31, 0x34, 0x6C, 0x6A, 0x6B, 0x5F, 0x21,
        0x5C, 0x5D, 0x26, 0x30, 0x55, 0x3C, 0x27, 0x28
    )

    fun decrypt(input: File, output: File, onProgress: (Float) -> Unit = {}): DecryptedAudio {
        RandomAccessFile(input, "r").use { raf ->
            if (raf.length() < 24) throw DecryptException("文件过小，不是有效的 NCM")

            val header = ByteArray(10)
            raf.readFully(header)
            if (!isNcmHeader(header)) throw DecryptException("不是有效的 NCM 文件")

            val keyLen = readLeU32(raf, MAX_KEY_LEN, "NCM 密钥长度非法")
            val rawKey = ByteArray(keyLen)
            raf.readFully(rawKey)
            for (i in rawKey.indices) rawKey[i] = (rawKey[i].toInt() xor 0x64).toByte()

            val decryptedKey = try {
                aesEcbDecrypt(SCORE_KEY, rawKey)
            } catch (_: Exception) {
                throw DecryptException("NCM 密钥数据损坏")
            }
            if (decryptedKey.size <= 17) throw DecryptException("NCM 密钥数据损坏")
            val keyBox = buildKeyBox(decryptedKey.copyOfRange(17, decryptedKey.size))

            val metaLen = readLeU32(raf, MAX_META_LEN, "NCM 元数据长度非法")
            val meta = parseMeta(raf, metaLen)

            skipFully(raf, 5) // CRC32(4) + 版本(1)

            val coverFrameLen = readLeU32(raf, MAX_IMAGE_LEN, "NCM 封面长度非法")
            val imageLen = readLeU32(raf, MAX_IMAGE_LEN, "NCM 图片长度非法")
            var cover: ByteArray? = null
            if (imageLen > 0) {
                val img = ByteArray(imageLen)
                raf.readFully(img)
                cover = img
            }
            val skipRest = (if (coverFrameLen == 0) imageLen else coverFrameLen) - imageLen
            if (skipRest > 0) skipFully(raf, skipRest.toLong())

            val audioStart = raf.filePointer
            val audioLength = raf.length() - audioStart
            if (audioLength <= 0) throw DecryptException("NCM 中没有音频数据")

            output.parentFile?.mkdirs()
            output.outputStream().use { os ->
                val buf = ByteArray(CHUNK)
                var remaining = audioLength
                while (remaining > 0) {
                    val toRead = minOf(CHUNK.toLong(), remaining).toInt()
                    raf.readFully(buf, 0, toRead)
                    for (i in 0 until toRead) {
                        val j = (i + 1) and 0xFF
                        buf[i] = (buf[i].toInt() xor keyBox[
                            (keyBox[j] + keyBox[(keyBox[j] + j) and 0xFF]) and 0xFF
                        ]).toByte()
                    }
                    os.write(buf, 0, toRead)
                    remaining -= toRead
                    onProgress(1f - remaining.toFloat() / audioLength.toFloat())
                }
            }

            val ext = AudioSniffer.detectExt(output)
            return DecryptedAudio(
                file = output,
                containerExt = ext,
                title = meta.title,
                artist = meta.artist,
                album = meta.album,
                cover = cover,
            )
        }
    }

    /** 只读头部判断是不是 NCM，不做解密。 */
    fun isNcm(file: File): Boolean {
        if (file.length() < 10) return false
        return RandomAccessFile(file, "r").use { raf ->
            val h = ByteArray(10)
            raf.readFully(h)
            isNcmHeader(h)
        }
    }

    fun isNcmHeader(h: ByteArray): Boolean {
        val ctenfdam = byteArrayOf(0x43, 0x54, 0x45, 0x4E, 0x46, 0x44, 0x41, 0x4D)
        val ctcm = byteArrayOf(0x43, 0x54, 0x43, 0x4D)
        val ncmc = byteArrayOf(0x4E, 0x43, 0x4D, 0x43)
        return matches(h, ctenfdam) || matches(h, ctcm) || matches(h, ncmc)
    }

    private fun matches(data: ByteArray, magic: ByteArray): Boolean {
        if (data.size < magic.size) return false
        return magic.indices.all { data[it] == magic[it].toByte() }
    }

    private class NcmMeta(
        val title: String?,
        val artist: String?,
        val album: String?,
    )

    /** 元数据块：XOR 0x63 → 去掉 22 字节前缀 → base64 → AES-128-ECB → "music:{json}"。 */
    private fun parseMeta(raf: RandomAccessFile, metaLen: Int): NcmMeta {
        if (metaLen <= 0) return NcmMeta(null, null, null)
        if (metaLen > MAX_META_LEN) throw DecryptException("NCM 元数据长度非法")
        val raw = ByteArray(metaLen)
        raf.readFully(raw)
        return try {
            for (i in raw.indices) raw[i] = (raw[i].toInt() xor 0x63).toByte()
            if (raw.size <= 22) return NcmMeta(null, null, null)
            val b64 = String(raw, 22, raw.size - 22, Charsets.US_ASCII)
            val plain = aesEcbDecrypt(META_KEY, Base64.getMimeDecoder().decode(b64))
            val jsonStr = String(plain, Charsets.UTF_8).substringAfter("music:", "")
            if (jsonStr.isBlank()) return NcmMeta(null, null, null)
            val json = JSONObject(jsonStr)
            val artists = json.optJSONArray("artist")
            val names = ArrayList<String>()
            if (artists != null) {
                for (i in 0 until artists.length()) {
                    val name = artists.optJSONArray(i)?.optString(0)
                    if (!name.isNullOrBlank()) names.add(name)
                }
            }
            NcmMeta(
                title = json.optString("musicName").ifBlank { null },
                artist = names.joinToString(" / ").ifBlank { null },
                album = json.optString("album").ifBlank { null },
            )
        } catch (_: Exception) {
            NcmMeta(null, null, null)
        }
    }

    private fun aesEcbDecrypt(key: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    /** 自定义 keyBox（不是标准 RC4 KSA）。 */
    private fun buildKeyBox(key: ByteArray): IntArray {
        val s = IntArray(256) { it }
        var lastByte = 0
        var keyOffset = 0
        for (i in 0..255) {
            val swap = s[i]
            val c = (swap + lastByte + (key[keyOffset].toInt() and 0xFF)) and 0xFF
            keyOffset++
            if (keyOffset >= key.size) keyOffset = 0
            s[i] = s[c]
            s[c] = swap
            lastByte = c
        }
        return s
    }

    private fun readLeU32(raf: RandomAccessFile, max: Int, message: String): Int {
        val b = ByteArray(4)
        try {
            raf.readFully(b)
        } catch (_: Exception) {
            throw DecryptException(message)
        }
        val v = (b[0].toLong() and 0xFF) or
            ((b[1].toLong() and 0xFF) shl 8) or
            ((b[2].toLong() and 0xFF) shl 16) or
            ((b[3].toLong() and 0xFF) shl 24)
        if (v < 0 || v > max) throw DecryptException(message)
        return v.toInt()
    }

    private fun skipFully(raf: RandomAccessFile, length: Long) {
        if (length <= 0) return
        val target = raf.filePointer + length
        if (target > raf.length()) throw DecryptException("NCM 头部不完整")
        raf.seek(target)
    }
}
