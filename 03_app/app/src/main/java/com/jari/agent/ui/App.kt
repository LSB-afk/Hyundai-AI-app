package com.jari.agent.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jari.agent.Action
import com.jari.agent.AgentUi
import com.jari.agent.Axis
import com.jari.agent.CarState
import com.jari.agent.STATIONS
import com.jari.agent.Sim
import com.jari.agent.SeatId
import com.jari.agent.WindowId
import kotlin.math.roundToInt

private val SUGGESTIONS = listOf(
    "허리가 아파", "조수석 좀 눕혀줘", "너무 졸려", "트렁크 열어줘", "충전소 찾아줘", "창문 전부 닫아줘",
    "선루프 반만 열어", "더워", "뒷좌석 따뜻하게", "내릴게", "원래대로",
)
private val SIDE = listOf(WindowId.FL, WindowId.FR, WindowId.RL, WindowId.RR)

@Composable
fun App(
    state: CarState,
    ui: AgentUi,
    onAsk: (String) -> Unit,
    onMic: () -> Unit,
    onAction: (List<Action>) -> Unit,
    onToggleDriving: () -> Unit,
    initialTab: Int = 0,
    onSim: (Sim) -> Unit = {},
) {
    // Pleos 앱 영역은 높이 1210px(mdpi) 고정, 폭은 1개 window(portrait ≈840) 또는 2개(landscape ≈1680).
    // 높이 920dp 기준으로 키워 두 모드의 글자 크기를 같게 유지하고, 폭으로 레이아웃을 고른다.
    // Android 15(targetSdk 35)는 앱이 상태바 뒤까지 그려진다 → 상태바·내비바 영역을 비켜야 상단 칩이 눌린다
    BoxWithConstraints(Modifier.fillMaxSize().background(Bg).safeDrawingPadding()) {
        val d = LocalDensity.current
        val scale = (maxHeight.value / 920f).coerceIn(1f, 2f)
        val wide = maxWidth.value / scale >= 1000f
        CompositionLocalProvider(LocalDensity provides Density(d.density * scale, d.fontScale)) {
            AppContent(state, ui, onAsk, onMic, onAction, onToggleDriving, onSim, initialTab, wide)
        }
    }
}

