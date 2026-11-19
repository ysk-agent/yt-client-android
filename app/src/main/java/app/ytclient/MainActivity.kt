package app.ytclient

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray

class MainActivity : AppCompatActivity() {
    private lateinit var heroBox: FrameLayout
    private lateinit var status: TextView
    private lateinit var feedAdapter: VideoAdapter
    private var groups: List<Pair<String, List<String>>> = emptyList()
    private var currentGroup = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }
        // Search bar
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
            background = Ui.rounded(Ui.SURFACE, 24, this@MainActivity)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setSingleLine(true)
        }
        val go = Ui.text(this, "検索", 14f, Color.BLACK, true).apply {
            background = Ui.rounded(Color.WHITE, 24, this@MainActivity)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setOnClickListener { submitSearch(input.text.toString()) }
        }
        input.setOnEditorActionListener { _, _, _ -> submitSearch(input.text.toString()); true }
        searchRow.addView(input)
        searchRow.addView(go.apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
        })
        root.addView(searchRow)

        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col)
        root.addView(scroll)

        heroBox = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300))
        }
        col.addView(heroBox)
        status = Ui.text(this, "読み込み中…", 13f, Ui.MUTED).apply {
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        col.addView(status)

        col.addView(Ui.heading(this, "グループ"))
        val tileScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val tileRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), 0, dp(16), dp(4))
        }
        tileScroll.addView(tileRow)
        col.addView(tileScroll)

        col.addView(Ui.heading(this, "新着フィード"))
        feedAdapter = VideoAdapter(this, wide = false)
        val feedRv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = feedAdapter
            setPadding(dp(16), 0, 0, dp(8))
            clipToPadding = false
        }
        col.addView(feedRv)

        val ver = try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            "v${pi.versionName} (${pi.longVersionCode})"
        } catch (e: Exception) { "" }
        col.addView(Ui.text(this, "YT Client  $ver", 12f, Ui.MUTED).apply {
            setPadding(dp(16), dp(20), dp(16), dp(28))
        })
        setContentView(root)

        groups = loadGroups()
        for ((name, ids) in groups) {
            tileRow.addView(groupTile(name, ids))
        }
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

    private fun groupTile(name: String, ids: List<String>): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, dp(12), 0)
            isClickable = true
            setOnClickListener { selectGroup(name, ids) }
        }
        val tile = FrameLayout(this).apply {
            background = Ui.rounded(Ui.SURFACE, 18, this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
        }
        tile.addView(Ui.text(this, name.take(1), 26f, Ui.TEXT, true).apply {
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        })
        col.addView(tile)
        col.addView(Ui.text(this, name, 11.5f, Ui.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        })
        return col
    }

    private fun selectGroup(name: String, ids: List<String>) {
        currentGroup = name
        status.text = "「$name」の新着を取得中…"
        Bridge.call("feeds", JSONArray(ids).toString()) { data, err ->
            if (err != null || data == null) {
                status.text = "フィード取得エラー: $err"
                return@call
            }
            val items = Video.list(data.optJSONArray("items"))
            val errs = data.optJSONObject("errors")
            status.text = when {
                items.isEmpty() -> "新着がありません（エラー: $errs）"
                errs != null && errs.length() > 0 -> "${items.size} 件 · ${errs.length()} チャンネル取得失敗"
                else -> "${items.size} 件の新着"
            }
            feedAdapter.set(items)
            items.firstOrNull()?.let { buildHero(it) }
        }
    }

    private fun buildHero(v: Video) {
        heroBox.removeAllViews()
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        Img.load(iv, Img.thumbUrl(v.id, "maxresdefault"))
        heroBox.addView(iv)
        val scrim = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Ui.BG)
            )
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        heroBox.addView(scrim)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(14))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
            )
        }
        col.addView(Ui.text(this, v.title, 21f, Ui.TEXT, true).apply { maxLines = 3 })
        col.addView(Ui.text(this, v.meta(), 12.5f, Ui.MUTED).apply { setPadding(0, dp(4), 0, dp(10)) })
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val play = Ui.text(this, "▶  Play", 15f, Color.BLACK, true).apply {
            background = Ui.rounded(Color.WHITE, 24, this@MainActivity)
            setPadding(dp(26), dp(10), dp(26), dp(10))
            isClickable = true
            setOnClickListener { Ui.openDetail(this@MainActivity, v) }
        }
        val info = Ui.text(this, "＋   ｜   ⓘ", 15f, Ui.TEXT, true).apply {
            background = Ui.rounded(0x66FFFFFF, 24, this@MainActivity)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            isClickable = true
            setOnClickListener { Ui.openDetail(this@MainActivity, v) }
        }
        btnRow.addView(play)
        btnRow.addView(info.apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(10)
        })
        col.addView(btnRow)
        heroBox.addView(col)
    }
}
