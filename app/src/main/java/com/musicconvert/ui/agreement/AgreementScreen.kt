package com.musicconvert.ui.agreement

import android.content.Context
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.musicconvert.R

/** 协议同意状态，按版本号存：条款改动后递增 VERSION 就会重新征求同意。 */
class AgreementStore(context: Context) {
    private val prefs = context.getSharedPreferences("agreement", Context.MODE_PRIVATE)

    val accepted: Boolean
        get() = prefs.getBoolean(KEY, false)

    fun accept() {
        prefs.edit().putBoolean(KEY, true).apply()
    }

    private companion object {
        const val VERSION = 1
        const val KEY = "accepted_v$VERSION"
    }
}

private const val PARAGRAPH_1 =
    "本项目仅作为音频/文件格式转换的技术工具使用，不提供、不存储、不分发任何版权内容。" +
        "软件本身不包含任何音乐作品，也无法获取任何音乐作品。"

private const val PARAGRAPH_2 =
    "使用者应确保对所处理文件拥有合法权利，或已获得权利人的授权。" +
        "不得将本工具用于侵犯著作权及相关权利人合法权益的行为，包括但不限于破解付费内容后传播、批量下载并二次分发。"

private const val PARAGRAPH_3 =
    "解密功能仅用于恢复你已合法获取的本地文件的播放能力。" +
        "因使用本工具产生的任何法律责任或纠纷，均由使用者自行承担，与项目作者无关。"

private const val PARAGRAPH_4 =
    "隐私：所有解密与转码都在你的设备本地完成，音频文件不会上传到任何服务器。" +
        "软件仅在「检查更新」时访问版本信息接口，不收集任何个人信息，不含广告与统计 SDK。"

private const val PARAGRAPH_5 =
    "继续使用即表示你已阅读、理解并同意上述条款。若不同意，请退出并卸载本应用。"

@Composable
fun AgreementScreen(onAccept: () -> Unit, onDecline: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
            ) {
                Spacer(Modifier.height(40.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.app_logo),
                        contentDescription = null,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(14.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "使用条款与免责声明",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "请在使用前阅读",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))

                listOf(PARAGRAPH_1, PARAGRAPH_2, PARAGRAPH_3, PARAGRAPH_4).forEach { p ->
                    Text(
                        text = p,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                }

                Text(
                    text = PARAGRAPH_5,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(28.dp))
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Button(
                    onClick = onAccept,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("同意并继续")
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    TextButton(onClick = onDecline) {
                        Text(
                            text = "不同意，退出",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
