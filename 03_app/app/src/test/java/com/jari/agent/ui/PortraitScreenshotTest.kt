package com.jari.agent.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.jari.agent.Agent
import com.jari.agent.AgentUi
import com.jari.agent.Axis
import com.jari.agent.CarState
import com.jari.agent.SeatId
import com.jari.agent.WindowId
import org.junit.Rule
import org.junit.Test

/** Pleos 기본 실행 형태: 앱 영역 window 1개 (portrait) */
class PortraitScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(
            screenWidth = 841, screenHeight = 1210, xdpi = 160, ydpi = 160,
            density = Density.MEDIUM, orientation = ScreenOrientation.PORTRAIT,
        ),
        theme = "android:Theme.Material.Light.NoActionBar",
    )

    private val state = CarState.initial()
        .withSeat(SeatId.DRIVER) { copy(heat = 2, pos = pos + (Axis.LUMBAR to 80)) }
        .withSeat(SeatId.PASSENGER) { copy(vent = 2, pos = pos + (Axis.RECLINE to 70)) }
        .let { it.copy(windows = it.windows + (WindowId.FR to 30) + (WindowId.ROOF to 50)) }

    private val ui = AgentUi(heard = "허리가 아파", reply = Agent.plan("허리가 아파", CarState.initial()).speech)

    @Test fun home() = shot(state, 0)
    @Test fun seats() = shot(state, 1)
    @Test fun charge() = shot(state, 3)
    @Test fun seatsWhileDriving() = shot(state.copy(driving = true), 1)

    private fun shot(s: CarState, tab: Int) = paparazzi.snapshot {
        JariTheme { App(s, ui, {}, {}, {}, {}, initialTab = tab) }
    }
}
