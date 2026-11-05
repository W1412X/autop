package com.example.autop.core.ui.overlay

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * 悬浮窗 ComposeView 的生命周期宿主（docs/tech/05 §10，供 feature-overlay 使用）：
 * 提供 `LifecycleOwner` + `SavedStateRegistryOwner` + `ViewModelStoreOwner`。
 *
 * 窗口 attach → `ON_RESUME`，detach → `ON_DESTROY` 并清理 [viewModelStore]。
 * 一次性使用：`ON_DESTROY` 后不可再次 attach，重建窗口时新建实例。
 * [onWindowAttached] / [onWindowDetached] 必须在主线程调用。
 */
public class OverlayLifecycleOwner :
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    init {
        savedStateController.performRestore(null)
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    /** 窗口 attach：生命周期推进到 RESUMED。 */
    public fun onWindowAttached() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    /** 窗口 detach：生命周期推进到 DESTROYED 并清理 ViewModelStore。 */
    public fun onWindowDetached() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}
