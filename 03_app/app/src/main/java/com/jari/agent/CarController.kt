package com.jari.agent

import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.MoveControl
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val SEAT_AREA = mapOf(
    SeatId.DRIVER to SeatArea(SeatValue.ROW_1_LEFT),
    SeatId.PASSENGER to SeatArea(SeatValue.ROW_1_RIGHT),
    SeatId.REAR_LEFT to SeatArea(SeatValue.ROW_2_LEFT),
    SeatId.REAR_RIGHT to SeatArea(SeatValue.ROW_2_RIGHT),
)
private val WINDOW_AREA = mapOf(
    WindowId.FL to WindowArea(WindowValue.ROW_1_LEFT),
    WindowId.FR to WindowArea(WindowValue.ROW_1_RIGHT),
    WindowId.RL to WindowArea(WindowValue.ROW_2_LEFT),
    WindowId.RR to WindowArea(WindowValue.ROW_2_RIGHT),
    WindowId.ROOF to WindowArea(WindowValue.ROOF_TOP_1),
)

/**
 * Pleos Vehicle SDK 브리지. 서비스가 없으면(일반 에뮬레이터) 같은 상태 머신으로 시뮬레이션만 한다.
 * UI 상태는 항상 여기서 갱신되고, SDK 호출은 실패해도 로그만 남긴다.
 */
