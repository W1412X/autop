/**
 * shell 工具注册（shell-exec 设计 §3、ADR-0040）：`tool.run_shell` 以多绑定加入 ToolRouter 的工具集合。
 * 是否可见由工具自身的 [com.example.autop.agent.Tool.minMode] 与 `isAvailable` 决定，不在这里过滤。
 */
package com.example.autop.device.impl.di

import com.example.autop.agent.Tool
import com.example.autop.device.impl.tools.RunShellTool
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
public object ToolsShellModule {
    @Provides
    @IntoSet
    public fun provideRunShellTool(tool: RunShellTool): Tool = tool
}
