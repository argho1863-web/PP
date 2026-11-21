package com.example.phoneagent.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class AgentAction(
    val thought: String = "",
    val action: String = "",
    val params: JsonObject = JsonObject(emptyMap()),
    @SerialName("requires_confirmation") val requiresConfirmation: Boolean = false,
)

private val lenient = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

/** Strips ```json fences / prose and returns the outermost {...} block, or null. */
fun extractJsonObject(raw: String): String? {
    val cleaned = raw.replace("```json", "").replace("```JSON", "").replace("```", "").trim()
    val start = cleaned.indexOf('{')
    val end = cleaned.lastIndexOf('}')
    return if (start in 0 until end) cleaned.substring(start, end + 1) else null
}

/** Parses (and repairs) the model's reply. Returns null if it cannot be turned into an action. */
fun parseAgentAction(raw: String): AgentAction? {
    val json = extractJsonObject(raw) ?: return null
    return runCatching { lenient.decodeFromString<AgentAction>(json) }
        .getOrNull()
        ?.takeIf { it.action.isNotBlank() }
}

fun JsonObject.str(key: String): String? =
    runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it != "null" }

fun JsonObject.num(key: String): Double? = str(key)?.toDoubleOrNull()
fun JsonObject.int(key: String): Int? = num(key)?.toInt()
