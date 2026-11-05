package com.example.autop.device.impl.vision

import com.example.autop.device.NodeFlags
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 类别 → className/NodeFlags/actions 映射（vp8 §8.2，v1/v2 同表）。 */
internal class CategoryMapperTest {
    @Test
    fun `button maps to visual dot Button with clickable`() {
        assertThat(CategoryMapper.classNameOf(VisualCategory.BUTTON)).isEqualTo("visual.Button")
        assertThat(CategoryMapper.flagsOf(VisualCategory.BUTTON) and NodeFlags.CLICKABLE).isNotEqualTo(0)
        assertThat(CategoryMapper.flagsOf(VisualCategory.BUTTON) and NodeFlags.ENABLED).isNotEqualTo(0)
    }

    @Test
    fun `icon maps to visual dot ImageButton with clickable`() {
        for (category in listOf(VisualCategory.ICON, VisualCategory.ICON_CLICKABLE)) {
            assertThat(CategoryMapper.classNameOf(category)).isEqualTo("visual.ImageButton")
            assertThat(CategoryMapper.flagsOf(category) and NodeFlags.CLICKABLE).isNotEqualTo(0)
        }
    }

    @Test
    fun `input field maps to editable focusable`() {
        assertThat(CategoryMapper.classNameOf(VisualCategory.INPUT_FIELD)).isEqualTo("visual.EditText")
        assertThat(CategoryMapper.flagsOf(VisualCategory.INPUT_FIELD) and NodeFlags.EDITABLE).isNotEqualTo(0)
        assertThat(CategoryMapper.flagsOf(VisualCategory.INPUT_FIELD) and NodeFlags.FOCUSABLE).isNotEqualTo(0)
    }

    @Test
    fun `switch and checkbox map to checkable clickable`() {
        for (category in listOf(VisualCategory.SWITCH, VisualCategory.CHECKBOX, VisualCategory.RADIO)) {
            assertThat(CategoryMapper.flagsOf(category) and NodeFlags.CHECKABLE).isNotEqualTo(0)
            assertThat(CategoryMapper.flagsOf(category) and NodeFlags.CLICKABLE).isNotEqualTo(0)
        }
    }

    @Test
    fun `text label is non interactive without actions`() {
        assertThat(CategoryMapper.classNameOf(VisualCategory.TEXT_LABEL)).isEqualTo("visual.TextView")
        assertThat(CategoryMapper.flagsOf(VisualCategory.TEXT_LABEL) and NodeFlags.CLICKABLE).isEqualTo(0)
        assertThat(CategoryMapper.actionsOf(VisualCategory.TEXT_LABEL)).isEmpty()
    }

    @Test
    fun `interactive categories get action click`() {
        assertThat(CategoryMapper.actionsOf(VisualCategory.BUTTON)).containsExactly(CategoryMapper.ACTION_CLICK)
        assertThat(CategoryMapper.actionsOf(VisualCategory.LIST_ITEM)).containsExactly(CategoryMapper.ACTION_CLICK)
    }

    @Test
    fun `v2 classes map per vp8 section 8 2`() {
        assertThat(CategoryMapper.classNameOf(VisualCategory.TEXT)).isEqualTo("visual.TextView")
        assertThat(CategoryMapper.classNameOf(VisualCategory.IMAGE)).isEqualTo("visual.ImageView")
        assertThat(CategoryMapper.classNameOf(VisualCategory.PROGRESS)).isEqualTo("visual.ProgressBar")
        assertThat(CategoryMapper.flagsOf(VisualCategory.IMAGE) and NodeFlags.CLICKABLE).isEqualTo(0)
        assertThat(CategoryMapper.flagsOf(VisualCategory.PROGRESS) and NodeFlags.CLICKABLE).isEqualTo(0)
        assertThat(CategoryMapper.classNameOf(VisualCategory.ITEM)).isEqualTo("visual.Item")
        assertThat(CategoryMapper.flagsOf(VisualCategory.ITEM) and NodeFlags.CLICKABLE).isNotEqualTo(0)
    }

    @Test
    fun `slider is clickable and scrollable`() {
        assertThat(CategoryMapper.classNameOf(VisualCategory.SLIDER)).isEqualTo("visual.SeekBar")
        val flags = CategoryMapper.flagsOf(VisualCategory.SLIDER)
        assertThat(flags and NodeFlags.CLICKABLE).isNotEqualTo(0)
        assertThat(flags and NodeFlags.SCROLLABLE).isNotEqualTo(0)
    }

    @Test
    fun `checked state adds check flag and state desc`() {
        val on = CategoryMapper.flagsOf(VisualCategory.SWITCH, checked = true)
        assertThat(on and NodeFlags.CHECKED).isNotEqualTo(0)
        assertThat(CategoryMapper.stateDescOf(true)).isEqualTo(CategoryMapper.STATE_ON)
        val off = CategoryMapper.flagsOf(VisualCategory.SWITCH, checked = false)
        assertThat(off and NodeFlags.CHECKED).isEqualTo(0)
        assertThat(CategoryMapper.stateDescOf(false)).isEqualTo(CategoryMapper.STATE_OFF)
        assertThat(CategoryMapper.stateDescOf(null)).isNull()
    }

    @Test
    fun `tab and tab group split roles`() {
        assertThat(CategoryMapper.classNameOf(VisualCategory.TAB)).isEqualTo("visual.Tab")
        assertThat(CategoryMapper.classNameOf(VisualCategory.TAB_GROUP)).isEqualTo("visual.TabWidget")
    }

    @Test
    fun `unknown category falls back to container`() {
        assertThat(CategoryMapper.classNameOf("no-such-category")).isEqualTo("visual.ViewGroup")
        assertThat(CategoryMapper.flagsOf("no-such-category")).isEqualTo(NodeFlags.VISIBLE)
        assertThat(CategoryMapper.actionsOf("no-such-category")).isEmpty()
    }
}
