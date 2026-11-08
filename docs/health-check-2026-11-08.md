# MindBellPomodoro 仓库体检报告

- 仓库：`ONEmengred/MindBellPomodoro`（public，默认分支 `main`，MIT）
- 体检时间：2026-11-08
- 体检基线：commit `4c97941`（Update README.md，2026-11-02）
- 规模：34 个 Kotlin 源文件 / 4708 行（main）+ 1 个测试文件 / 341 行；11 次提交；仓库 1.17 MiB
- 本机限制：无 JDK / Android SDK / Gradle，**未做本地编译**，一切构建结论来自 GitHub Actions 历史

---

## 一、总体结论

代码质量高于「自己用的玩具项目」的平均水平：计时内核是纯函数、时间戳全量持久化、进度与网络无关、
自检页覆盖了后台被杀的各种失败面、20 个单元测试把最容易错的边界都钉住了。CI 从 2026-11-01 起全绿。

主要问题集中在**「记账」和「工程化」两块**：四种会话跑起来是准的，但跑完之后写进统计库的数据大面积失真
（完成番茄数恒为 0、盲盒专注完全不记、还会凭空多出冥想记录）。工程方面缺 gradle wrapper、
CI 不跑 lint、无分支保护、签名 secrets 已丢失、文档与实现有 3 处不符。

| 维度 | 评分 | 说明 |
| --- | --- | --- |
| 计时正确性 | 良好 | 纯函数 + 20 个单测，迟到铃静默策略合理 |
| 数据记账 | 差 | 3 个缺陷 + 1 个凭空写入 |
| 后台可靠性 | 良好 | 前台服务 + setAlarmClock + 三层冗余，思路正确；但服务只启不停 |
| UI/UX | 中等 | 视觉完整，但有死功能（任务名）、设置项缺失、进度方向不一致 |
| 工程化 | 偏弱 | 无 wrapper、无 lint、无分支保护、无依赖更新、无 Release 自动化 |
| 文档 | 中等 | README 写得认真，但与实现有 3 处矛盾 |
| 安全 | 需处理 | 对话中泄露的 GitHub token（全权限）应立即吊销；签名 secrets 已空 |

---

## 二、功能性缺陷（按严重度）

### P0-1　统计页「完成番茄数」永远是 0
- `HistoryDao.observeCompletedPomodoroCount()` = `COUNT WHERE phase='FOCUS' AND completed=1`。
- 全仓只有 `SessionController.recordHistoryOnStop()` 一处写 `PomodoroRecord`，且写死 `completed = false`；
  `TimerEngine.tickPomodoro()` 结束专注阶段时只发 `PhaseChanged` 事件，`deliver()` 对它「什么都不做」。
- 结果：**没有任何代码路径能写入 `completed = true` 的番茄记录**，该指标恒为 0，番茄记录全部显示「提前结束」。

### P0-2　每次盲盒跑满上限都会凭空多记一条「完成冥想」
- `TimerForegroundService.handleEvents()` 收到 `TimerEvent.Finished` 后，用「冥想快照是否 IDLE 且
  phaseEndEpochMs == 0」来猜「刚才结束的是不是冥想」：
  ```kotlin
  if (snap.status == RunStatus.IDLE && snap.phaseEndEpochMs == 0L) controller.recordFinishedMeditation(snap)
  ```
- 但冥想快照的**初始状态**（以及手动停止后的状态）正是 `IDLE` + `phaseEnd == 0`。
- 而盲盒到 cap 时 `tickRandom()` 也会发 `Finished`。→ 每完成一次盲盒，统计页就多一条「完成冥想」，
  时长取设置里的 `sessionMinutes`（默认 20 分钟）。
- 同一逻辑还会让「用户早先停止的冥想」在之后任何一次 `Finished` 时被重复记一次完成。

### P0-3　冥想完成时长是「计划时长」而非真实时长
- `recordFinishedMeditation()`：`practicedMs = settings.sessionMinutes * 60_000`、`startedAt = now - planned`。
- 坐中暂停过、或中途改过「练习时长」设置，记录都会偏；暂停的时间被算成练习时间。

