package com.kaan.afktapper

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var modePoints: RadioButton
    private lateinit var modeAi: RadioButton
    private lateinit var pointsBox: LinearLayout
    private lateinit var aiBox: LinearLayout
    private lateinit var interval: EditText
    private lateinit var minutes: EditText
    private lateinit var apiKey: EditText
    private lateinit var model: EditText
    private lateinit var goal: EditText
    private lateinit var step: EditText

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(TapService.PREFS, Context.MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(48), dp(20), dp(24))
        }

        root.addView(TextView(this).apply {
            text = "AFK Tapper"
            textSize = 28f
            setTextColor(Color.BLACK)
        })
        root.addView(TextView(this).apply {
            text = "Oyunda senin yerine ekrana dokunur. İstersen Gemini oyunu görüp oynar."
            textSize = 15f
            setPadding(0, dp(4), 0, dp(16))
        })

        status = TextView(this).apply {
            textSize = 16f
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(status)

        root.addView(Button(this).apply {
            text = "1) Erişilebilirlik ayarlarını aç"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })
        root.addView(TextView(this).apply {
            text = "Listeden “AFK Tapper”ı bulup aç. Anahtar gri ve basılamıyorsa: " +
                "Ayarlar → Uygulamalar → AFK Tapper → sağ üst ⋮ → " +
                "“Kısıtlanmış ayarlara izin ver”, sonra tekrar dene. " +
                "Uygulamayı güncellediysen servisi bir kez kapatıp aç."
            textSize = 13f
            setPadding(0, dp(4), 0, dp(16))
        })

        // ---- Mod seçimi ----
        root.addView(label("Mod"))
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        modePoints = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Sabit noktalar (basit tıklayıcı)"
        }
        modeAi = RadioButton(this).apply {
            id = View.generateViewId()
            text = "AI modu (Gemini oyunu görüp oynar)"
        }
        group.addView(modePoints)
        group.addView(modeAi)
        root.addView(group)
        if (prefs.getString(TapService.KEY_MODE, "points") == "ai") {
            modeAi.isChecked = true
        } else {
            modePoints.isChecked = true
        }
        group.setOnCheckedChangeListener { _, _ -> updateModeUi() }

        // ---- Sabit nokta ayarları ----
        pointsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pointsBox.addView(label("Tıklama aralığı (milisaniye, en az 50)"))
        interval = numberField(prefs.getLong(TapService.KEY_INTERVAL, 500L).toString())
        pointsBox.addView(interval)
        root.addView(pointsBox)

        // ---- AI ayarları ----
        aiBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        aiBox.addView(TextView(this).apply {
            autoLinkMask = Linkify.WEB_URLS
            text = "Gemini API anahtarını buradan alabilirsin: https://aistudio.google.com/apikey " +
                "(anahtar sadece bu cihazda saklanır; kota ve ücret senin hesabına aittir). " +
                "Android 11 veya üstü gerekir."
            textSize = 13f
            setPadding(0, dp(8), 0, 0)
        })
        aiBox.addView(label("Gemini API anahtarı"))
        apiKey = textField(
            prefs.getString(TapService.KEY_API, "").orEmpty(),
            "AIza…",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        aiBox.addView(apiKey)
        aiBox.addView(label("Model (hata verirse ai.google.dev/gemini-api/docs/models'tan güncelle)"))
        model = textField(
            prefs.getString(TapService.KEY_MODEL, TapService.DEFAULT_MODEL).orEmpty(),
            TapService.DEFAULT_MODEL,
            InputType.TYPE_CLASS_TEXT
        )
        aiBox.addView(model)
        aiBox.addView(label("Oyundaki hedefin (Gemini'ye talimat)"))
        goal = textField(
            prefs.getString(TapService.KEY_GOAL, "").orEmpty(),
            "Örn: Savaşlara gir, ödülleri topla, görev butonlarına bas",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        ).apply { minLines = 2 }
        aiBox.addView(goal)
        aiBox.addView(label("Adımlar arası bekleme (milisaniye, en az 800)"))
        step = numberField(prefs.getLong(TapService.KEY_STEP, 1500L).toString())
        aiBox.addView(step)
        root.addView(aiBox)

        // ---- Ortak ----
        root.addView(label("Otomatik durma (dakika, 0 = kapalı)"))
        minutes = numberField(prefs.getLong(TapService.KEY_MINUTES, 0L).toString())
        root.addView(minutes)

        root.addView(Button(this).apply {
            text = "2) Başlat"
            setOnClickListener { onStartPressed() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(20) })

        root.addView(TextView(this).apply {
            text = "Nasıl kullanılır:\n" +
                "• Başlat'a bas, uyarıyı kabul et, oyuna geç.\n" +
                "• Sabit nokta modu: numaralı daireleri tıklanacak yerlere sürükle, " +
                "＋ / － ile nokta ekle / çıkar (en fazla 8).\n" +
                "• AI modu: ▶'e bas, Gemini her adımda ekrana bakıp bir hamle yapar. " +
                "Her adım birkaç saniye sürer; sıra tabanlı ve yavaş oyunlarda daha iyi çalışır.\n" +
                "• ▶ başlatır, ⏸ durdurur. ☰ ile paneli taşırsın, ✕ ile kapatırsın."
            textSize = 13f
            gravity = Gravity.START
            setPadding(0, dp(24), 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(root) })
        updateModeUi()
    }

    override fun onResume() {
        super.onResume()
        if (TapService.instance != null) {
            status.text = "✅ Servis açık"
            status.setTextColor(Color.parseColor("#1B8A3A"))
        } else {
            status.text = "❌ Servis kapalı — önce aşağıdan etkinleştir"
            status.setTextColor(Color.parseColor("#C0392B"))
        }
    }

    private fun updateModeUi() {
        val ai = modeAi.isChecked
        aiBox.visibility = if (ai) View.VISIBLE else View.GONE
        pointsBox.visibility = if (ai) View.GONE else View.VISIBLE
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 14f
        setPadding(0, dp(8), 0, 0)
    }

    private fun numberField(value: String) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(value)
    }

    private fun textField(value: String, hintText: String, type: Int) = EditText(this).apply {
        inputType = type
        hint = hintText
        setText(value)
    }

    private fun onStartPressed() {
        val service = TapService.instance
        if (service == null) {
            Toast.makeText(this, "Önce erişilebilirlik servisini etkinleştir.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        val ai = modeAi.isChecked
        if (ai) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                Toast.makeText(this, "AI modu Android 11 veya üstü gerektirir.", Toast.LENGTH_LONG).show()
                return
            }
            if (apiKey.text.toString().isBlank()) {
                Toast.makeText(this, "Gemini API anahtarını gir.", Toast.LENGTH_LONG).show()
                return
            }
        }

        var warning = "Bu uygulama ekrana senin yerine otomatik dokunur.\n\n" +
            "Birçok oyun bot / otomasyon kullanımını şartlarında yasaklar. Hesabın askıya " +
            "alınabilir veya kalıcı olarak yasaklanabilir.\n\n" +
            "Uygulamanın geliştiricisi; hesap yasaklanması, banlanma, ilerleme / veri kaybı " +
            "veya başka herhangi bir zarardan SORUMLU DEĞİLDİR. Tüm risk sana aittir. " +
            "Kullanmadan önce oyunun kullanım şartlarını kontrol et.\n\n"
        if (ai) {
            warning += "AI MODU: Her adımda ekranının görüntüsü Google Gemini'ye gönderilir. " +
                "Ekranda görünen her şey (mesajlar, kişisel bilgiler, şifreler dahil) Google'a " +
                "gidebilir; bu yüzden sadece oyun açıkken kullan. API kotası ve ücreti senin " +
                "hesabına aittir. Yapay zeka hata yapabilir ve yanlış yere basabilir; " +
                "satın alma butonlarından kaçınması istense de bunun garantisi yoktur.\n\n"
        }
        warning += "———\n" +
            "This app taps the screen automatically. Many games forbid bots and may suspend " +
            "or ban your account. The developer is not responsible for any ban, suspension, " +
            "or loss. You use it at your own risk."
        if (ai) {
            warning += "\nAI mode sends screenshots of your screen to Google Gemini at every step. " +
                "The AI can make mistakes."
        }

        AlertDialog.Builder(this)
            .setTitle("⚠️ Uyarı / Warning")
            .setMessage(warning)
            .setCancelable(false)
            .setPositiveButton("Kabul ediyorum, başlat") { _, _ ->
                val ms = (interval.text.toString().toLongOrNull() ?: 500L).coerceAtLeast(50L)
                val stepMs = (step.text.toString().toLongOrNull() ?: 1500L).coerceAtLeast(800L)
                val min = (minutes.text.toString().toLongOrNull() ?: 0L).coerceAtLeast(0L)
                getSharedPreferences(TapService.PREFS, Context.MODE_PRIVATE).edit()
                    .putString(TapService.KEY_MODE, if (ai) "ai" else "points")
                    .putLong(TapService.KEY_INTERVAL, ms)
                    .putLong(TapService.KEY_STEP, stepMs)
                    .putLong(TapService.KEY_MINUTES, min)
                    .putString(TapService.KEY_API, apiKey.text.toString().trim())
                    .putString(TapService.KEY_MODEL, model.text.toString().trim())
                    .putString(TapService.KEY_GOAL, goal.text.toString().trim())
                    .apply()
                service.hidePanel()
                service.showPanel()
                moveTaskToBack(true)
            }
            .setNegativeButton("İptal", null)
            .show()
    }
}
