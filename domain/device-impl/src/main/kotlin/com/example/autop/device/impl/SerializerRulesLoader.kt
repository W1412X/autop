/**
 * 序列化规则加载（docs/tech/05 §3.1）：从 `assets/rules/serializer_rules.json` 读取，
 * device-core 只接收数据对象。
 */
package com.example.autop.device.impl

import android.content.Context
import com.example.autop.core.common.AutopJson
import com.example.autop.core.common.getOrNull
import com.example.autop.core.config.ConfigSnapshot
import com.example.autop.core.config.DeviceConfig
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.device.core.SerializerConfig
import com.example.autop.device.core.SerializerRules
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 序列化规则加载器（进程内缓存一份）。 */
@Singleton
public class SerializerRulesLoader
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val rules: SerializerRules by lazy { load() }

        /** 规则对象（惰性加载一次）。 */
        public fun get(): SerializerRules = rules

        /** 由配置快照构造序列化配置（renderPrompt 注入模板渲染）。 */
        public fun configOf(
            config: ConfigSnapshot,
            prompts: PromptRepository,
        ): SerializerConfig {
            val device = config[DeviceConfig.SERIALIZER]
            return SerializerConfig(
                textMaxChars = device.textMaxChars,
                maxMergedLabels = device.maxMergedLabels,
                maxListItems = device.maxListItems,
                hideSystemDecoration = device.hideSystemDecoration,
                diffMaxRatio = device.diffMaxRatio,
                semanticPoorMinInteractive = device.semanticPoorMinInteractive,
                semanticPoorAreaRatio = device.semanticPoorAreaRatio,
                degenerateNodeThreshold = config[DeviceConfig.VISION].degenerateNodeThreshold,
                uncertainConf = config[DeviceConfig.VISION].uncertainConf,
                renderPrompt = { id, vars ->
                    prompts
                        .render(id, vars)
                        .getOrNull()
                        ?.text
                        .orEmpty()
                },
            )
        }

        private fun load(): SerializerRules {
            val json =
                context.assets
                    .open(RULES_PATH)
                    .reader(Charsets.UTF_8)
                    .readText()
            return AutopJson.default.decodeFromString(SerializerRules.serializer(), json)
        }

        private companion object {
            const val RULES_PATH = "rules/serializer_rules.json"
        }
    }
