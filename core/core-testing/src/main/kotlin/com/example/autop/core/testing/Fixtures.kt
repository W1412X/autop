package com.example.autop.core.testing

/**
 * 测试夹具加载器（docs/tech/02 §5）：读取消费模块 `src/test/resources/fixtures/` 下的文件
 * （夹具经测试 classpath 对本对象可见）。找不到即失败，不允许静默回退。
 */
public object Fixtures {
    private const val ROOT = "fixtures"

    /** 读取 `fixtures/[path]` 的完整文本。 */
    public fun load(path: String): String {
        val stream =
            Fixtures::class.java.classLoader.getResourceAsStream("$ROOT/$path")
                ?: error("fixture not found on classpath: $ROOT/$path")
        return stream.bufferedReader().use { it.readText() }
    }
}
