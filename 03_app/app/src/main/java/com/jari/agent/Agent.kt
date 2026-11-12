package com.jari.agent

enum class SeatId(val label: String) {
    DRIVER("운전석"), PASSENGER("조수석"), REAR_LEFT("뒷좌석 왼쪽"), REAR_RIGHT("뒷좌석 오른쪽")
}

enum class WindowId(val label: String) {
    FL("운전석 창문"), FR("조수석 창문"), RL("뒷좌석 왼쪽 창문"), RR("뒷좌석 오른쪽 창문"), ROOF("선루프")
}

/** 외부 개폐부. 값은 0(닫힘)..100(완전 열림). */
enum class Opening(val label: String) {
    FRUNK("프렁크"), TRUNK("트렁크"), CHARGE_PORT("충전구")
}

enum class Axis(val label: String) {
    FORE_AFT("앞뒤 위치"), RECLINE("등받이"), HEIGHT("높이"), LUMBAR("허리 지지")
}

/** 시트 축 값은 0..100 (FORE_AFT 클수록 앞, RECLINE 클수록 눕힘). 열선/통풍 0..3단. */
data class SeatState(
    val pos: Map<Axis, Int> = DEFAULT_POS,
    val heat: Int = 0,
    val vent: Int = 0,
    val occupied: Boolean = false,
)

/** 창문 값은 0(닫힘)..100(완전 열림). */
data class CarState(
    val seats: Map<SeatId, SeatState>,
    val windows: Map<WindowId, Int>,
    val driving: Boolean = false,
    val live: Boolean = false, // Pleos Vehicle Service 연결 여부
    val battery: Int = 34, // SoC %
    val chargeLimit: Int = 80,
    val charging: Boolean = false,
    val chargeStart: Int = 34, // 충전 시작 시 SoC (충전량 계산용)
    val navTo: String? = null, // 길안내 중인 충전소 id
    val navProgress: Float = 0f, // 앱 안 길안내 시뮬레이션 진행률 0..1 (Pleos 내비가 없을 때)
    val driveBase: Map<Axis, Int>? = null, // 주행을 시작할 때의 운전석 자세 (안전 가드 기준점)
    val openings: Map<Opening, Int> = Opening.entries.associateWith { 0 },
) {
    val rangeKm get() = (battery * KM_PER_PERCENT).toInt()

    fun seat(id: SeatId) = seats.getValue(id)
    fun withSeat(id: SeatId, f: SeatState.() -> SeatState) = copy(seats = seats + (id to seat(id).f()))

    companion object {
        fun initial() = CarState(
            seats = SeatId.entries.associateWith {
                SeatState(occupied = it == SeatId.DRIVER || it == SeatId.PASSENGER)
            },
            windows = WindowId.entries.associateWith { 0 },
        )
    }
}

val DEFAULT_POS = mapOf(Axis.FORE_AFT to 50, Axis.RECLINE to 30, Axis.HEIGHT to 50, Axis.LUMBAR to 30)
private val REST = mapOf(Axis.FORE_AFT to 20, Axis.RECLINE to 85, Axis.HEIGHT to 35, Axis.LUMBAR to 40)
private val DRIVE = mapOf(Axis.FORE_AFT to 60, Axis.RECLINE to 25, Axis.HEIGHT to 55, Axis.LUMBAR to 60)
private val EXIT = mapOf(Axis.FORE_AFT to 0, Axis.HEIGHT to 20)
private val SIDE = listOf(WindowId.FL, WindowId.FR, WindowId.RL, WindowId.RR)

sealed interface Action {
    data class Seat(val seat: SeatId, val axis: Axis, val value: Int) : Action
    data class Heat(val seat: SeatId, val level: Int) : Action
    data class Vent(val seat: SeatId, val level: Int) : Action
    data class Window(val window: WindowId, val percent: Int) : Action
    data class Navigate(val stationId: String) : Action
    data object StopNavigate : Action
    data class Charge(val on: Boolean) : Action
    data class ChargeLimit(val percent: Int) : Action
    data class Open(val opening: Opening, val open: Boolean) : Action
}

data class Plan(val actions: List<Action>, val speech: String)

