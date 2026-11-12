/**
 * 模型配置页用户操作（docs/standards/coding-standards §8：sealed interface，
 * 用户操作统一通过 `onAction(action)` 交给 ViewModel）。
 */
package com.example.autop.feature.settings.me.models

public sealed interface ModelsAction {
    /** 新建模型配置。 */
    public data object New : ModelsAction

    /** 编辑既有配置。 */
    public data class Edit(
        val id: String,
    ) : ModelsAction

    /** 编辑页返回列表（放弃草稿）。 */
    public data object Cancel : ModelsAction

    /** 保存草稿（保存成功后自动测试连接并弹窗展示结果）。 */
    public data object Save : ModelsAction

    /** 关闭测试结果对话框并返回列表。 */
    public data object CloseProbe : ModelsAction

    /** 重新测试当前已保存的配置。 */
    public data object RetestProbe : ModelsAction

    /** 模型名输入聚焦时自动拉取服务端模型列表（N164：拉取可用模型 id 供点选填入）。 */
    public data object FetchRemoteModels : ModelsAction

    /** 请求删除当前编辑的配置（弹确认框）。 */
    public data object RequestDelete : ModelsAction

    /** 确认删除。 */
    public data object ConfirmDelete : ModelsAction

    /** 关闭浮层提示：[kind] 指明横幅/确认框。 */
    public data class Dismiss(
        val kind: DismissKind,
    ) : ModelsAction

    /** 编辑页文本字段变更。 */
    public data class SetField(
        val field: DraftField,
        val value: String,
    ) : ModelsAction

    /** quirks 一项变更（docs/tech/03 §6）。 */
    public data class SetQuirk(
        val key: String,
        val value: String,
    ) : ModelsAction

    /** 高级（quirks）折叠区展开/收起。 */
    public data object ToggleQuirks : ModelsAction

    /** 设置默认模型（null 表示清除默认）。 */
    public data class SetDefault(
        val profileId: String?,
    ) : ModelsAction
}

/** [ModelsAction.Dismiss] 的目标。 */
public enum class DismissKind {
    /** 错误横幅。 */
    ERROR,

    /** 提示横幅。 */
    NOTICE,

    /** 删除确认框。 */
    DELETE_CONFIRM,
}
