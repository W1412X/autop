/**
 * 只读设备类工具的多绑定登记（docs/tech/05 §8）。
 */
package com.example.autop.device.impl.di

import com.example.autop.agent.Tool
import com.example.autop.device.impl.tools.ObserveTool
import com.example.autop.device.impl.tools.ReadNotificationsTool
import com.example.autop.device.impl.tools.ScreenshotTool
import com.example.autop.device.impl.tools.WaitTool
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** observe / screenshot / wait / read_notifications。 */
@Module
@InstallIn(SingletonComponent::class)
public object ToolsReadModule {
    @Provides
    @IntoSet
    public fun provideObserveTool(tool: ObserveTool): Tool = tool

    @Provides
    @IntoSet
    public fun provideScreenshotTool(tool: ScreenshotTool): Tool = tool

    @Provides
    @IntoSet
    public fun provideWaitTool(tool: WaitTool): Tool = tool

    @Provides
    @IntoSet
    public fun provideReadNotificationsTool(tool: ReadNotificationsTool): Tool = tool
}
