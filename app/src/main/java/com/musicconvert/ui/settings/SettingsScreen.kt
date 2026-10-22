package com.musicconvert.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musicconvert.MusicConvertApp
import com.musicconvert.data.AppSettings
import com.musicconvert.data.model.OutputFormat
import com.musicconvert.util.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val container = (context.applicationContext as MusicConvertApp).container
    val store = container.settingsStore
    val scope = rememberCoroutineScope()

    val settings by store.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrDefault("0.1.0")
    }

    var ekeyCount by remember { mutableIntStateOf(container.ekeyStore.size) }

    var checking by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf("当前 v${versionName ?: "0.1.0"}") }
    var newVersion by remember { mutableStateOf<String?>(null) }

    val outputLabel = remember(settings.outputTreeUri) {
        settings.outputTreeUri?.let { raw ->
            Uri.decode(raw).substringAfterLast(':').substringAfterLast('/')
                .ifBlank { "自定义目录" }
        } ?: "Music/MusicConvert"
    }

    val dirPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            scope.launch { store.setOutputTreeUri(uri.toString()) }
            Toast.makeText(context, "输出目录已更新", Toast.LENGTH_SHORT).show()
        }
    }
    val ekeyPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { container.ekeyStore.importFrom(uri, context) }
            }
            result.fold(
                onSuccess = { count ->
                    ekeyCount = container.ekeyStore.size
                    Toast.makeText(context, "已导入 $count 条密钥", Toast.LENGTH_SHORT).show()
                },
                onFailure = { e ->
                    Toast.makeText(context, "导入失败：${e.message}", Toast.LENGTH_LONG).show()
                },
            )
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp)) {
            Text(
                text = "设置",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "转换的默认参数与行为",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionTitle("转换默认值")
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        ) {
            Column {
                DropdownRow(
                    title = "默认输出格式",
                    value = settings.defaultFormat.label,
                    options = OutputFormat.entries,
                    labelOf = { it.label },
                    onSelect = { scope.launch { store.setFormat(it) } },
                )
                if (settings.defaultFormat.supportsBitrate) {
                    DropdownRow(
                        title = "默认码率",
                        value = "${settings.defaultBitrate} kbps",
                        options = OutputFormat.BITRATES,
                        labelOf = { "$it kbps" },
                        onSelect = { scope.launch { store.setBitrate(it) } },
                    )
                }
                SwitchRow(
                    title = "保留原始标签",
                    subtitle = "把源文件的标题/歌手/专辑写进新文件",
                    checked = settings.keepOriginalTags,
                    onCheckedChange = { scope.launch { store.setKeepOriginalTags(it) } },
                )
                SwitchRow(
                    title = "写入解密信息",
                    subtitle = "加密文件解密后，用文件内嵌的歌名、歌手、专辑重命名",
                    checked = settings.keepMetadata,
                    onCheckedChange = { scope.launch { store.setKeepMetadata(it) } },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { dirPicker.launch(null) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "输出位置",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = outputLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "更改",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                SwitchRow(
                    title = "同格式直接导出",
                    subtitle = "源音频与目标格式一致时跳过重编码，最快且不损失音质（会忽略码率设置）",
                    checked = settings.preferDirectCopy,
                    onCheckedChange = { scope.launch { store.setPreferDirectCopy(it) } },
                )
            }
        }

        SectionTitle("通知")
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        ) {
            SwitchRow(
                title = "完成后提醒",
                subtitle = "批量任务结束时发一条通知",
                checked = settings.notifyOnFinish,
                onCheckedChange = { scope.launch { store.setNotifyOnFinish(it) } },
            )
        }

        SectionTitle("QQ 音乐密钥")
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "已导入 $ekeyCount 条",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "新版 .mflac0 / .mgg1 的密钥不在文件里，需要从这里导入才能解密。" +
                        "支持 JSON（{\"文件名\": \"密钥\"}）或每行「文件名<TAB>密钥」的文本。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { ekeyPicker.launch(arrayOf("*/*")) },
                    ) {
                        Text("导入密钥文件")
                    }
                    if (ekeyCount > 0) {
                        TextButton(
                            onClick = {
                                container.ekeyStore.clear()
                                ekeyCount = 0
                            },
                        ) {
                            Text("清空")
                        }
                    }
                }
            }
        }

        SectionTitle("外观")
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        ) {
            SwitchRow(
                title = "跟随壁纸取色",
                subtitle = "由系统壁纸生成主题色，关闭则使用内置暖橙配色",
                checked = settings.dynamicColor,
                onCheckedChange = { scope.launch { store.setDynamicColor(it) } },
            )
        }

        SectionTitle("关于")
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                InfoLine("版本", versionName ?: "0.1.0")
                InfoLine("转换引擎", "FFmpeg 8.1（本地）")
                Spacer(Modifier.height(8.dp))
                GithubRow { openUrl(context, URL_GITHUB) }
                UpdateRow(
                    status = updateStatus,
                    checking = checking,
                    onClick = {
                        if (checking) return@UpdateRow
                        scope.launch {
                            checking = true
                            updateStatus = "正在检查…"
                            when (val r = UpdateChecker.check(versionName ?: "0.1.0")) {
                                is UpdateChecker.Result.Newer -> {
                                    updateStatus = "发现 ${r.version}"
                                    newVersion = r.version
                                }

                                UpdateChecker.Result.Latest -> updateStatus = "已是最新"
                                is UpdateChecker.Result.Failed -> updateStatus = r.message
                            }
                            checking = false
                        }
                    },
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "所有转换与解密都在本机完成，不联网、不上传。仅用于处理你已合法获取的音频文件，请勿用于传播受版权保护的内容。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    val pending = newVersion
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { newVersion = null },
            title = { Text("发现新版本") },
            text = { Text("$pending 已发布，当前版本 v${versionName ?: "0.1.0"}。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        newVersion = null
                        openUrl(context, UpdateChecker.RELEASES_PAGE)
                    },
                ) { Text("去下载") }
            },
            dismissButton = {
                TextButton(onClick = { newVersion = null }) { Text("以后再说") }
            },
        )
    }
}

private const val URL_GITHUB = "https://github.com/piaomiaozhishang/uni-audio-convert"
private const val URL_GITEE = "https://gitee.com/piaomiaozhishang/uni-audio-convert"

@Composable
private fun GithubRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = GithubVector,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "开源地址",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "GitHub",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun UpdateRow(status: String, checking: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !checking, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "检查更新",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (checking) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** GitHub 标记（24×24 的官方 path，用 PathParser 转成 Compose 矢量）。 */
private val GithubVector: ImageVector by lazy {
    ImageVector.Builder(
        name = "GitHub",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(GITHUB_PATH).toNodes(),
            fill = SolidColor(Color.Black),
        )
    }.build()
}

private const val GITHUB_PATH =
    "M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 " +
        "0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7" +
        "c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 " +
        "3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 " +
        "1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 " +
        "3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 " +
        "3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 " +
        "0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12"

private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure {
        Toast.makeText(context, "没有可用的浏览器", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 28.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun <T> DropdownRow(
    title: String,
    value: String,
    options: List<T>,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier
                    .size(20.dp)
                    .padding(start = 4.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(labelOf(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
