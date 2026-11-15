/**
 * [SpeechInput] 的实现：安卓系统自带的语音识别（[SpeechRecognizer]），不需要 API Key。
 * SpeechRecognizer 必须在主线程创建和调用，所以所有对外动作都切到主线程执行。
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

        private val mutableListening = MutableStateFlow(false)
        override val listening: StateFlow<Boolean> = mutableListening.asStateFlow()

        private val mutablePartial = MutableStateFlow("")
        override val partial: StateFlow<String> = mutablePartial.asStateFlow()

        private val mutableResults = MutableSharedFlow<String>(extraBufferCapacity = EVENT_BUFFER)
        override val results: SharedFlow<String> = mutableResults.asSharedFlow()

        private val mutableErrors = MutableSharedFlow<String>(extraBufferCapacity = EVENT_BUFFER)
        override val errors: SharedFlow<String> = mutableErrors.asSharedFlow()

        override fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

        override fun start() =
            onMain {
                if (mutableListening.value) return@onMain
                if (!available()) {
                    mutableErrors.tryEmit(ERR_NO_SERVICE)
                    return@onMain
                }
                // 每次会话新建识别器，结束后释放（与 dsh-Go 一致）：复用已出错的识别器会在后续启动时报 ERROR_CLIENT
                releaseRecognizer()
                val engine = createRecognizer()
                cancelled = false
                mutablePartial.value = ""
                mutableListening.value = true
                engine.startListening(recognitionIntent())
            }

        override fun stop() =
            onMain {
                if (mutableListening.value) recognizer?.stopListening()
            }

        override fun cancel() =
            onMain {
                cancelled = true
                finishSession()
                releaseRecognizer()
            }

        private fun createRecognizer(): SpeechRecognizer {
            val engine = SpeechRecognizer.createSpeechRecognizer(context)
            engine.setRecognitionListener(listener)
            recognizer = engine
            return engine
        }

        private fun releaseRecognizer() {
            val engine = recognizer ?: return
            recognizer = null
            runCatching { engine.cancel() }
            runCatching { engine.destroy() }
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

        private fun finishSession() {
            mutableListening.value = false
            mutablePartial.value = ""
        }

        private val listener =
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit

                override fun onBeginningOfSpeech() = Unit

                override fun onRmsChanged(rmsdB: Float) = Unit

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() = Unit

                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults?.firstText().orEmpty()
                    if (text.isNotBlank()) mutablePartial.value = text
                }

                override fun onResults(results: Bundle?) {
                    val text = results?.firstText().orEmpty()
                    val wasCancelled = cancelled
                    finishSession()
                    releaseRecognizer()
                    if (!wasCancelled && text.isNotBlank()) mutableResults.tryEmit(text)
                }

                override fun onError(error: Int) {
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
        }
    }
