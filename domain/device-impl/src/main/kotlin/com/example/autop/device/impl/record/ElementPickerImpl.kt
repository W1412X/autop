/**
 * 元素拾取实现：读取当前无障碍快照，取点位上面积最小、且带文字/描述或可点击的节点。
 */
package com.example.autop.device.impl.record

import com.example.autop.core.common.AppError
import com.example.autop.core.common.Outcome
import com.example.autop.device.DeviceErrorCodes
import com.example.autop.device.ElementPicker
import com.example.autop.device.NodeFlags
import com.example.autop.device.NodeSnapshot
import com.example.autop.device.PickedElement
import com.example.autop.device.Rect
import com.example.autop.device.ScreenReader
import com.example.autop.device.ScreenSnapshot
import javax.inject.Inject
import javax.inject.Singleton

/** [ElementPicker] 实现。 */
@Singleton
public class ElementPickerImpl
    @Inject
    constructor(
        private val screenReader: ScreenReader,
    ) : ElementPicker {
        override suspend fun pickAt(
            x: Int,
            y: Int,
        ): Outcome<PickedElement> =
            when (val got = screenReader.snapshot()) {
                is Outcome.Err -> got
                is Outcome.Ok -> pickFrom(got.value, x, y)
            }

        private fun pickFrom(
            snap: ScreenSnapshot,
            x: Int,
            y: Int,
        ): Outcome<PickedElement> {
            val hit =
                snap.nodes
                    .filter { contains(it.bounds, x, y) && isPickable(it) }
                    .minByOrNull { it.bounds.width * it.bounds.height }
                    ?: return Outcome.Err(
                        AppError(
                            code = DeviceErrorCodes.ELEMENT_NOT_FOUND,
                            message = "该位置没有可拾取的元素",
                            hint = "换一个位置再拾取",
                        ),
                    )
            return Outcome.Ok(
                PickedElement(
                    text = hit.text,
                    contentDescription = hit.contentDesc,
                    className = hit.className,
                    viewId = hit.viewId,
                    bounds = hit.bounds,
                ),
            )
        }

        private fun isPickable(node: NodeSnapshot): Boolean =
            (node.flags and NodeFlags.VISIBLE) != 0 &&
                (
                    !node.text.isNullOrEmpty() ||
                        !node.contentDesc.isNullOrEmpty() ||
                        (node.flags and NodeFlags.CLICKABLE) != 0
                )

        private fun contains(
            r: Rect,
            x: Int,
            y: Int,
        ): Boolean = x >= r.l && x < r.r && y >= r.t && y < r.b
    }
