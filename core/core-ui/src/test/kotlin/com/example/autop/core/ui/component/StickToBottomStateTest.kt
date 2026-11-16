/**
 * 贴底跟随（rememberStickToBottom）：打开页面时即使列表已有内容也应落到底部，
 * 内容随后到达同样要贴底。
 */
package com.example.autop.core.ui.component

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StickToBottomStateTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `open page with existing content lands at the bottom`() {
        val listState = LazyListState()
        compose.setContent {
            Timeline(listState = listState, items = List(100) { "消息 $it" })
            rememberStickToBottom(listState)
        }

        compose.waitForIdle()

        compose.runOnIdle {
            assertThat(listState.canScrollForward).isFalse()
            val lastVisible = listState.layoutInfo.visibleItemsInfo.last()
            assertThat(lastVisible.index).isEqualTo(99)
        }
    }

    @Test
    fun `content arriving after open lands at the bottom`() {
        val listState = LazyListState()
        val messages = mutableStateOf(emptyList<String>())
        compose.setContent {
            Timeline(listState = listState, items = messages.value)
            rememberStickToBottom(listState)
        }
        compose.waitForIdle()

        messages.value = List(100) { "消息 $it" }
        compose.waitForIdle()

        compose.runOnIdle {
            assertThat(listState.canScrollForward).isFalse()
            val lastVisible = listState.layoutInfo.visibleItemsInfo.last()
            assertThat(lastVisible.index).isEqualTo(99)
        }
    }

    @Test
    fun `tall last message is fully shown at the bottom`() {
        val listState = LazyListState()
        compose.setContent {
            Timeline(listState = listState, items = List(3) { "消息 $it" }, itemHeight = 600.dp)
            rememberStickToBottom(listState)
        }

        compose.waitForIdle()

        compose.runOnIdle {
            assertThat(listState.canScrollForward).isFalse()
        }
    }
}

@Composable
private fun Timeline(
    listState: LazyListState,
    items: List<String>,
    itemHeight: Dp = 48.dp,
) {
    LazyColumn(state = listState, modifier = Modifier.height(400.dp)) {
        items(items) { text -> Text(text = text, modifier = Modifier.height(itemHeight)) }
    }
}
