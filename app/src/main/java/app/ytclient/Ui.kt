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

    /** Sets a TextView's line height to an exact pixel value, compensating
     * for the font's natural metrics. Text size must be set first. */
    fun setLineHeightPx(tv: TextView, lineHeightPx: Float) {
        val fm = tv.paint.fontMetrics
        tv.setLineSpacing(lineHeightPx - (fm.descent - fm.ascent), 1f)
        tv.includeFontPadding = false
    }

    fun truncateTitle(title: String, max: Int = 40): String =
        if (title.length > max) title.substring(0, max) + "…" else title

    /**
     * The shared video row used by Home feed, Search results and Channel
     * videos. Thumbnail is 16:9 at a debug-adjustable percentage of the row
     * width (Prefs.thumbPct). Font sizes are derived from the thumbnail
     * height H so the text block (title 2 lines + meta 1 line) fills H
     * exactly, and every row in a list has the same height:
     *   title font 0.24H / line 0.36H, meta font 0.187H / line 0.28H.
     * Lists are expected to give the RecyclerView dp(12) horizontal padding.
     */
    fun videoRow(ctx: Context, v: Video): View {
        val contentW = ctx.resources.displayMetrics.widthPixels - ctx.dp(24)
        val thumbW = contentW * Prefs.thumbPct(ctx) / 100
        val thumbH = thumbW * 9 / 16
        val padV = ctx.dp(10)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, thumbH + padV * 2
            )
            setPadding(0, padV, 0, padV)
            isClickable = true
            setOnClickListener { openDetail(ctx, v) }
        }

        // Thumbnail with optional badges.
        val thumbBox = android.widget.FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(thumbW, thumbH)
            background = rounded(SURFACE, 8, ctx)
            clipToOutline = true
        }
        val iv = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        Img.load(iv, Img.thumbUrl(v.id))
        thumbBox.addView(iv)
        fun badge(label: String, gravity: Int, bg: Int) {
            val b = TextView(ctx).apply {
                text = label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, thumbH * 0.15f)
                background = rounded(bg, 4, ctx)
                setPadding(ctx.dp(5), ctx.dp(1), ctx.dp(5), ctx.dp(1))
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravity
                ).apply { setMargins(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4)) }
            }
            thumbBox.addView(b)
        }
        if (v.isLive) {
            badge("LIVE", Gravity.BOTTOM or Gravity.START, 0xFFC62F3B.toInt())
        } else if (!v.duration.isNullOrBlank()) {
            badge(v.duration!!, Gravity.BOTTOM or Gravity.END, 0xD9000000.toInt())
        }
        root.addView(thumbBox)

        // Text column: exactly thumbH tall (2 title lines + 1 meta line).
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, thumbH, 1f).apply { marginStart = ctx.dp(11) }
        }
        val titleH = (thumbH * 0.72f).toInt()
        val metaH = thumbH - titleH
        val title = TextView(ctx).apply {
            text = truncateTitle(v.title)
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, thumbH * 0.24f)
            setTypeface(typeface, Typeface.BOLD)
            setLineHeightPx(this, thumbH * 0.36f)
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, titleH)
        }
        col.addView(title)

        // Meta: one line. Channel name ellipsizes (weighted); views + age
        // never shrink, so they always stay visible.
        val metaRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, metaH)
        }
        val metaFont = thumbH * 0.187f
        val tail = listOfNotNull(v.views, v.published).joinToString(" ・ ")
        if (!v.channelName.isNullOrBlank()) {
            val ch = TextView(ctx).apply {
                text = v.channelName
                setTextColor(MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, metaFont)
                setLineHeightPx(this, thumbH * 0.28f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            if (!v.channelId.isNullOrBlank()) {
                ch.isClickable = true
                ch.setOnClickListener { openChannel(ctx, v.channelId!!, v.channelName) }
            }
            metaRow.addView(ch)
        }
        if (tail.isNotBlank()) {
            val t = TextView(ctx).apply {
                text = if (!v.channelName.isNullOrBlank()) " ・ $tail" else tail
                setTextColor(MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, metaFont)
                setLineHeightPx(this, thumbH * 0.28f)
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            metaRow.addView(t)
        }
        col.addView(metaRow)
        root.addView(col)
        return root
    }
}

/** A RecyclerView.Adapter that builds the shared video row on bind. */
class VideoAdapter(
    private val ctx: Context,
) : RecyclerView.Adapter<VideoAdapter.H>() {
    val items = ArrayList<Video>()
    class H(v: View) : RecyclerView.ViewHolder(v)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): H {
        val container = LinearLayout(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        return H(container)
    }
    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: H, position: Int) {
        val container = holder.itemView as LinearLayout
        container.removeAllViews()
        container.addView(Ui.videoRow(ctx, items[position]))
    }
    fun set(list: List<Video>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
    fun add(list: List<Video>) { val at = items.size; items.addAll(list); notifyItemRangeInserted(at, list.size) }
}
