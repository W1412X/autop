/**
 * 语音转文字端口（系统自带语音识别，无需 API Key）。实现见 device-impl 的 SystemSpeechInput。
 * 调用方只通过本端口开始、停止、取消识别；麦克风权限由调用方（UI）先行取得。
 */
package com.example.autop.device

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** 语音转文字。 */
public interface SpeechInput {
    /** 设备是否有可用的系统语音识别服务。 */
    public fun available(): Boolean

    /** 是否正在识别。 */
    public val listening: StateFlow<Boolean>

    /** 录音音量 0..1（录音中实时更新，用于音波动效）。 */
    public val level: StateFlow<Float>

    /** 识别过程中的临时结果（尚未确定的整句文字）。 */
    public val partial: StateFlow<String>

    /** 确定的识别结果（一句话结束后推送一次）。 */
    public val results: SharedFlow<String>

    /** 识别出错（错误码为固定词汇，不含原始内容）。 */
    public val errors: SharedFlow<String>

    /** 开始识别。已在识别中时无效果。 */
    public fun start()

    /** 结束识别并交出已听到的内容。 */
    public fun stop()

    /** 取消识别，丢弃结果。 */
    public fun cancel()
}
