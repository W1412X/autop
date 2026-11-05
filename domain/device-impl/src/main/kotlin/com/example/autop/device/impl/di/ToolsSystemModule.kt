/**
 * 系统级工具的多绑定登记（docs/tech/05 §8）。
 */
package com.example.autop.device.impl.di

import com.example.autop.agent.Tool
import com.example.autop.device.impl.tools.ClipboardGetTool
import com.example.autop.device.impl.tools.ClipboardSetTool
import com.example.autop.device.impl.tools.DeviceStateTool
import com.example.autop.device.impl.tools.ListAppsTool
import com.example.autop.device.impl.tools.OpenAppTool
import com.example.autop.device.impl.tools.OpenUriTool
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** open_app / open_uri / list_apps / device_state / clipboard_get / clipboard_set。 */
@Module
@InstallIn(SingletonComponent::class)
public object ToolsSystemModule {
    @Provides
    @IntoSet
    public fun provideOpenAppTool(tool: OpenAppTool): Tool = tool

    @Provides
    @IntoSet
    public fun provideOpenUriTool(tool: OpenUriTool): Tool = tool

    @Provides
    @IntoSet
    public fun provideListAppsTool(tool: ListAppsTool): Tool = tool

    @Provides
    @IntoSet
    public fun provideDeviceStateTool(tool: DeviceStateTool): Tool = tool

    @Provides
    @IntoSet
    public fun provideClipboardGetTool(tool: ClipboardGetTool): Tool = tool

    @Provides
    @IntoSet
    public fun provideClipboardSetTool(tool: ClipboardSetTool): Tool = tool
}
