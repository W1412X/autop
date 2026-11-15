/**
 * [SpeechInput] 的系统实现：系统 [SpeechRecognizer]，不内置 API Key。
 * 每次会话新建识别器，结束、出错、取消时释放（与 dsh-Go 一致）；录音状态只在识别器确实开始后才置为 true，
 * 停止后若引擎迟迟不回调，看门狗会收尾，避免界面卡在录音态。
 */
package com.example.autop.device.impl.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.autop.device.SpeechInput
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
public class SystemSpeechInput
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : SpeechInput {
        private val main = Handler(Looper.getMainLooper())
        private var recognizer: SpeechRecognizer? = null
        private var cancelled = false

        /** 会话代号：每次开始或结束录音都递增，过期的回调与看门狗据此被忽略。 */
        private var generation = 0

        private val mutableListening = MutableStateFlow(false)
        override val listening: StateFlow<Boolean> = mutableListening.asStateFlow()

        private val mutablePartial = MutableStateFlow("")
        override val partial: StateFlow<String> = mutablePartial.asStateFlow()

        private val mutableLevel = MutableStateFlow(0f)
        override val level: StateFlow<Float> = mutableLevel.asStateFlow()

        private val mutableResults = MutableSharedFlow<String>(extraBufferCapacity = EVENT_BUFFER)
        override val results: SharedFlow<String> = mutableResults.asSharedFlow()

        private val mutableErrors = MutableSharedFlow<String>(extraBufferCapacity = EVENT_BUFFER)
        override val errors: SharedFlow<String> = mutableErrors.asSharedFlow()

        private val stopWatchdog = Runnable { forceFinish() }

        override fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

        override fun start() =
            onMain {
                if (mutableListening.value) return@onMain
                if (!available()) {
                    mutableErrors.tryEmit(ERR_NO_SERVICE)
                    return@onMain
                }
                releaseRecognizer()
                cancelled = false
                val session = ++generation
                val engine =
                    runCatching { SpeechRecognizer.createSpeechRecognizer(context) }
                        .onFailure { Log.w(TAG, "createSpeechRecognizer failed", it) }
                        .getOrNull()
                if (engine == null) {
                    mutableErrors.tryEmit(errorCodeOf(SpeechRecognizer.ERROR_CLIENT))
                    return@onMain
                }
                engine.setRecognitionListener(SessionListener(session))
                recognizer = engine
                val started = runCatching { engine.startListening(recognitionIntent()) }
                if (started.isFailure) {
                    Log.w(TAG, "startListening failed", started.exceptionOrNull())
                    releaseRecognizer()
                    mutableErrors.tryEmit(errorCodeOf(SpeechRecognizer.ERROR_CLIENT))
                    return@onMain
                }
                mutablePartial.value = ""
                mutableListening.value = true
            }

        /** 第一次：请引擎给出最终结果并启动看门狗；再次点击（或看门狗超时）：直接提交已有文本并结束。 */
        override fun stop() =
            onMain {
                if (!mutableListening.value) return@onMain
                if (main.hasCallbacks(stopWatchdog)) {
                    forceFinish()
                    return@onMain
                }
                runCatching { recognizer?.stopListening() }
                main.postDelayed(stopWatchdog, STOP_TIMEOUT_MS)
            }

        override fun cancel() =
            onMain {
                cancelled = true
                finishSession()
                releaseRecognizer()
            }

        /** 引擎迟迟不给结果时收尾：已有的 partial 文本作为结果提交。 */
        private fun forceFinish() {
            val pending = mutablePartial.value
            finishSession()
            releaseRecognizer()
            if (!cancelled && pending.isNotBlank()) mutableResults.tryEmit(pending)
        }

        private fun recognitionIntent(): Intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)

        private fun onMain(block: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
        }

        /** 结束当前会话的界面状态；递增代号，使此后到达的旧回调失效。 */
        private fun finishSession() {
            generation++
            main.removeCallbacks(stopWatchdog)
            mutableListening.value = false
            mutablePartial.value = ""
            mutableLevel.value = 0f
        }

        private fun releaseRecognizer() {
            val engine = recognizer ?: return
            recognizer = null
            runCatching { engine.cancel() }
            runCatching { engine.destroy() }
        }

        /** 只处理所属会话的回调；过期会话的回调直接忽略。 */
        private inner class SessionListener(
            private val session: Int,
        ) : RecognitionListener {
            private fun current() = session == generation

            override fun onReadyForSpeech(params: Bundle?) = Unit

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) {
                // 静音约 -2 dB，常见声音约 10 dB；归一化到 0..1
                if (current()) mutableLevel.value = ((rmsdB + RMS_OFFSET_DB) / RMS_RANGE_DB).coerceIn(0f, 1f)
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                if (!current()) return
                val text = partialResults?.firstText().orEmpty()
                if (text.isNotBlank()) mutablePartial.value = text
            }

            override fun onResults(results: Bundle?) {
                if (!current()) return
                val text = results?.firstText().orEmpty()
                val wasCancelled = cancelled
                finishSession()
                releaseRecognizer()
                if (!wasCancelled && text.isNotBlank()) mutableResults.tryEmit(text)
            }

            override fun onError(error: Int) {
                if (!current()) return
                val wasCancelled = cancelled
                finishSession()
                releaseRecognizer()
                if (wasCancelled) return
                Log.w(TAG, "recognition error $error")
                mutableErrors.tryEmit(errorCodeOf(error))
            }

            override fun onEvent(
                eventType: Int,
                params: Bundle?,
            ) = Unit
        }

        private fun Bundle.firstText(): String? =
            getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

        /** 错误码原样保留（speech.err.N），界面按 SpeechRecognizer 的错误表给出具体原因。 */
        private fun errorCodeOf(error: Int): String =
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) ERR_PERMISSION else "speech.err.$error"

        private companion object {
            const val TAG = "SystemSpeechInput"
            const val EVENT_BUFFER = 4
            const val ERR_PERMISSION = "speech.permission"
            const val ERR_NO_SERVICE = "speech.no_service"
            const val STOP_TIMEOUT_MS = 1_500L
            const val RMS_OFFSET_DB = 2f
            const val RMS_RANGE_DB = 12f
        }
    }
