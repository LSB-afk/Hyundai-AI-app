package com.jari.agent

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import com.jari.agent.ui.App
import com.jari.agent.ui.JariTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class AgentUi(
    val heard: String = "",
    val reply: String = "안녕하세요, JARI예요. 자세나 창문, 편하게 말로 맡겨 주세요.",
    val listening: Boolean = false,
    val rms: Float = 0f,
    val history: List<Pair<String, String>> = emptyList(),
)

class MainActivity : ComponentActivity() {
    private lateinit var car: CarController
    private lateinit var voice: Voice
    private val ui = mutableStateOf(AgentUi())
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) mic() else say("마이크 권한이 없어 음성으로 들을 수 없어요. 추천 문장을 누르거나 직접 입력해 주세요.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        car = CarController(applicationContext, lifecycleScope)
        voice = Voice(
            applicationContext,
            onText = { text, final -> if (final) ask(text) else ui.value = ui.value.copy(heard = text) },
            onListening = { ui.value = ui.value.copy(listening = it) },
            onRms = { ui.value = ui.value.copy(rms = it) },
            onError = ::say,
        )
        watchCare()
        lifecycleScope.launch { car.events.collect(::announce) }
        setContent {
            JariTheme {
                val state by car.state.collectAsState()
                App(
                    state = state,
                    ui = ui.value,
                    onAsk = ::ask,
                    onMic = ::mic,
                    onAction = ::manual,
                    onToggleDriving = car::toggleDriving,
                    onSim = car::sim,
                )
            }
        }
    }

    /** 음성/텍스트/프리셋 → 에이전트 계획 → 실행 → TTS 응답 */
    private fun ask(text: String) {
        if (text.isBlank()) return // 음성인식이 빈 결과로 끝난 경우
        val plan = Agent.plan(text, car.state.value)
        car.execute(plan.actions)
        ui.value = ui.value.copy(
            heard = text, reply = plan.speech, listening = false,
            history = (listOf(text to plan.speech) + ui.value.history).take(5),
        )
        voice.speak(plan.speech)
    }

    /**
     * 차량 신호를 1초마다 살펴 JARI가 먼저 말을 건다(연속 운전 2시간, 장거리 후 정차, 배터리 부족).
     * debug 빌드는 시연용으로 1초를 1분으로 센다(2분이면 "2시간 운전"). release 빌드는 실제 시간.
     */
    private fun watchCare() = lifecycleScope.launch {
        val monitor = CareMonitor()
        val minPerSec = if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) 1f else 1f / 60f
        while (true) {
            delay(1000)
            val s = car.state.value
            val drove = monitor.drivingMinutes
            val care = monitor.tick(s, minPerSec) ?: continue
            val plan = CareMonitor.plan(care, s, if (care == Care.ARRIVED_TIRED) drove else monitor.drivingMinutes)
            car.execute(plan.actions)
            announce(plan.speech)
        }
    }

    /** 요청 없이 JARI가 먼저 말하는 것(챙기기·길안내 도착) */
    private fun announce(text: String) {
        ui.value = ui.value.copy(reply = text, history = (listOf("JARI가 먼저" to text) + ui.value.history).take(5))
        voice.speak(text)
    }

    /** 화면 수동 조작도 같은 안전 가드를 거친다. */
    private fun manual(actions: List<Action>) {
        val plan = Agent.guard(Plan(actions, ""), car.state.value)
        car.execute(plan.actions)
        if (plan.speech.isNotBlank()) {
            ui.value = ui.value.copy(reply = plan.speech)
            voice.speak(plan.speech)
        }
    }

    private fun mic() {
        when {
            ui.value.listening -> voice.stop()
            voice.needsMicPermission && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            voice.listen() -> ui.value = ui.value.copy(heard = "", listening = true)
            else -> say("음성 서비스를 사용할 수 없어요. 아래 추천 문장을 누르거나 직접 입력해 주세요.")
        }
    }

    /** 명령 실행 없이 안내만 화면에 띄운다(음성인식 실패 등). */
    private fun say(text: String) {
        ui.value = ui.value.copy(reply = text, listening = false)
    }

    override fun onDestroy() {
        voice.release()
        car.release()
        super.onDestroy()
    }
}
