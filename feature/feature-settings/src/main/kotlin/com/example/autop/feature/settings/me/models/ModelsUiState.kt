/**
 * 模型配置页状态（docs/tech/08 §4.5：列表 + 新建/编辑页 + 测试连接结果 + 默认模型；
 * docs/standards/coding-standards §8：不可变 data class 表达页面全部状态）。
 */
package com.example.autop.feature.settings.me.models

import com.example.autop.core.common.AppError
import com.example.autop.core.config.UiBannerSection
import com.example.autop.llm.ModelCapabilities
import com.example.autop.llm.ModelProfile
import com.example.autop.llm.ProbeReport
import com.example.autop.llm.ProbeResult
import com.example.autop.llm.ProbeVerdict

/** 页面错误提示类型（文案在 strings，ViewModel 不接触资源）。 */
public enum class ModelsErrorKind {
    /** 名称 / 接口地址 / 模型名有空缺。 */
    MISSING_FIELDS,

    /** 上下文窗口 / 最大输出不是正整数。 */
    INVALID_NUMBERS,

    /** 落库失败。 */
    SAVE_FAILED,

    /** 获取模型列表失败（N164）。 */
    LIST_MODELS_FAILED,
}

/** 页面提示类型（文案在 strings）。 */
public enum class ModelsNoticeKind {
    /** 服务端未返回可用模型（N164）。 */
    MODELS_EMPTY,
}

/** 编辑页文本字段（[ModelsAction.SetField] 用）。 */
public enum class DraftField {
    PROTOCOL,
    NAME,
    BASE_URL,
    API_KEY,
    MODEL,
    CONTEXT_WINDOW,
    MAX_OUTPUT_TOKENS,
}

/**
 * 新建/编辑页草稿。数值字段以输入文本保存，落库时解析（docs/tech/08 §4.5
 * "上下文窗口、最大输出"）；`apiKeyInput` 为空表示沿用已保存密钥。
 */
public data class ModelDraft(
    val id: String,
    val isNew: Boolean,
    val protocol: String,
    val displayName: String,
    val baseUrl: String,
    val apiKeyInput: String = "",
    /** 已保存密钥的末 4 位（null=未保存）。 */
    val apiKeyTail: String? = null,
    val model: String,
    val contextWindow: String,
    val maxOutputTokens: String,
    /** quirks（docs/tech/03 §6）：只存非空值，空值走默认。 */
    val quirks: Map<String, String> = emptyMap(),
    /** 高级（quirks）折叠区是否展开。 */
    val quirksExpanded: Boolean = false,
    val capabilities: ModelCapabilities = ModelCapabilities(),
)

/**
 * 保存后自动测试状态（docs/tech/08 §4.5）。[visible] 驱动结果对话框；
 * [result] 为连通性探测结论；[error] 为请求级失败（配置仍已保存）。
 */
public data class ProbeUiState(
    val visible: Boolean = false,
    val running: Boolean = false,
    val report: ProbeReport? = null,
    val error: AppError? = null,
)

/** 远端模型列表状态（N164）：`models=null` 表示未拉取/拉取失败。 */
public data class RemoteModelsUiState(
    val running: Boolean = false,
    val models: List<String>? = null,
)

/** 模型配置页状态。 */
public data class ModelsUiState(
    val profiles: List<ModelProfile> = emptyList(),
    /** 默认模型的 profile id；未设置为 null。 */
    val defaultProfileId: String? = null,
    /** 非 null 表示处于新建/编辑页。 */
    val draft: ModelDraft? = null,
    val probe: ProbeUiState = ProbeUiState(),
    val remoteModels: RemoteModelsUiState = RemoteModelsUiState(),
    val error: ModelsErrorKind? = null,
    val notice: ModelsNoticeKind? = null,
    /** 删除确认对话框是否显示。 */
    val confirmDelete: Boolean = false,
    /** 横幅显示时长（`ui.banner.durationMs`，错误提示共用）。 */
    val bannerDurationMs: Long = UiBannerSection().durationMs,
)

