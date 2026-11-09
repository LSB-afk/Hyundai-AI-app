package com.jari.agent

data class Station(
    val id: String,
    val name: String,
    val address: String,
    val km: Double,
    val minutes: Int,
    val kw: Int,
    val free: Int,
    val total: Int,
    val won: Int, // 원/kWh
    val parking: String,
    val rating: Double,
    val lat: Double,
    val lon: Double,
    val mapX: Float, // 지도 카드 위 위치 (0..1)
    val mapY: Float,
)

/**
 * ponytail: 데모용 고정 데이터. 충전소 실시간 정보는 Pleos SDK에 없어서
 * 환경부 공공 충전소 API(또는 E-pit API) 연동 시 이 리스트만 교체하면 된다.
 */
val STATIONS = listOf(
    Station("epit-pangyo", "E-pit 판교 테크노밸리", "성남시 분당구 판교역로 166", 2.4, 7, 350, 3, 6, 347, "1시간 무료", 4.8, 37.4012, 127.1086, 0.86f, 0.44f),
    Station("epit-seopangyo", "E-pit 서판교", "성남시 분당구 운중로 135", 4.1, 11, 350, 0, 4, 347, "2시간 무료", 4.6, 37.3913, 127.0782, 0.18f, 0.22f),
    Station("jeongja", "정자역 공영주차장 급속", "성남시 분당구 정자일로 1", 6.1, 15, 100, 2, 4, 324, "1,000원/시간", 4.3, 37.3666, 127.1086, 0.18f, 0.72f),
    Station("sunae", "수내 충전스테이션", "성남시 분당구 황새울로 200", 7.4, 19, 200, 5, 8, 309, "무료", 4.5, 37.3783, 127.1145, 0.70f, 0.80f),
)

/** 지도 위 내 차 위치 (0..1). 도로 격자 교차점에 맞춤 (ui/ChargeScreens.kt MAIN_X/MAIN_Y) */
const val CAR_X = 0.52f
const val CAR_Y = 0.58f

/** ponytail: 아이오닉5 롱레인지 기준 근사. 실차는 EvBattery.getEvBatteryCapacity·효율 API로 대체. */
const val BATTERY_KWH = 84.0
const val KM_PER_PERCENT = 4.6

fun station(id: String?) = STATIONS.find { it.id == id }
fun nearestAvailable() = STATIONS.filter { it.free > 0 }.minBy { it.km }
