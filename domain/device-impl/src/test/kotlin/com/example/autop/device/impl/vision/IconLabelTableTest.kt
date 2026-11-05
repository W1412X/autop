package com.example.autop.device.impl.vision

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/** 图标显示名表（vp9 §5.4）：解析、未收录键回退键名、assets 全量登记。 */
internal class IconLabelTableTest {
    @Test
    fun `parse maps keys to display names`() {
        val table = IconLabelTable.parse("""{"schemaVersion":1,"reject":["text_crop"],"labels":{"search":"搜索"}}""")
        assertThat(table.displayOf("search")).isEqualTo("搜索")
        assertThat(table.reject).containsExactly("text_crop")
    }

    @Test
    fun `unknown key falls back to key name`() {
        val table = IconLabelTable.parse("""{"labels":{}}""")
        assertThat(table.displayOf("fab")).isEqualTo("fab")
    }

    @Test
    fun `illegal json yields empty table`() {
        val table = IconLabelTable.parse("not json")
        assertThat(table.displayOf("fab")).isEqualTo("fab")
        assertThat(table.reject).isEmpty()
    }

    @Test
    fun `asset covers all icon classes and reject list`() {
        val table = IconLabelTable.parse(File("src/main/assets/rules/icon_labels.json").readText())
        assertThat(table.reject).containsExactly("text_crop", "image_crop")
        // 100 个图标类全部给中文显示名（§5.4）：与 PoC labels_icon.yaml 的前 100 类一致。
        val iconKeys =
            (
                "add airplane alarm arrow_down arrow_left arrow_right arrow_up attach bag barcode battery " +
                    "bluetooth bookmark brightness calculator calendar call camera car cart chart check_mark " +
                    "clock close cloud computer contrast credit_card crop cursor cut dashboard delete dollar " +
                    "download edit external_link eye fab fast_forward favorite file filter fingerprint fire flag " +
                    "flashlight folder gift globe grid headphones home inbox info laptop light_bulb link location " +
                    "lock mail map maximize megaphone menu microphone minimize mobile moon music mute " +
                    "notifications overflow_menu play printer profile_avatar qr_code question refresh reply rewind " +
                    "save search send settings share signal sort tag television thumbs_up ticket trash trophy " +
                    "unlock upload user video_camera volume warning"
            ).split(" ")
        assertThat(iconKeys).hasSize(100)
        for (key in iconKeys) {
            assertThat(table.displayOf(key)).isNotEqualTo(key)
        }
    }
}
