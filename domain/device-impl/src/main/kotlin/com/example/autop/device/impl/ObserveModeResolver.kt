/**
 * 观测模式可用性（docs/design/observation-modes-coordinate-actions §2.1、§2.3）：
 * 生效模式 = `device.observe.priority` 中已启用且当前可用的模式，保持优先级顺序。
 */
package com.example.autop.device.impl

import com.example.autop.core.common.Outcome
import com.example.autop.core.config.DeviceObserveSection
import com.example.autop.device.ObserveMode
import com.example.autop.device.ObserveModeAvailability
import com.example.autop.device.VisualScreenParser
import com.example.autop.llm.LlmGateway
import com.example.autop.llm.ModelRole
import javax.inject.Inject
import javax.inject.Singleton

/** 模式可用性判定。 */
@Singleton
public class ObserveModeResolver
    @Inject
    constructor(
        private val holder: ServiceHolder,
        private val visual: VisualScreenParser,
        private val gateway: LlmGateway,
    ) : ObserveModeAvailability {
        /** 当前生效的模式列表（按配置优先级排序）。 */
        public fun effective(config: DeviceObserveSection): List<ObserveMode> =
            config.priority
                .mapNotNull { ObserveMode.parse(it) }
                .filter { enabled(it, config) && capabilityReason(it) == null }

        /** 参与 observe 不传 mode 时自动选择的生效方式（按优先级排序）。 */
        public fun autoCandidates(config: DeviceObserveSection): List<ObserveMode> =
            effective(config).filter { config.modes.of(it.id).auto }

        /** 显式选择 [mode] 时的不可用原因；可用返回 null。 */
        public fun unavailableReason(
            mode: ObserveMode,
            config: DeviceObserveSection,
        ): String? =
            if (!enabled(mode, config)) {
                REASON_DISABLED
            } else {
                capabilityReason(mode)
            }

        /** 能力可用性（不看用户开关）：设置页展示用。 */
        override fun unavailableReason(mode: ObserveMode): String? = capabilityReason(mode)

        private fun enabled(
            mode: ObserveMode,
            config: DeviceObserveSection,
        ): Boolean = config.modes.of(mode.id).enabled

        private fun capabilityReason(mode: ObserveMode): String? =
            when (mode) {
                ObserveMode.A11Y ->
                    if (holder.current() == null) "无障碍服务未连接" else null
                ObserveMode.VISUAL ->
                    if (visual.available) null else "视觉解析模型未就绪"
                ObserveMode.LLM -> llmReason()
            }

        /** llm 模式要求 VISION 角色已分配且模型声明支持图片（§4.1）。 */
        private fun llmReason(): String? =
            when (val got = gateway.forRole(ModelRole.VISION)) {
                is Outcome.Err -> "未在模型配置中设置视觉模型"
                is Outcome.Ok ->
                    if (got.value.profile.capabilities.vision) {
                        null
                    } else {
                        "所选视觉模型不支持图片"
                    }
            }

        private companion object {
            const val REASON_DISABLED: String = "该模式已在设置中关闭"
        }
    }
