package com.example.autop.device.impl

import com.example.autop.core.common.RunId
import com.example.autop.core.testing.FakeConfigProvider
import com.example.autop.device.DisplayTarget
import com.example.autop.device.Rect
import com.example.autop.device.ScreenSnapshot
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class ContentAreaProviderTest {
    @Test
    fun `main full screen mode ignores snapshot content area`() =
        runTest {
            val areas = ContentAreaProvider(config(hideSystemDecoration = false))
            areas.update(CONTENT)

            assertThat(areas.forSnapshot(snapshot(CONTENT))).isNull()
        }

    @Test
    fun `main content mode keeps configured coordinate area`() =
        runTest {
            val areas = ContentAreaProvider(config(hideSystemDecoration = true))
            areas.update(CONTENT)

            assertThat(areas.forSnapshot(snapshot(CONTENT))).isEqualTo(CONTENT)
        }

    @Test
    fun `background uses its own snapshot area`() =
        runTest {
            val areas = ContentAreaProvider(config(hideSystemDecoration = false))
            areas.update(CONTENT)
            val target = DisplayTarget.Background(RunId("background-run"), 7, 1080, 1920, 320)

            val area = withContext(target) { areas.forSnapshot(snapshot(FULL)) }

            assertThat(area).isEqualTo(FULL)
        }

    private fun config(hideSystemDecoration: Boolean): FakeConfigProvider =
        FakeConfigProvider(
            buildJsonObject {
                put(
                    "device",
                    buildJsonObject {
                        put(
                            "serializer",
                            buildJsonObject { put("hideSystemDecoration", hideSystemDecoration) },
                        )
                    },
                )
            },
        )

    private fun snapshot(area: Rect): ScreenSnapshot =
        ScreenSnapshot(
            capturedAt = 0L,
            windows = emptyList(),
            nodes = emptyList(),
            foregroundPackage = null,
            screenWidth = 1080,
            screenHeight = 1920,
            rotation = 0,
            keyguardLocked = false,
            contentArea = area,
            truncated = false,
        )

    private companion object {
        val CONTENT = Rect(0, 100, 1080, 1820)
        val FULL = Rect(0, 0, 1080, 1920)
    }
}
