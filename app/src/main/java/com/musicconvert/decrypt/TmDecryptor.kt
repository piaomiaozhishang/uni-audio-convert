package com.musicconvert.decrypt

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * QQ 音乐 iOS 端 .tm0/.tm2/.tm3/.tm6。
 *
 * 只有一个规则：.tm2/.tm6（M4A）头 4 字节是 "QQMU"，把前 8 字节换成真正的 ftyp 头即可；
 * .tm0/.tm3（MP3）本身没加密，直通。
 */
object TmDecryptor {

    private const val CHUNK = 65536
    private val MAGIC = byteArrayOf(0x51, 0x51, 0x4D, 0x55) // "QQMU"
    private val REPLACE_HEADER = byteArrayOf(
        0x00, 0x00, 0x00, 0x20, 0x66, 0x74, 0x79, 0x70
    )

    fun decrypt(input: File, output: File, onProgress: (Float) -> Unit = {}): DecryptedAudio {
        RandomAccessFile(input, "r").use { raf ->
            val total = raf.length()
            if (total < 4) throw DecryptException("TM 文件过短")

            val first = ByteArray(minOf(CHUNK.toLong(), total).toInt())
            raf.readFully(first)
            val faked = MAGIC.indices.all { first[it] == MAGIC[it] }
            if (faked && first.size < REPLACE_HEADER.size) throw DecryptException("TM 文件头过短")
            if (faked) System.arraycopy(REPLACE_HEADER, 0, first, 0, 8)

            output.parentFile?.mkdirs()
            FileOutputStream(output).use { out ->
                out.write(first, 0, first.size)
                var written = first.size.toLong()
                val buf = ByteArray(CHUNK)
                while (true) {
                    val read = raf.read(buf)
                    if (read <= 0) break
                    out.write(buf, 0, read)
                    written += read
                    if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                }
            }
            onProgress(1f)

            return DecryptedAudio(file = output, containerExt = AudioSniffer.detectExt(output))
        }
    }
}