/**
 * 발화 → 차량 제어 계획. 온디바이스 규칙 기반이라 네트워크 없이 즉시 동작.
 * 복합 문장("조수석 눕히고 열선 켜줘")은 절 단위로 나눠 각각 해석하고, 좌석 지칭은 앞 절에서 이어받는다.
 * ponytail: 키워드 매칭이라 사전에 없는 표현은 못 알아듣는다. LLM 플래너로 교체 시 plan() 시그니처 유지.
 */
object Agent {
    const val HELP = "좌석이나 창문에 대해 말씀해 주세요. 예: \"허리가 아파\", \"조수석 좀 눕혀줘\", \"창문 반만 열어\""

    /** 주행 중 운전석 자세가 이 값(%)보다 크게 바뀌면 차단. */
    const val DRIVING_LIMIT = 10

    // "~고 싶어"의 "고"는 절 경계가 아니다 ("쉬고 싶고 창문 열어줘" → "쉬고 싶" / "창문 열어줘")
    private val CLAUSE = Regex("그리고|(?<=[가-힣])(?<!최)고\\s+(?!싶)|,\\s*|다음에?\\s+")
    private val SEAT_WORD = Regex("모든 ?좌석|전체 ?좌석|전좌석|운전석|조수석|옆자리|뒷좌석 ?(왼쪽|오른쪽)?|뒷자리 ?(왼쪽|오른쪽)?")

    fun plan(raw: String, s: CarState): Plan {
        val clauses = raw.split(CLAUSE).map { it.trim() }.filter { it.isNotEmpty() }
        if (clauses.size < 2) return planOne(raw, s)
        var seat = ""
        val plans = clauses.map { c ->
            val named = SEAT_WORD.find(c)?.value
            if (named != null) seat = named
            planOne(if (named == null && seat.isNotEmpty()) "$seat $c" else c, s)
        }.filter { it.speech != HELP }
        // 나눴더니 아무 절도 못 알아들었으면 문장 전체로 다시 본다
        if (plans.isEmpty()) return planOne(raw, s)
        return Plan(plans.flatMap { it.actions }.distinct(), plans.joinToString(" ") { it.speech })
    }

    private fun planOne(raw: String, s: CarState): Plan {
        val t = raw.replace(" ", "")
        if (t.isBlank()) return Plan(emptyList(), HELP)
        if (t.has("안내", "내비") && t.has("종료", "그만", "취소", "멈춰", "꺼")) return Plan(
            listOf(Action.StopNavigate),
            if (s.navTo == null) "지금은 길안내 중이 아니에요." else "길안내를 종료할게요.",
        )
        if (t.has("창문", "창", "윈도우", "선루프", "썬루프", "환기", "답답")) return guard(windows(t), s)
        openings(t)?.let { return guard(it, s) }
        charging(t, s)?.let { return it }

        val seats = targetSeats(t, s)
        val off = t.has("꺼", "끄", "그만", "됐어")
        fun posture(p: Map<Axis, Int>) = seats.flatMap { id -> p.map { (a, v) -> Action.Seat(id, a, v) } }

        val plan = when {
            t.has("피곤", "졸려", "졸음", "잠와", "잠이와", "쉬고", "휴식", "눕고", "낮잠") -> {
                if (s.driving && SeatId.DRIVER in seats) return Plan(
                    listOf(Action.Window(WindowId.FL, 20), Action.Vent(SeatId.DRIVER, 3)),
                    "주행 중에는 운전석을 눕힐 수 없어요. 대신 창문을 조금 열고 통풍 시트를 켰어요. 가까운 휴게소에서 꼭 쉬어 가세요.",
                )
                Plan(posture(REST), "${names(seats)} 휴식 모드로 바꿨어요. 등받이를 눕히고 시트를 뒤로 뺐어요.")
            }
            t.has("허리") && !t.has("줄여", "약하게", "빼", "열선", "통풍") -> Plan(
                seats.flatMap { listOf(Action.Seat(it, Axis.LUMBAR, 80), Action.Heat(it, 2)) },
                "${names(seats)} 허리 지지대를 높이고 열선을 2단으로 켰어요. 오래 운전하신다면 한 시간마다 스트레칭 해주세요.",
            )
            t.has("추워", "춥", "쌀쌀", "따뜻", "열선", "시려") -> {
                val level = if (off) 0 else level(t)
                val closeOpen = if (off) emptyList() else SIDE.filter { s.windows.getValue(it) > 0 }.map { Action.Window(it, 0) }
                Plan(
                    seats.map { Action.Heat(it, level) } + closeOpen,
                    if (level == 0) "${names(seats)} 열선을 껐어요."
                    else "${names(seats)} 열선을 ${level}단으로 켰어요." + if (closeOpen.isNotEmpty()) " 열린 창문도 닫았어요." else "",
                )
            }
            t.has("더워", "덥", "땀", "통풍", "시원", "후끈") -> {
                val level = if (off) 0 else level(t)
                Plan(
                    seats.map { Action.Vent(it, level) },
                    if (level == 0) "${names(seats)} 통풍을 껐어요." else "${names(seats)} 통풍을 ${level}단으로 켰어요.",
                )
            }
            t.has("원래", "기본", "초기화", "처음") -> Plan(
                posture(DEFAULT_POS) + seats.flatMap { listOf(Action.Heat(it, 0), Action.Vent(it, 0)) },
                "${names(seats)} 기본 자세로 되돌렸어요.",
            )
            t.has("운전자세", "운전모드", "집중", "똑바로앉") ->
                Plan(posture(DRIVE), "${names(seats)} 운전하기 좋은 자세로 맞췄어요.")
            t.has("내릴", "내리기", "타기", "승하차", "탈게", "나갈") ->
                Plan(posture(EXIT), "${names(seats)} 타고 내리기 편하게 시트를 뒤로 빼고 낮췄어요.")
            else -> adjust(t, s, seats)
        }
        return guard(plan, s)
    }

