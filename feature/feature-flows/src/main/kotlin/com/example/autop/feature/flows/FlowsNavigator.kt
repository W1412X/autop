/**
 * 流程域导航回调（feature 之间不互相引用，路由由 app 组装）。
 */
package com.example.autop.feature.flows

/** `flows/detail/{flowId}`、`flows/editor/{flowId}` 的参数名。 */
public const val FLOW_ID_ARG: String = "flowId"

/** `flows/record/{runId}` 的参数名。 */
public const val FLOW_RUN_ID_ARG: String = "runId"

/** `flows/author?draft=&edit=` 的参数名。 */
public const val AUTHOR_DRAFT_ARG: String = "draft"

/** `flows/author?draft=&edit=` 的参数名。 */
public const val AUTHOR_EDIT_ARG: String = "edit"

/** 路由参数里"没有值"的占位。 */
public const val NO_ARG: String = "-"

/** 流程页导航回调。 */
public interface FlowsNavigator {
    /** 流程详情。 */
    public fun openFlow(flowId: String)

    /** 编辑器（简明 / 高级 / 原始 JSON）。 */
    public fun openEditor(flowId: String)

    /** AI 创建：[draftId] 恢复草稿，[editFlowId] 基于已有流程修改，都为 null 即新建。 */
    public fun openAuthor(
        draftId: String?,
        editFlowId: String?,
    )

    /** 创建入口（AI 创建 / 手动创建 / 导入）。 */
    public fun openCreate()

    /** 一次运行的执行记录。 */
    public fun openRunRecord(runId: String)

    /** 接口登记。 */
    public fun openEndpoints()

    /** 流程状态管理。 */
    public fun openState(flowId: String)

    /** 版本历史。 */
    public fun openVersions(flowId: String)

    /** 通过系统分享发出文本（导出）。 */
    public fun shareText(text: String)

    /** 返回。 */
    public fun navigateBack()
}
