/**
 * 晨间问候的持久化：记录今天（epoch 天）是否已经播过哈欠与招手，保证每天只播一次。
 */
package com.example.autop.feature.overlay.ball

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 晨间问候记录端口。 */
public interface BallGreetingStore {
    /** 最近一次播放晨间问候的日期（epoch 天）；从未播过时为 null。 */
    public fun lastGreetDay(): Long?

    /** 记录今天已播放晨间问候。 */
    public fun markGreetDay(day: Long)
}

/** [BallGreetingStore] 的 SharedPreferences 实现。 */
@Singleton
public class SharedPrefsBallGreetingStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : BallGreetingStore {
        private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        override fun lastGreetDay(): Long? = if (prefs.contains(KEY_DAY)) prefs.getLong(KEY_DAY, 0L) else null

        override fun markGreetDay(day: Long) {
            prefs.edit().putLong(KEY_DAY, day).apply()
        }

        private companion object {
            const val PREFS_NAME = "overlay_ball_greeting"
            const val KEY_DAY = "last_greet_day"
        }
    }
