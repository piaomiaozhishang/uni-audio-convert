# 音频转换器（Android · Kotlin · Compose · MD3）

本地音频格式转换工具：支持常见格式互转、加密音乐文件离线解密、批量转换。

## 技术栈

| 层 | 选型 | 说明 |
|---|---|---|
| UI | Jetpack Compose + Material 3 | 动态取色（Android 12+），三 Tab：转换 / 历史 / 设置 |
| 转码 | `dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.9` | FFmpeg 8.1 LTS，LGPL-3.0 |
| 解密 | 纯 Kotlin（`decrypt/`） | 无 JNI、不联网、流式处理不占内存 |
| 播放 | Media3 ExoPlayer | 转换前后试听 |
| 存储 | MediaStore（API 29+）/ 公共目录 | 输出到 `Music/MusicConvert` |
| 历史 | 裸 SQLite | 不引 Room/KSP，减少构建链风险 |
| 设置 | DataStore Preferences | — |

## 为什么不用原来的 ffmpeg-kit

`com.arthenica:ffmpeg-kit-*` 已于 2025-01 归档、2025-04 从 Maven Central 下架，现在无法解析依赖。
本项目用社区维护的 `dev.ffmpegkit-maintained`，包名 / 类名 / API 与原版完全一致，只换了 group ID。

注意该构建只发布 **arm64-v8a + x86_64**，没有 armeabi-v7a。32 位老设备装不上。

## 支持情况（重要）

### 普通格式转换 ✅

输出：MP3 / M4A(AAC) / FLAC / WAV / ALAC / OGG / OPUS / AIFF / WMA
输入：由 FFmpeg 解码器决定（mp3、flac、wav、ape、m4a、aac、ogg、opus、wma、aiff、dsf 等）

### 加密格式解密

| 格式 | 状态 | 说明 |
|---|---|---|
| 网易云 `.ncm` | ✅ | 含内嵌标签 + 封面解析，64KB 流式解密 |
| QQ音乐 `.qmc*` / `.mflac*` / `.mgg*` / `.bkc*` / `.tkm` / `.mmp4` | ✅ | QMCv2：footer 解析 → TEA-CBC 派生密钥 → map/RC4/static 三套流密码 |
| QQ音乐 `.tm0` / `.tm2` / `.tm3` / `.tm6` | ✅ | 还原 M4A 的 ftyp 头，MP3 直通 |
| 酷狗 `.kgm` / `.kgma` / `.vpr` | ✅ | crypto v3，酷狗魔改 MD5 |
| 酷我 `.kwm` / `.kwma` | ✅ | 头部 0x400，32 字节 mask |
| 酷狗 `.kgg` / KGM v5 | ❌ | 密钥在客户端 `KGMusicV3.db`（SQLCipher），离线无解 |
| QQ新版 `STag` / `musicex`（`.mflac0.flac` 等） | ⚠️ | 文件里没密钥，需在设置页「导入密钥」后解密 |

算法实现均移植自 `Brayn-Studio/freenote`（MIT），其代码逐字节对齐 unlock-music 的 `algo/*`。

## 构建

```bash
# 需要 JDK 17 + Android SDK 36
./gradlew :app:assembleDebug

# 解密器的往返 / 边界测试
./gradlew :app:testDebugUnitTest
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

### 验证方式

- **单元测试**（14 个）：用对称算法造密文 → 走完整 `decrypt()` → 逐字节比对明文；
  另有 ffmpeg 参数构造的断言（封面分支绝不能再带 `-vn`）。
  加解密夹具与解密实现规模不同（加密一次成型、解密 64KB 分块），能抓出 keystream 相位错位这类 bug。
- **设备端实测**：把一个 3 秒 WAV 用 QMC 静态密码加密成 `.qmc`，App 解密 + 转码后，
  产物与「WAV 直接转 MP3」的 MD5 **完全一致**（`873287c4…`）。这证明解密输出是逐字节正确的。
- **队列恢复**：入队后 `force-stop` 再启动，任务仍在且能继续转换（SAF URI 持久权限已申请）。
- **未覆盖**：QMC 的 TEA/map/RC4 分支没有官方二进制测试向量，正确性依赖逐字节对照参考实现的源码；
  封面内嵌只有参数级测试，没有带封面的真实样本实测。

### 其他能力

- **队列持久化**：未完成任务存 `filesDir/pending_queue.json`，进程被杀后重启恢复为待处理。
- **自定义输出目录**：设置页「输出位置」可指定任意 SAF 目录，未设置或目录失效时回退到 `Music/MusicConvert`。
- **封面内嵌**：解密出的封面以 `attached_pic` 写进 MP3/FLAC/M4A/OGG；WAV/AIFF/Opus/WMA 自动跳过。
- **密钥导入**：支持 JSON、`文件名<TAB>密钥` 文本，以及 QQ 音乐 `MMKVStreamEncryptId` 二进制文件。
- **同格式直通**：解密结果的容器与目标格式一致时（mp3→MP3、flac→FLAC、wav→WAV）直接搬文件，
  跳过 ffmpeg 重编码。实测 60 秒音频：重编码 0.67s → 直通 0.36s，且输出与源文件字节完全一致。
  设置页可关闭（关闭后按选定码率重新编码）。
- **关于页**：开源地址（GitHub）与检查更新（Gitee）可直接点击跳转。
- **清空**：首页有已完成任务时显示「已完成 N 项 / 清空」，一键清掉不再需要的结果卡片。
- **首页四个标签**：转换 / 历史 / 关于 / 设置，用 `HorizontalPager` 实现，支持手势左右滑。
- **支持格式页**：首页右上角入口，分「通用格式」「音乐平台」两页，列出可输出的 9 种格式、
  可读取的常见容器，以及各平台加密格式的支持状态与技术说明。
- **首次启动协议**：使用条款与免责声明，同意后记录在 SharedPreferences（带版本号，
  条款改动后递增 VERSION 会重新征求同意）。
- **关于页**：应用图标、版本、检查更新、下载页、GitHub 仓库入口、完整免责声明。

### 代理

Gradle 不读系统代理，若在受限网络下构建需显式传参：

```bash
./gradlew :app:assembleDebug -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897
```

## 代码结构

```
app/src/main/java/com/musicconvert/
├── MusicConvertApp.kt          Application + 手动依赖容器
├── MainActivity.kt
├── data/
│   ├── model/                  OutputFormat（含 ffmpeg 参数）、ConvertTask
│   ├── HistoryStore.kt         SQLite
│   └── SettingsStore.kt        DataStore
├── decrypt/
│   ├── MusicDecryptor.kt       格式识别 + 分发
│   └── NcmDecryptor.kt         NCM 算法（流式）
├── ffmpeg/FfmpegRunner.kt      命令拼接 + 进度回调
├── service/
│   ├── ConvertEngine.kt        串行队列，解密→转码→落盘→历史
│   └── ConvertService.kt       前台服务 + 通知
├── player/PlayerHolder.kt
├── ui/                         Compose 页面
└── util/
```

## 免责声明

本工具仅用于处理用户**已合法获取**的本地音频文件，所有解密与转码均在本机完成，不联网、不上传。
请勿用于侵犯版权、商业分发或违反当地法律法规的用途。
