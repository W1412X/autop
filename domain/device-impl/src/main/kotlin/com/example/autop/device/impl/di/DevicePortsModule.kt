/**
 * device 领域端口装配（docs/tech/05）：屏幕读取、手势、节点解析、稳定等待、应用启动、设备状态。
 */
package com.example.autop.device.impl.di

import com.example.autop.agent.ReplayStateProbe
import com.example.autop.agent.ReplayTargetResolver
import com.example.autop.device.AppLauncher
import com.example.autop.device.DeviceStateProvider
import com.example.autop.device.ElementPicker
import com.example.autop.device.GestureController
import com.example.autop.device.NodeActionBlockStore
import com.example.autop.device.ObserveModeAvailability
import com.example.autop.device.ScreenLease
import com.example.autop.device.ScreenReader
import com.example.autop.device.ShellInput
import com.example.autop.device.ShellKeepAlive
import com.example.autop.device.TouchShield
import com.example.autop.device.UiStabilizer
import com.example.autop.device.VisualScreenParser
import com.example.autop.device.impl.AppLauncherImpl
import com.example.autop.device.impl.DeviceStateProviderImpl
import com.example.autop.device.impl.GestureControllerImpl
import com.example.autop.device.impl.ObserveModeResolver
import com.example.autop.device.impl.ScreenLeaseImpl
import com.example.autop.device.impl.ScreenReaderImpl
import com.example.autop.device.impl.UiStabilizerImpl
import com.example.autop.device.impl.nodeaction.FileNodeActionBlockStore
import com.example.autop.device.impl.observation.ReplayStateProbeImpl
import com.example.autop.device.impl.observation.ReplayTargetResolverImpl
import com.example.autop.device.impl.record.ElementPickerImpl
import com.example.autop.device.impl.shell.AdbShellInput
import com.example.autop.device.impl.shell.ShellKeepAliveImpl
import com.example.autop.device.impl.touch.TouchShieldController
import com.example.autop.device.impl.vision.VisualScreenParserImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** 端口接口到实现的绑定。 */
@Module
@Suppress("TooManyFunctions") // N114：DI 绑定/提供模块只增不减，函数数随端口增长不可压缩。
@InstallIn(SingletonComponent::class)
public object DevicePortsModule {
    @Provides
    @Singleton
    public fun provideScreenReader(impl: ScreenReaderImpl): ScreenReader = impl

    @Provides
    @Singleton
    public fun provideElementPicker(impl: ElementPickerImpl): ElementPicker = impl

    @Provides
    @Singleton
    public fun provideGestureController(impl: GestureControllerImpl): GestureController = impl

    @Provides
    @Singleton
    public fun provideUiStabilizer(impl: UiStabilizerImpl): UiStabilizer = impl

    @Provides
    @Singleton
    public fun provideAppLauncher(impl: AppLauncherImpl): AppLauncher = impl

    @Provides
    @Singleton
    public fun provideDeviceStateProvider(impl: DeviceStateProviderImpl): DeviceStateProvider = impl

    @Provides
    @Singleton
    public fun provideScreenLease(): ScreenLease = ScreenLeaseImpl()

    @Provides
    @Singleton
    public fun provideReplayStateProbe(impl: ReplayStateProbeImpl): ReplayStateProbe = impl

    @Provides
    @Singleton
    public fun provideReplayTargetResolver(impl: ReplayTargetResolverImpl): ReplayTargetResolver = impl

    @Provides
    @Singleton
    public fun provideTouchShield(impl: TouchShieldController): TouchShield = impl

    @Provides
    @Singleton
    public fun provideVisualScreenParser(impl: VisualScreenParserImpl): VisualScreenParser = impl

    @Provides
    @Singleton
    public fun provideShellInput(impl: AdbShellInput): ShellInput = impl

    /** shell 通道后台保活（N203）。 */
    @Provides
    @Singleton
    public fun provideShellKeepAlive(impl: ShellKeepAliveImpl): ShellKeepAlive = impl

    /** 节点动作屏蔽记录（ADR-0015）。 */
    @Provides
    @Singleton
    public fun provideNodeActionBlockStore(impl: FileNodeActionBlockStore): NodeActionBlockStore = impl

    /** 观测模式可用性（设置页展示各模式能否用）。 */
    @Provides
    @Singleton
    public fun provideObserveModeAvailability(impl: ObserveModeResolver): ObserveModeAvailability = impl
}
