package com.jari.agent

import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.MoveControl
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.EvChargePortOpenListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatBackrestAnglePositionListener
import ai.pleos.playground.vehicle.listener.SeatForeAftPositionListener
import ai.pleos.playground.vehicle.listener.SeatHeightPositionListener
import ai.pleos.playground.vehicle.listener.SeatLumbarForeAftPositionListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 시뮬레이션 패널에서 바꾸는 차량 신호 */
sealed interface Sim {
    data class Battery(val percent: Int) : Sim
    data class Occupied(val seat: SeatId, val on: Boolean) : Sim
}

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
private val DOOR_AREA = mapOf(
    Opening.TRUNK to DoorArea(DoorValue.REAR),
    Opening.FRUNK to DoorArea(DoorValue.HOOD),
)

/**
 * Pleos Vehicle SDK 브리지. 서비스가 없으면(일반 에뮬레이터) 같은 상태 머신으로 시뮬레이션만 한다.
 * UI 상태는 항상 여기서 갱신되고, SDK 호출은 실패해도 로그만 남긴다.
 */
class CarController(context: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(CarState.initial())
    val state: StateFlow<CarState> = _state.asStateFlow()

    /** 요청 없이 JARI가 알려야 할 일(길안내 시뮬레이션 도착 등). 화면·음성으로 전한다 */
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events.asSharedFlow()

    // SDK 위치 콜백(바인더 스레드)에서도 읽으므로 동시성 맵
    private val jobs = java.util.concurrent.ConcurrentHashMap<Any, Job>()
    private val windowBounds = mutableMapOf<WindowId, AreaBounds<Int>>()
    private val doorBounds = mutableMapOf<Opening, AreaBounds<Int>>()
    /** 구동 중 실제 위치가 들어온 시트 축. 이후로는 시간 계산 대신 실제 위치를 따른다. */
    private val reported = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<SeatId, Axis>>()
    private val unregister = mutableListOf<Vehicle.() -> Unit>()
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

    private val portListener = object : EvChargePortOpenListener {
        override fun onEvChargePortOpenUpdated(open: Boolean?) = setOpening(Opening.CHARGE_PORT, if (open == true) 100 else 0)
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
            getEvBattery().isEvChargePortOpen({ open -> setOpening(Opening.CHARGE_PORT, if (open == true) 100 else 0) }, ::fail)
            getEvBattery().registerEvChargePortOpen(portListener)
            unregister += { getEvBattery().unregisterEvChargePortOpen(portListener) }
            getDoor().checkDoorPositionCapabilities({ map: Map<DoorArea, AreaBounds<Int>> ->
                map.forEach { (area, b) -> DOOR_AREA.entries.find { it.value == area }?.let { watchDoor(it.key, b) } }
            }, ::fail)
            Axis.entries.forEach(::watchSeat)
        }
    }

    /** 트렁크/프렁크 실제 위치를 받아 화면에 반영 */
    private fun watchDoor(o: Opening, b: AreaBounds<Int>) = sdk {
        doorBounds[o] = b
        val area = DOOR_AREA.getValue(o)
        val l = object : DoorPositionListener {
            override fun onPositionUpdated(raw: Int?) { if (raw != null) setOpening(o, percent(raw, b)) }
            override fun onFailed(e: Exception) = fail(e)
        }
        getDoor().getCurrentDoorPosition(area, { raw -> if (raw != null) setOpening(o, percent(raw, b)) }, ::fail)
        getDoor().registerDoorPosition(area, l)
        unregister += { getDoor().unregisterDoorPosition(area, l) }
    }

    /** 시트 축별 위치 범위를 확인하고, 되는 축은 실제 위치를 구독한다. */
    private fun watchSeat(axis: Axis) = sdk {
        val onCaps = { _: CapabilityConfig, map: Map<SeatArea, AreaBounds<Int>> ->
            map.forEach { (area, b) ->
                val id = SEAT_AREA.entries.find { it.value == area }?.key ?: return@forEach
                subscribeSeat(id, axis, b)
            }
        }
        with(getSeat()) {
            when (axis) {
                Axis.FORE_AFT -> checkSeatForeAftPositionCapabilities(onCaps, ::fail)
                Axis.RECLINE -> checkSeatBackrestAnglePositionCapabilities(onCaps, ::fail)
                Axis.HEIGHT -> checkSeatHeightPositionCapabilities(onCaps, ::fail)
                Axis.LUMBAR -> checkSeatLumbarForeAftPositionCapabilities(onCaps, ::fail)
            }
        }
    }

    private fun subscribeSeat(id: SeatId, axis: Axis, b: AreaBounds<Int>) = sdk {
        val area = SEAT_AREA.getValue(id)
        val on = { raw: Int? -> if (raw != null) onSeatPos(id, axis, percent(raw, b)) }
        with(getSeat()) {
            when (axis) {
                Axis.FORE_AFT -> {
                    getCurrentSeatForeAftPosition(area, on, ::fail)
                    val l = object : SeatForeAftPositionListener {
                        override fun onSeatForeAftPositionUpdated(v: Int?) = on(v)
                        override fun onFailed(e: Exception) = fail(e)
                    }
                    registerSeatForeAftPosition(area, l)
                    unregister += { getSeat().unregisterSeatForeAftPosition(area, l) }
                }
                Axis.RECLINE -> {
                    getCurrentSeatBackrestAnglePosition(area, on, ::fail)
                    val l = object : SeatBackrestAnglePositionListener {
                        override fun onSeatBackrestAnglePositionUpdated(v: Int?) = on(v)
                        override fun onFailed(e: Exception) = fail(e)
                    }
                    registerSeatBackrestAnglePosition(area, l)
                    unregister += { getSeat().unregisterSeatBackrestAnglePosition(area, l) }
                }
                Axis.HEIGHT -> {
                    getCurrentSeatHeightPosition(area, on, ::fail)
                    val l = object : SeatHeightPositionListener {
                        override fun onSeatHeightPositionUpdated(v: Int?) = on(v)
                        override fun onFailed(e: Exception) = fail(e)
                    }
                    registerSeatHeightPosition(area, l)
                    unregister += { getSeat().unregisterSeatHeightPosition(area, l) }
                }
                Axis.LUMBAR -> {
                    getCurrentSeatLumbarForeAftPosition(area, on, ::fail)
                    val l = object : SeatLumbarForeAftPositionListener {
                        override fun onSeatLumbarForeAftPositionUpdated(v: Int?) = on(v)
                        override fun onFailed(e: Exception) = fail(e)
                    }
                    registerSeatLumbarForeAftPosition(area, l)
                    unregister += { getSeat().unregisterSeatLumbarForeAftPosition(area, l) }
                }
            }
        }
    }

    private fun onSeatPos(id: SeatId, axis: Axis, p: Int) {
        if (jobs[id to axis]?.isActive == true) reported += id to axis
        _state.update { it.withSeat(id) { copy(pos = pos + (axis to p)) } }
    }

    private fun setOpening(o: Opening, p: Int) = _state.update { it.copy(openings = it.openings + (o to p)) }

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
            Action.StopNavigate -> stopNavigate()
            is Action.Charge -> charge(a.on)
            is Action.ChargeLimit -> {
                _state.update { it.copy(chargeLimit = a.percent) }
                sdk { getEvBattery().setEvChargePercentLimit(a.percent.toFloat(), { }, ::fail) }
            }
            is Action.Open -> open(a)
        }
    }

    /**
     * 트렁크/프렁크 = Door 절대 위치, 충전구 = EvBattery 스위치. 화면은 목표값으로 바로 바꾸고
     * UI가 실제 개폐 속도로 애니메이션한다. 실차는 리스너가 실제 위치로 덮어쓴다.
     */
    private fun open(a: Action.Open) {
        setOpening(a.opening, if (a.open) 100 else 0)
        sdk {
            when (a.opening) {
                Opening.CHARGE_PORT -> getEvBattery().setEvChargePortOpen(a.open, { }, ::fail)
                else -> {
                    val b = doorBounds[a.opening] ?: AreaBounds(0, 100)
                    getDoor().setDoorPosition(DOOR_AREA.getValue(a.opening), if (a.open) b.maxValue else b.minValue, { _, _ -> }, ::fail)
                }
            }
        }
    }

    /** Pleos 내비로 길안내. Pleos 내비가 없는 환경(일반 에뮬레이터)에서는 앱 안에서 안내를 시뮬레이션한다. */
    private fun navigate(id: String) {
        val st = station(id) ?: return
        jobs.remove(NAV)?.cancel()
        _state.update { it.copy(navTo = id, navProgress = 0f) }
        val pleos = navi?.let { n ->
            runCatching {
                n.requestRoute(RouteInfo(latitude = st.lat, longitude = st.lon, poiName = st.name, poiId = st.id, address = st.address, poiSubId = "0"))
            }.onFailure(::fail).isSuccess
        } ?: false
        if (!pleos) simulateRoute(st)
    }

    /**
     * 데모용 안내: 기어를 D로 두고 지도 위 경로를 따라간다. 소요 시간 1분을 [SIM_SEC_PER_MIN]초로 줄여 보여준다.
     * 도중에 P로 바꾸면 멈췄다가 D면 이어 가고, 도착하면 P로 바꾸고 알린다.
     */
    private fun simulateRoute(st: Station) {
        if (!_state.value.live) setDriving(true)
        val step = 100f / (st.minutes * SIM_SEC_PER_MIN * 1000f)
        jobs[NAV] = scope.launch {
            while (_state.value.navProgress < 1f) {
                delay(100)
                if (_state.value.driving) _state.update { it.copy(navProgress = (it.navProgress + step).coerceAtMost(1f)) }
            }
            jobs.remove(NAV)
            _state.update { it.copy(navTo = null, navProgress = 0f) }
            if (!_state.value.live) setDriving(false)
            _events.tryEmit("${st.name}에 도착했어요. 충전을 시작하면 시트를 휴식 모드로 바꿔 드릴게요.")
        }
    }

    private fun stopNavigate() {
        val simulated = jobs.remove(NAV)?.also { it.cancel() } != null
        if (!simulated) runCatching { navi?.cancelRoute() }.onFailure(::fail)
        _state.update { it.copy(navTo = null, navProgress = 0f) }
        if (simulated && !_state.value.live) setDriving(false)
    }

    private fun charge(on: Boolean) {
        _state.update { it.copy(charging = on, chargeStart = if (on) it.battery else it.chargeStart) }
        if (on) setDriving(false)
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

    /** 시뮬레이션 모드에서만: 배터리·탑승 좌석을 바꾼다 (데모용). 실차에서는 차량 신호를 따른다. */
    fun sim(c: Sim) {
        if (_state.value.live) return
        when (c) {
            is Sim.Battery -> _state.update { it.copy(battery = c.percent.coerceIn(0, 100)) }
            is Sim.Occupied -> _state.update { it.withSeat(c.seat) { copy(occupied = c.on) } }
        }
    }

    /** 시뮬레이션 모드에서만: 주행/정차 전환 (데모용). 실차에서는 기어 신호를 따른다. */
    fun toggleDriving() {
        if (!_state.value.live) setDriving(!_state.value.driving)
    }

    fun release() {
        jobs.values.forEach { it.cancel() }
        sdk {
            getGear().unregisterGearSelection(gearListener)
            getEvBattery().unregisterEvBatteryStateOfCharge(socListener)
            unregister.forEach { it() }
        }
        runCatching { vehicle?.release() }
        runCatching { navi?.release() }
    }

    private fun onGear(gear: VehicleGear?) {
        setDriving(gear != null && gear != VehicleGear.GEAR_PARK && gear != VehicleGear.GEAR_UNKNOWN)
    }

    /**
     * 주행 시작: 그 순간의 운전석 자세를 안전 가드 기준점으로 잡고, 정차 중에 시작한 운전석 자세 변경(허리 지지 제외)은 멈춘다.
     * 기어 콜백은 바인더 스레드에서도 오므로 Job 취소만 하고, 모터 STOP은 각 Job의 finally가 보낸다.
     */
    private fun setDriving(driving: Boolean) {
        val was = _state.value.driving
        _state.update { it.copy(driving = driving, driveBase = if (driving) it.driveBase ?: it.seat(SeatId.DRIVER).pos else null) }
        if (driving && !was) Axis.entries.filter { it != Axis.LUMBAR }.forEach { jobs.remove(SeatId.DRIVER to it)?.cancel() }
    }

    /**
     * 시트 모터는 위치 지정이 아니라 FORWARD/BACKWARD/STOP 방식.
     * 기본은 시간으로 구동(개방 루프). 구동 중 실제 위치가 들어오면 그때부터 실제 위치가 목표에 닿을 때 STOP(폐루프).
     * (Pleos 에뮬레이터는 위치 범위는 주지만 모터를 시뮬레이션하지 않아 위치가 변하지 않는다)
     * ponytail: 원시 위치값의 증가 방향 = 우리 값 증가 방향으로 가정. 실차에서 반대면 percent()에서 뒤집기.
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
        reported -= key
        jobs[key] = scope.launch {
            seatMotor(a.seat, a.axis, if (dir > 0) up else down)
            try {
                var p = from
                while (p != a.value && key !in reported) {
                    delay(MS_PER_PERCENT)
                    p += dir
                    val v = p
                    _state.update { it.withSeat(a.seat) { copy(pos = pos + (a.axis to v)) } }
                }
                if (key in reported) {
                    // 방향이 반대로 매핑돼 있어도 무한 구동하지 않도록 예상 시간의 3배에서 끊는다
                    withTimeoutOrNull(kotlin.math.abs(a.value - from) * MS_PER_PERCENT * 3 + 1000) {
                        state.first { val q = it.seat(a.seat).pos.getValue(a.axis); if (dir > 0) q >= a.value else q <= a.value }
                    }
                }
            } finally {
                seatMotor(a.seat, a.axis, MoveControl.STOP)
                reported -= key
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

    private fun percent(raw: Int, b: AreaBounds<Int>) =
        if (b.maxValue == b.minValue) 0 else ((raw - b.minValue) * 100 / (b.maxValue - b.minValue)).coerceIn(0, 100)

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
        /** 길안내 시뮬레이션: 소요 1분을 몇 초로 보여줄지 */
        const val SIM_SEC_PER_MIN = 4f
        private const val NAV = "nav"
    }
}
