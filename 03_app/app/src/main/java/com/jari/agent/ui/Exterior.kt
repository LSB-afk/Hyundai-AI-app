package com.jari.agent.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EvStation
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Luggage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import com.jari.agent.Action
import com.jari.agent.CarState
import com.jari.agent.Opening
import com.jari.agent.WindowId

// 측면 실루엣 좌표계 1000x400, 차 앞쪽이 왼쪽(운전석 쪽 측면).
private const val CAR_W = 1000f
private const val CAR_H = 400f

/** 개폐 속도(ms, 0→100%). 실차 전동 테일게이트 ≈ 3초. */
private val OPEN_MS = mapOf(Opening.TRUNK to 2800, Opening.FRUNK to 1800, Opening.CHARGE_PORT to 700)

private val TAILGATE_HINGE = Offset(792f, 94f)
private val HOOD_HINGE = Offset(332f, 180f)
private val PORT = Rect(852f, 206f, 890f, 234f)

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
    Canvas(Modifier.fillMaxWidth().aspectRatio(CAR_W / CAR_H)) {
        val k = size.width / CAR_W
        scale(k, k, pivot = Offset.Zero) {
            drawCar(anim, front, rear, if (state.charging) pulse else 0f)
        }
    }
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

private fun DrawScope.drawCar(open: Map<Opening, Float>, front: Float, rear: Float, charge: Float) {
    val trunk = open.getValue(Opening.TRUNK)
    val hood = open.getValue(Opening.FRUNK)
    val port = open.getValue(Opening.CHARGE_PORT)
    val paint = Brush.verticalGradient(listOf(Color(0xFFFBFCFE), Color(0xFFE3E8EF), Color(0xFFB9C1CC)), 90f, 320f)
    val edge = Color(0xFF8E98A6)

    // 바닥 그림자
    drawOval(
        Brush.radialGradient(listOf(Color(0x33000000), Color.Transparent), Offset(500f, 330f), 480f),
        Offset(30f, 312f), Size(940f, 40f),
    )

    // 열린 트렁크·프렁크 안쪽(차체보다 먼저)
    if (trunk > 0f) {
        drawPath(polygon(792f to 112f, 866f to 122f, 926f to 184f, 938f to 218f, 942f to 262f, 880f to 262f, 820f to 150f), Color(0xFF2A2F37))
        drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE2A8).copy(alpha = 0.7f * trunk), Color.Transparent), Offset(880f, 200f), 70f), 70f, Offset(880f, 200f))
    }
    if (hood > 0f) drawPath(polygon(80f to 214f, 332f to 186f, 332f to 204f, 90f to 232f), Color(0xFF2A2F37))

    // 차체(트렁크 리드·후드는 따로 그린다)
    val body = Path().apply {
        moveTo(48f, 300f)
        lineTo(36f, 262f)
        cubicTo(36f, 240f, 50f, 222f, 76f, 214f)
        lineTo(332f, 188f)
        lineTo(470f, 98f)
        quadraticTo(640f, 84f, 792f, 94f)
        lineTo(792f, 112f)
        lineTo(866f, 122f)
        lineTo(926f, 184f)
        lineTo(938f, 218f)
        lineTo(942f, 262f)
        lineTo(964f, 262f)
        lineTo(966f, 292f)
        lineTo(950f, 312f)
        lineTo(868f, 312f)
        arcTo(Rect(Offset(780f, 312f), 88f), 0f, -180f, false)
        lineTo(308f, 312f)
        arcTo(Rect(Offset(220f, 312f), 88f), 0f, -180f, false)
        lineTo(56f, 312f)
        close()
    }
    drawPath(body, paint)
    drawPath(body, edge, style = Stroke(2f))

    // 유리: 창문 값만큼 위에서부터 내려간다 → 그만큼 실내가 보임
    glass(polygon(360f to 186f, 474f to 112f, 596f to 106f, 596f to 190f), front)
    glass(polygon(616f to 190f, 616f to 106f, 790f to 104f, 852f to 120f, 896f to 182f), rear)
    drawPath(polygon(596f to 106f, 616f to 106f, 616f to 192f, 596f to 192f), Color(0xFF1C2129)) // B필러

    // 디테일: 벨트라인, 도어 라인, 손잡이, 하부 클래딩, 등화
    drawLine(Color.White.copy(alpha = 0.9f), Offset(340f, 196f), Offset(930f, 190f), 3f)
    drawLine(Color(0xFFC3CAD4), Offset(120f, 252f), Offset(930f, 238f), 2f)
    drawLine(edge, Offset(606f, 194f), Offset(606f, 304f), 1.5f)
    drawLine(edge, Offset(340f, 192f), Offset(330f, 300f), 1.5f)
    drawLine(edge, Offset(872f, 192f), Offset(860f, 262f), 1.5f)
    drawRoundRect(Color(0xFF9AA4B2), Offset(520f, 206f), Size(46f, 8f), CornerRadius(4f))
    drawRoundRect(Color(0xFF9AA4B2), Offset(790f, 204f), Size(46f, 8f), CornerRadius(4f))
    drawRect(Color(0xFF3A414B), Offset(308f, 296f), Size(384f, 16f))
    drawRoundRect(Color(0xFFE8F4FF), Offset(38f, 228f), Size(80f, 7f), CornerRadius(4f))
    drawRoundRect(Color(0xFFE5484D), Offset(930f, 204f), Size(34f, 7f), CornerRadius(4f))
    wheel(Offset(220f, 312f))
    wheel(Offset(780f, 312f))

    // 충전구: 소켓 → 덮개가 앞쪽 경첩을 축으로 접힌다
    if (port > 0f) {
        drawRoundRect(Color(0xFF15191F), PORT.topLeft, PORT.size, CornerRadius(6f))
        val ring = if (charge > 0f) Color(0xFF2BD47D).copy(alpha = charge) else Color(0xFF2BD47D).copy(alpha = 0.6f * port)
        drawCircle(ring, 8f, PORT.center, style = Stroke(3f))
    }
    scale(1f - 0.85f * port, 1f, pivot = PORT.centerLeft) {
        drawRoundRect(Color(0xFFDDE3EA), PORT.topLeft, PORT.size, CornerRadius(6f))
        drawRoundRect(edge, PORT.topLeft, PORT.size, CornerRadius(6f), style = Stroke(1.5f))
    }

    // 테일게이트: 지붕 끝 경첩에서 위로 최대 55°
    rotate(-55f * trunk, TAILGATE_HINGE) {
        val lid = polygon(792f to 92f, 882f to 104f, 950f to 178f, 964f to 216f, 966f to 262f, 942f to 262f, 938f to 218f, 926f to 184f, 866f to 122f, 792f to 112f)
        drawPath(lid, paint)
        drawPath(lid, edge, style = Stroke(2f))
        drawRoundRect(Color(0xFFE5484D), Offset(946f, 204f), Size(18f, 7f), CornerRadius(4f))
    }
    // 프렁크(후드): 앞유리 쪽 경첩에서 앞이 들린다, 최대 26°
    rotate(26f * hood, HOOD_HINGE) {
        val lid = polygon(66f to 210f, 332f to 178f, 338f to 188f, 78f to 220f)
        drawPath(lid, paint)
        drawPath(lid, edge, style = Stroke(2f))
    }
}

