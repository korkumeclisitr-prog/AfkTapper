package com.kaan.afktapper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Erişilebilirlik servisi: ekranda sürüklenebilir numaralı daireler ve küçük bir kontrol
 * paneli gösterir. Başlatılınca daireleri sırayla, belirlenen aralıkla "dokunur".
 * TYPE_ACCESSIBILITY_OVERLAY kullanıldığı için ayrıca "üstte göster" izni gerekmez.
 */
class TapService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: TapService? = null
        const val PREFS = "afk"
        const val KEY_INTERVAL = "interval"
        const val KEY_MINUTES = "minutes"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var panel: LinearLayout? = null
    private var playBtn: TextView? = null
    private val points = mutableListOf<TextView>()
    private var running = false
    private var idx = 0

    private val tickRunnable = Runnable { tick() }
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
        instance = null
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

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

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(0xE6202030.toInt())
            }
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }

        val handle = barButton("☰")
        makeDraggable(handle, bar)
        bar.addView(handle)

        val play = barButton("▶")
        play.setOnClickListener { if (running) stopTapping() else startTapping() }
        playBtn = play
        bar.addView(play)

        val add = barButton("＋")
        add.setOnClickListener { addPoint() }
        bar.addView(add)

        val remove = barButton("－")
        remove.setOnClickListener { removePoint() }
        bar.addView(remove)

        val close = barButton("✕")
        close.setOnClickListener { hidePanel() }
        bar.addView(close)

        val m = resources.displayMetrics
        manager.addView(bar, params(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            m.widthPixels / 2 - dp(120), dp(80)
        ))
        panel = bar
        addPoint()
    }

    private fun barButton(label: String) = TextView(this).apply {
        text = label
        textSize = 20f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        minWidth = dp(44)
        minHeight = dp(44)
    }

    fun hidePanel() {
        stopTapping()
        val manager = wm
        points.forEach { runCatching { manager?.removeView(it) } }
        points.clear()
        panel?.let { runCatching { manager?.removeView(it) } }
        panel = null
        playBtn = null
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

    // ---------- Tıklama döngüsü ----------

    private fun startTapping() {
        if (running) return
        if (points.isEmpty()) {
            Toast.makeText(this, "Önce ＋ ile nokta ekle.", Toast.LENGTH_SHORT).show()
            return
        }
        running = true
        idx = 0
        playBtn?.text = "⏸"
        setPointsTouchable(false)

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val minutes = prefs.getLong(KEY_MINUTES, 0L)
        if (minutes > 0) handler.postDelayed(autoStopRunnable, minutes * 60_000L)

        handler.post(tickRunnable)
    }

    private fun stopTapping() {
        if (!running) return
        running = false
        handler.removeCallbacks(tickRunnable)
        handler.removeCallbacks(autoStopRunnable)
        playBtn?.text = "▶"
        setPointsTouchable(true)
    }

    private fun tick() {
        if (!running) return
        if (points.isEmpty()) {
            stopTapping()
            return
        }
        val interval = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_INTERVAL, 500L).coerceAtLeast(50L)

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
}
