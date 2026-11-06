package com.example.archaracters

import android.os.SystemClock
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.CameraNode
import io.github.sceneview.node.ModelNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * التحكم بالشخصيات باللمس (لمس متعدد):
 *  - إصبع واحد على شخصية: يمسكها ويحركها على السطح.
 *  - ضغط مطوّل (بدون حركة): ترتفع الشخصية عن الأرض ثم تتحرك بحرية في الهواء، وعند الترك تسقط.
 *  - عدة أصابع على عدة شخصيات: كل إصبع يمسك شخصية مختلفة في نفس اللحظة.
 *  - إصبعان على نفس الشخصية: تدوير وتكبير/تصغير.
 *  - نقرة سريعة على شخصية: تحدّث معها. نقرة على الفراغ: وضع شخصية جديدة.
 */
class TouchController(
    private val scope: CoroutineScope,
    private val slopPx: Float,
    private val camera: () -> CameraNode?,
    private val nodes: () -> List<ModelNode>,
    private val onTapCharacter: (ModelNode) -> Unit,
    private val onEvent: (Int, ModelNode) -> Unit,
    private val onTapEmpty: (Float, Float) -> Unit,
    private val haptic: (Int) -> Unit
) {
    companion object {
        const val HAPTIC_GRAB = 0
        const val HAPTIC_LIFT = 1
        const val HAPTIC_DROP = 2
        const val HAPTIC_STROKE = 3
        const val HAPTIC_POKE = 4

        const val EV_GRAB = 0
        const val EV_LIFT = 1
        const val EV_LAND = 2
        const val EV_STROKE = 3

        private const val LIFT_MS = 380L        // زمن الضغط المطوّل للرفع
        private const val TAP_MS = 300L         // أقصى زمن للنقرة
        private const val LIFT_H = 0.22f        // ارتفاع الرفع بالمتر
        private const val LIFT_ANIM_MS = 160f
        private const val FALL_MS = 260f
        private const val PICK_R = 0.36f        // نصف قطر اختيار الشخصية (قبل التكبير)
        private const val CENTER_H = 0.3f       // ارتفاع مركز الشخصية فوق قدميها
        private const val MAX_DIST = 20f
    }

    private class R(
        val ox: Float, val oy: Float, val oz: Float,
        val dx: Float, val dy: Float, val dz: Float
    )

    private class Finger(val id: Long, val startX: Float, val startY: Float, val startTime: Long) {
        var x = startX
        var y = startY
        var travelled = false
        var grab: Grab? = null
        var petNode: ModelNode? = null
        var petAcc = 0f
    }

    private class Grab(val node: ModelNode, var primary: Finger) {
        var secondary: Finger? = null
        var lifted = false
        var moved = false
        var liftY = 0f
        var planeY = 0f
        var offX = 0f
        var offY = 0f
        var offZ = 0f
        var pp = FloatArray(3)          // نقطة مستوى الرفع
        var pn = FloatArray(3)          // اتجاه مستوى الرفع (مواجه للكاميرا)
        var baseDist = 1f
        var baseAngle = 0f
        var baseFactor = 1f
        var baseYaw = 0f
        var liftJob: Job? = null
    }

    /** false = تحريك/رفع الشخصيات، true = تحسّس (المسح على الشخصية بدون تحريكها). */
    var petMode = false

    private val petStep get() = slopPx * 5f

    private val fingers = HashMap<Long, Finger>()
    private val grabs = HashMap<ModelNode, Grab>()
    private val home = HashMap<ModelNode, Float>()
    private val baseScale = HashMap<ModelNode, FloatArray>()
    private val factor = HashMap<ModelNode, Float>()
    private val yaw = HashMap<ModelNode, Float>()
    private val falls = HashMap<ModelNode, Job>()
    private var width = 0
    private var height = 0

    fun setSize(w: Int, h: Int) { width = w; height = h }

    /** هل توجد شخصية تحت هذه النقطة؟ (تستخدمها اليد الحقيقية لتقرر الإمساك) */
    fun hitsCharacter(x: Float, y: Float): Boolean = pick(x, y) != null

    fun reset() {
        grabs.values.forEach { it.liftJob?.cancel() }
        falls.values.forEach { it.cancel() }
        fingers.clear(); grabs.clear(); falls.clear()
        home.clear(); baseScale.clear(); factor.clear(); yaw.clear()
    }

    // ───────────── أحداث اللمس ─────────────

    fun down(id: Long, x: Float, y: Float) {
        val f = Finger(id, x, y, SystemClock.uptimeMillis())
        fingers[id] = f
        val node = pick(x, y) ?: return
        if (petMode) {
            ensureBase(node)
            f.petNode = node
            return
        }
        val g = grabs[node]
        if (g != null) {
            if (g.secondary == null) {
                g.secondary = f
                f.grab = g
                beginPinch(g)
            } else {
                f.travelled = true // إصبع ثالث على نفس الشخصية: يُهمل
            }
            return
        }
        startGrab(node, f)
    }

    fun move(id: Long, x: Float, y: Float) {
        val f = fingers[id] ?: return
        val step = hypot(x - f.x, y - f.y)
        f.x = x
        f.y = y
        if (!f.travelled && hypot(x - f.startX, y - f.startY) > slopPx) f.travelled = true
        val pet = f.petNode
        if (pet != null) {
            // تحسّس: كل مسافة معيّنة من المسح فوق الشخصية = ردّ فعل
            if (f.travelled && pick(x, y) === pet) {
                f.petAcc += step
                if (f.petAcc >= petStep) {
                    f.petAcc = 0f
                    onEvent(EV_STROKE, pet)
                }
            }
            return
        }
        val g = f.grab ?: return
        if (g.primary === f) {
            if (f.travelled) g.moved = true
            applyPosition(g)
        }
        if (g.secondary != null) updatePinch(g)
    }

    fun up(id: Long, x: Float, y: Float) {
        val f = fingers.remove(id) ?: return
        val pet = f.petNode
        if (pet != null) {
            if (!f.travelled && SystemClock.uptimeMillis() - f.startTime < TAP_MS) onTapCharacter(pet)
            return
        }
        val g = f.grab
        if (g == null) {
            val dt = SystemClock.uptimeMillis() - f.startTime
            if (!f.travelled && dt < TAP_MS) onTapEmpty(x, y)
            return
        }
        if (g.secondary === f) {
            g.secondary = null
            return
        }
        val sec = g.secondary
        if (sec != null) {
            // رُفع الإصبع الأساسي والثاني ما زال يمسك: يصبح هو الأساسي
            g.secondary = null
            g.primary = sec
            reanchor(g)
            return
        }
        grabs.remove(g.node)
        g.liftJob?.cancel()
        val dt = SystemClock.uptimeMillis() - f.startTime
        if (!g.moved && !g.lifted && dt < TAP_MS) onTapCharacter(g.node)
        val h = home[g.node]
        if (h != null && g.node.worldPosition.y > h + 0.003f) fall(g.node)
    }

    // ───────────── الإمساك والرفع ─────────────

    private fun startGrab(node: ModelNode, f: Finger) {
        falls.remove(node)?.cancel()
        ensureBase(node)
        val g = Grab(node, f)
        g.planeY = node.worldPosition.y
        grabs[node] = g
        f.grab = g
        reanchor(g)
        haptic(HAPTIC_GRAB)
        onEvent(EV_GRAB, node)
        g.liftJob = scope.launch {
            delay(LIFT_MS)
            if (grabs[node] === g && !g.moved && !g.lifted && g.secondary == null) lift(g)
        }
    }

    /** يحفظ الارتفاع الأصلي والحجم الأصلي للشخصية عند أول لمسة. */
    private fun ensureBase(node: ModelNode) {
        if (home.containsKey(node)) return
        home[node] = node.worldPosition.y
        val s = node.scale
        baseScale[node] = floatArrayOf(s.x, s.y, s.z)
    }

    /** يضبط مظهر الشخصية (للحركات التعبيرية فوق الحجم/الدوران الحاليين). */
    fun renderPose(node: ModelNode, sx: Float, sy: Float, yawDelta: Float) {
        ensureBase(node)
        val bs = baseScale[node] ?: return
        val f = factor[node] ?: 1f
        node.scale = Position(bs[0] * f * sx, bs[1] * f * sy, bs[2] * f * sx)
        node.rotation = Rotation(y = (yaw[node] ?: 0f) + yawDelta)
    }

    private fun lift(g: Grab) {
        val cf = rayAt(width / 2f, height / 2f) ?: return
        val p = g.node.worldPosition
        g.pp = floatArrayOf(p.x, p.y, p.z)
        g.pn = floatArrayOf(cf.dx, cf.dy, cf.dz)
        g.lifted = true
        g.liftY = 0f
        reanchor(g)
        haptic(HAPTIC_LIFT)
        onEvent(EV_LIFT, g.node)
        scope.launch {
            val t0 = SystemClock.uptimeMillis()
            while (grabs[g.node] === g) {
                val k = ((SystemClock.uptimeMillis() - t0) / LIFT_ANIM_MS).coerceAtMost(1f)
                g.liftY = LIFT_H * (1f - (1f - k) * (1f - k))
                applyPosition(g)
                if (k >= 1f) break
                delay(16)
            }
        }
    }

    private fun fall(node: ModelNode) {
        val h = home[node] ?: return
        falls[node]?.cancel()
        falls[node] = scope.launch {
            val y0 = node.worldPosition.y
            val t0 = SystemClock.uptimeMillis()
            while (true) {
                val k = ((SystemClock.uptimeMillis() - t0) / FALL_MS).coerceAtMost(1f)
                val p = node.worldPosition
                node.worldPosition = Position(p.x, y0 + (h - y0) * k * k, p.z)
                if (k >= 1f) break
                delay(16)
            }
            haptic(HAPTIC_DROP)
            onEvent(EV_LAND, node)
        }
    }

    // ───────────── التدوير والتكبير بإصبعين ─────────────

    private fun beginPinch(g: Grab) {
        val a = g.primary
        val b = g.secondary ?: return
        g.baseDist = max(hypot(b.x - a.x, b.y - a.y), 1f)
        g.baseAngle = atan2(b.y - a.y, b.x - a.x)
        g.baseFactor = factor[g.node] ?: 1f
        g.baseYaw = yaw[g.node] ?: 0f
        g.moved = true // يلغي الرفع بالضغط المطوّل
    }

    private fun updatePinch(g: Grab) {
        val a = g.primary
        val b = g.secondary ?: return
        val node = g.node
        val bs = baseScale[node] ?: return
        val f = (g.baseFactor * hypot(b.x - a.x, b.y - a.y) / g.baseDist).coerceIn(0.3f, 3f)
        factor[node] = f
        // Position وScale وRotation كلها Float3 في SceneView
        node.scale = Position(bs[0] * f, bs[1] * f, bs[2] * f)
        val dAng = Math.toDegrees((atan2(b.y - a.y, b.x - a.x) - g.baseAngle).toDouble()).toFloat()
        val newYaw = g.baseYaw - dAng
        yaw[node] = newYaw
        node.rotation = Rotation(y = newYaw)
    }

    // ───────────── الهندسة: الأشعة والمستويات ─────────────

    private fun rayAt(x: Float, y: Float): R? {
        val cam = camera() ?: return null
        return try {
            val r = cam.screenPointToRay(x, y)
            val dx = r.direction.x
            val dy = r.direction.y
            val dz = r.direction.z
            val len = sqrt(dx * dx + dy * dy + dz * dz)
            if (len < 1e-6f) null
            else R(r.origin.x, r.origin.y, r.origin.z, dx / len, dy / len, dz / len)
        } catch (e: Exception) {
            null
        }
    }

    private fun hit(
        r: R, px: Float, py: Float, pz: Float, nx: Float, ny: Float, nz: Float
    ): FloatArray? {
        val denom = r.dx * nx + r.dy * ny + r.dz * nz
        if (abs(denom) < 1e-4f) return null
        val t = ((px - r.ox) * nx + (py - r.oy) * ny + (pz - r.oz) * nz) / denom
        if (t < 0f || t > MAX_DIST) return null
        return floatArrayOf(r.ox + r.dx * t, r.oy + r.dy * t, r.oz + r.dz * t)
    }

    private fun planeHit(g: Grab, r: R): FloatArray? =
        if (!g.lifted) hit(r, 0f, g.planeY, 0f, 0f, 1f, 0f)
        else hit(r, g.pp[0], g.pp[1], g.pp[2], g.pn[0], g.pn[1], g.pn[2])

    /** يحسب الإزاحة بين موضع الشخصية ونقطة اللمس حتى لا تقفز الشخصية عند الإمساك. */
    private fun reanchor(g: Grab) {
        val p = g.node.worldPosition
        val h = rayAt(g.primary.x, g.primary.y)?.let { planeHit(g, it) }
        if (h == null) {
            g.offX = 0f; g.offY = 0f; g.offZ = 0f
        } else {
            g.offX = p.x - h[0]
            g.offY = p.y - g.liftY - h[1]
            g.offZ = p.z - h[2]
        }
    }

    private fun applyPosition(g: Grab) {
        val r = rayAt(g.primary.x, g.primary.y) ?: return
        val h = planeHit(g, r) ?: return
        g.node.worldPosition =
            if (!g.lifted) Position(h[0] + g.offX, g.planeY, h[2] + g.offZ)
            else Position(h[0] + g.offX, h[1] + g.offY + g.liftY, h[2] + g.offZ)
    }

    /** أقرب شخصية للشعاع الخارج من نقطة اللمس (الأقرب للكاميرا عند التداخل). */
    private fun pick(x: Float, y: Float): ModelNode? {
        val r = rayAt(x, y) ?: return null
        var best: ModelNode? = null
        var bestT = Float.MAX_VALUE
        for (n in nodes()) {
            val s = factor[n] ?: 1f
            val p = n.worldPosition
            val vx = p.x - r.ox
            val vy = p.y + CENTER_H * s - r.oy
            val vz = p.z - r.oz
            val t = vx * r.dx + vy * r.dy + vz * r.dz
            if (t < 0f) continue
            val qx = vx - r.dx * t
            val qy = vy - r.dy * t
            val qz = vz - r.dz * t
            val rad = PICK_R * s
            if (qx * qx + qy * qy + qz * qz <= rad * rad && t < bestT) {
                best = n
                bestT = t
            }
        }
        return best
    }
}
