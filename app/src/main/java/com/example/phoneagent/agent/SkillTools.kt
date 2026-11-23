package com.example.phoneagent.agent

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.text.Html
import com.example.phoneagent.service.PhoneAgentAccessibilityService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Native ports of the utility skills from GauravSingh9356/J.A.R.V.I.S (time, weather, news, Wikipedia,
 * dictionary with did-you-mean, jokes, device status, notes / to-do, email, YouTube and Maps search,
 * screen reading with OCR). Web skills use free keyless APIs (Open-Meteo, Google News RSS, Wikipedia,
 * dictionaryapi.dev, Datamuse), so no extra API keys are needed.
 */
class SkillTools(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("agent_lists", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private val jokes = listOf(
        "Why do programmers prefer dark mode? Because light attracts bugs.",
        "I would tell you a UDP joke, but you might not get it.",
        "There are 10 kinds of people: those who understand binary and those who do not.",
        "A SQL query walks into a bar, walks up to two tables and asks: can I join you?",
        "Why did the developer go broke? Because he used up all his cache.",
        "How many programmers does it take to change a light bulb? None, that is a hardware problem.",
        "I told my phone I needed space. It opened the storage settings.",
        "Why was the JavaScript developer sad? Because he did not know how to null his feelings.",
        "Debugging: being the detective in a crime movie where you are also the murderer.",
        "My code works and I have no idea why. My code does not work and I have no idea why.",
        "Why do Java developers wear glasses? Because they do not C sharp.",
        "A programmer's wife says: go to the store and get a loaf of bread, and if they have eggs, get a dozen. He comes back with twelve loaves.",
    )

    /** Runs a skill action. Returns null if [a] is not a skill (so the caller can report an unknown action). */
    suspend fun run(svc: PhoneAgentAccessibilityService, a: AgentAction): String? {
        val p = a.params
        return when (a.action) {
            "get_time" -> currentTime()
            "device_status" -> deviceStatus()
            "get_weather" -> weather(p.str("city") ?: p.str("location") ?: "")
            "get_news" -> news(p.str("topic") ?: p.str("query"))
            "wikipedia" -> wikipedia(p.str("query") ?: p.str("topic") ?: "")
            "dictionary" -> dictionary(p.str("word") ?: p.str("query") ?: "")
            "tell_joke" -> jokes.random()
            "todo_add" -> listAdd(KEY_TODO, "to-do item", p.str("text") ?: "")
            "todo_list" -> listShow(KEY_TODO, "To-do list")
            "todo_remove" -> listRemove(KEY_TODO, "to-do item", p.int("index") ?: -1)
            "remember_note" -> listAdd(KEY_NOTES, "note", p.str("text") ?: "")
            "recall_notes" -> listShow(KEY_NOTES, "Notes")
            "compose_email" -> composeEmail(svc, p.str("to") ?: "", p.str("subject") ?: "", p.str("body") ?: "")
            "youtube_search" -> youtubeSearch(svc, p.str("query") ?: "")
            "maps_search" -> mapsSearch(svc, p.str("query") ?: p.str("location") ?: "")
            "read_screen" -> readScreen(svc, false)
            "ocr_screen" -> readScreen(svc, true)
            else -> null
        }
    }

    // ---------------- time / device ----------------

    private fun currentTime(): String {
        val now = LocalDateTime.now()
        val hour = now.hour
        val part = if (hour < 12) "Good morning" else if (hour < 18) "Good afternoon" else "Good evening"
        val text = now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm", Locale.getDefault()))
        return "$part. It is $text (${ZoneId.systemDefault().id})."
    }

    private fun deviceStatus(): String {
        val bi = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bi?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = bi?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val battery = if (level >= 0 && scale > 0) "${level * 100 / scale}%" else "unknown"

        val mi = ActivityManager.MemoryInfo()
        app.getSystemService(ActivityManager::class.java)?.getMemoryInfo(mi)
        val ramUsed = if (mi.totalMem > 0) 100 - (mi.availMem * 100 / mi.totalMem) else 0L

        val stat = StatFs(Environment.getDataDirectory().path)
        val freeGb = stat.availableBytes / 1_000_000_000.0
        val chargingText = if (charging) " and charging" else ""
        return "Battery is at $battery$chargingText. Memory is ${ramUsed}% in use. Storage has ${"%.1f".format(freeGb)} GB free."
    }

    // ---------------- web skills ----------------

    private suspend fun weather(city: String): String {
        val c = city.trim()
        if (c.isEmpty()) return "error: get_weather needs a city. Ask the user which city."
        return try {
            val (gCode, gBody) = get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&name=${Uri.encode(c)}")
            if (gCode != 200) return "error: location lookup failed (HTTP $gCode)"
            val place = json.parseToJsonElement(gBody).jsonObject["results"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: return "error: I could not find a city called '$c'"
            val lat = place["latitude"]!!.jsonPrimitive.double
            val lon = place["longitude"]!!.jsonPrimitive.double
            val name = place["name"]?.jsonPrimitive?.content ?: c
            val country = place["country"]?.jsonPrimitive?.content.orEmpty()

            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,relative_humidity_2m,apparent_temperature,wind_speed_10m,weather_code&timezone=auto"
            val (wCode, wBody) = get(url)
            if (wCode != 200) return "error: weather service failed (HTTP $wCode)"
            val cur = json.parseToJsonElement(wBody).jsonObject["current"]!!.jsonObject
            val temp = cur["temperature_2m"]!!.jsonPrimitive.double
            val feels = cur["apparent_temperature"]!!.jsonPrimitive.double
            val humidity = cur["relative_humidity_2m"]!!.jsonPrimitive.double.toInt()
            val wind = cur["wind_speed_10m"]!!.jsonPrimitive.double
            val code = cur["weather_code"]!!.jsonPrimitive.double.toInt()
            val where = if (country.isEmpty()) name else "$name, $country"
            "Weather in $where: ${weatherText(code)}, $temp C (feels like $feels C), humidity $humidity%, wind $wind km/h."
        } catch (e: Exception) {
            "error: weather lookup failed: ${e.message}"
        }
    }

    private fun weatherText(code: Int): String = when (code) {
        0 -> "clear sky"
        1, 2 -> "mostly clear"
        3 -> "overcast"
        45, 48 -> "foggy"
        51, 53, 55, 56, 57 -> "drizzle"
        61, 63, 65, 66, 67 -> "rain"
        71, 73, 75, 77 -> "snow"
        80, 81, 82 -> "rain showers"
        85, 86 -> "snow showers"
        95, 96, 99 -> "thunderstorm"
        else -> "unsettled weather"
    }

    private suspend fun news(topic: String?): String {
        val q = topic?.trim().orEmpty()
        val url = if (q.isEmpty()) "https://news.google.com/rss?hl=en-US&gl=US&ceid=US:en"
        else "https://news.google.com/rss/search?q=${Uri.encode(q)}&hl=en-US&gl=US&ceid=US:en"
        return try {
            val (code, body) = get(url)
            if (code != 200) return "error: news service failed (HTTP $code)"
            val titles = Regex("<item>.*?<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
                .findAll(body)
                .map { cleanText(it.groupValues[1]) }
                .filter { it.isNotEmpty() }
                .take(5)
                .toList()
            if (titles.isEmpty()) "No headlines found${if (q.isEmpty()) "" else " for '$q'"}."
            else "Top headlines${if (q.isEmpty()) "" else " about $q"}:\n" +
                titles.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n")
        } catch (e: Exception) {
            "error: news lookup failed: ${e.message}"
        }
    }

    private suspend fun wikipedia(query: String): String {
        val q = query.trim()
        if (q.isEmpty()) return "error: wikipedia needs a query"
        return try {
            val url = "https://en.wikipedia.org/w/api.php?action=query&generator=search&gsrsearch=${Uri.encode(q)}" +
                "&gsrlimit=1&prop=extracts&exintro=1&explaintext=1&exsentences=3&redirects=1&format=json"
            val (code, body) = get(url)
            if (code != 200) return "error: Wikipedia failed (HTTP $code)"
            val pages = json.parseToJsonElement(body).jsonObject["query"]?.jsonObject?.get("pages")?.jsonObject
            val page = pages?.values?.firstOrNull()?.jsonObject
                ?: return "I could not find a Wikipedia article for '$q'."
            val title = page["title"]?.jsonPrimitive?.content ?: q
            val extract = page["extract"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (extract.isEmpty()) "Wikipedia has an article '$title' but no summary."
            else "According to Wikipedia ($title): ${extract.take(700)}"
        } catch (e: Exception) {
            "error: Wikipedia lookup failed: ${e.message}"
        }
    }

    private suspend fun dictionary(word: String): String {
        val w = word.trim().lowercase()
        if (w.isEmpty()) return "error: dictionary needs a word"
        return try {
            val (code, body) = get("https://api.dictionaryapi.dev/api/v2/entries/en/${Uri.encode(w)}")
            if (code == 200) {
                val meanings = json.parseToJsonElement(body).jsonArray.first().jsonObject["meanings"]!!.jsonArray
                val lines = meanings.take(3).mapNotNull { m ->
                    val o = m.jsonObject
                    val pos = o["partOfSpeech"]?.jsonPrimitive?.content.orEmpty()
                    val def = o["definitions"]?.jsonArray?.firstOrNull()?.jsonObject?.get("definition")?.jsonPrimitive?.content
                    if (def == null) null else "($pos) $def"
                }
                if (lines.isEmpty()) "No definition found for '$w'." else "$w: " + lines.joinToString(" ")
            } else {
                // Spell-check fallback, like the did-you-mean step in the original dictionary skill.
                val (sCode, sBody) = get("https://api.datamuse.com/sug?max=5&s=${Uri.encode(w)}")
                val suggestions = if (sCode == 200) {
                    json.parseToJsonElement(sBody).jsonArray.mapNotNull { it.jsonObject["word"]?.jsonPrimitive?.content }
                } else emptyList()
                if (suggestions.isEmpty()) "'$w' is not in the dictionary and I have no spelling suggestions."
                else "'$w' is not in the dictionary. Did you mean: ${suggestions.joinToString(", ")}?"
            }
        } catch (e: Exception) {
            "error: dictionary lookup failed: ${e.message}"
        }
    }

    private fun cleanText(raw: String): String =
        Html.fromHtml(
            raw.replace("<![CDATA[", "").replace("]]>", ""),
            Html.FROM_HTML_MODE_LEGACY,
        ).toString().trim()

    private suspend fun get(url: String): Pair<Int, String> = suspendCancellableCoroutine { cont ->
        val call = http.newCall(
            Request.Builder().url(url).header("User-Agent", "PhoneAgent/1.0 (Android)").build()
        )
        cont.invokeOnCancellation { runCatching { call.cancel() } }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resume(-1 to (e.message ?: "network error"))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    val body = runCatching { r.body?.string().orEmpty() }.getOrDefault("")
                    if (cont.isActive) cont.resume(r.code to body)
                }
            }
        })
    }

    // ---------------- persistent to-do list and notes ----------------

    private fun load(key: String): MutableList<String> = runCatching {
        json.decodeFromString<List<String>>(prefs.getString(key, "[]") ?: "[]").toMutableList()
    }.getOrElse { mutableListOf() }

    private fun save(key: String, items: List<String>) {
        prefs.edit().putString(key, json.encodeToString(items)).apply()
    }

    private fun listAdd(key: String, label: String, text: String): String {
        val t = text.trim()
        if (t.isEmpty()) return "error: nothing to add. Provide the text."
        val items = load(key)
        items.add(t)
        save(key, items)
        return "ok: saved $label #${items.size}: $t"
    }

    private fun listShow(key: String, title: String): String {
        val items = load(key)
        if (items.isEmpty()) return "$title is empty."
        return "$title:\n" + items.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n")
    }

    private fun listRemove(key: String, label: String, index: Int): String {
        val items = load(key)
        if (index < 1 || index > items.size) return "error: there is no $label with number $index (the list has ${items.size})"
        val removed = items.removeAt(index - 1)
        save(key, items)
        return "ok: removed $label #$index: $removed"
    }

    // ---------------- app intents ----------------

    private fun startFirst(ctx: Context, vararg intents: Intent): Boolean {
        for (i in intents) {
            try {
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: Exception) {
                // try the next fallback
            }
        }
        return false
    }

    private fun composeEmail(ctx: Context, to: String, subject: String, body: String): String {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            val recipients = to.split(",", ";").map { it.trim() }.filter { it.isNotEmpty() }
            if (recipients.isNotEmpty()) putExtra(Intent.EXTRA_EMAIL, recipients.toTypedArray())
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        return if (startFirst(ctx, intent)) "ok: opened the mail app with a draft. It has NOT been sent; tapping Send needs the user's approval."
        else "error: no email app is available"
    }

    private fun youtubeSearch(ctx: Context, query: String): String {
        val q = query.trim()
        if (q.isEmpty()) return "error: youtube_search needs a query"
        val uri = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(q)}")
        val ok = startFirst(
            ctx,
            Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.youtube"),
            Intent(Intent.ACTION_VIEW, uri),
        )
        return if (ok) "ok: opened YouTube results for \"$q\"" else "error: cannot open YouTube"
    }

    private fun mapsSearch(ctx: Context, query: String): String {
        val q = query.trim()
        if (q.isEmpty()) return "error: maps_search needs a place or query"
        val ok = startFirst(
            ctx,
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(q)}")).setPackage("com.google.android.apps.maps"),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(q)}")),
        )
        return if (ok) "ok: opened Maps for \"$q\"" else "error: cannot open Maps"
    }

    // ---------------- reading the screen: accessibility text + OCR ----------------

    private suspend fun readScreen(svc: PhoneAgentAccessibilityService, forceOcr: Boolean): String {
        if (!forceOcr) {
            val text = svc.visibleText()
            if (text.length >= 20) return "Visible text:\n$text"
        }
        val bmp = svc.screenshotBitmap(1440)
            ?: return "error: cannot capture the screen for OCR (needs Android 11+ and the accessibility service)"
        val ocr = recognize(bmp)
        return if (forceOcr) "OCR text:\n$ocr" else "The screen exposed no text, so I used OCR:\n$ocr"
    }

    private suspend fun recognize(bmp: Bitmap): String = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { r ->
                if (cont.isActive) cont.resume(r.text.trim().ifEmpty { "(no text recognised)" }.take(4000))
            }
            .addOnFailureListener { e ->
                if (cont.isActive) cont.resume("error: OCR failed: ${e.message}")
            }
    }

    private companion object {
        const val KEY_TODO = "todo"
        const val KEY_NOTES = "notes"
    }
}
