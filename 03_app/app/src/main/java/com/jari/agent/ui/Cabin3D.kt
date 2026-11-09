package com.jari.agent.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jari.agent.Axis
import com.jari.agent.CarState
import com.jari.agent.SeatId
import com.jari.agent.WindowId
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// ───────────────────────── 시트 애니메이션 값 ─────────────────────────

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

// ───────────────────────── 최소 3D 엔진 (원근 투영 + 램버트 셰이딩 + 페인터 정렬) ─────────────────────────
// ponytail: 소프트웨어 렌더러(면 ~300개). Pleos 권장 Filament+glTF 모델이 생기면 Scene 구성만 교체.
// 파트 구분은 Pleos glTF 네이밍(Seat_F_L_Top=등받이, Seat_F_L_Low=방석)을 따른다.

private data class V(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V) = V(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V) = V(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = V(x * s, y * s, z * s)
    infix fun dot(o: V) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: V) = V(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun norm(): V = this * (1f / sqrt(this dot this).coerceAtLeast(1e-6f))
}

private class Face(
    val pts: List<V>, val n: V, val color: Color, val alpha: Float,
    val lit: Boolean, val twoSided: Boolean, val seat: SeatId?,
) {
    val center = V(pts.sumOf { it.x.toDouble() }.toFloat() / pts.size, pts.sumOf { it.y.toDouble() }.toFloat() / pts.size, pts.sumOf { it.z.toDouble() }.toFloat() / pts.size)
}

private val BOX_FACES = listOf(
    listOf(0, 1, 2, 3), listOf(4, 5, 6, 7), listOf(0, 4, 7, 3),
    listOf(1, 5, 6, 2), listOf(3, 2, 6, 7), listOf(0, 1, 5, 4),
)

private class Scene {
    val faces = ArrayList<Face>(400)

    fun box(
        c: V, s: V, color: Color, alpha: Float = 1f, lit: Boolean = true, seat: SeatId? = null,
        xf: (V) -> V = { it },
    ) {
        val a = s.x / 2; val b = s.y / 2; val d = s.z / 2
        val w = listOf(V(-a, -b, -d), V(a, -b, -d), V(a, b, -d), V(-a, b, -d), V(-a, -b, d), V(a, -b, d), V(a, b, d), V(-a, b, d))
            .map { xf(c + it) }
        val ctr = xf(c)
        BOX_FACES.forEach { idx ->
            val p = idx.map { w[it] }
            var n = ((p[1] - p[0]) cross (p[2] - p[0])).norm()
            val fc = V((p[0].x + p[2].x) / 2, (p[0].y + p[2].y) / 2, (p[0].z + p[2].z) / 2)
            if ((n dot (fc - ctr)) < 0) n = n * -1f
            faces += Face(p, n, color, alpha, lit, false, seat)
        }
    }

    fun quad(p: List<V>, color: Color, alpha: Float = 1f, lit: Boolean = true) {
        faces += Face(p, ((p[1] - p[0]) cross (p[2] - p[0])).norm(), color, alpha, lit, true, null)
    }
}

private fun rotX(p: V, pivot: V, deg: Float): V {
    val r = Math.toRadians(deg.toDouble())
    val c = cos(r).toFloat(); val s = sin(r).toFloat()
    val d = p - pivot
    return pivot + V(d.x, d.y * c - d.z * s, d.y * s + d.z * c)
}

private class Camera(yaw: Float, pitch: Float, dist: Float, target: V, val w: Float, val h: Float) {
    val pos: V
    val fwd: V
    val right: V
    val up: V
    val f = min(w, h) * 2.3f

    init {
        val y = Math.toRadians(yaw.toDouble()); val p = Math.toRadians(pitch.toDouble())
        pos = target + V((cos(p) * sin(y)).toFloat(), sin(p).toFloat(), (cos(p) * cos(y)).toFloat()) * dist
        fwd = (target - pos).norm()
        right = (V(0f, 1f, 0f) cross fwd).norm()
        up = fwd cross right
    }

    fun depth(p: V) = (p - pos) dot fwd
    fun project(p: V): Offset {
        val d = p - pos
        val zc = (d dot fwd).coerceAtLeast(0.05f)
        return Offset(w / 2 + f * (d dot right) / zc, h / 2 - f * (d dot up) / zc)
    }
}

// ───────────────────────── 차량 씬 (단위: m, x=조수석 방향+, y=위, z=차 앞+) ─────────────────────────

