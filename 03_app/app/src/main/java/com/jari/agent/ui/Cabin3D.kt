package com.jari.agent.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawTransform
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jari.agent.Axis
import com.jari.agent.CarState
import com.jari.agent.R
import com.jari.agent.SeatId
import com.jari.agent.WindowId
import kotlin.math.abs
import kotlin.math.roundToInt

/** 화면에 그릴 시트 값. 상태값을 부드럽게 따라가며, 아직 목표에 못 미친 축은 '작동 중'(방향 ±1). */
class SeatAnim(
    val pos: Map<Axis, Float>,
    val heat: Float,
    val vent: Float,
    val occupied: Boolean,
    val moving: Map<Axis, Int>,
) {
    operator fun get(a: Axis) = pos.getValue(a)
}

@Composable
fun rememberSeatAnims(state: CarState): Map<SeatId, SeatAnim> = SeatId.entries.associateWith { id ->
    val s = state.seat(id)
    val pos = Axis.entries.associateWith { a ->
        animateFloatAsState(s.pos.getValue(a).toFloat(), tween(320), label = "$id$a").value
    }
    val heat by animateFloatAsState(s.heat.toFloat(), tween(600), label = "${id}heat")
    val vent by animateFloatAsState(s.vent.toFloat(), tween(600), label = "${id}vent")
    val moving = Axis.entries.mapNotNull { a ->
        val d = s.pos.getValue(a) - pos.getValue(a)
        if (abs(d) > 0.5f) a to (if (d > 0) 1 else -1) else null
    }.toMap()
    SeatAnim(pos, heat, vent, s.occupied, moving)
}

// 원본 아트워크(design/cabin_seats.png, 1536x1024) 좌표계. design/cut_seats.py 가 부위별 레이어로 잘라 둔다.
private const val ART_W = 1536f
private const val ART_H = 1024f

// 시트를 감싸는 차체: 턴테이블 프레임(746x494) 중 옆모습(HOME_FRAME)을 반투명으로 깔고 그 안에 시트를 놓는다.
// 좌우로 끌면 차가 돌고 놓은 각도에 머문다. 시트 그림은 한 각도뿐이라 옆모습에서만 보이며,
// 옆모습 근처에서 놓거나 "좌석 보기"를 누르면 옆모습으로 맞춰진다.
private const val HOME_FRAME = 58
// 프레임 px → 아트워크 좌표 = px * CAR_SCALE + CAR_AT. 시트·프레임 중 하나라도 바꾸면 다시 맞춰야 한다.
private const val CAR_SCALE = 5f
private val CAR_AT = Offset(-1175f, -590f)
/** 화면에 담는 범위(아트워크 좌표). 어느 각도로 돌려도 차가 잘리지 않는 크기. */
private val SCENE = Rect(-1090f, -300f, 2470f, 1700f)

// 선루프 위치(프레임 px, 옆모습 기준)
private val GLASS_ROOF = pathOf(325f to 105f, 400f to 96f, 492f to 99f, 510f to 146f, 400f to 148f, 350f to 152f)

private fun pathOf(vararg p: Pair<Float, Float>) = Path().apply {
    moveTo(p[0].first, p[0].second)
    p.drop(1).forEach { lineTo(it.first, it.second) }
    close()
}

private enum class Kind { BACK, CUSHION, FIXED }

/** seat_* 레이어. 뒤에 있는 것부터 그린다(조수석 → 뒷좌석 → 고정부 → 운전석). */
private class Layer(val res: Int, val at: Offset, val seat: SeatId?, val kind: Kind)

private val LAYERS = listOf(
    Layer(R.drawable.seat_ps_back, Offset(271f, 84f), SeatId.PASSENGER, Kind.BACK),
    Layer(R.drawable.seat_ps_cush, Offset(52f, 529f), SeatId.PASSENGER, Kind.CUSHION),
    Layer(R.drawable.seat_rr_back, Offset(910f, 131f), SeatId.REAR_RIGHT, Kind.BACK),
    Layer(R.drawable.seat_rl_back, Offset(1140f, 210f), SeatId.REAR_LEFT, Kind.BACK),
    Layer(R.drawable.seat_base, Offset(52f, 231f), null, Kind.FIXED),
    Layer(R.drawable.seat_dr_cush, Offset(104f, 640f), SeatId.DRIVER, Kind.CUSHION),
    Layer(R.drawable.seat_dr_back, Offset(416f, 176f), SeatId.DRIVER, Kind.BACK),
)

