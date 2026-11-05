/**
 * 悬浮窗内容的 ComposeView 组装（docs/tech/05 §10、N128）。
 *
 * `ViewTreeLifecycleOwner` 等注入器在编译期不可解析（N128），生命周期宿主改由两路提供：
 * ① `CompositionLocalProvider` 显式注入三个 Local owner（主路径）；
 * ② 反射调用 androidx 官方 `ViewTree*.set` 静态 API（兜底，供 ComposeView 内部的
 *    Recomposer 使用；N138 修正 N132：旧式 R 类反射在新版 androidx 分包后必然失败）。
 * 注入目标含窗口根 view（N177：WindowRecomposer 从 rootView 查 ViewTree，
 * 内容包在 PanelRootView 等根容器里时只装 ComposeView 自身会 attach 闪退）。
 */
package com.example.autop.feature.overlay

import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.example.autop.core.ui.overlay.OverlayLifecycleOwner
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.lang.reflect.Modifier

/**
 * 组装悬浮窗内容 View：[content] 内可直接使用 Lifecycle/ViewModel/SavedState API。
 *
 * @param owner 窗口生命周期宿主（由 device-impl 在窗口 attach/detach 时驱动，一次性使用）。
 */
public fun composeOverlayView(
    context: Context,
    owner: OverlayLifecycleOwner,
    content: @Composable () -> Unit,
): View {
    val frame = OwnerRootFrame(context, owner)
    val view = ComposeView(context)
    installViewTreeOwners(frame, owner)
    installViewTreeOwners(view, owner)
    view.setContent {
        val theme = remember { resolveThemeHost(context) }
        CompositionLocalProvider(
            LocalLifecycleOwner provides owner,
            LocalViewModelStoreOwner provides owner,
            LocalSavedStateRegistryOwner provides owner,
        ) {
            // N217：悬浮窗内容统一套应用主题（深浅模式跟随）；主题源缺失时退回裸内容。
            if (theme != null) {
                theme.Wrap { content() }
            } else {
                content()
            }
        }
    }
    frame.addView(
        view,
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
    )
    return frame
}

/** Hilt 入口（[composeOverlayView] 是纯函数拿不到注入，经 application 取主题源）。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface OverlayThemeEntryPoint {
    fun overlayThemeHost(): OverlayThemeHost
}

/**
 * 解析悬浮窗主题源；非 Hilt 环境（单测的普通 Application）取不到时**留痕**并返回 null
 * （同 [installViewTreeOwners] 的兜底留痕口径，N132 教训：静默吞错没法查）。
 */
private fun resolveThemeHost(context: Context): OverlayThemeHost? =
    runCatching {
        EntryPointAccessors
            .fromApplication(context.applicationContext, OverlayThemeEntryPoint::class.java)
            .overlayThemeHost()
    }.onFailure { Log.w(TAG, "overlay_theme_unavailable: $it") }.getOrNull()

/**
 * 悬浮窗内容的根容器：attach 时把 owner 注入**窗口根 view**（N177 回归）——
 * ViewGroup 自身的 `onAttachedToWindow` 先于 children dispatch，此时 rootView 已是
 * 最终根容器（如 PanelRootView），而 Compose 的 WindowRecomposer 恰好从 rootView 查
 * ViewTree owner；只装在内容 ComposeView 上时，包在 PanelRootView 里 attach 即
 * `ViewTreeLifecycleOwner not found from …PanelRootView` 闪退。
 */
private class OwnerRootFrame(
    context: Context,
    private val owner: OverlayLifecycleOwner,
) : FrameLayout(context) {
    override fun onAttachedToWindow() {
        installViewTreeOwners(rootView, owner)
        super.onAttachedToWindow()
    }
}

/**
 * ViewTree owner 兜底注入（N138）：反射官方 `ViewTreeLifecycleOwner.set(View, owner)` 等静态 API。
 * 不碰 R 类——新版 androidx 把 R 按 artifact 分包（`androidx.lifecycle.R$id` 已不存在），
 * 而 `ViewTree*` 类的 set/get 是库自己的公开 API，结构怎么变都稳定。
 * 反射失败**必须留痕**（N132 的静默吞错曾使真机崩溃只能靠解 APK 定位）。
 */
private fun installViewTreeOwners(
    view: View,
    owner: OverlayLifecycleOwner,
) {
    setViewTreeOwner(VIEW_TREE_LIFECYCLE, view, owner)
    setViewTreeOwner(VIEW_TREE_VIEW_MODEL_STORE, view, owner)
    setViewTreeOwner(VIEW_TREE_SAVED_STATE, view, owner)
}

/** 按候选类名反射 `className.set(View, …)` 静态方法安装 owner；全部失败才留痕（含各候选异常）。 */
private fun setViewTreeOwner(
    classNames: List<String>,
    view: View,
    owner: Any,
) {
    val failures = mutableListOf<String>()
    for (className in classNames) {
        val result =
            runCatching {
                val setter =
                    Class
                        .forName(className)
                        .methods
                        .firstOrNull {
                            it.name == SET_METHOD &&
                                Modifier.isStatic(it.modifiers) &&
                                it.parameterCount == SET_PARAM_COUNT &&
                                it.parameterTypes[0] == View::class.java
                        } ?: error("no static set(View, *) found in $className")
                setter.invoke(null, view, owner)
            }
        if (result.isSuccess) return
        failures += "$className: ${result.exceptionOrNull()}"
    }
    Log.w(TAG, "view_tree_install_failed: ${failures.joinToString("; ")}")
}

private const val TAG: String = "OverlayComposeView"

/** ViewTree owner 注入器的静态方法名。 */
private const val SET_METHOD: String = "set"

/** set(View, owner) 的参数个数。 */
private const val SET_PARAM_COUNT: Int = 2

private val VIEW_TREE_LIFECYCLE: List<String> = listOf("androidx.lifecycle.ViewTreeLifecycleOwner")

/**
 * `ViewTreeViewModelStoreOwner` 的包名随 lifecycle 版本漂移：2.11 实测在
 * `androidx.lifecycle`（java 静态版；曾按 `androidx.lifecycle.viewmodel` 猜测位置反射，
 * 真机 ClassNotFoundException，N177）。候选依次尝试，全部失败才留痕。
 */
private val VIEW_TREE_VIEW_MODEL_STORE: List<String> =
    listOf(
        "androidx.lifecycle.ViewTreeViewModelStoreOwner",
        "androidx.lifecycle.viewmodel.ViewTreeViewModelStoreOwner",
    )

private val VIEW_TREE_SAVED_STATE: List<String> = listOf("androidx.savedstate.ViewTreeSavedStateRegistryOwner")
