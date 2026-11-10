package com.jari.agent.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EvStation
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Luggage
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.jari.agent.Action
import com.jari.agent.CarState
import com.jari.agent.Opening
import com.jari.agent.R
import com.jari.agent.WindowId
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

// 실차 렌더 턴테이블: res/drawable-nodpi/car_00..64.webp (746x494, 한 바퀴 65장 ≈ 5.5° 간격).
// 0번이 기본 각도(앞-왼쪽 위 3/4 뷰)이고, 열리는 부위는 이 각도에서만 겹쳐 그린다.
private const val CAR_W = 746f
private const val CAR_H = 494f
internal val CAR_FRAMES = intArrayOf(
    R.drawable.car_00, R.drawable.car_01, R.drawable.car_02, R.drawable.car_03, R.drawable.car_04, R.drawable.car_05, R.drawable.car_06, R.drawable.car_07,
    R.drawable.car_08, R.drawable.car_09, R.drawable.car_10, R.drawable.car_11, R.drawable.car_12, R.drawable.car_13, R.drawable.car_14, R.drawable.car_15,
    R.drawable.car_16, R.drawable.car_17, R.drawable.car_18, R.drawable.car_19, R.drawable.car_20, R.drawable.car_21, R.drawable.car_22, R.drawable.car_23,
    R.drawable.car_24, R.drawable.car_25, R.drawable.car_26, R.drawable.car_27, R.drawable.car_28, R.drawable.car_29, R.drawable.car_30, R.drawable.car_31,
    R.drawable.car_32, R.drawable.car_33, R.drawable.car_34, R.drawable.car_35, R.drawable.car_36, R.drawable.car_37, R.drawable.car_38, R.drawable.car_39,
    R.drawable.car_40, R.drawable.car_41, R.drawable.car_42, R.drawable.car_43, R.drawable.car_44, R.drawable.car_45, R.drawable.car_46, R.drawable.car_47,
    R.drawable.car_48, R.drawable.car_49, R.drawable.car_50, R.drawable.car_51, R.drawable.car_52, R.drawable.car_53, R.drawable.car_54, R.drawable.car_55,
    R.drawable.car_56, R.drawable.car_57, R.drawable.car_58, R.drawable.car_59, R.drawable.car_60, R.drawable.car_61, R.drawable.car_62, R.drawable.car_63,
    R.drawable.car_64,
)

// 아래 겹침 좌표는 처음 맞춘 584x398 기준 → 0번 프레임 위치로 옮기는 배율·오프셋
private const val OVERLAY_SCALE = 1.06f
private val OVERLAY_OFFSET = Offset(43.8f, 11.6f)

/** 개폐 속도(ms, 0→100%). 실차 전동 테일게이트 ≈ 3초. */
private val OPEN_MS = mapOf(Opening.TRUNK to 2800, Opening.FRUNK to 1800, Opening.CHARGE_PORT to 700)

private val HOOD = polygon(
    135f to 171f, 150f to 190f, 180f to 205f, 215f to 218f, 245f to 222f, 264f to 222f, 210f to 252f,
    150f to 284f, 100f to 272f, 60f to 251f, 40f to 238f, 48f to 222f, 60f to 210f, 100f to 185f,
)
private val HOOD_HINGE = Offset(135f, 171f)       // 앞유리 아래 경첩선의 한 점
private val HOOD_NORMAL = Offset(-0.368f, 0.930f) // 경첩선에서 차 앞쪽을 향하는 단위 법선
private val HOOD_LIFT = Offset(0.08f, -0.6f)      // 완전 열림 시 경첩에서 1px 떨어진 점의 화면 이동량
private val FRONT_GLASS = polygon(315f to 186f, 324f to 157f, 338f to 137f, 352f to 129f, 374f to 122f, 378f to 172f, 340f to 192f)
private val REAR_GLASS = polygon(392f to 119f, 428f to 103f, 444f to 102f, 488f to 131f, 470f to 138f, 395f to 168f)
private val TAILGATE_A = Offset(402f, 50f)        // 지붕 끝 경첩선
private val TAILGATE_B = Offset(480f, 80f)
private val TAILGATE_REACH = Offset(50f, -42f)    // 완전 열림 시 끝단이 경첩에서 뻗는 화면 방향
// ponytail: 이 각도에서는 실제 충전구(우측 뒤)가 안 보여 보이는 쪽 뒤 펜더에 표시한다
private val PORT = Rect(495f, 131f, 512f, 144f)

