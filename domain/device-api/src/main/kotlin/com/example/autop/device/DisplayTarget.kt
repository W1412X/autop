package com.example.autop.device

import com.example.autop.core.common.Outcome
import com.example.autop.core.common.RunId
import kotlin.coroutines.CoroutineContext

/** 一次 Run 的不可变屏幕目标；显示资源由 [RunDisplayController] 持有。 */
public sealed interface DisplayTarget : CoroutineContext.Element {
    public data object Main : DisplayTarget {
        override val key: CoroutineContext.Key<*> get() = Key
    }

    public data class Background(
        val owner: RunId,
        val displayId: Int,
        val width: Int,
        val height: Int,
        val dpi: Int,
    ) : DisplayTarget {
        override val key: CoroutineContext.Key<*> get() = Key
    }

    public companion object Key : CoroutineContext.Key<DisplayTarget>
}

/** 当前协程的显示目标；缺失时保持真实屏行为。 */
public suspend fun currentDisplayTarget(): DisplayTarget? = kotlinx.coroutines.currentCoroutineContext()[DisplayTarget]

/** Run 级显示会话生命周期。后台创建失败不会返回真实屏目标。 */
public interface RunDisplayController {
    public suspend fun open(
        owner: RunId,
        backgroundScreen: Boolean,
    ): Outcome<DisplayTarget>

    public suspend fun release(owner: RunId)

    public suspend fun closeAll()
}
