/**
 * 记忆路径（docs/tech/06 §1）：层级寻址，如 `/people/张三`。
 *
 * 规范化规则：以 "/" 开头、不以 "/" 结尾（根为 "/"）；段内不含 "/"；
 * 段去除首尾空白；连续 "/" 合并。段长度与深度上限来自 `memory.path` 配置，
 * 默认值与 `MemoryPathSection` 一致（由调用方传入实际配置值）。
 */
package com.example.autop.memory

import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome

/** 规范化记忆路径。 */
@JvmInline
@kotlinx.serialization.Serializable
public value class MemoryPath
    private constructor(
        public val value: String,
    ) {
        /** 父路径；根的父为 null。 */
        public val parent: MemoryPath?
            get() {
                if (this == ROOT) return null
                val idx = value.lastIndexOf('/')
                return if (idx == 0) ROOT else MemoryPath(value.substring(0, idx))
            }

        /** 最后一段的名称；根的名称为空串。 */
        public val name: String
            get() = if (this == ROOT) "" else value.substringAfterLast('/')

        /** 拼接子路径（[name] 需已通过校验，此处不再检查）。 */
        public fun child(name: String): MemoryPath =
            if (this == ROOT) MemoryPath("/$name") else MemoryPath("$value/$name")

        /** 路径各段（不含根的空段）。 */
        public fun segments(): List<String> = value.split('/').filter { it.isNotEmpty() }

        override fun toString(): String = value

        public companion object {
            /** 根路径。 */
            public val ROOT: MemoryPath = MemoryPath("/")

            /** 段长度默认上限，与 `MemoryPathSection.maxSegmentChars` 默认值一致。 */
            public const val DEFAULT_MAX_SEGMENT_CHARS: Int = 64

            /** 深度默认上限，与 `MemoryPathSection.maxDepth` 默认值一致。 */
            public const val DEFAULT_MAX_DEPTH: Int = 8

            /**
             * 解析并规范化路径；非法路径返回 `memory.invalid_path`。
             */
            public fun parse(
                raw: String,
                maxSegmentChars: Int = DEFAULT_MAX_SEGMENT_CHARS,
                maxDepth: Int = DEFAULT_MAX_DEPTH,
            ): Outcome<MemoryPath> {
                val segments = raw.split('/').map { it.trim() }.filter { it.isNotEmpty() }
                val error =
                    when {
                        segments.size > maxDepth -> "路径深度超过上限 $maxDepth：$raw"
                        else -> segmentError(segments, maxSegmentChars)
                    }
                return if (error == null) {
                    Outcome.Ok(MemoryPath(segments.joinToString(separator = "/", prefix = "/")))
                } else {
                    invalid(error)
                }
            }

            private fun segmentError(
                segments: List<String>,
                maxSegmentChars: Int,
            ): String? =
                segments.firstOrNull { it.length > maxSegmentChars }?.let { "路径段超过长度上限 $maxSegmentChars：$it" }
                    ?: segments.firstOrNull { it.contains('/') }?.let { "路径段不能包含 /：$it" }

            private fun invalid(message: String): Outcome.Err =
                Outcome.Err(AppError(code = MemoryErrorCodes.INVALID_PATH, message = message))
        }
    }
