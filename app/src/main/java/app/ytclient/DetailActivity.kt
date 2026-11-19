package app.ytclient

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class DetailActivity : AppCompatActivity() {
    /** Text exactly as shown in the subtitle view right now, or null when
     * no caption body is loaded. The copy-all button copies exactly this,
     * so it follows whatever is displayed (original or formatted). */
    private var currentSubtitle: String? = null
    /** The unformatted display text (timestamps thinned to ~1 per 30 s),
     * kept so 整形 can be undone with 原文に戻す. */
    private var originalSubtitle: String? = null
    /** Latest Gemini-formatted text (or its partial result after an error). */
    private var formattedSubtitle: String? = null
    private var showingFormatted = false
    /** Bumped on every track load; stale background work checks it and stops. */
    @Volatile private var loadToken = 0
    @Volatile private var formatting = false

    private var copyButton: TextView? = null
    private var formatButton: TextView? = null
    private var subtitleView: TextView? = null
    private val geminiExec = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val videoId = intent.getStringExtra("video_id") ?: ""
        val title = intent.getStringExtra("title") ?: ""
        val channelName = intent.getStringExtra("channel_name")
        val channelId = intent.getStringExtra("channel_id")
        val meta = listOfNotNull(
            intent.getStringExtra("duration"),
            intent.getStringExtra("views"),
            intent.getStringExtra("published"),
        ).joinToString(" · ")
        val description = intent.getStringExtra("description")

        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.BG) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col)

        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230))
        }
        Img.load(iv, Img.thumbUrl(videoId, "maxresdefault"))
        col.addView(iv)
        col.addView(Ui.text(this, title, 20f, Ui.TEXT, true).apply {
            setPadding(dp(16), dp(14), dp(16), 0)
        })
        if (!channelName.isNullOrBlank()) {
            col.addView(Ui.text(this, channelName, 14f, 0xFF8AB4F8.toInt(), true).apply {
                setPadding(dp(16), dp(6), dp(16), 0)
                if (!channelId.isNullOrBlank()) {
                    isClickable = true
                    setOnClickListener { Ui.openChannel(this@DetailActivity, channelId, channelName) }
                }
            })
        }
        if (meta.isNotBlank()) {
            col.addView(Ui.text(this, meta, 12.5f, Ui.MUTED).apply {
                setPadding(dp(16), dp(4), dp(16), 0)
            })
        }
        if (!description.isNullOrBlank()) {
            col.addView(Ui.text(this, description, 13f, Ui.MUTED).apply {
                setPadding(dp(16), dp(10), dp(16), 0)
                maxLines = 6
            })
        }

        col.addView(Ui.heading(this, "字幕トラック"))
        val trackStatus = Ui.text(this, "字幕を取得中…", 13f, Ui.MUTED).apply {
            setPadding(dp(16), 0, dp(16), dp(6))
        }
        col.addView(trackStatus)
        val trackBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        col.addView(trackBox)

        fun actionButton(label: String, onClick: () -> Unit): TextView =
            Ui.text(this, label, 14f, Ui.TEXT, true).apply {
                background = Ui.rounded(Ui.SURFACE, 10, this@DetailActivity)
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(10), dp(14), dp(10))
                isEnabled = false
                alpha = 0.45f
                isClickable = true
                setOnClickListener { onClick() }
            }
        val copyBtn = actionButton("字幕を一括コピー") { copyAllSubtitles() }
        copyButton = copyBtn
        val fmtBtn = actionButton("整形") { onFormatClicked() }
        formatButton = fmtBtn
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(2), dp(16), 0)
        }
        actionRow.addView(copyBtn)
        (copyBtn.layoutParams as LinearLayout.LayoutParams).apply {
            width = 0
            weight = 1f
            marginEnd = dp(8)
        }
        actionRow.addView(fmtBtn)
        (fmtBtn.layoutParams as LinearLayout.LayoutParams).apply {
            width = 0
            weight = 1f
        }
        col.addView(actionRow)

        val subView = Ui.text(this, "", 13.5f, Ui.TEXT).apply {
            setPadding(dp(16), dp(12), dp(16), dp(32))
            // Long-press selection with copy handles for the caption body.
            setTextIsSelectable(true)
        }
        subtitleView = subView
        col.addView(subView)
        setContentView(scroll)

        Bridge.call("caption_tracks", videoId) { data, err ->
            if (err != null || data == null) {
                trackStatus.text = "字幕エラー: $err"
                return@call
            }
            val tracks = data.optJSONArray("tracks") ?: JSONArray()
            if (tracks.length() == 0) {
                trackStatus.text = "字幕トラックはありません"
                return@call
            }
            trackStatus.text = "${tracks.length()} トラック（タップで本文を表示）"
            for (i in 0 until tracks.length()) {
                val t = tracks.getJSONObject(i)
                val lang = t.optString("language_code")
                val kind = if (t.isNull("kind")) "" else t.optString("kind")
                val label = buildString {
                    append(t.optString("name").ifBlank { lang })
                    append("  [$lang]")
                    if (kind == "asr") append("  自動生成")
                }
                val tv = Ui.text(this, label, 14f, Ui.TEXT).apply {
                    background = Ui.rounded(Ui.SURFACE, 10, this@DetailActivity)
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    isClickable = true
                    setOnClickListener { loadSubtitle(videoId, lang, kind, subView) }
                }
                trackBox.addView(tv)
                (tv.layoutParams as LinearLayout.LayoutParams).apply {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    bottomMargin = dp(8)
                }
            }
        }
    }

    override fun onDestroy() {
        loadToken++
        formatting = false
        geminiExec.shutdown()
        super.onDestroy()
    }

    private fun loadSubtitle(videoId: String, lang: String, kind: String, out: TextView) {
        loadToken++
        formatting = false
        currentSubtitle = null
        originalSubtitle = null
        formattedSubtitle = null
        showingFormatted = false
        formatButton?.text = "整形"
        setActionsEnabled(false)
        out.text = "字幕本文を取得中…"
        Bridge.call("fetch_subtitle", videoId, lang, kind) { data, err ->
            if (err != null || data == null) {
                out.text = "字幕本文エラー: $err"
                return@call
            }
            val segs = data.optJSONArray("segments") ?: JSONArray()
            // Display assembly only (segment data itself is untouched):
            // a [m:ss] stamp is shown on the first line and then only on
            // lines at least 30 s after the last shown stamp, so the body
            // is not a solid wall of timestamps.
            val sb = StringBuilder()
            var lastShownMs = -TIMESTAMP_INTERVAL_MS
            for (i in 0 until segs.length()) {
                val s = segs.getJSONObject(i)
                val start = s.optLong("start_ms")
                val text = s.optString("text")
                if (start - lastShownMs >= TIMESTAMP_INTERVAL_MS) {
                    sb.append("[%d:%02d] %s\n".format(start / 60000, (start / 1000) % 60, text))
                    lastShownMs = start
                } else {
                    sb.append(text).append('\n')
                }
            }
            if (sb.isEmpty()) {
                out.text = "（セグメントなし）"
            } else {
                originalSubtitle = sb.toString()
                displayText(sb.toString())
                setActionsEnabled(true)
            }
        }
    }

    /** Sets the subtitle view content and the copy-all source together. */
    private fun displayText(text: String) {
        currentSubtitle = text
        subtitleView?.text = text
    }

    private fun setActionsEnabled(enabled: Boolean) {
        for (b in listOf(copyButton, formatButton)) {
            b?.apply {
                isEnabled = enabled
                alpha = if (enabled) 1f else 0.45f
            }
        }
    }

    private fun copyAllSubtitles() {
        val text = currentSubtitle
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "コピーできる字幕がありません", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("字幕", text))
        Toast.makeText(this, "字幕をコピーしました", Toast.LENGTH_SHORT).show()
    }

    // -- Gemini formatting ---------------------------------------------------

    private fun onFormatClicked() {
        if (formatting) return
        if (showingFormatted) {
            val orig = originalSubtitle ?: return
            showingFormatted = false
            displayText(orig)
            formatButton?.text = "整形"
            return
        }
        val orig = originalSubtitle
        if (orig.isNullOrBlank()) {
            Toast.makeText(this, "整形できる字幕がありません", Toast.LENGTH_SHORT).show()
            return
        }
        if (BuildConfig.GEMINI_API_KEY.isBlank() || BuildConfig.GEMINI_MODEL.isBlank()) {
            Toast.makeText(this, "APIキーが設定されていません", Toast.LENGTH_SHORT).show()
            return
        }
        startFormatting(orig)
    }

    private fun startFormatting(orig: String) {
        val chunks = chunkLines(stripTimestamps(orig), CHUNK_CHARS)
        if (chunks.isEmpty()) return
        val token = loadToken
        formatting = true
        formatButton?.apply {
            isEnabled = false
            alpha = 0.7f
            text = "整形中 0/${chunks.size}"
        }
        geminiExec.execute {
            val done = ArrayList<String>()
            var error: String? = null
            for (chunk in chunks) {
                if (token != loadToken) return@execute
                try {
                    done.add(geminiFormat(chunk))
                } catch (e: Exception) {
                    error = e.message ?: "不明なエラー"
                    break
                }
                val partial = (done + chunks.subList(done.size, chunks.size))
                    .joinToString("\n")
                val n = done.size
                mainHandler.post {
                    if (token != loadToken) return@post
                    displayText(partial)
                    formatButton?.text = "整形中 $n/${chunks.size}"
                }
            }
            val errMsg = error
            mainHandler.post {
                if (token != loadToken) return@post
                formatting = false
                if (done.isNotEmpty()) {
                    // Full or partial result stays on screen; the button
                    // becomes the way back to the original text.
                    val result = (done + chunks.subList(done.size, chunks.size))
                        .joinToString("\n")
                    formattedSubtitle = result
                    showingFormatted = true
                    displayText(result)
                    formatButton?.apply {
                        text = "原文に戻す"
                        isEnabled = true
                        alpha = 1f
                    }
                } else {
                    formatButton?.apply {
                        text = "整形"
                        isEnabled = true
                        alpha = 1f
                    }
                }
                if (errMsg != null) {
                    Toast.makeText(
                        this@DetailActivity, "整形エラー: $errMsg", Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /** One generateContent call for a single chunk. Throws on any failure. */
    private fun geminiFormat(chunk: String): String {
        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                "${BuildConfig.GEMINI_MODEL}:generateContent"
        )
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().put(
                    JSONObject().put("text", FORMAT_PROMPT + "\n\n" + chunk)
                ))
            }))
            put("generationConfig", JSONObject().put("temperature", 0.2))
        }
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", BuildConfig.GEMINI_API_KEY)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val respText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = try {
                    JSONObject(respText).optJSONObject("error")?.optString("message")
                } catch (e: Exception) { null }
                throw RuntimeException("HTTP $code" + if (!msg.isNullOrBlank()) ": $msg" else "")
            }
            val candidates = JSONObject(respText).optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                throw RuntimeException("応答に候補がありません")
            }
            val parts = candidates.getJSONObject(0).optJSONObject("content")
                ?.optJSONArray("parts") ?: throw RuntimeException("応答の形式が不正です")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                sb.append(parts.getJSONObject(i).optString("text"))
            }
            return sb.toString().trim().ifEmpty {
                throw RuntimeException("整形結果が空でした")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun stripTimestamps(text: String): String =
        text.lineSequence()
            .map { it.replace(TIMESTAMP_PREFIX, "") }
            .joinToString("\n")
            .trim()

    /** Splits at line boundaries into chunks of roughly [limit] characters. */
    private fun chunkLines(text: String, limit: Int): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (line in text.split("\n")) {
            if (cur.isNotEmpty() && cur.length + 1 + line.length > limit) {
                out.add(cur.toString())
                cur.setLength(0)
            }
            if (cur.isNotEmpty()) cur.append('\n')
            cur.append(line)
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    private companion object {
        const val TIMESTAMP_INTERVAL_MS = 30_000L
        const val CHUNK_CHARS = 1_000
        val TIMESTAMP_PREFIX = Regex("""^\[\d+:\d{2}\]\s*""")
        const val FORMAT_PROMPT =
            "以下は動画の字幕テキストです。句読点と改行を補って読みやすく整形してください。" +
            "要約、翻訳、内容の追加や削除はしないでください。" +
            "固有名詞と数値はそのまま残してください。出力は整形後の本文のみにしてください。"
    }
}