private val BodyWhite = Color(0xFFF2F4F7)
private val TireDark = Color(0xFF2A2F38)
private val Carpet = Color(0xFFCFC8BE)
private val DoorTrim = Color(0xFFD9D2C8)
private val Leather3 = Color(0xFFF3EEE7)
private val Bolster3 = Color(0xFFD9D0C3)
private val Pedestal = Color(0xFF8E97A3)
private val Dash3 = Color(0xFF1F2631)
private val Glass3 = Color(0xFF8FB4E3)

private val SEAT_POS = mapOf(
    SeatId.DRIVER to V(-0.40f, 0f, 0.15f), SeatId.PASSENGER to V(0.40f, 0f, 0.15f),
    SeatId.REAR_LEFT to V(-0.40f, 0f, -0.85f), SeatId.REAR_RIGHT to V(0.40f, 0f, -0.85f),
)
private val WINDOW_Z = mapOf(
    WindowId.FL to (0.05f to 0.85f), WindowId.FR to (0.05f to 0.85f),
    WindowId.RL to (-1.0f to -0.2f), WindowId.RR to (-1.0f to -0.2f),
)

/** 시트의 앞뒤·높이를 반영한 방석 중심 (탭 판정·카메라 포커스에 사용) */
private fun seatAnchor(id: SeatId, a: SeatAnim): V {
    val rear = id == SeatId.REAR_LEFT || id == SeatId.REAR_RIGHT
    val b = SEAT_POS.getValue(id)
    val dz = (a[Axis.FORE_AFT] - 50) / 100f * (if (rear) 0.12f else 0.30f)
    val dy = if (rear) 0f else a[Axis.HEIGHT] / 100f * 0.10f
    return V(b.x, 0.38f + dy, b.z + dz)
}

private fun buildScene(state: CarState, anims: Map<SeatId, SeatAnim>, win: Map<WindowId, Float>, pulse: Float, doors: Boolean): Scene = Scene().apply {
    // 외관: 차체 하부 · 보닛 · 트렁크 · 바퀴
    box(V(0f, -0.08f, 0.05f), V(1.75f, 0.16f, 3.9f), BodyWhite)
    box(V(0f, 0.42f, 1.62f), V(1.7f, 0.36f, 0.75f), BodyWhite)
    box(V(0f, 0.3f, -1.6f), V(1.7f, 0.36f, 0.55f), BodyWhite)
    listOf(-0.86f, 0.86f).forEach { x -> listOf(1.35f, -1.25f).forEach { z -> box(V(x, 0.16f, z), V(0.18f, 0.38f, 0.62f), TireDark) } }

    // 실내: 바닥 · 도어(반투명 컷어웨이) · 루프 레일 · B필러
    box(V(0f, 0.01f, -0.05f), V(1.5f, 0.02f, 2.4f), Carpet)
    // 측면 시점에서는 도어를 걷어낸 컷어웨이로 시트를 보여준다
    if (doors) listOf(-0.8f, 0.8f).forEach { x ->
        box(V(x, 0.31f, -0.08f), V(0.07f, 0.62f, 2.4f), DoorTrim, alpha = 0.55f)
    }
    // 창문 유리: 열린 만큼 도어 속으로 내려간다
    if (doors) WINDOW_Z.forEach { (w, zr) ->
        val x = if (w == WindowId.FL || w == WindowId.RL) -0.8f else 0.8f
        val top = 1.02f - 0.40f * win.getValue(w) / 100f
        if (top > 0.64f) quad(listOf(V(x, 0.62f, zr.first), V(x, 0.62f, zr.second), V(x, top, zr.second), V(x, top, zr.first)), Glass3, 0.35f)
    }
    // 앞유리
    quad(listOf(V(-0.78f, 0.74f, 1.28f), V(0.78f, 0.74f, 1.28f), V(0.72f, 1.04f, 0.86f), V(-0.72f, 1.04f, 0.86f)), Glass3, 0.22f)

    // 대시보드 · 파노라믹 디스플레이 · 스티어링 휠 · 센터 콘솔
    box(V(0f, 0.6f, 1.12f), V(1.56f, 0.28f, 0.34f), Dash3)
    box(V(-0.12f, 0.84f, 0.99f), V(0.95f, 0.16f, 0.03f), Blue, lit = false)
    box(V(-0.38f, 0.68f, 0.92f), V(0.06f, 0.06f, 0.28f), Dash3)
    val hub = V(-0.38f, 0.76f, 0.78f)
    val u = V(1f, 0f, 0f); val v = V(0f, 0.85f, -0.53f)
    for (i in 0 until 16) {
        val a0 = 2 * PI * i / 16; val a1 = 2 * PI * (i + 1) / 16
        fun ring(r: Float, a: Double) = hub + u * (r * cos(a).toFloat()) + v * (r * sin(a).toFloat())
        quad(listOf(ring(0.15f, a0), ring(0.19f, a0), ring(0.19f, a1), ring(0.15f, a1)), Dash3)
    }
    box(hub, V(0.10f, 0.06f, 0.04f), Color(0xFF3A424E))
    box(V(0f, 0.27f, 0.45f), V(0.26f, 0.54f, 1.0f), Color(0xFFC9C2B8))
    box(V(0f, 0.55f, 0.05f), V(0.24f, 0.04f, 0.32f), Color(0xFFB9B1A6))
    box(V(0.135f, 0.40f, 0.5f), V(0.01f, 0.02f, 0.8f), Blue, lit = false)

    // 2열 벤치 받침 · 가운데 좌석
    box(V(0f, 0.18f, -0.85f), V(1.5f, 0.36f, 0.55f), Bolster3)
    box(V(0f, 0.39f, -0.85f), V(0.3f, 0.06f, 0.48f), Leather3)

    SeatId.entries.forEach { id -> seat(id, anims.getValue(id), pulse) }
}

