/**
 * 设备侧状态端口装配（docs/tech/05 §2、WP7 占位）：
 * 无障碍连接状态、用户触摸、悬浮窗宿主/权限、窗口脱敏决策。
 */
package com.example.autop.device.impl.di

import com.example.autop.device.AccessibilityStatus
import com.example.autop.device.NotificationFeed
import com.example.autop.device.OverlayHost
import com.example.autop.device.OverlayPermission
import com.example.autop.device.UserActionMonitor
import com.example.autop.device.UserActivityMonitor
import com.example.autop.device.impl.NotificationFeedImpl
import com.example.autop.device.impl.PolicyWindowRedactor
import com.example.autop.device.impl.ServiceHolder
import com.example.autop.device.impl.WindowRedactor
import com.example.autop.device.impl.overlay.OverlayPermissionImpl
import com.example.autop.device.impl.overlay.OverlayWindowManager
import com.example.autop.device.impl.record.RecordingActionMonitor
import com.example.autop.device.impl.touch.UserActivityMonitorImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** 状态端口接口到实现的绑定。 */
@Module
@InstallIn(SingletonComponent::class)
public object DeviceStatusModule {
    @Provides
    @Singleton
    public fun provideAccessibilityStatus(impl: ServiceHolder): AccessibilityStatus = impl

    @Provides
    @Singleton
    public fun provideNotificationFeed(impl: NotificationFeedImpl): NotificationFeed = impl

    @Provides
    @Singleton
    public fun provideUserActivityMonitor(impl: UserActivityMonitorImpl): UserActivityMonitor = impl

    @Provides
    @Singleton
    public fun provideUserActionMonitor(impl: RecordingActionMonitor): UserActionMonitor = impl

    @Provides
    @Singleton
    public fun provideOverlayHost(impl: OverlayWindowManager): OverlayHost = impl

    @Provides
    @Singleton
    public fun provideOverlayPermission(impl: OverlayPermissionImpl): OverlayPermission = impl

    @Provides
    @Singleton
    public fun provideWindowRedactor(impl: PolicyWindowRedactor): WindowRedactor = impl
}
