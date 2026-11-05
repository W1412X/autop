/**
 * 调试页挂载点（docs/tech/08 §4.6、docs/tech/10 WP4）：
 * 实现（含 [com.example.autop.agent.ToolContext] 的调试实现）放在 feature-settings 的
 * debug 源集，release 构建没有实现、入口显示"仅调试构建可用"。
 */
package com.example.autop.feature.settings.debug

import androidx.compose.runtime.Composable
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** 调试页（debug 源集实现）。 */
public interface DebugPage {
    /** 调试页内容（自带顶栏与返回）。 */
    @Composable
    public fun Content(onBack: () -> Unit)
}

/** 调试页可选绑定：debug 源集提供实现，release 构建为空。 */
@Module
@InstallIn(SingletonComponent::class)
public abstract class DebugPageModule {
    /** debug 源集的实现注入到 [java.util.Optional]；release 无绑定。 */
    @BindsOptionalOf
    public abstract fun debugPage(): DebugPage
}
