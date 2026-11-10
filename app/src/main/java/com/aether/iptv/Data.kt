package com.aether.iptv

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Xml
import android.widget.ImageView
import org.xmlpull.v1.XmlPullParser
import org.json.JSONArray
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

fun c(v: Long): Int = v.toInt()

object Config {
    const val PLAYLIST = "https://iptv-org.github.io/iptv/index.m3u"

    // Paste your hosted guide.xml / guide.xml.gz link here (see README, EPG part).
    // Example: "https://YOURNAME.github.io/epg/guide.xml"   Empty = EPG off.
    const val EPG_URL = ""

    const val REFRESH_MS = 6L * 3600 * 1000
}

data class Channel(
    val num: Int, val name: String, val url: String, val group: String,
    val logo: String, val tvgId: String, val referrer: String?, val ua: String?
)

data class Prog(val start: Long, val stop: Long, val title: String)

object Net {
    /** Downloads url to dest if the cached copy is older than maxAgeMs. Returns true if dest is usable. */
    fun fetch(url: String, dest: File, maxAgeMs: Long): Boolean {
        if (dest.exists() && System.currentTimeMillis() - dest.lastModified() < maxAgeMs) return true
        return try {
            val con = URL(url).openConnection() as HttpURLConnection
            con.connectTimeout = 15000
            con.readTimeout = 30000
            con.instanceFollowRedirects = true
            con.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (con.responseCode !in 200..299) throw RuntimeException("HTTP ${con.responseCode}")
            val tmp = File(dest.path + ".tmp")
            con.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 65536) } }
            dest.delete()
            tmp.renameTo(dest)
            true
        } catch (e: Exception) {
            dest.exists()
        }
    }
}

/** User-added IPTV sources. Source 0 = built-in playlist ("TV 1"); added ones become TV 2, TV 3 ... */
object Sources {
    private const val KEY = "srcs"

    fun extras(p: SharedPreferences): MutableList<String> {
        val out = ArrayList<String>()
        try {
            val a = JSONArray(p.getString(KEY, "[]"))
            for (i in 0 until a.length()) out.add(a.getString(i))
        } catch (_: Exception) {}
        return out
    }

    fun save(p: SharedPreferences, list: List<String>) {
        val a = JSONArray()
        for (u in list) a.put(u)
        p.edit().putString(KEY, a.toString()).apply()
    }

    fun all(p: SharedPreferences): List<String> = listOf(Config.PLAYLIST) + extras(p)

    fun name(sourceIndex: Int) = "TV ${sourceIndex + 1}"

    fun cacheName(url: String) =
        if (url == Config.PLAYLIST) "playlist.m3u" else "src_" + Integer.toHexString(url.hashCode()) + ".m3u"

    /** Returns a clean http/https URL or null if it is not a usable link. */
    fun normalize(raw: String): String? {
        var u = raw.trim()
        if (u.isEmpty() || u.any { it.isWhitespace() }) return null
        if (!u.contains("://")) u = "http://$u"
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) return null
        return try { if (URL(u).host.isNullOrEmpty()) null else u } catch (_: Exception) { null }
    }
}


/** true = dest holds a playlist (many channels); false = the link is a single stream; null = could not reach it. */
fun Net.fetchSource(url: String, dest: File, maxAgeMs: Long): Boolean? {
    if (dest.exists() && System.currentTimeMillis() - dest.lastModified() < maxAgeMs) return true
    if (!url.startsWith("http", true)) return false
    return try {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 15000
        con.readTimeout = 30000
        con.instanceFollowRedirects = true
        con.setRequestProperty("User-Agent", "Mozilla/5.0")
        if (con.responseCode !in 200..299) throw RuntimeException("HTTP ${con.responseCode}")
        val ins = BufferedInputStream(con.inputStream, 65536)
        ins.mark(4096)
        val buf = ByteArray(4096)
        val n = ins.read(buf).coerceAtLeast(0)
        ins.reset()
        val head = String(buf, 0, n, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (head.startsWith("#EXTM3U") && head.contains("#EXTINF") && !head.contains("#EXT-X-")) {
            val tmp = File(dest.path + ".tmp")
            ins.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 65536) } }
            dest.delete()
            tmp.renameTo(dest)
            true
        } else {
            ins.close(); con.disconnect()
            false
        }
    } catch (e: Exception) {
        if (dest.exists()) true else null
    }
}

object M3U {
    private val attr = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")