@Composable
private fun AppContent(
    state: CarState,
    ui: AgentUi,
    onAsk: (String) -> Unit,
    onMic: () -> Unit,
    onAction: (List<Action>) -> Unit,
    onToggleDriving: () -> Unit,
    onSim: (Sim) -> Unit,
    initialTab: Int,
    wide: Boolean,
) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    var simOpen by rememberSaveable { mutableStateOf(false) }
    val onSimChip = { if (!state.live) simOpen = !simOpen }
    var selected by rememberSaveable { mutableStateOf(SeatId.DRIVER) }
    var stationId by rememberSaveable { mutableStateOf(state.navTo ?: STATIONS.first().id) }
    var autoRest by rememberSaveable { mutableStateOf(true) }
    // 에이전트가 길안내를 시작하면 충전 탭으로
    LaunchedEffect(state.navTo) {
        state.navTo?.let { stationId = it; tab = 3 }
    }
    // 충전 시작/종료에 맞춰 휴식 모드 ↔ 운전 자세 자동 전환
    var wasCharging by remember { mutableStateOf(state.charging) }
    LaunchedEffect(state.charging) {
        if (autoRest && state.charging != wasCharging) onAsk(if (state.charging) "충전하는 동안 휴식 모드" else "운전자세")
        wasCharging = state.charging
    }
    val onSeatTap: (SeatId) -> Unit = { selected = it; tab = 1 }
    @Composable
    fun ColumnScope.tabContent() = when (tab) {
        0 -> HomeTab(state, ui, wide, onAction, onAsk, onToggleDriving)
        1 -> SeatsTab(state, selected, wide, { selected = it }, onAction, onAsk)
        2 -> WindowsTab(state, onAction, onAsk)
        else -> ChargeTab(state, stationId, { stationId = it }, autoRest, { autoRest = it }, onAction, onAsk)
    }
    Box(Modifier.fillMaxSize().background(Bg)) {
        if (wide) {
            // landscape: 차량 시각화(좌) + 에이전트·제어(우)
            Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                val left = Modifier.weight(0.42f).fillMaxHeight()
                if (tab == 3) MapCard(state, stationId, { stationId = it }, onAction, left)
                else HeroCard(state, selected, onSeatTap, onToggleDriving, simOpen, onSimChip, left)
                Column(
                    Modifier.weight(0.58f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (simOpen) SimPanel(state, onToggleDriving, onSim) { simOpen = false }
                    AgentCard(ui, onAsk, onMic, typing = !state.driving)
                    tabContent()
                    Spacer(Modifier.height(96.dp))
                }
            }
        } else {
            // portrait: 한 window. 에이전트 → 탭별 핵심 시각화 → 제어 순으로 한 줄
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                TopBar(state, onToggleDriving, simOpen, onSimChip)
                if (simOpen) SimPanel(state, onToggleDriving, onSim) { simOpen = false }
                AgentCard(ui, onAsk, onMic, typing = !state.driving)
                val anims = rememberSeatAnims(state)
                when (tab) {
                    0, 2 -> Panel { Cabin3D(state, anims, selected, onSeatTap, Modifier.fillMaxWidth().height(440.dp)) }
                    1 -> Panel {
                        Cabin3D(state, anims, selected, onSeatTap, Modifier.fillMaxWidth().height(360.dp), side = true)
                        SeatAxisChips(selected.label, anims.getValue(selected), Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                    // 320dp에선 핀이 내 차·하단 배터리 표시와 겹쳤다
                    else -> MapCard(state, stationId, { stationId = it }, onAction, Modifier.fillMaxWidth().height(400.dp))
                }
                tabContent()
                Spacer(Modifier.height(96.dp))
            }
        }
        NavPill(tab, { tab = it }, ui.listening, onMic, Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp))
    }
}

// ───────────────────────── 좌측 히어로 ─────────────────────────

@Composable
private fun HeroCard(
    state: CarState, selected: SeatId, onSeatTap: (SeatId) -> Unit, onToggleDriving: () -> Unit,
    simOpen: Boolean, onSimChip: () -> Unit, modifier: Modifier,
) =
    Panel(modifier) {
        TopBar(state, onToggleDriving, simOpen, onSimChip)
        val anims = rememberSeatAnims(state)
        Cabin3D(state, anims, selected, onSeatTap, Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp))
        SeatAxisChips(selected.label, anims.getValue(selected), Modifier.fillMaxWidth().padding(bottom = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Legend(Warm, "열선"); Legend(Cool, "통풍"); Legend(Navy, "탑승"); Legend(Blue, "선택 좌석")
        }
    }

@Composable
private fun TopBar(state: CarState, onToggleDriving: () -> Unit, simOpen: Boolean, onSimChip: () -> Unit) = Column {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.AutoAwesome, null, tint = Blue, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(8.dp))
        Text("JARI", fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp, color = Navy)
        Spacer(Modifier.weight(1f))
        Chip(if (state.driving) "D  주행 중" else "P  정차", active = state.driving, onClick = onToggleDriving)
        Spacer(Modifier.width(8.dp))
        // 시뮬레이션 칩을 누르면 기어·배터리·탑승 좌석을 바꾸는 패널이 열린다 (실차 연결 시에는 표시만)
        Chip(if (state.live) "Pleos 연결" else "시뮬레이션", active = simOpen, onClick = if (state.live) null else onSimChip)
    }
    Text("AI 좌석 · 창문 에이전트", color = Sub, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
}

// ───────────────────────── 시뮬레이션 패널 ─────────────────────────

