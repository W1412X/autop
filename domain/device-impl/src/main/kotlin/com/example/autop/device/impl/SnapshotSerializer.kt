/**
 * 快照 → 序列化的共用装配（docs/tech/05 §3.1）：规则加载、窗口脱敏、自身包名过滤与模板渲染。
 * [com.example.autop.device.impl.observation.ScreenObserver] 与
 * [com.example.autop.device.impl.observation.ReplayStateProbeImpl] 共用。
 */
package com.example.autop.device.impl

import com.example.autop.core.config.ConfigSnapshot
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.device.ObserveMode
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.core.ScreenSerializer
import com.example.autop.device.core.SerializedScreen
import com.example.autop.device.core.SerializerConfig
import com.example.autop.device.core.SerializerRules
import javax.inject.Inject
import javax.inject.Singleton

/** 序列化装配：同一套规则与脱敏决策供观测、采样使用。 */
@Singleton
public class SnapshotSerializer
    @Inject
    constructor(
        private val rulesLoader: SerializerRulesLoader,
        private val redactor: WindowRedactor,
        private val appIdentity: AppIdentity,
        private val prompts: PromptRepository,
    ) {
        /** 序列化规则（角色表、marks 等）。 */
        public fun rules(): SerializerRules = rulesLoader.get()

        /** 序列化配置（含 prompt 渲染），差量生成也要用。 */
        public fun configOf(config: ConfigSnapshot): SerializerConfig = rulesLoader.configOf(config, prompts)

        /** 按配置快照与观测模式序列化 [snap]。 */
        public fun serialize(
            snap: ScreenSnapshot,
            mode: ObserveMode,
            config: ConfigSnapshot,
            effectiveModes: List<ObserveMode>,
        ): SerializedScreen =
            ScreenSerializer(rulesLoader.get(), configOf(config))
                .serialize(snap, mode, { redactor.redact(it) }, appIdentity.packageName, effectiveModes)
    }
