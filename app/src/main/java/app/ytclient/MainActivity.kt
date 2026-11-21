package app.ytclient

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var updatedText: TextView
    private lateinit var feedAdapter: VideoAdapter
    private lateinit var chipRow: LinearLayout
    private lateinit var debugRow: LinearLayout
    private lateinit var feedRv: RecyclerView
    private lateinit var feedLm: LinearLayoutManager
    private var groups: List<Pair<String, List<String>>> = emptyList()
    private var currentGroup = ""
    private var currentIds: List<String> = emptyList()
    /** Group whose items the adapter currently shows ("" = none yet). A
     * re-select of the same group skips the cache render so the live
     * scroll position is the only anchor that matters. */
    private var shownGroup = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            val sb = resources.getIdentifier("status_bar_height", "dimen", "android")
            setPadding(0, if (sb > 0) resources.getDimensionPixelSize(sb) else 0, 0, 0)
        }

        // Search bar: fully rounded pill; submitting opens SearchActivity.
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(14), dp(16), dp(6))
            gravity = Gravity.CENTER_VERTICAL
        }
        val input = EditText(this).apply {
            hint = "検索"
            setHintTextColor(Ui.MUTED)
            setTextColor(Ui.TEXT)
            textSize = 15f
            background = Ui.rounded(Ui.SURFACE, 28, this@MainActivity)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setSingleLine(true)
        }
        val go = Ui.text(this, "検索", 14f, Color.BLACK, true).apply {
            background = Ui.rounded(Color.WHITE, 28, this@MainActivity)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setOnClickListener { submitSearch(input.text.toString()) }
        }
        input.setOnEditorActionListener { _, _, _ -> submitSearch(input.text.toString()); true }
        searchRow.addView(input)
        searchRow.addView(go, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(8) })
        root.addView(searchRow)

        // Group chips.
        val chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(6), 0, dp(2))
        }
        chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        chipScroll.addView(chipRow)
        root.addView(chipScroll)

        // Last-updated row with channel-list route and refresh.
        val updRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        updatedText = Ui.text(this, "フィードの最終更新： —", 12f, Ui.MUTED).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        updRow.addView(updatedText)
        val chList = Ui.text(this, "チャンネル一覧", 12.5f, Ui.TEXT, true).apply {
            background = Ui.rounded(Ui.SURFACE, 16, this@MainActivity)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            isClickable = true
            setOnClickListener {
                if (currentIds.isNotEmpty()) {
                    startActivity(Intent(this@MainActivity, ChannelListActivity::class.java).apply {
                        putExtra("group_name", currentGroup)
                        putStringArrayListExtra("channel_ids", ArrayList(currentIds))
                    })
                }
            }
        }
        updRow.addView(chList)
        val refresh = Ui.text(this, "↻", 16f, 0xFFFFB37A.toInt(), true).apply {
            background = Ui.rounded(Ui.SURFACE, 16, this@MainActivity)
            setPadding(dp(11), dp(4), dp(11), dp(6))
            isClickable = true
            setOnClickListener { if (currentIds.isNotEmpty()) selectGroup(currentGroup, currentIds) }
        }
        updRow.addView(refresh, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(8) })
        root.addView(updRow)

        // Debug row: thumbnail size (persisted; re-renders the feed rows).
        debugRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        root.addView(debugRow)

        status = Ui.text(this, "読み込み中…", 12.5f, Ui.MUTED).apply {
            setPadding(dp(16), dp(6), dp(16), 0)
        }
        root.addView(status)

        feedAdapter = VideoAdapter(this)
        feedLm = LinearLayoutManager(this)
        feedRv = RecyclerView(this).apply {
            layoutManager = feedLm
            adapter = feedAdapter
            setPadding(dp(12), 0, dp(12), dp(8))
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        // Persist the scroll anchor whenever scrolling settles, so a
        // restart (or process death) reopens the feed where it was left.
        feedRv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) saveAnchor()
            }
        })
        root.addView(feedRv)

        val ver = try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            "v${pi.versionName} (${pi.longVersionCode})"
        } catch (e: Exception) { "" }
        root.addView(Ui.text(this, "YT Client  $ver", 12f, Ui.MUTED).apply {
            setPadding(dp(16), dp(8), dp(16), dp(14))
        })
        setContentView(root)

        groups = loadGroups()
        buildChips()
        buildDebugRow()
        val last = Prefs.lastGroup(this)
        val def = groups.firstOrNull { it.first == last }
            ?: groups.firstOrNull { it.first == "経済" } ?: groups.firstOrNull()
        if (def != null) selectGroup(def.first, def.second) else status.text = "グループ資産がありません"
    }

    override fun onPause() {
        saveAnchor()
        super.onPause()
    }

    /** The current scroll anchor: first (partially) visible row's
     * videoId and its top offset within the list. Null when the list is
     * empty or not laid out yet. */
    private fun captureAnchor(): Pair<String, Int>? {
        val pos = feedLm.findFirstVisibleItemPosition()
        if (pos == RecyclerView.NO_POSITION) return null
        val item = feedAdapter.items.getOrNull(pos) ?: return null
        val view = feedLm.findViewByPosition(pos) ?: return null
        return item.id to view.top
    }

    private fun saveAnchor() {
        if (currentGroup.isEmpty() || shownGroup != currentGroup) return
        val (id, off) = captureAnchor() ?: return
        Prefs.setFeedAnchor(this, currentGroup, id, off)
    }

    /** Scroll so that [anchor]'s row sits where it was, in whatever item
     * list the adapter currently holds. If the row is gone (aged out of
     * the feed), the position is left alone. */
    private fun applyAnchor(anchor: Pair<String, Int>) {
        val idx = feedAdapter.items.indexOfFirst { it.id == anchor.first }
        if (idx >= 0) feedLm.scrollToPositionWithOffset(idx, anchor.second)
    }

    private fun submitSearch(q: String) {
        val query = q.trim()
        if (query.isEmpty()) return
        startActivity(Intent(this, SearchActivity::class.java).putExtra("query", query))
    }

    private fun loadGroups(): List<Pair<String, List<String>>> {
        val out = ArrayList<Pair<String, List<String>>>()
        try {
            val raw = assets.open("groups.json").bufferedReader().readText()
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ids = ArrayList<String>()
                val ja = o.getJSONArray("channel_ids")
                for (j in 0 until ja.length()) ids.add(ja.getString(j))
                out.add(o.getString("name") to ids)
            }
        } catch (e: Exception) {
            android.util.Log.e("Main", "groups.json", e)
        }
        return out
    }

    private fun buildChips() {
        chipRow.removeAllViews()
        for ((name, ids) in groups) {
            val chip = Ui.text(this, name, 13f, if (name == currentGroup) Color.BLACK else Ui.TEXT, name == currentGroup).apply {
                background = Ui.rounded(if (name == currentGroup) Color.WHITE else Ui.SURFACE, 18, this@MainActivity)
                setPadding(dp(14), dp(7), dp(14), dp(7))
                isClickable = true
                setOnClickListener { selectGroup(name, ids) }
            }
            chipRow.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) })
        }
    }

    private fun buildDebugRow() {
        debugRow.removeAllViews()
        debugRow.addView(Ui.text(this, "デバッグ： サムネイル", 12f, Ui.MUTED))
        val cur = Prefs.thumbPct(this)
        for ((label, pct) in listOf("小" to 24, "中" to 33, "大" to 42)) {
            val sel = pct == cur
            val b = Ui.text(this, label, 12f, if (sel) Color.BLACK else Ui.TEXT, sel).apply {
                background = Ui.rounded(if (sel) 0xFFFFB37A.toInt() else Ui.SURFACE, 14, this@MainActivity)
                setPadding(dp(12), dp(5), dp(12), dp(5))
                isClickable = true
                setOnClickListener {
                    Prefs.setThumbPct(this@MainActivity, pct)
                    buildDebugRow()
                    feedAdapter.notifyDataSetChanged()
                }
            }
            debugRow.addView(b, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) })
        }
    }

    private fun updateUpdatedLabel() {
        val at = Prefs.feedUpdatedAt(this, currentGroup)
        updatedText.text = "フィードの最終更新： ${if (at > 0) relTimeLabel(at) else "—"}"
    }

    private fun selectGroup(name: String, ids: List<String>) {
        currentGroup = name
        currentIds = ids
        Prefs.setLastGroup(this, name)
        buildChips()
        updateUpdatedLabel()
        // Switching to a group that is not on screen: render its
        // persisted items immediately (with its saved scroll anchor) and
        // refresh behind them. Re-selecting the visible group keeps the
        // live list untouched and only refreshes.
        if (shownGroup != name) {
            val cached = FeedCache.load(this, name)
            if (cached != null) {
                feedAdapter.set(cached)
                shownGroup = name
                Prefs.feedAnchor(this, name)?.let { applyAnchor(it) }
                status.text = "「$name」の保存済みフィードを表示中・更新しています…"
            } else {
                status.text = "「$name」の新着を取得中…"
            }
        } else {
            status.text = "「$name」の新着を取得中…"
        }
        Bridge.call("feeds", JSONArray(ids).toString()) { data, err ->
            // A late reply for a group the user has since left must not
            // clobber the screen (its cache write below is still useful).
            if (err != null || data == null) {
                if (currentGroup == name) status.text = "フィード取得エラー: $err"
                return@call
            }
            // Defence in depth: the bridge already excludes Shorts from
            // feeds, but drop any flagged item here too.
            val items = Video.list(data.optJSONArray("items")).filter { !it.isShorts }
            FeedCache.save(this, name, data.optJSONArray("items"))
            if (currentGroup != name) return@call
            val errs = data.optJSONObject("errors")
            Prefs.setFeedUpdatedAt(this, name, System.currentTimeMillis())
            updateUpdatedLabel()
            status.text = when {
                items.isEmpty() -> "新着がありません（エラー: $errs）"
                errs != null && errs.length() > 0 -> "${items.size} 件 · ${errs.length()} チャンネル取得失敗"
                else -> "${items.size} 件の新着"
            }
            // Keep the user's place across the replacement: anchor on
            // the row at the top of the screen right now and re-find it
            // in the fresh list, so newly arrived items above it do not
            // shift what is being read.
            val anchor = captureAnchor()
            feedAdapter.set(items)
            shownGroup = name
            if (anchor != null) applyAnchor(anchor)
        }
    }
}
