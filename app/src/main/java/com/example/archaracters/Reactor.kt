package com.example.archaracters

import android.os.SystemClock
import io.github.sceneview.node.ModelNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * ردود فعل الشخصية على اللمس: حركة (انضغاط/تمدد/اهتزاز)، اهتزاز الهاتف، وجملة قصيرة.
 * الحركات تُحسب برمجياً فتعمل مع أي نموذج GLB حتى لو لم يحتوِ حركات جاهزة.
 *
 * @param pose يضبط مظهر الشخصية: (الشخصية، مضاعف العرض، مضاعف الارتفاع، دوران إضافي بالدرجات)
 * @param say يعرض/ينطق رد الشخصية: (الشخصية، نوع الحدث، جملة جاهزة)
 */
class Reactor(
    private val scope: CoroutineScope,
    private val pose: (ModelNode, Float, Float, Float) -> Unit,
    private val say: (ModelNode, String, String) -> Unit,
    private val haptic: (Int) -> Unit
) {
    companion object {
        const val POKE = "poke"
        const val STROKE = "stroke"
        const val GRAB = "grab"
        const val LIFT = "lift"
        const val LAND = "land"

        private const val MIN_GAP_MS = 2500L

        private val LINES = mapOf(
            POKE to listOf("أوه! لمستني!", "هههه، مرحباً!", "نعم؟ أنا هنا!", "آه، دغدغتني!"),
            STROKE to listOf("ممم، هذا لطيف جداً", "أحب هذا!", "هههه، يدغدغني!", "لا تتوقف!"),
            GRAB to listOf("ما هذا؟ أمسكتني!", "أوه، أنا بين يديك!"),
            LIFT to listOf("واااو، أنا أطير!", "أنا عالٍ جداً!", "لا تُسقطني!"),
            LAND to listOf("هبوط آمن!", "هههه، كان ممتعاً!", "مرة أخرى!")
        )
    }

    private val jobs = HashMap<ModelNode, Job>()
    private var lastLine = 0L
    private var strokes = 0

    fun reset() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    fun poke(node: ModelNode) {
        haptic(TouchController.HAPTIC_POKE)
        squash(node)
        speak(node, POKE, always = true)
    }

    fun stroke(node: ModelNode) {
        haptic(TouchController.HAPTIC_STROKE)
        wiggle(node)
        strokes++
        speak(node, STROKE, always = strokes % 4 == 1)
    }

    fun grabbed(node: ModelNode) {
        squash(node, strength = 0.6f)
        speak(node, GRAB, always = false)
    }

    fun lifted(node: ModelNode) {
        stretch(node)
        speak(node, LIFT, always = true)
    }

    fun landed(node: ModelNode) {
        squash(node, strength = 1.3f)
        speak(node, LAND, always = false)
    }

    // ───────────── الكلام ─────────────

    private fun speak(node: ModelNode, kind: String, always: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLine < MIN_GAP_MS) return
        if (!always && (now % 2L == 0L)) return // أحياناً بدون كلام حتى لا تكون مزعجة
        lastLine = now
        val line = LINES[kind]?.random() ?: return
        say(node, kind, line)
    }

    // ───────────── الحركات ─────────────

    /** انضغاط ثم ارتداد (نابض متخامد). */
    private fun squash(node: ModelNode, strength: Float = 1f) = play(node, 450L) { t ->
        val e = exp(-5f * t) * cos(14f * t)
        floatArrayOf(1f + 0.14f * strength * e, 1f - 0.28f * strength * e, 0f)
    }

    /** تمدد للأعلى ثم استقرار (عند الرفع). */
    private fun stretch(node: ModelNode) = play(node, 450L) { t ->
        val e = exp(-5f * t) * cos(14f * t)
        floatArrayOf(1f - 0.10f * e, 1f + 0.20f * e, 0f)
    }

    /** اهتزاز جانبي سريع مع نبض خفيف (عند التحسّس). */
    private fun wiggle(node: ModelNode) = play(node, 600L) { t ->
        val decay = exp(-3f * t)
        val s = sin((6 * PI * t).toFloat())
        floatArrayOf(1f + 0.03f * s * decay, 1f - 0.04f * s * decay, 7f * s * decay)
    }

    private fun play(node: ModelNode, durMs: Long, shape: (Float) -> FloatArray) {
        jobs[node]?.cancel()
        jobs[node] = scope.launch {
            val t0 = SystemClock.uptimeMillis()
            while (true) {
                val t = ((SystemClock.uptimeMillis() - t0) / durMs.toFloat()).coerceAtMost(1f)
                val v = shape(t)
                pose(node, v[0], v[1], v[2])
                if (t >= 1f) break
                delay(16)
            }
            pose(node, 1f, 1f, 0f)
        }
    }
}
