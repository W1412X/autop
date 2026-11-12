/**
 * 系统级工具的多绑定登记（docs/tech/05 §8）。
 */
package com.example.autop.device.impl.di

import com.example.autop.agent.Tool
import com.example.autop.device.impl.tools.ClipboardGetTool
import com.example.autop.device.impl.tools.ClipboardSetTool
import com.example.autop.device.impl.tools.IntentTool
import com.example.autop.device.impl.tools.ListAppsTool
import com.example.autop.device.impl.tools.OpenAppTool
import com.example.autop.device.impl.tools.RestartAppTool
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** open_app / intent / list_apps / device_state / clipboard / restart_app。 */
@Module
@InstallIn(SingletonComponent::class)
public object ToolsSystemModule {
    @Provides
    @IntoSet
    public fun provideOpenAppTool(tool: OpenAppTool): Tool = tool

    @Provides
    @IntoSet
    public fun provideRestartAppTool(tool: RestartAppTool): Tool = tool

    @Provides
    @IntoSet
    public fun provideIntentTool(tool: IntentTool): Tool = tool

    @Provides
    @IntoSet
    public fun provideListAppsTool(tool: ListAppsTool): Tool = tool

    @Provides
    @IntoSet
    public fun provideClipboardGetTool(tool: ClipboardGetTool): Tool = tool

    @Provides
    @IntoSet
    public fun provideClipboardSetTool(tool: ClipboardSetTool): Tool = tool
}