### P1-4　正念盲盒的专注时间完全不入库
- `recordHistoryOnStop()` 只处理 `MEDITATION` / `POMODORO`；`RANDOM_MINDFUL` 落进 `else -> {}`。
- 盲盒的 `focusMs` / `bellCount` 只活在快照里，`stop()` 后清零。
- → 统计页「专注总时长」只统计番茄钟，盲盒（本项目最核心的功能之一）贡献 0。

### P1-5　正常结束的番茄被记成「提前结束」
- 用户跑完 25 分钟专注、在边界处按「停止」：`stop()` → `recordHistoryOnStop()` → `completed = false`。
- 统计页显示「提前结束」，实际是完整的一个番茄。

### P1-6　前台服务只启不停，常驻通知永不消失
- `TimerForegroundService.start()` 有 5 处调用；`stop()` 定义了却**从未被调用**。
- 后果：第一次开始计时后，`timer_ongoing` 常驻通知（「准备就绪」）永久存在；所有会话结束后服务仍在
  每秒 tick 一次（`delay(1000)` 无限循环），持续唤醒 CPU。

### P1-7　闹钟通路不写历史
- `AlarmReceiver` 在服务已死时 `tickAll()` → 只发通知，不调用 `recordFinishedMeditation`。
- 后果：服务被杀（或被划掉后回收）期间完成的冥想，历史丢失；`Finished` 事件被静默吞掉。

### P1-8　「当前任务」输入框是死功能
- `PomodoroViewModel._taskName` 是内存 `MutableStateFlow`：不持久化、切换页面/进程重建即丢、
  且从未写入 `PomodoroRecord.taskName`（该字段永远 `""`）。

### P1-9　两个死设置 / 缺失的设置入口
- `PomodoroSettings.notify` 全仓从未被读取（"通知"开关无任何作用）。
- `PomodoroSettings.sound` / `volumePercent` 有字段、计时内核也在用，但番茄页面没有铃声/音量 UI 入口；
  `randomMindfulSettings.blindMode` 有 UI，其余三模式没有。

### P2-10　时段的 start == end 会让定时提醒永久静音
- `TimeWindow.contains()` 用 `in start until end`，`08:00–08:00` 恒为 false。
- 用户把「启用时段」两端设成同一时刻（很容易手滑），定时提醒再也不响，UI 无任何提示。

### P2-11　DataStore 解码失败静默丢弃会话
- `decodeSnapshot()` = `runCatching{...}.getOrNull()`，回落空快照，不写 `DiagLog`。
- 数据损坏时正在跑的会话无声消失，正好绕过了项目自带的自检体系。

### P2-12　SoundPool 异步加载的隐患（潜在）
- `BellPlayer.init` 里 `soundPool.load(...)` 后立即可以 `play(id)`；现在 `res/raw` 里没有铃声音源
  （只有彩蛋 wav），全部走 `ToneSynth` 合成，所以暂时不触发。**一旦按 `AUDIO_README` 放入
  `res/raw/temple_bell.ogg`，第一次响铃可能无声**（无 `setOnLoadCompleteListener`）。

### P2-13　铃声合成在主线程
- 服务 tick 协程跑在 `lifecycleScope`（Main）。`ToneSynth.render()` 一次要算几十万采样 + biquad 低通，
  寺院钟（7 秒衰减 @44.1kHz）最重；在低端机上可能造成明显掉帧/响铃延迟。

### P2-14　DiagLog 的同步文件 IO 与并发
- `append()` 在主线程 `readLines()` + `writeText()` 整文件重写；`clear()` 与 `append()` 无锁。
  日志量很小（≤120 行），风险有限，但属于「在 UI 线程做磁盘 IO」。

### P2-15　缺 `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` 监听
- 用户在系统设置里撤销「闹钟和提醒」后，App 不感知，直接降级到 `setAndAllowWhileIdle`，用户不会被告知。

### P2-16　进度环方向不一致
- 定时提醒：`1 - remaining/total`（弧线**增长**）；冥想 / 番茄：`remaining/total`（弧线**收缩**）；
  盲盒：`phaseProgress()`。三处风格不统一，三模式来回切会觉得「倒计时在倒着涨」。

