package com.example.phoneagent.llm

import com.example.phoneagent.data.AgentSettings
import com.example.phoneagent.data.ProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class LlmException(message: String) : Exception(message)

/**
 * Provider-agnostic client. One entry point, three wire formats:
 *  - OpenAI-compatible  (OpenAI, OpenRouter, Groq, Together, DeepSeek, Fireworks, Ollama, LM Studio...)
 *  - Google Gemini      (generateContent REST)
 *  - Anthropic Claude   (/v1/messages REST)
 */
class UniversalLlmClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build(),
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val parser = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Returns the model's raw text reply. [imageB64] is an optional JPEG (base64, no prefix).
     * Some newer models reject a `temperature` parameter; on such a 400 we retry once without it.
     */
    suspend fun complete(s: AgentSettings, system: String, user: String, imageB64: String? = null): String =
        withContext(Dispatchers.IO) {
            var withTemperature = true
            var result: String? = null
            while (result == null) {
                val request = when (s.providerType) {
                    ProviderType.OPENAI -> openAiRequest(s, system, user, imageB64, withTemperature)
                    ProviderType.GEMINI -> geminiRequest(s, system, user, imageB64, withTemperature)
                    ProviderType.ANTHROPIC -> anthropicRequest(s, system, user, imageB64, withTemperature)
                }
                val (code, body) = http.newCall(request).await()
                if (code in 200..299) {
                    val text = runCatching { extractText(s.providerType, body) }.getOrNull()
                    if (text.isNullOrBlank()) throw LlmException("Empty or unrecognised response: ${body.take(300)}")
                    result = text
                } else if (withTemperature && code == 400 && body.contains("temperature", ignoreCase = true)) {
                    withTemperature = false
                } else {
                    throw LlmException("HTTP $code: ${body.take(400)}")
                }
            }
            result ?: throw LlmException("No response")
        }

    // ---------- URL helpers (tolerate how users type base URLs) ----------

    private fun base(s: AgentSettings) = s.baseUrl.trim().trimEnd('/')

    private fun openAiUrl(s: AgentSettings) = base(s).removeSuffix("/chat/completions") + "/chat/completions"

    private fun geminiUrl(s: AgentSettings): String {
        var b = base(s)
        if (!b.contains("/v1")) b += "/v1beta"
        return "$b/models/${s.model.trim().removePrefix("models/")}:generateContent"
    }

    private fun anthropicUrl(s: AgentSettings): String {
        var b = base(s).removeSuffix("/messages")
        if (!b.endsWith("/v1")) b += "/v1"
        return "$b/messages"
    }

    // ---------- request builders ----------

    private fun openAiRequest(s: AgentSettings, system: String, user: String, img: String?, temp: Boolean): Request {
        val userContent: JsonElement = if (img == null) JsonPrimitive(user) else buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", user) })
            add(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,$img") })
            })
        }
        val body = buildJsonObject {
            put("model", s.model.trim())
            if (temp) put("temperature", 0)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", userContent) })
            })
        }
        val b = Request.Builder().url(openAiUrl(s)).post(body.toString().toRequestBody(jsonMedia))
        if (s.apiKey.isNotBlank()) b.header("Authorization", "Bearer ${s.apiKey}")
        b.header("X-Title", "Phone Agent") // OpenRouter attribution; harmless elsewhere
        return b.build()
    }

    private fun geminiRequest(s: AgentSettings, system: String, user: String, img: String?, temp: Boolean): Request {
        val body = buildJsonObject {
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray { add(buildJsonObject { put("text", system) }) })
            })
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", user) })
                        if (img != null) add(buildJsonObject {
                            put("inline_data", buildJsonObject {
                                put("mime_type", "image/jpeg"); put("data", img)
                            })
                        })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                if (temp) put("temperature", 0)
                put("responseMimeType", "application/json")
            })
        }
        return Request.Builder()
            .url(geminiUrl(s))
            .header("x-goog-api-key", s.apiKey)
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
    }

    private fun anthropicRequest(s: AgentSettings, system: String, user: String, img: String?, temp: Boolean): Request {
        val body = buildJsonObject {
            put("model", s.model.trim())
            put("max_tokens", 1024)
            if (temp) put("temperature", 0)
            put("system", system)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        if (img != null) add(buildJsonObject {
                            put("type", "image")
                            put("source", buildJsonObject {
                                put("type", "base64"); put("media_type", "image/jpeg"); put("data", img)
                            })
                        })
                        add(buildJsonObject { put("type", "text"); put("text", user) })
                    })
                })
            })
        }
        return Request.Builder()
            .url(anthropicUrl(s))
            .header("x-api-key", s.apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
    }

    // ---------- response parsing ----------

    private fun extractText(type: ProviderType, body: String): String {
        val root = parser.parseToJsonElement(body).jsonObject
        return when (type) {
            ProviderType.OPENAI -> root["choices"]!!.jsonArray[0].jsonObject["message"]!!
                .jsonObject["content"]!!.jsonPrimitive.content

            ProviderType.GEMINI -> root["candidates"]!!.jsonArray[0].jsonObject["content"]!!
                .jsonObject["parts"]!!.jsonArray
                .joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }

            ProviderType.ANTHROPIC -> root["content"]!!.jsonArray
                .filter { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }
                .joinToString("") { it.jsonObject["text"]!!.jsonPrimitive.content }
        }
    }

    /** Suspends on an OkHttp call; cancelling the coroutine cancels the HTTP request. */
    private suspend fun Call.await(): Pair<Int, String> = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(LlmException("Network error: ${e.message}"))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    val text = runCatching { r.body?.string().orEmpty() }.getOrDefault("")
                    if (cont.isActive) cont.resume(r.code to text)
                }
            }
        })
        cont.invokeOnCancellation { runCatching { cancel() } }
    }
}
