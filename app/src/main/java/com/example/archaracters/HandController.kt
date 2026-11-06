package com.example.archaracters

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import kotlin.math.hypot
import kotlin.math.min

/** مؤشر اليد على الشاشة (للرسم فوق المشهد). */
data class HandCursor(val x: Float, val y: Float, val pinching: Boolean, val over: Boolean)

/**
 * يحوّل اليد الحقيقية إلى "إصبع افتراضي" يقود TouchController:
 *  - قرص الإبهام والسبابة فوق شخصية = إمساكها وتحريكها (مثل إصبع على الشاشة).
 *  - القرص مع الثبات قليلاً = رفعها في الهواء، وفتح اليد = إسقاطها.
 *  - قرصة سريعة فوق شخصية = نقرة (تتحدث معك).
 * لا يضع شخصيات جديدة من اليد، فقط يمسك الموجود، حتى لا ينتج وضع عشوائي من اهتزاز التتبع.
 */
class HandController(private val touch: TouchController) {
    companion object {
        private const val HAND_BASE = 9_000_000L
        private const val PINCH_ON = 0.30f     // أقل من هذا = قرص
        private const val PINCH_OFF = 0.45f    // أكبر من هذا = فتح (فرق بين الحدّين يمنع الارتجاج)
        private const val LOST_MS = 250L       // اختفاء اليد أطول من هذا = ترك الشخصية
        private const val SMOOTH = 0.6f
    }

    private class Track(val fid: Long, var x: Float, var y: Float) {
        var pinching = false
        var holding = false
        var seen = 0L
        var matched = false
    }

    private val tracks = ArrayList<Track>()
    private var nextId = 0L
    private var lastTime = 0L
    private var width = 0
    private var height = 0

    var cursors by mutableStateOf<List<HandCursor>>(emptyList())
        private set

    fun setSize(w: Int, h: Int) { width = w; height = h }

    /** يُستدعى مع كل إطار ARCore. */
    fun update(frame: Frame, hf: HandFrame?) {
        val now = SystemClock.uptimeMillis()
        if (hf != null && hf.time != lastTime) {
            lastTime = hf.time
            consume(frame, hf, now)
        }
        val iter = tracks.iterator()
        while (iter.hasNext()) {
            val t = iter.next()
            if (now - t.seen > LOST_MS) {
                release(t)
                iter.remove()
            }
        }
        publish()
    }

    fun releaseAll() {
        tracks.forEach { release(it) }
        tracks.clear()
        cursors = emptyList()
    }

    private fun consume(frame: Frame, hf: HandFrame, now: Long) {
        val n = hf.hands.size
        if (n == 0) return
        val inp = FloatArray(n * 2)
        for (i in 0 until n) {
            inp[i * 2] = hf.hands[i].x
            inp[i * 2 + 1] = hf.hands[i].y
        }
        val out = FloatArray(n * 2)
        try {
            frame.transformCoordinates2d(Coordinates2d.IMAGE_NORMALIZED, inp, Coordinates2d.VIEW, out)
        } catch (e: Exception) {
            return
        }
        tracks.forEach { it.matched = false }
        val maxD = 0.3f * (if (width > 0 && height > 0) min(width, height).toFloat() else 400f)
        for (i in 0 until n) {
            val rx = out[i * 2]
            val ry = out[i * 2 + 1]
            var best: Track? = null
            var bd = maxD
            for (t in tracks) {
                if (t.matched) continue
                val d = hypot(t.x - rx, t.y - ry)
                if (d < bd) { best = t; bd = d }
            }
            val t: Track
            if (best == null) {
                t = Track(HAND_BASE + nextId++, rx, ry)
                tracks.add(t)
            } else {
                t = best
                t.x += (rx - t.x) * SMOOTH
                t.y += (ry - t.y) * SMOOTH
            }
            t.matched = true
            t.seen = now
            step(t, hf.hands[i].pinch)
        }
    }

    private fun step(t: Track, pinch: Float) {
        if (!t.pinching && pinch < PINCH_ON) {
            t.pinching = true
            if (touch.hitsCharacter(t.x, t.y)) {
                touch.down(t.fid, t.x, t.y)
                t.holding = true
            }
        } else if (t.pinching && pinch > PINCH_OFF) {
            t.pinching = false
            if (t.holding) {
                touch.up(t.fid, t.x, t.y)
                t.holding = false
            }
        } else if (t.holding) {
            touch.move(t.fid, t.x, t.y)
        }
    }

    private fun release(t: Track) {
        if (t.holding) {
            touch.up(t.fid, t.x, t.y)
            t.holding = false
        }
        t.pinching = false
    }

    private fun publish() {
        cursors = tracks.map { HandCursor(it.x, it.y, it.pinching, touch.hitsCharacter(it.x, it.y)) }
    }
}
