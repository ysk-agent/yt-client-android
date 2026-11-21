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
        Img.init(cacheDir)
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

/**
 * Thumbnail loader with two cache levels (no Glide):
 *  1. in-memory LruCache (24 MB),
 *  2. on-disk cache in cacheDir/thumbs — one file per URL named by the
 *     SHA-256 hex of the URL, holding the raw downloaded JPEG bytes.
 * Disk entries live 7 days from lastModified and the directory is capped
 * at 100 MB (oldest files evicted first). A fresh disk hit is decoded and
 * shown with no network access at all, so revisiting a feed on a slow
 * connection is instant. Load order: memory -> disk (fresh) -> network.
 * Concurrent loads of the same URL share one download/decode.
 */
object Img {
    private const val TTL_MS = 7L * 24 * 60 * 60 * 1000
    private const val CAP_BYTES = 100L * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private val exec = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var thumbsDir: java.io.File? = null
    private val diskLock = Object()
    /** URL -> views waiting for the one in-flight disk/network fetch. */
    private val inFlight = HashMap<String, MutableList<ImageView>>()

    /** Sets up the disk cache and runs one cleanup pass (expired files,
     * then the size cap) on a background thread. */
    fun init(cacheDir: java.io.File) {
        val dir = java.io.File(cacheDir, "thumbs")
        thumbsDir = dir
        exec.execute {
            synchronized(diskLock) {
                try {
                    if (!dir.exists()) dir.mkdirs()
                    // Leftover temp files from interrupted writes.
                    dir.listFiles()?.filter { it.name.contains(".tmp") }
                        ?.forEach { it.delete() }
                    pruneLocked()
                } catch (e: Exception) { /* cache is best-effort */ }
            }
        }
    }

    fun thumbUrl(videoId: String, size: String = "hqdefault") =
        "https://i.ytimg.com/vi/$videoId/$size.jpg"

    private fun fileFor(url: String): java.io.File? {
        val dir = thumbsDir ?: return null
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return java.io.File(dir, "$hex.jpg")
    }

    private fun isFresh(f: java.io.File): Boolean =
        f.exists() && System.currentTimeMillis() - f.lastModified() < TTL_MS