    /**
     * 안전 가드: 주행 중 운전석 자세(허리 지지 제외)를 크게 바꾸거나 트렁크·프렁크·충전구를 여는 동작은 제거한다.
     * 기준은 주행을 시작할 때의 자세라서 "조금씩" 여러 번 나눠 말해도 합계가 한도를 넘으면 막힌다.
     * 수동 조작도 이 경로를 탄다.
     */
    fun guard(plan: Plan, s: CarState): Plan {
        if (!s.driving) return plan
        val base = s.driveBase ?: s.seat(SeatId.DRIVER).pos
        val seats = plan.actions.filterIsInstance<Action.Seat>().filter {
            it.seat == SeatId.DRIVER && it.axis != Axis.LUMBAR &&
                kotlin.math.abs(it.value - base.getValue(it.axis)) > DRIVING_LIMIT
        }
        val opens = plan.actions.filterIsInstance<Action.Open>().filter { it.open }
        if (seats.isEmpty() && opens.isEmpty()) return plan
        val kept = plan.actions - (seats + opens).toSet()
        val notes = listOfNotNull(
            if (seats.isEmpty()) null else "주행 중이라 운전석 ${seats.map { it.axis.label }.distinct().joinToString("·")} 변경은 정차 후에 할게요.",
            if (opens.isEmpty()) null else "주행 중에는 ${opens.joinToString(", ") { it.opening.label }}${josa(opens.last().opening.label)} 열 수 없어요.",
        ).joinToString(" ")
        return Plan(kept, if (kept.isEmpty()) notes else "${plan.speech} 단, $notes".trim())
    }

    private fun openings(t: String): Plan? {
        val o = when {
            t.has("프렁크", "프론트트렁크", "앞트렁크", "보닛", "본넷") -> Opening.FRUNK
            t.has("트렁크", "테일게이트", "뒷문") -> Opening.TRUNK
            t.has("충전구", "충전포트", "충전단자") -> Opening.CHARGE_PORT
            else -> return null
        }
        val close = t.has("닫", "잠가")
        if (!close && !t.has("열", "오픈")) return null
        return Plan(listOf(Action.Open(o, !close)), "${o.label}${josa(o.label)} ${if (close) "닫을게요" else "열게요"}.")
    }