/** Pleos 차량 서비스가 없을 때 차량 신호(기어·배터리·탑승 좌석)를 직접 바꿔 보는 데모용 패널 */
@Composable
private fun SimPanel(state: CarState, onToggleDriving: () -> Unit, onSim: (Sim) -> Unit, onClose: () -> Unit) = Panel {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("시뮬레이션", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f))
        Text("닫기", color = Blue, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clickable(onClick = onClose).padding(4.dp))
    }
    Text("차량 서비스가 없어 앱이 차량 신호를 흉내 내요. 값을 바꾸면 JARI가 실제 차처럼 반응해요.", color = Sub, fontSize = 14.sp)

    SimLabel("기어")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Bg).padding(4.dp)) {
        listOf(false to "P  정차", true to "D  주행").forEach { (driving, label) ->
            val on = state.driving == driving
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (on) Blue else Color.Transparent)
                    .clickable { if (!on) onToggleDriving() }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, color = if (on) Color.White else Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        }
    }

    var drag by remember { mutableStateOf<Float?>(null) }
    val battery = drag?.roundToInt() ?: state.battery
    SimLabel("배터리  $battery%")
    Slider(
        value = drag ?: state.battery.toFloat(),
        onValueChange = { drag = it },
        onValueChangeFinished = { drag?.let { onSim(Sim.Battery(it.roundToInt())) }; drag = null },
        valueRange = 0f..100f,
        colors = SliderDefaults.colors(thumbColor = Blue, activeTrackColor = Blue, inactiveTrackColor = Line),
    )
    Text("주행 중에 20% 이하로 내리면 JARI가 먼저 충전소를 안내해요.", color = Sub, fontSize = 13.sp)

    SimLabel("탑승 좌석")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(SeatId.DRIVER to "운전석", SeatId.PASSENGER to "조수석", SeatId.REAR_LEFT to "뒤 왼쪽", SeatId.REAR_RIGHT to "뒤 오른쪽").forEach { (id, name) ->
            val on = state.seat(id).occupied
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (on) Navy else Bg)
                    .clickable { onSim(Sim.Occupied(id, !on)) }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text(name, color = if (on) Color.White else Sub, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
        }
    }
}

@Composable
private fun SimLabel(text: String) =
    Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))

// ───────────────────────── AI 에이전트 카드 ─────────────────────────

@Composable
private fun AgentCard(ui: AgentUi, onAsk: (String) -> Unit, onMic: () -> Unit, typing: Boolean) = Panel {
    Row(verticalAlignment = Alignment.CenterVertically) {
        MicOrb(ui.listening, onMic, 68.dp)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    ui.listening -> "듣고 있어요…  ${ui.heard}"
                    ui.heard.isNotBlank() -> "“${ui.heard}”"
                    else -> "JARI 에이전트"
                },
                color = if (ui.listening) Blue else Sub, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            // 어절 단위로 끊고 줄 길이를 고르게(마지막 줄에 "요."만 남지 않게). 본문 기본 자간(0.5sp)은 빼서 두 줄에 들어가게
            Text(
                sentenceLines(ui.reply), color = Ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp, // 줄 수 제한 없음: 긴 답변이 문장 중간에서 잘리지 않게
                style = TextStyle(
                    localeList = LocaleList("ko-KR"),
                    lineBreak = LineBreak(LineBreak.Strategy.Balanced, LineBreak.Strictness.Normal, LineBreak.WordBreak.Phrase),
                ),
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    if (typing) {
        var text by remember { mutableStateOf("") }
        val send = { if (text.isNotBlank()) { onAsk(text); text = "" } }
        // 입력창과 보내기 버튼을 같은 높이(56dp)로. 버튼은 종이비행기 아이콘만 (글자를 쓰면 진한 파랑)
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(fontSize = 15.sp, color = Ink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(16.dp)).background(Bg).padding(horizontal = 18.dp),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) Text("직접 입력 · 예) 뒷좌석 따뜻하게", color = Sub, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                },
            )
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(if (text.isBlank()) BlueSoft else Blue).clickable(onClick = send),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Rounded.Send, "보내기", tint = if (text.isBlank()) Blue else Color.White, modifier = Modifier.size(22.dp))
            }
        }
    } else {
        Text("주행 중에는 키보드 입력이 제한돼요. 마이크를 누르거나 아래 추천 문장을 눌러 주세요.", color = Sub, fontSize = 14.sp)
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SUGGESTIONS.forEach { Chip(it, active = false) { onAsk(it) } }
    }
}