/** 등받이 회전축(힌지)과 허리 지지 위치, 아트워크 좌표 */
private val HINGE = mapOf(
    SeatId.DRIVER to Offset(600f, 790f),
    SeatId.PASSENGER to Offset(440f, 540f),
    SeatId.REAR_RIGHT to Offset(1050f, 605f),
    SeatId.REAR_LEFT to Offset(1300f, 640f),
)
private val LUMBAR_AT = mapOf(
    SeatId.DRIVER to Offset(575f, 640f),
    SeatId.PASSENGER to Offset(485f, 440f),
    SeatId.REAR_RIGHT to Offset(1010f, 480f),
    SeatId.REAR_LEFT to Offset(1300f, 500f),
)

/** 앞좌석 레일 방향(아트워크에서 차 앞쪽 = 왼쪽 아래) */
private val FORWARD = Offset(-0.99f, 0.15f)
private val FRONT = setOf(SeatId.DRIVER, SeatId.PASSENGER)

/** 시트 값 → 화면 이동량. 앞좌석은 레일 ±28px·높이 ±18px, 등받이 각도는 앞 0.2°/%, 뒤 0.12°/%. */
private fun slide(id: SeatId, a: SeatAnim): Offset {
    if (id !in FRONT) return Offset.Zero
    val d = (a[Axis.FORE_AFT] - 50f) / 50f * 28f
    return Offset(FORWARD.x * d, FORWARD.y * d - (a[Axis.HEIGHT] - 50f) / 50f * 18f)
}

private fun recline(id: SeatId, a: SeatAnim) = (a[Axis.RECLINE] - 30f) * if (id in FRONT) 0.2f else 0.12f

// 터치 영역 중심(아트워크 비율). 앞좌석은 시트를 따라 움직인다.
private val SEAT_POINTS = mapOf(
    SeatId.DRIVER to (0.25f to 0.74f),
    SeatId.PASSENGER to (0.28f to 0.38f),
    SeatId.REAR_LEFT to (0.85f to 0.56f),
    SeatId.REAR_RIGHT to (0.66f to 0.36f),
)

