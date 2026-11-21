package app.ytclient

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
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
import org.json.JSONObject

/**
 * Search screen with conditions, in three layers (spec agreed with
 * Yosuke 2026-11-21):
 *
 * 1. Operator assist — form fields that compose the operators YouTube
 *    actually honours (measured 2026-11-21: intitle:, before:/after:,
 *    and quoted phrases work; the "-" exclusion operator is ignored
 *    by the backend, so exclusion lives in layer 3 instead).
 * 2. YouTube's own search filters — sort, upload date, duration and
 *    features, sent as the params token built by the bridge, plus the
 *    region (gl) / language (hl) pair.
 * 3. On-device filters — applied to fetched items in this activity:
 *    exclude keywords, exclude channels, duration range, minimum
 *    view count. Pages that filter down to almost nothing trigger an
 *    automatic refill from the next page (bounded rounds).
 *
 * Conditions persist across searches (Prefs.searchConditions).
 */
class SearchActivity : AppCompatActivity() {

    /** All search conditions. sort: 0 relevance, 1 rating,
     * 2 upload date, 3 view count. upload: 0 all, 1 hour, 2 today,
     * 3 week, 4 month, 5 year. duration uses YouTube's own filter
     * values: 0 all, 4 = under 3 min, 5 = 3-20 min, 2 = 20 min+.
     * features holds Innertube filter field numbers (4 HD, 5
     * subtitles, 8 live, 14 4K). */
    private data class Cond(
        var sort: Int = 0,
        var upload: Int = 0,
        var duration: Int = 0,
        var features: MutableSet<Int> = mutableSetOf(),
        var gl: String = "JP",
        var hl: String = "ja",
        var opIntitle: String = "",
        var opPhrase: String = "",
        var opAfter: String = "",
        var opBefore: String = "",
        var odExcludeKw: String = "",
        var odExcludeCh: String = "",
        var odMinDur: String = "",
        var odMaxDur: String = "",
        var odMinViewsMan: String = "",
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("sort", sort).put("upload", upload).put("duration", duration)
            .put("features", JSONArray(features.toList()))
            .put("gl", gl).put("hl", hl)
            .put("opIntitle", opIntitle).put("opPhrase", opPhrase)
            .put("opAfter", opAfter).put("opBefore", opBefore)
            .put("odExcludeKw", odExcludeKw).put("odExcludeCh", odExcludeCh)
            .put("odMinDur", odMinDur).put("odMaxDur", odMaxDur)
            .put("odMinViewsMan", odMinViewsMan)

        /** True when any on-device filter is set (they cost refills). */
        fun hasOnDevice(): Boolean =
            odExcludeKw.isNotBlank() || odExcludeCh.isNotBlank() ||
                odMinDur.isNotBlank() || odMaxDur.isNotBlank() || odMinViewsMan.isNotBlank()

        /** Short labels of the non-default conditions, for the
         * summary line above the results. */
        fun summaryLabels(): List<String> {
            val out = mutableListOf<String>()
            when (sort) {
                1 -> out += "評価順"
                2 -> out += "新しい順"
                3 -> out += "再生回数順"
            }
            when (upload) {
                1 -> out += "1時間以内"
                2 -> out += "今日"
                3 -> out += "今週"
                4 -> out += "今月"
                5 -> out += "今年"
            }
            when (duration) {
                4 -> out += "3分未満"
                5 -> out += "3〜20分"
                2 -> out += "20分以上"
            }
            if (8 in features) out += "ライブ"
            if (4 in features) out += "HD"
            if (14 in features) out += "4K"
            if (5 in features) out += "字幕"
            if (gl != "JP") out += "地域:${REGIONS.firstOrNull { it.second == gl }?.first ?: gl}"
            if (hl != "ja") out += "言語:${LANGUAGES.firstOrNull { it.second == hl }?.first ?: hl}"
            if (opIntitle.isNotBlank()) out += "タイトル:${opIntitle}"
            if (opPhrase.isNotBlank()) out += "フレーズ:${opPhrase}"
            if (opAfter.isNotBlank()) out += "${opAfter}年以降"
            if (opBefore.isNotBlank()) out += "${opBefore}年以前"
            if (odExcludeKw.isNotBlank()) out += "除外:${odExcludeKw}"
            if (odExcludeCh.isNotBlank()) out += "除外CH:${odExcludeCh}"
            if (odMinDur.isNotBlank() || odMaxDur.isNotBlank()) {
                out += "長さ:${odMinDur.ifBlank { "0" }}〜${odMaxDur.ifBlank { "∞" }}分"
            }
            if (odMinViewsMan.isNotBlank()) out += "${odMinViewsMan}万回以上"
            return out
        }

        companion object {
            fun fromJson(o: JSONObject): Cond {
                val feats = mutableSetOf<Int>()
                val arr = o.optJSONArray("features")
                if (arr != null) for (i in 0 until arr.length()) feats += arr.optInt(i)
                return Cond(
                    sort = o.optInt("sort", 0),
                    upload = o.optInt("upload", 0),
                    duration = o.optInt("duration", 0),
                    features = feats,
                    gl = o.optString("gl", "JP"),
                    hl = o.optString("hl", "ja"),
                    opIntitle = o.optString("opIntitle", ""),
                    opPhrase = o.optString("opPhrase", ""),
                    opAfter = o.optString("opAfter", ""),
                    opBefore = o.optString("opBefore", ""),
                    odExcludeKw = o.optString("odExcludeKw", ""),
                    odExcludeCh = o.optString("odExcludeCh", ""),
                    odMinDur = o.optString("odMinDur", ""),
                    odMaxDur = o.optString("odMaxDur", ""),
                    odMinViewsMan = o.optString("odMinViewsMan", ""),
                )
            }
        }
    }

