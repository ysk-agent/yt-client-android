package app.ytclient

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
    /** Language code of the currently loaded caption track; selects which
     * stored formatting prompt applies. Defaults to Japanese. */
    private var currentLang: String = "ja"
    private var videoTitle: String = ""
    /** Channel name and video description, sent with every formatting
     * request as read-only context: proper nouns that sound alone cannot
     * resolve (a speaker's name in a self-introduction, …) often can be
     * resolved from these. */
    private var channelNameText: String = ""
    private var videoDescription: String = ""
    private val geminiExec = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val videoId = intent.getStringExtra("video_id") ?: ""
        val title = intent.getStringExtra("title") ?: ""
        videoTitle = title
        val channelName = intent.getStringExtra("channel_name")
        channelNameText = channelName ?: ""
        val channelId = intent.getStringExtra("channel_id")
        val meta = listOfNotNull(
            intent.getStringExtra("duration"),
            intent.getStringExtra("views"),
            intent.getStringExtra("published"),
        ).joinToString(" · ")
        val description = intent.getStringExtra("description")
        videoDescription = description ?: ""

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
        // Always available (unlike the two action buttons): the prompt can
        // be reviewed or edited before any subtitle is loaded.
        val promptBtn = Ui.text(this, "プロンプト", 12.5f, Ui.MUTED).apply {
            background = Ui.rounded(Ui.SURFACE, 10, this@DetailActivity)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            isClickable = true
            setOnClickListener { showPromptDialog() }
        }
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
            marginEnd = dp(8)
        }
        actionRow.addView(promptBtn)
        (promptBtn.layoutParams as LinearLayout.LayoutParams).apply {
            width = ViewGroup.LayoutParams.WRAP_CONTENT
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
        currentLang = lang
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
                // Prefetch design: formatting starts as soon as a caption
                // body lands, batch 1 first, so the reader rarely waits
                // for the head of the text. The button still toggles back
                // to the original once formatting has produced a result.
                if (BuildConfig.GEMINI_API_KEY.isNotBlank() &&
                    BuildConfig.GEMINI_MODEL.isNotBlank()
                ) {
                    startFormatting()
                }
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

    /**
     * Prompt editor, scoped to the current track's language: prompts are
     * stored per caption language, because what needs fixing differs by
     * source language. The pipeline only ever sends the stored prompt
     * plus the caption blocks, so editing here fully redefines what 整形
     * does for that language (cleanup, translation, …) from the next run.
     */
    private fun showPromptDialog() {
        val lang = currentLang
        val edit = EditText(this).apply {
            setText(Prefs.formatPrompt(this@DetailActivity, lang))
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            gravity = Gravity.TOP or Gravity.START
            minLines = 8
            maxLines = 14
            setSelection(text.length)
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(edit)
        }
        AlertDialog.Builder(this)
            .setTitle("整形プロンプト（$lang）")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val text = edit.text.toString()
                if (text.isBlank()) {
                    Toast.makeText(this, "プロンプトが空のため保存しませんでした", Toast.LENGTH_SHORT).show()
                } else {
                    Prefs.setFormatPrompt(this, lang, text)
                    Toast.makeText(this, "プロンプトを保存しました", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("デフォルトに戻す") { _, _ ->
                Prefs.resetFormatPrompt(this, lang)
                Toast.makeText(this, "デフォルトのプロンプトに戻しました", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

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
        startFormatting()
    }

    /** One caption block: the text between two thinned timestamps. The
     * stamp itself is owned by this pipeline — it is never sent to the
     * model, and is re-attached to the formatted body afterwards — so a
     * prompt can no longer lose or move timestamps. */
    private data class Block(val stamp: String, val body: String)

    private class GeminiException(val status: Int, message: String) :
        RuntimeException(message)

    /** Splits the thinned display text into blocks at its stamp lines. */
    private fun parseBlocks(text: String): List<Block> {
        val stampRe = Regex("""^\[(\d+):(\d{2})\]\s?""")
        // CJK caption fragments join without spaces; space-separated
        // languages need the space back between segment lines.
        val sep = if (currentLang.startsWith("ja") || currentLang.startsWith("zh") ||
            currentLang.startsWith("ko")
        ) "" else " "
        val blocks = ArrayList<Block>()
        var stamp: String? = null
        val body = StringBuilder()
        fun flush() {
            val s = stamp ?: return
            blocks.add(Block(s, body.toString()))
            body.setLength(0)
        }
        for (line in text.split("\n")) {
            val m = stampRe.find(line)
            if (m != null) {
                flush()
                stamp = "[${m.groupValues[1]}:${m.groupValues[2]}]"
                body.append(line.substring(m.range.last + 1))
            } else if (stamp != null) {
                if (body.isNotEmpty() && sep.isNotEmpty()) body.append(sep)
                body.append(line)
            }
        }
        flush()
        return blocks
    }

    /** Batch 1 is just the first two blocks (with a wide context prefill,
     * it returns fast and reads coherently); later batches group blocks
     * up to roughly [BATCH_CHARS] of body text. */
    private fun makeBatches(blocks: List<Block>): List<List<Int>> {
        val batches = ArrayList<List<Int>>()
        if (blocks.isEmpty()) return batches
        batches.add((0 until minOf(2, blocks.size)).toList())
        var cur = ArrayList<Int>()
        var len = 0
        for (i in 2 until blocks.size) {
            if (cur.isNotEmpty() && len + blocks[i].body.length > BATCH_CHARS) {
                batches.add(cur)
                cur = ArrayList()
                len = 0
            }
            cur.add(i)
            len += blocks[i].body.length
        }
        if (cur.isNotEmpty()) batches.add(cur)
        return batches
    }

    private fun assemble(blocks: List<Block>, results: Map<Int, String>): String =
        blocks.mapIndexed { i, b -> "${b.stamp} ${results[i] ?: b.body}" }
            .joinToString("\n\n")

    /** Splits a marked multi-block response back into per-block texts.
     * Returns null when the marker count does not match (the caller then
     * falls back to per-block calls). Any [m:ss] strings the model
     * hallucinated into its output are stripped: stamps live in code. */
    private fun parseMarked(out: String, n: Int): List<String>? {
        val parts = MARK_RE.split(out).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size != n) return null
        return parts.map { STAMP_RE.replace(it, "").trim() }
    }

    private fun startFormatting() {
        val orig = originalSubtitle
        if (orig.isNullOrBlank() || formatting) return
        val blocks = parseBlocks(orig)
        if (blocks.isEmpty()) return
        val batches = makeBatches(blocks)
        // Read the prompt for this track's language once per run, so an
        // edit mid-run cannot change a job already in flight.
        val lang = currentLang
        val basePrompt = Prefs.formatPrompt(this, lang)
        val title = videoTitle
        // Read-only context sent with every batch (bench-verified: the
        // channel name resolves proper nouns that sound alone cannot,
        // e.g. a speaker's name garbled in a self-introduction).
        val meta = buildString {
            append("\n\n動画タイトル：").append(title)
            if (channelNameText.isNotBlank()) {
                append("\nチャンネル名：").append(channelNameText)
            }
            if (videoDescription.isNotBlank()) {
                append("\n動画の説明：").append(videoDescription.take(DESC_CHARS))
            }
        }
        val context = blocks.joinToString("") { it.body }.take(CONTEXT_CHARS)
        val token = loadToken
        formatting = true
        formatButton?.apply {
            isEnabled = false
            alpha = 0.7f
            text = "整形中 0/${batches.size}"
        }
        geminiExec.execute {
            val results = HashMap<Int, String>()
            var failedBatches = 0
            for ((bi, idxs) in batches.withIndex()) {
                if (token != loadToken) return@execute
                val payload = idxs.mapIndexed { j, i ->
                    "[[BLOCK ${j + 1}]]\n${blocks[i].body}"
                }.joinToString("\n")
                val prompt = buildString {
                    append(basePrompt)
                    append(meta)
                    if (bi == 0) {
                        append("\n参考として字幕全体の冒頭（整形対象ではありません）：\n")
                        append(context)
                    }
                    append("\n").append(MARKER_RULE)
                }
                var parsed: List<String>? = null
                var callFailed = false
                try {
                    val out = geminiCallWithRetry(token, prompt, payload)
                    parsed = parseMarked(out, idxs.size)
                } catch (e: InterruptedException) {
                    return@execute
                } catch (e: Exception) {
                    callFailed = true
                }
                if (parsed != null) {
                    for ((i, p) in idxs.zip(parsed)) results[i] = p
                } else if (!callFailed) {
                    // The call succeeded but its markers did not parse:
                    // redo this batch block by block (no markers).
                    for (i in idxs) {
                        if (token != loadToken) return@execute
                        try {
                            val single = geminiCallWithRetry(
                                token,
                                basePrompt + meta,
                                blocks[i].body,
                            )
                            results[i] = STAMP_RE.replace(single, "").trim()
                        } catch (e: InterruptedException) {
                            return@execute
                        } catch (e: Exception) {
                            // This block stays in its original wording.
                        }
                    }
                } else {
                    // A failed batch is NOT retried block by block: on a
                    // rate limit that cascade only multiplies calls. Its
                    // blocks stay in their original wording.
                    failedBatches++
                }
                val snapshot = HashMap(results)
                val n = bi + 1
                mainHandler.post {
                    if (token != loadToken) return@post
                    displayText(assemble(blocks, snapshot))
                    formatButton?.text = "整形中 $n/${batches.size}"
                }
            }
            mainHandler.post {
                if (token != loadToken) return@post
                formatting = false
                if (results.isNotEmpty()) {
                    // Full or partial result stays on screen; the button
                    // becomes the way back to the original text.
                    val result = assemble(blocks, results)
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
                if (failedBatches > 0) {
                    Toast.makeText(
                        this@DetailActivity,
                        "一部の整形に失敗しました（該当箇所は原文のままです）",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    /**
     * One generateContent call with the retry policy the bench runs
     * established: a 429 is a rate limit, not a failure — wait with
     * backoff (20 s, then 40 s) and retry the same call; a 5xx gets one
     * retry after a short wait; anything else throws at once.
     */
    private fun geminiCallWithRetry(token: Int, prompt: String, payload: String): String {
        var attempt = 0
        while (true) {
            try {
                return geminiFormat(prompt, payload)
            } catch (e: GeminiException) {
                attempt++
                val waitMs = when {
                    e.status == 429 && attempt <= 2 ->
                        if (attempt == 1) 20_000L else 40_000L
                    e.status >= 500 && attempt <= 1 -> 2_000L
                    else -> throw e
                }
                // Sleep in slices so a track switch aborts promptly
                // instead of blocking the single formatting thread.
                var left = waitMs
                while (left > 0) {
                    if (token != loadToken) throw InterruptedException("stale")
                    Thread.sleep(minOf(500L, left))
                    left -= 500L
                }
            }
        }
    }

    /** One generateContent call for a single chunk. Throws on any failure. */
    private fun geminiFormat(prompt: String, chunk: String): String {
        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                "${BuildConfig.GEMINI_MODEL}:generateContent"
        )
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().put(
                    JSONObject().put("text", prompt + "\n\n" + chunk)
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
                throw GeminiException(
                    code, "HTTP $code" + if (!msg.isNullOrBlank()) ": $msg" else ""
                )
            }
            val candidates = JSONObject(respText).optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                throw GeminiException(0, "応答に候補がありません")
            }
            val parts = candidates.getJSONObject(0).optJSONObject("content")
                ?.optJSONArray("parts") ?: throw GeminiException(0, "応答の形式が不正です")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                sb.append(parts.getJSONObject(i).optString("text"))
            }
            return sb.toString().trim().ifEmpty {
                throw GeminiException(0, "整形結果が空でした")
            }
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val TIMESTAMP_INTERVAL_MS = 30_000L
        /** Body characters per batch after the first one. */
        const val BATCH_CHARS = 1_000
        /** Characters of caption head sent as read-only context with batch 1. */
        const val CONTEXT_CHARS = 1_800
        /** Video description is context, not content: its head is enough. */
        const val DESC_CHARS = 500
        val MARK_RE = Regex("""\[\[BLOCK \d+\]\]""")
        val STAMP_RE = Regex("""\[\d+:\d{1,2}(:\d{2})?\]""")
        /** Appended to every batch prompt by code (never user-edited):
         * the block-marker contract the parser relies on. */
        const val MARKER_RULE =
            "入力は [[BLOCK n]] の見出しで区切られた複数のブロックです。" +
                "各ブロックを個別に整形し、見出しをそのまま付けて、同じ順序と個数で出力してください。"
    }
}
