# 正念铃 · 番茄钟

一个 Android 应用，里面放了两个互不相干的功能：正念铃和番茄钟。想用哪个用哪个，它们各记各的状态。

图标是一只奶蛙

Kotlin + Jetpack Compose + Material 3，单模块，minSdk 26 / targetSdk 35 / compileSdk 35。

<img src="docs/naiva-icon.png" width="200" alt="奶蛙图标">

## 下载

到 [Releases](https://github.com/ONEmengred/MindBellPomodoro/releases/latest) 下 `MindBellPomodoro-1.0.3-release.apk`，装上就能用，Android 8.0 及以上。

不想装现成的包可以自己编译，见文末。

## 功能

正念铃有三个模式：

- 定时提醒：按固定间隔提醒（5/10/15/20/25/30/60 分钟预设，或自己填 1–180 分钟），可以限定每天哪段时间才提醒、哪段时间不打扰。
- 冥想练习：设一个总时长，配开始铃、间隔铃、结束铃和开始前的准备时间，中途可以提前结束，练完会记下实际练了多久。
- 正念盲盒：在最短和最长分钟之间随机抽一个时长，到点响铃，然后进入几分钟的觉察呼吸，呼吸结束再抽下一次。可以设整场练习的上限，也可以开盲盒模式把倒计时数字藏起来。

三个模式都支持开始、暂停、继续、停止，铃声、音量、振动都能调。

番茄钟就是常见的那套：专注 25、短休息 5、长休息 15，都能改；可以设几个番茄之后进长休息、要不要自动进入下一阶段、当前在做什么任务；结束时响铃、发通知、振动，并记下番茄数和专注时长。

统计页有累计的冥想时长、番茄数、专注总时长，以及一条条历史记录。所有设置和历史都存在本机（DataStore + Room），不联网、不用账号。

所有时间都能直接输入数字改，不用一直点加减号。

## 几个实现上的选择

**计时只存时间戳。** 不存「还剩多少秒」，只存结束时刻、下次响铃时刻这些绝对时间点，每秒拿当前时间比一下。所以进程被杀、手机重启、锁屏睡了半小时，醒来算一次就回到正确位置，不会因为漏掉几秒而整体跑偏。

**四类会话各自独立。** 定时提醒、冥想、正念盲盒、番茄钟在 DataStore 里各占一个 key，同时跑也不会互相覆盖。

**暂停是平移时间戳。** 暂停时记下暂停时刻，恢复时把这个会话所有未来的时间点统一往后推暂停的那段时长，暂停多久都吃不掉剩余时间。

**铃声是算出来的。** APK 里没有音频文件，风铃、颂钵、轻叮咚、寺院钟、木鱼、收尾钟声都由 `audio/ToneSynth.kt` 实时合成（正弦分音 + 升余弦包络 + 低通滤波，44.1 kHz / PCM16）。体积小，没有版权问题，音色也能随时改参数。想换成真实录音的话，把文件放进 `app/src/main/res/raw/`（比如 `temple_bell.ogg`），`BellPlayer` 会优先播它。

**后台响铃做了三层。** Android 会冻结后台进程，Doze 会拖慢轮询，不少厂商 ROM 还会直接杀进程，所以准点响铃不能只靠一个每秒循环：

1. 前台服务每秒 tick，常驻通知里直接显示还剩多久（`专注 12:34`），盲盒模式下按设置隐藏数字。
2. 每次 tick 之后把最近的待触发时刻交给 `AlarmManager.setAlarmClock`，它精确、不受 Doze 影响、进程被杀也会触发；失败时依次降级到 `setExactAndAllowWhileIdle`、`setAndAllowWhileIdle`。闹钟到点由 `AlarmReceiver` 补跑一次 tick，再排下一个。
3. 每次铃响都发一条高优先级通知并振动，媒体音量小或者手机静音时不至于完全没感知。

反过来，如果某个铃在 App 被冻结期间早就该响了（迟到超过 20 秒），重新打开 App 时不会补响，只把状态推到正确位置。免得一进 App 被突然的一声铃吓到。

统计页顶部有一张自检卡片，列着通知权限、铃响渠道、精确闹钟、电池优化、媒体音量、后台服务是否在跑、闹钟排到几点、最近一次铃响和最近一次报错，还有最近十几行运行日志。不响的时候先看这里，或者点一下「10 秒后测试闹钟」把 App 退到后台，十秒后该响就说明整条通路是通的。

## 目录

```
app/src/main/java/com/mindfulfocus/
├── MainActivity.kt              入口
├── MindFocusApp.kt              Application，持有 AppContainer
├── AppContainer.kt              手写依赖容器
├── core/
│   ├── SessionType.kt           会话类型、状态、SessionSnapshot
│   ├── Settings.kt              各模式设置与时间窗
│   ├── TimerEngine.kt           计时逻辑，纯函数，按时间戳推进并产生事件
│   ├── DiagLog.kt               运行日志，落 diag.log
│   └── SessionController.kt     start / pause / resume / stop 与每秒 tickAll
├── data/
│   ├── AppDataStore.kt          设置与会话快照持久化
│   └── History.kt               Room：冥想记录、番茄记录
├── audio/
│   ├── ToneSynth.kt             实时合成铃声
│   └── BellPlayer.kt            SoundPool 与振动
├── service/
│   ├── TimerForegroundService.kt 前台服务，每秒 tick，常驻通知带倒计时
│   ├── AlertNotifier.kt         通知
│   ├── AlarmScheduler.kt        精确闹钟
│   ├── AlarmReceiver.kt         闹钟到点补跑一次 tick
│   └── BootReceiver.kt          开机恢复
└── ui/
    ├── AppRoot.kt               底部导航与 NavHost
    ├── theme/Theme.kt           配色
    ├── components/              倒计时环、分段标签、数字输入步进器
    ├── diagnostics/             自检卡片
    ├── bell/                    正念铃
    ├── pomodoro/                番茄钟
    └── history/                 统计

app/src/test/java/com/mindfulfocus/core/TimerEngineTest.kt   计时逻辑单元测试，20 个用例
```

## 构建

需要 JDK 17 以上和 Android SDK 35。

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
./gradlew :app:testDebugUnitTest
```

仓库里没放 `gradle-wrapper.jar`，只有版本号。用命令行时先 `gradle wrapper --gradle-version 8.9` 生成一次，或者直接用系统装的 Gradle 8.9。

版本：AGP 8.7.2 / Kotlin 2.0.21 / Compose BOM 2024.10.01 / KSP 2.0.21-1.0.28 / Gradle 8.9。

### 签名

签名文件不进仓库。要打 release 包，在工程根目录建一个 `keystore.properties`（已在 .gitignore 里排除）：

```properties
storeFile=/绝对路径/release.jks
storePassword=******
keyAlias=mindbell
keyPassword=******
```

没有这个文件也能构建，只是出来的 release 包没签名。

### CI

`.github/workflows/android.yml` 在每次 push 或 PR 到 main 时跑单元测试和 `assembleDebug`，把 APK 和测试报告作为 artifact 上传。

想让 CI 顺便产出签名的 release 包，去仓库 Settings → Secrets 配 `KEYSTORE_BASE64`（keystore 文件的 base64 文本）、`KEYSTORE_STORE_PASSWORD`、`KEYSTORE_KEY_ALIAS`、`KEYSTORE_KEY_PASSWORD`，工作流会自动多跑一步。

## 状态

本机用 JDK 21 + Android SDK 35 + Gradle 8.9 编译通过，`assembleDebug`、`assembleRelease`、`testDebugUnitTest`（20 个用例）都没问题，release 包用独立 keystore 签名后发在 Releases。

## 许可

[MIT](LICENSE) © 2026 ONEmeng
