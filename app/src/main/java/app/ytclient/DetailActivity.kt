package app.ytclient

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray

class DetailActivity : AppCompatActivity() {
    /** Full text of the caption track currently shown, or null when no
     * caption body is loaded. The copy-all button copies exactly this. */
    private var currentSubtitle: String? = null
    private var copyButton: TextView? = null

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
        val copyBtn = Ui.text(this, "字幕を一括コピー", 14f, Ui.TEXT, true).apply {
            background = Ui.rounded(Ui.SURFACE, 10, this@DetailActivity)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isEnabled = false
            alpha = 0.45f
            isClickable = true
            setOnClickListener { copyAllSubtitles() }
        }
        copyButton = copyBtn
        val copyWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(2), dp(16), 0)
        }
        copyWrap.addView(copyBtn)
        (copyBtn.layoutParams as LinearLayout.LayoutParams).width = ViewGroup.LayoutParams.MATCH_PARENT
        col.addView(copyWrap)
        val subtitleView = Ui.text(this, "", 13.5f, Ui.TEXT).apply {
            setPadding(dp(16), dp(12), dp(16), dp(32))
            // Long-press selection with copy handles for the caption body.
            setTextIsSelectable(true)
        }
        col.addView(subtitleView)
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
                    setOnClickListener { loadSubtitle(videoId, lang, kind, subtitleView) }
                }
                trackBox.addView(tv)
                (tv.layoutParams as LinearLayout.LayoutParams).apply {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    bottomMargin = dp(8)
                }
            }
        }
    }

    private fun loadSubtitle(videoId: String, lang: String, kind: String, out: TextView) {
        currentSubtitle = null
        setCopyEnabled(false)
        out.text = "字幕本文を取得中…"
        Bridge.call("fetch_subtitle", videoId, lang, kind) { data, err ->
            if (err != null || data == null) {
                out.text = "字幕本文エラー: $err"
                return@call
            }
            val segs = data.optJSONArray("segments") ?: JSONArray()
            val sb = StringBuilder()
            for (i in 0 until segs.length()) {
                val s = segs.getJSONObject(i)
                val start = s.optLong("start_ms")
                sb.append("[%d:%02d] %s\n".format(start / 60000, (start / 1000) % 60, s.optString("text")))
            }
            if (sb.isEmpty()) {
                out.text = "（セグメントなし）"
            } else {
                currentSubtitle = sb.toString()
                setCopyEnabled(true)
                out.text = sb.toString()
            }
        }
    }

    private fun setCopyEnabled(enabled: Boolean) {
        copyButton?.apply {
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.45f
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
}
