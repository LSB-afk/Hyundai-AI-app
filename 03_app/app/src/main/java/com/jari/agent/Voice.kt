package com.jari.agent

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.tts.constant.Mode as TtsMode
import android.speech.tts.TextToSpeech as AndroidTts

/**
 * 음성 입출력. Pleos STT/TTS(ON_DEVICE)가 기본이고, Pleos 서비스가 없는 환경(일반 에뮬레이터 등)에서는
 * 안드로이드 기본 음성인식·TTS로 대신한다. 콜백은 모두 메인 스레드에서 부른다.
 */
class Voice(
    context: Context,
    private val onText: (text: String, final: Boolean) -> Unit,
    private val onListening: (Boolean) -> Unit,
    private val onRms: (Float) -> Unit,
    private val onError: (String) -> Unit,
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
            Log.w(TAG, "STT error: $errorType")
            main.post { onListening(false); onError(NOT_HEARD) }
        }
    }

    private val stt = runCatching {
        SpeechToText(context, SttMode.ON_DEVICE).apply {
            initialize()
            addListener(listener)
            setLocale(Locale.KOREA)
        }
    }.onFailure { Log.w(TAG, "Pleos STT 없음 → 안드로이드 음성인식 사용", it) }.getOrNull()

    private val tts = runCatching {
        TextToSpeech(context, TtsMode.ON_DEVICE).apply {
            initialize()
            setLocale(Locale.KOREA)
        }
    }.onFailure { Log.w(TAG, "Pleos TTS 없음 → 안드로이드 TTS 사용", it) }.getOrNull()

    // ── 대체 경로: 안드로이드 기본 음성인식 / TTS ──
    private val recognizer: SpeechRecognizer? =
        if (stt == null && SpeechRecognizer.isRecognitionAvailable(context)) {
            runCatching { SpeechRecognizer.createSpeechRecognizer(context) }
                .onFailure { Log.w(TAG, "SpeechRecognizer init failed", it) }.getOrNull()
                ?.apply { setRecognitionListener(androidListener()) }
        } else null

    private var androidTtsReady = false
    private val androidTts: AndroidTts? = if (tts == null) AndroidTts(context) { status ->
        androidTtsReady = status == AndroidTts.SUCCESS
        if (!androidTtsReady) Log.w(TAG, "Android TTS init failed: $status")
    } else null

    /** 안드로이드 음성인식은 마이크 런타임 권한이 필요하다(Pleos STT는 Manifest 권한만으로 동작). */
    val needsMicPermission get() = stt == null && recognizer != null

    fun listen(): Boolean {
        stt?.let { return runCatching { it.request() }.isSuccess }
        val r = recognizer ?: return false
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        return runCatching { r.startListening(intent) }.onFailure { Log.w(TAG, "startListening failed", it) }.isSuccess
    }

    fun stop() {
        runCatching { stt?.stop() }
        runCatching { recognizer?.stopListening() }
    }

    fun speak(text: String) {
        tts?.let { pleos -> runCatching { pleos.speak(text) }; return }
        val t = androidTts ?: return
        if (!androidTtsReady) return
        runCatching {
            t.language = Locale.KOREA
            t.speak(text, AndroidTts.QUEUE_FLUSH, null, "jari")
        }
    }

    fun release() {
        runCatching { stt?.removeListener(listener); stt?.release() }
        runCatching { tts?.release() }
        runCatching { recognizer?.destroy() }
        runCatching { androidTts?.shutdown() }
    }

    private fun androidListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = onListening(true)
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = onRms(rmsdB)
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
        override fun onPartialResults(partial: Bundle?) {
            first(partial)?.let { onText(it, false) }
        }
        override fun onResults(results: Bundle?) {
            onListening(false)
            val text = first(results)
            if (text.isNullOrBlank()) onError(NOT_HEARD) else onText(text, true)
        }
        override fun onError(error: Int) {
            Log.w(TAG, "SpeechRecognizer error: $error")
            onListening(false)
            onError(
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> NOT_HEARD
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "마이크 권한이 필요해요."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                        "음성 인식 서버에 연결할 수 없어요. 추천 문장을 누르거나 직접 입력해 주세요."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "음성 인식이 아직 처리 중이에요. 잠시 후 다시 눌러 주세요."
                    else -> "음성 인식에 실패했어요. 추천 문장을 누르거나 직접 입력해 주세요."
                },
            )
        }
    }

    private fun first(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private companion object {
        const val TAG = "JARI"
        const val NOT_HEARD = "잘 못 들었어요. 마이크를 누르고 다시 말씀해 주세요."
    }
}
