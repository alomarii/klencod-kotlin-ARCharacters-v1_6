package com.example.archaracters

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.ar.core.Frame
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.max

/**
 * يد مكتشفة.
 * (x, y): موضع "المقبض" = منتصف طرفي الإبهام والسبابة، بإحداثيات صورة الكاميرا الأصلية المعيارية (0..1).
 * pinch: نسبة المسافة بين الإبهام والسبابة إلى حجم اليد (صغيرة = قرص).
 */
class HandSample(val x: Float, val y: Float, val pinch: Float)

class HandFrame(val time: Long, val hands: List<HandSample>)

/**
 * تتبع اليد الحقيقية بالكاميرا (MediaPipe Hand Landmarker).
 * يأخذ صورة ARCore، يدوّرها لتصبح مستقيمة، يكتشف معالم اليد، ثم يحوّلها إلى موضع المقبض ونسبة القرص.
 * نموذج الكشف (hand_landmarker.task) يُقرأ من assets إن وُضع هناك، وإلا يُنزَّل مرة واحدة ويُحفظ على الجهاز.
 */
class HandTracker(private val context: Context) {
    companion object {
        const val MODEL_FILE = "hand_landmarker.task"
        private const val MODEL_URL =
            "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
        private const val MIN_INTERVAL_MS = 45L   // ~20 صورة/ثانية كحد أقصى
        private const val MAX_SIDE = 800          // أكبر ضلع قبل التصغير للنصف
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var landmarker: HandLandmarker? = null
    private var preparing = false

    @Volatile private var busy = false
    @Volatile private var busySince = 0L
    @Volatile private var bw = 1
    @Volatile private var bh = 1
    @Volatile private var rot = 0
    private var lastSubmit = 0L
    private var lastTs = 0L

    @Volatile
    var latest: HandFrame? = null
        private set

    val isReady: Boolean get() = landmarker != null

    /** يجهّز النموذج (تنزيل أول مرة) ثم المكتشف. الاستدعاءات تعود على الخيط الرئيسي. */
    fun prepare(onStatus: (String) -> Unit, onDone: (Boolean) -> Unit) {
        if (landmarker != null) { onDone(true); return }
        if (preparing) return
        preparing = true
        Thread {
            try {
                val hasAsset = try {
                    context.assets.list("")?.contains(MODEL_FILE) == true
                } catch (e: Exception) {
                    false
                }
                val file = File(context.filesDir, MODEL_FILE)
                if (!hasAsset && !(file.exists() && file.length() > 1_000_000L)) {
                    main.post { onStatus("جارٍ تنزيل نموذج تتبع اليد (حوالي 8 ميغابايت)...") }
                    download(file)
                }
                main.post { onStatus("جارٍ تشغيل تتبع اليد...") }
                val baseBuilder = BaseOptions.builder()
                if (hasAsset) {
                    baseBuilder.setModelAssetPath(MODEL_FILE)
                } else {
                    val buffer = FileInputStream(file).use { fis ->
                        fis.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
                    }
                    baseBuilder.setModelAssetBuffer(buffer)
                }
                val options = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(baseBuilder.build())
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumHands(2)
                    .setMinHandDetectionConfidence(0.5f)
                    .setMinHandPresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .setResultListener { result, _ -> onResult(result) }
                    .setErrorListener { _ -> busy = false }
                    .build()
                landmarker = HandLandmarker.createFromOptions(context, options)
                main.post { onDone(true) }
            } catch (e: Throwable) {
                // لا نترك ملفاً ناقصاً/تالفاً يمنع المحاولة التالية
                try { File(context.filesDir, MODEL_FILE).delete() } catch (_: Exception) {}
                main.post {
                    onStatus("تعذّر تشغيل تتبع اليد: ${e.message ?: e.javaClass.simpleName}")
                    onDone(false)
                }
            } finally {
                preparing = false
            }
        }.start()
    }

    private fun download(target: File) {
        val tmp = File(context.filesDir, "$MODEL_FILE.part")
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
            conn.inputStream.use { input -> tmp.outputStream().use { out -> input.copyTo(out) } }
        } finally {
            conn.disconnect()
        }
        if (tmp.length() < 1_000_000L) {
            tmp.delete()
            throw java.io.IOException("ملف النموذج غير مكتمل")
        }
        if (!tmp.renameTo(target)) throw java.io.IOException("تعذّر حفظ النموذج")
    }

