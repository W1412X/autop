/**
 * 面板动作（docs/tech/08 §5.2）：发送任务 / steer 补充指令 / 回答 / 暂停继续 /
 * 停止 / 拦截处置 / 打开对话 / 打开应用（N182 圆弧菜单）。
 */
package com.example.autop.feature.overlay.panel

import android.content.Context
import android.content.Intent
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.core.common.RunId
import com.example.autop.core.common.ThreadId
import com.example.autop.core.common.exceptionHandler
import com.example.autop.core.common.getOrNull
import com.example.autop.device.TouchShield
import com.example.autop.task.ActiveRunInfo
import com.example.autop.task.RunRecord
import com.example.autop.task.RunService
import com.example.autop.task.RunStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** 面板动作执行器：全部经 [RunService] 端口（FEATURE 不依赖 IMPL）。 */
@Singleton
public class PanelRunActions
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val runService: RunService,
        private val shield: TouchShield,
        private val logger: Logger,
        dispatchers: DispatcherProvider,
    ) {
        private val scope =
            CoroutineScope(SupervisorJob() + dispatchers.main + logger.exceptionHandler("PanelRunActions"))

        /** 当前活跃 Run（运行面板数据源）。 */
        public val active: StateFlow<ActiveRunInfo?> = runService.observeActive()

        /** 控制过渡态（点击即反馈）：落地或 [INTENT_TIMEOUT_MS] 超时清除。 */
        private val _controlIntent = MutableStateFlow<ControlIntent?>(null)

        /** 当前过渡态（控制条/进度窗按钮反馈用）。 */
        public val controlIntent: StateFlow<ControlIntent?> = _controlIntent.asStateFlow()

        init {
            // 过渡态收敛：活跃 Run 状态落地（或换 Run/消失）即清除，按钮立即回常态。
            scope.launch {
                active.collect { info ->
                    val intent = _controlIntent.value ?: return@collect
                    if (controlIntentSettled(intent, info)) _controlIntent.value = null
                }
            }
        }

        /**
         * 输入面板发送：新建对话发起任务（attachCurrentScreen=「针对当前屏幕」chip）。
         * [onStarted] 发起成功后回调（N182：发送后转任务进度悬浮窗）。
         */
        public fun send(
            text: String,
            attachScreen: Boolean,
            onStarted: (RunRecord) -> Unit = {},
        ) {
            scope.launch {
                runService.startChat(null, text, attachScreen).getOrNull()?.let(onStarted)
            }
        }

        /** 运行面板补充指令（steer，不打断当前采样）；失败留给对话页提示（WP8.5）。 */
        public fun steer(text: String) {
            if (ignoreWhileInjecting(ACTION_STEER)) return
            val id = activeId() ?: return
            scope.launch { runService.steer(id, text) }
        }

        /** 问题面板回答。 */
        public fun answer(answer: String) {
            if (ignoreWhileInjecting(ACTION_ANSWER)) return
            val id = activeId() ?: return
            scope.launch { runService.answer(id, answer) }
        }

        /** 暂停 / 继续（按当前状态切换）；过渡态立即置起防连点翻转。 */
        public fun pauseResume() {
            if (ignoreWhileInjecting(ACTION_PAUSE_RESUME)) return
            val id = activeId() ?: return
            if (active.value?.run?.status == RunStatus.PAUSED) {
                publish(ControlIntentKind.RESUME, id)
                runService.resume(id, null)
            } else {
                publish(ControlIntentKind.PAUSE, id)
                runService.pause(id)
            }
        }

        /**
         * 停止（N191/N217，安全关键）：先强制解除触摸屏蔽再取消——终止按钮点下就必须能
         * 立刻操作手机，即使取消链路异常或 Run 卡死未收口。
         */
        public fun stop() {
            if (ignoreWhileInjecting(ACTION_STOP)) return
            shield.forceRelease()
            val id = activeId() ?: return
            publish(ControlIntentKind.STOP, id)
            runService.cancel(id)
        }

        /** 拦截面板「我已手动完成，继续」。 */
        public fun continueBlocked() {
            if (ignoreWhileInjecting(ACTION_CONTINUE_BLOCKED)) return
            val id = activeId() ?: return
            publish(ControlIntentKind.RESUME, id)
            runService.resume(id, RESUME_NOTE)
        }

        /** 拦截面板「结束」（同 [stop]：先强制解除屏蔽）。 */
        public fun finishBlocked() {
            if (ignoreWhileInjecting(ACTION_FINISH_BLOCKED)) return
            shield.forceRelease()
            val id = activeId() ?: return
            publish(ControlIntentKind.STOP, id)
            runService.cancel(id)
        }

        /**
         * 打开对话：跳 app 对话页（线程 id 经 launch extra 传递，questions.md N135）。
         * [threadId] 显式指定（N185：Run 结束后进度窗靠最近线程仍可打开），缺省取活跃 Run。
         */
        public fun openThread(threadId: ThreadId? = null) {
            val target = threadId ?: active.value?.run?.threadId ?: return
            val intent =
                context.packageManager
                    .getLaunchIntentForPackage(context.packageName)
                    ?.putExtra(EXTRA_THREAD_ID, target.value)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ?: return
            context.startActivity(intent)
        }

        /** 打开应用（N182 圆弧菜单）：不带 thread extra 拉起主 Activity。 */
        public fun openApp() {
            val intent =
                context.packageManager
                    .getLaunchIntentForPackage(context.packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ?: return
            context.startActivity(intent)
        }

        private fun activeId(): RunId? = active.value?.run?.id

        /**
         * 注入进行中忽略控制入口点击（N222）：注入的点击可能刚好落在自家按钮上——
         * 实测顶部控制条的「中止」被工具注入的 tap 命中，Run 被误取消。
         * 注入只有毫秒级，忽略不影响用户主动操作。
         * 公开给控制条/进度窗的「停止流程」等直连入口复用（这些入口不走本类方法）。
         */
        public fun ignoreWhileInjecting(action: String): Boolean {
            if (!shield.injecting.value) return false
            logger.i(TAG, "panel_click_ignored", "action" to action, "reason" to "injecting")
            return true
        }

        /** 置过渡态并起超时兜底（状态迟迟不落地时恢复按钮可用）。 */
        private fun publish(
            kind: ControlIntentKind,
            runId: RunId,
        ) {
            val intent = ControlIntent(kind, runId)
            _controlIntent.value = intent
            scope.launch {
                delay(INTENT_TIMEOUT_MS)
                if (_controlIntent.value == intent) _controlIntent.value = null
            }
        }

        public companion object {
            /** 日志 tag。 */
            private const val TAG: String = "PanelRunActions"

            /** 「打开对话」跳转 extra 键（app 导航消费）。 */
            public const val EXTRA_THREAD_ID: String = "autop.threadId"

            /** 过渡态兜底超时（毫秒）。 */
            private const val INTENT_TIMEOUT_MS: Long = 5_000L

            /** 「我已手动完成，继续」附带的恢复备注。 */
            private const val RESUME_NOTE: String = "user_marked_done"

            /** 注入期忽略的入口（日志留痕用，N222）。 */
            private const val ACTION_STEER: String = "steer"
            private const val ACTION_ANSWER: String = "answer"
            private const val ACTION_PAUSE_RESUME: String = "pause_resume"
            private const val ACTION_STOP: String = "stop"
            private const val ACTION_CONTINUE_BLOCKED: String = "continue_blocked"
            private const val ACTION_FINISH_BLOCKED: String = "finish_blocked"

            /** 控制条/进度窗的「停止流程」入口（直连 flowFeed，守卫在本类之外，N222）。 */
            public const val ACTION_STOP_FLOW: String = "stop_flow"
        }
    }