/** 실사 시트 아트워크. 레이어를 시트 값대로 움직여 실제 시트 동작을 보여 준다. */
@Composable
fun Cabin3D(
    state: CarState,
    anims: Map<SeatId, SeatAnim>,
    selected: SeatId,
    onSeatTap: (SeatId) -> Unit,
    modifier: Modifier = Modifier,
    side: Boolean = false,
) {
    val bitmaps = LAYERS.map { ImageBitmap.imageResource(it.res) }
    val roof by animateFloatAsState(state.windows.getValue(WindowId.ROOF) / 100f, tween(400), label = "roof")
    val pulse by rememberInfiniteTransition(label = "seatPulse")
        .animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "p")
    // 옆모습에서 몇 프레임 돌아갔는지. 0(또는 한 바퀴의 배수)이면 시트가 보이는 기본 각도.
    val n = CAR_FRAMES.size
    val turn = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val home = (turn.value / n).roundToInt() * n.toFloat()
    val away = (abs(turn.value - home) / 2.5f).coerceAtMost(1f) // 0 = 기본 각도, 1 = 완전히 돌아감
    val frame = ((HOME_FRAME + turn.value.roundToInt()) % n + n) % n
    val car = ImageBitmap.imageResource(CAR_FRAMES[frame])
    Column(modifier) {
        Text("좌석을 누르면 선택 · 좌우로 끌면 차량 회전", color = Sub, fontSize = 14.sp, modifier = Modifier.padding(4.dp))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val sceneWidth = minOf(maxWidth, maxHeight * (SCENE.width / SCENE.height))
            val sceneHeight = sceneWidth * (SCENE.height / SCENE.width)
            val unit = sceneWidth / SCENE.width.dp // 아트워크 1px = unit dp
            Box(
                Modifier.size(sceneWidth, sceneHeight).align(Alignment.Center).pointerInput(Unit) {
                    // 옆모습에서 4프레임(약 22°) 안쪽에 놓으면 옆모습으로 붙고, 그 밖이면 놓은 자리에 머문다
                    val settle = {
                        val h = (turn.value / n).roundToInt() * n.toFloat()
                        if (abs(turn.value - h) <= 4f) scope.launch { turn.animateTo(h, tween(300)) }
                        Unit
                    }
                    detectHorizontalDragGestures(onDragEnd = settle, onDragCancel = settle) { _, dx ->
                        scope.launch { turn.snapTo(turn.value + dx / size.width * n) }
                    }
                },
            ) {
                val toScene: DrawTransform.() -> Unit = {
                    val k = size.width / SCENE.width
                    scale(k, k, pivot = Offset.Zero); translate(-SCENE.left, -SCENE.top)
                }
                // 차체: 기본 각도에서는 시트가 비치게 반투명, 돌리는 동안은 선명하게
                Canvas(Modifier.fillMaxSize()) {
                    withTransform({ toScene(); translate(CAR_AT.x, CAR_AT.y); scale(CAR_SCALE, CAR_SCALE, Offset.Zero) }) {
                        drawImage(car, alpha = 0.55f + 0.45f * away)
                        if (away == 0f) drawRoofOpening(roof)
                    }
                }
                Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - away }) {
                    withTransform(toScene) {
                        LAYERS.forEachIndexed { i, l -> drawLayer(l, bitmaps[i], l.seat?.let(anims::getValue), pulse, l.seat == selected) }
                    }
                }
                if (away >= 0.5f) Text(
                    "좌석 보기", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.BottomCenter).clip(RoundedCornerShape(50))
                        .clickable { scope.launch { turn.animateTo(home, tween(600)) } }
                        .background(Blue).padding(horizontal = 18.dp, vertical = 12.dp),
                )
                if (away < 0.5f) SeatId.entries.forEach { id ->
                    val (x, y) = SEAT_POINTS.getValue(id)
                    val at = Offset(ART_W * x - SCENE.left, ART_H * y - SCENE.top) + slide(id, anims.getValue(id))
                    // 터치 영역은 시트 크기에 맞춰 키운다(시트 한 개 ≈ 아트워크 340px)
                    val touch = (340f * unit).dp.coerceAtLeast(48.dp)
                    SeatMarker(
                        id, anims.getValue(id), selected == id, { onSeatTap(id) },
                        Modifier.absoluteOffset(x = (at.x * unit).dp - touch / 2, y = (at.y * unit).dp - touch / 2).size(touch),
                    )
                }
            }
        }
        if (!side) WindowSummary(state)
    }
}

/** 선루프는 유리가 뒤로 밀리며 앞쪽부터 열린다. 열린 자리는 실내가 밝게 보인다. */
private fun DrawScope.drawRoofOpening(open: Float) {
    if (open <= 0f) return
    val hole = Path().apply { op(GLASS_ROOF, Path().apply { addPath(GLASS_ROOF, Offset(150f * open, 0f)) }, PathOperation.Difference) }
    drawPath(hole, Color(0xFFEFF3F8))
    drawPath(hole, Blue, alpha = 0.8f, style = Stroke(1.2f))
}

