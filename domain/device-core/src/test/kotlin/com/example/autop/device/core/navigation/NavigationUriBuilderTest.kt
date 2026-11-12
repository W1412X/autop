package com.example.autop.device.core.navigation

import com.example.autop.device.core.IntentSpec
import com.example.autop.device.core.NavMode
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File

/** 金标：每个应用 × 出行方式 × 名称/坐标组合的完整 URI 字符串。 */
class NavigationUriBuilderTest {
    private val source = "com.example.autop"

    private fun app(id: String): NavigationAppRule = realRules().apps.first { it.id == id }

    private fun nav(
        destination: String? = null,
        lat: Double? = null,
        lng: Double? = null,
        mode: NavMode = NavMode.DRIVE,
    ) = IntentSpec.Navigate(destination, lat, lng, mode, map = null)

    @Test
    fun `amap with a name uses dname and drive mode 0`() {
        val uri = NavigationUriBuilder.build(app("amap"), nav("Beijing Station"), source)
        assertThat(uri).isEqualTo(
            "amapuri://route/plan/?sourceApplication=com.example.autop&dname=Beijing%20Station&dev=0&t=0",
        )
    }

    @Test
    fun `amap encodes chinese names as utf8 percent escapes`() {
        val uri = NavigationUriBuilder.build(app("amap"), nav("天安门"), source)
        assertThat(uri).isEqualTo(
            "amapuri://route/plan/?sourceApplication=com.example.autop" +
                "&dname=%E5%A4%A9%E5%AE%89%E9%97%A8&dev=0&t=0",
        )
    }

    @Test
    fun `amap with coordinates and name walks with mode 2`() {
        val uri = NavigationUriBuilder.build(app("amap"), nav("A", 39.9042, 116.4074, NavMode.WALK), source)
        assertThat(uri).isEqualTo(
            "amapuri://route/plan/?sourceApplication=com.example.autop&dname=A&dlat=39.9042&dlon=116.4074&dev=0&t=2",
        )
    }

    @Test
    fun `baidu with coordinates only uses latlng destination and transit mode`() {
        val uri = NavigationUriBuilder.build(app("baidu"), nav(lat = 39.9042, lng = 116.4074, mode = NavMode.TRANSIT), source)
        assertThat(uri).isEqualTo(
            "baidumap://map/direction?destination=latlng%3A39.9042%2C116.4074" +
                "&coord_type=gcj02&mode=transit&src=com.example.autop",
        )
    }

    @Test
    fun `baidu with coordinates and name joins them with a pipe`() {
        val uri = NavigationUriBuilder.build(app("baidu"), nav("A", 39.9042, 116.4074, NavMode.RIDE), source)
        assertThat(uri).isEqualTo(
            "baidumap://map/direction?destination=latlng%3A39.9042%2C116.4074%7Cname%3AA" +
                "&coord_type=gcj02&mode=riding&src=com.example.autop",
        )
    }

    @Test
    fun `baidu with a name only walks with walking mode`() {
        val uri = NavigationUriBuilder.build(app("baidu"), nav("Beijing Station", mode = NavMode.WALK), source)
        assertThat(uri).isEqualTo(
            "baidumap://map/direction?destination=Beijing%20Station" +
                "&coord_type=gcj02&mode=walking&src=com.example.autop",
        )
    }

    @Test
    fun `tencent with a name uses to and bike for ride`() {
        val uri = NavigationUriBuilder.build(app("tencent"), nav("Beijing Station", mode = NavMode.RIDE), source)
        assertThat(uri).isEqualTo(
            "qqmap://map/routeplan?type=bike&to=Beijing%20Station&referer=com.example.autop",
        )
    }

    @Test
    fun `tencent with coordinates only uses tocoord and bus for transit`() {
        val uri = NavigationUriBuilder.build(app("tencent"), nav(lat = 39.9042, lng = 116.4074, mode = NavMode.TRANSIT), source)
        assertThat(uri).isEqualTo(
            "qqmap://map/routeplan?type=bus&tocoord=39.9042%2C116.4074&referer=com.example.autop",
        )
    }

    @Test
    fun `google walking with a name adds dir_action`() {
        val uri = NavigationUriBuilder.build(app("google"), nav("Beijing Station", mode = NavMode.WALK), source)
        assertThat(uri).isEqualTo(
            "https://www.google.com/maps/dir/?api=1&destination=Beijing%20Station" +
                "&travelmode=walking&dir_action=navigate",
        )
    }

    @Test
    fun `google transit omits dir_action`() {
        val uri = NavigationUriBuilder.build(app("google"), nav("Beijing Station", mode = NavMode.TRANSIT), source)
        assertThat(uri).isEqualTo(
            "https://www.google.com/maps/dir/?api=1&destination=Beijing%20Station&travelmode=transit",
        )
    }

    @Test
    fun `google with coordinates uses the comma separated destination`() {
        val uri = NavigationUriBuilder.build(app("google"), nav(lat = 39.9042, lng = 116.4074), source)
        assertThat(uri).isEqualTo(
            "https://www.google.com/maps/dir/?api=1&destination=39.9042%2C116.4074" +
                "&travelmode=driving&dir_action=navigate",
        )
    }

    @Test
    fun `missing travel mode makes the app unavailable`() {
        val amap = app("amap")
        val withoutRide = amap.copy(uri = amap.uri.copy(modes = amap.uri.modes - "ride"))
        assertThat(NavigationUriBuilder.build(withoutRide, nav("x", mode = NavMode.RIDE), source)).isNull()
    }

    @Test
    fun `small coordinates never use scientific notation`() {
        assertThat(NavigationUriBuilder.formatCoord(0.00001)).isEqualTo("0.00001")
        assertThat(NavigationUriBuilder.formatCoord(116.40740000001)).isEqualTo("116.4074")
        assertThat(NavigationUriBuilder.formatCoord(0.0)).isEqualTo("0")
        assertThat(NavigationUriBuilder.formatCoord(null)).isNull()
    }

    @Test
    fun `geo with coordinates and name appends the label`() {
        assertThat(NavigationUriBuilder.geo(nav("北京站", 39.9042, 116.4074))).isEqualTo(
            "geo:39.9042,116.4074?q=39.9042,116.4074(%E5%8C%97%E4%BA%AC%E7%AB%99)",
        )
    }

    @Test
    fun `geo with coordinates only has no label`() {
        assertThat(NavigationUriBuilder.geo(nav(lat = 39.9042, lng = 116.4074))).isEqualTo(
            "geo:39.9042,116.4074?q=39.9042,116.4074",
        )
    }

    @Test
    fun `geo with a name only uses the origin and an encoded query`() {
        assertThat(NavigationUriBuilder.geo(nav("Beijing Station"))).isEqualTo("geo:0,0?q=Beijing%20Station")
        assertThat(NavigationUriBuilder.geo(nav("a+b&c=d"))).isEqualTo("geo:0,0?q=a%2Bb%26c%3Dd")
    }

    private fun realRules(): NavigationRules {
        val file = File(ASSET_PATH)
        check(file.exists()) { "navigation_apps.json not found at ${file.absolutePath}" }
        return json.decodeFromString(NavigationRules.serializer(), file.readText(Charsets.UTF_8))
    }

    private companion object {
        const val ASSET_PATH = "../device-impl/src/main/assets/rules/navigation_apps.json"
        val json = Json { ignoreUnknownKeys = true }
    }
}
