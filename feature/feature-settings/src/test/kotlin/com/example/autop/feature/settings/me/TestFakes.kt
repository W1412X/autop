package com.example.autop.feature.settings.me

import com.example.autop.core.common.IdGenerator
import com.example.autop.core.common.Logger
import com.example.autop.core.common.Outcome
import com.example.autop.core.config.ConfigEditor
import com.example.autop.llm.ModelDirectory
import com.example.autop.llm.ModelProbe
import com.example.autop.llm.ModelProfile
import com.example.autop.llm.ProbeReport
import com.example.autop.llm.ProbeResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** 记录写入调用的 [ConfigEditor]（[result] 可改为 Err 模拟写入失败）。 */
internal class RecordingConfigEditor(
    var result: Outcome<Unit> = Outcome.Ok(Unit),
) : ConfigEditor {
    val writes = mutableListOf<Pair<String, JsonElement>>()

    override suspend fun setOverride(
        path: String,
        value: JsonElement,
    ): Outcome<Unit> {
        writes += path to value
        return result
    }

    override suspend fun clearOverride(path: String) {
        writes += path to JsonNull
    }

    override suspend fun userOverrides(): JsonObject = JsonObject(emptyMap())
}

/** 记录错误日志的 [Logger]（只关心 `e` 级别：写入失败路径）。 */
internal class RecordingLogger : Logger {
    val errors = mutableListOf<Pair<String, Map<String, Any?>>>()

    override fun d(
        tag: String,
        event: String,
        vararg kv: Pair<String, Any?>,
    ) = Unit

    override fun i(
        tag: String,
        event: String,
        vararg kv: Pair<String, Any?>,
    ) = Unit

    override fun w(
        tag: String,
        event: String,
        vararg kv: Pair<String, Any?>,
        error: Throwable?,
    ) = Unit

    override fun e(
        tag: String,
        event: String,
        vararg kv: Pair<String, Any?>,
        error: Throwable?,
    ) {
        errors += event to kv.toMap()
    }
}

/** 顺序发号的 [IdGenerator]（断言新建 id 可预测）。 */
internal class FakeIdGenerator : IdGenerator {
    private var next = 0

    override fun newId(): String = "id-${++next}"
}

/** 可编程结果的 [ModelProbe]（记录收到的 profile）。 */
internal class RecordingModelProbe(
    var report: ProbeReport =
        ProbeReport(
            text = ProbeResult(ok = true, elapsedMs = 1200),
            tools = ProbeResult(ok = true, elapsedMs = 800),
            vision = ProbeResult(ok = false, elapsedMs = 300),
        ),
) : ModelProbe {
    val received = mutableListOf<ModelProfile>()

    override suspend fun probe(profile: ModelProfile): ProbeReport {
        received += profile
        return report
    }
}

/** 可编程结果的 [ModelDirectory]（记录收到的 profile，N164）。 */
internal class RecordingModelDirectory(
    var result: Outcome<List<String>> = Outcome.Ok(listOf("gpt-4o-mini", "gpt-4o")),
) : ModelDirectory {
    val received = mutableListOf<ModelProfile>()

    override suspend fun listModels(profile: ModelProfile): Outcome<List<String>> {
        received += profile
        return result
    }
}

/** 构造一份完整 [ModelProfile]（测试入参不省略）。 */
internal fun testProfile(
    id: String,
    displayName: String = "模型 $id",
    protocol: String = "openai",
    model: String = "gpt-4o-mini",
): ModelProfile =
    ModelProfile(
        id = id,
        displayName = displayName,
        protocol = protocol,
        baseUrl = "https://api.example.com/v1",
        apiKeyRef = "secret:model:$id",
        model = model,
        contextWindow = 128000,
        maxOutputTokens = 4096,
    )

/** 全通过的 [ProbeResult]（能力回填断言用）。 */
internal fun okProbeReport(): ProbeReport =
    ProbeReport(
        text = ProbeResult(ok = true, elapsedMs = 1100),
        tools = ProbeResult(ok = true, elapsedMs = 900),
        vision = ProbeResult(ok = true, elapsedMs = 700),
    )
