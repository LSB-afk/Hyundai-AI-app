package com.jari.agent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jari.agent.Action
import com.jari.agent.BATTERY_KWH
import com.jari.agent.CAR_AVG_KW
import com.jari.agent.CAR_X
import com.jari.agent.CAR_Y
import com.jari.agent.CarState
import com.jari.agent.STATIONS
import com.jari.agent.Station
import com.jari.agent.station
import java.text.NumberFormat
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private val MAIN_X = listOf(0.18f, 0.52f, 0.86f)
private val MAIN_Y = listOf(0.22f, 0.58f, 0.80f)
private val MapBlock = Color(0xFFE4E7EC)
private val Green = Color(0xFF1FA463)
private val Red = Color(0xFFE5484D)

private fun won(v: Number) = NumberFormat.getInstance().format(v.toLong()) + "원"

// ───────────────────────── 좌측: 지도 ─────────────────────────

@Composable
internal fun MapCard(state: CarState, selectedId: String, onSelect: (String) -> Unit, onAction: (List<Action>) -> Unit, modifier: Modifier) = BoxWithConstraints(
    modifier.shadow(10.dp, RoundedCornerShape(26.dp), spotColor = Color(0x22000000)).clip(RoundedCornerShape(26.dp)).background(MapBlock),
) {
    val w = maxWidth
    val h = maxHeight
    val sel = station(selectedId) ?: STATIONS.first()
    // 길안내 중이면 안내 대상까지, 아니면 선택한 충전소까지 경로를 그린다. 내 차는 진행률만큼 경로를 따라간다
    val guide = station(state.navTo)
    val route = routeOf(guide ?: sel)
    val (carAt, ahead) = splitRoute(route, if (guide != null) state.navProgress else 0f, w.value, h.value)
    Canvas(Modifier.fillMaxSize()) {
        // 공원 · 고속도로 · 도로 격자
        drawRoundRect(Color(0xFFD5EAD8), Offset(size.width * 0.02f, size.height * 0.62f), Size(size.width * 0.12f, size.height * 0.15f), CornerRadius(12f))
        drawLine(Color(0xFFF5E6B3), Offset(0f, size.height * 0.40f), Offset(size.width, size.height * 1.02f), 26.dp.toPx())
        listOf(0.35f, 0.70f).forEach { drawLine(Color.White, Offset(size.width * it, 0f), Offset(size.width * it, size.height), 9.dp.toPx()) }
        listOf(0.40f, 0.95f).forEach { drawLine(Color.White, Offset(0f, size.height * it), Offset(size.width, size.height * it), 9.dp.toPx()) }
        MAIN_X.forEach { drawLine(Color.White, Offset(size.width * it, 0f), Offset(size.width * it, size.height), 18.dp.toPx()) }
        MAIN_Y.forEach { drawLine(Color.White, Offset(0f, size.height * it), Offset(size.width, size.height * it), 18.dp.toPx()) }

        // 내 차 → 충전소 경로 (도로를 따라 ㄱ자). 지나온 구간은 그리지 않는다
        val path = Path().apply {
            ahead.forEachIndexed { i, p -> if (i == 0) moveTo(p.x * size.width, p.y * size.height) else lineTo(p.x * size.width, p.y * size.height) }
        }
        drawPath(path, Blue.copy(alpha = 0.25f), style = Stroke(14.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, Blue, style = Stroke(6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    // 오버레이. 세로 화면(지도 폭이 좁음)에서는 핀과 겹치는 장식용 검색칩·지도 버튼을 숨긴다
    val roomy = w >= 480.dp && guide == null
    if (roomy) Row(
        Modifier.padding(18.dp).shadow(6.dp, RoundedCornerShape(50)).clip(RoundedCornerShape(50)).background(Color.White)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.EvStation, null, tint = Blue, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text("가까운 충전소", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink)
    }
    if (roomy) Column(
        Modifier.align(Alignment.TopEnd).padding(18.dp).shadow(6.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)).background(Color.White),
    ) {
        listOf(Icons.Rounded.Add, Icons.Rounded.Remove, Icons.Rounded.MyLocation).forEach {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(it, null, tint = Ink, modifier = Modifier.size(22.dp)) }
        }
    }
    if (guide == null) Row(
        Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp).clip(RoundedCornerShape(50)).background(Ink)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Place, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("성남시 분당구 · 배터리 ${state.battery}% · ${state.rangeKm}km", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }

    // 핀과 내 차는 오버레이(검색칩·지도 버튼) 위에 그린다. 세로 화면처럼 지도가 낮으면 위쪽 핀이 잘리지 않게 위치를 살짝 내린다
    // 길안내 중에는 목적지 핀만 남긴다
    STATIONS.filter { guide == null || it.id == guide.id }.forEach { st ->
        val y = (h * st.mapY - 76.dp).coerceAtLeast(4.dp)
        StationPin(st, st.id == selectedId, Modifier.offset(x = w * st.mapX - 30.dp, y = y).clickable { onSelect(st.id) })
    }
    // 내 차
    Box(Modifier.offset(x = w * carAt.x - 26.dp, y = h * carAt.y - 26.dp).size(52.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(Blue.copy(alpha = 0.18f)))
        Box(Modifier.size(36.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(Navy), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.DirectionsCar, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
    // 길안내 오버레이는 핀·내 차 위에 그린다
    if (guide != null) {
        // 길안내 중: 위 = 목적지, 아래 = 남은 거리·시간·도착 시각 + 안내 종료
        Row(
            Modifier.fillMaxWidth().padding(14.dp).shadow(6.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(Color.White)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("안내 중", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Blue).padding(horizontal = 10.dp, vertical = 4.dp))
            Spacer(Modifier.width(10.dp))
            Text(guide.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink, maxLines = 1)
        }
        val left = 1f - state.navProgress
        val leftMin = kotlin.math.ceil(guide.minutes * left).toInt().coerceAtLeast(1)
        // 도착 시각은 안내를 시작한 시점 기준으로 고정 (시뮬레이션은 시간을 빨리 감으므로 매번 다시 계산하면 당겨진다)
        val eta = remember(guide.id) { LocalTime.now().plusMinutes(guide.minutes.toLong()).format(DateTimeFormatter.ofPattern("HH:mm")) }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(14.dp).clip(RoundedCornerShape(18.dp)).background(Ink)
                .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("%.1fkm · %d분".format(guide.km * left, leftMin), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("도착 $eta", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
            }
            Text("안내 종료", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Red).clickable { onAction(listOf(Action.StopNavigate)) }
                    .padding(horizontal = 18.dp, vertical = 12.dp))
        }
    }
}

/** 내 차 → 충전소 ㄱ자 경로. 지도 비율(0..1) 좌표 */
private fun routeOf(st: Station): List<Offset> {
    val car = Offset(CAR_X, CAR_Y)
    val dst = Offset(st.mapX, st.mapY)
    val horizFirst = MAIN_X.any { abs(it - st.mapX) < 0.01f }
    return listOf(car, if (horizFirst) Offset(dst.x, car.y) else Offset(car.x, dst.y), dst)
}

/** 경로에서 진행률 [p] 지점과, 그 지점부터 남은 경로. 가로·세로 비율이 달라 길이는 실제 크기([w]×[h])로 잰다 */
private fun splitRoute(route: List<Offset>, p: Float, w: Float, h: Float): Pair<Offset, List<Offset>> {
    val seg = route.zipWithNext { a, b -> Offset((b.x - a.x) * w, (b.y - a.y) * h).getDistance() }
    var d = seg.sum() * p.coerceIn(0f, 1f)
    seg.forEachIndexed { i, len ->
        if (d <= len || i == seg.lastIndex) {
            val t = if (len == 0f) 1f else (d / len).coerceIn(0f, 1f)
            val at = route[i] + (route[i + 1] - route[i]) * t
            return at to listOf(at) + route.drop(i + 1)
        }
        d -= len
    }
    return route.last() to listOf(route.last())
}

@Composable
private fun StationPin(st: Station, selected: Boolean, modifier: Modifier) = Column(modifier.width(60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    Text(
        // 핀 폭(60dp)보다 길어도 줄바꿈하지 않고 가운데 기준으로 넘친다 ("7.4k / m" 방지)
        "${st.km}km", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, softWrap = false,
        modifier = Modifier.wrapContentWidth(unbounded = true).clip(RoundedCornerShape(50)).background(if (selected) Blue else Ink).padding(horizontal = 10.dp, vertical = 4.dp),
    )
    Spacer(Modifier.height(4.dp))
    Box(
        Modifier.size(width = 40.dp, height = 46.dp).shadow(6.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp))
            .background(Color.White).border(if (selected) 2.dp else 0.dp, Blue, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Rounded.EvStation, null, tint = if (st.free > 0) Blue else Sub, modifier = Modifier.size(24.dp)) }
}

// ───────────────────────── 우측: 충전 ─────────────────────────

@Composable
internal fun ChargeTab(
    state: CarState,
    selectedId: String,
    onSelect: (String) -> Unit,
    autoRest: Boolean,
    onAutoRest: (Boolean) -> Unit,
    onAction: (List<Action>) -> Unit,
    onAsk: (String) -> Unit,
) {
    val sel = station(selectedId) ?: STATIONS.first()
    if (state.charging) {
        ChargingSession(state, sel, onAction, onAsk)
        return
    }
    @Composable
    fun battery(modifier: Modifier) = Panel(modifier) {
        Text("배터리", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Text(if (state.battery < 40) "충전을 권장해요" else "여유 있어요", color = Sub, fontSize = 14.sp)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BatteryGlyph(state.battery)
            Spacer(Modifier.width(18.dp))
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${state.rangeKm}", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text(" km", fontSize = 16.sp, color = Ink, modifier = Modifier.padding(bottom = 6.dp))
                }
                HorizontalDivider(color = Line, modifier = Modifier.width(140.dp).padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${state.battery}%", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text("  %.1f kWh".format(BATTERY_KWH * state.battery / 100), fontSize = 14.sp, color = Sub, softWrap = false, modifier = Modifier.padding(bottom = 3.dp))
                }
            }
        }
    }
    @Composable
    fun restCard(modifier: Modifier) = Column(
        modifier.shadow(10.dp, RoundedCornerShape(26.dp)).clip(RoundedCornerShape(26.dp)).background(Navy).padding(22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Bedtime, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.weight(1f))
            Switch(
                checked = autoRest, onCheckedChange = onAutoRest,
                colors = SwitchDefaults.colors(checkedTrackColor = Blue, checkedThumbColor = Color.White),
            )
        }
        Spacer(Modifier.weight(1f))
        Text("충전하는 동안, 쉬세요", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text("충전이 시작되면 JARI가 시트를 휴식 모드로 바꾸고, 다 되면 운전 자세로 돌려드려요.", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, lineHeight = 19.sp)
    }
    // 세로 화면은 두 카드를 나란히 두면 배터리 수치가 한 글자씩 줄바꿈된다 → 좁으면 위아래로
    BoxWithConstraints {
        if (maxWidth < 480.dp) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            battery(Modifier.fillMaxWidth())
            restCard(Modifier.fillMaxWidth().height(200.dp))
        } else Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            battery(Modifier.weight(1f).fillMaxHeight())
            restCard(Modifier.weight(1f).fillMaxHeight())
        }
    }

    SectionHeader("가까운 충전소", "${STATIONS.count { it.free > 0 }}곳 이용 가능")
    STATIONS.sortedBy { it.km }.forEach { st -> StationRow(st, st.id == sel.id) { onSelect(st.id) } }
    StationDetail(state, sel, onAction)
}

