/**
 * 工具自测端口（N198）：悬浮面板选工具 → 切任意页面触发执行 → 看输出定位问题。
 * 探测直接调用工具本身（不走 ToolRouter 的锁与策略，人工诊断入口），
 * 记录探测历史并可导出详细日志。
 */
package com.example.autop.agent

import com.example.autop.llm.Block

/** 工具探测目标（N198）。 */
public data class ToolProbeTarget(
    /** 工具的模型可见名称（spec.name，如 `tap`）。 */
    val name: String,
    val access: ToolAccess,
    /** 默认测试参数（JSON 文本，回填/重置用，来自内置 fixture）。 */
    val defaultArgs: String,
    /** 会真实操作屏幕（点击/滑动/输入/启动应用），执行时面板让路避免挡住注入落点。 */
    val actsOnScreen: Boolean,
    /** 参数元数据（注册顺序，面板按此做可视化编辑；N207）。 */
    val params: List<ToolProbeParam> = emptyList(),
)

/** 参数编辑控件类型（由参数 JSON Schema 的 `type` 映射；N207）。 */
public enum class ToolParamKind {
    STRING,
    INTEGER,
    NUMBER,
    BOOLEAN,
    OBJECT,
    ARRAY,
}

/**
 * 单个工具参数的元数据（N207）：面板按键渲染输入控件 + 说明 + 示例。
 * 构造参数 6 个（detekt 阈值上限），子字段提示并入 [description]。
 */
public data class ToolProbeParam(
    /** 参数名（JSON 键）。 */
    val name: String,
    val kind: ToolParamKind,
    /** 是否必填（Schema `required`）。 */
    val required: Boolean,
    /** 限定取值（Schema `enum`），空表示不限。 */
    val enumValues: List<String>,
    /** 参数说明（prompt 模板 `meta["param.<名>"]`；嵌套参数附子字段提示）。 */
    val description: String,
    /** 示例值（编辑框回填文本；字符串不带引号，object/array 为 JSON 文本）。 */
    val example: String,
)

/** 一次工具探测结果（N198）。 */
public data class ToolProbeResult(
    val toolName: String,
    /** 实际执行的参数（JSON 文本）。 */
    val args: String,
    val ok: Boolean,
    val durationMs: Long,
    /** 输出摘要或错误描述（完整文本，展示端自行折叠；N207）。 */
    val summary: String,
    val atMillis: Long,
    /** 视觉链路调试图等附图（N208）：检测渲染图＋逐框识别 I/O 裁剪图，展示端点选查看。 */
    val images: List<Block.Image> = emptyList(),
)

/** 工具自测端口（N198）。 */
public interface ToolProbeService {
    /** 全部工具（注册顺序）。 */
    public fun targets(): List<ToolProbeTarget>

    /**
     * 执行探测：[argsJson] 为面板组装的参数 JSON 文本，null 或解析失败时回退该工具的
     * 默认参数；未知工具返回失败结果而不是抛异常。
     */
    public suspend fun probe(
        toolName: String,
        argsJson: String?,
    ): ToolProbeResult

    /** 探测历史（旧→新）。 */
    public fun history(): List<ToolProbeResult>

    /** 诊断导出文本：探测历史 + 应用运行日志（N198，给悬浮面板「导出日志」）。 */
    public fun diagnosticsText(): String
}
