package com.example.autop.task.impl.notify

import com.example.autop.agent.PauseReason
import com.example.autop.core.common.RunId
import com.example.autop.task.ActiveRunInfo
import com.example.autop.task.RunRecord
import com.example.autop.task.UserNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 需要用户处理的运行事件（ask_user 提问、策略拦截）的通知分发。
 *
 * 运行持有屏幕租约（用户正在占屏幕运行）时，由悬浮窗展开进度面板处理，这里不发通知。
 * 不占屏幕时出现这些事件，则发通知；否则运行会在后台无提示地等待或停住。
 * 同一提问或拦截只通知一次；提问结束（回答、超时、运行结束）后撤掉提问通知。
 */
public class RunAttentionNotifier(
    private val notifier: UserNotifier,
) {
    private var notifiedAsk: Pair<RunId, String>? = null
    private var notifiedBlockRun: RunId? = null

    /** 在应用作用域内订阅运行状态（active）与屏幕租约持有者（holder）。 */
    public fun start(
        scope: CoroutineScope,
        active: Flow<ActiveRunInfo?>,
        holder: Flow<String?>,
    ) {
        scope.launch {
            combine(active, holder) { info, owner -> info to owner }
                .collect { (info, owner) -> onState(info, owner) }
        }
    }

    /** 运行状态或屏幕租约变化时调用。 */
    internal fun onState(
        info: ActiveRunInfo?,
        holder: String?,
    ) {
        val run = info?.run
        val operating = run != null && holder == run.id.value
        onQuestion(run?.takeIf { it.waitingQuestion != null }, operating)
        onBlocked(run?.takeIf { it.pauseReason == PauseReason.POLICY_BLOCKED }, operating)
    }

    private fun onQuestion(
        run: RunRecord?,
        operating: Boolean,
    ) {
        val shown = notifiedAsk
        if (shown != null && (run == null || shown.first != run.id)) {
            notifier.cancelQuestion(shown.first)
            notifiedAsk = null
        }
        val question = run?.waitingQuestion
        if (run == null || question == null || operating) return
        val now = run.id to question
        if (now == notifiedAsk) return
        notifier.notifyQuestion(run, question)
        notifiedAsk = now
    }

    private fun onBlocked(
        run: RunRecord?,
        operating: Boolean,
    ) {
        if (run == null) {
            notifiedBlockRun = null
            return
        }
        if (operating || run.id == notifiedBlockRun) return
        notifier.notifyBlocked(run)
        notifiedBlockRun = run.id
    }
}