    private fun adjust(t: String, s: CarState, seats: List<SeatId>): Plan {
        val amt = amount(t)
        val recline = t.has("눕혀", "눕히", "젖혀", "젖히", "기울", "눕게")
        val acts = mutableListOf<Action.Seat>()
        for (id in seats) {
            fun add(axis: Axis, d: Int) {
                acts += Action.Seat(id, axis, (s.seat(id).pos.getValue(axis) + d).coerceIn(0, 100))
            }
            when {
                recline -> add(Axis.RECLINE, amt)
                t.has("세워", "세우", "일으켜", "일으키", "바로세") -> add(Axis.RECLINE, -amt)
            }
            when {
                t.has("앞으로", "당겨", "당기") -> add(Axis.FORE_AFT, amt)
                !recline && t.has("뒤로", "밀어", "공간", "넓게", "빼줘") -> add(Axis.FORE_AFT, -amt)
            }
            when {
                t.has("높여", "올려", "올리", "위로") -> add(Axis.HEIGHT, amt)
                t.has("낮춰", "낮추", "내려", "아래로") -> add(Axis.HEIGHT, -amt)
            }
        }
        if (acts.isEmpty()) return Plan(emptyList(), HELP)
        return Plan(acts, acts.joinToString(" ") {
            "${it.seat.label} ${it.axis.label}를 ${s.seat(it.seat).pos.getValue(it.axis)}%에서 ${it.value}%로 조정했어요."
        })
    }

    private fun charging(t: String, s: CarState): Plan? = when {
        t.has("충전한도", "충전제한", "충전량제한") -> {
            val p = (percent(t) ?: Regex("(\\d+)").find(t)?.value?.toInt() ?: 80).coerceIn(50, 100)
            Plan(listOf(Action.ChargeLimit(p)), "충전 한도를 ${p}%로 설정했어요.")
        }
        t.has("충전소", "충전기", "충전해야", "배터리부족", "배터리없", "충전할곳") -> {
            val st = nearestAvailable()
            Plan(
                listOf(Action.Navigate(st.id)),
                "배터리 ${s.battery}%, 약 ${s.rangeKm}km 갈 수 있어요. 가장 가까운 빈 충전기는 ${st.name}, " +
                    "${st.km}km 거리 ${st.minutes}분이에요. 길안내를 시작할게요.",
            )
        }
        // "충전 끝나면 ~"은 충전을 멈추라는 말이 아니다
        t.has("충전") && t.has("멈춰", "중지", "그만", "끝") && !t.has("끝나면", "끝나고") ->
            Plan(listOf(Action.Charge(false)), "충전을 멈췄어요. 현재 배터리는 ${s.battery}%예요.")
        else -> null
    }

    private fun windows(t: String): Plan {
        if (t.has("환기", "답답")) return Plan(SIDE.map { Action.Window(it, 20) }, "환기를 위해 창문을 모두 20% 열었어요.")
        val targets = when {
            t.has("선루프", "썬루프", "지붕") -> listOf(WindowId.ROOF)
            t.has("전부", "모두", "모든", "전체", "다열", "다닫", "다내려", "다올려") -> SIDE
            t.has("조수석", "옆자리") -> listOf(WindowId.FR)
            t.has("뒷", "뒤창", "뒤쪽") -> when {
                t.has("왼") -> listOf(WindowId.RL)
                t.has("오른") -> listOf(WindowId.RR)
                else -> listOf(WindowId.RL, WindowId.RR)
            }
            else -> listOf(WindowId.FL)
        }
        val close = t.has("닫", "올려", "잠가")
        if (!close && !t.has("열", "내려", "오픈")) return Plan(emptyList(), HELP)
        val v = if (close) 0 else percent(t) ?: when {
            t.has("반") -> 50
            t.has("조금", "살짝", "약간") -> 20
            else -> 100
        }
        val what = if (targets == SIDE) "창문을 모두" else targets.joinToString(", ") { it.label } + josa(targets.last().label)
        val done = when (v) { 0 -> "닫았어요"; 100 -> "열었어요"; else -> "$v% 열었어요" }
        return Plan(targets.map { Action.Window(it, v) }, "$what $done.")
    }

    private fun targetSeats(t: String, s: CarState): List<SeatId> = when {
        t.has("모든좌석", "전체좌석", "전좌석", "모두", "다같이") ->
            s.seats.filterValues { it.occupied }.keys.toList().ifEmpty { SeatId.entries }
        t.has("조수석", "옆자리", "옆사람", "동승") -> listOf(SeatId.PASSENGER)
        t.has("뒷좌석", "뒷자리", "뒤에앉", "아이자리") -> when {
            t.has("왼") -> listOf(SeatId.REAR_LEFT)
            t.has("오른") -> listOf(SeatId.REAR_RIGHT)
            else -> listOf(SeatId.REAR_LEFT, SeatId.REAR_RIGHT)
        }
        else -> listOf(SeatId.DRIVER)
    }

