package top.brokestar.emojiexporter.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import top.brokestar.emojiexporter.R
import top.brokestar.emojiexporter.data.LsposedBridge
import top.brokestar.emojiexporter.data.QQDatabaseReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InstalledPacksFragment : Fragment() {
    private lateinit var adapter: PackAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_list, container, false)
        val rv = v.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv)
        // 已添加页不需要搜索条：整条隐藏
        v.findViewById<View>(R.id.searchBar).visibility = View.GONE

        // 批量栏视图
        val batchBar = v.findViewById<View>(R.id.batchBar)
        val tvSelected = v.findViewById<android.widget.TextView>(R.id.tvSelected)
        val btnSelectAll = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSelectAll)
        val btnBatchExport = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnBatchExport)
        val btnCancel = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancel)

        adapter = PackAdapter(
            onExport = { pack -> ListUiHelper.exportOne(this, pack) },
            onOpenDetail = { pack -> ListUiHelper.openDetail(this, pack) },
            onSelectionChanged = { selected ->
                // 选中数 >0 显示批量栏
                if (selected.isNotEmpty()) {
                    batchBar.visibility = View.VISIBLE
                    tvSelected.text = "已选 ${selected.size} 个"
                } else {
                    batchBar.visibility = View.GONE
                }
            },
        )
        rv.layoutManager = LinearLayoutManager(context)
        rv.adapter = adapter
        // 性能：固定高度列表 + 提高离屏缓存，减少滑动时 onCreateViewHolder/onBind 调用
        rv.setHasFixedSize(true)
        rv.setItemViewCacheSize(8)
        (rv.itemAnimator as? androidx.recyclerview.widget.DefaultItemAnimator)?.let {
            it.addDuration = 0; it.changeDuration = 0; it.moveDuration = 0; it.removeDuration = 0
        }
        ViewCompat.setOnApplyWindowInsetsListener(rv) { view, insets ->
            view.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
            insets
        }
        // 批量栏按钮
        btnSelectAll.setOnClickListener { adapter.selectAll() }
        btnCancel.setOnClickListener { adapter.exitMultiSelect() }
        btnBatchExport.setOnClickListener {
            ListUiHelper.batchExport(this, adapter) { adapter.exitMultiSelect() }
        }

        load()
        return v
    }

    private fun load() {
        val ctx = requireContext().applicationContext
        val act = activity as? MainActivity
        lifecycleScope.launch(Dispatchers.IO) {
            var ls: List<top.brokestar.emojiexporter.data.EpMeta>? = null
            repeat(10) { attempt ->
                ls = LsposedBridge.getInstalled(ctx)
                if (!ls.isNullOrEmpty()) return@repeat
                delay(600)
            }
            if (!ls.isNullOrEmpty()) {
                val packs = ls!!
                withContext(Dispatchers.Main) { adapter.submit(packs.map { PackItem(it.epId, it.name ?: it.epId, it.coverUrl) }) }
                return@launch
            }
            // 回退：ROOT 读 DB
            val uin = (act?.currentUin) ?: run { delay(800); (activity as? MainActivity)?.currentUin }
            if (uin == null) {
                withContext(Dispatchers.Main) { Toast.makeText(context, "未检测到账号", Toast.LENGTH_SHORT).show() }
                return@launch
            }
            try {
                val opened = QQDatabaseReader.openForUin(ctx, uin) ?: run {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "无法打开 QQ 数据库", Toast.LENGTH_SHORT).show() }
                    return@launch
                }
                val packs = top.brokestar.emojiexporter.data.EmoticonPackageDao.listInstalled(opened.db, opened.kcKey)
                QQDatabaseReader.close(opened)
                withContext(Dispatchers.Main) {
                    if (packs.isEmpty()) Toast.makeText(context, "本地数据库无表情包记录", Toast.LENGTH_SHORT).show()
                    else adapter.submit(packs.map { PackItem(it.epId, it.name ?: it.epId, it.coverUrl) })
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(context, "读取失败: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }
}