    private lateinit var adapter: VideoAdapter
    private lateinit var status: TextView
    private lateinit var summary: TextView
    private lateinit var headingView: TextView
    private lateinit var sortRow: LinearLayout
    private lateinit var uploadRow: LinearLayout
    private lateinit var panel: LinearLayout
    private lateinit var durationRow: LinearLayout
    private lateinit var featureRow: LinearLayout
    private lateinit var regionRow: LinearLayout
    private lateinit var languageRow: LinearLayout
    private lateinit var detailToggle: TextView
    private var fIntitle: EditText? = null
    private var fPhrase: EditText? = null
    private var fAfter: EditText? = null
    private var fBefore: EditText? = null
    private var fExKw: EditText? = null
    private var fExCh: EditText? = null
    private var fMinDur: EditText? = null
    private var fMaxDur: EditText? = null
    private var fMinViews: EditText? = null

    private var cond: Cond = Cond()
    private var baseQuery: String = ""
    private var continuation: String? = null
    private var loading = false
    private var refillRounds = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        baseQuery = intent.getStringExtra("query") ?: ""
        Prefs.searchConditions(this)?.let {
            try { cond = Cond.fromJson(JSONObject(it)) } catch (e: Exception) { /* keep defaults */ }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            val sb = resources.getIdentifier("status_bar_height", "dimen", "android")
            setPadding(0, if (sb > 0) resources.getDimensionPixelSize(sb) else 0, 0, 0)
        }

