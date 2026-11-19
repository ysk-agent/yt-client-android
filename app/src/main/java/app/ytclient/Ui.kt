package app.ytclient

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

object Ui {
    const val BG = 0xFF0B0B10.toInt()
    const val SURFACE = 0xFF17171F.toInt()
    const val TEXT = 0xFFF5F5F7.toInt()
    const val MUTED = 0xFFB9B9C4.toInt()

    fun Context.dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    fun text(ctx: Context, s: String, sizeSp: Float, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun heading(ctx: Context, s: String): TextView = text(ctx, s, 19f, TEXT, true).apply {
        setPadding(ctx.dp(16), ctx.dp(18), ctx.dp(16), ctx.dp(8))
    }

    fun rounded(color: Int, radiusDp: Int, ctx: Context): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = ctx.dp(radiusDp).toFloat() }

    fun openDetail(ctx: Context, v: Video) {
        ctx.startActivity(Intent(ctx, DetailActivity::class.java).apply {
            putExtra("video_id", v.id)
            putExtra("title", v.title)
            putExtra("channel_name", v.channelName)
            putExtra("channel_id", v.channelId)
            putExtra("duration", v.duration)
            putExtra("views", v.views)
            putExtra("published", v.published)
            putExtra("description", v.description)
        })
    }

    fun openChannel(ctx: Context, channelId: String, name: String?) {
        ctx.startActivity(Intent(ctx, ChannelActivity::class.java).apply {
            putExtra("channel_id", channelId)
            putExtra("channel_name", name)
        })
    }

    /** Landscape 16:9 card with title + meta underneath (streaming style). */
    fun landscapeCard(ctx: Context, v: Video, widthDp: Int = 248): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(ctx.dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(0, 0, ctx.dp(12), 0)
            isClickable = true
            setOnClickListener { openDetail(ctx, v) }
        }
        val card = MaterialCardView(ctx).apply {
            radius = ctx.dp(12).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(SURFACE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(widthDp * 9 / 16))
        }
        val iv = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        Img.load(iv, Img.thumbUrl(v.id))
        card.addView(iv)
        root.addView(card)
        root.addView(text(ctx, v.title, 14f, TEXT, true).apply {
            maxLines = 2
            setPadding(0, ctx.dp(6), ctx.dp(4), 0)
        })
        root.addView(text(ctx, v.meta(), 11.5f, MUTED).apply { maxLines = 1 })
        return root
    }

    /** Full-width row card used by search results. */
    fun wideCard(ctx: Context, v: Video): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(ctx.dp(16), ctx.dp(6), ctx.dp(16), ctx.dp(6))
            isClickable = true
            setOnClickListener { openDetail(ctx, v) }
        }
        val card = MaterialCardView(ctx).apply {
            radius = ctx.dp(12).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(SURFACE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(196))
        }
        val iv = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        Img.load(iv, Img.thumbUrl(v.id))
        card.addView(iv)
        root.addView(card)
        root.addView(text(ctx, v.title, 15f, TEXT, true).apply {
            maxLines = 2
            setPadding(0, ctx.dp(6), 0, 0)
        })
        val meta = text(ctx, v.meta(), 12f, MUTED)
        root.addView(meta)
        if (!v.channelId.isNullOrBlank() && !v.channelName.isNullOrBlank()) {
            meta.isClickable = true
            meta.setOnClickListener { openChannel(ctx, v.channelId!!, v.channelName) }
        }
        return root
    }
}

/** A RecyclerView.Adapter that builds each row view on bind. */
class VideoAdapter(
    private val ctx: Context,
    private val wide: Boolean,
) : RecyclerView.Adapter<VideoAdapter.H>() {
    val items = ArrayList<Video>()
    class H(v: View) : RecyclerView.ViewHolder(v)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): H {
        val container = LinearLayout(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        return H(container)
    }
    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: H, position: Int) {
        val container = holder.itemView as LinearLayout
        container.removeAllViews()
        val v = items[position]
        container.addView(if (wide) Ui.wideCard(ctx, v) else Ui.landscapeCard(ctx, v))
    }
    fun set(list: List<Video>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
    fun add(list: List<Video>) { val at = items.size; items.addAll(list); notifyItemRangeInserted(at, list.size) }
}
