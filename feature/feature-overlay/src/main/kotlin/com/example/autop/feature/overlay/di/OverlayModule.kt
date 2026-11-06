/**
 * feature-overlay 的 Hilt 绑定（docs/tech/08 §5）：悬浮球端口 → 实现。
 */
package com.example.autop.feature.overlay.di

import com.example.autop.feature.overlay.ball.BallPositionStore
import com.example.autop.feature.overlay.ball.SharedPrefsBallPositionStore
import com.example.autop.feature.overlay.record.TargetPickSession
import com.example.autop.flow.TargetPicker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** 悬浮球依赖绑定。 */
@Module
@InstallIn(SingletonComponent::class)
public object OverlayModule {
    /** 悬浮球位置持久化（SharedPreferences）。 */
    @Provides
    @Singleton
    public fun provideBallPositionStore(impl: SharedPrefsBallPositionStore): BallPositionStore = impl

    /** 编辑器“在手机上重新选取”的拾取端口。 */
    @Provides
    @Singleton
    public fun provideTargetPicker(impl: TargetPickSession): TargetPicker = impl
}
