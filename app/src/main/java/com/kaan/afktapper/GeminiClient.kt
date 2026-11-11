package com.kaan.afktapper

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class GeminiException(val code: Int, message: String) : Exception(message)

/** Koordinatlar 0-1000 ölçeğinde (görüntünün solundan / üstünden). x2,y2 sadece swipe için. */
data class AiAction(
    val type: String,
    val x: Int,
    val y: Int,
    val x2: Int,
    val y2: Int,
    val reason: String
)

object GeminiClient {

    // ---------- Genel mod: ekrana bak, 1..maxActions hamle seç ----------

    /** Ağ çağrısı yapar; arka plan thread'inde çağır. */
    fun nextActions(
        apiKey: String,
        model: String,
        goal: String,
        history: List<String>,
        shot: Bitmap,
        maxActions: Int
    ): List<AiAction> {
        val parts = JSONArray()
            .put(JSONObject().put("text", buildPrompt(goal, history, maxActions)))
            .put(imagePart(shot, 768, 60))
        val text = call(apiKey, model, parts, "minimal", 400, 0.2)
        try {
            val raw = JsonText.clean(text)
            val arr = if (raw.startsWith("[")) {
                JSONArray(raw)
            } else {
                val o = JSONObject(raw)
                o.optJSONArray("actions") ?: JSONArray().put(o)
            }
            val list = JsonText.actions(arr, maxActions)
            if (list.isEmpty()) throw GeminiException(0, "Gemini yanıtında eylem yok")
            return list
        } catch (e: JSONException) {
            throw GeminiException(0, "Gemini yanıtı okunamadı")
        }
    }

    // ---------- Bulmaca modu: sadece tahtayı ve parçaları oku, hamleyi cihazdaki çözücü seçer ----------

    private const val PUZZLE_PROMPT = """This is a screenshot of an Android game. Describe it. Do NOT choose any move.

If it is a block-puzzle play screen (a square grid board with a tray of block pieces below it, like Block Blast), reply:
{"screen":"game","board":{"left":N,"top":N,"right":N,"bottom":N,"cells":["........", "..##....", ...]},"pieces":[{"cx":N,"cy":N,"shape":["##","#."]}]}

Rules:
- All numbers (left, top, right, bottom, cx, cy) are coordinates relative to the screenshot on a 0-1000 scale: (0,0) is the top-left corner, (1000,1000) the bottom-right corner.
- board.left/top/right/bottom are the outer edges of the playable cell grid only (not the decorative frame, score, or background).
- cells: one string per grid row from top to bottom, one character per cell: '#' = the cell contains a block (any color), '.' = the cell is empty. Look at every cell carefully. Usually the grid is 8 rows of 8 characters. All rows must have the same length.
- pieces: every block piece currently waiting in the tray, from left to right. Ignore empty tray slots and pieces that are being dragged. shape = the piece's tight bounding box, one string per row, '#' for filled and '.' for empty, with no empty rows or columns at the edges. cx,cy = the center of that bounding box on the screen.

If the screen is NOT the playable board (menu, ad, game over, reward or other popup), reply instead:
{"screen":"other","actions":[{"action":"tap","x":N,"y":N,"reason":"max 5 words"}]}
and choose the single best tap to get back to playing: close an ad with its X button, or tap Play again / Continue / Claim / OK. Never buy anything, subscribe, or open a payment dialog. Use {"action":"wait"} if the screen is loading or an animation is playing.

Reply with JSON only."""

    /** Ağ çağrısı yapar; arka plan thread'inde çağır. */
    fun readPuzzle(apiKey: String, model: String, shot: Bitmap): PuzzleRead {
        val parts = JSONArray()
            .put(JSONObject().put("text", PUZZLE_PROMPT))
            .put(imagePart(shot, 1024, 80))
        val text = call(apiKey, model, parts, "low", 4096, 0.1)
        try {
            return PuzzleParser.parse(text)
        } catch (e: JSONException) {
            throw GeminiException(0, "Gemini yanıtı okunamadı")
        }
    }

    // ---------- Ortak ----------

    private fun call(
        apiKey: String,
        model: String,
        parts: JSONArray,
        level: String,
        maxTokens: Int,
        temperature: Double
    ): String {
        val thinking = thinkingConfig(model, level)
        var result = post(apiKey, model, bodyOf(parts, thinking, maxTokens, temperature))
        // Düşünme ayarını model kabul etmediyse ayarsız tekrar dene.
        if (result.first == 400 && thinking != null) {
            result = post(apiKey, model, bodyOf(parts, null, maxTokens, temperature))
        }
        val (code, body) = result
        if (code !in 200..299) throw GeminiException(code, errorMessage(body, code))
        return extractText(body)
    }

