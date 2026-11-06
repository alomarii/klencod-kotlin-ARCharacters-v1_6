package com.example.archaracters

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.HapticFeedbackConstants
import android.view.Surface
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import io.github.sceneview.Scene
import io.github.sceneview.ar.ARScene
import io.github.sceneview.ar.arcore.createAnchorOrNull
import io.github.sceneview.ar.arcore.isValid
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.ar.rememberARCameraNode
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.CameraNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberView
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

private const val CREDIT = "برمجة وتطوير: محمد العمري  730215825"

// كاميرا الوضع ثلاثي الأبعاد (للأجهزة التي لا تدعم ARCore)
private const val CAM_Y = 0.9f        // ارتفاع الكاميرا بالمتر
private const val CAM_Z = 2.4f        // بُعد الكاميرا عن مركز المشهد
private const val CAM_PITCH = 18f     // ميل الكاميرا للأسفل بالدرجات
private const val FOV_V = 46f         // الزاوية الرأسية التقريبية للكاميرا

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val characters by lazy { Characters.load(this) }
    private val translator = LabelTranslator()
    private val handTracker by lazy { HandTracker(applicationContext) }
    private val analyzer = SceneAnalyzer { en -> translator.translateAll(en) { ar -> onLabels(ar) } }

    // حالة الواجهة
    private var labels by mutableStateOf<List<String>>(emptyList())
    private var reply by mutableStateOf("")
    private var thinking by mutableStateOf(false)
    private var activeName by mutableStateOf<String?>(null)
    private var showSettings by mutableStateOf(false)
    private var handsOn by mutableStateOf(false)      // المستخدم فعّل تتبع اليد
    private var handReady by mutableStateOf(false)     // النموذج جاهز والمكتشف يعمل
    private var handStatus by mutableStateOf("")

    private val histories = mutableMapOf<String, MutableList<Pair<String, String>>>()
    private val nodeNames = HashMap<Node, String>()
    private val models = mutableListOf<ModelNode>()   // العقد القابلة للإمساك
    private val seen = mutableSetOf<String>()
    private var lastAuto = 0L
    private var lastAiReact = 0L
    private var installRequested = false
    private var modeStarted = false

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startMode(true) else {
                Toast.makeText(this, "بدون الكاميرا سيعمل التطبيق بالوضع ثلاثي الأبعاد", Toast.LENGTH_LONG).show()
                startMode(false)
            }
        }

    private val speechLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val text = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!text.isNullOrBlank()) sendUserMessage(text)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashReporter()
        showPreviousCrash()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        prefs = getSharedPreferences("ar_prefs", MODE_PRIVATE)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale("ar"))
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Toast.makeText(this, "ثبّت بيانات الصوت العربي ثم أعد فتح التطبيق", Toast.LENGTH_LONG).show()
                    try { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) } catch (_: Exception) {}
                } else {
                    tts?.setSpeechRate(0.95f)
                    ttsReady = true
                }
            }
        }
        resolveMode()
    }

    override fun onResume() {
        super.onResume()
        // العودة من تثبيت/تحديث خدمات ARCore
        if (!modeStarted && installRequested) resolveMode()
    }

    // ───────────── اختيار الوضع حسب قدرات الجهاز ─────────────

    /** يقرر: واقع معزز (ARCore) إن كان الجهاز يدعمه، وإلا وضع ثلاثي الأبعاد يعمل على أي جهاز. */
    private fun resolveMode() {
        if (modeStarted) return
        val availability = ArCoreApk.getInstance().checkAvailability(this)
        if (availability.isTransient) {
            Handler(Looper.getMainLooper()).postDelayed({ resolveMode() }, 200)
            return
        }
        if (availability == ArCoreApk.Availability.SUPPORTED_INSTALLED) {
            startAr()
            return
        }
        if (availability.isSupported) {
            try {
                val status = ArCoreApk.getInstance().requestInstall(this, !installRequested)
                if (status == ArCoreApk.InstallStatus.INSTALLED) startAr() else installRequested = true
                return
            } catch (e: Exception) {
                // رفض المستخدم التثبيت أو الجهاز غير متوافق ← الوضع ثلاثي الأبعاد
            }
        }
        startMode(false)
    }

    private fun startAr() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startMode(true) else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun startMode(ar: Boolean) {
        if (modeStarted) return
        modeStarted = true
        showApp(ar)
    }

    /** اتجاه حساس الكاميرا الخلفية (معظم الهواتف 90، وبعض التابلت يختلف). */
    private val sensorOrientation: Int by lazy {
        try {
            val cm = getSystemService(CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            }
            id?.let { cm.getCameraCharacteristics(it).get(CameraCharacteristics.SENSOR_ORIENTATION) } ?: 90
        } catch (e: Exception) {
            90
        }
    }

    /** زاوية تدوير صورة الكاميرا لتصبح مستقيمة (ML Kit وتتبع اليد) حسب اتجاه الشاشة واتجاه الحساس. */
    @Suppress("DEPRECATION")
    private fun mlRotation(): Int {
        val display = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return ((sensorOrientation - display) % 360 + 360) % 360
    }

    // ───────────── تحميل المجسمات بصيغ احتياطية ─────────────

    private val flavorDirs = listOf("models", "models_b", "models_c")
    private var goodFlavor: String? = null
    private var fallbackReported = false

    /**
     * يحمّل المجسم من الصيغة الكاملة، وإن رفضها Filament يجرّب صيغاً أبسط (بدون ألوان رؤوس، ثم بدون حركة)،
     * وأخيراً كرة اختبار؛ ويعرض سبب الفشل بدل إغلاق التطبيق.
     */
    private fun <T> loadModelWithFallback(file: String, load: (String) -> T): T {
        val errors = StringBuilder()
        val order = goodFlavor?.let { listOf(it) } ?: flavorDirs
        for (dir in order) {
            try {
                val r = load("$dir/$file")
                if (goodFlavor == null) {
                    goodFlavor = dir
                    if (dir != "models") {
                        Toast.makeText(this, "تم تحميل المجسمات بالصيغة البديلة ($dir)", Toast.LENGTH_LONG).show()
                    }
                }
                return r
            } catch (e: Throwable) {
                errors.append(dir).append(": ").append(e.javaClass.simpleName).append(' ')
                    .append(e.message ?: "").append('\n')
                    .append(shortTrace(e)).append('\n')
            }
        }
        try {
            val r = load("models_d/basic.glb")
            if (!fallbackReported) {
                fallbackReported = true
                showReport("تعذّر تحميل مجسمات الشخصيات (عُرضت كرة اختبار)", deviceInfo() + "\n" + errors)
            }
            return r
        } catch (e: Throwable) {
            errors.append("models_d: ").append(e.javaClass.simpleName).append(' ').append(e.message ?: "")
                .append('\n').append(shortTrace(e)).append('\n').append(probeAsset("models_d/basic.glb"))
            throw IllegalStateException("فشل تحميل كل الصيغ:\n$errors")
        }
    }

    /** أول أسطر تتبّع الخطأ (تكفي لمعرفة السطر داخل المكتبة)، مع السبب الأصلي إن وُجد. */
    private fun shortTrace(e: Throwable): String {
        val sb = StringBuilder()
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 3) {
            if (depth > 0) sb.append("caused by ").append(t.javaClass.name).append(": ").append(t.message ?: "").append('\n')
            t.stackTrace.take(6).forEach { sb.append("  at ").append(it).append('\n') }
            t = t.cause
            depth++
        }
        return sb.toString().trimEnd()
    }

    /** يتأكد أن الملف يُقرأ من assets ويبدأ برأس glTF (للفصل بين فشل القراءة وفشل التحليل). */
    private fun probeAsset(path: String): String = try {
        val b = assets.open(path).use { it.readBytes() }
        "probe $path: ${b.size} bytes, magic=" + String(b.copyOf(4), Charsets.ISO_8859_1) +
            ", abis=" + android.os.Build.SUPPORTED_ABIS.joinToString(",")
    } catch (e: Throwable) {
        "probe $path: ${e.javaClass.simpleName} ${e.message ?: ""}"
    }

    // ───────────── تقرير الأعطال ─────────────

    private fun deviceInfo() =
        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE} / v1.6"

    /** يحفظ سبب أي إغلاق مفاجئ ليُعرض عند التشغيل التالي (لمعرفة الخطأ بالضبط). */
    private fun installCrashReporter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                java.io.File(filesDir, "last_crash.txt")
                    .writeText(deviceInfo() + "\n" + android.util.Log.getStackTraceString(e))
            } catch (_: Throwable) {}
            previous?.uncaughtException(thread, e)
        }
    }

    private fun showPreviousCrash() {
        val f = java.io.File(filesDir, "last_crash.txt")
        if (!f.exists()) return
        val text = try { f.readText() } catch (_: Exception) { "" }
        f.delete()
        if (text.isNotBlank()) showReport("سبب الإغلاق السابق", text)
    }

    /** نافذة تعرض نص الخطأ مع زر نسخ. */
    private fun showReport(title: String, text: String) {
        try {
            val tv = android.widget.TextView(this).apply {
                this.text = text.take(5000)
                textSize = 11f
                setTextIsSelectable(true)
                setPadding(32, 16, 32, 16)
                textDirection = android.view.View.TEXT_DIRECTION_LTR
            }
            val sv = android.widget.ScrollView(this).apply { addView(tv) }
            android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton("نسخ") { _, _ ->
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("error", text))
                    Toast.makeText(this, "تم نسخ الخطأ", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("إغلاق", null)
                .show()
        } catch (_: Throwable) {}
    }

    private fun reportError(title: String, e: Throwable) {
        android.util.Log.e("ARCharacters", title, e)
        showReport(title, deviceInfo() + "\n" + android.util.Log.getStackTraceString(e))
    }

    /** اهتزاز الهاتف حسب نوع الحدث (إمساك، رفع، سقوط، تحسّس، نقر). */
    private fun hapticFeedback(kind: Int) {
        val code = when (kind) {
            TouchController.HAPTIC_GRAB -> HapticFeedbackConstants.VIRTUAL_KEY
            TouchController.HAPTIC_LIFT -> HapticFeedbackConstants.LONG_PRESS
            TouchController.HAPTIC_STROKE -> HapticFeedbackConstants.CLOCK_TICK
            TouchController.HAPTIC_POKE -> HapticFeedbackConstants.KEYBOARD_TAP
            else -> HapticFeedbackConstants.CONTEXT_CLICK
        }
        window.decorView.performHapticFeedback(code)
    }

    /** ردّ الشخصية على اللمس: جملة جاهزة فورية، أو تعليق من Claude (مرة كل 15 ثانية كحد أدنى). */
    private fun reactSay(node: ModelNode, kind: String, line: String) {
        val name = characterOf(node) ?: return
        activeName = name
        val key = prefs.getString("key", "").orEmpty()
        val now = SystemClock.elapsedRealtime()
        val useAi = key.isNotBlank() && !thinking && now - lastAiReact > 15_000 &&
            kind != Reactor.GRAB && kind != Reactor.LAND
        if (useAi) {
            lastAiReact = now
            val what = when (kind) {
                Reactor.POKE -> "نقرك المستخدم بإصبعه"
                Reactor.STROKE -> "يمسح المستخدم عليك بإصبعه بلطف"
                else -> "رفعك المستخدم بيده في الهواء"
            }
            ask(name, "[نظام] $what. علّق بجملة قصيرة جداً وبعفوية.", auto = true)
        } else {
            reply = "$name: $line"
            speak(line)
        }
    }

    /** يحوّل نقطة لمس على الشاشة إلى نقطة على الأرضية الافتراضية (x, z) في الوضع ثلاثي الأبعاد. */
    private fun floorPoint(x: Float, y: Float, size: IntSize): Pair<Float, Float>? {
        if (size.width == 0 || size.height == 0) return null
        val tanV = tan(Math.toRadians(FOV_V / 2.0)).toFloat()
        val tanH = tanV * size.width / size.height
        val dcx = (2f * x / size.width - 1f) * tanH
        val dcy = (1f - 2f * y / size.height) * tanV
        val pitch = Math.toRadians(CAM_PITCH.toDouble())
        val s = sin(pitch).toFloat()
        val c = cos(pitch).toFloat()
        val dy = dcy * c - s
        val dz = -dcy * s - c
        if (dy > -0.02f) return null // اللمس فوق خط الأفق
        val t = -CAM_Y / dy
        return Pair(dcx * t, CAM_Z + dz * t)
    }

    // ───────────── الذكاء والكلام ─────────────

    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "say")
    }

    private fun systemPrompt(name: String): String {
        val persona = characters.firstOrNull { it.name == name }?.persona ?: ""
        val scene = if (labels.isEmpty()) "غير معروف" else labels.joinToString("، ")
        return """
            اسمك: $name.
            $persona
            أنت شخصية افتراضية تظهر في الواقع المعزز عبر كاميرا هاتف المستخدم وتعيش في مكانه الحقيقي.
            الأشياء التي تراها الكاميرا الآن (من نظام تعرف آلي وقد يخطئ): $scene.
            تحدث دائماً باللغة العربية فقط، حتى لو كتب المستخدم بلغة أخرى، وافهم اللهجات العربية وردّ بلهجة قريبة من لهجة المستخدم أو بالفصحى المبسطة. استخدم جملاً قصيرة جداً (جملة أو جملتان) وبأسلوب طبيعي يصلح للنطق الصوتي، بلا رموز تعبيرية ولا تنسيق.
            إذا وصلتك رسالة تبدأ بـ [نظام] فهي ملاحظة آلية وليست من المستخدم، فعلّق عليها بعفوية.
        """.trimIndent()
    }

    private fun ask(name: String, text: String, auto: Boolean) {
        val key = prefs.getString("key", "").orEmpty()
        if (key.isBlank()) {
            if (auto) {
                val l = labels.firstOrNull()
                if (l != null) { reply = "$name: أرى $l"; speak("أرى $l") }
            } else {
                reply = "أضف مفتاح Claude API من الإعدادات ⚙ لأتمكن من الرد"
            }
            return
        }
        val hist = histories.getOrPut(name) { mutableListOf() }
        hist.add("user" to text)
        while (hist.size > 20) hist.removeAt(0)
        thinking = true
        lifecycleScope.launch {
            try {
                val answer = ClaudeClient.chat(key, systemPrompt(name), hist.toList())
                hist.add("assistant" to answer)
                reply = "$name: $answer"
                speak(answer)
            } catch (e: Exception) {
                if (hist.isNotEmpty()) hist.removeAt(hist.lastIndex)
                reply = "تعذر الاتصال: ${e.message}"
            }
            thinking = false
        }
    }

    private fun sendUserMessage(text: String) {
        val name = activeName
        if (name == null) {
            reply = "ضع شخصية في المكان أو المس واحدة لتتحدث معها"
            return
        }
        if (thinking) return
        ask(name, text, auto = false)
    }

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "تحدث الآن")
        }
        try { speechLauncher.launch(intent) } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "التعرف على الصوت غير متوفر، ثبّت تطبيق Google", Toast.LENGTH_LONG).show()
        }
    }

    // ───────────── تفاعل الشخصية مع الواقع (ML Kit) ─────────────

    private fun onLabels(new: List<String>) {
        labels = new
        val name = activeName ?: return
        if (!prefs.getBoolean("auto", true) || thinking) return
        val fresh = new.filter { it !in seen }
        seen.addAll(new)
        if (seen.size > 60) seen.clear()
        val now = SystemClock.elapsedRealtime()
        if (fresh.isNotEmpty() && now - lastAuto > 25_000) {
            lastAuto = now
            ask(name, "[نظام] ظهر أمامك الآن في الكاميرا: ${fresh.joinToString("، ")}. علّق بجملة قصيرة.", auto = true)
        }
    }

    private fun characterOf(node: Node): String? {
        var n: Node? = node
        while (n != null) {
            nodeNames[n]?.let { return it }
            n = n.parent
        }
        return null
    }

    // ───────────── الواجهة ─────────────

    private fun showApp(arMode: Boolean) {
        setContent {
            MaterialTheme {
              CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Box(Modifier.fillMaxSize()) {
                    val engine = rememberEngine()
                    val modelLoader = rememberModelLoader(engine)
                    val view = rememberView(engine)
                    val collisionSystem = rememberCollisionSystem(view)
                    val childNodes = rememberNodes()
                    var frame by remember { mutableStateOf<Frame?>(null) }
                    var trackingOk by remember { mutableStateOf(false) }
                    var count by remember { mutableIntStateOf(0) }
                    var selected by remember { mutableStateOf(characters.firstOrNull()) }
                    var input by remember { mutableStateOf("") }
                    var sceneSize by remember { mutableStateOf(IntSize.Zero) }

                    fun newModel(ch: CharacterInfo): ModelNode {
                        // التحريك والتدوير والتكبير يتولاها TouchController (لمس متعدد)
                        val node = ModelNode(
                            modelInstance = loadModelWithFallback(ch.file) { path -> modelLoader.createModelInstance(path) },
                            scaleToUnits = 0.6f
                        )
                        try { node.playAnimation(0, loop = true) } catch (_: Exception) {}
                        return node
                    }

                    /** نقرة على الفراغ: وضع شخصية جديدة (AR: على سطح حقيقي، 3D: على الأرضية الافتراضية). */
                    fun placeAt(x: Float, y: Float) {
                        val ch = selected
                        if (ch == null) {
                            Toast.makeText(this@MainActivity, "أضف ملفات GLB إلى assets/models", Toast.LENGTH_LONG).show()
                            return
                        }
                        val modelNode: ModelNode
                        if (arMode) {
                            val hit = frame?.hitTest(x, y)
                                ?.firstOrNull { it.isValid(depthPoint = false, point = false) }
                            val anchor = hit?.createAnchorOrNull() ?: return
                            modelNode = try {
                                newModel(ch)
                            } catch (e: Throwable) {
                                try { anchor.detach() } catch (_: Exception) {}
                                throw e
                            }
                            val anchorNode = AnchorNode(engine = engine, anchor = anchor)
                            anchorNode.addChildNode(modelNode)
                            nodeNames[anchorNode] = ch.name
                            childNodes += anchorNode
                        } else {
                            val spot = floorPoint(x, y, sceneSize)
                            if (spot == null) {
                                Toast.makeText(this@MainActivity, "المس الأرضية (الجزء الأسفل من الشاشة)", Toast.LENGTH_SHORT).show()
                                return
                            }
                            modelNode = newModel(ch)
                            modelNode.position = Position(x = spot.first, y = 0f, z = spot.second)
                            childNodes += modelNode
                        }
                        nodeNames[modelNode] = ch.name
                        models += modelNode
                        count++
                        activeName = ch.name
                        ask(ch.name, "[نظام] ظهرتَ للتو أمام المستخدم في غرفته. رحّب به بجملة قصيرة.", auto = true)
                        if (prefs.getString("key", "").isNullOrBlank()) {
                            reply = "${ch.name}: مرحباً بك!"
                            speak("مرحباً بك!")
                        }
                    }

                    val camHolder = remember { arrayOfNulls<CameraNode>(1) }
                    val touchHolder = remember { arrayOfNulls<TouchController>(1) }
                    val slop = LocalViewConfiguration.current.touchSlop
                    var petMode by remember { mutableStateOf(false) }

                    val reactor = remember {
                        Reactor(
                            scope = lifecycleScope,
                            pose = { n, sx, sy, yw -> touchHolder[0]?.renderPose(n, sx, sy, yw) },
                            say = { n, kind, line -> reactSay(n, kind, line) },
                            haptic = { kind -> hapticFeedback(kind) }
                        )
                    }
                    val touch = remember {
                        TouchController(
                            scope = lifecycleScope,
                            slopPx = slop,
                            camera = { camHolder[0] },
                            nodes = { models },
                            onTapCharacter = { n ->
                                characterOf(n)?.let { name ->
                                    activeName = name
                                    reply = "أنت تتحدث الآن مع $name"
                                }
                                reactor.poke(n)
                            },
                            onEvent = { ev, n ->
                                when (ev) {
                                    TouchController.EV_GRAB -> reactor.grabbed(n)
                                    TouchController.EV_LIFT -> reactor.lifted(n)
                                    TouchController.EV_LAND -> reactor.landed(n)
                                    else -> reactor.stroke(n)
                                }
                            },
                            onTapEmpty = { x, y ->
                                try { placeAt(x, y) } catch (e: Throwable) { reportError("تعذّر وضع الشخصية", e) }
                            },
                            haptic = { kind -> hapticFeedback(kind) }
                        ).also { touchHolder[0] = it }
                    }
                    val hands = remember { HandController(touch) }
                    // المشهد لا يستقبل اللمس مباشرة؛ طبقة اللمس أدناه تتحكم بكل شيء
                    val noGestures = rememberOnGestureListener()

                    if (arMode) {
                        val cameraNode = rememberARCameraNode(engine)
                        camHolder[0] = cameraNode
                        ARScene(
                            modifier = Modifier.fillMaxSize(),
                            childNodes = childNodes,
                            engine = engine,
                            view = view,
                            modelLoader = modelLoader,
                            collisionSystem = collisionSystem,
                            cameraNode = cameraNode,
                            sessionConfiguration = { session, config ->
                                config.depthMode =
                                    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC))
                                        Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                                config.instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
                                config.focusMode = Config.FocusMode.AUTO
                                config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                            },
                            onSessionUpdated = { _, updated ->
                                frame = updated
                                trackingOk = updated.camera.trackingState == TrackingState.TRACKING
                                if (trackingOk) analyzer.analyze(updated, mlRotation())
                                if (handsOn && handReady) {
                                    if (trackingOk) handTracker.analyze(updated, mlRotation())
                                    hands.update(updated, handTracker.latest)
                                }
                            },
                            onGestureListener = noGestures
                        )
                    } else {
                        val cameraNode = rememberCameraNode(engine) {
                            position = Position(y = CAM_Y, z = CAM_Z)
                            rotation = Rotation(x = -CAM_PITCH)
                        }
                        camHolder[0] = cameraNode
                        Scene(
                            modifier = Modifier.fillMaxSize(),
                            engine = engine,
                            modelLoader = modelLoader,
                            view = view,
                            collisionSystem = collisionSystem,
                            cameraNode = cameraNode,
                            childNodes = childNodes,
                            cameraManipulator = null,
                            onGestureListener = noGestures
                        )
                    }

                    // طبقة اللمس المتعدد فوق المشهد
                    Box(
                        Modifier
                            .fillMaxSize()
                            .onSizeChanged { sceneSize = it; touch.setSize(it.width, it.height); hands.setSize(it.width, it.height) }
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        for (c in event.changes) {
                                            val id = c.id.value
                                            if (c.changedToDown()) touch.down(id, c.position.x, c.position.y)
                                            else if (c.changedToUp()) touch.up(id, c.position.x, c.position.y)
                                            else if (c.pressed && c.positionChanged()) touch.move(id, c.position.x, c.position.y)
                                            c.consume()
                                        }
                                    }
                                }
                            }
                    )

                    // مؤشرات اليد الحقيقية (لا تستقبل اللمس) — في composable مستقل حتى لا تُعاد رسم الواجهة كلها مع كل إطار
                    if (handsOn && handReady) HandCursors(hands)

                    // الشريط العلوي: الحالة + الأشياء المكتشفة + الإعدادات
                    Row(
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(12.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                        ) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                Text(
                                    when {
                                        !arMode -> "الوضع ثلاثي الأبعاد: المس الأرضية لوضع شخصية ($count)"
                                        !trackingOk -> "حرّك الهاتف ببطء لاكتشاف الأسطح..."
                                        else -> "المس سطحاً لوضع شخصية ($count)"
                                    }
                                )
                                if (labels.isNotEmpty())
                                    Text("👁 ${labels.joinToString("، ")}", style = MaterialTheme.typography.bodySmall)
                                if (handStatus.isNotBlank())
                                    Text("🖐 $handStatus", style = MaterialTheme.typography.bodySmall)
                                if (handsOn && handReady && count > 0)
                                    Text("اقرص الإبهام والسبابة فوق شخصية لإمساكها، ثبّت لترفعها، وافتح يدك لتسقط", style = MaterialTheme.typography.bodySmall)
                                if (count > 0)
                                    Text(
                                        if (petMode) "تحسّس: امسح بإصبعك على الشخصية أو انقرها"
                                        else "اسحب = تحريك • اضغط مطولاً = رفع • إصبعان = تدوير وتكبير",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                Text(CREDIT, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { showSettings = true }) { Text("⚙") }
                            Button(onClick = {
                                if (!arMode) {
                                    Toast.makeText(this@MainActivity, "تتبع اليد يحتاج جهازاً يدعم ARCore (الواقع المعزز)", Toast.LENGTH_LONG).show()
                                } else if (handsOn) {
                                    handsOn = false
                                    hands.releaseAll()
                                    handStatus = ""
                                } else {
                                    handsOn = true
                                    handStatus = "جارٍ التجهيز..."
                                    handTracker.prepare(
                                        onStatus = { handStatus = it },
                                        onDone = { ok ->
                                            handReady = ok
                                            if (ok) handStatus = "تتبع اليد يعمل" else handsOn = false
                                        }
                                    )
                                }
                            }) { Text(if (handsOn) "🖐 ✓" else "🖐") }
                            if (count > 0) {
                                Button(onClick = { petMode = !petMode; touch.petMode = petMode }) {
                                    Text(if (petMode) "👆 تحسّس" else "✋ تحريك")
                                }
                            }
                        }
                    }

                    // الشريط السفلي: اختيار الشخصية + الرد + المحادثة
                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .imePadding()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (thinking || reply.isNotBlank()) {
                            Surface(
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                            ) {
                                Text(
                                    if (thinking) "..." else reply,
                                    modifier = Modifier.padding(12.dp).fillMaxWidth()
                                )
                            }
                        }

                        if (characters.size > 1) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(characters) { c ->
                                    if (c == selected) Button(onClick = { selected = c }) { Text(c.name) }
                                    else OutlinedButton(
                                        onClick = { selected = c },
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                                        )
                                    ) { Text(c.name) }
                                }
                            }
                        }

                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                        ) {
                            Row(
                                Modifier.padding(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                OutlinedTextField(
                                    value = input,
                                    onValueChange = { input = it },
                                    modifier = Modifier.weight(1f),
                                    placeholder = {
                                        Text(activeName?.let { "تحدث مع $it..." } ?: "ضع شخصية أولاً")
                                    },
                                    singleLine = true
                                )
                                TextButton(onClick = { startVoiceInput() }) { Text("🎤") }
                                Button(onClick = {
                                    val t = input.trim()
                                    if (t.isNotEmpty()) { input = ""; sendUserMessage(t) }
                                }) { Text("إرسال") }
                            }
                        }

                        if (count > 0) {
                            OutlinedButton(
                                onClick = {
                                    hands.releaseAll(); touch.reset(); reactor.reset()
                                    val old = childNodes.toList()
                                    models.clear(); childNodes.clear(); nodeNames.clear()
                                    // تحرير الـ anchors والنماذج حتى لا تتسرب الذاكرة
                                    old.forEach { n ->
                                        try { (n as? AnchorNode)?.anchor?.detach() } catch (_: Exception) {}
                                        try { n.destroy() } catch (_: Exception) {}
                                    }
                                    count = 0; activeName = null; reply = ""
                                },
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                                )
                            ) { Text("مسح كل الشخصيات") }
                        }
                    }

                    if (showSettings) SettingsDialog()
                }
              }
            }
        }
    }

    /** يرسم مؤشرات اليد؛ قراءة الحالة تتم أثناء الرسم فقط فلا يحدث إعادة تركيب للواجهة. */
    @Composable
    private fun HandCursors(hands: HandController) {
        Canvas(Modifier.fillMaxSize()) {
            for (c in hands.cursors) {
                val col = when {
                    c.pinching -> Color(0xFF4CAF50)
                    c.over -> Color(0xFFFFC107)
                    else -> Color(0xCCFFFFFF)
                }
                val r = if (c.pinching) 10.dp.toPx() else 16.dp.toPx()
                drawCircle(col, radius = r, center = Offset(c.x, c.y), style = Stroke(width = 3.dp.toPx()))
                drawCircle(col, radius = 4.dp.toPx(), center = Offset(c.x, c.y))
            }
        }
    }

    @Composable
    private fun SettingsDialog() {
        var key by remember { mutableStateOf(prefs.getString("key", "").orEmpty()) }
        var auto by remember { mutableStateOf(prefs.getBoolean("auto", true)) }
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("الإعدادات") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        label = { Text("مفتاح Claude API") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("تعليق الشخصية تلقائياً على ما تراه الكاميرا", Modifier.weight(1f))
                        Switch(checked = auto, onCheckedChange = { auto = it })
                    }
                    Text(CREDIT, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit().putString("key", key.trim()).putBoolean("auto", auto).apply()
                    showSettings = false
                }) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("إلغاء") } }
        )
    }

    override fun onDestroy() {
        tts?.shutdown()
        analyzer.close()
        translator.close()
        handTracker.close()
        super.onDestroy()
    }
}