package top.brokestar.emojiexporter.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import top.brokestar.emojiexporter.R

/** 列表项数据：epId + 名称 + 封面 URL（主页直接显示缩略图）+ 搜索结果的 eId（详情页预览用）。 */
data class PackItem(
    val epId: String,
    val title: String,
    val coverUrl: String? = null,
    val eId: String? = null,
)

/**
 * 表情包列表适配器：卡片式，封面缩略图 + 名称 + epId。
 * - 点整行 → onOpenDetail（进详情页预览）
 * - 点"导出"按钮 → onExport（快速单包导出）
 * - 长按 → 进入多选模式，CheckBox 显现，切换选中 → onSelectionChanged
 *
 * 性能要点：
 *  - 用 stable id（epId），切换多选/选中只刷新受影响条目，避免整列表重绑导致封面图重加载
 *  - onBindViewHolder 只在 payload 为 SELECTION 时更新选中态，不复触发图片加载
 */
class PackAdapter(
    private val onExport: (PackItem) -> Unit,
    private val onOpenDetail: (PackItem) -> Unit,
    private val onSelectionChanged: (Set<String>) -> Unit,
) : RecyclerView.Adapter<PackAdapter.Holder>() {

    private val data = mutableListOf<PackItem>()
    private val selectedIds = mutableSetOf<String>()
    private var multiSelectMode = false

    init { setHasStableIds(true) }

    override fun getItemId(position: Int): Long =
        data[position].epId.hashCode().toLong()

    fun submit(list: List<PackItem>) {
        data.clear(); data.addAll(list)
        selectedIds.clear(); multiSelectMode = false
        notifyDataSetChanged()
    }

    /** 追加更多（翻页加载）。 */
    fun append(list: List<PackItem>) {
        val start = data.size
        data.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    /** 末尾占位"加载中"项（用 selectedIds 占位不可，需要专门类型——这里用 epId=LOADING_MARKER 占位）。 */
    fun appendLoading() { /* 占位由滚动条上的 ProgressBar 承担，这里 no-op 保持简单 */ }
    fun removeLoading() {}

    fun enterMultiSelect() { if (!multiSelectMode) { multiSelectMode = true; notifyDataSetChanged() } }
    fun exitMultiSelect() { if (multiSelectMode || selectedIds.isNotEmpty()) { multiSelectMode = false; selectedIds.clear(); notifyDataSetChanged(); onSelectionChanged(emptySet()) } }
    fun selectAll() { selectedIds.clear(); data.forEach { selectedIds.add(it.epId) }; notifyDataSetChanged(); onSelectionChanged(selectedIds.toSet()) }
    fun isSelected(epId: String) = selectedIds.contains(epId)
    fun selectedPacks(): List<PackItem> = data.filter { selectedIds.contains(it.epId) }
    fun selectedCount() = selectedIds.size

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val cb: CheckBox = v.findViewById(R.id.cbSelect)
        val iv: ImageView = v.findViewById(R.id.ivCover)
        val tvName: TextView = v.findViewById(R.id.tvName)
        val tvEpId: TextView = v.findViewById(R.id.tvEpId)
        val btnExport: View = v.findViewById(R.id.btnExport)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        Holder(LayoutInflater.from(p.context).inflate(R.layout.item_pack, p, false))

    override fun getItemCount() = data.size

    override fun onBindViewHolder(h: Holder, pos: Int) {
        onBindFull(h, pos)
    }

    override fun onBindViewHolder(h: Holder, pos: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_SELECTION)) {
            // 仅刷新选中态：不重新触发图片加载，避免滑动/多选时的卡顿
            applySelectionState(h, pos)
        } else {
            onBindFull(h, pos)
        }
    }

    private fun onBindFull(h: Holder, pos: Int) {
        val item = data[pos]
        h.tvName.text = item.title
        h.tvEpId.text = "epId: ${item.epId}"
        // 封面：coverUrl 已是绝对 URL（CDN 或 hook 兜底）；为空时按 epId 拼 CDN 模板
        val coverUrl = item.coverUrl?.takeIf { it.isNotBlank() }
            ?: top.brokestar.emojiexporter.data.CoverUrl.cdn(item.epId)
        h.iv.load(coverUrl) {
            crossfade(true)
            error(R.drawable.ic_emoji_placeholder)
            placeholder(R.drawable.ic_emoji_placeholder)
        }
        applySelectionState(h, pos)
        h.itemView.setOnClickListener {
            if (multiSelectMode) toggleSelect(item.epId, pos)
            else onOpenDetail(item)
        }
        h.itemView.setOnLongClickListener {
            if (!multiSelectMode) enterMultiSelect()
            toggleSelect(item.epId, pos)
            true
        }
        h.cb.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) selectedIds.add(item.epId) else selectedIds.remove(item.epId)
            onSelectionChanged(selectedIds.toSet())
        }
        h.btnExport.setOnClickListener { onExport(item) }
    }

    private fun applySelectionState(h: Holder, pos: Int) {
        val item = data[pos]
        h.cb.visibility = if (multiSelectMode) View.VISIBLE else View.GONE
        h.btnExport.visibility = if (multiSelectMode) View.GONE else View.VISIBLE
        h.cb.setOnCheckedChangeListener(null)
        h.cb.isChecked = selectedIds.contains(item.epId)
    }

    private fun toggleSelect(epId: String, pos: Int) {
        if (selectedIds.contains(epId)) selectedIds.remove(epId) else selectedIds.add(epId)
        notifyItemChanged(pos, PAYLOAD_SELECTION)
        onSelectionChanged(selectedIds.toSet())
        if (selectedIds.isEmpty() && multiSelectMode) exitMultiSelect()
    }

    companion object { private const val PAYLOAD_SELECTION = "sel" }
}
