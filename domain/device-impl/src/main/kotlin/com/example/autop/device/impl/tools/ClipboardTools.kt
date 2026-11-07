/**
 * `tool.clipboard_get` / `tool.clipboard_set`（docs/tech/05 §5）：剪贴板读写。
 * 应用不在前台时系统会拒绝读取，失败返回 `device.action_failed` 并说明限制。
 */
package com.example.autop.device.impl.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.example.autop.agent.Tool
import com.example.autop.agent.ToolAccess
import com.example.autop.agent.ToolContext
import com.example.autop.agent.ToolOutput
import com.example.autop.core.common.CommonErrorCodes
import com.example.autop.device.DeviceErrorCodes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** 剪贴板读取工具。 */
@Singleton
public class ClipboardGetTool
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : Tool {
        override val id: String = "tool.clipboard_get"

        override val spec = DeviceToolSpecs.clipboardGet

        override val access: ToolAccess = ToolAccess.READ_SYSTEM

        override suspend fun invoke(
            args: JsonObject,
            ctx: ToolContext,
        ): ToolOutput {
            // 空剪贴板与“被系统拒绝”要分开：混报会让模型盲目重试（N172）。
            val clip =
                try {
                    clipboardManager(context).primaryClip
                } catch (denied: SecurityException) {
                    return toolError(
                        code = DeviceErrorCodes.ACTION_FAILED,
                        message = "读取剪贴板被系统拒绝：${denied.message}",
                        hint = "应用不在前台时系统会拒绝读取剪贴板，可在用户操作后重试",
                    )
                }
            val text = clip?.getItemAt(0)?.coerceToText(context)?.toString()
            return if (text.isNullOrEmpty()) {
                textOutput("(剪贴板为空)")
            } else {
                textOutput(text)
            }
        }
    }

/** 剪贴板写入工具。 */
@Singleton
public class ClipboardSetTool
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : Tool {
        override val id: String = "tool.clipboard_set"

        override val spec = DeviceToolSpecs.clipboardSet

        override val access: ToolAccess = ToolAccess.WRITE_SYSTEM

        override suspend fun invoke(
            args: JsonObject,
            ctx: ToolContext,
        ): ToolOutput {
            // 与 input_text 同口径：空串视为未提供，不写空剪贴板。
            val text = args.str("text") ?: return toolError(CommonErrorCodes.INVALID_ARGUMENT, "必须提供 text")
            clipboardManager(context).setPrimaryClip(ClipData.newPlainText("autop", text))
            return textOutput("已写入剪贴板")
        }
    }

private fun clipboardManager(context: Context): ClipboardManager =
    context.getSystemService(ClipboardManager::class.java)