/** 차량 외관 + 트렁크·프렁크·충전구 조작. 값은 상태를 따라 실제 개폐 속도로 움직인다. */
@Composable
fun ExteriorPanel(state: CarState, onAction: (List<Action>) -> Unit) = Panel {
    val anim = Opening.entries.associateWith { o ->
        animateFloatAsState(
            state.openings.getValue(o) / 100f, tween(OPEN_MS.getValue(o), easing = FastOutSlowInEasing), label = o.name,
        ).value
    }
    val front by animateFloatAsState(state.windows.getValue(WindowId.FL) / 100f, tween(400), label = "fl")
    val rear by animateFloatAsState(state.windows.getValue(WindowId.RL) / 100f, tween(400), label = "rl")
    val pulse by rememberInfiniteTransition(label = "port")
        .animateFloat(0.4f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "p")
    // 가로로 끌면 회전(단위: 프레임). 캔버스 폭만큼 끌면 약 한 바퀴.
    val turn = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val n = CAR_FRAMES.size
    suspend fun home() = turn.animateTo((turn.value / n).roundToInt() * n.toFloat(), tween(500))
    val opened = state.openings.values.any { it > 0 } || state.windows.getValue(WindowId.FL) > 0 || state.windows.getValue(WindowId.RL) > 0
    // 개폐 모션은 기본 각도에서만 그릴 수 있으므로, 상태가 바뀌면 기본 각도로 돌아온다
    LaunchedEffect(state.openings, state.windows) { home() }
    val frame = (turn.value.roundToInt() % n + n) % n
    val car = ImageBitmap.imageResource(CAR_FRAMES[frame])
    Canvas(
        Modifier.fillMaxWidth(0.9f).align(Alignment.CenterHorizontally).aspectRatio(CAR_W / CAR_H)
            .pointerInput(opened) {
                detectHorizontalDragGestures(
                    onDragEnd = { if (opened) scope.launch { home() } },
                ) { _, dx -> scope.launch { turn.snapTo(turn.value + dx / size.width * n) } }
            },
    ) {
        val k = size.width / CAR_W
        scale(k, k, pivot = Offset.Zero) {
            drawImage(car)
            if (frame == 0) withTransform({ translate(OVERLAY_OFFSET.x, OVERLAY_OFFSET.y); scale(OVERLAY_SCALE, OVERLAY_SCALE, Offset.Zero) }) {
                drawOpenings(car, anim, front, rear, if (state.charging) pulse else 0f)
            }
        }
    }
    Text("좌우로 끌어서 돌려보기", color = Sub, fontSize = 14.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(
            Opening.FRUNK to Icons.Rounded.Inventory2,
            Opening.TRUNK to Icons.Rounded.Luggage,
            Opening.CHARGE_PORT to Icons.Rounded.EvStation,
        ).forEach { (o, icon) ->
            val target = state.openings.getValue(o)
            val now = anim.getValue(o) * 100f
            val status = when {
                kotlin.math.abs(now - target) > 1f -> if (target > now) "여는 중…" else "닫는 중…"
                target > 0 -> "열림"
                else -> "닫힘"
            }
            QuickTile(o.label, status, icon, target > 0) { onAction(listOf(Action.Open(o, target == 0))) }
        }
    }
}

