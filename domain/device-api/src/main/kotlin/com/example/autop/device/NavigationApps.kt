/**
 * 导航应用端口（intent-tool §5.1）：列出规则表中的导航应用及其安装状态。
 */
package com.example.autop.device

/** 导航应用端口。 */
public interface NavigationApps {
    /** 规则表中已安装且启用的导航应用，按 priority 升序。 */
    public suspend fun installed(): List<NavigationApp>

    /**
     * 规则表中的全部应用（含未安装），按 priority 升序。
     * 设置页用它显示“已选但未安装”的名称。
     */
    public fun known(): List<NavigationApp>
}

/** 一个导航应用（来自规则表）。 */
public data class NavigationApp(
    val id: String,
    val label: String,
    val packageName: String,
)