private fun Scene.seat(id: SeatId, a: SeatAnim, pulse: Float) {
    val rear = id == SeatId.REAR_LEFT || id == SeatId.REAR_RIGHT
    val anchor = seatAnchor(id, a)
    val x = anchor.x
    var leather = Leather3
    if (a.heat > 0.05f) leather = lerp(leather, Warm, (0.22f + 0.12f * a.heat) * (0.8f + 0.2f * pulse))
    if (a.vent > 0.05f) leather = lerp(leather, Cool, 0.18f + 0.1f * a.vent)

    // Seat_*_Low: 받침대 · 방석 · 사이드 볼스터
    if (!rear) box(V(x, (anchor.y - 0.06f) / 2, anchor.z), V(0.38f, anchor.y - 0.06f, 0.38f), Pedestal, seat = id)
    box(anchor, V(0.5f, 0.12f, 0.52f), leather, seat = id)
    listOf(-0.22f, 0.22f).forEach { ox -> box(anchor + V(ox, 0.07f, 0f), V(0.07f, 0.05f, 0.5f), Bolster3, seat = id) }

    // Seat_*_Top: 힌지 기준 회전하는 등받이 · 허리 지지 · 헤드레스트
    val hinge = V(x, anchor.y + 0.06f, anchor.z - 0.24f)
    val deg = if (rear) -(12f + 18f * a[Axis.RECLINE] / 100f) else -(8f + 45f * a[Axis.RECLINE] / 100f)
    val rot: (V) -> V = { rotX(it, hinge, deg) }
    box(hinge + V(0f, 0.33f, -0.05f), V(0.5f, 0.66f, 0.12f), leather, seat = id, xf = rot)
    listOf(-0.22f, 0.22f).forEach { ox -> box(hinge + V(ox, 0.3f, 0.03f), V(0.07f, 0.56f, 0.1f), Bolster3, seat = id, xf = rot) }
    val lumbarOut = 0.012f + 0.05f * a[Axis.LUMBAR] / 100f
    val lumbarColor = if (Axis.LUMBAR in a.moving) lerp(Bolster3, Blue, 0.3f + 0.5f * pulse) else Bolster3
    box(hinge + V(0f, 0.2f, lumbarOut), V(0.34f, 0.14f, 0.06f), lumbarColor, seat = id, xf = rot)
    box(hinge + V(0f, 0.77f, -0.04f), V(0.28f, 0.15f, 0.1f), Bolster3, seat = id, xf = rot)
    box(hinge + V(0f, 0.68f, -0.04f), V(0.03f, 0.06f, 0.03f), Pedestal, xf = rot)

    // 탑승자 (lowpoly 마네킹): 등받이를 따라 함께 눕는다
    if (a.occupied) {
        val body = Navy
        box(hinge + V(0f, 0.36f, 0.14f), V(0.36f, 0.44f, 0.18f), body, alpha = 0.5f, xf = rot)
        box(hinge + V(0f, 0.73f, 0.12f), V(0.17f, 0.2f, 0.19f), body, alpha = 0.85f, xf = rot)
        box(anchor + V(0f, 0.12f, 0.08f), V(0.34f, 0.1f, 0.4f), body, alpha = 0.45f)
    }
}

