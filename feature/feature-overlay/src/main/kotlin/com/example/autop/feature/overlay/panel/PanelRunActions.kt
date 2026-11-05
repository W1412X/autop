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
import kotlinx.coroutines.flow.StateFlow
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

        /** 运行面板补充指令（steer，不打断当前采样）。 */
        public fun steer(text: String) {
            activeId()?.let { runService.steer(it, text) }
        }

        /** 问题面板回答。 */
        public fun answer(answer: String) {
            val id = activeId() ?: return
            scope.launch { runService.answer(id, answer) }
        }

        /** 暂停 / 继续（按当前状态切换）。 */
        public fun pauseResume() {
            val id = activeId() ?: return
            if (active.value?.run?.status == RunStatus.PAUSED) {
                runService.resume(id, null)
            } else {
                runService.pause(id)
            }
        }

        /**
         * 停止（N191/N217，安全关键）：先强制解除触摸屏蔽再取消——终止按钮点下就必须能
         * 立刻操作手机，即使取消链路异常或 Run 卡死未收口。
         */
        public fun stop() {
            shield.forceRelease()
            activeId()?.let { runService.cancel(it) }
        }

        /** 拦截面板「我已手动完成，继续」。 */
        public fun continueBlocked() {
            activeId()?.let { runService.resume(it, RESUME_NOTE) }
        }

        /** 拦截面板「结束」（同 [stop]：先强制解除屏蔽）。 */
        public fun finishBlocked() {
            shield.forceRelease()
            activeId()?.let { runService.cancel(it) }
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

        public companion object {
            /** 「打开对话」跳转 extra 键（app 导航消费）。 */
            public const val EXTRA_THREAD_ID: String = "autop.threadId"

            /** 「我已手动完成，继续」附带的恢复备注。 */
            private const val RESUME_NOTE: String = "user_marked_done"
        }
    }
