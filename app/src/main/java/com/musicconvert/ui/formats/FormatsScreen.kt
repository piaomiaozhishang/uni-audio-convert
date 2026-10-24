package com.musicconvert.ui.formats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.musicconvert.data.model.OutputFormat

/** 输入侧：FFmpeg 能解码、且常见到值得列出来的容器。 */
private val INPUT_FORMATS = listOf(
    "MP3" to "最常见的通用有损格式，几乎所有平台都有",
    "FLAC" to "无损压缩，音频发烧友首选",
    "WAV" to "无损未压缩，体积大但兼容性最好",
    "M4A / AAC" to "苹果生态常用，同为有损但有更好的压缩效率",
    "ALAC" to "苹果的无损格式，容器同样是 m4a",
    "OGG / Vorbis" to "开源有损格式，常见于游戏与开源项目",
    "Opus" to "低码率下表现最好的有损格式",
    "WMA" to "微软老格式，部分老资源还在用",
    "APE" to "无损但压缩率极高，解码较吃 CPU",
    "AIFF" to "苹果的无压缩格式",
    "DSF / DFF" to "SACD 提取的 DSD 音频",
    "WavPack / TAK / TTA" to "较小众的无损格式",
)

private data class PlatformFormat(
    val platform: String,
    val suffixes: String,
    val status: String,
    val detail: String,
    val supported: Boolean,
)

private val PLATFORM_FORMATS = listOf(
    PlatformFormat(
        platform = "网易云音乐",
        suffixes = ".ncm",
        status = "完整支持",
        detail = "文件内自带密钥与元数据，可直接解出音频、歌名、歌手、专辑与封面。" +
            "解密后若目标格式与源一致，会直接导出而不重新编码。",
        supported = true,
    ),
    PlatformFormat(
        platform = "QQ 音乐",
        suffixes = ".qmc0 .qmc2 .qmc3 .qmc4 .qmc6 .qmc8 .qmcflac .qmcogg .qmcra .mflac .mgg .bkcmp3 .bkcflac .tkm .mmp4",
        status = "完整支持",
        detail = "QMCv2：从文件尾部解析 QTag，经 TEA-CBC 推出密钥，再按 map / RC4 / 静态表三套流密码之一解密。" +
            "文件里带歌曲信息时会一并写入输出文件。",
        supported = true,
    ),
    PlatformFormat(
        platform = "QQ 音乐（新版加密）",
        suffixes = ".mflac0 .mflac1 .mgg1 .mgg2 .mflach（STag / musicex 标记）",
        status = "需导入密钥",
        detail = "这类文件的音乐密钥不存在文件里，只保存在客户端的 MMKV 数据库。" +
            "可在「设置 → QQ 音乐密钥」导入 MMKVStreamEncryptId 文件或密钥清单后解密。",
        supported = true,
    ),
    PlatformFormat(
        platform = "QQ 音乐（自有格式）",
        suffixes = ".tm0 .tm2 .tm3 .tm6",
        status = "完整支持",
        detail = "去掉文件头后还原成标准 MP4/M4A 容器，MP3 内容则直接输出。",
        supported = true,
    ),
    PlatformFormat(
        platform = "酷狗音乐",
        suffixes = ".kgm .kgma .vpr",
        status = "完整支持",
        detail = "KGM v3 加密：用酷狗魔改过的 MD5 派生密钥，解出后再按容器还原扩展名。",
        supported = true,
    ),
    PlatformFormat(
        platform = "酷狗音乐（KGM v5）",
        suffixes = ".kgg",
        status = "不支持",
        detail = "密钥保存在客户端加密数据库 KGMusicV3.db（SQLCipher）中，纯离线无法获得，" +
            "因此不做支持，也不会输出损坏的文件。",
        supported = false,
    ),
    PlatformFormat(
        platform = "酷我音乐",
        suffixes = ".kwm .kwma",
        status = "完整支持",
        detail = "跳过 0x400 字节文件头，用 32 字节掩码按二进制密钥逐字节异或还原。",
        supported = true,
    ),
)

@Composable
fun FormatsScreen(onBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 24.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "支持的格式",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "通用格式互转，以及各平台加密文件的支持情况",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        TabRow(
            selectedTabIndex = tab,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                text = { Text("通用格式") },
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text("音乐平台") },
            )
        }

        if (tab == 0) {
            CommonTab()
        } else {
            PlatformTab()
        }
    }
}

@Composable
private fun CommonTab() {
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionLabel("可输出为（目标格式）")
        }
        items(OutputFormat.entries.toList()) { fmt ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = fmt.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = buildString {
                                append(".")
                                append(fmt.ext)
                                append(" · ")
                                append(if (fmt.lossless) "无损" else "有损")
                                if (fmt.supportsBitrate) append(" · 可调码率")
                                if (fmt.supportsCover) append(" · 支持封面")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Badge("可转换", true)
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
        item { SectionLabel("可读取（源文件）") }
        items(INPUT_FORMATS) { (name, desc) ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlatformTab() {
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "加密文件在本地解密，解密后可直接导出或转成任意目标格式。文件不会离开你的设备。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(PLATFORM_FORMATS) { p ->
            PlatformCard(p)
        }
    }
}

@Composable
private fun PlatformCard(p: PlatformFormat) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = p.platform,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Badge(p.status, p.supported)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = p.suffixes,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = p.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Badge(text: String, positive: Boolean) {
    val container = if (positive) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (positive) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = content,
        modifier = Modifier
            .background(container, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp),
    )
}