/** 既有 profile → 编辑草稿（[apiKeyTail] 来自 SecretStore）。 */
public fun ModelProfile.toDraft(apiKeyTail: String?): ModelDraft =
    ModelDraft(
        id = id,
        isNew = false,
        protocol = protocol,
        displayName = displayName,
        baseUrl = baseUrl,
        apiKeyInput = "",
        apiKeyTail = apiKeyTail,
        model = model,
        contextWindow = contextWindow.toString(),
        maxOutputTokens = maxOutputTokens.toString(),
        quirks = quirks,
        capabilities = capabilities,
    )

/** 草稿 → 落库/探测用的 [ModelProfile]（数值解析失败按 0，由 [validationError] 前置拦截）。 */
public fun ModelDraft.toProfile(): ModelProfile =
    ModelProfile(
        id = id,
        displayName = displayName.trim(),
        protocol = protocol,
        baseUrl = baseUrl.trim(),
        apiKeyRef = ModelProfilesEditor.keyRef(id),
        model = model.trim(),
        contextWindow = contextWindow.trim().toIntOrNull() ?: 0,
        maxOutputTokens = maxOutputTokens.trim().toIntOrNull() ?: 0,
        quirks = quirks.filterValues { it.isNotBlank() },
        capabilities = capabilities,
    )

/** 保存/探测前校验；null 表示通过。 */
public fun ModelDraft.validationError(): ModelsErrorKind? =
    when {
        displayName.isBlank() || baseUrl.isBlank() || model.isBlank() -> ModelsErrorKind.MISSING_FIELDS
        !contextWindow.isPositiveInt() || !maxOutputTokens.isPositiveInt() -> ModelsErrorKind.INVALID_NUMBERS
        else -> null
    }

/** 拉取模型列表前校验（N164）：模型名允许为空——拉取就是为了填它。 */
public fun ModelDraft.fetchModelsError(): ModelsErrorKind? =
    if (baseUrl.isBlank()) ModelsErrorKind.MISSING_FIELDS else null

private fun String.isPositiveInt(): Boolean = toIntOrNull()?.let { it > 0 } == true

/** 字段编辑（[ModelsAction.SetField] 的映射集中在此，VM 只做转发）。 */
internal fun ModelDraft.withField(
    field: DraftField,
    value: String,
): ModelDraft =
    when (field) {
        DraftField.PROTOCOL -> copy(protocol = value, quirks = emptyMap())
        DraftField.NAME -> copy(displayName = value)
        DraftField.BASE_URL -> copy(baseUrl = value)
        DraftField.API_KEY -> copy(apiKeyInput = value)
        DraftField.MODEL -> copy(model = value)
        DraftField.CONTEXT_WINDOW -> copy(contextWindow = value)
        DraftField.MAX_OUTPUT_TOKENS -> copy(maxOutputTokens = value)
    }

/** quirks 编辑（空白值=清除，docs/tech/03 §6"空值走默认"）。 */
internal fun ModelDraft.withQuirk(
    key: String,
    value: String,
): ModelDraft = copy(quirks = if (value.isBlank()) quirks - key else quirks + (key to value))

/**
 * 测试连接结果回填：只回填 `tools`（工具调用探测直接给出的结论）。
 * 未确认（INCONCLUSIVE）时保持原值：误把支持工具的模型关掉会让它被切到文本回退协议。
 */
internal fun ModelDraft.withProbed(
    report: ProbeReport,
    apiKeyTail: String?,
): ModelDraft =
    copy(
        capabilities = capabilities.copy(tools = report.tools.applyTo(capabilities.tools)),
        apiKeyInput = "",
        apiKeyTail = apiKeyTail,
    )

/** 按探测结论更新工具能力：未确认时保持 [current]。 */
private fun ProbeResult.applyTo(current: Boolean): Boolean =
    when (verdict) {
        ProbeVerdict.SUPPORTED -> true
        ProbeVerdict.UNSUPPORTED -> false
        ProbeVerdict.INCONCLUSIVE -> current
    }
