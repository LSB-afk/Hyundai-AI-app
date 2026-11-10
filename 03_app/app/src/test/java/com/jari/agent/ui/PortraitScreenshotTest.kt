package com.jari.agent.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.jari.agent.Agent
import com.jari.agent.AgentUi
import com.jari.agent.Axis
import com.jari.agent.CarState
import com.jari.agent.Opening
import com.jari.agent.SeatId
import com.jari.agent.WindowId
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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

    /** 휴식 자세(운전석 눕힘·뒤로·낮게) + 뒷좌석 눕힘, 트렁크·프렁크·충전구 열림 */
    @Test fun motion() = paparazzi.snapshot {
        val rest = mapOf(Axis.FORE_AFT to 20, Axis.RECLINE to 85, Axis.HEIGHT to 35, Axis.LUMBAR to 40)
        val s = CarState.initial()
            .withSeat(SeatId.DRIVER) { copy(pos = rest) }
            .withSeat(SeatId.REAR_LEFT) { copy(pos = pos + (Axis.RECLINE to 90)) }
            .let { it.copy(openings = Opening.entries.associateWith { 100 }, windows = it.windows + (WindowId.FL to 60)) }
        JariTheme {
            Column(Modifier.padding(20.dp)) {
                Panel { Cabin3D(s, rememberSeatAnims(s), SeatId.DRIVER, {}, Modifier.fillMaxWidth().height(440.dp), side = true) }
                ExteriorPanel(s) {}
            }
        }
    }

    private fun shot(s: CarState, tab: Int) = paparazzi.snapshot {
        JariTheme { App(s, ui, {}, {}, {}, {}, initialTab = tab) }
    }
}
