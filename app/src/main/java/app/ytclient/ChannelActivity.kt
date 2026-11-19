package app.ytclient

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ChannelActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val channelId = intent.getStringExtra("channel_id") ?: ""
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.BG) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col)

        val nameView = Ui.text(this, intent.getStringExtra("channel_name") ?: "チャンネル", 22f, Ui.TEXT, true).apply {
            setPadding(dp(16), dp(18), dp(16), 0)
        }
        col.addView(nameView)
        val infoView = Ui.text(this, "チャンネル情報を取得中…", 13f, Ui.MUTED).apply {
            setPadding(dp(16), dp(6), dp(16), 0)
        }
        col.addView(infoView)
        val descView = Ui.text(this, "", 12.5f, Ui.MUTED).apply {
            setPadding(dp(16), dp(8), dp(16), 0)
            maxLines = 5
        }
        col.addView(descView)

        col.addView(Ui.heading(this, "動画"))
        val videosAdapter = VideoAdapter(this, wide = false)
        col.addView(horizontalRv(videosAdapter))
        col.addView(Ui.heading(this, "Shorts"))
        val shortsAdapter = VideoAdapter(this, wide = false)
        col.addView(horizontalRv(shortsAdapter))
        val status = Ui.text(this, "", 12.5f, Ui.MUTED).apply {
            setPadding(dp(16), dp(10), dp(16), dp(28))
        }
        col.addView(status)
        setContentView(scroll)

        Bridge.call("channel_info", channelId) { data, err ->
            if (err != null || data == null) {
                infoView.text = "情報エラー: $err"
                return@call
            }
            nameView.text = data.optString("name").ifBlank { nameView.text.toString() }
            infoView.text = listOfNotNull(
                if (data.isNull("handle")) null else data.optString("handle"),
                if (data.isNull("subscriber_count_text")) null else data.optString("subscriber_count_text"),
                if (data.isNull("video_count_text")) null else data.optString("video_count_text"),
            ).joinToString(" · ")
            descView.text = if (data.isNull("description")) "" else data.optString("description")
        }
        Bridge.call("channel_videos", channelId, "latest") { data, err ->
            if (err != null || data == null) {
                status.text = "動画エラー: $err"
                return@call
            }
            videosAdapter.set(Video.list(data.optJSONArray("items")))
        }
        Bridge.call("channel_shorts", channelId) { data, err ->
            if (err == null && data != null) {
                shortsAdapter.set(Video.list(data.optJSONArray("items")))
            }
        }
    }

    private fun horizontalRv(adapter: VideoAdapter): RecyclerView = RecyclerView(this).apply {
        layoutManager = LinearLayoutManager(this@ChannelActivity, LinearLayoutManager.HORIZONTAL, false)
        this.adapter = adapter
        setPadding(dp(16), 0, 0, dp(8))
        clipToPadding = false
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
}
