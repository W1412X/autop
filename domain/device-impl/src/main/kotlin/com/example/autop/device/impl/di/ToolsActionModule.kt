/**
 * 屏幕写操作类工具的多绑定登记（docs/tech/05 §8）。
 */
package com.example.autop.device.impl.di

import com.example.autop.agent.Tool
import com.example.autop.device.impl.tools.GlobalActionTool
import com.example.autop.device.impl.tools.InputTextTool
import com.example.autop.device.impl.tools.ScrollNodeTool
import com.example.autop.device.impl.tools.ScrollTool
import com.example.autop.device.impl.tools.SwipeTool
import com.example.autop.device.impl.tools.TapNodeTool
import com.example.autop.device.impl.tools.TapTool
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** tap / tap_node / input_text / scroll / scroll_node / swipe / global_action。 */
@Module
@InstallIn(SingletonComponent::class)
public object ToolsActionModule {
    @Provides
    @IntoSet
    public fun provideTapTool(tool: TapTool): Tool = tool

    @Provides
    @IntoSet
    public fun provideTapNodeTool(tool: TapNodeTool): Tool = tool

    @Provides
    @IntoSet
    public fun provideInputTextTool(tool: InputTextTool): Tool = tool

    @Provides
    @IntoSet
    public fun provideScrollTool(tool: ScrollTool): Tool = tool

    @Provides
    @IntoSet
    public fun provideScrollNodeTool(tool: ScrollNodeTool): Tool = tool

    @Provides
    @IntoSet
    public fun provideSwipeTool(tool: SwipeTool): Tool = tool

    @Provides
    @IntoSet
    public fun provideGlobalActionTool(tool: GlobalActionTool): Tool = tool
}
