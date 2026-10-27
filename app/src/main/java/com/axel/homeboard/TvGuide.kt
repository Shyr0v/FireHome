package com.axel.homeboard

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import android.util.Xml
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser

/** Une chaîne suivie et l'app à ouvrir pour la regarder. */
data class GuideChannel(val name: String, val aliases: List<String>, val appKeyword: String)

val GUIDE_CHANNELS = listOf(
    GuideChannel("TF1", listOf("tf1"), "tf1"),
    GuideChannel("France 2", listOf("france2"), "francetv"),
    GuideChannel("France 3", listOf("france3"), "francetv"),
    GuideChannel("Canal+", listOf("canal+", "canalplus"), "canal"),
    GuideChannel("France 5", listOf("france5"), "francetv"),
    GuideChannel("M6", listOf("m6"), "m6"),
    GuideChannel("Arte", listOf("arte"), "arte"),
)

data class TvShow(
    val channel: String,
    val title: String,
    val category: String?,
    val start: OffsetDateTime,
    val stop: OffsetDateTime,
    val image: String?,
)

data class TvEvening(val day: LocalDate, val isToday: Boolean, val shows: List<TvShow>)

/** "France 2" -> "france2", "CANAL+" -> "canal+", "france.tv" -> "francetv" */
fun normalizeName(s: String) = s.lowercase().filter { it.isLetterOrDigit() || it == '+' }

object TvGuide {
    private val ZONE = ZoneId.of("Europe/Paris")
    private val PRIME_TIME = LocalTime.of(21, 15)
    private val XMLTV_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z")

    /** Flux XMLTV public de xmltvfr.fr (projet racacax/XML-TV-Fr). */
    private val SOURCES = listOf(
        "https://xmltvfr.fr/xmltv/xmltv_tnt.xml.gz",
        "https://xmltvfr.fr/xmltv/xmltv_tnt.xml",
    )

    @Volatile
    var lastError: String? = null
        private set

    /** Programme de ce soir (ou de demain soir s'il est tard), mis en cache une fois par jour. */
    suspend fun evening(ctx: Context): TvEvening = withContext(Dispatchers.IO) {
        val now = OffsetDateTime.now(ZONE)
        val today = now.toLocalDate()
        val day = if (now.toLocalTime().isAfter(LocalTime.of(23, 30))) today.plusDays(1) else today
        val cache = File(ctx.filesDir, "guide_$day.json")

        val shows = readCache(cache) ?: download(day)?.also { list ->
            if (list.isNotEmpty()) {
                ctx.filesDir.listFiles { f -> f.name.startsWith("guide_") }?.forEach { it.delete() }
                writeCache(cache, list)
            }
        } ?: emptyList()

        TvEvening(day, day == today, shows)
    }

    private fun download(day: LocalDate): List<TvShow>? {
        val target = day.atTime(PRIME_TIME).atZone(ZONE).toOffsetDateTime()
        for (src in SOURCES) {
            try {
                val conn = URL(src).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
                conn.setRequestProperty("User-Agent", "FireHome")
                if (conn.responseCode != 200) {
                    lastError = "$src : HTTP ${conn.responseCode}"
                    conn.disconnect(); continue
                }
                val raw = conn.inputStream.buffered()
                val input: InputStream = if (src.endsWith(".gz")) GZIPInputStream(raw) else raw
                val result = input.use { parse(it, target) }
                conn.disconnect()
                if (result.isNotEmpty()) { lastError = null; return result }
                lastError = "$src : aucune émission trouvée"
            } catch (e: Exception) {
                lastError = "$src : ${e.javaClass.simpleName} ${e.message.orEmpty()}"
            }
        }
        return null
    }

    /** Lecture en flux (le fichier est gros) : on ne garde que l'émission de 21h15 de chaque chaîne suivie. */
    private fun parse(input: InputStream, target: OffsetDateTime): List<TvShow> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, "UTF-8")

        val idToChannel = HashMap<String, GuideChannel>()
        val found = LinkedHashMap<String, TvShow>()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> {
                        val id = parser.getAttributeValue(null, "id")
                        readChannelNames(parser).forEach { n ->
                            GUIDE_CHANNELS.firstOrNull { normalizeName(n) in it.aliases }?.let { ch ->
                                if (id != null && idToChannel.values.none { it == ch }) idToChannel[id] = ch
                            }
                        }
                    }
                    "programme" -> {
                        val ch = idToChannel[parser.getAttributeValue(null, "channel")]
                        val start = parser.getAttributeValue(null, "start")?.let(::parseTime)
                        val stop = parser.getAttributeValue(null, "stop")?.let(::parseTime)
                        if (ch != null && ch.name !in found && start != null && stop != null &&
                            !start.isAfter(target) && stop.isAfter(target)
                        ) {
                            found[ch.name] = readProgramme(parser, ch.name, start, stop)
                            if (found.size == idToChannel.size && found.size == GUIDE_CHANNELS.size) break
                        } else {
                            skip(parser)
                        }
                    }
                }
            }
            event = parser.next()
        }
        return GUIDE_CHANNELS.mapNotNull { found[it.name] }
    }

    private fun readChannelNames(p: XmlPullParser): List<String> {
        val names = mutableListOf<String>()
        val depth = p.depth
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "display-name") names += p.nextText()
            if (p.eventType == XmlPullParser.END_DOCUMENT) break
        }
        return names
    }

    private fun readProgramme(p: XmlPullParser, channel: String, start: OffsetDateTime, stop: OffsetDateTime): TvShow {
        var title: String? = null
        var category: String? = null
        var image: String? = null
        val depth = p.depth
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
            if (p.eventType == XmlPullParser.END_DOCUMENT) break
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.name) {
                "title" -> if (title == null) title = p.nextText()
                "category" -> if (category == null) category = p.nextText()
                "icon" -> if (image == null) image = p.getAttributeValue(null, "src")
            }
        }
        return TvShow(channel, title ?: "Programme", category, start, stop, image)
    }

    private fun skip(p: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private fun parseTime(s: String): OffsetDateTime? =
        runCatching { OffsetDateTime.parse(s.trim(), XMLTV_TIME) }.getOrNull()

    /* ---------- cache JSON (un fichier par soirée) ---------- */

    private fun writeCache(f: File, list: List<TvShow>) = runCatching {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("channel", it.channel); put("title", it.title)
                put("category", it.category ?: ""); put("image", it.image ?: "")
                put("start", it.start.toString()); put("stop", it.stop.toString())
            })
        }
        f.writeText(arr.toString())
    }

    private fun readCache(f: File): List<TvShow>? = runCatching {
        if (!f.exists()) return null
        val arr = JSONArray(f.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            TvShow(
                channel = o.getString("channel"),
                title = o.getString("title"),
                category = o.optString("category").ifBlank { null },
                start = OffsetDateTime.parse(o.getString("start")),
                stop = OffsetDateTime.parse(o.getString("stop")),
                image = o.optString("image").ifBlank { null },
            )
        }.takeIf { it.isNotEmpty() }
    }.getOrNull()
}

/** Petit chargeur d'images (affiches des émissions), avec cache mémoire. */
object ImageCache {
    private val mem = LruCache<String, ImageBitmap>(24)

    suspend fun load(url: String, maxWidth: Int = 480): ImageBitmap? = withContext(Dispatchers.IO) {
        mem.get(url) ?: runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.asImageBitmap()
        }.getOrNull()?.also { mem.put(url, it) }
    }
}
