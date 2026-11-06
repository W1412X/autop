/**
 * 编辑器“在手机上重新选取”端口（flows-recording-recovery.md V7）：让用户在目标应用上点选元素，
 * 返回转换好的 [Target]。实现在悬浮窗模块（需要整屏拾取层）。
 */
package com.example.autop.flow

import com.example.autop.core.common.Outcome

/** 目标元素重选。 */
public interface TargetPicker {
    /** 进入拾取，直到用户点选或失败；返回的目标可直接写回节点。 */
    public suspend fun pickTarget(): Outcome<Target>
}
