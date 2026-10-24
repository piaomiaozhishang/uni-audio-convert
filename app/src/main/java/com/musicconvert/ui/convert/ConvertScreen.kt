package com.musicconvert.ui.convert

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musicconvert.MusicConvertApp
import com.musicconvert.data.model.ConvertTask
import com.musicconvert.data.model.OutputFormat
import com.musicconvert.data.model.TaskStatus
import com.musicconvert.decrypt.EncryptedKind
import com.musicconvert.service.ConvertService
import com.musicconvert.util.formatSize
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.first

@Composable
fun ConvertScreen(
    onRequestNotificationPermission: () -> Unit,
    onOpenFormats: () -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as MusicConvertApp).container
    val engine = container.engine

    val tasks by engine.queue.collectAsStateWithLifecycle()
    val running by engine.running.collectAsStateWithLifecycle()

    var formatName by rememberSaveable { mutableStateOf(OutputFormat.MP3.name) }
    var bitrate by rememberSaveable { mutableIntStateOf(320) }
    var initialized by rememberSaveable { mutableStateOf(false) }
    val format = remember(formatName) { OutputFormat.valueOf(formatName) }

    LaunchedEffect(Unit) {
        if (!initialized) {
            val settings = container.settingsStore.settings.first()
            formatName = settings.defaultFormat.name
            bitrate = settings.defaultBitrate
            initialized = true
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) engine.enqueue(uris, format, bitrate)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 12.dp, top = 20.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "音频转换",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "全格式互转 · 加密文件本地解密 · 不上传",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onOpenFormats) {
                Icon(
                    imageVector = Icons.Default.List,
                    contentDescription = "支持的格式",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (tasks.isEmpty()) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                EmptyState(onPick = { picker.launch(arrayOf("*/*")) })
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(tasks, key = { it.id }) { task ->
                    TaskCard(task = task, onRemove = { engine.remove(task.id) })
                }
            }
        }

        BottomPanel(
            format = format,
            bitrate = bitrate,
            running = running,
            taskCount = tasks.size,
            finishedCount = tasks.count {
                it.status == TaskStatus.DONE || it.status == TaskStatus.FAILED
            },
            onFormatChange = { formatName = it.name },
            onBitrateChange = { bitrate = it },
            onPick = { picker.launch(arrayOf("*/*")) },
            onStart = {
                onRequestNotificationPermission()
                engine.start()
                ConvertService.start(context)
            },
            onStop = { engine.stop() },
            onClear = { engine.clearFinished() },
        )
    }
}

@Composable
private fun EmptyState(onPick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(32.dp),
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(44.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = "还没有添加文件",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "支持 mp3 / flac / wav / ape / m4a 等常见格式，\n也能直接选中 .ncm 等加密文件",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        FilledTonalButton(onClick = onPick) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("选择文件")
        }
    }
}

@Composable
private fun TaskCard(task: ConvertTask, onRemove: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FormatBadge(task)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = task.sourceName,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = statusText(task),
                        style = MaterialTheme.typography.bodySmall,
                        color = when (task.status) {
                            TaskStatus.FAILED -> MaterialTheme.colorScheme.error
                            TaskStatus.DONE -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (task.status == TaskStatus.DONE) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                } else if (!task.isRunning) {
                    IconButton(onClick = onRemove) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "移除",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (task.isRunning) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { task.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                )
            }
        }
    }
}

@Composable
private fun FormatBadge(task: ConvertTask) {
    val encrypted = task.isEncrypted
    val container = if (encrypted) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (encrypted) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = if (encrypted) {
        task.kind.tag
    } else {
        task.sourceName.substringAfterLast('.', "?").uppercase().take(4)
    }

    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = content,
        )
    }
}

private fun statusText(task: ConvertTask): String {
    val size = formatSize(task.sizeBytes)
    return when (task.status) {
        TaskStatus.PENDING -> "$size · 等待中"
        TaskStatus.DECRYPTING -> "解密中 ${(task.progress * 100).toInt()}%"
        TaskStatus.CONVERTING -> "转换中 ${(task.progress * 100).toInt()}%"
        TaskStatus.DONE -> "$size · 已保存为 ${task.outputName ?: task.outputFormat.label}"
        TaskStatus.FAILED -> task.message ?: "失败"
    }
}

@Composable
private fun BottomPanel(
    format: OutputFormat,
    bitrate: Int,
    running: Boolean,
    taskCount: Int,
    finishedCount: Int,
    onFormatChange: (OutputFormat) -> Unit,
    onBitrateChange: (Int) -> Unit,
    onPick: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChoiceDropdown(
                    label = "格式",
                    valueText = format.label,
                    options = OutputFormat.entries,
                    optionText = { it.label },
                    enabled = !running,
                    onSelect = onFormatChange,
                    modifier = Modifier.weight(1f),
                )
                if (format.supportsBitrate) {
                    ChoiceDropdown(
                        label = "码率",
                        valueText = "${bitrate}k",
                        options = OutputFormat.BITRATES,
                        optionText = { "${it}k" },
                        enabled = !running,
                        onSelect = onBitrateChange,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (finishedCount > 0 && !running) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "已完成 $finishedCount 项",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onClear) {
                        Text("清空")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(
                    onClick = onPick,
                    modifier = Modifier.weight(1f),
                    enabled = !running,
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("添加文件")
                }
                Button(
                    onClick = if (running) onStop else onStart,
                    modifier = Modifier.weight(1f),
                    enabled = running || taskCount > 0,
                ) {
                    Text(if (running) "取消" else "开始转换")
                }
            }
        }
    }
}

@Composable
private fun <T> ChoiceDropdown(
    label: String,
    valueText: String,
    options: List<T>,
    optionText: (T) -> String,
    enabled: Boolean,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionText(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
