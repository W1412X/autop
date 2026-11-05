/**
 * 对话标题生成端口（docs/tech/08 §4.2）：首条用户消息 → 精炼标题。
 * 纯端口，实现在 task-impl（LIGHT 模型 + 模板）。
 */
package com.example.autop.task

/** 对话标题生成。 */
public interface ChatTitleGenerator {
    /**
     * 生成标题；关闭/超时/失败给 null（调用方保持占位标题，不阻塞对话主链路）。
     *
     * @param firstMessage 新会话的首条用户消息（生成上下文）。
     */
    public suspend fun generate(firstMessage: String): String?
}