    private fun thinkingConfig(model: String, level: String): JSONObject? {
        val m = model.lowercase()
        return when {
            "pro" in m -> null
            "2.5" in m -> JSONObject().put(
                "thinkingBudget",
                when (level) {
                    "minimal" -> 0
                    "low" -> 1024
                    else -> 4096
                }
            )
            "gemini-3" in m -> JSONObject().put("thinkingLevel", level)
            else -> null
        }
    }

    private fun bodyOf(parts: JSONArray, thinking: JSONObject?, maxTokens: Int, temperature: Double): JSONObject {
        val gen = JSONObject()
            .put("responseMimeType", "application/json")
            .put("temperature", temperature)
            .put("maxOutputTokens", maxTokens)
        if (thinking != null) gen.put("thinkingConfig", thinking)
        return JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("generationConfig", gen)
    }

    private fun post(apiKey: String, model: String, body: JSONObject): Pair<Int, String> {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    private fun extractText(body: String): String {
        val parts = try {
            JSONObject(body).optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
        } catch (e: JSONException) {
            null
        } ?: throw GeminiException(0, "Gemini boş yanıt döndürdü (içerik filtresine takılmış olabilir)")

        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            if (part.optBoolean("thought", false)) continue
            sb.append(part.optString("text", ""))
        }
        val raw = sb.toString().trim()
        if (raw.isEmpty()) throw GeminiException(0, "Gemini yanıtı boş geldi")
        return raw
    }

    private fun buildPrompt(goal: String, history: List<String>, maxActions: Int): String {
        val sb = StringBuilder()
        sb.append("You control an Android phone to play a game for the user. Be fast and decisive.\n")
        sb.append("Goal: ").append(goal).append("\n\n")
        sb.append("Look at the screenshot and reply with JSON only, in this shape:\n")
        sb.append("{\"actions\":[{\"action\":\"tap\"|\"long_press\"|\"swipe\"|\"wait\",")
        sb.append("\"x\":0-1000,\"y\":0-1000,\"x2\":0-1000,\"y2\":0-1000,\"reason\":\"max 5 words, Turkish\"}]}\n")
        sb.append("Coordinates are relative to the image: x from the left, y from the top, ")
        sb.append("0-1000 scale ((0,0) top-left, (1000,1000) bottom-right). ")
        sb.append("For swipe, x/y is the start and x2/y2 the end; omit x2/y2 otherwise.\n\n")
        sb.append("Rules:\n")
        if (maxActions > 1) {
            sb.append("- Return up to ").append(maxActions).append(" actions in order. ")
            sb.append("Return more than one ONLY when the screen will not change layout between them ")
            sb.append("(for example repeated taps on the same button, or several visible buttons). ")
            sb.append("If a tap opens a new screen, dialog, or animation, return just that one action.\n")
        } else {
            sb.append("- Return exactly ONE action.\n")
        }
        sb.append("- Never tap anything that spends real money, buys items, subscribes, or opens a payment dialog.\n")
        sb.append("- You may close ads with their close (X) button.\n")
        sb.append("- Never log out, delete data, or leave the game.\n")
        sb.append("- If the screen is loading or an animation is playing, answer a single wait.\n")
        sb.append("- If you keep repeating the same action with no visible effect, try something different.\n")
        if (history.isNotEmpty()) {
            sb.append("\nRecent actions (oldest first):\n")
            history.forEach { sb.append("- ").append(it).append("\n") }
        }
        return sb.toString()
    }

    private fun imagePart(src: Bitmap, maxSide: Int, quality: Int): JSONObject =
        JSONObject().put(
            "inline_data",
            JSONObject()
                .put("mime_type", "image/jpeg")
                .put("data", encode(src, maxSide, quality))
        )

    private fun encode(src: Bitmap, maxSide: Int, quality: Int): String {
        val scale = maxSide.toFloat() / maxOf(src.width, src.height)
        val bmp = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                src,
                (src.width * scale).toInt().coerceAtLeast(1),
                (src.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else src
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (bmp !== src) bmp.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun errorMessage(body: String, code: Int): String {
        val detail = runCatching { JSONObject(body).getJSONObject("error").optString("message") }
            .getOrNull()
        val hint = when (code) {
            400 -> "API anahtarı veya istek geçersiz"
            401, 403 -> "API anahtarı reddedildi"
            404 -> "Model bulunamadı, model adını kontrol et"
            429 -> "Kota / limit doldu, biraz beklenecek"
            else -> "Sunucu hatası"
        }
        return "$hint (HTTP $code)" + if (!detail.isNullOrBlank()) ": ${detail.take(120)}" else ""
    }
}