### P2-17　诊断卡每秒重建对象
- `DiagnosticsCard` 每次重组（因为 `now` 每秒变）都 `AlertNotifier(context)` + 读 4 个系统服务 +
  权限检查，未 `remember`。开销不大但没必要。

### P2-18　数据库无迁移策略
- `exportSchema = false`（不产出 schema）+ `fallbackToDestructiveMigration()`。
- 以后任何一次 `version` 升级都会**清空用户数据**，且没有可审查的 schema 变更历史。

### P2-19　测试覆盖只到计时内核
- 20 个用例全部在 `TimerEngine`；`SessionController`（尤其是上面 P0/P1 的记账逻辑）、DAO、通知、
  闹钟、ViewModel **零覆盖**。上面几个 bug 正是「纯函数测得很好、编排层没人测」的典型后果。

---

## 三、工程化与 CI

1. **仓库故意不带 Gradle wrapper**：`gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.jar` 都不存在。
   - CI 用 `gradle/actions/setup-gradle@v4` 注入 Gradle 8.9 绕过；
   - 但任何人（包括未来的你）clone 之后 `./gradlew` 直接失败，README 里专门为此写了段说明。
   - 建议补齐 wrapper（3 个文件，几百 KB），CI 改回 `./gradlew`——这是最标准、最省事的形态。

2. **CI 只跑 `testDebugUnitTest` + `assembleDebug/Release`**：没有 `lintDebug`，没有 ktlint/detekt，
   没有 androidTest。所以 P2-10/P2-16 这类问题不会被自动发现。工作流里也没有 PR 评论/报告上传。

3. **没有分支保护**：`main` 可强推、可直接提交；`branches/main/protection` 返回 404。

4. **没有依赖更新机制**：无 Dependabot/Renovate。依赖停在 2024-10 前后：
   AGP 8.7.2、Kotlin 2.0.21、Compose BOM 2024.10.01、compileSdk/targetSdk 35、Gradle 8.9。
   已落后约一年（当前应为 AGP 9.x / Kotlin 2.2.x / compileSdk 36 一线）。

5. **Release 是手搓的**：tag `v1.0.0`–`v1.0.3` 与 Release 都由人工创建，CI 不上传 Release 资产。
   可以考虑 tag push 时自动建 Release 并附 APK。

6. **签名 secrets 已空**：`/actions/secrets` 返回空列表，但 v1.0.1–v1.0.3 的 release APK 是签过名的。
   → 现在**无法复现签名构建**。若 keystore 本身也丢了，后续版本将无法覆盖安装升级（必须卸载重装）。
   这是全仓库风险最高的一项，需要你确认 keystore 是否还有备份。

7. **硬编码中文字符串**：`strings.xml` 里只有 `app_name` 一条，其余约 1700 个汉字全部写在 Kotlin 里
   （`DiagnosticsCard.kt` 392 个、`BlindBoxPane.kt` 181 个…）。没有 `values-en`，无法国际化，
   lint 的 `HardcodedText` 规则也无从生效。

8. **1.4 MB 彩蛋音频入库**：`app/src/main/res/raw/naiva_laugh.wav` 单文件 1.4 MB，占仓库打包体积绝大部分
   （`size-pack 1.17 MiB`）。自用无妨，但直接进 APK、且与 README 的「APK 里没有音频文件」矛盾。

9. **未启用混淆**：`isMinifyEnabled = false`，`proguard-rules.pro` 是空壳。小应用影响不大，但 release
   APK 可读性高、体积大；R8 至少能压掉 `material-icons-extended` 里没用到的图标。

10. **缺失的常规文件**：无 `.editorconfig`、无 `.gitattributes`、无 `CODEOWNERS`、无 issue/PR 模板、
    无 `CHANGELOG.md`、无 `detekt.yml`/`.editorconfig`。

---

## 四、文档与实际不符（3 处）