    /** يُستدعى من خيط الإطارات (الرئيسي) مع كل إطار ARCore؛ يتجاهل الإطار إن كان المكتشف مشغولاً. */
    fun analyze(frame: Frame, rotationDegrees: Int) {
        val lm = landmarker ?: return
        val now = SystemClock.uptimeMillis()
        if (busy && now - busySince < 600) return
        if (now - lastSubmit < MIN_INTERVAL_MS) return
        val image = try {
            frame.acquireCameraImage()
        } catch (e: Exception) {
            return // الصورة غير جاهزة بعد
        }
        busy = true
        busySince = now
        lastSubmit = now
        worker.execute {
            try {
                val bmp = try {
                    toRotatedBitmap(image, rotationDegrees)
                } finally {
                    image.close()
                }
                if (bmp == null) {
                    busy = false
                } else {
                    bw = bmp.width
                    bh = bmp.height
                    rot = rotationDegrees
                    val ts = max(SystemClock.uptimeMillis(), lastTs + 1)
                    lastTs = ts
                    lm.detectAsync(BitmapImageBuilder(bmp).build(), ts)
                }
            } catch (e: Throwable) {
                busy = false
            }
        }
    }

    private fun onResult(result: HandLandmarkerResult) {
        val list = ArrayList<HandSample>()
        val w = bw.toFloat()
        val h = bh.toFloat()
        for (lm in result.landmarks()) {
            if (lm.size < 21) continue
            val thumb = lm[4]
            val index = lm[8]
            val wrist = lm[0]
            val mcp = lm[9]
            val size = max(hypot((wrist.x() - mcp.x()) * w, (wrist.y() - mcp.y()) * h), 1f)
            val pinch = hypot((thumb.x() - index.x()) * w, (thumb.y() - index.y()) * h) / size
            val u = (thumb.x() + index.x()) / 2f
            val v = (thumb.y() + index.y()) / 2f
            // من إحداثيات الصورة المُدوَّرة إلى إحداثيات صورة ARCore الأصلية
            val p = when (rot) {
                90 -> Pair(v, 1f - u)
                180 -> Pair(1f - u, 1f - v)
                270 -> Pair(1f - v, u)
                else -> Pair(u, v)
            }
            list.add(HandSample(p.first, p.second, pinch))
        }
        latest = HandFrame(SystemClock.uptimeMillis(), list)
        busy = false
    }

    /** يحوّل صورة YUV_420_888 إلى Bitmap مُدوَّر باتجاه عقارب الساعة (rot = 0/90/180/270) مع تصغير عند اللزوم. */
    private fun toRotatedBitmap(img: Image, rot: Int): Bitmap? {
        val planes = img.planes
        if (planes.size < 3) return null
        val w = img.width
        val h = img.height
        val step = if (max(w, h) > MAX_SIDE) 2 else 1
        val ow = w / step
        val oh = h / step
        val yBuf = planes[0].buffer
        val uBuf = planes[1].buffer
        val vBuf = planes[2].buffer
        val ya = ByteArray(yBuf.remaining()).also { yBuf.get(it) }
        val ua = ByteArray(uBuf.remaining()).also { uBuf.get(it) }
        val va = ByteArray(vBuf.remaining()).also { vBuf.get(it) }
        val yRow = planes[0].rowStride
        val yPix = planes[0].pixelStride
        val uRow = planes[1].rowStride
        val uPix = planes[1].pixelStride
        val vRow = planes[2].rowStride
        val vPix = planes[2].pixelStride
        val out = IntArray(ow * oh)
        for (y in 0 until oh) {
            val sy = y * step
            for (x in 0 until ow) {
                val sx = x * step
                val yy = ya[sy * yRow + sx * yPix].toInt() and 0xFF
                val u = (ua[(sy shr 1) * uRow + (sx shr 1) * uPix].toInt() and 0xFF) - 128
                val v = (va[(sy shr 1) * vRow + (sx shr 1) * vPix].toInt() and 0xFF) - 128
                var r = yy + ((1436 * v) shr 10)
                var g = yy - ((352 * u + 731 * v) shr 10)
                var b = yy + ((1815 * u) shr 10)
                r = if (r < 0) 0 else if (r > 255) 255 else r
                g = if (g < 0) 0 else if (g > 255) 255 else g
                b = if (b < 0) 0 else if (b > 255) 255 else b
                val color = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                val idx = when (rot) {
                    90 -> x * oh + (oh - 1 - y)
                    180 -> (oh - 1 - y) * ow + (ow - 1 - x)
                    270 -> (ow - 1 - x) * oh + y
                    else -> y * ow + x
                }
                out[idx] = color
            }
        }
        val rw = if (rot == 90 || rot == 270) oh else ow
        val rh = if (rot == 90 || rot == 270) ow else oh
        val bmp = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, rw, 0, 0, rw, rh)
        return bmp
    }

    fun close() {
        worker.shutdown()
        try { landmarker?.close() } catch (_: Exception) {}
        landmarker = null
    }
}