// ───────────────────────── Composable ─────────────────────────

private enum class View3D(val label: String) { ORBIT("3D"), TOP("위"), SIDE("측면") }

/**
 * 실내 3D 시뮬레이션. 드래그로 회전, 프리셋(3D/위/측면)으로 시점 전환, 시트를 누르면 선택.
 * 시트 앞뒤·높이·등받이·허리 지지와 창문 개폐가 실제 형상으로 움직인다.
 */
@Composable
fun Cabin3D(
    state: CarState,
    anims: Map<SeatId, SeatAnim>,
    selected: SeatId,
    onSeatTap: (SeatId) -> Unit,
    modifier: Modifier = Modifier,
    side: Boolean = false,
) {
    val tm = rememberTextMeasurer()
    val tap by rememberUpdatedState(onSeatTap)
    val scope = rememberCoroutineScope()
    val inf = rememberInfiniteTransition(label = "3d")
    val phase by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "phase")
    val win = WindowId.entries.associateWith {
        animateFloatAsState(state.windows.getValue(it).toFloat(), tween(250), label = "w$it").value
    }

    var view by remember { mutableStateOf(if (side) View3D.SIDE else View3D.ORBIT) }
    var reset by remember { mutableIntStateOf(0) } // 같은 프리셋을 다시 눌러도 시점 복귀
    val yaw = remember { Animatable(if (side) 270f else 210f) }
    val pitch = remember { Animatable(if (side) 8f else 38f) }
    val dist = remember { Animatable(if (side) 6.2f else 8.6f) }
    val sel = anims.getValue(selected)
    val focus = seatAnchor(selected, sel)
    val tx = remember { Animatable(if (side) focus.x else 0f) }
    val ty = remember { Animatable(if (side) 0.62f else 0.35f) }
    val tz = remember { Animatable(if (side) focus.z - 0.1f else -0.05f) }
    LaunchedEffect(view, selected, reset) {
        val leftSide = selected == SeatId.DRIVER || selected == SeatId.REAR_LEFT
        val (y, p, d, t) = when (view) {
            View3D.ORBIT -> listOf(210f, 38f, 8.6f, 0f)
            View3D.TOP -> listOf(180f, 86f, 9.2f, 0f)
            View3D.SIDE -> listOf(if (leftSide) 270f else 90f, 8f, 6.2f, 1f)
        }
        val spec = tween<Float>(700)
        launch { yaw.animateTo(y, spec) }
        launch { pitch.animateTo(p, spec) }
        launch { dist.animateTo(d, spec) }
        launch { tx.animateTo(if (t > 0) focus.x else 0f, spec) }
        launch { ty.animateTo(if (t > 0) 0.62f else 0.35f, spec) }
        launch { tz.animateTo(if (t > 0) focus.z - 0.1f else -0.05f, spec) }
    }

    Box(modifier) {
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        scope.launch {
                            yaw.snapTo(yaw.value - drag.x * 0.35f)
                            pitch.snapTo((pitch.value + drag.y * 0.25f).coerceIn(5f, 88f))
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { o ->
                        val cam = Camera(yaw.value, pitch.value, dist.value, V(tx.value, ty.value, tz.value), size.width.toFloat(), size.height.toFloat())
                        SeatId.entries.minByOrNull { (cam.project(seatAnchor(it, anims.getValue(it))) - o).getDistance() }
                            ?.takeIf { (cam.project(seatAnchor(it, anims.getValue(it))) - o).getDistance() < size.width * 0.12f }
                            ?.let(tap)
                    }
                },
        ) {
            val pulse = 0.5f + 0.5f * sin(phase * 2 * PI).toFloat()
            val cam = Camera(yaw.value, pitch.value, dist.value, V(tx.value, ty.value, tz.value), size.width, size.height)
            // 바닥 그림자
            val sh = listOf(V(-1.15f, -0.17f, -2.15f), V(1.15f, -0.17f, -2.15f), V(1.15f, -0.17f, 2.2f), V(-1.15f, -0.17f, 2.2f)).map { cam.project(it) }
            drawPath(Path().apply { moveTo(sh[0].x, sh[0].y); sh.drop(1).forEach { lineTo(it.x, it.y) }; close() }, Color.Black.copy(alpha = 0.07f))
            render(buildScene(state, anims, win, pulse, doors = view != View3D.SIDE), cam, anims, selected, pulse)
            ventParticles(cam, anims, phase)
            if (view != View3D.SIDE) windowLabels(tm, cam, win)
        }
        // 시점 프리셋
        Row(Modifier.align(Alignment.TopEnd).padding(4.dp).clip(RoundedCornerShape(50)).background(Bg).padding(4.dp)) {
            View3D.entries.forEach { v ->
                Text(
                    v.label, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    color = if (view == v) Color.White else Ink,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (view == v) Blue else Color.Transparent)
                        .clickable { view = v; reset++ }.padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
        Text("드래그해서 돌려보세요", fontSize = 14.sp, color = Sub, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp))
    }
}

