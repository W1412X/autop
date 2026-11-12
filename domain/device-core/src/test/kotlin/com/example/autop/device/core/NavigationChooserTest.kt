package com.example.autop.device.core

import com.example.autop.device.NavigationApp
import com.example.autop.device.core.navigation.NavigationRules
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File

class NavigationChooserTest {
    private val amap = NavigationApp("amap", "高德地图", "com.autonavi.minimap")
    private val baidu = NavigationApp("baidu", "百度地图", "com.baidu.BaiduMap")
    private val tencent = NavigationApp("tencent", "腾讯地图", "com.tencent.map")
    private val google = NavigationApp("google", "Google 地图", "com.google.android.apps.maps")
    private val rules = realRules()

    @Test
    fun `requested map that is installed wins`() {
        val choice = NavigationChooser.choose("baidu", "auto", listOf(amap, baidu), NavMode.DRIVE, rules)
        assertThat(choice).isEqualTo(NavChoice.Use(baidu, NavReason.REQUESTED))
    }

    @Test
    fun `requested map that is not installed is reported`() {
        val choice = NavigationChooser.choose("baidu", "auto", listOf(amap), NavMode.DRIVE, rules)
        assertThat(choice).isEqualTo(NavChoice.RequestedNotInstalled("baidu"))
    }

    @Test
    fun `requested map is reported even when nothing is installed`() {
        val choice = NavigationChooser.choose("baidu", "auto", emptyList(), NavMode.DRIVE, rules)
        assertThat(choice).isEqualTo(NavChoice.RequestedNotInstalled("baidu"))
    }

    @Test
    fun `installed preferred app is used`() {
        val choice = NavigationChooser.choose(null, "tencent", listOf(amap, tencent), NavMode.DRIVE, rules)
        assertThat(choice).isEqualTo(NavChoice.Use(tencent, NavReason.PREFERRED))
    }

    @Test
    fun `missing preferred app falls back to automatic choice`() {
        val choice = NavigationChooser.choose(null, "tencent", listOf(amap, google), NavMode.DRIVE, rules)
        assertThat(choice).isEqualTo(NavChoice.Use(amap, NavReason.AUTO))
    }

    @Test
    fun `auto picks the highest priority installed app`() {
        val choice = NavigationChooser.choose(null, "auto", listOf(baidu, google), NavMode.DRIVE, rules)
        assertThat(choice).isEqualTo(NavChoice.Use(baidu, NavReason.AUTO))
    }

    @Test
    fun `unknown preference behaves like auto`() {
        val choice = NavigationChooser.choose(null, "whatever", listOf(google), NavMode.DRIVE, rules)
        assertThat(choice).isEqualTo(NavChoice.Use(google, NavReason.AUTO))
    }

    @Test
    fun `ask with several apps returns the options`() {
        val choice = NavigationChooser.choose(null, "ask", listOf(amap, baidu, tencent), NavMode.DRIVE, rules)
        assertThat(choice).isEqualTo(NavChoice.Ask(listOf(amap, baidu, tencent)))
    }

    @Test
    fun `ask with a single app does not ask`() {
        val choice = NavigationChooser.choose(null, "ask", listOf(google), NavMode.DRIVE, rules)
        assertThat(choice).isEqualTo(NavChoice.Use(google, NavReason.ONLY_ONE))
    }

    @Test
    fun `no installed app means geo fallback`() {
        assertThat(NavigationChooser.choose(null, "auto", emptyList(), NavMode.DRIVE, rules))
            .isEqualTo(NavChoice.NoneInstalled)
        assertThat(NavigationChooser.choose(null, "ask", emptyList(), NavMode.DRIVE, rules))
            .isEqualTo(NavChoice.NoneInstalled)
    }

    @Test
    fun `apps without the requested travel mode are not usable`() {
        val noRide =
            rules.copy(
                apps =
                    rules.apps.map {
                        if (it.id ==
                            "amap"
                        ) {
                            it.copy(uri = it.uri.copy(modes = it.uri.modes - "ride"))
                        } else {
                            it
                        }
                    },
            )
        val choice = NavigationChooser.choose(null, "auto", listOf(amap, baidu), NavMode.RIDE, noRide)
        assertThat(choice).isEqualTo(NavChoice.Use(baidu, NavReason.AUTO))
    }

    private fun realRules(): NavigationRules {
        val file = File("../device-impl/src/main/assets/rules/navigation_apps.json")
        return Json { ignoreUnknownKeys = true }.decodeFromString(
            NavigationRules.serializer(),
            file.readText(Charsets.UTF_8),
        )
    }
}
