package com.musicconvert.decrypt

import java.io.File
import java.io.RandomAccessFile

/**
 * 加密容器类型与解密分发。
 * 不能解的不要假装能解——输出乱码比报错更糟。
 */
enum class EncryptedKind(
    val tag: String,
    val label: String,
    val supported: Boolean,
) {
    NCM("NCM", "网易云 .ncm", true),
    QMC("QMC", "QQ音乐 .qmc/.mflac/.mgg", true),
    TM("TM", "QQ音乐 .tm*", true),
    KGM("KGM", "酷狗 .kgm/.vpr", true),
    KGG("KGG", "酷狗 .kgg", false),
    KWM("KWM", "酷我 .kwm", true),
    UNKNOWN("", "未知格式", true),
}

object MusicDecryptor {

    private val QMC_EXTS = setOf(
        "qmc", "qmc0", "qmc2", "qmc3", "qmc4", "qmc6", "qmc8",
        "qmcflac", "qmcogg", "tkm",
        "bkcmp3", "bkcm4a", "bkcflac", "bkcwav", "bkcape", "bkcogg", "bkcwma",
        "666c6163", "6d7033", "6f6767", "6d3461", "776176", "mmp4",
        "mflac", "mflac0", "mflac1", "mflaca", "mflach", "mflacl", "mflacm",
        "mgg", "mgg0", "mgg1", "mgga", "mggh", "mggl", "mggm",
    )

    private val TM_EXTS = setOf("tm0", "tm2", "tm3", "tm6")

    /**
     * 识别加密容器。魔数优先（改名也能认出来），QMC/TM 没有魔数，只能靠扩展名。
     */
    fun sniff(name: String, file: File?): EncryptedKind {
        val lower = name.lowercase()

        if (file != null) {
            val head = ByteArray(16)
            val n = try {
                RandomAccessFile(file, "r").use { it.read(head) }
            } catch (_: Exception) {
                0
            }
            if (n >= 10 && NcmDecryptor.isNcmHeader(head)) return EncryptedKind.NCM
            if (n >= 16 && KgmDecryptor.matchesMagic(head)) return EncryptedKind.KGM
            if (n >= 16 && KwmDecryptor.matchesMagic(head)) return EncryptedKind.KWM
        }

        if (lower.endsWith(".ncm")) return EncryptedKind.NCM

        val segments = lower.split('.').drop(1)
        if (segments.any { it in QMC_EXTS }) return EncryptedKind.QMC
        if (segments.any { it in TM_EXTS }) return EncryptedKind.TM
        if (lower.endsWith(".kgm") || lower.endsWith(".kgma") || lower.endsWith(".vpr")) {
            return EncryptedKind.KGM
        }
        if (lower.endsWith(".kgg")) return EncryptedKind.KGG
        if (lower.endsWith(".kwm") || lower.endsWith(".kwma")) return EncryptedKind.KWM
        return EncryptedKind.UNKNOWN
    }

    /**
     * 解密到 [output]，扩展名由调用方决定（内容识别由 AudioSniffer 负责）。
     * @param fileName   原始文件名，QMC 用它匹配导入的密钥
     * @param ekeyLookup 外部密钥查找（STag / musicex 文件），返回 base64 EKey
     */
    fun decrypt(
        input: File,
        output: File,
        kind: EncryptedKind,
        fileName: String = input.name,
        ekeyLookup: ((List<String>) -> String?)? = null,
        onProgress: (Float) -> Unit = {},
    ): DecryptedAudio = when (kind) {
        EncryptedKind.NCM -> NcmDecryptor.decrypt(input, output, onProgress)
        EncryptedKind.QMC -> QmcDecryptor.decrypt(input, output, fileName, ekeyLookup, onProgress)
        EncryptedKind.TM -> TmDecryptor.decrypt(input, output, onProgress)
        EncryptedKind.KGM -> KgmDecryptor.decrypt(input, output, onProgress)
        EncryptedKind.KWM -> KwmDecryptor.decrypt(input, output, onProgress)
        EncryptedKind.KGG -> throw DecryptException(
            "酷狗 .kgg 的密钥存在客户端数据库（KGMusicV3.db），无法离线解密"
        )

        EncryptedKind.UNKNOWN -> throw DecryptException("无法识别的加密格式")
    }
}
