# 正念铃 · 番茄钟（MindBellPomodoro）

一个安静的 Android 计时器，里面装了两个**互相独立**的功能：**正念铃** 和 **番茄钟**。
吉祥物是一只叫 **奶蛙** 的黄色小家伙。

Kotlin + Jetpack Compose + Material 3，单模块工程，`minSdk 26` / `targetSdk 35` / `compileSdk 35`。

<p align="center">
  <img src="docs/naiva-icon.png" width="200" alt="奶蛙图标">
</p>

---

## 功能

### 模块 A · 正念铃（三个模式）

| 模式 | 说明 |
| --- | --- |
| **定时提醒** | 固定间隔（5/10/15/20/25/30/60 分钟预设，或自定义 1–180 分钟）提醒，可设每日启用时段与免打扰时段 |
| **冥想练习** | 自定义练习时长，开始铃 / 间隔铃 / 结束铃，开始前准备时间，可提前结束，记录实际练习时长 |
| **正念盲盒** | 在「最短–最长」分钟之间随机抽一个时长，静候铃响 → 响铃 → 觉察呼吸 → 重新抽；可设会话总上限、盲盒模式（隐藏倒计时） |

三个模式都支持开始 / 暂停 / 继续 / 停止，铃声、音量、振动可调。

### 模块 B · 番茄钟

专注 25 / 短休息 5 / 长休息 15（都可改），可设几个番茄后长休息、是否自动进入下一阶段、当前任务名，结束时铃声 + 通知 + 振动，记录番茄数与专注时长。

### 其他

- 统计页：累计冥想时长、番茄数、专注总时长，以及逐条历史记录
- 全部设置与历史**本地保存**（DataStore + Room），不联网、无账号
- 所有时长都能**直接输入数字**，不用狂点加减号

---

## 声音是算出来的，不是素材

APK 里没有任何音频文件：风铃、颂钵、轻叮咚、寺院钟、木鱼、收尾钟声全部由
`audio/ToneSynth.kt` 在运行时用正弦分音 + 升余弦包络 + 低通滤波实时合成（44.1 kHz / PCM16）。
好处是体积小、没有版权问题，而且音色随时能调参数。

> 想换成真实录音，把文件放进 `app/src/main/res/raw/`（如 `temple_bell.ogg`），
> `BellPlayer` 会优先播放它。

---

## 计时为什么靠时间戳

不存「还剩多少秒」，只存**绝对时间戳**（`phaseEndEpochMs` / `nextBellEpochMs` / `pendingBellEpochs`）。
每秒只做「读时钟 → 比较 → 触发」，因此：

- 进程被杀 → 服务 `START_STICKY` 重启后一算就准
- 锁屏 / 休眠 → 醒来一算就准，错过的铃声会补响
- 重启手机 → `BootReceiver` 恢复继续

四种场景（定时提醒 / 冥想 / 正念盲盒 / 番茄钟）各自在 DataStore 里占一个**独立 key**
（`session_snapshot_<TYPE>`），所以可以同时跑而互不覆盖。

暂停时记录 `pausedAtEpochMs`，恢复时把该会话所有未来时间戳统一加上暂停时长，暂停多久都不会吃掉剩余时间。

---

## 目录结构

```
app/src/main/java/com/mindfulfocus/
├── MainActivity.kt              入口（通知权限）
├── MindFocusApp.kt              Application，持有 AppContainer
├── AppContainer.kt              手写依赖容器
├── core/
│   ├── SessionType.kt           会话类型 / 状态 / SessionSnapshot（核心状态模型）
│   ├── Settings.kt              各模式设置 + 时间窗
│   ├── TimerEngine.kt           ★计时大脑：纯函数，按时间戳推进并产生事件
│   └── SessionController.kt     命令入口：start/pause/resume/stop + 每秒 tickAll
├── data/
│   ├── AppDataStore.kt          设置与会话快照持久化
│   └── History.kt               Room：冥想记录、番茄记录
├── audio/
│   ├── ToneSynth.kt             实时合成所有铃声
│   ├── BellPlayer.kt            SoundPool + 振动
│   └── EasterEggPlayer.kt       彩蛋音效
├── service/
│   ├── TimerForegroundService.kt 前台服务，每秒 tick，常驻通知
│   └── BootReceiver.kt           开机恢复
└── ui/
    ├── AppRoot.kt               底部导航 + NavHost
    ├── theme/Theme.kt           暖黄 / 白配色
    ├── components/              倒计时环、分段标签、数字输入步进器
    ├── bell/                    正念铃（定时提醒 / 冥想 / 正念盲盒）
    ├── pomodoro/                番茄钟
    └── history/                 统计

app/src/test/java/com/mindfulfocus/core/TimerEngineTest.kt   计时引擎单元测试（纯 JVM，11 个用例）
```

---

## 构建

需要 JDK 17+ 与 Android SDK 35。

```bash
./gradlew :app:assembleDebug      # 打 debug APK
./gradlew :app:test               # 跑计时引擎单元测试
```

首次用 Android Studio 打开时，若缺少 Gradle wrapper，选择从 `gradle-wrapper.properties` 读取，
先执行一次 `gradle wrapper --gradle-version 8.9` 生成 `gradle-wrapper.jar`。

关键版本：AGP 8.7.2 / Kotlin 2.0.21 / Compose BOM 2024.10.01 / KSP 2.0.21-1.0.28 / Gradle 8.9。

---

## 状态

代码已在本机用 JDK 21 + Android SDK 35 + Gradle 8.9 编译通过，`assembleDebug` 与
`testDebugUnitTest`（11 个用例）均通过，产出的 debug APK 可直接安装。

---

## 许可

[MIT](LICENSE) © 2026 ONEmeng

欢迎拿去改、拿去用，也欢迎提 Issue / PR。

> 顺带说一句：App 里藏了一个小彩蛋，自己找找看 🙂