private fun DrawScope.glass(shape: Path, open: Float) {
    val b = shape.getBounds()
    drawPath(shape, Color(0xFFD9DEE5)) // 실내
    drawCircle(Color(0xFF4A5260), 22f, Offset(b.left + b.width * 0.55f, b.top + 34f)) // 헤드레스트
    clipPath(shape) {
        val top = b.top + b.height * open
        drawRect(
            Brush.verticalGradient(listOf(Color(0xFF2A3442), Color(0xFF4C5868)), top, b.bottom),
            Offset(b.left, top), Size(b.width, b.bottom - top),
        )
        if (open > 0.02f) drawLine(Color.White.copy(alpha = 0.8f), Offset(b.left, top), Offset(b.right, top), 2f)
    }
}

private fun DrawScope.wheel(c: Offset) {
    drawCircle(Color(0xFF1B1E23), 70f, c)
    drawCircle(Brush.radialGradient(listOf(Color(0xFFE7EBF0), Color(0xFF8D96A3)), c, 50f), 50f, c)
    repeat(5) { i ->
        rotate(i * 72f, c) { drawLine(Color(0xFF59616D), c, c + Offset(0f, -46f), 9f) }
    }
    drawCircle(Color(0xFF3A414B), 11f, c)
}

private fun polygon(vararg p: Pair<Float, Float>) = Path().apply {
    moveTo(p[0].first, p[0].second)
    p.drop(1).forEach { lineTo(it.first, it.second) }
    close()
}