@Composable
private fun StationRow(st: Station, selected: Boolean, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(22.dp), spotColor = Color(0x22000000)).clip(RoundedCornerShape(22.dp))
        .background(CardBg).border(if (selected) BorderStroke(2.dp, Blue) else BorderStroke(0.dp, Color.Transparent), RoundedCornerShape(22.dp))
        .clickable(onClick = onClick).padding(16.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Box(Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(Navy), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.EvStation, null, tint = Color.White, modifier = Modifier.size(26.dp))
            Text(if (st.name.startsWith("E-pit")) "E-pit" else "EV", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
    Spacer(Modifier.width(16.dp))
    Column(Modifier.weight(1f)) {
        Text(st.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Text("${won(st.won)}/kWh  •  ${st.free}/${st.total}대", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (st.free > 0) Blue else Red)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Star, null, tint = Color(0xFFFFB020), modifier = Modifier.size(15.dp))
            Text(" ${st.rating} · ${st.minutes}분 거리", fontSize = 14.sp, color = Sub)
        }
    }
    Text("${st.km}km", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
}

/**
 * 충전소 상세. 차량용 충전 화면(Tesla·Rivian·Google 지도)처럼 빈 충전기 → 거리·속도 → 요금 → 위치 순으로 보여준다.
 * 내 차 배터리는 위 배터리 카드에 있으니 여기선 뺀다. 색은 초록 = 사용 가능, 빨강 = 만석에만 쓴다.
 */
@Composable
private fun StationDetail(state: CarState, st: Station, onAction: (List<Action>) -> Unit) = Panel {
    val ok = st.free > 0
    val status = if (ok) Green else Red
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Navy), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.EvStation, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(st.name, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Text("${st.km}km · ${st.minutes}분 · ${if (st.kw >= 200) "초급속" else "급속"} ${st.kw}kW", fontSize = 14.sp, color = Sub)
        }
    }
    Spacer(Modifier.height(20.dp))

    // 빈 충전기: 숫자 + 충전기별 칸(Tesla 충전소 배치도를 줄인 형태)
    Row(verticalAlignment = Alignment.Bottom) {
        Text(if (ok) "${st.free}대" else "만석", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = status)
        Text(
            if (ok) "  비어 있음 · 전체 ${st.total}대" else "  전체 ${st.total}대 사용 중",
            fontSize = 15.sp, color = Sub, modifier = Modifier.padding(bottom = 5.dp),
        )
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(st.total) { i ->
            Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(50)).background(if (i < st.free) Green else Line))
        }
    }
    HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 18.dp))

    // 요금 + 충전 한도까지 예상 비용·시간
    Row(verticalAlignment = Alignment.Bottom) {
        Text(won(st.won), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text(" /kWh", fontSize = 14.sp, color = Sub, softWrap = false, modifier = Modifier.padding(bottom = 4.dp))
    }
    val need = (state.chargeLimit - state.battery).coerceAtLeast(0) / 100.0 * BATTERY_KWH
    val minutes = kotlin.math.ceil(need / minOf(st.kw.toDouble(), CAR_AVG_KW) * 60).toInt()
    Text(
        if (need <= 0.0) "이미 충전 한도(${state.chargeLimit}%)까지 차 있어요"
        else "${state.chargeLimit}%까지 약 ${won(need * st.won)} · 약 ${minutes}분",
        fontSize = 14.sp, color = Sub,
    )
    Spacer(Modifier.height(14.dp))
    InfoLine(Icons.Rounded.Place, st.address)
    InfoLine(Icons.Rounded.LocalParking, "주차 ${st.parking} · ★ ${st.rating}")
    Spacer(Modifier.height(20.dp))

    // 차 화면은 주요 동작을 크게, 한 번에: 길안내가 주 버튼. 충전 시작은 도착 후에 쓰는 보조 버튼
    val guiding = state.navTo == st.id
    Row(
        Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(50)).background(if (guiding) Ink else Blue)
            .clickable { onAction(listOf(if (guiding) Action.StopNavigate else Action.Navigate(st.id))) },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (guiding) Icons.Rounded.Close else Icons.Rounded.Navigation, null, tint = Color.White, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(if (guiding) "안내 종료" else "길안내 시작 · ${st.minutes}분", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
    Spacer(Modifier.height(10.dp))
    Box(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(50)).background(if (ok) BlueSoft else Bg)
            .then(if (ok) Modifier.clickable { onAction(listOf(Action.Charge(true))) } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (ok) "도착했다면 충전 시작" else "빈 충전기가 없어요", color = if (ok) Blue else Sub, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun InfoLine(icon: ImageVector, text: String) = Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, null, tint = Sub, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(10.dp))
    Text(text, fontSize = 15.sp, color = Ink)
}

@Composable
private fun ChargingSession(state: CarState, st: Station, onAction: (List<Action>) -> Unit, onAsk: (String) -> Unit) {
    var fast by remember { mutableStateOf(true) }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Place)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(st.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                Text("Bay 04 · CCS1 · ${st.kw}kW", fontSize = 14.sp, color = Sub)
            }
            Chip("충전 중", active = true)
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Bg).padding(4.dp)) {
            listOf(false to "에코 모드", true to "급속 충전").forEach { (isFast, label) ->
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (fast == isFast) Blue else Color.Transparent)
                        .clickable { fast = isFast }.padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (isFast) Icons.Rounded.Bolt else Icons.Rounded.EnergySavingsLeaf, null, tint = if (fast == isFast) Color.White else Sub, modifier = Modifier.size(18.dp))
                    Text("  $label", color = if (fast == isFast) Color.White else Sub, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        ChargingCar(state.battery, Modifier.fillMaxWidth().height(190.dp))
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${state.battery}%", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text("충전 레벨", fontSize = 14.sp, color = Sub)
            }
            VerticalDivider(color = Line)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${state.rangeKm}km", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text("주행 가능 거리", fontSize = 14.sp, color = Sub)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Bg)
                .clickable {
                    val next = listOf(70, 80, 90, 100).firstOrNull { it > state.chargeLimit } ?: 70
                    onAction(listOf(Action.ChargeLimit(next)))
                }
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("충전 한도: ", fontSize = 15.sp, color = Sub)
            Text("${state.chargeLimit}%", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Blue, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.ExpandMore, null, tint = Sub)
        }
    }
    val added = (state.battery - state.chargeStart).coerceAtLeast(0) / 100.0 * BATTERY_KWH
    Panel {
        CostRow(Icons.Rounded.BatteryChargingFull, "충전량", "%.1f kWh".format(added))
        CostRow(Icons.Rounded.Payments, "단가", "${won(st.won)}/kWh")
        HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 6.dp))
        CostRow(Icons.Rounded.Receipt, "현재 요금", won(added * st.won), highlight = true)
    }
    // 타일(1/3 폭)은 부제가 두 줄로 늘어나 버튼과 높이가 어긋났다 → 1:1로 나누고 높이를 맞춘다
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        QuickTile("휴식 모드", "충전 중 시트 눕히기", Icons.Rounded.Bedtime, false) { onAsk("충전하는 동안 휴식 모드") }
        Box(
            Modifier.weight(1f).fillMaxHeight().shadow(12.dp, RoundedCornerShape(24.dp), spotColor = Blue).clip(RoundedCornerShape(24.dp))
                .background(Blue).clickable { onAction(listOf(Action.Charge(false))) },
            contentAlignment = Alignment.Center,
        ) { Text("충전 중지", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun CostRow(icon: ImageVector, label: String, value: String, highlight: Boolean = false) = Row(
    Modifier.fillMaxWidth().padding(vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Icon(icon, null, tint = if (highlight) Blue else Sub, modifier = Modifier.size(20.dp))
    Spacer(Modifier.width(12.dp))
    Text(label, fontSize = 16.sp, color = if (highlight) Ink else Sub, modifier = Modifier.weight(1f))
    Text(value, fontSize = if (highlight) 22.sp else 16.sp, fontWeight = FontWeight.SemiBold, color = if (highlight) Blue else Ink)
}

/** 세로 배터리 아이콘 (레퍼런스의 Battery 카드) */
@Composable
private fun BatteryGlyph(percent: Int) = Canvas(Modifier.size(width = 54.dp, height = 96.dp)) {
    val cap = Size(size.width * 0.36f, 6.dp.toPx())
    drawRoundRect(Line, Offset((size.width - cap.width) / 2, 0f), cap, CornerRadius(4f))
    val body = Size(size.width, size.height - cap.height - 3.dp.toPx())
    val top = cap.height + 3.dp.toPx()
    drawRoundRect(Color(0xFFF1F4F8), Offset(0f, top), body, CornerRadius(14.dp.toPx()))
    val fillH = body.height * percent / 100f
    clipRect(top = top + body.height - fillH) {
        drawRoundRect(Blue, Offset(0f, top), body, CornerRadius(14.dp.toPx()))
    }
}

/** 위에서 본 차량에 충전량만큼 파랗게 채움 (레퍼런스의 Energy Life) */
@Composable
private fun ChargingCar(percent: Int, modifier: Modifier) {
    val tm = rememberTextMeasurer()
    Canvas(modifier) {
        val bw = size.width * 0.62f
        val bh = minOf(size.height * 0.62f, bw * 0.45f)
        val left = (size.width - bw) / 2
        val top = (size.height - bh) / 2
        val body = Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(left, top, left + bw, top + bh, CornerRadius(bh * 0.45f)))
        }
        drawPath(body, Color(0x14000000), style = Stroke(10.dp.toPx()))
        drawPath(body, Color.White)
        // 앞/뒷유리, 지붕
        drawRoundRect(Color(0xFFCFD9E6), Offset(left + bw * 0.24f, top + bh * 0.14f), Size(bw * 0.16f, bh * 0.72f), CornerRadius(18f))
        drawRoundRect(Color(0xFFE6ECF3), Offset(left + bw * 0.42f, top + bh * 0.18f), Size(bw * 0.26f, bh * 0.64f), CornerRadius(12f))
        drawRoundRect(Color(0xFFCFD9E6), Offset(left + bw * 0.70f, top + bh * 0.18f), Size(bw * 0.10f, bh * 0.64f), CornerRadius(14f))
        // 충전량 (뒤쪽부터 채움)
        val fillW = bw * percent / 100f
        clipPath(body) {
            clipRect(left = left + bw - fillW) {
                drawRect(Blue.copy(alpha = 0.85f), Offset(left, top), Size(bw, bh))
            }
        }
        drawPath(body, Line, style = Stroke(1.5.dp.toPx()))
        // 충전 케이블
        val plug = Offset(left + bw, top + bh * 0.72f)
        val cable = Path().apply {
            moveTo(plug.x, plug.y)
            cubicTo(plug.x + 40.dp.toPx(), plug.y + 10.dp.toPx(), plug.x + 20.dp.toPx(), size.height, size.width * 0.95f, size.height * 0.98f)
        }
        drawPath(cable, Ink, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(Blue, 6.dp.toPx(), plug)
        // 퍼센트
        val inside = fillW > bw * 0.2f
        val m = tm.measure("$percent%", TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, color = if (inside) Color.White else Ink))
        val at = Offset(if (inside) left + bw - fillW / 2 else left + bw * 0.88f, top + bh / 2)
        drawText(m, topLeft = Offset(at.x - m.size.width / 2f, at.y - m.size.height / 2f))
    }
}
