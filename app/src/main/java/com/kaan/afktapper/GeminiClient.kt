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

    /** Ağ çağrısı yapar; arka plan thread'inde çağır. */
    fun nextAction(
        apiKey: String,
        model: String,
        goal: String,
        history: List<String>,
        shot: Bitmap
    ): AiAction {
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("text", buildPrompt(goal, history)))
                .put(JSONObject().put("inline_data", JSONObject()
                    .put("mime_type", "image/jpeg")
                    .put("data", encode(shot)))))))
            put("generationConfig", JSONObject()
                .put("responseMimeType", "application/json")
                .put("temperature", 0.3))
        }

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw GeminiException(code, errorMessage(text, code))
            return parse(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun buildPrompt(goal: String, history: List<String>): String {
        val sb = StringBuilder()
        sb.append("You are controlling an Android phone to play a game for the user.\n")
        sb.append("Goal: ").append(goal).append("\n\n")
        sb.append("Look at the screenshot and choose exactly ONE next action. ")
        sb.append("Reply with JSON only, in this shape:\n")
        sb.append("{\"action\":\"tap\"|\"long_press\"|\"swipe\"|\"wait\",")
        sb.append("\"x\":0-1000,\"y\":0-1000,\"x2\":0-1000,\"y2\":0-1000,")
        sb.append("\"reason\":\"very short explanation in Turkish\"}\n")
        sb.append("Coordinates are relative to the image: x from the left, y from the top, ")
        sb.append("on a 0-1000 scale ((0,0) is top-left, (1000,1000) is bottom-right). ")
        sb.append("For swipe, x/y is the start and x2/y2 the end. Omit x2/y2 for other actions.\n\n")
        sb.append("Rules:\n")
        sb.append("- Never tap anything that spends real money, buys items, subscribes, or opens a payment dialog.\n")
        sb.append("- You may close ads with their close (X) button.\n")
        sb.append("- Never log out, delete data, or leave the game.\n")
        sb.append("- If the screen is loading, an animation is playing, or nothing useful can be done, answer wait.\n")
        sb.append("- If you keep repeating the same action with no visible effect, try something different.\n")
        if (history.isNotEmpty()) {
            sb.append("\nRecent actions (oldest first):\n")
            history.forEach { sb.append("- ").append(it).append("\n") }
        }
        return sb.toString()
    }

    private fun encode(src: Bitmap): String {
        val maxSide = 1024
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
        bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
        if (bmp !== src) bmp.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun parse(text: String): AiAction {
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

        val obj = try {
            if (raw.startsWith("[")) JSONArray(raw).getJSONObject(0) else JSONObject(raw)
        } catch (e: Exception) {
            throw GeminiException(0, "Gemini yanıtı okunamadı")
        }
        return AiAction(
            type = obj.optString("action", "wait").trim().lowercase(),
            x = obj.optInt("x", 500),
            y = obj.optInt("y", 500),
            x2 = obj.optInt("x2", -1),
            y2 = obj.optInt("y2", -1),
            reason = obj.optString("reason", "").take(80)
        )
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
