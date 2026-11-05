/**
 * 工具坐标换算（docs/design/observation-modes-coordinate-actions §1）：
 * 工具链固定使用内容区 0–1000 归一化坐标，不再跟随模型 `coordSpace`。
 */
package com.example.autop.device.impl

import com.example.autop.core.common.getOrNull
import com.example.autop.core.prompt.PromptRepository
import com.example.autop.device.Rect
import com.example.autop.device.core.CoordMapper
import com.example.autop.device.core.PixelSize
import com.example.autop.llm.CoordSpace
import javax.inject.Inject
import javax.inject.Singleton

/** 0–1000 坐标 → 屏幕像素，以及越界提示。 */
@Singleton
public class CoordSpaceResolver
    @Inject
    constructor(
        private val prompts: PromptRepository,
    ) {
        /** 换算器：[area] 为内容区（像素，null=全屏）。 */
        public fun mapper(
            screen: PixelSize,
            area: Rect?,
        ): CoordMapper = CoordMapper(CoordSpace.NORMALIZED_1000, screen, screen, area)

        /** 超范围提示（模板 `device.hint.coord_out_of_range`，范围固定 0–1000）。 */
        public fun outOfRangeHint(): String =
            prompts
                .render(PROMPT_COORD_OUT_OF_RANGE, mapOf(VAR_RANGE to RANGE_TEXT))
                .getOrNull()
                ?.text
                .orEmpty()

        private companion object {
            const val PROMPT_COORD_OUT_OF_RANGE = "device.hint.coord_out_of_range"
            const val VAR_RANGE = "range"
            const val RANGE_TEXT = "0–1000"
        }
    }
