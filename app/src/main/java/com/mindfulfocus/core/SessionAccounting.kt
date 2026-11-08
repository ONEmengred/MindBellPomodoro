package com.mindfulfocus.core

/**
 * 记一笔「已完成 / 中途结束」的冥想静坐。
 *
 * 这里刻意只是纯数据：真正落库的工作交给 `SessionController`，这样记账规则可以
 * 在没有 Android 依赖的 JVM 单测里被完整验证（见 `SessionAccountingTest`）。
 */
data class MeditationDraft(
    val startedAtEpochMs: Long,
    /** 真正静坐的时长，不含准备倒计时。 */
    val practicedMs: Long,
    val plannedMs: Long,
    val completed: Boolean
)

/** 一段专注时间 —— 番茄的 focus 阶段，或正念盲盒。 */
data class FocusDraft(
    val startedAtEpochMs: Long,
    val focusedMs: Long,
    val completed: Boolean,
    val origin: FocusOrigin
)

/** [FocusDraft] 的来源，决定它落库时写哪个 phase 名。 */
enum class FocusOrigin {
    /** 番茄钟的 focus 阶段，落库 phase = "FOCUS"。 */
    POMODORO,

    /** 正念盲盒，落库 phase = "MINDFUL"。 */
    MINDFUL
}

/** 一次 tick / stop 之后本应写进历史表的全部内容。 */
data class HistoryDrafts(
    val meditations: List<MeditationDraft> = emptyList(),
    val focuses: List<FocusDraft> = emptyList()
) {
    val isEmpty: Boolean get() = meditations.isEmpty() && focuses.isEmpty()
}

/**
 * 记账规则：把「计时引擎发生了什么」翻译成「历史表该写什么」。
 *
 * 之所以独立成一个纯函数对象，是因为以前这块逻辑散落在 service 里靠「猜」：
 * 它只能拿到 tick 之后的快照，而那时会话已经被重置成 IDLE，于是拿设置里的计划时长
 * 冒充实际时长，并且把「冥想默认的 IDLE 状态」误判成「刚刚完成」。现在改由引擎在
 * 事件里带上真实数据，这里只做无歧义的映射：
 *
 *  - 冥想自然结束（`Finished(MEDITATION)`）→ 一条 completed = true 的静坐记录；
 *  - 盲盒到达上限（`Finished(RANDOM_MINDFUL)`）→ 一条 completed = true 的专注记录；
 *  - 番茄 focus 阶段跑完（`PhaseChanged(from = FOCUS)`）→ 一条 completed = true 的
 *    番茄记录，这正是「完成番茄数」此前恒为 0 的根因；
 *  - 用户主动停止 → 只记真正跑过的那一段，且不重复记已经结算过的阶段。
 */
object SessionAccounting {

    /**
     * 本次 tick 产生的记账项。[before] 必须是 tick **之前**的快照 —— 番茄的
     * `PhaseChanged` 只告诉你阶段变了，本阶段应记的时长只能从旧快照里取。
     */
    fun draftsForTick(before: SessionSnapshot, result: TickResult): HistoryDrafts {
        val meditations = mutableListOf<MeditationDraft>()
        val focuses = mutableListOf<FocusDraft>()

        for (event in result.events) {
            when (event) {
                is TimerEvent.Finished -> when (event.type) {
                    SessionType.MEDITATION ->
                        if (event.practicedMs > 0L) {
                            meditations += MeditationDraft(
                                startedAtEpochMs = event.startedAtEpochMs,
                                practicedMs = event.practicedMs,
                                plannedMs = event.plannedMs,
                                completed = true
                            )
                        }
                    SessionType.RANDOM_MINDFUL ->
                        if (event.practicedMs > 0L) {
                            focuses += FocusDraft(
                                startedAtEpochMs = event.startedAtEpochMs,
                                focusedMs = event.practicedMs,
                                completed = true,
                                origin = FocusOrigin.MINDFUL
                            )
                        }
                    // 定时提醒没有「练习时长」这个概念，不入账。
                    SessionType.INTERVAL_BELL, SessionType.POMODORO -> Unit
                }

                is TimerEvent.PhaseChanged ->
                    if (event.from == PomodoroPhase.FOCUS && before.phaseTotalMs > 0L) {
                        val focused = before.phaseTotalMs
                        focuses += FocusDraft(
                            startedAtEpochMs = (before.phaseEndEpochMs - focused).coerceAtLeast(0L),
                            focusedMs = focused,
                            completed = true,
                            origin = FocusOrigin.POMODORO
                        )
                    }

                else -> Unit
            }
        }
        return HistoryDrafts(meditations, focuses)
    }

    /**
     * 用户按了「停止 / 重置」时的记账。
     *
     * [snapshot] 是停止前的快照，[now] 是按下按钮的时刻。
     * IDLE 等于「还没有开始」或者「已经结算过」（自然结束时会话已被引擎重置为 IDLE），
     * 两种都不该再写一行。
     */
    fun draftsForStop(snapshot: SessionSnapshot, now: Long): HistoryDrafts {
        if (snapshot.status == RunStatus.IDLE) return HistoryDrafts()
        val elapsed = snapshot.elapsedMs(now).coerceAtLeast(0L)
        if (elapsed <= 0L) return HistoryDrafts()

        return when (snapshot.type) {
            SessionType.MEDITATION -> {
                val prepMs = snapshot.prepMs.coerceIn(0L, snapshot.phaseTotalMs)
                val plannedMs = (snapshot.phaseTotalMs - prepMs).coerceAtLeast(0L)
                val practicedMs = (elapsed - prepMs).coerceIn(0L, plannedMs)
                // 准备阶段就退出（还在调姿势）不算一次静坐。
                if (practicedMs <= 0L) HistoryDrafts()
                else HistoryDrafts(
                    meditations = listOf(
                        MeditationDraft(
                            startedAtEpochMs = now - elapsed,
                            practicedMs = practicedMs,
                            plannedMs = plannedMs,
                            completed = false
                        )
                    )
                )
            }

            // 休息阶段不记：历史列表和统计都只认 focus。停在阶段边界上时
            // phaseEndEpochMs == 0，而那一轮已经由 PhaseChanged 记成 completed 了。
            SessionType.POMODORO ->
                if (snapshot.pomodoroPhase != PomodoroPhase.FOCUS || snapshot.phaseEndEpochMs <= 0L) {
                    HistoryDrafts()
                } else {
                    HistoryDrafts(
                        focuses = listOf(
                            FocusDraft(
                                startedAtEpochMs = now - elapsed,
                                focusedMs = elapsed.coerceAtMost(snapshot.phaseTotalMs.coerceAtLeast(0L)),
                                completed = false,
                                origin = FocusOrigin.POMODORO
                            )
                        )
                    )
                }

            SessionType.RANDOM_MINDFUL -> {
                val focusMs = snapshot.focusMs(now).coerceAtLeast(0L)
                if (focusMs <= 0L) HistoryDrafts()
                else HistoryDrafts(
                    focuses = listOf(
                        FocusDraft(
                            startedAtEpochMs = now - elapsed,
                            focusedMs = focusMs,
                            completed = false,
                            origin = FocusOrigin.MINDFUL
                        )
                    )
                )
            }

            SessionType.INTERVAL_BELL -> HistoryDrafts()
        }
    }
}