/** 기본 각도 프레임 [car] 위에 열린 부위를 그린다. 좌표는 584x398 기준(호출부에서 변환). */
private fun DrawScope.drawOpenings(car: ImageBitmap, open: Map<Opening, Float>, front: Float, rear: Float, charge: Float) {
    val trunk = open.getValue(Opening.TRUNK)
    val hood = open.getValue(Opening.FRUNK)
    val port = open.getValue(Opening.CHARGE_PORT)
    val edge = Color(0xFF8E98A6)

    glass(FRONT_GLASS, front, 62f)
    glass(REAR_GLASS, rear, 52f)

    // 프렁크: 원본의 후드 부분을 잘라 경첩선 기준으로 들어 올리고, 그 아래 적재함을 드러낸다
    if (hood > 0f) {
        drawPath(HOOD, Brush.verticalGradient(listOf(Color(0xFF14181D), Color(0xFF3A414B)), 171f, 284f), alpha = (hood * 5f).coerceAtMost(1f))
        val v = HOOD_LIFT * hood
        val c = -(HOOD_NORMAL.x * HOOD_HINGE.x + HOOD_NORMAL.y * HOOD_HINGE.y)
        val lift = Matrix().apply { // P' = P + (n·(P−H))·v : 경첩선은 고정, 멀수록 많이 움직인다
            this[0, 0] = 1f + HOOD_NORMAL.x * v.x; this[1, 0] = HOOD_NORMAL.y * v.x; this[3, 0] = c * v.x
            this[0, 1] = HOOD_NORMAL.x * v.y; this[1, 1] = 1f + HOOD_NORMAL.y * v.y; this[3, 1] = c * v.y
        }
        withTransform({ transform(lift) }) {
            clipPath(HOOD) {
                withTransform({ scale(1f / OVERLAY_SCALE, 1f / OVERLAY_SCALE, Offset.Zero); translate(-OVERLAY_OFFSET.x, -OVERLAY_OFFSET.y) }) { drawImage(car) }
            }
            drawPath(HOOD, edge, style = Stroke(1.5f))
        }
    }

    // 테일게이트: 지붕 끝 경첩에서 뒤·위로 뻗어 나온다(닫힌 상태에서는 차 뒤에 가려 안 보임)
    if (trunk > 0f) {
        val o = TAILGATE_REACH * trunk
        fun quad(inset: Float): Path {
            val a = lerp(TAILGATE_A, TAILGATE_B, inset)
            val b = lerp(TAILGATE_B, TAILGATE_A, inset)
            return polygon(
                (a + o * inset).let { it.x to it.y }, (b + o * inset).let { it.x to it.y },
                (b + o * (1f - inset)).let { it.x to it.y }, (a + o * (1f - inset)).let { it.x to it.y },
            )
        }
        val lid = quad(0f)
        drawPath(lid, Brush.linearGradient(listOf(Color(0xFFF2F4F7), Color(0xFFA9B1BC)), TAILGATE_A + o, TAILGATE_B))
        drawPath(quad(0.16f), Color(0xFF1C2129)) // 뒷유리
        drawPath(lid, edge, style = Stroke(1.5f))
    }

    // 충전구: 소켓 → 덮개가 앞쪽 경첩을 축으로 접힌다
    if (port > 0f) {
        drawRoundRect(Color(0xFF15191F), PORT.topLeft, PORT.size, CornerRadius(3f))
        val ring = if (charge > 0f) Color(0xFF2BD47D).copy(alpha = charge) else Color(0xFF2BD47D).copy(alpha = 0.6f * port)
        drawCircle(ring, 3.5f, PORT.center, style = Stroke(1.8f))
        scale(1f - 0.85f * port, 1f, pivot = PORT.centerLeft) {
            drawRoundRect(Color(0xFFDDE3EA), PORT.topLeft, PORT.size, CornerRadius(3f))
            drawRoundRect(edge, PORT.topLeft, PORT.size, CornerRadius(3f), style = Stroke(1f))
        }
    }
}

/** 유리가 [open] 만큼 아래로 내려가고, 내려간 자리(위쪽)는 실내가 들여다보인다. [drop] = 완전 개방 시 이동량. */
private fun DrawScope.glass(shape: Path, open: Float, drop: Float) {
    if (open <= 0f) return
    val lowered = Path().apply { addPath(shape, Offset(0f, drop * open)) }
    val gap = Path().apply { op(shape, lowered, PathOperation.Difference) }
    drawPath(gap, Color(0xFFAEB6C1))
    drawPath(gap, Color.White.copy(alpha = 0.7f), style = Stroke(1f))
}

private fun polygon(vararg p: Pair<Float, Float>) = Path().apply {
    moveTo(p[0].first, p[0].second)
    p.drop(1).forEach { lineTo(it.first, it.second) }
    close()
}
