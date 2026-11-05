/**
 * 悬浮窗 ComposeView 的 ViewTree owner 注入回归（docs/tech/05 §10，N177）：
 * owner 必须装到**窗口根 view**——Compose 的 WindowRecomposer 从 rootView 查 ViewTree，
 * 只装 ComposeView 自身时面板（PanelRootView 等根容器再包一层）attach 即
 * `ViewTreeLifecycleOwner not found from …PanelRootView` 闪退。
 */
package com.example.autop.feature.overlay

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import com.example.autop.core.ui.overlay.OverlayLifecycleOwner
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34]) // 与 memory-impl/task-impl 既有 Robolectric 测试一致（高 SDK 的 android-all 在 JDK21 下起不来）
class OverlayComposeViewTest {
    @Test
    fun `attach installs view tree owners on root container`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val owner = OverlayLifecycleOwner()
        // 面板形态：内容包在根容器里（PanelRootView 同构场景）。
        val root = FrameLayout(activity)
        root.addView(composeOverlayView(activity, owner) {})
        activity.setContentView(root) // 窗口 attach（含 ComposeView 的 ensureCompositionCreated）
        assertThat(viewTreeOwner(root)).isEqualTo(owner)
        assertThat(viewTreeOwner(root.getChildAt(0))).isEqualTo(owner)
    }

    @Test
    fun `view tree owners installed on view itself`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val owner = OverlayLifecycleOwner()
        val view = composeOverlayView(activity, owner) {}
        assertThat(viewTreeOwner(view)).isEqualTo(owner)
    }

    /**
     * 与产品同机制反射读 `ViewTreeLifecycleOwner.get(View)`——
     * 该类编译期不可解析（N128），测试也走反射。
     */
    private fun viewTreeOwner(view: View): Any? =
        Class
            .forName("androidx.lifecycle.ViewTreeLifecycleOwner")
            .getMethod("get", View::class.java)
            .invoke(null, view)
}
