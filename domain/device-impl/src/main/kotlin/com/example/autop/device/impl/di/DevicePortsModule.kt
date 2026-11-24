/**
 * device 领域端口装配（docs/tech/05）：屏幕读取、手势、节点解析、稳定等待、应用启动、设备状态。
 */
package com.example.autop.device.impl.di

import android.content.Context
import com.example.autop.agent.ReplayStateProbe
import com.example.autop.agent.ReplayTargetResolver
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.Logger
import com.example.autop.device.AppLauncher
import com.example.autop.device.DeviceStateProvider
import com.example.autop.device.ElementPicker
import com.example.autop.device.GestureController
import com.example.autop.device.NavigationApps
import com.example.autop.device.ObserveModeAvailability
import com.example.autop.device.PairingDiscovery
import com.example.autop.device.RunDisplayController
import com.example.autop.device.ScreenLease
import com.example.autop.device.ScreenReader
import com.example.autop.device.ShellInput
import com.example.autop.device.ShellKeepAlive
import com.example.autop.device.SpeechInput
import com.example.autop.device.TouchShield
import com.example.autop.device.UiStabilizer
import com.example.autop.device.UpdateChecker
import com.example.autop.device.VisualScreenParser
import com.example.autop.device.impl.DeviceStateProviderImpl
import com.example.autop.device.impl.ObserveModeResolver
import com.example.autop.device.impl.ScreenLeaseImpl
import com.example.autop.device.impl.bgdisplay.BackgroundDisplayManagerImpl
import com.example.autop.device.impl.bgdisplay.route.RoutedAppLauncher
import com.example.autop.device.impl.bgdisplay.route.RoutedGestureController
import com.example.autop.device.impl.bgdisplay.route.RoutedScreenReader
import com.example.autop.device.impl.bgdisplay.route.RoutedUiStabilizer
import com.example.autop.device.impl.intent.NavigationAppsImpl
import com.example.autop.device.impl.observation.ReplayStateProbeImpl
import com.example.autop.device.impl.observation.ReplayTargetResolverImpl
import com.example.autop.device.impl.record.ElementPickerImpl
import com.example.autop.device.impl.shell.AdbShellInput
import com.example.autop.device.impl.shell.PairingDiscoveryImpl
import com.example.autop.device.impl.shell.ShellKeepAliveImpl
import com.example.autop.device.impl.speech.SystemSpeechInput
import com.example.autop.device.impl.touch.TouchShieldController
import com.example.autop.device.impl.update.GitHubUpdateChecker
import com.example.autop.device.impl.update.UpdateReadyNotifier
import com.example.autop.device.impl.vision.VisualScreenParserImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** 端口接口到实现的绑定。 */
@Module
@Suppress("TooManyFunctions") // N114：DI 绑定/提供模块只增不减，函数数随端口增长不可压缩。
@InstallIn(SingletonComponent::class)
public object DevicePortsModule {
    @Provides
    @Singleton
    public fun provideScreenReader(impl: RoutedScreenReader): ScreenReader = impl

    @Provides
    @Singleton
    public fun provideElementPicker(impl: ElementPickerImpl): ElementPicker = impl

    @Provides
    @Singleton
    public fun provideGestureController(impl: RoutedGestureController): GestureController = impl

    @Provides
    @Singleton
    public fun provideUiStabilizer(impl: RoutedUiStabilizer): UiStabilizer = impl

    @Provides
    @Singleton
    public fun provideAppLauncher(impl: RoutedAppLauncher): AppLauncher = impl

    @Provides
    @Singleton
    public fun provideRunDisplayController(impl: BackgroundDisplayManagerImpl): RunDisplayController = impl

    @Provides
    @Singleton
    public fun provideNavigationApps(impl: NavigationAppsImpl): NavigationApps = impl

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

    /** 语音转文字（系统自带语音识别）。 */
    @Provides
    @Singleton
    public fun provideSpeechInput(impl: SystemSpeechInput): SpeechInput = impl

    /** shell 通道后台保活（N203）。 */
    @Provides
    @Singleton
    public fun provideShellKeepAlive(impl: ShellKeepAliveImpl): ShellKeepAlive = impl

    /** 无线调试配对自动发现（mDNS 可配对服务 → 通知/自动填端口）。 */
    @Provides
    @Singleton
    public fun providePairingDiscovery(impl: PairingDiscoveryImpl): PairingDiscovery = impl

    /** 观测模式可用性（设置页展示各模式能否用）。 */
    @Provides
    @Singleton
    public fun provideObserveModeAvailability(impl: ObserveModeResolver): ObserveModeAvailability = impl

    /** 应用更新检测（GitHub Releases 静默检查 + 后台下载 + 系统安装器）。 */
    @Provides
    @Singleton
    public fun provideUpdateChecker(
        @ApplicationContext context: Context,
        dispatchers: DispatcherProvider,
        logger: Logger,
        readyNotifier: UpdateReadyNotifier,
    ): UpdateChecker = GitHubUpdateChecker(context, dispatchers, logger, readyNotifier)
}
