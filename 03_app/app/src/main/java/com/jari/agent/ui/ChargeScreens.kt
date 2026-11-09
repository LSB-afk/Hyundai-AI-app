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
import com.jari.agent.CAR_X
import com.jari.agent.CAR_Y
import com.jari.agent.CarState
import com.jari.agent.STATIONS
import com.jari.agent.Station
import com.jari.agent.station
import java.text.NumberFormat
import kotlin.math.abs

private val MAIN_X = listOf(0.18f, 0.52f, 0.86f)
private val MAIN_Y = listOf(0.22f, 0.58f, 0.80f)
private val MapBlock = Color(0xFFE4E7EC)
private val Green = Color(0xFF1FA463)
private val Red = Color(0xFFE5484D)

private fun won(v: Number) = NumberFormat.getInstance().format(v.toLong()) + "원"

// ───────────────────────── 좌측: 지도 ─────────────────────────

@Composable
internal fun MapCard(state: CarState, selectedId: String, onSelect: (String) -> Unit, modifier: Modifier) = BoxWithConstraints(
    modifier.shadow(10.dp, RoundedCornerShape(26.dp), spotColor = Color(0x22000000)).clip(RoundedCornerShape(26.dp)).background(MapBlock),
) {
    val w = maxWidth
    val h = maxHeight
    val sel = station(selectedId) ?: STATIONS.first()
    Canvas(Modifier.fillMaxSize()) {
        // 공원 · 고속도로 · 도로 격자
        drawRoundRect(Color(0xFFD5EAD8), Offset(size.width * 0.02f, size.height * 0.62f), Size(size.width * 0.12f, size.height * 0.15f), CornerRadius(12f))
        drawLine(Color(0xFFF5E6B3), Offset(0f, size.height * 0.40f), Offset(size.width, size.height * 1.02f), 26.dp.toPx())
        listOf(0.35f, 0.70f).forEach { drawLine(Color.White, Offset(size.width * it, 0f), Offset(size.width * it, size.height), 9.dp.toPx()) }
        listOf(0.40f, 0.95f).forEach { drawLine(Color.White, Offset(0f, size.height * it), Offset(size.width, size.height * it), 9.dp.toPx()) }
        MAIN_X.forEach { drawLine(Color.White, Offset(size.width * it, 0f), Offset(size.width * it, size.height), 18.dp.toPx()) }
        MAIN_Y.forEach { drawLine(Color.White, Offset(0f, size.height * it), Offset(size.width, size.height * it), 18.dp.toPx()) }

        // 내 차 → 선택한 충전소 경로 (도로를 따라 ㄱ자)
        val car = Offset(size.width * CAR_X, size.height * CAR_Y)
        val dst = Offset(size.width * sel.mapX, size.height * sel.mapY)
        val horizFirst = MAIN_X.any { abs(it - sel.mapX) < 0.01f }
        val corner = if (horizFirst) Offset(dst.x, car.y) else Offset(car.x, dst.y)
        val route = Path().apply { moveTo(car.x, car.y); lineTo(corner.x, corner.y); lineTo(dst.x, dst.y) }
        drawPath(route, Blue.copy(alpha = 0.25f), style = Stroke(14.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(route, Blue, style = Stroke(6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    STATIONS.forEach { st ->
        StationPin(st, st.id == selectedId, Modifier.offset(x = w * st.mapX - 30.dp, y = h * st.mapY - 76.dp).clickable { onSelect(st.id) })
    }
    // 내 차
    Box(Modifier.offset(x = w * CAR_X - 26.dp, y = h * CAR_Y - 26.dp).size(52.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(Blue.copy(alpha = 0.18f)))
        Box(Modifier.size(36.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(Navy), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.DirectionsCar, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }

    // 오버레이
    Row(
        Modifier.padding(18.dp).shadow(6.dp, RoundedCornerShape(50)).clip(RoundedCornerShape(50)).background(Color.White)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.EvStation, null, tint = Blue, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text("가까운 충전소", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink)
    }
    Column(
        Modifier.align(Alignment.TopEnd).padding(18.dp).shadow(6.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)).background(Color.White),
    ) {
        listOf(Icons.Rounded.Add, Icons.Rounded.Remove, Icons.Rounded.MyLocation).forEach {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(it, null, tint = Ink, modifier = Modifier.size(22.dp)) }
        }
    }
    Row(
        Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp).clip(RoundedCornerShape(50)).background(Ink)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Place, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("성남시 분당구 · 배터리 ${state.battery}% · ${state.rangeKm}km", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StationPin(st: Station, selected: Boolean, modifier: Modifier) = Column(modifier.width(60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    Text(
        "${st.km}km", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) Blue else Ink).padding(horizontal = 10.dp, vertical = 4.dp),
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
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(Modifier.weight(1f).fillMaxHeight()) {
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
                        Text("  %.1f kWh".format(BATTERY_KWH * state.battery / 100), fontSize = 14.sp, color = Sub, modifier = Modifier.padding(bottom = 3.dp))
                    }
                }
            }
        }
        Column(
            Modifier.weight(1f).fillMaxHeight().shadow(10.dp, RoundedCornerShape(26.dp)).clip(RoundedCornerShape(26.dp)).background(Navy).padding(22.dp),
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

@Composable
private fun StationDetail(state: CarState, st: Station, onAction: (List<Action>) -> Unit) = Column(
    Modifier.fillMaxWidth().shadow(10.dp, RoundedCornerShape(26.dp), spotColor = Color(0x22000000)).clip(RoundedCornerShape(26.dp)).background(Ink),
) {
    Column(Modifier.padding(22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(Blue), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Bolt, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(st.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text("⚡ ${state.battery}%", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("${state.rangeKm}km 주행 가능", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            DarkStat("${st.km} km", "거리")
            VerticalDivider(color = Color.White.copy(alpha = 0.15f), modifier = Modifier.padding(horizontal = 20.dp))
            DarkStat("${st.minutes}분", "소요 시간")
        }
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)).background(CardBg).padding(22.dp)) {
        Text("개요", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Place)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(st.address, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Ink)
                Text("충전기 ${st.total}대 · 평점 ${st.rating}", fontSize = 14.sp, color = Sub)
            }
            val ok = st.free > 0
            Row(
                Modifier.clip(RoundedCornerShape(50)).background((if (ok) Green else Red).copy(alpha = 0.1f)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (ok) Green else Red))
                Text(if (ok) "  사용 가능" else "  혼잡", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (ok) Green else Red)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoTile(Icons.Rounded.Bolt, if (st.kw >= 200) "초급속" else "급속", "${st.kw} kW")
            InfoTile(Icons.Rounded.EvStation, "${st.free}/${st.total}대", "이용 가능")
            InfoTile(Icons.Rounded.LocalParking, "주차", st.parking)
        }
        HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(won(st.won), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text(" /kWh", fontSize = 14.sp, color = Sub, modifier = Modifier.padding(bottom = 4.dp))
                }
                val toLimit = (state.chargeLimit - state.battery).coerceAtLeast(0) / 100.0 * BATTERY_KWH
                Text("≈ ${state.chargeLimit}%까지 ${won(toLimit * st.won)}", fontSize = 14.sp, color = Sub)
            }
            Text(
                "충전 시작", color = Blue, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(BlueSoft).clickable { onAction(listOf(Action.Charge(true))) }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            )
            Spacer(Modifier.width(10.dp))
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Blue).clickable { onAction(listOf(Action.Navigate(st.id))) }
                    .padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (state.navTo == st.id) "안내 중" else "길안내", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(12.dp))
                Box(Modifier.size(38.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Navigation, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
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
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        QuickTile("휴식 모드", "충전 중 시트 눕히기", Icons.Rounded.Bedtime, false) { onAsk("충전하는 동안 휴식 모드") }
        Box(
            Modifier.weight(2f).height(132.dp).shadow(12.dp, RoundedCornerShape(24.dp), spotColor = Blue).clip(RoundedCornerShape(24.dp))
                .background(Blue).clickable { onAction(listOf(Action.Charge(false))) },
            contentAlignment = Alignment.Center,
        ) { Text("충전 중지", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun RowScope.InfoTile(icon: ImageVector, title: String, sub: String) = Column(
    Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(Bg).padding(16.dp),
) {
    Icon(icon, null, tint = Ink, modifier = Modifier.size(22.dp))
    Spacer(Modifier.height(18.dp))
    Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink, maxLines = 1)
    Text(sub, fontSize = 14.sp, color = Sub, maxLines = 1)
}

@Composable
private fun DarkStat(value: String, label: String) = Column {
    Text(value, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    Text(label, color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
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