| 位置 | 文档说 | 实际 |
| --- | --- | --- |
| README「实现选择」段 | APK 里没有音频文件（铃声全部合成） | `res/raw/naiva_laugh.wav` 1.4 MB 就在仓库/APK 里（彩蛋） |
| `app/AUDIO_README.md` | 没有铃声文件时「回退到系统通知铃声（见 `BellPlayer.playFallbackRingtone`）」 | 该方法不存在；回退是 `ToneSynth` 实时合成 |
| `app/AUDIO_README.md` | 把铃声文件「放在这个目录」（`app/`） | 必须在 `app/src/main/res/raw/`，且扩展名要与 `getIdentifier` 匹配 |

另外 README 自称「20 个用例」，与实际一致（`@Test` 计数 = 20），这一条是准的。

---

## 五、安全与合规

- **GitHub token 已在对话中明文暴露**（值不再复述），该 token 权限极宽
  （`admin:enterprise`、`delete_repo`、`workflow`、`write:packages` …）。**请立即在
  Settings → Developer settings → Personal access tokens 吊销并重新签发**，且下次只授 `repo` 最小权限。
- `USE_EXACT_ALARM` + `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 都是 Google Play 的受限/敏感权限，
  自用 APK 无碍；若将来上架需改用 `SCHEDULE_EXACT_ALARM` + 引导页，并提交权限声明。
- `allowBackup = true` 且无 `dataExtractionRules`：冥想/番茄记录会随系统备份外流（个人数据，可按需收紧）。
- 签名材料没有被提交进仓库（`.gitignore` 正确忽略了 `keystore.properties` / `*.jks` / `*.keystore`），
  这一点做得对；问题只在 secrets 丢了。

---

## 六、建议的修复批次（供选择）

### 批次 A：数据记账修复（半天，纯逻辑 + 单测，风险最低，收益最大）
1. 番茄完成时写入 `completed = true` 的记录（在 `tickPomodoro` 发出阶段结束信号时，由 controller 落库，
   `focusedMs` 用真实时长）。
2. 删掉「看冥想快照猜是谁结束」的判定，改为让 `TimerEvent.Finished` 携带 `SessionType`，
   由 controller 按类型记账 → 消除幻影冥想记录。
3. 冥想完成记录改用真实 `elapsedMs`（排除准备阶段），`startedAt` 用真实开始时间。
4. 盲盒结束时落库专注时长（复用 pomodoro 表加 `source` 字段，或新增表 → DB v2 + 迁移）。
5. 服务空闲时自动 `stop()`，常驻通知随之消失。
6. `AlarmReceiver` 与 service 共用同一条记账路径。
7. 新增 8–12 个测试覆盖上述逻辑（含「盲盒结束不产生冥想记录」「番茄完成计数 +1」）。

### 批次 B：工程化补课（1 天）
1. 补 Gradle wrapper（`gradlew` / `gradlew.bat` / `gradle-wrapper.jar`），CI 改用 `./gradlew`。
2. CI 增加 `lintDebug`（失败即红）+ ktlint 或 detekt；上传 lint 报告。
3. Dependabot（gradle + github-actions），`main` 开分支保护（禁强推 + 要求 CI 通过）。
4. tag push 时自动创建 Release 并上传 APK。
5. 修 README / AUDIO_README 三处不符；把 1.4 MB wav 压缩为 ogg 或移到 Release 资产。
6. 加 `.editorconfig`、`.gitattributes`（`*.kt text eol=lf`）。

### 批次 C：可选增强
1. 升级 AGP / Kotlin / Compose BOM / compileSdk 36（需 CI + 真机回归，建议单独一个 PR）。
2. `strings.xml` 抽取 + `values-en`。
3. Room `exportSchema = true` + 真迁移 + `schemas/` 入库。
4. 番茄页补齐铃声/音量/通知开关；任务名持久化并入库。
5. 时段配置校验（start == end 时提示）；`ToneSynth` 移到 `Dispatchers.Default`；`DiagLog` 异步落盘。

---

## 七、下一步需要你确认的事

1. **签名 keystore 是否还有备份？** 若丢了，v1.0.4 无法覆盖安装，只能换包名或让用户重装。
2. **是否允许我直接 push 到 `main`**（当前 token 有 `repo` 权限），还是走「开分支 → PR → CI → 合并」。
3. **先做哪一批**（建议 A，它修的是用户能直接看见的统计错误）。
