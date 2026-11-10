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

// 마커 위치(아트워크 비율). 앞좌석 마커는 시트를 따라 움직인다.
private val SEAT_POINTS = mapOf(
    SeatId.DRIVER to (0.25f to 0.74f),
    SeatId.PASSENGER to (0.28f to 0.38f),
    SeatId.REAR_LEFT to (0.80f to 0.64f),
    SeatId.REAR_RIGHT to (0.67f to 0.39f),
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
    val pulse by rememberInfiniteTransition(label = "seatPulse")
        .animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "p")
    Column(modifier) {
        Text("좌석을 눌러 선택하세요", color = Sub, fontSize = 14.sp, modifier = Modifier.padding(4.dp))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val artWidth = minOf(maxWidth, maxHeight * (ART_W / ART_H))
            val artHeight = artWidth * (ART_H / ART_W)
            Box(Modifier.size(artWidth, artHeight).align(Alignment.Center)) {
                Canvas(Modifier.fillMaxSize()) {
                    val k = size.width / ART_W
                    scale(k, k, pivot = Offset.Zero) {
                        LAYERS.forEachIndexed { i, l -> drawLayer(l, bitmaps[i], l.seat?.let(anims::getValue), pulse) }
                    }
                }
                SeatId.entries.forEach { id ->
                    val (x, y) = SEAT_POINTS.getValue(id)
                    val move = slide(id, anims.getValue(id)) * (artWidth / ART_W.dp)
                    SeatMarker(
                        id, anims.getValue(id), selected == id, { onSeatTap(id) },
                        Modifier.absoluteOffset(x = artWidth * x - 53.dp + move.x.dp, y = artHeight * y - 24.dp + move.y.dp)
                            .width(106.dp),
                    )
                }
            }
        }
        if (!side) WindowSummary(state)
    }
}

private fun DrawScope.drawLayer(l: Layer, bmp: ImageBitmap, a: SeatAnim?, pulse: Float) {
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
    Column(
        modifier.clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = id.label
                stateDescription = status
            }.padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(40.dp).background(Color.White.copy(alpha = 0.96f), CircleShape)
                    .border(if (selected) 3.dp else 2.dp, if (selected) Blue else Line, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(14.dp).border(4.dp, if (selected) Blue else Ink, CircleShape))
            }
            if (seat.occupied) Box(
                Modifier.align(Alignment.TopEnd).padding(3.dp).size(10.dp)
                    .background(Navy, CircleShape).border(2.dp, Color.White, CircleShape),
            )
        }
        Text(
            id.label, color = if (selected) Color.White else Ink, fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) Blue else Color.White.copy(alpha = 0.94f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (heat > 0) SeatStatus("열선 $heat", Warm)
            if (vent > 0) SeatStatus("통풍 $vent", Cool)
        }
        if (seat.moving.isNotEmpty()) SeatStatus("조절 중", Blue)
    }
}

@Composable
private fun SeatStatus(text: String, color: Color) {
    Text(
        text, fontSize = 11.sp, color = color, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 3.dp).clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.96f)).padding(horizontal = 5.dp, vertical = 2.dp),
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
