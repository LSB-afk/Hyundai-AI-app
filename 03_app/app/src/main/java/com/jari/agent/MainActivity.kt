package com.jari.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import com.jari.agent.ui.App
import com.jari.agent.ui.JariTheme

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        car = CarController(applicationContext, lifecycleScope)
        voice = Voice(
            applicationContext,
            onText = { text, final -> if (final) ask(text) else ui.value = ui.value.copy(heard = text) },
            onListening = { ui.value = ui.value.copy(listening = it) },
            onRms = { ui.value = ui.value.copy(rms = it) },
        )
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
                )
            }
        }
    }

    /** 음성/텍스트/프리셋 → 에이전트 계획 → 실행 → TTS 응답 */
    private fun ask(text: String) {
        val plan = Agent.plan(text, car.state.value)
        car.execute(plan.actions)
        ui.value = ui.value.copy(
            heard = text, reply = plan.speech, listening = false,
            history = (listOf(text to plan.speech) + ui.value.history).take(5),
        )
        voice.speak(plan.speech)
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
            voice.listen() -> ui.value = ui.value.copy(heard = "", listening = true)
            else -> ui.value = ui.value.copy(reply = "음성 서비스를 사용할 수 없어요. 아래 추천 문장을 누르거나 직접 입력해 주세요.")
        }
    }

    override fun onDestroy() {
        voice.release()
        car.release()
        super.onDestroy()
    }
}
