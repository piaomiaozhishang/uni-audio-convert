package com.musicconvert.decrypt

/**
 * 腾讯 MMKV 数据文件的只读解析（移植自 Brayn-Studio/freenote，MIT）。
 *
 * 目标文件是 QQ 音乐的 `MMKVStreamEncryptId`：文件名 → base64 EKey 的明文 vault
 * （以空密钥打开，未加密）。
 *
 * 格式：
 *   [0..4)      actualSize : uint32 LE（有效 KV 区长度）
 *   [4..8)      ItemSizeHolder（跳过，不是 key）
 *   [8..4+size) 条目：重复 [varint keyLen][key UTF-8][varint valLen][val]
 * 存的 value 本身还是 `[varint rawLen][raw]`——key 和 raw ekey 之间夹着两层 varint。
 * 追加写入：同名 key 以后出现的为准；value 为空表示该 key 被删除。
 *
 * AES-128-CFB 加密的 vault 不支持（QQ 音乐这个库不加密）。
 */
object MmkvParser {

    fun parse(data: ByteArray): Map<String, String> {
        if (data.size < 8) throw IllegalArgumentException("文件太小，不是 MMKV")
        val actualSize = (data[0].toInt() and 0xFF) or
            ((data[1].toInt() and 0xFF) shl 8) or
            ((data[2].toInt() and 0xFF) shl 16) or
            ((data[3].toInt() and 0xFF) shl 24)
        val end = 4 + actualSize
        if (actualSize <= 0 || end > data.size) throw IllegalArgumentException("MMKV actualSize 非法")

        var pos = 4 + 4 // 跳过 header(4) + ItemSizeHolder(4)
        val raw = LinkedHashMap<String, ByteArray>()

        fun readVarint(): Int {
            var result = 0
            var shift = 0
            while (true) {
                if (pos >= end) throw IllegalArgumentException("varint 被截断")
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
                if (shift >= 35) throw IllegalArgumentException("varint 格式错误")
            }
            return result
        }

        fun readLenDelim(): ByteArray {
            val len = readVarint()
            if (len < 0 || pos + len > end) throw IllegalArgumentException("value 被截断")
            val out = data.copyOfRange(pos, pos + len)
            pos += len
            return out
        }

        while (pos < end) {
            val key = String(readLenDelim(), Charsets.UTF_8)
            val value = readLenDelim() // 仍是 [varint rawLen][raw]
            if (value.isEmpty()) raw.remove(key) else raw[key] = value
        }

        val out = LinkedHashMap<String, String>(raw.size)
        for ((k, v) in raw) {
            val ekey = getBytes(v) ?: continue
            out[k] = String(ekey, Charsets.ISO_8859_1)
        }
        return out
    }

    /** 去掉内层 varint 长度前缀，拿到原始值字节（等价 MMKV getBytes）。 */
    private fun getBytes(wrapped: ByteArray): ByteArray? {
        var p = 0
        var result = 0
        var shift = 0
        while (true) {
            if (p >= wrapped.size) return null
            val b = wrapped[p++].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift >= 35) return null
        }
        if (result < 0 || p + result > wrapped.size) return null
        return wrapped.copyOfRange(p, p + result)
    }
}
