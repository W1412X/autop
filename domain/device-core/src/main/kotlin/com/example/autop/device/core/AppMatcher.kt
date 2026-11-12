/**
 * 应用名称匹配（intent-tool §4.7）：纯函数，供 `open_app` 与 `intent` 的 share/settings 共用。
 */
package com.example.autop.device.core

import com.example.autop.device.AppInfo

/** 应用名称匹配（纯函数，无 Android 依赖）。 */
public object AppMatcher {
    /**
     * 名称匹配排序：完全相等 > 前缀 > 包含，同级保持原顺序。
     * 与 `open_app` 原先的 `matchApps` 行为完全一致。
     */
    public fun rank(
        all: List<AppInfo>,
        name: String,
    ): List<AppInfo> {
        val exact = all.filter { it.label == name }
        val prefix = all.filter { it.label.startsWith(name) && it.label != name }
        val contains = all.filter { it.label.contains(name) && !it.label.startsWith(name) }
        return exact + prefix + contains
    }
}
