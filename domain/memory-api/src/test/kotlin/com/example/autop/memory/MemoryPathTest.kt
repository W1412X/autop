/**
 * MemoryPath 测试（docs/tech/06 §1/§7）：规范化规则与上限校验。
 */
package com.example.autop.memory

import com.example.autop.core.common.Outcome
import com.example.autop.core.common.getOrNull
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 路径规范化行为测试。 */
public class MemoryPathTest {
    @Test
    public fun `parse normalizes slashes and trims segments`() {
        val parsed = MemoryPath.parse("  //people  /// 张三/ ")

        val path = (parsed as Outcome.Ok).value
        assertThat(path.value).isEqualTo("/people/张三")
    }

    @Test
    public fun `parse accepts root forms`() {
        assertThat(MemoryPath.parse("/").getOrNull()).isEqualTo(MemoryPath.ROOT)
        assertThat(MemoryPath.parse("").getOrNull()).isEqualTo(MemoryPath.ROOT)
        assertThat(MemoryPath.parse("///").getOrNull()).isEqualTo(MemoryPath.ROOT)
    }

    @Test
    public fun `parse rejects depth over limit`() {
        val raw = (1..5).joinToString(separator = "/") { "第${it}层" }

        val result = MemoryPath.parse(raw, maxSegmentChars = 64, maxDepth = 4)

        assertThat(result).isInstanceOf(Outcome.Err::class.java)
        assertThat((result as Outcome.Err).error.code).isEqualTo(MemoryErrorCodes.INVALID_PATH)
    }

    @Test
    public fun `parse rejects over-long segment`() {
        val result = MemoryPath.parse("/${"长".repeat(5)}", maxSegmentChars = 4)

        assertThat(result).isInstanceOf(Outcome.Err::class.java)
    }

    @Test
    public fun `parent name and segments agree with value`() {
        val path = MemoryPath.parse("/people/张三").getOrNull()!!

        assertThat(path.parent!!.value).isEqualTo("/people")
        assertThat(path.name).isEqualTo("张三")
        assertThat(path.segments()).containsExactly("people", "张三").inOrder()
        assertThat(path.child("电话").value).isEqualTo("/people/张三/电话")
    }

    @Test
    public fun `root has null parent and empty name`() {
        assertThat(MemoryPath.ROOT.parent).isNull()
        assertThat(MemoryPath.ROOT.name).isEmpty()
        assertThat(MemoryPath.ROOT.child("profile").value).isEqualTo("/profile")
    }
}
