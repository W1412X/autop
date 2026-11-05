package com.example.autop.core.prompt.impl

import android.content.Context
import com.example.autop.core.prompt.PromptSource
import com.example.autop.core.prompt.PromptTemplate
import com.example.autop.core.prompt.parsePromptTemplate

/** 从 Android assets 的 prompts 目录递归读取全部模板文件（docs/tech/02 §3.1）。 */
public fun readPromptAssetFiles(context: Context): List<PromptAssetFile> {
    val files = mutableListOf<PromptAssetFile>()
    collectAssetFiles(context, PROMPT_ASSET_DIR, files)
    return files.sortedBy { it.name }
}

/** 解析全部内置模板；id 重复、解析或校验失败都是启动失败（docs/tech/02 §3.1、§3.2）。 */
public fun loadBuiltinTemplates(files: List<PromptAssetFile>): Map<String, PromptTemplate> {
    val builtins = LinkedHashMap<String, PromptTemplate>()
    for (file in files.sortedBy { it.name }) {
        val template = parsePromptTemplate(file.text, PromptSource.BUILTIN)
        val previous = builtins.put(template.id, template)
        check(previous == null) {
            "duplicate prompt id '${template.id}': ${file.name} conflicts with earlier definition"
        }
    }
    return builtins
}

private fun collectAssetFiles(
    context: Context,
    dir: String,
    out: MutableList<PromptAssetFile>,
) {
    for (name in checkNotNull(context.assets.list(dir)).sorted()) {
        val path = "$dir/$name"
        val children = context.assets.list(path)
        if (children != null && children.isNotEmpty()) {
            collectAssetFiles(context, path, out)
        } else if (name.endsWith(MARKDOWN_SUFFIX)) {
            val text =
                context.assets
                    .open(path)
                    .bufferedReader()
                    .use { it.readText() }
            out += PromptAssetFile(path, text)
        }
    }
}

private const val PROMPT_ASSET_DIR = "prompts"
private const val MARKDOWN_SUFFIX = ".md"