    fun parse(f: File): List<Channel> {
        val out = ArrayList<Channel>(12000)
        var have = false
        var name = ""; var group = "Undefined"; var logo = ""; var id = ""
        var ref: String? = null; var ua: String? = null
        f.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val s = raw.trim()
                if (s.isEmpty()) continue
                if (s.startsWith("#EXTINF")) {
                    have = true; ref = null; ua = null
                    group = "Undefined"; logo = ""; id = ""
                    val q = s.lastIndexOf('"')
                    val comma = s.indexOf(',', if (q > 0) q else 0)
                    name = if (comma >= 0) s.substring(comma + 1).trim() else ""
                    val head = if (comma >= 0) s.substring(0, comma) else s
                    for (m in attr.findAll(head)) {
                        val v = m.groupValues[2]
                        when (m.groupValues[1].lowercase()) {
                            "tvg-id" -> id = v
                            "tvg-logo" -> logo = v
                            "group-title" -> group = v.substringBefore(';').trim().ifEmpty { "Undefined" }
                        }
                    }
                } else if (s.startsWith("#EXTVLCOPT:")) {
                    val kv = s.substring(11)
                    when {
                        kv.startsWith("http-referrer=") || kv.startsWith("http-referer=") -> ref = kv.substringAfter('=')
                        kv.startsWith("http-user-agent=") -> ua = kv.substringAfter('=')
                    }
                } else if (!s.startsWith("#") && have) {
                    val n = out.size + 1
                    out.add(Channel(n, name.ifEmpty { "Channel $n" }, s, group, logo, id, ref, ua))
                    have = false
                }
            }
        }
        return out
    }
}

object Epg {
    @Volatile var data: Map<String, List<Prog>> = emptyMap()

    // playlist ids look like "BBCOne.uk@SD", guide ids like "BBCOne.uk"
    fun key(id: String): String = id.substringBefore('@').lowercase()

    fun now(id: String): Prog? {
        val l = data[key(id)] ?: return null
        val t = System.currentTimeMillis()
        return l.firstOrNull { t >= it.start && t < it.stop }
    }

    fun next(id: String): Prog? {
        val l = data[key(id)] ?: return null
        val t = System.currentTimeMillis()
        return l.firstOrNull { it.start > t }
    }

    fun load(f: File, wanted: Set<String>) {
        val raw = f.inputStream().buffered(65536)
        raw.mark(2)
        val b0 = raw.read(); val b1 = raw.read()
        raw.reset()
        val ins = if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(raw, 65536) else raw
        val p = Xml.newPullParser()
        p.setInput(ins, null)
        val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        val now = System.currentTimeMillis()
        val horizon = now + 36L * 3600 * 1000
        val map = HashMap<String, ArrayList<Prog>>()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.name == "programme") {
                val ch = key(p.getAttributeValue(null, "channel") ?: "")
                val st = p.getAttributeValue(null, "start")
                val sp = p.getAttributeValue(null, "stop")
                var title = ""
                while (true) {
                    val e = p.next()
                    if (e == XmlPullParser.END_DOCUMENT) break
                    if (e == XmlPullParser.END_TAG && p.name == "programme") break
                    if (e == XmlPullParser.START_TAG && p.name == "title" && title.isEmpty()) title = p.nextText().trim()
                }
                if (ch in wanted && st != null && sp != null) {
                    try {
                        val a = fmt.parse(st)!!.time
                        val b = fmt.parse(sp)!!.time
                        if (b > now && a < horizon) map.getOrPut(ch) { ArrayList() }.add(Prog(a, b, title))
                    } catch (_: Exception) {}
                }
            }
            ev = p.next()
        }
        for (l in map.values) l.sortBy { it.start }
        data = map
    }
}

object Img {
    private val cache = object : LruCache<String, Bitmap>(20 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun load(url: String, v: ImageView) {
        v.tag = url
        v.setImageDrawable(null)
        if (url.isEmpty()) return
        cache.get(url)?.let { v.setImageBitmap(it); return }
        pool.execute {
            try {
                val con = URL(url).openConnection() as HttpURLConnection
                con.connectTimeout = 6000; con.readTimeout = 6000
                val bytes = con.inputStream.use { it.readBytes() }
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var s = 1
                while (o.outWidth > 0 && o.outWidth / s > 256) s *= 2
                val bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
                if (bm != null) {
                    cache.put(url, bm)
                    main.post { if (v.tag == url) v.setImageBitmap(bm) }
                }
            } catch (_: Exception) {}
        }
    }
}
