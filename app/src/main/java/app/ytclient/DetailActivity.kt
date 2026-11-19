package app.ytclient

import android.os.Bundle
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray

class DetailActivity : AppCompatActivity() {
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
        val subtitleView = Ui.text(this, "", 13.5f, Ui.TEXT).apply {
            setPadding(dp(16), dp(12), dp(16), dp(32))
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
            out.text = if (sb.isEmpty()) "（セグメントなし）" else sb.toString()
        }
    }
}
