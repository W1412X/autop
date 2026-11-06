/**
 * 动作效果校验（docs/design/flows-recording-recovery.md R3）。
 *
 * 动作执行后轮询屏幕，直到期望出现或超时。期望来自节点 `expect`；未写时按
 * `settings.verify` 与 `flow.recovery.verifyTools` 决定是否做“屏幕是否变化”的弱校验。
 */
package com.example.autop.flow.core.verify

import com.example.autop.agent.ToolAccess
import com.example.autop.core.config.FlowRecoverySection
import com.example.autop.device.ScreenModel
import com.example.autop.flow.Expect
import com.example.autop.flow.ExpectKind
import com.example.autop.flow.ToolNode
import com.example.autop.flow.VerifyMode
import com.example.autop.flow.core.FlowPorts
import com.example.autop.flow.core.Frame
import com.example.autop.flow.core.ScreenService
import com.example.autop.flow.core.screen.TargetMatch
import kotlinx.coroutines.delay

/** 动作效果校验器。 */
internal class ActionVerifier(
    private val ports: FlowPorts,
    private val screens: ScreenService,
    private val cfg: FlowRecoverySection,
) {
    /** 动作前的屏幕指纹（强制重新观测）。 */
    suspend fun fingerprint(frame: Frame): Int {
        val observed = screens.screen(null, frame.flow.settings.observeMode, force = true)
        return screens.fingerprintOf(observed.screen)
    }

    /** 本次工具调用应校验的期望；null 表示不校验。 */
    fun expectFor(
        node: ToolNode,
        frame: Frame,
        access: ToolAccess?,
    ): Expect? {
        val explicit = node.expect
        if (explicit != null) return if (explicit.kind == ExpectKind.NONE) null else explicit
        return autoExpect(node, frame, access)
    }

    private fun autoExpect(
        node: ToolNode,
        frame: Frame,
        access: ToolAccess?,
    ): Expect? {
        if (!cfg.enabled) return null
        val auto =
            when (frame.flow.settings.verify) {
                VerifyMode.OFF -> false
                VerifyMode.AUTO -> node.tool in cfg.verifyTools
                VerifyMode.STRICT -> access == ToolAccess.WRITE_SCREEN
            }
        return if (auto) Expect(kind = ExpectKind.SCREEN_CHANGED) else null
    }

    /** 轮询直到期望成立（true）或超时（false）。 */
    suspend fun await(
        expect: Expect,
        frame: Frame,
        before: Int?,
    ): Boolean {
        val deadline = ports.clock.nowMs() + (expect.timeoutMs ?: cfg.verifyTimeoutMs)
        while (true) {
            if (holds(expect, frame, before)) return true
            if (ports.clock.nowMs() >= deadline) return false
            delay(cfg.verifyPollMs)
        }
    }

    private suspend fun holds(
        expect: Expect,
        frame: Frame,
        before: Int?,
    ): Boolean {
        val mode = frame.flow.settings.observeMode
        val screen = screens.screen(null, mode, force = true).screen
        return when (expect.kind) {
            ExpectKind.NONE -> true
            ExpectKind.SCREEN_CHANGED -> before == null || screens.fingerprintOf(screen) != before
            ExpectKind.TEXT -> textPresent(expect, frame, screen) == true
            ExpectKind.TEXT_GONE -> textPresent(expect, frame, screen) != true
            ExpectKind.ELEMENT -> elementPresent(expect, frame) == true
            ExpectKind.ELEMENT_GONE -> elementPresent(expect, frame) != true
            ExpectKind.APP -> expect.app != null && screens.foregroundApp(screen) == expect.app
        }
    }

    /** 页面文字是否包含期望文字；未配置文字时返回 null。 */
    private fun textPresent(
        expect: Expect,
        frame: Frame,
        screen: ScreenModel,
    ): Boolean? = expect.text?.let { screens.pageText(screen).contains(frame.templates.renderString(it)) }

    /** 目标元素是否存在；未配置目标时返回 null。 */
    private suspend fun elementPresent(
        expect: Expect,
        frame: Frame,
    ): Boolean? = expect.target?.let { screens.find(frame, it, wait = false) != TargetMatch.NotFound }
}