private fun DrawScope.render(scene: Scene, cam: Camera, anims: Map<SeatId, SeatAnim>, selected: SeatId, pulse: Float) {
    val light = V(-0.35f, 1f, 0.45f).norm()
    val visible = scene.faces.filter { it.twoSided || (it.n dot (cam.pos - it.center)) > 0f }
        .sortedByDescending { cam.depth(it.center) }
    for (f in visible) {
        val pts = f.pts.map { cam.project(it) }
        val path = Path().apply { moveTo(pts[0].x, pts[0].y); for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y); close() }
        val shade = if (f.lit) 0.6f + 0.4f * (abs(f.n dot light)) else 1f
        val c = Color(f.color.red * shade, f.color.green * shade, f.color.blue * shade)
        drawPath(path, c.copy(alpha = f.alpha))
        drawPath(path, Color.Black.copy(alpha = 0.06f * f.alpha), style = Stroke(0.8f))
        val seat = f.seat ?: continue
        val a = anims.getValue(seat)
        when {
            a.moving.isNotEmpty() -> drawPath(path, Blue.copy(alpha = 0.35f + 0.55f * pulse), style = Stroke(2.dp.toPx()))
            seat == selected -> drawPath(path, Blue.copy(alpha = 0.55f), style = Stroke(1.2.dp.toPx()))
        }
    }
}

private fun DrawScope.ventParticles(cam: Camera, anims: Map<SeatId, SeatAnim>, phase: Float) {
    anims.forEach { (id, a) ->
        if (a.vent < 0.05f) return@forEach
        val base = seatAnchor(id, a)
        for (i in 0 until 8) {
            val f = (phase + i / 8f) % 1f
            val p = base + V(-0.18f + 0.36f * ((i * 3) % 8) / 7f, 0.08f + 0.5f * f, -0.15f + 0.3f * ((i * 5) % 8) / 7f)
            drawCircle(Cool.copy(alpha = (a.vent / 3f) * (1 - f)), (3.dp.toPx() * 4f / cam.depth(p).coerceAtLeast(1f)), cam.project(p))
        }
    }
}

private fun DrawScope.windowLabels(tm: TextMeasurer, cam: Camera, win: Map<WindowId, Float>) {
    WINDOW_Z.forEach { (w, zr) ->
        val left = w == WindowId.FL || w == WindowId.RL
        val p = cam.project(V(if (left) -1.4f else 1.4f, 0.7f, (zr.first + zr.second) / 2))
        val v = win.getValue(w).toInt()
        label3d(tm, if (v == 0) "닫힘" else "$v%", p, v > 0, big = true)
        label3d(tm, w.label.removeSuffix(" 창문"), p + Offset(0f, 22.dp.toPx()), false)
    }
    val roof = win.getValue(WindowId.ROOF).toInt()
    label3d(tm, if (roof == 0) "선루프 닫힘" else "선루프 $roof%", cam.project(V(0f, 0.75f, 1.75f)), roof > 0)
}

private fun DrawScope.label3d(tm: TextMeasurer, text: String, at: Offset, active: Boolean, big: Boolean = false) {
    val m = tm.measure(
        text,
        TextStyle(fontSize = if (big) 20.sp else 14.sp, fontWeight = if (big) FontWeight.Bold else FontWeight.Medium, color = if (active) Blue else if (big) Ink else Sub),
    )
    drawText(m, topLeft = Offset(at.x - m.size.width / 2f, at.y - m.size.height / 2f))
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
