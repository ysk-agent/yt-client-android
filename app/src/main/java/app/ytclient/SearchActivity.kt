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
        // Pill search bar, prefilled; submitting restarts the search.
        val searchRow = LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(14), dp(16), dp(6))
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val input = android.widget.EditText(this).apply {
            setText(query)
            setHintTextColor(Ui.MUTED)
            setTextColor(Ui.TEXT)
            textSize = 15f
            background = Ui.rounded(Ui.SURFACE, 28, this@SearchActivity)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setSingleLine(true)
        }
        val go = Ui.text(this, "検索", 14f, android.graphics.Color.BLACK, true).apply {
            background = Ui.rounded(android.graphics.Color.WHITE, 28, this@SearchActivity)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            isClickable = true
            setOnClickListener { restartSearch(input.text.toString()) }
        }
        input.setOnEditorActionListener { _, _, _ -> restartSearch(input.text.toString()); true }
        searchRow.addView(input)
        searchRow.addView(go, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(8) })
        root.addView(searchRow)
        root.addView(Ui.heading(this, "「$query」の検索結果"))
        val status = Ui.text(this, "検索中…", 13f, Ui.MUTED).apply {
            setPadding(dp(16), 0, dp(16), dp(6))
        }
        root.addView(status)
        adapter = VideoAdapter(this)
        val lm = LinearLayoutManager(this)
        val rv = RecyclerView(this).apply {
            layoutManager = lm
            adapter = this@SearchActivity.adapter
            setPadding(dp(12), 0, dp(12), 0)
            clipToPadding = false
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
            // Shorts and every non-video kind are excluded (the bridge
            // already filters; this is defence in depth).
            val items = Video.list(data.optJSONArray("items"))
                .filter { it.id.isNotBlank() && it.kind == "video" }
            continuation = if (data.isNull("continuation")) null else data.optString("continuation")
            status.text = "${items.size} 件"
            adapter.set(items)
        }
    }

    private fun restartSearch(q: String) {
        val query = q.trim()
        if (query.isEmpty()) return
        startActivity(android.content.Intent(this, SearchActivity::class.java).putExtra("query", query))
        finish()
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
                .filter { it.kind == "video" }
            continuation = if (data.isNull("continuation")) null else data.optString("continuation")
            adapter.add(items)
            status.text = "全 ${adapter.itemCount} 件"
        }
    }
}