@Composable
internal fun MicOrb(listening: Boolean, onClick: () -> Unit, size: Dp) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val s by pulse.animateFloat(1f, 1.18f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "s")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (listening) Box(Modifier.size(size).scale(s).clip(CircleShape).background(Blue.copy(alpha = 0.18f)))
        Box(
            Modifier.size(size * 0.86f).clip(CircleShape)
                .background(Brush.linearGradient(listOf(Blue, Navy)))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (listening) Icons.Rounded.GraphicEq else Icons.Rounded.Mic, "말하기", tint = Color.White, modifier = Modifier.size(size * 0.4f))
        }
    }
}

// ───────────────────────── 홈 ─────────────────────────

@Composable
private fun HomeTab(state: CarState, ui: AgentUi, wide: Boolean, onAction: (List<Action>) -> Unit, onAsk: (String) -> Unit, onToggleDriving: () -> Unit) {
    SectionHeader("빠른 제어", "전체 초기화") { onAsk("모든 좌석 원래대로") }
    val d = state.seat(SeatId.DRIVER)
    val open = SIDE.count { state.windows.getValue(it) > 0 }
    val roof = state.windows.getValue(WindowId.ROOF)
    TileRows(
        wide,
        tiles = listOf(
            { QuickTile("운전석 열선", level(d.heat), Icons.Rounded.LocalFireDepartment, d.heat > 0) {
                onAction(listOf(Action.Heat(SeatId.DRIVER, (d.heat + 1) % 4)))
            } },
            { QuickTile("운전석 통풍", level(d.vent), Icons.Rounded.AcUnit, d.vent > 0) {
                onAction(listOf(Action.Vent(SeatId.DRIVER, (d.vent + 1) % 4)))
            } },
            { QuickTile("창문", if (open == 0) "모두 닫힘" else "${open}개 열림", Icons.Rounded.Window, open > 0) {
                onAction(SIDE.map { Action.Window(it, if (open > 0) 0 else 20) })
            } },
            { QuickTile("선루프", if (roof == 0) "닫힘" else "$roof% 열림", Icons.Rounded.WbSunny, roof > 0) {
                onAction(listOf(Action.Window(WindowId.ROOF, if (roof > 0) 0 else 100)))
            } },
        ),
    )
    @Composable
    fun RowScope.statusPanels() {
        Panel(Modifier.weight(1f).fillMaxHeight(), onClick = onToggleDriving) {
            IconBadge(Icons.Rounded.Speed)
            Spacer(Modifier.height(12.dp))
            Text(if (state.driving) "D" else "P", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text(if (state.driving) "주행 중\n안전 제한 적용" else "정차 중\n모든 조절 가능", color = Sub, fontSize = 14.sp)
        }
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            IconBadge(Icons.Rounded.Groups)
            Spacer(Modifier.height(12.dp))
            // 큰 글자에 한글("명")이 섞이면 대체 폰트 때문에 줄이 높아져 옆 카드 "P"와 높이가 어긋난다 → 숫자만 크게, 단위는 기준선 맞춤
            Row {
                Text("${state.seats.values.count { it.occupied }}", Modifier.alignByBaseline(), fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text("명", Modifier.alignByBaseline().padding(start = 4.dp), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
            }
            Text("탑승 인원\n시트 센서", color = Sub, fontSize = 14.sp)
        }
    }
    @Composable
    fun recentPanel(modifier: Modifier) = Panel(modifier) {
        Text("최근 명령", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Spacer(Modifier.height(8.dp))
        if (ui.history.isEmpty()) Text("아직 명령이 없어요. 마이크를 눌러 말해 보세요.", color = Sub, fontSize = 14.sp)
        ui.history.take(3).forEach { (q, a) ->
            Text("“$q”", color = Blue, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(sentenceLines(a), color = Ink, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
        }
    }
    // portrait는 폭이 좁아 세 칸이면 글자가 한 자씩 줄바꿈된다 → 상태 카드 2개 + 최근 명령은 아래 한 줄
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        statusPanels()
        if (wide) recentPanel(Modifier.weight(1.5f).fillMaxHeight())
    }
    if (!wide) recentPanel(Modifier.fillMaxWidth())
    SectionHeader("차량")
    ExteriorPanel(state, onAction)
}

// ───────────────────────── 좌석 ─────────────────────────

@Composable
private fun SeatsTab(state: CarState, selected: SeatId, wide: Boolean, onSelect: (SeatId) -> Unit, onAction0: (List<Action>) -> Unit, onAsk0: (String) -> Unit) {
    val locked = state.driving
    val onAction: (List<Action>) -> Unit = { if (!locked) onAction0(it) }
    val onAsk: (String) -> Unit = { if (!locked) onAsk0(it) }
    if (locked) Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Navy).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Mic, null, tint = Color.White, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Text("주행 중에는 시트 화면 조작이 제한돼요. 음성으로 말씀해 주세요.", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SeatId.entries.forEach { Chip(it.label, active = it == selected, big = true) { onSelect(it) } }
    }
    val s = state.seat(selected)
    val dim = if (locked) Modifier.alpha(0.4f) else Modifier
    Panel(dim) {
        Axis.entries.forEachIndexed { i, axis ->
            if (i > 0) HorizontalDivider(color = Line)
            val v = s.pos.getValue(axis)
            SettingRow(AXIS_ICON.getValue(axis), axis.label, AXIS_HINT.getValue(axis)) {
                Stepper(
                    "$v%",
                    { onAction(listOf(Action.Seat(selected, axis, (v - 10).coerceAtLeast(0)))) },
                    { onAction(listOf(Action.Seat(selected, axis, (v + 10).coerceAtMost(100)))) },
                )
            }
        }
        HorizontalDivider(color = Line)
        SettingRow(Icons.Rounded.LocalFireDepartment, "열선", "0~3단") {
            Stepper(level(s.heat), { onAction(listOf(Action.Heat(selected, (s.heat - 1).coerceAtLeast(0)))) },
                { onAction(listOf(Action.Heat(selected, (s.heat + 1).coerceAtMost(3)))) })
        }
        HorizontalDivider(color = Line)
        SettingRow(Icons.Rounded.AcUnit, "통풍", "0~3단") {
            Stepper(level(s.vent), { onAction(listOf(Action.Vent(selected, (s.vent - 1).coerceAtLeast(0)))) },
                { onAction(listOf(Action.Vent(selected, (s.vent + 1).coerceAtMost(3)))) })
        }
    }
    SectionHeader("추천 자세")
    TileRows(
        wide, dim,
        listOf(
            { QuickTile("휴식 모드", "눕히고 뒤로", Icons.Rounded.Bedtime, false) { onAsk("${selected.label} 휴식 모드") } },
            { QuickTile("운전 자세", "바른 자세", Icons.Rounded.SelfImprovement, false) { onAsk("${selected.label} 운전자세") } },
            { QuickTile("승하차", "뒤로 · 낮게", Icons.AutoMirrored.Rounded.DirectionsWalk, false) { onAsk("${selected.label} 내릴게") } },
            { QuickTile("초기화", "기본 자세", Icons.Rounded.RestartAlt, false) { onAsk("${selected.label} 원래대로") } },
        ),
    )
}

private val AXIS_ICON = mapOf(
    Axis.FORE_AFT to Icons.Rounded.SwapVert,
    Axis.RECLINE to Icons.Rounded.AirlineSeatReclineExtra,
    Axis.HEIGHT to Icons.Rounded.Height,
    Axis.LUMBAR to Icons.Rounded.AccessibilityNew,
)
private val AXIS_HINT = mapOf(
    Axis.FORE_AFT to "뒤  ←  →  앞",
    Axis.RECLINE to "세움  ←  →  눕힘",
    Axis.HEIGHT to "낮게  ←  →  높게",
    Axis.LUMBAR to "약하게  ←  →  강하게",
)

// ───────────────────────── 창문 ─────────────────────────

@Composable
private fun WindowsTab(state: CarState, onAction: (List<Action>) -> Unit, onAsk: (String) -> Unit) {
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        QuickTile("전체 열기", "측면 창문", Icons.Rounded.UnfoldMore, false) { onAsk("창문 전부 열어") }
        QuickTile("전체 닫기", "측면 창문", Icons.Rounded.UnfoldLess, false) { onAsk("창문 전부 닫아") }
        QuickTile("환기 모드", "모두 20%", Icons.Rounded.Air, SIDE.all { state.windows.getValue(it) == 20 }) { onAsk("환기") }
    }
    Panel {
        WindowId.entries.forEachIndexed { i, w ->
            if (i > 0) HorizontalDivider(color = Line)
            val p = state.windows.getValue(w)
            SettingRow(if (w == WindowId.ROOF) Icons.Rounded.WbSunny else Icons.Rounded.Window, w.label, if (p == 0) "닫힘" else "$p% 열림") {
                var drag by remember(w) { mutableStateOf<Float?>(null) }
                Slider(
                    value = drag ?: p.toFloat(),
                    onValueChange = { drag = it },
                    onValueChangeFinished = {
                        drag?.let { onAction(listOf(Action.Window(w, it.roundToInt()))) }
                        drag = null
                    },
                    valueRange = 0f..100f,
                    colors = SliderDefaults.colors(thumbColor = Blue, activeTrackColor = Blue, inactiveTrackColor = Line),
                    // 고정 260dp면 세로 화면에서 창문 이름이 한 글자씩 줄바꿈된다 → 남는 폭의 절반까지만
                    modifier = Modifier.fillMaxWidth(0.5f).widthIn(max = 260.dp),
                )
            }
        }
    }
}

// ───────────────────────── 하단 내비 ─────────────────────────

@Composable
private fun NavPill(tab: Int, onTab: (Int) -> Unit, listening: Boolean, onMic: () -> Unit, modifier: Modifier) = Row(
    modifier.shadow(18.dp, RoundedCornerShape(50)).clip(RoundedCornerShape(50)).background(Navy).padding(8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
) {
    listOf(Icons.Rounded.DirectionsCar to "홈", Icons.Rounded.EventSeat to "좌석", Icons.Rounded.Window to "창문", Icons.Rounded.Bolt to "충전").forEachIndexed { i, (icon, label) ->
        val on = i == tab
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(if (on) Color.White.copy(alpha = 0.14f) else Color.Transparent)
                .clickable { onTab(i) }.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, label, tint = Color.White.copy(alpha = if (on) 1f else 0.55f), modifier = Modifier.size(26.dp))
            if (on) {
                Spacer(Modifier.width(8.dp))
                Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    Spacer(Modifier.width(4.dp))
    MicOrb(listening, onMic, 54.dp)
}

// ───────────────────────── 공통 컴포넌트 ─────────────────────────

@Composable
internal fun Panel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) = Column(
    modifier
        .shadow(10.dp, RoundedCornerShape(26.dp), ambientColor = Color(0x22000000), spotColor = Color(0x22000000))
        .clip(RoundedCornerShape(26.dp))
        .background(CardBg)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(22.dp),
    content = content,
)

@Composable
internal fun RowScope.QuickTile(title: String, sub: String, icon: ImageVector, active: Boolean, onClick: () -> Unit) = Column(
    // 글자가 두 줄이 되면 타일이 늘어난다. 같은 줄 타일 높이는 부모 Row의 IntrinsicSize.Min으로 맞춘다
    Modifier.weight(1f).fillMaxHeight().heightIn(min = 132.dp)
        .shadow(if (active) 12.dp else 8.dp, RoundedCornerShape(24.dp), spotColor = if (active) Blue else Color(0x22000000))
        .clip(RoundedCornerShape(24.dp))
        .background(if (active) Blue else CardBg)
        .clickable(onClick = onClick)
        .padding(18.dp),
) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(if (active) Color.White.copy(alpha = 0.2f) else BlueSoft),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (active) Color.White else Blue, modifier = Modifier.size(22.dp)) }
    // 제목은 아이콘 바로 아래 고정 위치. (아래 정렬이면 부제가 두 줄인 타일만 제목이 위로 올라가 옆 타일과 어긋난다)
    Spacer(Modifier.height(14.dp))
    Text(title, color = if (active) Color.White else Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
    Text(sub, color = if (active) Color.White.copy(alpha = 0.8f) else Sub, fontSize = 14.sp, maxLines = 2)
}

/** 타일 줄. 세로(좁은) 화면에서는 4개를 2×2로 나눈다. 같은 줄 타일은 가장 높은 타일에 높이를 맞춘다. */
@Composable
internal fun TileRows(wide: Boolean, modifier: Modifier = Modifier, tiles: List<@Composable RowScope.() -> Unit>) =
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(if (wide || tiles.size < 4) tiles.size else 2).forEach { row ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) { row.forEach { it() } }
        }
    }

