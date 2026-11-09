package com.jari.agent.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.jari.agent.Agent
import com.jari.agent.AgentUi
import com.jari.agent.CarState
import com.jari.agent.SeatId
import com.jari.agent.WindowId
import org.junit.Rule
import org.junit.Test

/** 기획서·README용 화면 시안. 결과: app/src/test/snapshots/images */
class ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(
            screenWidth = 1682, screenHeight = 1210, xdpi = 160, ydpi = 160, // Pleos landscape (window 2개)
            density = Density.MEDIUM, orientation = ScreenOrientation.LANDSCAPE,
        ),
        theme = "android:Theme.Material.Light.NoActionBar",
    )

    private val state = CarState.initial()
        .withSeat(SeatId.DRIVER) { copy(heat = 2, pos = pos + (com.jari.agent.Axis.LUMBAR to 80)) }
        .withSeat(SeatId.PASSENGER) { copy(vent = 2, pos = pos + (com.jari.agent.Axis.RECLINE to 70)) }
        .let { it.copy(windows = it.windows + (WindowId.FR to 30) + (WindowId.ROOF to 50)) }

    private val ui = AgentUi(
        heard = "허리가 아파",
        reply = Agent.plan("허리가 아파", CarState.initial()).speech,
        history = listOf(
            "허리가 아파" to "운전석 허리 지지대를 높이고 열선을 2단으로 켰어요.",
            "조수석 좀 눕혀줘" to "조수석 등받이를 30%에서 50%로 조정했어요.",
        ),
    )

    @Test fun home() = shot(0)
    @Test fun seats() = shot(1)
    @Test fun windows() = shot(2)
    @Test fun drivingBlocked() = paparazzi.snapshot {
        val s = state.copy(driving = true)
        JariTheme { App(s, AgentUi(heard = "너무 졸려", reply = Agent.plan("너무 졸려", s).speech, listening = true), {}, {}, {}, {}) }
    }

    @Test fun chargeStations() = shot(3)
    @Test fun charging() = paparazzi.snapshot {
        val s = state.copy(charging = true, battery = 41, chargeStart = 22, navTo = "epit-pangyo")
        JariTheme { App(s, ui, {}, {}, {}, {}, initialTab = 3) }
    }

    private fun shot(tab: Int) = paparazzi.snapshot {
        JariTheme { App(state, ui, {}, {}, {}, {}, initialTab = tab) }
    }
}
