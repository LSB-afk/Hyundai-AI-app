package com.jari.agent

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.tts.constant.Mode as TtsMode

/**
 * Pleos STT/TTS 래퍼. ON_DEVICE 모드라 앱 등록·네트워크 불필요.
 * 콜백은 바인더 스레드에서 오므로 메인으로 넘긴다.
 */
class Voice(
    context: Context,
    private val onText: (text: String, final: Boolean) -> Unit,
    private val onListening: (Boolean) -> Unit,
    private val onRms: (Float) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    private val listener = object : ResultListener {
        override fun onReady() = Unit
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onStartedRecognition() { main.post { onListening(true) } }
        override fun onUpdated(stt: String, completed: Boolean) { main.post { onText(stt, completed) } }
        override fun onUpdatedRms(rms: Double) { main.post { onRms(rms.toFloat()) } }
        override fun onEndedRecognition() { main.post { onListening(false) } }
        override fun onError(errorType: ErrorType) {
            Log.w("JARI", "STT error: $errorType")
            main.post { onListening(false) }
        }
    }

    private val stt = runCatching {
        SpeechToText(context, SttMode.ON_DEVICE).apply {
            initialize()
            addListener(listener)
            setLocale(Locale.KOREA)
        }
    }.onFailure { Log.w("JARI", "STT init failed", it) }.getOrNull()

    private val tts = runCatching {
        TextToSpeech(context, TtsMode.ON_DEVICE).apply {
            initialize()
            setLocale(Locale.KOREA)
        }
    }.onFailure { Log.w("JARI", "TTS init failed", it) }.getOrNull()

    fun listen(): Boolean {
        val s = stt ?: return false
        return runCatching { s.request() }.isSuccess
    }

    fun stop() {
        runCatching { stt?.stop() }
    }

    fun speak(text: String) {
        runCatching { tts?.speak(text) }
    }

    fun release() {
        runCatching { stt?.removeListener(listener); stt?.release() }
        runCatching { tts?.release() }
    }
}
