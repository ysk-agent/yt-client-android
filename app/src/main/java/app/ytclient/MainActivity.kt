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
    private var groups: List<Pair<String, List<String>>> = emptyList()
    private var currentGroup = ""
    private var currentIds: List<String> = emptyList()

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
        val feedRv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = feedAdapter
            setPadding(dp(12), 0, dp(12), dp(8))
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
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
        val def = groups.firstOrNull { it.first == "経済" } ?: groups.firstOrNull()
        if (def != null) selectGroup(def.first, def.second) else status.text = "グループ資産がありません"
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
        buildChips()
        updateUpdatedLabel()
        status.text = "「$name」の新着を取得中…"
        Bridge.call("feeds", JSONArray(ids).toString()) { data, err ->
            if (err != null || data == null) {
                status.text = "フィード取得エラー: $err"
                return@call
            }
            // Defence in depth: the bridge already excludes Shorts from
            // feeds, but drop any flagged item here too.
            val items = Video.list(data.optJSONArray("items")).filter { !it.isShorts }
            val errs = data.optJSONObject("errors")
            Prefs.setFeedUpdatedAt(this, name, System.currentTimeMillis())
            updateUpdatedLabel()
            status.text = when {
                items.isEmpty() -> "新着がありません（エラー: $errs）"
                errs != null && errs.length() > 0 -> "${items.size} 件 · ${errs.length()} チャンネル取得失敗"
                else -> "${items.size} 件の新着"
            }
            feedAdapter.set(items)
        }
    }
}
