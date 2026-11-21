package app.ytclient

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray

class ChannelActivity : AppCompatActivity() {
    private lateinit var adapter: VideoAdapter
    private lateinit var status: TextView
    private lateinit var scroll: ScrollView
    private lateinit var col: LinearLayout
    private var continuation: String? = null
    private var loading = false
    /** True while the list shows the Atom feed's first page. The browse
     * (channel) fetch happens lazily, only when the user scrolls toward
     * the end of that page and page 2+ is actually wanted. */
    private var feedPhase = true
    private var channelId: String = ""
    private var channelName: String = ""

    /** Stage label of the in-flight paging call ("browse初回" /
     * "継続ページ"), used by the watchdog below. */
    private var pagingStage: String? = null
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val watchdog = Runnable {
        val stage = pagingStage ?: return@Runnable
        // The call is still out after 30 s: say where it is stuck
        // instead of leaving an unexplained spinner. The call itself
        // is bounded by the bridge's total timeout and will still
        // resolve or error out on its own.
        Log.w(TAG, "paging watchdog: stuck at stage=$stage channel=$channelId")
        status.text = "読み込みが長引いています（段階: $stage）…"
    }

    private fun pagingStart(stage: String) {
        pagingStage = stage
        Log.d(TAG, "paging start: stage=$stage channel=$channelId")
        watchdogHandler.removeCallbacks(watchdog)
        watchdogHandler.postDelayed(watchdog, 30_000)
    }

    private fun pagingEnd(stage: String, result: String) {
        Log.d(TAG, "paging end: stage=$stage result=$result channel=$channelId")
        pagingStage = null
        watchdogHandler.removeCallbacks(watchdog)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        channelId = intent.getStringExtra("channel_id") ?: ""
        channelName = intent.getStringExtra("channel_name") ?: ""
        scroll = ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            val sb = resources.getIdentifier("status_bar_height", "dimen", "android")
            setPadding(0, if (sb > 0) resources.getDimensionPixelSize(sb) else 0, 0, 0)
        }
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
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

        scroll.setOnScrollChangeListener { _, _, _, _, _ -> maybeLoadMore() }

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

        // Page 1 is the Atom feed: one light GET, the same source as the
        // home feed. The channel (browse) fetch is deferred until page 2+
        // is wanted (see bootstrapBrowse).
        loading = true
        Bridge.call("feeds", JSONArray(listOf(channelId)).toString()) { data, err ->
            loading = false
            if (err != null || data == null) {
                // Feed failed: fall back to the browse first page so the
                // screen still works.
                bootstrapBrowse()
                return@call
            }
            val items = Video.list(data.optJSONArray("items")).filter { !it.isShorts }
            adapter.set(items)
            status.text = "全 ${adapter.itemCount} 件"
            if (items.isEmpty()) {
                bootstrapBrowse()
            } else {
                scroll.post { maybeLoadMore() }
            }
        }
    }

    /** Fires when the visible bottom comes within ~400dp of the content
     * end. In the feed phase that means page 2+ is wanted, so the browse
     * cursor is fetched first; afterwards pages continue via
     * channel_videos_next. The feed is never used beyond page 1. */
    private fun maybeLoadMore() {
        if (loading) return
        val remaining = col.height - (scroll.scrollY + scroll.height)
        if (remaining >= dp(400)) return
        if (feedPhase) bootstrapBrowse()
        else if (continuation != null) loadNext()
    }

    /** Fetches browse page 1 to obtain the continuation cursor, and
     * replaces the feed list with it (browse page 1 is a superset of the
     * feed page and carries durations). */
    private fun bootstrapBrowse() {
        if (loading) return
        loading = true
        pagingStart("browse初回")
        status.text = "続きを読み込み中…"
        Bridge.call("channel_videos", channelId, "latest") { data, err ->
            loading = false
            if (err != null || data == null) {
                pagingEnd("browse初回", "error: $err")
                status.text = "ページングエラー: $err"
                return@call
            }
            feedPhase = false
            val items = Video.list(data.optJSONArray("items")).filter { it.kind == "video" }
            continuation = if (data.isNull("continuation")) null else data.optString("continuation")
            pagingEnd("browse初回", "items=${items.size} continuation=${continuation != null}")
            adapter.set(items)
            status.text = "全 ${adapter.itemCount} 件"
            scroll.post { maybeLoadMore() }
        }
    }

    private fun loadNext() {
        val cont = continuation ?: return
        loading = true
        pagingStart("継続ページ")
        status.text = "続きを読み込み中…"
        Bridge.call("channel_videos_next", cont, channelId, channelName) { data, err ->
            loading = false
            if (err != null || data == null) {
                pagingEnd("継続ページ", "error: $err")
                status.text = "ページングエラー: $err"
                return@call
            }
            val items = Video.list(data.optJSONArray("items")).filter { it.kind == "video" }
            continuation = if (data.isNull("continuation")) null else data.optString("continuation")
            pagingEnd("継続ページ", "items=${items.size} continuation=${continuation != null}")
            if (items.isEmpty()) {
                // An empty page means there is nothing more to page
                // through, whatever the token says: stop here instead
                // of re-firing loadNext at the same scroll position.
                continuation = null
                status.text = "全 ${adapter.itemCount} 件（これ以上ありません）"
                return@call
            }
            adapter.add(items)
            status.text = "全 ${adapter.itemCount} 件"
            scroll.post { maybeLoadMore() }
        }
    }

    private companion object {
        const val TAG = "YtChannel"
    }
}
