/**
 * 记忆页导航回调（docs/tech/08 §4.4）：app 的 NavHost 实现。
 */
package com.example.autop.feature.memory

/** 记忆域导航接口。 */
public interface MemoryNavigator {
    /** 打开记忆浏览页（[path] 为当前目录路径）。 */
    public fun openBrowse(path: String)

    /** 打开条目编辑页。 */
    public fun openNode(path: String)

    /** 直接打开新建页（在 [parent] 目录下新建条目或目录；名称在编辑页内填写）。 */
    public fun openNewNode(
        parent: String,
        isDirectory: Boolean,
    )

    /** 打开最近变更页。 */
    public fun openChanges()

    /** 返回上一页。 */
    public fun navigateBack()
}
