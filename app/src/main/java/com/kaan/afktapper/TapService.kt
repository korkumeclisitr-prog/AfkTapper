package com.kaan.afktapper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.TargetApi
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Erişilebilirlik servisi. İki mod:
 *  - Sabit noktalar: sürüklenebilir numaralı daireleri sırayla, belirlenen aralıkla "dokunur".
 *  - AI modu: her adımda ekran görüntüsü alır, Gemini'ye gönderir, dönen hamleyi uygular.
 * TYPE_ACCESSIBILITY_OVERLAY kullanıldığı için ayrıca "üstte göster" izni gerekmez.
 */
class TapService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: TapService? = null
        const val PREFS = "afk"
        const val KEY_INTERVAL = "interval"
        const val KEY_MINUTES = "minutes"
        const val KEY_MODE = "mode"          // "points" | "ai"
        const val KEY_API = "api_key"
        const val KEY_MODEL = "model"
        const val KEY_GOAL = "goal"
        const val KEY_STEP = "step_ms"
        const val DEFAULT_MODEL = "gemini-3.6-flash"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var panel: LinearLayout? = null
    private var playBtn: TextView? = null
    private var statusText: TextView? = null
    private val points = mutableListOf<TextView>()
    private var running = false
    private var aiMode = false
    private var idx = 0

    // AI modu
    private val worker = Executors.newSingleThreadExecutor()
    private val aiHistory = ArrayList<String>()
    private var aiErrors = 0
    private var session = 0

    private val tickRunnable = Runnable { tick() }
    private val aiRunnable = Runnable {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) aiStep()
    }
    private val autoStopRunnable = Runnable {
        stopTapping()
        Toast.makeText(this, "Süre doldu, durduruldu.", Toast.LENGTH_SHORT).show()
    }

    private val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        stopTapping()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        hidePanel()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        hidePanel()
        worker.shutdownNow()
        instance = null
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun params(w: Int, h: Int, x: Int, y: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        baseFlags,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        this.x = x
        this.y = y
    }

    private fun makeDraggable(handle: View, target: View) {
        var startX = 0f
        var startY = 0f
        var origX = 0
        var origY = 0
        handle.setOnTouchListener { _, ev ->
            val p = target.layoutParams as WindowManager.LayoutParams
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = ev.rawX; startY = ev.rawY
                    origX = p.x; origY = p.y
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = origX + (ev.rawX - startX).toInt()
                    p.y = origY + (ev.rawY - startY).toInt()
                    wm?.updateViewLayout(target, p)
                }
            }
            true
        }
    }

    // ---------- Panel ----------

    fun showPanel() {
        if (panel != null) return
        val manager = wm ?: return
        aiMode = prefs().getString(KEY_MODE, "points") == "ai"

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(0xE6202030.toInt())
            }
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val handle = barButton("☰")
        makeDraggable(handle, container)
        bar.addView(handle)

        val play = barButton("▶")
        play.setOnClickListener { if (running) stopTapping() else startTapping() }
        playBtn = play
        bar.addView(play)

        if (!aiMode) {
            val add = barButton("＋")
            add.setOnClickListener { addPoint() }
            bar.addView(add)

            val remove = barButton("－")
            remove.setOnClickListener { removePoint() }
            bar.addView(remove)
        }

        val close = barButton("✕")
        close.setOnClickListener { hidePanel() }
        bar.addView(close)
        container.addView(bar)

        val st = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFFCCCCDD.toInt())
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(240)
            setPadding(dp(8), 0, dp(8), dp(4))
            text = "Hazır. ▶ ile başlat."
            visibility = if (aiMode) View.VISIBLE else View.GONE
        }
        container.addView(st)
        statusText = st

        val m = resources.displayMetrics
        manager.addView(container, params(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            m.widthPixels / 2 - dp(120), dp(80)
        ))
        panel = container
        if (!aiMode) addPoint()
    }

    private fun barButton(label: String) = TextView(this).apply {
        text = label
        textSize = 20f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        minWidth = dp(44)
        minHeight = dp(44)
    }

    private fun setStatus(s: String) {
        statusText?.text = s
    }

    fun hidePanel() {
        stopTapping()
        val manager = wm
        points.forEach { runCatching { manager?.removeView(it) } }
        points.clear()
        panel?.let { runCatching { manager?.removeView(it) } }
        panel = null
        playBtn = null
        statusText = null
    }

    // ---------- Noktalar ----------

    private fun addPoint() {
        if (running) {
            Toast.makeText(this, "Önce durdur.", Toast.LENGTH_SHORT).show()
            return
        }
        if (points.size >= 8) {
            Toast.makeText(this, "En fazla 8 nokta.", Toast.LENGTH_SHORT).show()
            return
        }
        val manager = wm ?: return
        val size = dp(56)
        val tv = TextView(this).apply {
            text = "${points.size + 1}"
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x99FF3B30.toInt())
                setStroke(dp(2), Color.WHITE)
            }
        }
        val m = resources.displayMetrics
        val offset = points.size * dp(14)
        manager.addView(tv, params(size, size,
            m.widthPixels / 2 - size / 2 + offset,
            m.heightPixels / 2 - size / 2 + offset))
        makeDraggable(tv, tv)
        points.add(tv)
    }

    private fun removePoint() {
        if (running) {
            Toast.makeText(this, "Önce durdur.", Toast.LENGTH_SHORT).show()
            return
        }
        val last = points.removeLastOrNull() ?: return
        runCatching { wm?.removeView(last) }
    }

    private fun setPointsTouchable(touchable: Boolean) {
        val manager = wm ?: return
        points.forEach { v ->
            val p = v.layoutParams as WindowManager.LayoutParams
            p.flags = if (touchable) baseFlags
            else baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            v.alpha = if (touchable) 1f else 0.45f
            manager.updateViewLayout(v, p)
        }
    }

    // ---------- Başlat / durdur ----------

    private fun scheduleAutoStop() {
        val minutes = prefs().getLong(KEY_MINUTES, 0L)
        if (minutes > 0) handler.postDelayed(autoStopRunnable, minutes * 60_000L)
    }

    private fun startTapping() {
        if (running) return
        if (aiMode) {
            startAi()
            return
        }
        if (points.isEmpty()) {
            Toast.makeText(this, "Önce ＋ ile nokta ekle.", Toast.LENGTH_SHORT).show()
            return
        }
        running = true
        idx = 0
        playBtn?.text = "⏸"
        setPointsTouchable(false)
        scheduleAutoStop()
        handler.post(tickRunnable)
    }

    private fun stopTapping() {
        if (!running) return
        running = false
        session++
        handler.removeCallbacks(tickRunnable)
        handler.removeCallbacks(aiRunnable)
        handler.removeCallbacks(autoStopRunnable)
        panel?.visibility = View.VISIBLE
        playBtn?.text = "▶"
        setPointsTouchable(true)
        if (aiMode) setStatus("Durduruldu.")
    }

    // ---------- Sabit nokta döngüsü ----------

    private fun tick() {
        if (!running) return
        if (points.isEmpty()) {
            stopTapping()
            return
        }
        val interval = prefs().getLong(KEY_INTERVAL, 500L).coerceAtLeast(50L)

        val v = points[idx % points.size]
        idx++
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val cx = loc[0] + v.width / 2f
        val cy = loc[1] + v.height / 2f

        val path = Path().apply { moveTo(cx, cy) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()

        val next = Runnable { handler.postDelayed(tickRunnable, interval) }
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                next.run()
            }

            override fun onCancelled(g: GestureDescription?) {
                next.run()
            }
        }, null)
        if (!ok) next.run()
    }

    // ---------- AI modu ----------

    private fun startAi() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(this, "AI modu Android 11 veya üstü gerektirir.", Toast.LENGTH_LONG).show()
            return
        }
        if (prefs().getString(KEY_API, "").isNullOrBlank()) {
            Toast.makeText(this, "Önce ana ekrandan Gemini API anahtarını gir.", Toast.LENGTH_LONG).show()
            return
        }
        running = true
        session++
        aiHistory.clear()
        aiErrors = 0
        playBtn?.text = "⏸"
        setStatus("Başlıyor…")
        scheduleAutoStop()
        handler.post(aiRunnable)
    }

    private fun stepDelay() = prefs().getLong(KEY_STEP, 1500L).coerceAtLeast(800L)

    private fun scheduleNextAi() {
        if (running) handler.postDelayed(aiRunnable, stepDelay())
    }

    @TargetApi(Build.VERSION_CODES.R)
    private fun aiStep() {
        if (!running) return
        val mySession = session
        // Paneli ekran görüntüsünde göstermemek için kısa süre gizle.
        panel?.visibility = View.INVISIBLE
        handler.postDelayed({
            if (!running || mySession != session) {
                panel?.visibility = View.VISIBLE
                return@postDelayed
            }
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        panel?.visibility = View.VISIBLE
                        val buffer = result.hardwareBuffer
                        val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        buffer.close()
                        hw?.recycle()
                        if (bmp == null) {
                            aiFail("Ekran görüntüsü okunamadı", false, 5000L)
                        } else {
                            callGemini(bmp, mySession)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        panel?.visibility = View.VISIBLE
                        aiFail("Ekran görüntüsü alınamadı (kod $errorCode)", false, 3000L)
                    }
                }
            )
        }, 150L)
    }

    private fun callGemini(bmp: Bitmap, mySession: Int) {
        val p = prefs()
        val key = p.getString(KEY_API, "").orEmpty().trim()
        val model = p.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty().trim().ifEmpty { DEFAULT_MODEL }
        val goal = p.getString(KEY_GOAL, "").orEmpty().trim()
            .ifEmpty { "Progress in the game: complete objectives, collect rewards, fight enemies." }
        val history = aiHistory.toList()
        val w = bmp.width
        val h = bmp.height
        setStatus("Gemini düşünüyor…")

        worker.execute {
            val result = runCatching { GeminiClient.nextAction(key, model, goal, history, bmp) }
            bmp.recycle()
            handler.post { handleAiResult(result, w, h, mySession) }
        }
    }

    private fun handleAiResult(result: Result<AiAction>, w: Int, h: Int, mySession: Int) {
        if (!running || mySession != session) return
        val err = result.exceptionOrNull()
        if (err != null) {
            val ge = err as? GeminiException
            val msg = ge?.message ?: ("Ağ hatası: " + (err.message ?: err.javaClass.simpleName))
            val fatal = ge != null && ge.code in intArrayOf(400, 401, 403, 404)
            aiFail(msg, fatal, if (ge?.code == 429) 12_000L else 5_000L)
            return
        }
        aiErrors = 0
        performAi(result.getOrThrow(), w, h, mySession)
    }

    private fun aiFail(msg: String, fatal: Boolean, retryDelay: Long) {
        aiErrors++
        if (fatal || aiErrors >= 3) {
            stopTapping()
            setStatus(msg)
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            return
        }
        setStatus("$msg — tekrar denenecek")
        if (running) handler.postDelayed(aiRunnable, retryDelay)
    }

    private fun performAi(a: AiAction, w: Int, h: Int, mySession: Int) {
        val x = a.x.coerceIn(0, 1000) / 1000f * w
        val y = a.y.coerceIn(0, 1000) / 1000f * h

        aiHistory.add("${a.type} (${a.x},${a.y}) ${a.reason}".trim())
        while (aiHistory.size > 6) aiHistory.removeAt(0)
        setStatus("${a.type}: ${a.reason}")

        val path = Path().apply { moveTo(x, y) }
        val duration: Long = when (a.type) {
            "tap" -> 60L
            "long_press" -> 900L
            "swipe" -> {
                if (a.x2 < 0 || a.y2 < 0) {
                    60L
                } else {
                    path.lineTo(a.x2.coerceIn(0, 1000) / 1000f * w, a.y2.coerceIn(0, 1000) / 1000f * h)
                    350L
                }
            }
            else -> {
                scheduleNextAi()
                return
            }
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        val next = Runnable { if (mySession == session) scheduleNextAi() }
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                next.run()
            }

            override fun onCancelled(g: GestureDescription?) {
                next.run()
            }
        }, null)
        if (!ok) next.run()
    }
}
