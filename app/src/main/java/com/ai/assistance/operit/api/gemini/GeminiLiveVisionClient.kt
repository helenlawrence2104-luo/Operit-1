package com.ai.assistance.operit.api.gemini

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Gemini 2.5 Flash 实时视觉多模态客户端
 */
class GeminiLiveVisionClient(private var apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun updateApiKey(newKey: String) {
        this.apiKey = newKey
    }

    /**
     * 将实时相机画面与用户的语音提问一同发送给 Gemini 2.5 Flash 进行视觉理解
     */
    suspend fun analyzeFrameAndPrompt(
        bitmap: Bitmap?,
        prompt: String,
        systemInstruction: String = "你是一个正在与用户进行实时视频通话的AI助手。你可以实时看到用户摄像头拍摄的画面。请结合画面中的视觉细节，以亲切、口语化、简洁的语气与用户交流。"
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
            val partsArray = JSONArray()

            // 1. 如果有实时摄像头画面，压缩并转为 Base64 传入
            if (bitmap != null) {
                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
                val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

                val imagePart = JSONObject().apply {
                    put("inlineData", JSONObject().apply {
                        put("mimeType", "image/jpeg")
                        put("data", base64Image)
                    })
                }
                partsArray.put(imagePart)
            }

            // 2. 传入用户语音/文字提问
            val effectivePrompt = if (prompt.isBlank()) "请看看当前画面里有什么，并用简短生动的语言告诉我" else prompt
            partsArray.put(JSONObject().apply { put("text", effectivePrompt) })

            // 3. 构建请求体
            val rootJson = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("parts", partsArray)
                    })
                })
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", systemInstruction) })
                    })
                })
            }

            val requestBody = rootJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(url)
                .addHeader("X-goog-api-key", apiKey)
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("API Error ${response.code}: $responseBody"))
            }

            val jsonResponse = JSONObject(responseBody)
            val candidates = jsonResponse.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val firstCandidate = candidates.getJSONObject(0)
                val content = firstCandidate.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    val replyText = parts.getJSONObject(0).optString("text", "")
                    return@withContext Result.success(replyText)
                }
            }

            Result.failure(Exception("未能获取到有效回复"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}