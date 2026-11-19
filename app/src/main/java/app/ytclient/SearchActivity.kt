package app.ytclient

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class SearchActivity : AppCompatActivity() {
    private lateinit var adapter: VideoAdapter
    private var continuation: String? = null
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val query = intent.getStringExtra("query") ?: ""
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            val sb = resources.getIdentifier("status_bar_height", "dimen", "android")
            setPadding(0, if (sb > 0) resources.getDimensionPixelSize(sb) else 0, 0, 0)
        }
        root.addView(Ui.heading(this, "「$query」の検索結果"))
        val status = Ui.text(this, "検索中…", 13f, Ui.MUTED).apply {
            setPadding(dp(16), 0, dp(16), dp(6))
        }
        root.addView(status)
        adapter = VideoAdapter(this, wide = true)
        val lm = LinearLayoutManager(this)
        val rv = RecyclerView(this).apply {
            layoutManager = lm
            adapter = this@SearchActivity.adapter
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv2: RecyclerView, dx: Int, dy: Int) {
                val cont = continuation ?: return
                if (loading || dy <= 0) return
                if (lm.findLastVisibleItemPosition() >= adapter.itemCount - 4) {
                    loadNext(cont, status)
                }
            }
        })
        root.addView(rv)
        setContentView(root)

        loading = true
        Bridge.call("search", query) { data, err ->
            loading = false
            if (err != null || data == null) {
                status.text = "検索エラー: $err"
                return@call
            }
            val items = Video.list(data.optJSONArray("items"))
                .filter { it.id.isNotBlank() && (it.kind == "video" || it.kind == "shorts") }
            continuation = if (data.isNull("continuation")) null else data.optString("continuation")
            status.text = "${items.size} 件"
            adapter.set(items)
        }
    }

    private fun loadNext(cont: String, status: android.widget.TextView) {
        loading = true
        status.text = "続きを読み込み中…"
        Bridge.call("search_next", cont) { data, err ->
            loading = false
            if (err != null || data == null) {
                status.text = "ページングエラー: $err"
                return@call
            }
            val items = Video.list(data.optJSONArray("items"))
                .filter { it.kind == "video" || it.kind == "shorts" }
            continuation = if (data.isNull("continuation")) null else data.optString("continuation")
            adapter.add(items)
            status.text = "全 ${adapter.itemCount} 件"
        }
    }
}