    /** Deletes expired entries, then evicts oldest-first until the total
     * size is under the cap. Caller must hold diskLock. */
    private fun pruneLocked() {
        val dir = thumbsDir ?: return
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".jpg") }
            ?: return
        val now = System.currentTimeMillis()
        val alive = files.filter { f ->
            if (now - f.lastModified() >= TTL_MS) { f.delete(); false } else true
        }
        var total = alive.sumOf { it.length() }
        if (total <= CAP_BYTES) return
        for (f in alive.sortedBy { it.lastModified() }) {
            if (total <= CAP_BYTES) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    /** Decodes [f] downsampled so the result is ~320–640 px wide
     * (hqdefault sources are 480x360, which pass through unsampled). */
    private fun decodeSampled(f: java.io.File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 320) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(f.absolutePath, opts)
    }

    /** Downloads [url] into [dest] atomically: bytes go to a temp file in
     * the same directory and are renamed into place only on success. */
    private fun download(url: String, dest: java.io.File): Boolean {
        val tmp = java.io.File(dest.parentFile, dest.name + ".tmp-" + System.nanoTime())
        return try {
            dest.parentFile?.mkdirs()
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000; conn.readTimeout = 8000
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                tmp.delete(); return false
            }
            conn.inputStream.use { input ->
                java.io.FileOutputStream(tmp).use { input.copyTo(it) }
            }
            if (tmp.length() <= 0L) { tmp.delete(); return false }
            synchronized(diskLock) {
                if (!tmp.renameTo(dest)) { tmp.delete(); return false }
                pruneLocked()
            }
            true
        } catch (e: Exception) {
            tmp.delete()
            false
        }
    }

    fun load(iv: ImageView, url: String) {
        iv.tag = url
        cache.get(url)?.let { iv.setImageBitmap(it); return }
        iv.setImageBitmap(null)
        // In-flight dedup: if this URL is already being fetched, just
        // queue this view for the shared result.
        synchronized(inFlight) {
            val waiters = inFlight[url]
            if (waiters != null) { waiters.add(iv); return }
            inFlight[url] = mutableListOf(iv)
        }
        exec.execute {
            var bmp: Bitmap? = null
            try {
                val file = fileFor(url)
                if (file != null && isFresh(file)) {
                    bmp = decodeSampled(file)
                    if (bmp == null) {
                        // Corrupt entry: drop it and fall through to network.
                        synchronized(diskLock) { file.delete() }
                    }
                }
                if (bmp == null && file != null) {
                    if (download(url, file)) bmp = decodeSampled(file)
                }
                if (bmp == null && file == null) {
                    // Disk cache not initialised: plain network decode.
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 8000; conn.readTimeout = 8000
                    conn.inputStream.use { bmp = BitmapFactory.decodeStream(it) }
                }
            } catch (e: Exception) { /* leave placeholder */ }
            val b = bmp
            if (b != null) cache.put(url, b)
            val targets = synchronized(inFlight) { inFlight.remove(url) } ?: emptyList()
            if (b != null) {
                main.post {
                    for (v in targets) if (v.tag == url) v.setImageBitmap(b)
                }
            }
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

    /** Last group shown on the home feed, so a restart reopens where the
     * user left off instead of always resetting to the default group. */
    fun lastGroup(ctx: android.content.Context): String? =
        ctx.getSharedPreferences(FILE, 0).getString("last_group", null)
    fun setLastGroup(ctx: android.content.Context, group: String) {
        ctx.getSharedPreferences(FILE, 0).edit().putString("last_group", group).apply()
    }

    /**
     * Scroll anchor of a group's feed: the videoId of the first visible
     * row plus its pixel offset from the list top. Restoring by videoId
     * (not by index) keeps the user's place even when a refresh inserts
     * newer items above it. Stored as "<videoId>|<offsetPx>".
     */
    fun feedAnchor(ctx: android.content.Context, group: String): Pair<String, Int>? {
        val raw = ctx.getSharedPreferences(FILE, 0).getString("feed_anchor_$group", null)
            ?: return null
        val sep = raw.lastIndexOf('|')
        if (sep <= 0) return null
        val off = raw.substring(sep + 1).toIntOrNull() ?: return null
        return raw.substring(0, sep) to off
    }
    fun setFeedAnchor(ctx: android.content.Context, group: String, videoId: String, offsetPx: Int) {
        ctx.getSharedPreferences(FILE, 0).edit()
            .putString("feed_anchor_$group", "$videoId|$offsetPx").apply()
    }

    /** Search conditions (sort / filters / locale / operator assist /
     * on-device filters) as one JSON object written by SearchActivity,
     * so a search reopens with the conditions the user last used. */
    fun searchConditions(ctx: android.content.Context): String? =
        ctx.getSharedPreferences(FILE, 0).getString("search_conditions", null)
    fun setSearchConditions(ctx: android.content.Context, json: String) {
        ctx.getSharedPreferences(FILE, 0).edit().putString("search_conditions", json).apply()
    }

    /** The playback rate the user last chose in the player, remembered
     * app-wide: a newly opened video starts at this rate. */
    fun playbackRate(ctx: android.content.Context): Double =
        ctx.getSharedPreferences(FILE, 0).getFloat("playback_rate", 1.0f).toDouble()
    fun setPlaybackRate(ctx: android.content.Context, rate: Double) {
        ctx.getSharedPreferences(FILE, 0).edit().putFloat("playback_rate", rate.toFloat()).apply()
    }

    /**
     * Per-video resume positions, seconds, as one JSON object
     * {videoId: seconds}. Written by the player while a video plays and
     * read back when the same video is opened again; an entry is dropped
     * when the video ends or plays into its last seconds, so a finished
     * video starts from the beginning next time. Capped at 300 entries
     * (arbitrary eviction beyond that — the map is a convenience cache,
     * not a record).
     */
    private fun positionsJson(ctx: android.content.Context): JSONObject {
        val raw = ctx.getSharedPreferences(FILE, 0).getString("positions_v1", null)
        return try { if (raw != null) JSONObject(raw) else JSONObject() } catch (e: Exception) { JSONObject() }
    }
    fun position(ctx: android.content.Context, videoId: String): Double {
        if (videoId.isBlank()) return 0.0
        return positionsJson(ctx).optDouble(videoId, 0.0)
    }
    fun setPosition(ctx: android.content.Context, videoId: String, seconds: Double) {
        if (videoId.isBlank() || seconds <= 0.0) return
        val map = positionsJson(ctx)
        map.put(videoId, seconds)
        if (map.length() > 300) {
            val keys = map.keys()
            while (map.length() > 300 && keys.hasNext()) map.remove(keys.next())
        }
        ctx.getSharedPreferences(FILE, 0).edit().putString("positions_v1", map.toString()).apply()
    }
    fun clearPosition(ctx: android.content.Context, videoId: String) {
        if (videoId.isBlank()) return
        val map = positionsJson(ctx)
        if (!map.has(videoId)) return
        map.remove(videoId)
        ctx.getSharedPreferences(FILE, 0).edit().putString("positions_v1", map.toString()).apply()
    }

    /**
     * Gemini formatting prompts for the Detail screen, kept per caption
     * language: what needs fixing differs by source language (Japanese
     * ASR cleanup vs. English-to-Japanese translation, …), so one prompt
     * cannot serve both well. The pipeline only ever sends the stored
     * prompt for the track's language plus the caption blocks, and never
     * interprets it, so behaviour is changed by editing text alone.
     * Timestamps are NOT part of the prompt's business any more: the
     * pipeline owns them in code (blocks are split at stamp positions,
     * stamps are re-attached afterwards), so no default prompt mentions
     * them. Stored in SharedPreferences once the user edits a language's
     * prompt; the DEFAULT_* texts are the built-in defaults.
     */
    val DEFAULT_FORMAT_PROMPT: String = listOf(
        "以下は動画の自動生成字幕テキストです。次の方針で整形してください。",
        "1. 句読点と改行を補って、読みやすくしてください。",
        "2. 意味を持たないフィラーは削除してください。言い淀みや相づちの「えー」「あのー」「うん」「えっと」「うーん」のほか、文頭や文中で意味に寄与しない「ま」「まあ」もフィラーです。ただし「まあいい」のように意味を持つ場合は残してください。また、言い淀みによる同じ語の単純な繰り返しは1回にまとめてください。",
        "3. 音声認識の誤りは、文脈から明らかなものは正しい語に直してください。とくに、同音で漢字だけが違う取り違え、その話題の分野の専門用語への誤認、意味の通らない漢字列の音からの推測に注意してください。ただし、置き換えは元の音に近い語に限ってください。音が大きく異なる別の語に置き換えることはしないでください。数値そのものも変更しないでください。",
        "4. 話し言葉の冗長な言い回しは、意味を変えない範囲で自然な文章に言い換えてかまいません。ただし要約、内容の追加や削除、丁寧な文体への言い換えはしないでください。文を丸ごと落とすことも削除に含まれます。冒頭の挨拶や自己紹介も残してください。",
        "5. 出力は整形後の本文のみにしてください。",
    ).joinToString("\n")

    /** Built-in default for English tracks: translate + format to Japanese. */
    val DEFAULT_FORMAT_PROMPT_EN: String = listOf(
        "以下は動画の英語の自動生成字幕テキストです。次の方針で、日本語の字幕として整形してください。",
        "1. 英語を自然な日本語に翻訳し、句読点と改行を補って読みやすくしてください。",
        "2. 英語側の認識や翻訳の誤りは、文脈から明らかなものは正しい内容に直してください。固有名詞（人名、会社名、商品名、制度名）は、一般的な日本語表記に直してください。",
        "3. 文頭の >> は話者交代の印です。出力には含めないでください。",
        "4. 数値は原則として変更しないでください。ただし数値自体が明らかに壊れていて文脈と合わない場合に限り、文脈に合う数値に直してください。",
        "5. 要約、内容の追加や削除はしないでください。",
        "6. 出力は日本語の本文のみにしてください。",
    ).joinToString("\n")

    fun defaultFormatPrompt(lang: String): String =
        if (lang.startsWith("en")) DEFAULT_FORMAT_PROMPT_EN else DEFAULT_FORMAT_PROMPT

    fun formatPrompt(ctx: android.content.Context, lang: String): String {
        val prefs = ctx.getSharedPreferences(FILE, 0)
        prefs.getString("format_prompt_$lang", null)?.let { return it }
        // v0.1.6 stored a single language-less prompt; it was edited
        // against Japanese tracks, so it carries over to Japanese only.
        if (lang.startsWith("ja")) {
            prefs.getString("format_prompt", null)?.let { return it }
        }
        return defaultFormatPrompt(lang)
    }
    fun setFormatPrompt(ctx: android.content.Context, lang: String, prompt: String) {
        ctx.getSharedPreferences(FILE, 0).edit()
            .putString("format_prompt_$lang", prompt).apply()
    }
    fun resetFormatPrompt(ctx: android.content.Context, lang: String) {
        val edit = ctx.getSharedPreferences(FILE, 0).edit()
            .remove("format_prompt_$lang")
        if (lang.startsWith("ja")) edit.remove("format_prompt")
        edit.apply()
    }
}

/**
 * Persisted home-feed items, one file per group under filesDir, holding
 * the bridge's raw items array wrapped as {"group": name, "items": […]}.
 * The home screen renders this instantly on open (a restart or a group
 * switch never starts from an empty list) and then refreshes from the
 * network in the background; the scroll anchor (Prefs.feedAnchor) is
 * what keeps the user's place across that replacement. Writes run on a
 * background executor; reads are small enough for the caller's thread in
 * practice (worst case ~2,300 feed rows ≈ 1 MB of JSON).
 */
object FeedCache {
    private val exec = Executors.newSingleThreadExecutor()

    private fun file(ctx: android.content.Context, group: String): java.io.File {
        val safe = group.map { c -> if (c.isLetterOrDigit()) c else '_' }.joinToString("")
        return java.io.File(ctx.filesDir, "feedcache_$safe.json")
    }

    fun load(ctx: android.content.Context, group: String): List<Video>? {
        return try {
            val f = file(ctx, group)
            if (!f.exists()) return null
            val root = JSONObject(f.readText())
            if (root.optString("group") != group) return null
            val items = Video.list(root.optJSONArray("items")).filter { !it.isShorts }
            items.ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    fun save(ctx: android.content.Context, group: String, items: JSONArray?) {
        if (items == null) return
        val text = JSONObject().put("group", group).put("items", items).toString()
        val f = file(ctx, group)
        exec.execute {
            try {
                val tmp = java.io.File(f.parentFile, f.name + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(f)) tmp.delete()
            } catch (e: Exception) { /* cache is best-effort */ }
        }
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
    /** Feed payloads carry this flag (Atom /shorts/ link). The bridge
     * already drops Shorts from feeds; the UI filters again as defence
     * in depth. Absent on search/browse payloads, where it stays false. */
    val isShorts: Boolean = false,
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
                isShorts = o.optBoolean("is_shorts", false),
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
