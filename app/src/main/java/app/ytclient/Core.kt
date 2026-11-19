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

/** One video, normalised across feed / browse / search payloads. */
data class Video(
    val id: String,
    val title: String,
    val channelName: String? = null,
    val channelId: String? = null,
    val duration: String? = null,
    val views: String? = null,
    val published: String? = null,
    val description: String? = null,
) {
    fun meta(): String = listOfNotNull(
        channelName,
        duration?.let { "$it" },
        views,
        published,
    ).joinToString(" · ")

    companion object {
        fun from(o: JSONObject): Video {
            fun s(k: String): String? = if (o.isNull(k)) null else o.optString(k).ifBlank { null }
            val viewsRaw = if (o.isNull("view_count")) null else o.opt("view_count")
            val views = s("view_count_text") ?: (viewsRaw as? Number)?.let { "%,d 回視聴".format(it.toLong()) }
            val published = s("published_text") ?: s("published_at")?.let { it.substring(0, 10) }
            return Video(
                id = o.optString("video_id"),
                title = s("title") ?: "(無題)",
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