        // Pill search bar, prefilled; submitting re-runs in place with
        // the current conditions.
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(14), dp(16), dp(6))
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val input = EditText(this).apply {
            setText(baseQuery)
            setHint("検索")
            setHintTextColor(Ui.MUTED)
            setTextColor(Ui.TEXT)
            textSize = 15f
            background = Ui.rounded(Ui.SURFACE, 28, this@SearchActivity)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setSingleLine(true)
        }
        val go = Ui.text(this, "検索", 14f, Color.BLACK, true).apply {
            background = Ui.rounded(Color.WHITE, 28, this@SearchActivity)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            isClickable = true
            setOnClickListener { submitQuery(input.text.toString()) }
        }
        input.setOnEditorActionListener { _, _, _ -> submitQuery(input.text.toString()); true }
        searchRow.addView(input)
        searchRow.addView(go, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(8) })
        root.addView(searchRow)

        headingView = Ui.heading(this, "")
        root.addView(headingView)

        // Sort and upload-date chip rows: one tap applies and re-runs.
        sortRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(chipScroll(sortRow))
        uploadRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(chipScroll(uploadRow))

        // Detail toggle + active-condition summary.
        val detailRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        detailToggle = Ui.text(this, "詳細条件 ▾", 13f, Ui.TEXT, true).apply {
            background = Ui.rounded(Ui.SURFACE, 18, this@SearchActivity)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            isClickable = true
            setOnClickListener {
                val open = panel.visibility == View.VISIBLE
                panel.visibility = if (open) View.GONE else View.VISIBLE
                detailToggle.text = if (open) "詳細条件 ▾" else "詳細条件 ▴"
            }
        }
        detailRow.addView(detailToggle)
        summary = Ui.text(this, "", 12f, Ui.MUTED).apply {
            setPadding(dp(10), 0, 0, 0)
        }
        detailRow.addView(summary)
        root.addView(detailRow)

        panel = buildPanel()
        panel.visibility = View.GONE
        root.addView(panel)

        status = Ui.text(this, "検索中…", 13f, Ui.MUTED).apply {
            setPadding(dp(16), dp(8), dp(16), dp(6))
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
                    loadNext(cont)
                }
            }
        })
        root.addView(rv)
        setContentView(root)

        rebuildChipRows()
        updateSummary()
        runSearch()
    }

    // -- UI construction -------------------------------------------------

    private fun chipScroll(row: LinearLayout): HorizontalScrollView =
        HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(16), dp(6), dp(16), 0)
            addView(row)
        }

    private fun chip(label: String, selected: Boolean, onClick: () -> Unit): TextView =
        Ui.text(this, label, 13f, if (selected) Color.BLACK else Ui.TEXT, selected).apply {
            background = Ui.rounded(if (selected) Color.WHITE else Ui.SURFACE, 18, this@SearchActivity)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun addChip(row: LinearLayout, label: String, selected: Boolean, onClick: () -> Unit) {
        row.addView(chip(label, selected, onClick), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(8) })
    }

    private fun rebuildChipRows() {
        sortRow.removeAllViews()
        for ((label, v) in SORTS) {
            addChip(sortRow, label, cond.sort == v) {
                cond.sort = v; persist(); rebuildChipRows(); updateSummary(); runSearch()
            }
        }
        uploadRow.removeAllViews()
        for ((label, v) in UPLOADS) {
            addChip(uploadRow, label, cond.upload == v) {
                cond.upload = v; persist(); rebuildChipRows(); updateSummary(); runSearch()
            }
        }
    }

    private fun panelLabel(s: String): TextView =
        Ui.text(this, s, 12.5f, Ui.MUTED, true).apply {
            setPadding(dp(16), dp(14), dp(16), dp(6))
        }

    private fun panelChipRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(16), 0, dp(16), 0)
    }

    private fun field(hint: String, initial: String, numeric: Boolean = false): EditText =
        EditText(this).apply {
            setText(initial)
            setHint(hint)
            setHintTextColor(Ui.MUTED)
            setTextColor(Ui.TEXT)
            textSize = 13.5f
            background = Ui.rounded(Ui.SURFACE, 12, this@SearchActivity)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            setSingleLine(true)
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(8) }
        }

    private fun fieldRow(vararg fields: EditText): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), 0, dp(8), 0)
            for (f in fields) addView(f)
        }

    private fun buildPanel(): LinearLayout {
        val p = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        p.addView(panelLabel("長さ（YouTube側）"))
        durationRow = panelChipRow()
        p.addView(durationRow)
        p.addView(panelLabel("特徴（YouTube側・複数可）"))
        featureRow = panelChipRow()
        p.addView(featureRow)
        p.addView(panelLabel("地域"))
        regionRow = panelChipRow()
        p.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(regionRow)
        })
        p.addView(panelLabel("言語"))
        languageRow = panelChipRow()
        p.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(languageRow)
        })
        rebuildPanelChips()

        p.addView(panelLabel("検索演算子（タイトル指定・フレーズ・年）"))
        fIntitle = field("タイトルに含む語", cond.opIntitle)
        fPhrase = field("完全一致フレーズ", cond.opPhrase)
        p.addView(fieldRow(fIntitle!!, fPhrase!!))
        fAfter = field("年以降（例 2020）", cond.opAfter, numeric = true)
        fBefore = field("年以前（例 2019）", cond.opBefore, numeric = true)
        p.addView(fieldRow(fAfter!!, fBefore!!).apply { setPadding(dp(16), dp(8), dp(8), 0) })

        p.addView(panelLabel("オンデバイス絞り込み（取得後に適用）"))
        fExKw = field("除外キーワード", cond.odExcludeKw)
        fExCh = field("除外チャンネル", cond.odExcludeCh)
        p.addView(fieldRow(fExKw!!, fExCh!!))
        fMinDur = field("最短（分）", cond.odMinDur, numeric = true)
        fMaxDur = field("最長（分）", cond.odMaxDur, numeric = true)
        fMinViews = field("最低再生回数（万回）", cond.odMinViewsMan, numeric = true)
        p.addView(fieldRow(fMinDur!!, fMaxDur!!, fMinViews!!).apply {
            setPadding(dp(16), dp(8), dp(8), 0)
        })

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(14), dp(16), dp(6))
        }
        val reset = Ui.text(this, "リセット", 13.5f, Ui.TEXT, true).apply {
            background = Ui.rounded(Ui.SURFACE, 18, this@SearchActivity)
            setPadding(dp(16), dp(9), dp(16), dp(9))
            isClickable = true
            setOnClickListener {
                cond = Cond()
                persist()
                rebuildChipRows()
                rebuildPanelChips()
                syncFieldsFromCond()
                updateSummary()
                runSearch()
            }
        }
        buttons.addView(reset)
        val apply = Ui.text(this, "この条件で検索", 13.5f, Color.BLACK, true).apply {
            background = Ui.rounded(Color.WHITE, 18, this@SearchActivity)
            setPadding(dp(16), dp(9), dp(16), dp(9))
            isClickable = true
            setOnClickListener {
                syncFieldsToCond()
                persist()
                updateSummary()
                panel.visibility = View.GONE
                detailToggle.text = "詳細条件 ▾"
                runSearch()
            }
        }
        buttons.addView(apply, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(10) })
        p.addView(buttons)
        return p
    }

    private fun rebuildPanelChips() {
        durationRow.removeAllViews()
        for ((label, v) in DURATIONS) {
            addChip(durationRow, label, cond.duration == v) {
                cond.duration = v; rebuildPanelChips()
            }
        }
        featureRow.removeAllViews()
        for ((label, v) in FEATURES) {
            val sel = v in cond.features
            addChip(featureRow, label, sel) {
                if (sel) cond.features.remove(v) else cond.features.add(v)
                rebuildPanelChips()
            }
        }
        regionRow.removeAllViews()
        for ((label, v) in REGIONS) {
            addChip(regionRow, label, cond.gl == v) {
                cond.gl = v; rebuildPanelChips()
            }
        }
        languageRow.removeAllViews()
        for ((label, v) in LANGUAGES) {
            addChip(languageRow, label, cond.hl == v) {
                cond.hl = v; rebuildPanelChips()
            }
        }
    }

    private fun syncFieldsFromCond() {
        fIntitle?.setText(cond.opIntitle)
        fPhrase?.setText(cond.opPhrase)
        fAfter?.setText(cond.opAfter)
        fBefore?.setText(cond.opBefore)
        fExKw?.setText(cond.odExcludeKw)
        fExCh?.setText(cond.odExcludeCh)
        fMinDur?.setText(cond.odMinDur)
        fMaxDur?.setText(cond.odMaxDur)
        fMinViews?.setText(cond.odMinViewsMan)
    }

    private fun syncFieldsToCond() {
        cond.opIntitle = fIntitle?.text?.toString()?.trim().orEmpty()
        cond.opPhrase = fPhrase?.text?.toString()?.trim().orEmpty()
        cond.opAfter = fAfter?.text?.toString()?.trim().orEmpty()
        cond.opBefore = fBefore?.text?.toString()?.trim().orEmpty()
        cond.odExcludeKw = fExKw?.text?.toString()?.trim().orEmpty()
        cond.odExcludeCh = fExCh?.text?.toString()?.trim().orEmpty()
        cond.odMinDur = fMinDur?.text?.toString()?.trim().orEmpty()
        cond.odMaxDur = fMaxDur?.text?.toString()?.trim().orEmpty()
        cond.odMinViewsMan = fMinViews?.text?.toString()?.trim().orEmpty()
    }

    private fun persist() {
        Prefs.setSearchConditions(this, cond.toJson().toString())
    }

    private fun updateSummary() {
        val labels = cond.summaryLabels()
        summary.text = if (labels.isEmpty()) "" else labels.joinToString("・")
    }

    // -- searching ---------------------------------------------------------

    private fun submitQuery(q: String) {
        val query = q.trim()
        if (query.isEmpty()) return
        baseQuery = query
        syncFieldsToCond()
        persist()
        updateSummary()
        runSearch()
    }

    /** The query string with the operator-assist fields composed in.
     * 年以前 is inclusive of that year, so it becomes
     * before:(year+1)-01-01. */
    private fun effectiveQuery(): String {
        val parts = mutableListOf<String>()
        if (cond.opPhrase.isNotBlank()) parts += "\"${cond.opPhrase}\""
        if (cond.opIntitle.isNotBlank()) {
            for (w in cond.opIntitle.split(Regex("\\s+"))) {
                if (w.isNotBlank()) parts += "intitle:$w"
            }
        }
        parts += baseQuery
        val after = cond.opAfter.toIntOrNull()
        if (after != null && after in 1900..2100) parts += "after:%04d-01-01".format(after)
        val before = cond.opBefore.toIntOrNull()
        if (before != null && before in 1900..2100) parts += "before:%04d-01-01".format(before + 1)
        return parts.joinToString(" ")
    }

    private fun optionsJson(): String = JSONObject()
        .put("sort", cond.sort)
        .put("upload", cond.upload)
        .put("duration", cond.duration)
        .put("features", JSONArray(cond.features.toList()))
        .put("hl", cond.hl)
        .put("gl", cond.gl)
        .toString()

    private fun runSearch() {
        if (baseQuery.isBlank()) {
            headingView.text = "検索"
            status.text = "キーワードを入力してください"
            return
        }
        headingView.text = "「$baseQuery」の検索結果"
        loading = true
        refillRounds = 0
        continuation = null
        status.text = "検索中…"
        Bridge.call("search_opt", effectiveQuery(), optionsJson()) { data, err ->
            loading = false
            if (err != null || data == null) {
                status.text = "検索エラー: $err"
                return@call
            }
            val raw = Video.list(data.optJSONArray("items"))
                .filter { it.id.isNotBlank() && it.kind == "video" }
            continuation = if (data.isNull("continuation")) null else data.optString("continuation")
            val visible = applyOnDevice(raw)
            adapter.set(visible)
            afterPage(visible.size)
        }
    }

    private fun loadNext(cont: String) {
        loading = true
        status.text = "続きを読み込み中…"
        Bridge.call("search_next", cont, cond.hl, cond.gl) { data, err ->
            loading = false
            if (err != null || data == null) {
                status.text = "ページングエラー: $err"
                return@call
            }
            val raw = Video.list(data.optJSONArray("items"))
                .filter { it.kind == "video" }
            continuation = if (data.isNull("continuation")) null else data.optString("continuation")
            val visible = applyOnDevice(raw)
            adapter.add(visible)
            afterPage(visible.size)
        }
    }

    /** After a page lands: if on-device filters cut it to almost
     * nothing and more pages exist, pull the next page right away
     * (bounded, so a pathological filter cannot drain the API). */
    private fun afterPage(addedVisible: Int) {
        val cont = continuation
        if (cond.hasOnDevice() && adapter.itemCount < 10 && cont != null && refillRounds < 3) {
            refillRounds++
            status.text = "条件で絞り込み中…"
            loadNext(cont)
            return
        }
        status.text = when {
            adapter.itemCount == 0 && continuation == null && cond.hasOnDevice() ->
                "条件に合う動画が見つかりませんでした"
            adapter.itemCount == 0 -> "0 件"
            else -> "全 ${adapter.itemCount} 件"
        }
    }

    // -- on-device filters -------------------------------------------------

    private fun durationSeconds(v: Video): Int? {
        val t = v.duration ?: return null
        if (t.equals("LIVE", ignoreCase = true)) return null
        val parts = t.split(":")
        var s = 0
        for (p in parts) {
            val n = p.toIntOrNull() ?: return null
            s = s * 60 + n
        }
        return s
    }

    private fun applyOnDevice(items: List<Video>): List<Video> {
        if (!cond.hasOnDevice()) return items
        val exKw = cond.odExcludeKw.split(Regex("[\\s,、]+")).filter { it.isNotBlank() }
            .map { it.lowercase() }
        val exCh = cond.odExcludeCh.split(Regex("[\\s,、]+")).filter { it.isNotBlank() }
            .map { it.lowercase() }
        val minDurSec = cond.odMinDur.toIntOrNull()?.times(60)
        val maxDurSec = cond.odMaxDur.toIntOrNull()?.times(60)
        val minViews = cond.odMinViewsMan.toLongOrNull()?.times(10_000)
        return items.filter { v ->
            val title = v.title.lowercase()
            val ch = (v.channelName ?: "").lowercase()
            if (exKw.any { it in title || it in ch }) return@filter false
            if (exCh.any { it in ch }) return@filter false
            if (minDurSec != null || maxDurSec != null) {
                val d = durationSeconds(v)
                if (d != null) {
                    if (minDurSec != null && d < minDurSec) return@filter false
                    if (maxDurSec != null && d > maxDurSec) return@filter false
                }
            }
            if (minViews != null) {
                // Live concurrent-viewer labels are not view counts;
                // unparseable counts are kept rather than dropped.
                val label = v.views
                if (label != null && !label.contains("視聴中")) {
                    val n = parseViews(label)
                    if (n != null && n < minViews) return@filter false
                }
            }
            true
        }
    }

    private companion object {
        val SORTS = listOf("関連度" to 0, "評価" to 1, "新しい順" to 2, "再生回数" to 3)
        val UPLOADS = listOf(
            "すべて" to 0, "1時間以内" to 1, "今日" to 2,
            "今週" to 3, "今月" to 4, "今年" to 5,
        )
        val DURATIONS = listOf("すべて" to 0, "3分未満" to 4, "3〜20分" to 5, "20分以上" to 2)
        val FEATURES = listOf("ライブ" to 8, "HD" to 4, "4K" to 14, "字幕" to 5)
        val REGIONS = listOf(
            "日本" to "JP", "アメリカ" to "US", "イギリス" to "GB", "韓国" to "KR",
            "台湾" to "TW", "ドイツ" to "DE", "フランス" to "FR", "インド" to "IN",
        )
        val LANGUAGES = listOf(
            "日本語" to "ja", "英語" to "en", "韓国語" to "ko",
            "中国語（簡）" to "zh-Hans", "中国語（繁）" to "zh-Hant",
            "ドイツ語" to "de", "フランス語" to "fr", "スペイン語" to "es",
        )
    }
}
