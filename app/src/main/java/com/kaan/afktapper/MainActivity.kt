package com.kaan.afktapper

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var statusAction: TextView

    private lateinit var modeSeg: Segmented
    private lateinit var gameSeg: Segmented

    private lateinit var pointsCard: LinearLayout
    private lateinit var aiCard: LinearLayout
    private lateinit var goalBox: LinearLayout
    private lateinit var advToggle: TextView
    private lateinit var advBox: LinearLayout
    private lateinit var generalAdv: LinearLayout
    private lateinit var puzzleAdv: LinearLayout
    private lateinit var gameDesc: TextView

    private lateinit var interval: EditText
    private lateinit var minutes: EditText
    private lateinit var apiKey: EditText
    private lateinit var model: EditText
    private lateinit var puzModel: EditText
    private lateinit var goal: EditText
    private lateinit var step: EditText
    private lateinit var actions: EditText
    private lateinit var dragOff: EditText

    private var advOpen = false

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun lp(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply {
                topMargin = dp(top)
                bottomMargin = dp(bottom)
            }

    private fun card(parent: LinearLayout): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(this@MainActivity, Ui.CARD, 18, Ui.STROKE)
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        parent.addView(c, lp(bottom = 12))
        return c
    }

    private fun fieldLabel(parent: LinearLayout, s: String) {
        parent.addView(Ui.label(this, s, 13f, Ui.MUTED), lp(top = 12, bottom = 6))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        val prefs = getSharedPreferences(TapService.PREFS, Context.MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.BG) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(8))
        }
        scroll.addView(col)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- Başlık ----
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = TextView(this).apply {
            text = "👆"
            textSize = 26f
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Ui.ACCENT, Ui.ACCENT2)
            ).apply { shape = android.graphics.drawable.GradientDrawable.OVAL }
        }
        header.addView(logo, LinearLayout.LayoutParams(dp(56), dp(56)))
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        titles.addView(Ui.label(this, "AFK Tapper", 26f, Ui.TEXT, true))
        titles.addView(Ui.label(this, "Senin yerine oynayan akıllı tıklayıcı", 13f, Ui.MUTED))
        header.addView(titles)
        col.addView(header, lp(bottom = 18))

        // ---- Servis durumu ----
        val statusCard = card(col)
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = View(this)
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(12), dp(12)))
        statusText = Ui.label(this, "", 15f, Ui.TEXT, true).apply { setPadding(dp(10), 0, dp(8), 0) }
        statusRow.addView(statusText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statusAction = Ui.chip(this, "Etkinleştir").apply {
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        statusRow.addView(statusAction)
        statusCard.addView(statusRow)
        statusCard.addView(
            Ui.label(
                this,
                "Listeden “AFK Tapper”ı aç. Anahtar gri ve basılamıyorsa: Ayarlar → Uygulamalar → " +
                    "AFK Tapper → sağ üst ⋮ → “Kısıtlanmış ayarlara izin ver”. " +
                    "Uygulamayı güncellediysen servisi bir kez kapatıp aç.",
                12f, Ui.MUTED
            ), lp(top = 10)
        )

        // ---- Mod ----
        val modeCard = card(col)
        modeCard.addView(Ui.label(this, "Mod", 16f, Ui.TEXT, true), lp(bottom = 10))
        modeSeg = Segmented(this, listOf("Sabit noktalar", "AI (Gemini)"),
            if (prefs.getString(TapService.KEY_MODE, "points") == "ai") 1 else 0) { updateUi() }
        modeCard.addView(modeSeg, lp())

        // ---- Sabit nokta ayarları ----
        pointsCard = card(col)
        pointsCard.addView(Ui.label(this, "Sabit noktalar", 16f, Ui.TEXT, true))
        pointsCard.addView(
            Ui.label(
                this,
                "Başlat'a bas, ekrandaki numaralı daireleri tıklanacak yerlere sürükle. " +
                    "＋ / － ile nokta ekle ya da çıkar (en fazla 8).",
                12f, Ui.MUTED
            ), lp(top = 4)
        )
        fieldLabel(pointsCard, "Tıklama aralığı (milisaniye, en az 50)")
        interval = Ui.field(this, prefs.getLong(TapService.KEY_INTERVAL, 500L).toString(), "500", InputType.TYPE_CLASS_NUMBER)
        pointsCard.addView(interval, lp())

        // ---- AI ayarları ----
        aiCard = card(col)
        aiCard.addView(Ui.label(this, "Yapay zeka", 16f, Ui.TEXT, true))
        aiCard.addView(
            Ui.label(this, "", 12f, Ui.MUTED).apply {
                autoLinkMask = Linkify.WEB_URLS
                setLinkTextColor(Ui.ACCENT)
                text = "Gemini API anahtarını https://aistudio.google.com/apikey adresinden al. " +
                    "Anahtar sadece bu cihazda saklanır; kota ve ücret senin hesabına aittir. Android 11+ gerekir."
            }, lp(top = 4)
        )
        fieldLabel(aiCard, "Gemini API anahtarı")
        apiKey = Ui.field(
            this, prefs.getString(TapService.KEY_API, "").orEmpty(), "AIza…",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        aiCard.addView(apiKey, lp())

        fieldLabel(aiCard, "Oyun türü")
        gameSeg = Segmented(this, listOf("Genel oyun", "Block Blast / bulmaca"),
            if (prefs.getString(TapService.KEY_GAME, "general") == "blocks") 1 else 0) { updateUi() }
        aiCard.addView(gameSeg, lp())
        gameDesc = Ui.label(this, "", 12f, Ui.MUTED)
        aiCard.addView(gameDesc, lp(top = 8))

        goalBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fieldLabel(goalBox, "Oyundaki hedefin (Gemini'ye talimat)")
        goal = Ui.field(
            this, prefs.getString(TapService.KEY_GOAL, "").orEmpty(),
            "Örn: Savaşlara gir, ödülleri topla, görev butonlarına bas",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        ).apply { minLines = 2 }
        goalBox.addView(goal, lp())
        aiCard.addView(goalBox, lp())

        advToggle = Ui.label(this, "", 13f, Ui.ACCENT, true).apply {
            setPadding(0, dp(14), 0, dp(4))
            setOnClickListener {
                advOpen = !advOpen
                updateUi()
            }
        }
        aiCard.addView(advToggle, lp())

        advBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        generalAdv = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fieldLabel(generalAdv, "Model (hızlı: flash-lite)")
        val savedModel = prefs.getString(TapService.KEY_MODEL, TapService.DEFAULT_MODEL).orEmpty()
        model = Ui.field(
            this,
            // Eski varsayılan (yavaş) model otomatik olarak yenisiyle değişir.
            if (savedModel == "gemini-3.6-flash") TapService.DEFAULT_MODEL else savedModel,
            TapService.DEFAULT_MODEL, InputType.TYPE_CLASS_TEXT
        )
        generalAdv.addView(model, lp())
        fieldLabel(generalAdv, "Tek seferde en fazla hamle (1-5)")
        actions = Ui.field(this, prefs.getInt(TapService.KEY_ACTIONS, 3).toString(), "3", InputType.TYPE_CLASS_NUMBER)
        generalAdv.addView(actions, lp())
        advBox.addView(generalAdv, lp())

        puzzleAdv = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fieldLabel(puzzleAdv, "Bulmaca modeli (tahtayı okur; daha güçlü model daha doğru okur)")
        puzModel = Ui.field(
            this, prefs.getString(TapService.KEY_PUZ_MODEL, TapService.DEFAULT_PUZ_MODEL).orEmpty(),
            TapService.DEFAULT_PUZ_MODEL, InputType.TYPE_CLASS_TEXT
        )
        puzzleAdv.addView(puzModel, lp())
        fieldLabel(puzzleAdv, "Sürükleme kayması (% hücre; parça hedefin yukarısına düşüyorsa artır, 0 = yok)")
        dragOff = Ui.field(
            this, prefs.getInt(TapService.KEY_DRAG_OFF, 0).toString(), "0",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        )
        puzzleAdv.addView(dragOff, lp())
        advBox.addView(puzzleAdv, lp())

        fieldLabel(advBox, "Adımlar arası bekleme (milisaniye, en az 300)")
        step = Ui.field(this, prefs.getLong(TapService.KEY_STEP, 700L).toString(), "700", InputType.TYPE_CLASS_NUMBER)
        advBox.addView(step, lp())
        aiCard.addView(advBox, lp())

        // ---- Ortak ----
        val commonCard = card(col)
        commonCard.addView(Ui.label(this, "Otomatik durma", 16f, Ui.TEXT, true))
        fieldLabel(commonCard, "Kaç dakika sonra dursun? (0 = kapalı)")
        minutes = Ui.field(this, prefs.getLong(TapService.KEY_MINUTES, 0L).toString(), "0", InputType.TYPE_CLASS_NUMBER)
        commonCard.addView(minutes, lp())

        // ---- Yardım ----
        val helpCard = card(col)
        helpCard.addView(Ui.label(this, "Nasıl kullanılır", 16f, Ui.TEXT, true))
        helpCard.addView(
            Ui.label(
                this,
                "• Başlat'a bas, uyarıyı kabul et, oyuna geç.\n" +
                    "• Ekranda 👆 baloncuğu çıkar. Sürükleyerek istediğin yere koy, dokununca " +
                    "kontroller açılır / küçülür. ▶ başlatır, ⏸ durdurur, ✕ kapatır.\n" +
                    "• Block Blast modunda yapay zeka sadece tahtayı okur, hamleyi uygulama hesaplar " +
                    "ve parçaları sürükleyerek yerleştirir.\n" +
                    "• Parça hedefin hep biraz yukarısına ya da aşağısına düşüyorsa " +
                    "Gelişmiş ayarlardan “Sürükleme kayması”nı değiştir.",
                12.5f, Ui.MUTED
            ), lp(top = 6)
        )

        // ---- Alt çubuk: Başlat ----
        val bar = LinearLayout(this).apply {
            setBackgroundColor(Ui.BG)
            setPadding(dp(18), dp(10), dp(18), dp(16))
        }
        bar.addView(
            Ui.primaryButton(this, "▶  Başlat").apply { setOnClickListener { onStartPressed() } },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        root.addView(bar)

        setContentView(root)
        updateUi()
    }

    override fun onResume() {
        super.onResume()
        val on = TapService.instance != null
        statusDot.background = Ui.circle(if (on) Ui.GOOD else Ui.BAD)
        statusText.text = if (on) "Servis açık" else "Servis kapalı"
        statusAction.visibility = if (on) View.GONE else View.VISIBLE
    }

    private fun isAi() = modeSeg.index == 1
    private fun isBlocks() = isAi() && gameSeg.index == 1

    private fun updateUi() {
        val ai = isAi()
        val blocks = isBlocks()
        pointsCard.visibility = if (ai) View.GONE else View.VISIBLE
        aiCard.visibility = if (ai) View.VISIBLE else View.GONE
        goalBox.visibility = if (blocks) View.GONE else View.VISIBLE
        generalAdv.visibility = if (blocks) View.GONE else View.VISIBLE
        puzzleAdv.visibility = if (blocks) View.VISIBLE else View.GONE
        advBox.visibility = if (advOpen) View.VISIBLE else View.GONE
        advToggle.text = if (advOpen) "⚙  Gelişmiş ayarlar  ▴" else "⚙  Gelişmiş ayarlar  ▾"
        gameDesc.text = if (blocks) {
            "Block Blast gibi parça yerleştirme oyunları. Gemini tahtayı ve parçaları okur, " +
                "en iyi yerleşimi uygulama hesaplar, parçalar sürüklenerek yerleştirilir. " +
                "Reklam veya “oyun bitti” ekranı çıkarsa Gemini devam butonuna basar."
        } else {
            "Her oyun için: Gemini ekrana bakıp bir sonraki hamleyi seçer. " +
                "Yavaş ve sıra tabanlı oyunlarda iyi çalışır."
        }
    }

    private fun onStartPressed() {
        val service = TapService.instance
        if (service == null) {
            Toast.makeText(this, "Önce erişilebilirlik servisini etkinleştir.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        val ai = isAi()
        val blocks = isBlocks()
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

        var warning = "Bu uygulama ekrana senin yerine otomatik dokunur" +
            (if (blocks) " ve parçaları sürükler" else "") + ".\n\n" +
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

        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("⚠️ Uyarı / Warning")
            .setMessage(warning)
            .setCancelable(false)
            .setPositiveButton("Kabul ediyorum, başlat") { _, _ ->
                val ms = (interval.text.toString().toLongOrNull() ?: 500L).coerceAtLeast(50L)
                val stepMs = (step.text.toString().toLongOrNull() ?: 700L).coerceAtLeast(300L)
                val maxActs = (actions.text.toString().toIntOrNull() ?: 3).coerceIn(1, 5)
                val min = (minutes.text.toString().toLongOrNull() ?: 0L).coerceAtLeast(0L)
                val off = (dragOff.text.toString().toIntOrNull() ?: 0).coerceIn(-300, 300)
                getSharedPreferences(TapService.PREFS, Context.MODE_PRIVATE).edit()
                    .putString(TapService.KEY_MODE, if (ai) "ai" else "points")
                    .putString(TapService.KEY_GAME, if (blocks) "blocks" else "general")
                    .putLong(TapService.KEY_INTERVAL, ms)
                    .putLong(TapService.KEY_STEP, stepMs)
                    .putInt(TapService.KEY_ACTIONS, maxActs)
                    .putInt(TapService.KEY_DRAG_OFF, off)
                    .putLong(TapService.KEY_MINUTES, min)
                    .putString(TapService.KEY_API, apiKey.text.toString().trim())
                    .putString(TapService.KEY_MODEL, model.text.toString().trim())
                    .putString(TapService.KEY_PUZ_MODEL, puzModel.text.toString().trim())
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
