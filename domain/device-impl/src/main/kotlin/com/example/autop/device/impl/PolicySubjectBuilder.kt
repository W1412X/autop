/**
 * 策略主体生成（docs/tech/05 §6、docs/design/observation-modes-coordinate-actions §5.6）：
 * 描述“一次写屏动作发生在什么屏幕上、目标是什么”，供 WP5 的策略引擎匹配规则。
 */
package com.example.autop.device.impl

import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.DeviceConfig
import com.example.autop.core.config.PolicyConfig
import com.example.autop.device.NodeFlags
import com.example.autop.device.NodeSnapshot
import com.example.autop.device.Point
import com.example.autop.device.ScreenModel
import com.example.autop.device.ScreenNode
import com.example.autop.device.ScreenSnapshot
import com.example.autop.device.core.HitResolver
import com.example.autop.device.core.ScreenJsonWire
import com.example.autop.policy.PolicySubject
import javax.inject.Inject
import javax.inject.Singleton

/** [PolicySubject] 的统一生成入口（工具只调用它）。 */
@Singleton
public class PolicySubjectBuilder
    @Inject
    constructor(
        private val configProvider: ConfigProvider,
        private val secureCache: SecureWindowCache,
    ) {
        /**
         * 生成策略主体：[target] 为落点处节点（可空），[extra] 为工具自报信息。
         * 实时快照为空（应用屏蔽）时用 [fallbackTree] 中 [fallbackPoint] 的命中节点作为目标。
         */
        public fun build(
            toolId: String,
            snapshot: ScreenSnapshot,
            target: NodeSnapshot?,
            extra: Map<String, String>,
            fallbackTree: ScreenModel? = null,
            fallbackPoint: Point? = null,
        ): PolicySubject {
            val subject = configProvider.current()[PolicyConfig.POLICY].subject
            val fallbackModel = if (snapshot.nodes.isEmpty()) fallbackTree else null
            val fallback = fallbackModel?.let { model -> fallbackPoint?.let { HitResolver.resolve(it, model) } }
            return PolicySubject(
                toolId = toolId,
                packageName = snapshot.foregroundPackage,
                activity = snapshot.activity,
                targetText = targetText(target, snapshot, fallback),
                targetIsPassword = isPassword(target, fallback),
                targetDataSensitive = target != null && target.has(NodeFlags.DATA_SENSITIVE),
                screenTexts = screenTextsFor(snapshot, fallbackTree, subject.maxScreenTexts),
                hasPasswordField = hasPasswordField(snapshot, fallbackModel),
                windowSecure = secureCache.isSecure(subject.secureCacheMs),
                extra = extraWithSource(extra, fallback, fallbackModel),
            )
        }

        private fun targetText(
            target: NodeSnapshot?,
            snapshot: ScreenSnapshot,
            fallback: ScreenNode?,
        ): String? = target?.let { targetTextOf(snapshot, it) } ?: fallback?.let { fallbackTextOf(it) }

        /** 命中节点或兜底命中节点是否带密码语义。 */
        private fun isPassword(
            target: NodeSnapshot?,
            fallback: ScreenNode?,
        ): Boolean =
            target?.has(NodeFlags.PASSWORD) == true ||
                (fallback != null && ScreenJsonWire.ST_PASSWORD in fallback.st)

        private fun hasPasswordField(
            snapshot: ScreenSnapshot,
            fallbackModel: ScreenModel?,
        ): Boolean =
            snapshot.nodes.any { it.has(NodeFlags.PASSWORD) } ||
                fallbackModel?.flatten()?.any { ScreenJsonWire.ST_PASSWORD in it.st } == true

        /** 实时树为空时用最近观测树取文字。 */
        private fun screenTextsFor(
            snapshot: ScreenSnapshot,
            fallbackTree: ScreenModel?,
            limit: Int,
        ): List<String> {
            val model = if (snapshot.nodes.isEmpty()) fallbackTree else null
            return model?.let { fallbackScreenTexts(it, limit) } ?: screenTextsOf(snapshot, limit)
        }

        private fun extraWithSource(
            extra: Map<String, String>,
            fallback: ScreenNode?,
            fallbackModel: ScreenModel?,
        ): Map<String, String> {
            val model = fallbackModel ?: return extra
            return if (fallback == null) extra else extra + (VAR_TARGET to "from_${model.mode.id}")
        }

        /** 目标文字：text + desc + 合并的后代 label（数量取 `device.serializer.maxMergedLabels`）。 */
        private fun targetTextOf(
            snapshot: ScreenSnapshot,
            target: NodeSnapshot,
        ): String? {
            val parts = mutableListOf<String>()
            listOfNotNull(target.text, target.contentDesc)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { parts += it }
            val limit = configProvider.current()[DeviceConfig.SERIALIZER].maxMergedLabels
            collectLabels(snapshot, target, parts, limit)
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        }

        /** 先序收集后代的可见 label，最多 [limit] 个。 */
        private fun collectLabels(
            snapshot: ScreenSnapshot,
            node: NodeSnapshot,
            out: MutableList<String>,
            limit: Int,
        ) {
            if (out.size >= limit) return
            for (childIdx in node.children) {
                val child = snapshot.nodes.getOrNull(childIdx) ?: continue
                val label = labelOf(child)
                if (label != null && child.has(NodeFlags.VISIBLE) && out.size < limit) out += label
                collectLabels(snapshot, child, out, limit)
            }
        }

        /** 当前屏幕的全部可见 label（上限 [limit] 条）。 */
        private fun screenTextsOf(
            snapshot: ScreenSnapshot,
            limit: Int,
        ): List<String> {
            val texts = mutableListOf<String>()
            for (node in snapshot.nodes) {
                if (texts.size >= limit) break
                val label = if (node.has(NodeFlags.VISIBLE) && node.bounds.area > 0) labelOf(node) else null
                if (label != null) texts += label
            }
            return texts
        }

        /** 最近观测树的命中节点文字：text、icon、desc 非空者。 */
        private fun fallbackTextOf(node: ScreenNode): String? =
            listOfNotNull(node.text, node.icon, node.desc)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .takeIf { it.isNotEmpty() }
                ?.joinToString(" · ")

        /** 最近观测树的全部文字（上限 [limit] 条）。 */
        private fun fallbackScreenTexts(
            model: ScreenModel,
            limit: Int,
        ): List<String> {
            val texts = mutableListOf<String>()
            for (node in model.flatten()) {
                if (texts.size >= limit) break
                listOfNotNull(node.text, node.icon, node.desc)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { if (texts.size < limit) texts += it }
            }
            return texts
        }

        /** text 优先、其次 contentDesc；空白视为无。 */
        private fun labelOf(node: NodeSnapshot): String? {
            val text =
                node.text
                    ?.trim()
                    .orEmpty()
                    .ifEmpty { node.contentDesc?.trim().orEmpty() }
            return text.ifEmpty { null }
        }

        private fun NodeSnapshot.has(flag: Int): Boolean = flags and flag != 0

        private companion object {
            const val VAR_TARGET = "target"
        }
    }
