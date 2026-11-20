package app.ytclient

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ChannelActivity : AppCompatActivity() {
    private lateinit var adapter: VideoAdapter
    private lateinit var status: TextView
    private var continuation: String? = null
    private var loading = false
    private var channelId: String = ""
    private var channelName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        channelId = intent.getStringExtra("channel_id") ?: ""
        channelName = intent.getStringExtra("channel_name") ?: ""
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            val sb = resources.getIdentifier("status_bar_height", "dimen", "android")
            setPadding(0, if (sb > 0) resources.getDimensionPixelSize(sb) else 0, 0, 0)
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col)

        val nameView = Ui.text(this, channelName.ifBlank { "チャンネル" }, 22f, Ui.TEXT, true).apply {
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
        adapter = VideoAdapter(this)
        val videosRv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ChannelActivity)
            adapter = this@ChannelActivity.adapter
            setPadding(dp(12), 0, dp(12), 0)
            clipToPadding = false
            isNestedScrollingEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        col.addView(videosRv)
        status = Ui.text(this, "", 12.5f, Ui.MUTED).apply {
            setPadding(dp(16), dp(10), dp(16), dp(28))
        }
        col.addView(status)
        setContentView(scroll)

        // Page 2+ loads when the scroll nears the bottom of the list.
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> maybeLoadMore(scroll, col) }

        Bridge.call("channel_info", channelId) { data, err ->
            if (err != null || data == null) {
                infoView.text = "情報エラー: $err"
                return@call
            }
            nameView.text = data.optString("name").ifBlank { nameView.text.toString() }
            if (channelName.isBlank()) channelName = data.optString("name")
            infoView.text = listOfNotNull(
                if (data.isNull("handle")) null else data.optString("handle"),
                if (data.isNull("subscriber_count_text")) null else data.optString("subscriber_count_text"),
                if (data.isNull("video_count_text")) null else data.optString("video_count_text"),
            ).joinToString(" · ")
            descView.text = if (data.isNull("description")) "" else data.optString("description")
        }
        loading = true
        Bridge.call("channel_videos", channelId, "latest") { data, err ->
            loading = false
            if (err != null || data == null) {
                status.text = "動画エラー: $err"
                return@call
            }
            // No Shorts section: shorts items are dropped here too, in
            // case a browse page mixes them into the videos tab.
            val items = Video.list(data.optJSONArray("items")).filter { it.kind == "video" }
            continuation = if (data.isNull("continuation")) null else data.optString("continuation")
            adapter.set(items)
            status.text = "全 ${adapter.itemCount} 件"
            // A short first page may not fill the screen; pull the next
            // page right away in that case (no scroll event would fire).
            scroll.post { maybeLoadMore(scroll, col) }
        }
    }

    /** Loads the next page when the visible bottom is within ~400dp of
     * the content end. Pages 2+ always come from the channel client's
     * browse continuation (channel_videos_next), never from the feed. */
    private fun maybeLoadMore(scroll: ScrollView, col: LinearLayout) {
        if (loading || continuation == null) return
        val remaining = col.height - (scroll.scrollY + scroll.height)
        if (remaining < dp(400)) loadNext(scroll, col)
    }

    private fun loadNext(scroll: ScrollView, col: LinearLayout) {
        val cont = continuation ?: return
        loading = true
        status.text = "続きを読み込み中…"
        Bridge.call("channel_videos_next", cont, channelId, channelName) { data, err ->
            loading = false
            if (err != null || data == null) {
                status.text = "ページングエラー: $err"
                return@call
            }
            val items = Video.list(data.optJSONArray("items")).filter { it.kind == "video" }
            continuation = if (data.isNull("continuation")) null else data.optString("continuation")
            adapter.add(items)
            status.text = "全 ${adapter.itemCount} 件"
            scroll.post { maybeLoadMore(scroll, col) }
        }
    }
}
