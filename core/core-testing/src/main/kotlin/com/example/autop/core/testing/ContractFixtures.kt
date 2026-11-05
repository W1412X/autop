/**
 * Provider 契约测试的夹具模型（docs/tech/03 §5.3）：线格式文本由各适配器的
 * `src/test/resources/fixtures/` 提供（文件名与用例对应），经 [Fixtures] 加载。
 */
package com.example.autop.core.testing

import com.example.autop.core.common.SecretStore
import com.example.autop.llm.Usage

/** 一次工具调用的期望值（契约用例 2/3）。 */
public data class ContractTool(
    val id: String,
    val name: String,
    val argsJson: String,
)

/**
 * 契约测试所需夹具（docs/tech/03 §5.3）：[textStream] 用例 1、[singleToolStream] 用例 2、
 * [multiToolStream] 用例 3、[imageStream] 用例 4、[usageStream] 用例 5、
 * [rateLimitBody] 用例 6、[truncatedStream] 用例 7、[contextExceededBody] 用例 8、
 * [hangStream] 用例 9；[textFinal] / [expectedUsage] 为对应期望值。
 */
public data class ContractFixtures(
    val textStream: String,
    val textFinal: String,
    val singleTool: ContractTool,
    val singleToolStream: String,
    val multiTools: List<ContractTool>,
    val multiToolStream: String,
    val imageStream: String,
    val usageStream: String,
    val expectedUsage: Usage,
    val rateLimitBody: String,
    val truncatedStream: String,
    val contextExceededBody: String,
    val hangStream: String,
)

/** 内存密钥仓（契约测试向 Provider 注入 API Key；真实实现见 core-platform 的 KeystoreSecretStore）。 */
public class FakeSecretStore(
    initial: Map<String, String> = emptyMap(),
) : SecretStore {
    private val secrets: MutableMap<String, String> = initial.toMutableMap()

    override suspend fun put(
        ref: String,
        secret: String,
    ) {
        secrets[ref] = secret
    }

    override suspend fun get(ref: String): String? = secrets[ref]

    override suspend fun remove(ref: String) {
        secrets.remove(ref)
    }
}
