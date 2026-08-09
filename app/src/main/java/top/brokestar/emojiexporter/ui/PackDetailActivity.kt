package top.brokestar.emojiexporter.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import top.brokestar.emojiexporter.R
import top.brokestar.emojiexporter.data.CdnDownloader
import top.brokestar.emojiexporter.data.CoverUrl
import top.brokestar.emojiexporter.data.QqMallApi
import top.brokestar.emojiexporter.export.ExportManager
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 表情包详情页：封面大图 + 名称 + 表情网格预览（缩略图从 CDN 加载）+ 导出全部。
 * 入口：列表 item 点击。Intent extras: epId / name / coverUrl。
 */
class PackDetailActivity : AppCompatActivity() {

    private lateinit var toolbar: Toolbar
    private lateinit var ivCover: ImageView
    private lateinit var tvEpId: TextView
    private lateinit var tvCount: TextView
    private lateinit var btnExport: MaterialButton
    private lateinit var progress: ProgressBar
    private lateinit var rv: RecyclerView

    private val epId by lazy { intent.getStringExtra("epId") ?: "" }
    private val name by lazy { intent.getStringExtra("name") }
    private val coverUrl by lazy { intent.getStringExtra("coverUrl") }
    private var eIds: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)
        toolbar = findViewById(R.id.toolbar)
        ivCover = findViewById(R.id.ivCover)
        tvEpId = findViewById(R.id.tvEpId)
        tvCount = findViewById(R.id.tvCount)
        btnExport = findViewById(R.id.btnExportAll)
        progress = findViewById(R.id.progress)
        rv = findViewById(R.id.rvEmojis)

        // setSupportActionBar 接管后必须用 supportActionBar 设标题，否则会被 ActionBar 默认标题（App 名）覆盖
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            title = name?.takeIf { it.isNotBlank() } ?: "epId: $epId"
            setDisplayHomeAsUpEnabled(true)
        }
        toolbar.setNavigationOnClickListener { finish() }

        tvEpId.text = "epId: $epId"
        // 封面：统一用 img/parcel CDN 模板（与外部列表一致，稳定可访问）
        ivCover.load(CoverUrl.cdn(epId)) {
            crossfade(true); error(R.drawable.ic_emoji_placeholder); placeholder(R.drawable.ic_emoji_placeholder)
        }

        btnExport.setOnClickListener { exportAll() }
        rv.layoutManager = GridLayoutManager(this, 4)
        loadEmojis()
    }

    private fun loadEmojis() {
        progress.visibility = View.VISIBLE
        tvCount.text = "加载中…"
        lifecycleScope.launch {
            val (items, cdnEIds) = withContext(Dispatchers.IO) {
                // 优先走 QQ 内部清单（覆盖 CDN 没有的专属包），fallback CDN xydata
                val its = QqMallApi.fetchItems(this@PackDetailActivity, epId)
                val cdn = if (its.isNullOrEmpty()) CdnDownloader.fetchXydata(epId) else emptyList()
                its to cdn
            }
            eIds = if (!items.isNullOrEmpty()) items.map { it.eId } else cdnEIds
            progress.visibility = View.GONE
            if (eIds.isEmpty()) {
                tvCount.text = "未能加载表情清单（QQ 未下载此包，或网络异常）"
                return@launch
            }
            tvCount.text = "共 ${eIds.size} 个表情"
            rv.adapter = EmojiGridAdapter(epId, eIds, items?.associate { it.eId to (it.name) }) { eId -> showBigPreview(eId) }
        }
    }

    private fun exportAll() {
        btnExport.isEnabled = false
        btnExport.text = "导出中…"
        lifecycleScope.launch {
            try {
                val dir = ExportManager(this@PackDetailActivity).exportPackage(epId, name) { p ->
                    runOnUiThread { btnExport.text = "${p.done}/${p.total}" }
                }
                // 成功：按钮显示路径，不可再点（需重新进页面才再导出）
                btnExport.text = "✓ ${dir.absolutePath}"
                btnExport.isEnabled = false
            } catch (e: Throwable) {
                Toast.makeText(this@PackDetailActivity, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
                btnExport.isEnabled = true
                btnExport.text = "导出全部"
            }
        }
    }

    /** 点击缩略图放大预览（Dialog，走 hook big 图 QQ 内部解密）。 */
    private fun showBigPreview(eId: String) {
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(48, 48, 48, 48)
        }
        val bigUrl = QqMallApi.imageUrl(this, epId, eId, "big")
        iv.load(bigUrl) { crossfade(true); error(R.drawable.ic_emoji_placeholder) }
        AlertDialog.Builder(this).setView(iv).setOnCancelListener { }.show()
    }
}

/** 表情网格适配器：每个 eId 一张缩略图（走 hook QQ 内部下载）。 */
class EmojiGridAdapter(
    private val epId: String,
    private val eIds: List<String>,
    private val names: Map<String, String?>?,
    private val onClick: (String) -> Unit,
) : RecyclerView.Adapter<EmojiGridAdapter.Holder>() {
    class Holder(v: View) : RecyclerView.ViewHolder(v) { val iv: ImageView = v.findViewById(R.id.ivEmoji) }
    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        Holder(LayoutInflater.from(p.context).inflate(R.layout.item_emoji_grid, p, false))
    override fun getItemCount() = eIds.size
    override fun onBindViewHolder(h: Holder, pos: Int) {
        val eId = eIds[pos]
        val ctx = h.itemView.context
        val hookUrl = QqMallApi.imageUrl(ctx, epId, eId, "aio")
        h.iv.load(hookUrl) { crossfade(true); error(R.drawable.ic_emoji_placeholder); placeholder(R.drawable.ic_emoji_placeholder) }
        h.iv.setOnClickListener { onClick(eId) }
    }
}
