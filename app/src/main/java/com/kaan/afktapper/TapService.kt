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
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Erişilebilirlik servisi. Modlar:
 *  - Sabit noktalar: sürüklenebilir numaralı daireleri sırayla "dokunur".
 *  - AI / genel: ekran görüntüsünü Gemini'ye gönderir, dönen hamleyi uygular.
 *  - AI / Block Blast: Gemini sadece tahtayı ve parçaları okur; en iyi yerleşimi
 *    cihazdaki çözücü ([BlockSolver]) seçer, parçalar sürükleyerek yerleştirilir.
 * Kontrol paneli, ekranda istenen yere sürüklenebilen bir baloncuktur (dokununca açılır/kapanır).
 * TYPE_ACCESSIBILITY_OVERLAY kullanıldığı için ayrıca "üstte göster" izni gerekmez.
 */
class TapService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: TapService? = null
        const val PREFS = "afk"
        const val KEY_INTERVAL = "interval"
        const val KEY_MINUTES = "minutes"
        const val KEY_MODE = "mode"            // "points" | "ai"
        const val KEY_GAME = "game"            // "general" | "blocks"
        const val KEY_API = "api_key"
        const val KEY_MODEL = "model"
        const val KEY_PUZ_MODEL = "puzzle_model"
        const val KEY_GOAL = "goal"
        const val KEY_STEP = "step_ms"
        const val KEY_ACTIONS = "max_actions"
        const val KEY_DRAG_OFF = "drag_offset" // hücre yüksekliğinin yüzdesi, + = aşağı
        const val KEY_BX = "bubble_x"
        const val KEY_BY = "bubble_y"
        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
        const val DEFAULT_PUZ_MODEL = "gemini-3.6-flash"
    }

    private class DragMove(val sx: Float, val sy: Float, val tx: Float, val ty: Float, val desc: String)

    private sealed class PuzzleOutcome {
        class Screen(val actions: List<AiAction>) : PuzzleOutcome()
        class Moves(val moves: List<DragMove>, val lines: Int, val pieceCount: Int) : PuzzleOutcome()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var panel: LinearLayout? = null
    private var bubble: TextView? = null
    private var controls: LinearLayout? = null
    private var playBtn: TextView? = null
    private var statusText: TextView? = null
    private val points = mutableListOf<TextView>()
    private var running = false
    private var expanded = true
    private var aiMode = false
    private var blocksMode = false
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

    private fun dp(v: Int) = Ui.dp(this, v)

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

    /**
     * [handle]'a dokunup sürüklenince [target] penceresi taşınır. Kısa dokunuş [onTap],
     * sürükleme bitince [onDropped] çağrılır.
     */
    private fun attachDrag(
        handle: View,
        target: View,
        onTap: (() -> Unit)? = null,
        onDropped: (() -> Unit)? = null
    ) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0f
        var startY = 0f
        var origX = 0
        var origY = 0
        var moved = false
        handle.setOnTouchListener { _, ev ->
            val p = target.layoutParams as? WindowManager.LayoutParams
            if (p == null) {
                false
            } else {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = ev.rawX; startY = ev.rawY
                        origX = p.x; origY = p.y
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - startX
                        val dy = ev.rawY - startY
                        if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                        if (moved) {
                            val m = resources.displayMetrics
                            p.x = (origX + dx).toInt().coerceIn(0, maxOf(0, m.widthPixels - target.width))
                            p.y = (origY + dy).toInt().coerceIn(0, maxOf(0, m.heightPixels - target.height))
                            wm?.updateViewLayout(target, p)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) onDropped?.invoke() else onTap?.invoke()
                    }
                }
                true
            }
        }
    }

    // ---------- Baloncuk panel ----------

    fun showPanel() {
        if (panel != null) return
        val manager = wm ?: return
        val p = prefs()
        aiMode = p.getString(KEY_MODE, "points") == "ai"
        blocksMode = aiMode && p.getString(KEY_GAME, "general") == "blocks"
        expanded = true

        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val b = TextView(this).apply {
            text = "👆"
            textSize = 22f
            gravity = Gravity.CENTER
        }
        row.addView(b, LinearLayout.LayoutParams(dp(48), dp(48)))
        bubble = b

        val ctrls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(2), 0)
        }
        val play = circleButton("▶")
        play.setOnClickListener { if (running) stopTapping() else startTapping() }
        playBtn = play
        ctrls.addView(play, buttonParams())

        if (!aiMode) {
            val add = circleButton("＋")
            add.setOnClickListener { addPoint() }
            ctrls.addView(add, buttonParams())
            val remove = circleButton("－")
            remove.setOnClickListener { removePoint() }
            ctrls.addView(remove, buttonParams())
        }

        val close = circleButton("✕")
        close.setOnClickListener { hidePanel() }
        ctrls.addView(close, buttonParams())
        row.addView(ctrls)
        controls = ctrls
        c.addView(row)

        val st = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFFCFD3EE.toInt())
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(230)
            setPadding(dp(8), 0, dp(8), dp(4))
            text = "Hazır. ▶ ile başlat."
        }
        c.addView(st)
        statusText = st

        // Baloncuğa dokun = aç/kapat, sürükle = taşı. Panelin boş yerlerinden de sürüklenir.
        attachDrag(b, c, onTap = { toggleExpanded() }, onDropped = { savePosition(c) })
        attachDrag(c, c, onDropped = { savePosition(c) })

        val m = resources.displayMetrics
        val x = p.getInt(KEY_BX, m.widthPixels / 2 - dp(120)).coerceIn(0, maxOf(0, m.widthPixels - dp(60)))
        val y = p.getInt(KEY_BY, dp(120)).coerceIn(0, maxOf(0, m.heightPixels - dp(60)))
        manager.addView(
            c, params(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                x, y
            )
        )
        panel = c
        applyExpanded()
        updateBubble()
        if (!aiMode) addPoint()
    }

    private fun circleButton(label: String) = TextView(this).apply {
        text = label
        textSize = 17f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = Ui.circle(0x33FFFFFF)
        isClickable = true
    }

    private fun buttonParams() = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
        marginStart = dp(4)
    }

    private fun toggleExpanded() {
        expanded = !expanded
        applyExpanded()
    }

    private fun applyExpanded() {
        val c = panel ?: return
        controls?.visibility = if (expanded) View.VISIBLE else View.GONE
        statusText?.visibility = if (expanded && aiMode) View.VISIBLE else View.GONE
        c.background = if (expanded) Ui.rounded(this, 0xEE171829.toInt(), 30) else null
        c.post { clampPanel() }
    }

    /** Açılınca ekranın dışına taşarsa içeri çeker. */
    private fun clampPanel() {
        val c = panel ?: return
        val p = c.layoutParams as? WindowManager.LayoutParams ?: return
        val m = resources.displayMetrics
        val nx = p.x.coerceIn(0, maxOf(0, m.widthPixels - c.width))
        val ny = p.y.coerceIn(0, maxOf(0, m.heightPixels - c.height))
        if (nx != p.x || ny != p.y) {
            p.x = nx
            p.y = ny
            runCatching { wm?.updateViewLayout(c, p) }
        }
    }

    private fun savePosition(c: View) {
        val p = c.layoutParams as? WindowManager.LayoutParams ?: return
        prefs().edit().putInt(KEY_BX, p.x).putInt(KEY_BY, p.y).apply()
    }

    private fun updateBubble() {
        bubble?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (running) Ui.GOOD else Ui.ACCENT)
            setStroke(dp(2), 0xCCFFFFFF.toInt())
        }
        playBtn?.text = if (running) "⏸" else "▶"
    }

    private fun setStatus(s: String) {
        statusText?.text = s
    }

    /** Hareket sırasında paneli dokunuşa kapatır (sürükleme ona çarpmasın diye). */
    private fun setPanelTouchable(touchable: Boolean) {
        val c = panel ?: return
        val p = c.layoutParams as? WindowManager.LayoutParams ?: return
        val want = if (touchable) baseFlags else baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (p.flags != want) {
            p.flags = want
            runCatching { wm?.updateViewLayout(c, p) }
        }
    }

    fun hidePanel() {
        stopTapping()
        val manager = wm
        points.forEach { runCatching { manager?.removeView(it) } }
        points.clear()
        panel?.let { runCatching { manager?.removeView(it) } }
        panel = null
        bubble = null
        controls = null
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
        manager.addView(
            tv, params(
                size, size,
                m.widthPixels / 2 - size / 2 + offset,
                m.heightPixels / 2 - size / 2 + offset
            )
        )
        attachDrag(tv, tv)
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
        updateBubble()
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
        setPanelTouchable(true)
        updateBubble()
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
        updateBubble()
        setStatus("Başlıyor…")
        scheduleAutoStop()
        handler.post(aiRunnable)
    }

    private fun stepDelay() = prefs().getLong(KEY_STEP, 700L).coerceAtLeast(300L)

    private fun scheduleNextAi(extra: Long = 0L) {
        if (running) handler.postDelayed(aiRunnable, stepDelay() + extra)
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
                        } else if (blocksMode) {
                            callPuzzle(bmp, mySession)
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

    private fun handleError(err: Throwable) {
        val ge = err as? GeminiException
        val msg = ge?.message ?: ("Ağ hatası: " + (err.message ?: err.javaClass.simpleName))
        val fatal = ge != null && ge.code in intArrayOf(400, 401, 403, 404)
        aiFail(msg, fatal, if (ge?.code == 429) 12_000L else 5_000L)
    }

    private fun remember(entry: String) {
        aiHistory.add(entry)
        while (aiHistory.size > 6) aiHistory.removeAt(0)
    }

    // ----- Genel mod -----

    private fun callGemini(bmp: Bitmap, mySession: Int) {
        val p = prefs()
        val key = p.getString(KEY_API, "").orEmpty().trim()
        val model = p.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty().trim().ifEmpty { DEFAULT_MODEL }
        val goal = p.getString(KEY_GOAL, "").orEmpty().trim()
            .ifEmpty { "Progress in the game: complete objectives, collect rewards, fight enemies." }
        val maxActions = p.getInt(KEY_ACTIONS, 3).coerceIn(1, 5)
        val history = aiHistory.toList()
        val w = bmp.width
        val h = bmp.height
        setStatus("Gemini bakıyor…")

        worker.execute {
            val result = runCatching {
                try {
                    GeminiClient.nextActions(key, model, goal, history, bmp, maxActions)
                } finally {
                    bmp.recycle()
                }
            }
            handler.post { handleAiResult(result, w, h, mySession) }
        }
    }

    private fun handleAiResult(result: Result<List<AiAction>>, w: Int, h: Int, mySession: Int) {
        if (!running || mySession != session) return
        val err = result.exceptionOrNull()
        if (err != null) {
            handleError(err)
            return
        }
        aiErrors = 0
        performAiActions(result.getOrThrow(), 0, w, h, mySession)
    }

    /** Gemini'nin döndürdüğü hamleleri sırayla uygular; hepsi bitince yeni ekran görüntüsü alır. */
    private fun performAiActions(list: List<AiAction>, i: Int, w: Int, h: Int, mySession: Int) {
        if (!running || mySession != session) return
        if (i >= list.size) {
            scheduleNextAi()
            return
        }
        val a = list[i]
        remember("${a.type} (${a.x},${a.y}) ${a.reason}".trim())
        setStatus("${a.type}: ${a.reason}")
        if (a.type == "wait") {
            scheduleNextAi()
            return
        }

        val x = a.x.coerceIn(0, 1000) / 1000f * w
        val y = a.y.coerceIn(0, 1000) / 1000f * h
        var x2: Float? = null
        var y2: Float? = null
        val duration: Long = when (a.type) {
            "long_press" -> 900L
            "swipe", "drag" -> {
                if (a.x2 >= 0 && a.y2 >= 0) {
                    x2 = a.x2.coerceIn(0, 1000) / 1000f * w
                    y2 = a.y2.coerceIn(0, 1000) / 1000f * h
                    400L
                } else {
                    60L
                }
            }
            else -> 60L
        }
        gesture(x, y, x2, y2, duration, mySession) {
            handler.postDelayed({ performAiActions(list, i + 1, w, h, mySession) }, 300L)
        }
    }

    // ----- Block Blast / bulmaca modu -----

    private fun callPuzzle(bmp: Bitmap, mySession: Int) {
        val p = prefs()
        val key = p.getString(KEY_API, "").orEmpty().trim()
        val model = p.getString(KEY_PUZ_MODEL, DEFAULT_PUZ_MODEL).orEmpty().trim()
            .ifEmpty { DEFAULT_PUZ_MODEL }
        val dragOffsetPct = p.getInt(KEY_DRAG_OFF, 0)
        val w = bmp.width
        val h = bmp.height
        setStatus("Tahta okunuyor…")

        worker.execute {
            val result = runCatching {
                val read = try {
                    GeminiClient.readPuzzle(key, model, bmp)
                } finally {
                    bmp.recycle()
                }
                when (read) {
                    is PuzzleRead.Other -> PuzzleOutcome.Screen(read.actions)
                    is PuzzleRead.Game -> planMoves(read, w, h, dragOffsetPct)
                }
            }
            handler.post { handlePuzzle(result, w, h, mySession) }
        }
    }

    /** Çözücüyü çalıştırır, yerleşimleri ekran pikseli sürüklemelerine çevirir. */
    private fun planMoves(read: PuzzleRead.Game, w: Int, h: Int, dragOffsetPct: Int): PuzzleOutcome.Moves {
        val rows = read.cells.size
        val cols = read.cells[0].size
        val plan = BlockSolver.plan(read.cells, read.pieces.map { it.shape })
        val cellHeightPx = (read.bottom - read.top) / rows / 1000f * h
        val offsetY = dragOffsetPct / 100f * cellHeightPx
        val moves = plan.placements.map { pl ->
            val piece = read.pieces[pl.pieceIndex]
            val tx = PuzzleGeometry.targetX(read.left, read.right, cols, pl.col, piece.cols) / 1000f * w
            val ty = PuzzleGeometry.targetY(read.top, read.bottom, rows, pl.row, piece.rows) / 1000f * h + offsetY
            DragMove(
                piece.cx / 1000f * w,
                piece.cy / 1000f * h,
                tx,
                ty,
                "satır ${pl.row + 1}, sütun ${pl.col + 1}"
            )
        }
        return PuzzleOutcome.Moves(moves, plan.lines, read.pieces.size)
    }

    private fun handlePuzzle(result: Result<PuzzleOutcome>, w: Int, h: Int, mySession: Int) {
        if (!running || mySession != session) return
        val err = result.exceptionOrNull()
        if (err != null) {
            handleError(err)
            return
        }
        when (val o = result.getOrThrow()) {
            is PuzzleOutcome.Screen -> {
                aiErrors = 0
                performAiActions(o.actions, 0, w, h, mySession)
            }
            is PuzzleOutcome.Moves -> {
                if (o.moves.isEmpty()) {
                    if (o.pieceCount == 0) {
                        aiFail("Parçalar görünmüyor", false, 1500L)
                    } else {
                        aiFail("Uygun hamle bulunamadı", false, 1500L)
                    }
                } else {
                    aiErrors = 0
                    setStatus("${o.moves.size} parça yerleştiriliyor (${o.lines} çizgi temizlenecek)")
                    runDrags(o.moves, 0, mySession)
                }
            }
        }
    }

    private fun runDrags(list: List<DragMove>, i: Int, mySession: Int) {
        if (!running || mySession != session) return
        if (i >= list.size) {
            // Temizleme animasyonu ve yeni parçalar için biraz fazla bekle.
            scheduleNextAi(900L)
            return
        }
        val m = list[i]
        remember("blocks: parça ${i + 1} → ${m.desc}")
        setStatus("Parça ${i + 1}/${list.size}: ${m.desc}")
        gesture(m.sx, m.sy, m.tx, m.ty, 700L, mySession) {
            handler.postDelayed({ runDrags(list, i + 1, mySession) }, 400L)
        }
    }

    // ----- Ortak hareket -----

    /** (x1,y1)'e dokunur; x2/y2 verilmişse oraya sürükler. Bitince [done] (oturum hâlâ geçerliyse). */
    private fun gesture(
        x1: Float,
        y1: Float,
        x2: Float?,
        y2: Float?,
        duration: Long,
        mySession: Int,
        done: () -> Unit
    ) {
        val m = resources.displayMetrics
        val maxX = m.widthPixels.toFloat()
        val maxY = m.heightPixels.toFloat()
        val path = Path().apply {
            moveTo(x1.coerceIn(0f, maxX), y1.coerceIn(0f, maxY))
            if (x2 != null && y2 != null) lineTo(x2.coerceIn(0f, maxX), y2.coerceIn(0f, maxY))
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        setPanelTouchable(false)
        val finish = Runnable {
            setPanelTouchable(true)
            if (mySession == session) done()
        }
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) {
                finish.run()
            }

            override fun onCancelled(d: GestureDescription?) {
                finish.run()
            }
        }, null)
        if (!ok) finish.run()
    }
}
