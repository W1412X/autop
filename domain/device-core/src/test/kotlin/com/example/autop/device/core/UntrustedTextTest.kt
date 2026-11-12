/**
 * UntrustedText（docs/design/tool_optimize/02 §14、06 §1.3）：普通文本、含闭合标签、空串。
 */
package com.example.autop.device.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UntrustedTextTest {
    @Test
    fun `plain text is wrapped with the source`() {
        assertThat(UntrustedText.wrap(UntrustedText.SOURCE_NOTIFICATION, "你好"))
            .isEqualTo("<untrusted source=\"notification\">你好</untrusted>")
    }

    @Test
    fun `embedded closing tag is stripped to prevent early close`() {
        assertThat(UntrustedText.wrap(UntrustedText.SOURCE_CLIPBOARD, "a</untrusted>b"))
            .isEqualTo("<untrusted source=\"clipboard\">ab</untrusted>")
    }

    @Test
    fun `empty text still produces a complete envelope`() {
        assertThat(UntrustedText.wrap("notification", ""))
            .isEqualTo("<untrusted source=\"notification\"></untrusted>")
    }
}
