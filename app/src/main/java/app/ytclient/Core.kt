package app.ytclient

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

fun android.content.Context.dp(v: Int): Int = android.util.TypedValue.applyDimension(
    android.util.TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
).toInt()

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        Bridge.init(filesDir.absolutePath)
    }
}

/** Thin synchronous wrapper over the Python bridge module. All calls run on
 * a single background thread: Python calls must never happen on the UI thread,
 * and serialising them keeps one asyncio loop from overlapping another. */
object Bridge {
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var inited = false

    fun init(filesDir: String) {
        exec.execute {
            try {
                Python.getInstance().getModule("bridge").callAttr("init", filesDir)
                inited = true
            } catch (e: Exception) {
                android.util.Log.e("Bridge", "init failed", e)
            }
        }
    }

    /** Calls bridge.<fn>(args...) and delivers the parsed "data" object, or
     * an error string of the form "<Type>: <message>". */
    fun call(fn: String, vararg args: Any, cb: (JSONObject?, String?) -> Unit) {
        exec.execute {
            var data: JSONObject? = null
            var err: String? = null
            try {
                var tries = 0
                while (!inited && tries < 50) { Thread.sleep(100); tries++ }
                val raw = Python.getInstance().getModule("bridge")
                    .callAttr(fn, *args).toString()
                val root = JSONObject(raw)
                if (root.optBoolean("ok")) {
                    data = root.optJSONObject("data") ?: JSONObject()
                } else {
                    val e = root.optJSONObject("error")
                    err = "${e?.optString("type") ?: "Error"}: ${e?.optString("message") ?: raw}"
                }
            } catch (e: Exception) {
                err = "BridgeError: ${e.message}"
            }
            val d = data; val er = err
            main.post { cb(d, er) }
        }
    }
}

/** Minimal thumbnail loader with an in-memory cache (no Glide). */
object Img {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private val exec = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    fun thumbUrl(videoId: String, size: String = "hqdefault") =
        "https://i.ytimg.com/vi/$videoId/$size.jpg"

    fun load(iv: ImageView, url: String) {
        iv.tag = url
        cache.get(url)?.let { iv.setImageBitmap(it); return }
        iv.setImageBitmap(null)
        exec.execute {
            var bmp: Bitmap? = null
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000; conn.readTimeout = 8000
                conn.inputStream.use { bmp = BitmapFactory.decodeStream(it) }
            } catch (e: Exception) { /* leave placeholder */ }
            val b = bmp
            if (b != null) cache.put(url, b)
            main.post { if (iv.tag == url && b != null) iv.setImageBitmap(b) }
        }
    }
}

/** User preferences shared across screens. */
object Prefs {
    private const val FILE = "ytclient_prefs"
    fun thumbPct(ctx: android.content.Context): Int =
        ctx.getSharedPreferences(FILE, 0).getInt("thumb_pct", 33)
    fun setThumbPct(ctx: android.content.Context, pct: Int) {
        ctx.getSharedPreferences(FILE, 0).edit().putInt("thumb_pct", pct).apply()
    }
    fun feedUpdatedAt(ctx: android.content.Context, group: String): Long =
        ctx.getSharedPreferences(FILE, 0).getLong("feed_updated_$group", 0L)
    fun setFeedUpdatedAt(ctx: android.content.Context, group: String, at: Long) {
        ctx.getSharedPreferences(FILE, 0).edit().putLong("feed_updated_$group", at).apply()
    }
}

/** "33分前" / "19時間前" / "2日前" style relative label for an epoch-ms time. */
fun relTimeLabel(atMillis: Long, now: Long = System.currentTimeMillis()): String {
    if (atMillis <= 0L) return "—"
    val sec = (now - atMillis) / 1000
    return when {
        sec < 60 -> "たった今"
        sec < 3600 -> "${sec / 60}分前"
        sec < 86400 -> "${sec / 3600}時間前"
        else -> "${sec / 86400}日前"
    }
}

/** View count in the row format: 1097回 under 10k, else 6.0万回. */
fun formatViews(n: Long): String =
    if (n < 10000) "${n}回"
    else String.format(java.util.Locale.US, "%.1f万回", n / 10000.0)

/** Best-effort numeric view count from a localised view_count_text such as
 * "329,239回視聴", "6.0万回視聴" or "1.2M views". */
fun parseViews(text: String?): Long? {
    if (text.isNullOrBlank()) return null
    val t = text.replace(",", "").replace("，", "")
    val num = Regex("""([\d.]+)""").find(t)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
    val mult = when {
        t.contains("億") -> 100_000_000.0
        t.contains("万") -> 10_000.0
        t.contains("B", ignoreCase = true) && t.contains("view", ignoreCase = true) -> 1_000_000_000.0
        t.contains("M", ignoreCase = true) && t.contains("view", ignoreCase = true) -> 1_000_000.0
        t.contains("K", ignoreCase = true) && t.contains("view", ignoreCase = true) -> 1_000.0
        else -> 1.0
    }
    return (num * mult).toLong()
}

/** One video, normalised across feed / browse / search payloads.
 * [views] and [published] are display-ready labels: views is "1097回" /
 * "6.0万回" (never the word 再生回数), published is a relative age when the
 * payload carries a timestamp, else the client's own age text. */
data class Video(
    val id: String,
    val title: String,
    val kind: String = "video",
    val channelName: String? = null,
    val channelId: String? = null,
    val duration: String? = null,
    val views: String? = null,
    val published: String? = null,
    val description: String? = null,
) {
    val isLive: Boolean get() = duration?.equals("LIVE", ignoreCase = true) == true

    companion object {
        fun from(o: JSONObject): Video {
            fun s(k: String): String? = if (o.isNull(k)) null else o.optString(k).ifBlank { null }
            val viewsRaw = if (o.isNull("view_count")) null else o.opt("view_count")
            val views = when {
                viewsRaw is Number -> formatViews(viewsRaw.toLong())
                else -> parseViews(s("view_count_text"))?.let { formatViews(it) }
                    ?: s("view_count_text")?.replace("再生回数", "")?.removeSuffix("視聴")?.trim()?.ifBlank { null }
            }
            val published = s("published_text") ?: s("published_at")?.let { iso ->
                try {
                    val ms = java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
                    relTimeLabel(ms)
                } catch (e: Exception) { iso.substring(0, 10) }
            }
            return Video(
                id = o.optString("video_id"),
                title = s("title") ?: "(無題)",
                kind = s("kind") ?: "video",
                channelName = s("channel_name"),
                channelId = s("channel_id"),
                duration = s("duration_text"),
                views = views,
                published = published,
                description = s("description"),
            )
        }

        fun list(arr: JSONArray?): List<Video> {
            if (arr == null) return emptyList()
            val out = ArrayList<Video>()
            for (i in 0 until arr.length()) {
                val v = from(arr.getJSONObject(i))
                if (v.id.isNotBlank()) out.add(v)
            }
            return out
        }
    }
}
