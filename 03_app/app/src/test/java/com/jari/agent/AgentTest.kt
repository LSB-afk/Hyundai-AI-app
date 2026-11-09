package com.jari.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTest {
    private val parked = CarState.initial()
    private val driving = parked.copy(driving = true)

    @Test fun reclinePassenger() {
        val p = Agent.plan("조수석 좀 눕혀줘", parked)
        assertEquals(listOf(Action.Seat(SeatId.PASSENGER, Axis.RECLINE, 50)), p.actions)
    }

    @Test fun drivingBlocksDriverRecline() {
        val p = Agent.plan("운전석 끝까지 눕혀줘", driving)
        assertTrue(p.actions.isEmpty())
        assertTrue(p.speech.contains("주행 중"))
    }

    @Test fun drivingAllowsSmallAdjust() {
        val p = Agent.plan("등받이 조금 세워줘", driving)
        assertEquals(listOf(Action.Seat(SeatId.DRIVER, Axis.RECLINE, 20)), p.actions)
    }

    @Test fun drowsyWhileDriving() {
        val p = Agent.plan("너무 졸려", driving)
        assertEquals(listOf(Action.Window(WindowId.FL, 20), Action.Vent(SeatId.DRIVER, 3)), p.actions)
    }

    @Test fun restWhenParked() {
        val p = Agent.plan("좀 쉬고 싶어", parked)
        assertTrue(Action.Seat(SeatId.DRIVER, Axis.RECLINE, 85) in p.actions)
    }

    @Test fun backPain() {
        val p = Agent.plan("허리가 아파", driving)
        assertEquals(listOf(Action.Seat(SeatId.DRIVER, Axis.LUMBAR, 80), Action.Heat(SeatId.DRIVER, 2)), p.actions)
    }

    @Test fun windows() {
        assertEquals(SIDE_CLOSED, Agent.plan("창문 전부 닫아줘", parked).actions)
        assertEquals(listOf(Action.Window(WindowId.ROOF, 50)), Agent.plan("선루프 반만 열어", parked).actions)
        assertEquals(listOf(Action.Window(WindowId.FR, 30)), Agent.plan("조수석 창문 30% 열어줘", parked).actions)
    }

    @Test fun heatOffAndCold() {
        val opened = parked.copy(windows = parked.windows + (WindowId.FL to 40))
        val p = Agent.plan("추워", opened)
        assertEquals(listOf(Action.Heat(SeatId.DRIVER, 3), Action.Window(WindowId.FL, 0)), p.actions)
        assertEquals(listOf(Action.Heat(SeatId.DRIVER, 0)), Agent.plan("열선 꺼줘", parked).actions)
    }

    @Test fun findCharger() {
        val p = Agent.plan("배터리 부족해 충전소 찾아줘", parked)
        assertEquals(listOf(Action.Navigate("epit-pangyo")), p.actions) // 서판교는 만석이라 제외
        assertTrue(p.speech.contains("2.4km"))
    }

    @Test fun chargeLimitAndRestWhileCharging() {
        assertEquals(listOf(Action.ChargeLimit(90)), Agent.plan("충전 한도 90%로 해줘", parked).actions)
        assertTrue(Action.Seat(SeatId.DRIVER, Axis.RECLINE, 85) in Agent.plan("충전하는 동안 휴식 모드", parked).actions)
        assertEquals(listOf(Action.Charge(false)), Agent.plan("충전 그만", parked).actions)
    }

    @Test fun unknown() {
        assertEquals(Agent.HELP, Agent.plan("오늘 날씨 어때", parked).speech)
    }

    private companion object {
        val SIDE_CLOSED = listOf(WindowId.FL, WindowId.FR, WindowId.RL, WindowId.RR).map { Action.Window(it, 0) }
    }
}
