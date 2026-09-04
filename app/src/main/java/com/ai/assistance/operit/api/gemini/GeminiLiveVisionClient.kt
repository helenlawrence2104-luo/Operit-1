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
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Gemini 3.8 Flash 实时视觉多模态客户端（支持极速 SSE 流式输出）
 */
class GeminiLiveVisionClient(private var apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun updateApiKey(newKey: String) {
        this.apiKey = newKey
    }

    /**
     * SSE 流式分析画面与提问，每当模型生成若干字词即刻回调，极大降低首字发声延迟
     */
    suspend fun streamAnalyzeFrameAndPrompt(
        bitmap: Bitmap?,
        prompt: String,
        systemInstruction: String = "你是一个正在与用户进行实时视频通话的AI助手。你可以实时看到用户手机摄像头拍摄的画面。请结合画面中的视觉细节，像真人一样亲切、口语化、简洁地与用户交流。不要输出复杂的Markdown，直接说出易于语音播报的自然口语。",
        onChunk: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:streamGenerateContent?alt=sse"
            val partsArray = JSONArray()

            // 1. 编码压缩当前相机画面
            if (bitmap != null) {
                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 65, outputStream)
                val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

                val imagePart = JSONObject().apply {
                    put("inlineData", JSONObject().apply {
                        put("mimeType", "image/jpeg")
                        put("data", base64Image)
                    })
                }
                partsArray.put(imagePart)
            }

            // 2. 传入提问
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
            if (!response.isSuccessful) {
                val err = response.body?.string() ?: ""
                return@withContext Result.failure(Exception("API 错误 ${response.code}: $err"))
            }

            val fullTextBuilder = java.lang.StringBuilder()
            val inputStream = response.body?.byteStream() ?: return@withContext Result.failure(Exception("无法读取响应流"))
            val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line?.trim() ?: continue
                if (currentLine.startsWith("data:")) {
                    val jsonData = currentLine.removePrefix("data:").trim()
                    if (jsonData.isEmpty()) continue
                    try {
                        val json = JSONObject(jsonData)
                        val candidates = json.optJSONArray("candidates")
                        if (candidates != null && candidates.length() > 0) {
                            val candidate = candidates.getJSONObject(0)
                            val content = candidate.optJSONObject("content")
                            val parts = content?.optJSONArray("parts")
                            if (parts != null && parts.length() > 0) {
                                val textChunk = parts.getJSONObject(0).optString("text", "")
                                if (textChunk.isNotEmpty()) {
                                    fullTextBuilder.append(textChunk)
                                    onChunk(textChunk)
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // 忽略单个 chunk 的解析异常
                    }
                }
            }

            Result.success(fullTextBuilder.toString())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}