class CarController(context: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(CarState.initial())
    val state: StateFlow<CarState> = _state.asStateFlow()

    private val jobs = mutableMapOf<Any, Job>()
    private val windowBounds = mutableMapOf<WindowId, AreaBounds<Int>>()
    private val vehicle: Vehicle? = runCatching { Vehicle(context).apply { initialize() } }.onFailure(::fail).getOrNull()

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) = onGear(gear)
        override fun onFailed(e: Exception) = fail(e)
    }

    private val navi: NaviHelper? = runCatching { NaviHelper(context).apply { initialize() } }.onFailure(::fail).getOrNull()

    private val socListener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(soc: Int?) = onSoc(soc)
        override fun onFailed(e: Exception) = fail(e)
    }

    init {
        sdk {
            getEvBattery().getEvBatteryStateOfCharge(::onSoc, ::fail)
            getEvBattery().registerEvBatteryStateOfCharge(socListener)
            getEvBattery().getEvChargeSwitchState({ on -> _state.update { it.copy(charging = on == true) } }, ::fail)
            getWindow().checkWindowPositionCapabilities({ map: Map<WindowArea, AreaBounds<Int>> ->
                map.forEach { (area, b) -> WINDOW_AREA.entries.find { it.value == area }?.let { windowBounds[it.key] = b } }
                _state.update { it.copy(live = true) }
            }, ::fail)
            SEAT_AREA.forEach { (id, area) ->
                getSeat().getCurrentSeatOccupancy(area, { occ -> _state.update { it.withSeat(id) { copy(occupied = occ == true) } } }, ::fail)
            }
            getGear().getCurrentGearSelectionState(::onGear, ::fail)
            getGear().registerGearSelection(gearListener)
        }
    }

    fun execute(actions: List<Action>) = actions.forEach { a ->
        when (a) {
            is Action.Seat -> moveSeat(a)
            is Action.Window -> moveWindow(a)
            is Action.Heat -> {
                _state.update { it.withSeat(a.seat) { copy(heat = a.level) } }
                sdk { getSeat().setHvacSeatTemperature(SEAT_AREA.getValue(a.seat), a.level, { _, _ -> }, ::fail) }
            }
            is Action.Vent -> {
                _state.update { it.withSeat(a.seat) { copy(vent = a.level) } }
                sdk { getSeat().setHvacSeatVentilation(SEAT_AREA.getValue(a.seat), a.level, { _, _ -> }, ::fail) }
            }
            is Action.Navigate -> navigate(a.stationId)
            is Action.Charge -> charge(a.on)
            is Action.ChargeLimit -> {
                _state.update { it.copy(chargeLimit = a.percent) }
                sdk { getEvBattery().setEvChargePercentLimit(a.percent.toFloat(), { }, ::fail) }
            }
        }
    }

    private fun navigate(id: String) {
        val st = station(id) ?: return
        _state.update { it.copy(navTo = id) }
        runCatching {
            navi?.requestRoute(
                RouteInfo(latitude = st.lat, longitude = st.lon, poiName = st.name, poiId = st.id, address = st.address, poiSubId = "0"),
            )
        }.onFailure(::fail)
    }

    private fun charge(on: Boolean) {
        _state.update { it.copy(charging = on, chargeStart = if (on) it.battery else it.chargeStart, driving = if (on) false else it.driving) }
        sdk { getEvBattery().setEvChargeSwitch(on, { }, ::fail) }
        jobs.remove("charge")?.cancel()
        // 실차는 SoC 리스너가 갱신. 시뮬레이션에서만 충전 진행을 흉내낸다.
        if (on && !_state.value.live) jobs["charge"] = scope.launch {
            while (_state.value.charging && _state.value.battery < _state.value.chargeLimit) {
                delay(600)
                _state.update { it.copy(battery = it.battery + 1) }
            }
            _state.update { it.copy(charging = false) }
        }
    }

    private fun onSoc(soc: Int?) {
        if (soc != null) _state.update { it.copy(battery = soc) }
    }

    /** 시뮬레이션 모드에서만: 주행/정차 전환 (데모용). 실차에서는 기어 신호를 따른다. */
    fun toggleDriving() {
        if (!_state.value.live) _state.update { it.copy(driving = !it.driving) }
    }

    fun release() {
        jobs.values.forEach { it.cancel() }
        sdk {
            getGear().unregisterGearSelection(gearListener)
            getEvBattery().unregisterEvBatteryStateOfCharge(socListener)
        }
        runCatching { vehicle?.release() }
        runCatching { navi?.release() }
    }

    private fun onGear(gear: VehicleGear?) {
        _state.update { it.copy(driving = gear != null && gear != VehicleGear.GEAR_PARK && gear != VehicleGear.GEAR_UNKNOWN) }
    }

    /**
     * 시트 모터는 위치 지정이 아니라 FORWARD/BACKWARD/STOP 방식이라, 목표까지 시간으로 구동한 뒤 STOP.
     * ponytail: 개방 루프 타이밍. 실차에서 MS_PER_PERCENT 보정 필요, 위치 조회 API로 폐루프 전환 가능.
     */
    private fun moveSeat(a: Action.Seat) {
        val key = a.seat to a.axis
        jobs.remove(key)?.cancel()
        val from = _state.value.seat(a.seat).pos.getValue(a.axis)
        if (from == a.value) return
        val dir = if (a.value > from) 1 else -1
        // 등받이는 값이 클수록 눕힘 = BACKWARD. 나머지는 값 증가 = FORWARD (실차에서 방향 확인 필요)
        val up = if (a.axis == Axis.RECLINE) MoveControl.BACKWARD else MoveControl.FORWARD
        val down = if (up == MoveControl.FORWARD) MoveControl.BACKWARD else MoveControl.FORWARD
        jobs[key] = scope.launch {
            seatMotor(a.seat, a.axis, if (dir > 0) up else down)
            try {
                var p = from
                while (p != a.value) {
                    delay(MS_PER_PERCENT)
                    p += dir
                    val v = p
                    _state.update { it.withSeat(a.seat) { copy(pos = pos + (a.axis to v)) } }
                }
            } finally {
                seatMotor(a.seat, a.axis, MoveControl.STOP)
            }
        }
    }

    private fun seatMotor(seat: SeatId, axis: Axis, c: MoveControl) = sdk {
        val area = SEAT_AREA.getValue(seat)
        val ok: (SeatArea, MoveControl?) -> Unit = { _, _ -> }
        with(getSeat()) {
            when (axis) {
                Axis.FORE_AFT -> moveSeatForeAft(area, c, ok, ::fail)
                Axis.RECLINE -> moveSeatBackrestAngle(area, c, ok, ::fail)
                Axis.HEIGHT -> moveSeatHeight(area, c, ok, ::fail)
                Axis.LUMBAR -> moveSeatLumbarForeAft(area, c, ok, ::fail)
            }
        }
    }

    /** 창문은 절대 위치 API. 0%=min(닫힘), 100%=max(열림)으로 Capability 범위에 매핑. */
    private fun moveWindow(a: Action.Window) {
        jobs.remove(a.window)?.cancel()
        sdk {
            val b = windowBounds[a.window] ?: AreaBounds(0, 100)
            val raw = b.minValue + (b.maxValue - b.minValue) * a.percent / 100
            getWindow().setWindowPosition(WINDOW_AREA.getValue(a.window), raw, { _, _ -> }, ::fail)
        }
        jobs[a.window] = scope.launch {
            var p = _state.value.windows.getValue(a.window)
            while (p != a.percent) {
                delay(12)
                p += if (a.percent > p) 1 else -1
                val v = p
                _state.update { it.copy(windows = it.windows + (a.window to v)) }
            }
        }
    }

    private inline fun sdk(block: Vehicle.() -> Unit) {
        val v = vehicle ?: return
        runCatching { v.block() }.onFailure(::fail)
    }

    private fun fail(e: Throwable) {
        Log.w(TAG, "vehicle call failed: ${e.message}")
    }

    companion object {
        private const val TAG = "JARI"
        /** 시트 1% 이동 시간(ms). 실차 보정 노브. */
        const val MS_PER_PERCENT = 35L
    }
}
