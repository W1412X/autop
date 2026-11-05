package com.example.autop.core.prompt.impl

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** 测试用内存覆盖存储。 */
internal class FakePromptOverridesStore(
    initial: Map<String, String> = emptyMap(),
) : PromptOverridesStore {
    private val state = MutableStateFlow(initial)

    override val data: Flow<Map<String, String>> = state

    override fun current(): Map<String, String> = state.value

    override suspend fun set(
        id: String,
        body: String,
    ) {
        state.value = state.value + (id to body)
    }

    override suspend fun remove(id: String) {
        state.value = state.value - id
    }
}

/** 读取真实 assets/prompts（autop.promptDir 系统属性指向该目录，docs/tech/02 §5）。 */
internal fun loadRealPromptFiles(): List<PromptAssetFile> {
    val dir = File(checkNotNull(System.getProperty("autop.promptDir")))
    return dir
        .walkTopDown()
        .filter { it.isFile && it.name.endsWith(".md") }
        .sortedBy { it.relativeTo(dir).path }
        .map { PromptAssetFile(it.relativeTo(dir).path.replace('\\', '/'), it.readText()) }
        .toList()
}
