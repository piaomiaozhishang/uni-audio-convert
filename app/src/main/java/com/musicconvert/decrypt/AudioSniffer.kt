package com.musicconvert.decrypt

import java.io.File
import java.io.RandomAccessFile

/**
 * 解密出来的裸流没有容器标签，只能靠魔数猜真实扩展名。
 * ffmpeg 靠内容识别，所以这个值主要用于文件命名和 UI 展示。
 */
object AudioSniffer {

    fun detectExt(file: File): String {
        val head = ByteArray(16)
        val n = try {
            RandomAccessFile(file, "r").use { it.read(head) }
        } catch (_: Exception) {
            return "bin"
        }
        return detectExt(head, n)
    }

    fun detectExt(head: ByteArray, length: Int = head.size): String {
        if (length < 4) return "bin"
        fun at(i: Int) = head[i].toInt() and 0xFF
        return when {
            at(0) == 0x66 && at(1) == 0x4C && at(2) == 0x61 && at(3) == 0x43 -> "flac"
            at(0) == 0x4F && at(1) == 0x67 && at(2) == 0x67 && at(3) == 0x53 -> "ogg"
            at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 -> "wav"
            length >= 5 && at(0) == 0x23 && at(1) == 0x21 && at(2) == 0x41 && at(3) == 0x4D -> "ape"
            length >= 8 && at(4) == 0x66 && at(5) == 0x74 && at(6) == 0x79 && at(7) == 0x70 -> "m4a"
            at(0) == 0x49 && at(1) == 0x44 && at(2) == 0x33 -> "mp3"
            at(0) == 0xFF && (at(1) and 0xE0) == 0xE0 -> "mp3"
            else -> "mp3"
        }
    }
}
