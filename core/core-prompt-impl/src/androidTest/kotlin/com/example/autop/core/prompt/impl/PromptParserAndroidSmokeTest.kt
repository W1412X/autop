package com.example.autop.core.prompt.impl

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.autop.core.common.Outcome
import com.example.autop.core.prompt.PromptSource
import com.example.autop.core.prompt.parsePromptTemplate
import com.example.autop.core.prompt.validatePromptBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 正则引擎冒烟（docs/tech/questions.md N134）：
 * Android 的 `java.util.regex` 底层是 ICU，对未转义 `}` 等语法比 OpenJDK 严格——
 * 裸 `}` 在 JVM 单测里被当字面量容忍，在真机 `<clinit>` 抛 `PatternSyntaxException`。
 * 本测试必须在真机/模拟器（androidTest）运行：触发 PromptParser 顶层正则编译并走通解析。
 */
@RunWith(AndroidJUnit4::class)
class PromptParserAndroidSmokeTest {
    @Test
    fun promptTokenRegexCompilesAndParsesOnAndroid() {
        val template = parsePromptTemplate(SMOKE_TEMPLATE, PromptSource.BUILTIN)
        assertEquals(setOf("user", "hint"), template.vars)
        val validated = validatePromptBody("hi {{user}}{{#if hint}} ({{hint}}){{/if}}", template)
        assertTrue(validated is Outcome.Ok)
    }

    private companion object {
        val SMOKE_TEMPLATE =
            """
            ---
            id: smoke
            version: 1
            vars: [user, hint]
            optionalVars: [hint]
            ---
            hi {{user}}
            """.trimIndent()
    }
}
