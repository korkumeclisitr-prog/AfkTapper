package com.kaan.afktapper

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
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

    /** Ağ çağrısı yapar; arka plan thread'inde çağır. Tek çağrıda 1..maxActions hamle döner. */
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
            .put(JSONObject().put("inline_data", JSONObject()
                .put("mime_type", "image/jpeg")
                .put("data", encode(shot))))

        val thinking = thinkingConfig(model)
        var result = post(apiKey, model, bodyOf(parts, thinking))
        // Düşünme ayarını model kabul etmediyse ayarsız tekrar dene.
        if (result.first == 400 && thinking != null) {
            result = post(apiKey, model, bodyOf(parts, null))
        }
        val (code, text) = result
        if (code !in 200..299) throw GeminiException(code, errorMessage(text, code))
        return parse(text, maxActions)
    }

    private fun thinkingConfig(model: String): JSONObject? {
        val m = model.lowercase()
        return when {
            "pro" in m -> null
            "2.5" in m -> JSONObject().put("thinkingBudget", 0)
            "gemini-3" in m -> JSONObject().put("thinkingLevel", "minimal")
            else -> null
        }
    }

    private fun bodyOf(parts: JSONArray, thinking: JSONObject?): JSONObject {
        val gen = JSONObject()
            .put("responseMimeType", "application/json")
            .put("temperature", 0.2)
            .put("maxOutputTokens", 400)
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
            conn.readTimeout = 40_000
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

    private fun encode(src: Bitmap): String {
        val maxSide = 768
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
        bmp.compress(Bitmap.CompressFormat.JPEG, 60, out)
        if (bmp !== src) bmp.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun parse(text: String, maxActions: Int): List<AiAction> {
        val root = JSONObject(text)
        val parts = root.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: throw GeminiException(0, "Gemini boş yanıt döndürdü (içerik filtresine takılmış olabilir)")

        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            if (part.optBoolean("thought", false)) continue
            sb.append(part.optString("text", ""))
        }
        val raw = sb.toString().trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (raw.isEmpty()) throw GeminiException(0, "Gemini yanıtında eylem yok")

        val arr: JSONArray = try {
            when {
                raw.startsWith("[") -> JSONArray(raw)
                else -> {
                    val obj = JSONObject(raw)
                    obj.optJSONArray("actions") ?: JSONArray().put(obj)
                }
            }
        } catch (e: Exception) {
            throw GeminiException(0, "Gemini yanıtı okunamadı")
        }

        val list = ArrayList<AiAction>()
        for (i in 0 until minOf(arr.length(), maxActions.coerceAtLeast(1))) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(
                AiAction(
                    type = o.optString("action", "wait").trim().lowercase(),
                    x = o.optInt("x", 500),
                    y = o.optInt("y", 500),
                    x2 = o.optInt("x2", -1),
                    y2 = o.optInt("y2", -1),
                    reason = o.optString("reason", "").take(60)
                )
            )
        }
        if (list.isEmpty()) throw GeminiException(0, "Gemini yanıtında eylem yok")
        return list
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
