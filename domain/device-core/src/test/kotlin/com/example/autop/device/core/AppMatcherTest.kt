package com.example.autop.device.core

import com.example.autop.device.AppInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppMatcherTest {
    private val apps =
        listOf(
            AppInfo("com.a.wechat", "微信"),
            AppInfo("com.b.wechatpay", "微信支付"),
            AppInfo("com.c.mini", "小微信"),
            AppInfo("com.d.map", "地图"),
        )

    @Test
    fun `rank puts exact then prefix then contains matches first`() {
        val ranked = AppMatcher.rank(apps, "微信").map { it.label }
        assertThat(ranked).containsExactly("微信", "微信支付", "小微信").inOrder()
    }

    @Test
    fun `match accepts a package name directly`() {
        assertThat(AppMatcher.match("com.d.map", apps)).isEqualTo(AppMatch.Found(AppInfo("com.d.map", "地图")))
    }

    @Test
    fun `match returns the single prefix hit`() {
        assertThat(AppMatcher.match("支付", apps)).isEqualTo(AppMatch.Found(AppInfo("com.b.wechatpay", "微信支付")))
    }

    @Test
    fun `match prefers the unique exact label over other hits`() {
        assertThat(AppMatcher.match("微信", apps)).isEqualTo(AppMatch.Found(AppInfo("com.a.wechat", "微信")))
    }

    @Test
    fun `match reports ambiguity with the ranked candidates`() {
        val result = AppMatcher.match("微", apps) as AppMatch.Ambiguous
        assertThat(result.candidates.map { it.label }).containsExactly("微信", "微信支付", "小微信").inOrder()
    }

    @Test
    fun `match caps candidates at five`() {
        val many = (1..7).map { AppInfo("com.x.app$it", "应用$it") }
        val result = AppMatcher.match("应用", many) as AppMatch.Ambiguous
        assertThat(result.candidates).hasSize(AppMatcher.MAX_CANDIDATES)
    }

    @Test
    fun `match reports not found`() {
        assertThat(AppMatcher.match("不存在", apps)).isEqualTo(AppMatch.NotFound)
    }
}