@Composable
internal fun SettingRow(icon: ImageVector, title: String, sub: String, trailing: @Composable () -> Unit) = Row(
    Modifier.fillMaxWidth().padding(vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    IconBadge(icon)
    Spacer(Modifier.width(14.dp))
    Column(Modifier.weight(1f)) {
        Text(title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(sub, color = Sub, fontSize = 14.sp)
    }
    trailing()
}

@Composable
internal fun IconBadge(icon: ImageVector) = Box(
    Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(BlueSoft),
    contentAlignment = Alignment.Center,
) { Icon(icon, null, tint = Blue, modifier = Modifier.size(24.dp)) }

@Composable
private fun Stepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit) = Row(
    Modifier.clip(RoundedCornerShape(50)).background(Bg).padding(4.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    StepButton(Icons.Rounded.Remove, onMinus)
    Text(value, Modifier.widthIn(min = 64.dp), textAlign = TextAlign.Center, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink)
    StepButton(Icons.Rounded.Add, onPlus)
}

@Composable
private fun StepButton(icon: ImageVector, onClick: () -> Unit) = Box(
    Modifier.size(40.dp).clip(CircleShape).background(Color.White).clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
) { Icon(icon, null, tint = Ink, modifier = Modifier.size(20.dp)) }

@Composable
internal fun Chip(text: String, active: Boolean, big: Boolean = false, onClick: (() -> Unit)? = null) = Text(
    text,
    color = if (active) Color.White else Ink,
    fontSize = if (big) 15.sp else 13.sp,
    fontWeight = FontWeight.Medium,
    modifier = Modifier.clip(RoundedCornerShape(50))
        .background(if (active) Blue else BlueSoft)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = if (big) 20.dp else 14.dp, vertical = if (big) 12.dp else 8.dp),
)

@Composable
internal fun SectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) = Row(
    Modifier.fillMaxWidth().padding(top = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f))
    if (action != null) Text(action, color = Blue, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clickable(onClick = onAction))
}

@Composable
private fun Legend(color: Color, label: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
    Spacer(Modifier.width(6.dp))
    Text(label, color = Sub, fontSize = 14.sp)
}

private fun level(l: Int) = if (l == 0) "꺼짐" else "${l}단"

/** 화면 표시용: 문장이 끝나면 줄을 바꾼다("바꿨어요. / 등받이를…"). 음성(TTS)에는 원문을 쓴다. */
private fun sentenceLines(text: String) = text
    .replace(Regex("(?<=[.!?])\\s+"), "\n")
    .replace(Regex("약 (?=\\d)"), "약\u00A0") // "약 / 82km"처럼 갈라지지 않게 붙임 공백