    private fun amount(t: String) = when {
        t.has("끝까지", "최대", "완전", "최고") -> 100
        t.has("많이", "훨씬", "확") -> 35
        t.has("조금", "살짝", "약간") -> 10
        else -> 20
    }

    private fun level(t: String) = when {
        t.has("1단", "약하게", "조금", "살짝") -> 1
        t.has("2단", "중간") -> 2
        else -> 3
    }

    private fun percent(t: String) = Regex("(\\d+)(%|퍼센트|프로)").find(t)?.groupValues?.get(1)?.toInt()?.coerceIn(0, 100)

    private fun names(seats: List<SeatId>) = seats.joinToString(", ") { it.label }

    /** 받침 있으면 "을", 없으면 "를". */
    private fun josa(word: String): String {
        val c = word.last()
        return if (c in '가'..'힣' && (c - '가') % 28 != 0) "을" else "를"
    }

    private fun String.has(vararg k: String) = k.any { contains(it) }
}

/** 요청이 없어도 차량 신호만 보고 JARI가 먼저 챙기는 상황. */
enum class Care { LONG_DRIVE, ARRIVED_TIRED, LOW_BATTERY }

/**
 * 기어(주행 여부)·배터리 신호를 시간에 따라 지켜보다가 챙길 순간을 알려준다.
 * 2시간 기준은 "2시간 운전 후 15분 휴식" 장거리 운전 권고를 따른 것.
 * ponytail: 잠깐 정차(P)해도 연속 운전 시간이 초기화된다. 필요하면 정차 유예 시간을 둔다.
 */
class CareMonitor {
    private var driveMin = 0f
    private var nextLong = LONG_DRIVE_MIN
    private var lowWarned = false

    val drivingMinutes get() = driveMin.toInt()

    /** [minutes] = 지난 호출 이후 흐른 시간(분). 챙길 일이 생긴 순간에만 값을 돌려준다. */
    fun tick(s: CarState, minutes: Float): Care? {
        if (!s.driving) {
            val tired = driveMin >= TIRED_MIN
            driveMin = 0f; nextLong = LONG_DRIVE_MIN; lowWarned = false
            return if (tired) Care.ARRIVED_TIRED else null
        }
        driveMin += minutes
        return when {
            driveMin >= nextLong -> { nextLong += LONG_DRIVE_MIN; Care.LONG_DRIVE }
            s.battery <= LOW_BATTERY && !lowWarned -> { lowWarned = true; Care.LOW_BATTERY }
            else -> null
        }
    }

    companion object {
        const val LONG_DRIVE_MIN = 120f
        const val TIRED_MIN = 60f
        const val LOW_BATTERY = 20

        /** 상황별로 JARI가 스스로 하는 일. 주행 중에도 안전한 동작(허리 지지·통풍)만 실행하고 나머지는 제안만 한다. */
        fun plan(care: Care, s: CarState, minutes: Int): Plan = when (care) {
            Care.LONG_DRIVE -> {
                val lumbar = s.seat(SeatId.DRIVER).pos.getValue(Axis.LUMBAR)
                Plan(
                    listOf(Action.Seat(SeatId.DRIVER, Axis.LUMBAR, if (lumbar >= 60) lumbar - 20 else lumbar + 20)),
                    "${minutes / 60}시간째 운전 중이에요. 같은 곳이 계속 눌리지 않게 허리 지지대 위치를 바꿨어요. 다음 휴게소에서 잠깐 쉬어 가세요.",
                )
            }
            Care.ARRIVED_TIRED -> Plan(
                emptyList(),
                "${if (minutes >= 60) "${minutes / 60}시간 " else ""}${if (minutes % 60 > 0) "${minutes % 60}분 " else ""}운전하셨어요. 수고하셨어요. \"쉬고 싶어\"라고 하시면 휴식 자세로 바꿔 드릴게요.",
            )
            Care.LOW_BATTERY -> Plan(
                emptyList(),
                "배터리가 ${s.battery}%, 약 ${s.rangeKm}km 남았어요. \"충전소 찾아줘\"라고 하시면 빈 충전기로 안내할게요.",
            )
        }
    }
}