private fun DrawScope.drawLayer(l: Layer, bmp: ImageBitmap, a: SeatAnim?, pulse: Float, selected: Boolean) {
    if (l.seat == null || a == null) {
        drawImage(bmp, topLeft = l.at)
        return
    }
    val id = l.seat
    val hinge = HINGE.getValue(id)
    withTransform({
        slide(id, a).let { translate(it.x, it.y) }
        if (l.kind == Kind.BACK) rotate(recline(id, a), hinge)
    }) {
        drawImage(bmp, topLeft = l.at)
        // 선택한 좌석은 시트 자체를 파랗게 물들여 표시한다(마커 없음)
        if (selected) drawImage(bmp, topLeft = l.at, colorFilter = ColorFilter.tint(Blue.copy(alpha = 0.38f), BlendMode.SrcAtop))
        // 열선/통풍은 시트 표면에 은은한 색, 작동 중인 부위는 파랗게 깜빡임
        val axes = if (l.kind == Kind.BACK) listOf(Axis.RECLINE, Axis.LUMBAR) else listOf(Axis.FORE_AFT, Axis.HEIGHT)
        val moving = axes.any { it in a.moving } || (id in FRONT && l.kind == Kind.BACK && (Axis.FORE_AFT in a.moving || Axis.HEIGHT in a.moving))
        val tint = when {
            moving -> Blue.copy(alpha = 0.28f * pulse)
            a.heat > 0.05f -> Warm.copy(alpha = 0.07f * a.heat)
            a.vent > 0.05f -> Cool.copy(alpha = 0.07f * a.vent)
            else -> null
        }
        if (tint != null) drawImage(bmp, topLeft = l.at, colorFilter = ColorFilter.tint(tint, BlendMode.SrcAtop))
        if (l.kind == Kind.BACK && Axis.LUMBAR in a.moving) {
            val c = LUMBAR_AT.getValue(id)
            val r = 40f + 50f * a[Axis.LUMBAR] / 100f
            drawCircle(Brush.radialGradient(listOf(Blue.copy(alpha = 0.55f * pulse), Color.Transparent), c, r), r, c)
        }
    }
}

/** 보이지 않는 터치 영역. 선택 표시는 시트 색으로 하고, 이름·상태는 읽기 도구용 설명으로만 남긴다. */
@Composable
private fun SeatMarker(id: SeatId, seat: SeatAnim, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val heat = seat.heat.roundToInt()
    val vent = seat.vent.roundToInt()
    val status = listOfNotNull(
        if (seat.occupied) "탑승 중" else "빈 좌석",
        if (heat > 0) "열선 ${heat}단" else null,
        if (vent > 0) "통풍 ${vent}단" else null,
        if (seat.moving.isNotEmpty()) "조절 중" else null,
    ).joinToString(", ")
    Box(
        modifier.clip(CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = id.label
                stateDescription = status
            },
    )
}

@Composable
private fun WindowSummary(state: CarState) {
    val labels = listOf("운전석", "조수석", "뒤 왼쪽", "뒤 오른쪽", "선루프")
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WindowId.entries.forEachIndexed { index, id ->
            val percent = state.windows.getValue(id)
            Column(
                Modifier.width(76.dp).clip(RoundedCornerShape(12.dp)).background(Bg).padding(vertical = 8.dp)
                    .semantics(mergeDescendants = true) { contentDescription = id.label },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(labels[index], fontSize = 11.sp, color = Sub)
                Text(if (percent == 0) "닫힘" else "${percent}% 열림", fontSize = 12.sp,
                    color = if (percent == 0) Ink else Blue, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** 선택 시트의 현재 값 (작동 중인 축은 파랗게) */
@Composable
fun SeatAxisChips(label: String, a: SeatAnim, modifier: Modifier = Modifier) = Row(modifier, verticalAlignment = Alignment.CenterVertically) {
    Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.weight(1f))
    listOf(
        "등받이 ${(8f + 45f * a[Axis.RECLINE] / 100f).toInt()}°" to Axis.RECLINE,
        "앞뒤 ${a[Axis.FORE_AFT].toInt()}%" to Axis.FORE_AFT,
        "높이 ${a[Axis.HEIGHT].toInt()}%" to Axis.HEIGHT,
        "허리 ${a[Axis.LUMBAR].toInt()}%" to Axis.LUMBAR,
    ).forEach { (t, axis) ->
        val on = axis in a.moving
        Text(
            t, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (on) Color.White else Ink,
            modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(50)).background(if (on) Blue else Bg)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}
