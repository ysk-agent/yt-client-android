package app.ytclient

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Channel list of one feed group. Names are resolved lazily through
 * channel_info; the channel id is shown until (and unless) it resolves. */
class ChannelListActivity : AppCompatActivity() {
    private val ids = ArrayList<String>()
    private val names = HashMap<String, String>()
    private lateinit var adapter: ListAdapter

    inner class ListAdapter : RecyclerView.Adapter<ListAdapter.H>() {
        inner class H(val tv: TextView) : RecyclerView.ViewHolder(tv)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): H {
            val act = this@ChannelListActivity
            val tv = Ui.text(act, "", 15f, Ui.TEXT).apply {
                setPadding(act.dp(16), act.dp(14), act.dp(16), act.dp(14))
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                isClickable = true
            }
            return H(tv)
        }
        override fun getItemCount() = ids.size
        override fun onBindViewHolder(holder: H, position: Int) {
            val id = ids[position]
            holder.tv.text = names[id] ?: id
            holder.tv.setOnClickListener { Ui.openChannel(this@ChannelListActivity, id, names[id]) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val group = intent.getStringExtra("group_name") ?: "グループ"
        // Accept both ArrayList (in-app) and String[] (adb --esa) extras.
        ids.addAll(
            intent.getStringArrayListExtra("channel_ids")
                ?: intent.getStringArrayExtra("channel_ids")?.toList()
                ?: emptyList()
        )
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            val sb = resources.getIdentifier("status_bar_height", "dimen", "android")
            setPadding(0, if (sb > 0) resources.getDimensionPixelSize(sb) else 0, 0, 0)
        }
        root.addView(Ui.heading(this, "「$group」のチャンネル"))
        root.addView(Ui.text(this, "${ids.size} チャンネル", 12.5f, Ui.MUTED).apply {
            setPadding(dp(16), 0, dp(16), dp(6))
        })
        adapter = ListAdapter()
        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ChannelListActivity)
            adapter = this@ChannelListActivity.adapter
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(rv)
        setContentView(root)

        for ((index, id) in ids.withIndex()) {
            Bridge.call("channel_info", id) { data, _ ->
                val name = data?.optString("name")?.ifBlank { null } ?: return@call
                names[id] = name
                adapter.notifyItemChanged(index)
            }
        }
    }
}
