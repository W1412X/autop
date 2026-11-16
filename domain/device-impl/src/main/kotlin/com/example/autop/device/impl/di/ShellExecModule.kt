/**
 * shell 执行端口装配（shell-exec 设计 §5，ADR-0042）：`ShellExec` 端口到 [ShellPool] 的绑定，
 * 以及资源参数（来自配置 `device.shell.exec.*`，shell-exec 设计 §9.1）。
 *
 * 资源参数在进程启动时读取一次；修改配置后需要重启应用才生效。
 */
package com.example.autop.device.impl.di

import com.example.autop.core.config.ConfigProvider
import com.example.autop.core.config.DeviceConfig
import com.example.autop.device.ShellExec
import com.example.autop.device.impl.shell.ShellExecLimits
import com.example.autop.device.impl.shell.ShellPool
import com.example.autop.device.impl.shell.shellExecLimitsOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
public object ShellExecModule {
    @Provides
    @Singleton
    public fun provideShellExec(impl: ShellPool): ShellExec = impl

    @Provides
    @Singleton
    public fun provideShellExecLimits(configProvider: ConfigProvider): ShellExecLimits =
        shellExecLimitsOf(configProvider.current()[DeviceConfig.SHELL].exec)
}
