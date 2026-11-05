package com.example.autop.core.platform.di

import android.content.Context
import android.content.pm.ApplicationInfo
import com.example.autop.core.common.AnimationScaleReader
import com.example.autop.core.common.Clock
import com.example.autop.core.common.DeviceQuirks
import com.example.autop.core.common.DiagnosticLogSource
import com.example.autop.core.common.DispatcherProvider
import com.example.autop.core.common.IdGenerator
import com.example.autop.core.common.LogFiles
import com.example.autop.core.common.Logger
import com.example.autop.core.common.PermissionChecks
import com.example.autop.core.common.PlatformCapabilities
import com.example.autop.core.common.SecretStore
import com.example.autop.core.common.SystemClock
import com.example.autop.core.common.UuidV7IdGenerator
import com.example.autop.core.platform.AndroidDispatcherProvider
import com.example.autop.core.platform.AndroidLogger
import com.example.autop.core.platform.AndroidPermissionChecks
import com.example.autop.core.platform.DeviceQuirksImpl
import com.example.autop.core.platform.KeystoreSecretStore
import com.example.autop.core.platform.PlatformCapabilitiesImpl
import com.example.autop.core.platform.RollingLogFile
import com.example.autop.core.platform.SettingsAnimationScaleReader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * core-common 平台端口的装配（docs/tech/02 §4）：Clock、DispatcherProvider、Logger、
 * PlatformCapabilities、SecretStore、DeviceQuirks、IdGenerator、AnimationScaleReader。
 *
 * Hilt 生成的 Java 工厂需要调用本 object 的 @Provides 方法，故保持 public
 * （同 ConfigModule 的 mangling 教训，见 questions.md N12）。
 */
@Suppress("TooManyFunctions") // Hilt 装配模块：各 provider 一一对应组件，拆分无收益
@Module
@InstallIn(SingletonComponent::class)
public object PlatformModule {
    @Provides
    @Singleton
    public fun provideClock(): Clock = SystemClock()

    @Provides
    @Singleton
    public fun provideDispatcherProvider(): DispatcherProvider = AndroidDispatcherProvider()

    /** 日志文件（`files/logs/` 滚动持久化，崩溃后唯一取证来源，questions.md N165）。 */
    @Provides
    @Singleton
    public fun provideLogFiles(
        @ApplicationContext context: Context,
    ): LogFiles = RollingLogFile(File(context.filesDir, "logs"))

    /** 具体类型也暴露一份：配置变化时调整环形缓冲容量与 debug 开关（questions.md N15/N165）。 */
    @Provides
    @Singleton
    public fun provideAndroidLogger(
        clock: Clock,
        @ApplicationContext context: Context,
        logFiles: LogFiles,
    ): AndroidLogger {
        val isDebug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return AndroidLogger(clock, isDebug, logFiles = logFiles)
    }

    @Provides
    @Singleton
    public fun provideLogger(logger: AndroidLogger): Logger = logger

    /** 诊断日志页读取环形缓冲（docs/tech/02 §4.4）。 */
    @Provides
    @Singleton
    public fun provideDiagnosticLogSource(logger: AndroidLogger): DiagnosticLogSource = logger

    /** 权限与电池优化白名单状态（WP11 引导与健康检查）。 */
    @Provides
    @Singleton
    public fun providePermissionChecks(
        @ApplicationContext context: Context,
        capabilities: PlatformCapabilities,
    ): PermissionChecks = AndroidPermissionChecks(context, capabilities)

    /** SDK_INT 的唯一读取点；[PlatformCapabilitiesImpl.sdkInt] 供 DeviceQuirks 匹配复用。 */
    @Provides
    @Singleton
    public fun providePlatformCapabilitiesImpl(): PlatformCapabilitiesImpl = PlatformCapabilitiesImpl()

    @Provides
    @Singleton
    public fun providePlatformCapabilities(impl: PlatformCapabilitiesImpl): PlatformCapabilities = impl

    @Provides
    @Singleton
    public fun provideSecretStore(
        @ApplicationContext context: Context,
    ): SecretStore = KeystoreSecretStore(context)

    @Provides
    @Singleton
    public fun provideDeviceQuirks(
        @ApplicationContext context: Context,
        capabilities: PlatformCapabilitiesImpl,
    ): DeviceQuirks = DeviceQuirksImpl.fromAssets(context, capabilities.sdkInt)

    @Provides
    @Singleton
    public fun provideIdGenerator(clock: Clock): IdGenerator = UuidV7IdGenerator(clock)

    /** 系统动画缩放读取（docs/tech/08 §1.5）。 */
    @Provides
    @Singleton
    public fun provideAnimationScaleReader(
        @ApplicationContext context: Context,
    ): AnimationScaleReader = SettingsAnimationScaleReader(context)
}
