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
import androidx.recyclerview.widget.RecyclerView
import top.brokestar.emojiexporter.R
import top.brokestar.emojiexporter.data.CoverUrl
import top.brokestar.emojiexporter.data.LsposedBridge
import top.brokestar.emojiexporter.data.QqMallApi
import top.brokestar.emojiexporter.data.SearchApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 搜索商城：真正的"按关键词搜表情包"。
 * - 主路径：商城 SSO 搜索（OIDB 0x94c3_0，返回表情专辑/包级结果，带翻页 page_session）
 * - 回退：HotPic 单表情搜索（按 packageID 聚合）
 * - 再回退：公开 HTTP API
 * 支持滚动到底自动加载下一页（基于 page_session 游标）。
 */
class SearchFragment : Fragment() {
    private lateinit var adapter: PackAdapter
    private lateinit var rv: RecyclerView
    private var keyword: String = ""
    private var pageSession: String = ""     // 当前翻页游标
    private var isLoadingMore = false
    private var isEnd = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_list, container, false)
        rv = v.findViewById(R.id.rv)
        val et = v.findViewById<android.widget.EditText>(R.id.etKeyword)
        val btnGo = v.findViewById<View>(R.id.btnGo)

        // 批量栏
        val batchBar = v.findViewById<View>(R.id.batchBar)
        val tvSelected = v.findViewById<android.widget.TextView>(R.id.tvSelected)
        val btnSelectAll = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSelectAll)
        val btnBatchExport = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnBatchExport)
        val btnCancel = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancel)

        adapter = PackAdapter(
            onExport = { pack -> ListUiHelper.exportOne(this, pack) },
            onOpenDetail = { pack -> ListUiHelper.openDetail(this, pack) },
            onSelectionChanged = { selected ->
                if (selected.isNotEmpty()) { batchBar.visibility = View.VISIBLE; tvSelected.text = "已选 ${selected.size} 个" }
                else batchBar.visibility = View.GONE
            },
        )
        rv.layoutManager = LinearLayoutManager(context)
        rv.adapter = adapter
        rv.setHasFixedSize(true)
        rv.setItemViewCacheSize(8)
        (rv.itemAnimator as? androidx.recyclerview.widget.DefaultItemAnimator)?.let {
            it.addDuration = 0; it.changeDuration = 0; it.moveDuration = 0; it.removeDuration = 0
        }
        // 滚动到底自动加载下一页
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || isLoadingMore || isEnd || keyword.isEmpty()) return
                val lm = rv.layoutManager as LinearLayoutManager
                if (lm.findLastVisibleItemPosition() >= adapter.itemCount - 3) loadMore()
            }
        })
        ViewCompat.setOnApplyWindowInsetsListener(rv) { view, insets ->
            view.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
            insets
        }
        btnSelectAll.setOnClickListener { adapter.selectAll() }
        btnCancel.setOnClickListener { adapter.exitMultiSelect() }
        btnBatchExport.setOnClickListener { ListUiHelper.batchExport(this, adapter) { adapter.exitMultiSelect() } }

        btnGo.setOnClickListener {
            val kw = et.text.toString().trim()
            if (kw.isEmpty()) { Toast.makeText(context, "请输入 epId 或关键词", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (kw.all { it.isDigit() }) {
                // epId 直达：封面走 CDN 兜底
                pageSession = ""; isEnd = true; keyword = kw
                adapter.submit(listOf(PackItem(kw, "epId: $kw", CoverUrl.cdn(kw))))
                return@setOnClickListener
            }
            // 新搜索：重置游标
            keyword = kw; pageSession = ""; isEnd = false
            searchFirst(kw)
        }
        return v
    }

    private fun searchFirst(kw: String) {
        btnGoEnabled(false)
        lifecycleScope.launch(Dispatchers.IO) {
            val ctx = requireContext().applicationContext
            // 主路径：商城 SSO 搜索（带翻页）
            var page = QqMallApi.fetchSearchHttp(ctx, kw, "")
            if (page == null && LsposedBridge.triggerSearch(ctx, kw)) {
                repeat(4) { delay(800); val polled = LsposedBridge.getLastSearchForKeyword(ctx, kw); if (!polled.isNullOrEmpty()) { page = QqMallApi.SearchPage(polled, "", true, "ipc"); return@repeat } }
                val fallback = LsposedBridge.getLastSearch(ctx)
                if (!fallback.isNullOrEmpty()) page = QqMallApi.SearchPage(fallback, "", true, "ipc")
            }
            // 再回退：公开 API
            if (page == null) page = SearchApi.search(kw).let { if (it.isNotEmpty()) QqMallApi.SearchPage(it, "", true, "public") else null }

            val p = page
            withContext(Dispatchers.Main) {
                btnGoEnabled(true)
                if (p == null || p.items.isEmpty()) {
                    isEnd = true
                    Toast.makeText(requireContext(), "未搜到结果（可直接输入 epId 直达）", Toast.LENGTH_LONG).show()
                } else {
                    pageSession = p.nextPageSession; isEnd = p.isEnd || p.nextPageSession.isBlank()
                    adapter.submit(p.items.map {
                        PackItem(it.epId, it.name?.takeIf { n -> n.isNotBlank() } ?: "epId: ${it.epId}", it.coverUrl ?: CoverUrl.cdn(it.epId), null)
                    })
                }
            }
        }
    }

    private fun loadMore() {
        if (pageSession.isBlank() || isEnd) return
        isLoadingMore = true
        adapter.appendLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            val ctx = requireContext().applicationContext
            val page = QqMallApi.fetchSearchHttp(ctx, keyword, pageSession)
            withContext(Dispatchers.Main) {
                adapter.removeLoading()
                isLoadingMore = false
                if (page != null && page.items.isNotEmpty()) {
                    pageSession = page.nextPageSession
                    isEnd = page.isEnd || page.nextPageSession.isBlank()
                    adapter.append(page.items.map {
                        PackItem(it.epId, it.name?.takeIf { n -> n.isNotBlank() } ?: "epId: ${it.epId}", it.coverUrl ?: CoverUrl.cdn(it.epId), null)
                    })
                } else {
                    isEnd = true
                    if (page == null) Toast.makeText(context, "加载更多失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun btnGoEnabled(enabled: Boolean) {
        view?.findViewById<View>(R.id.btnGo)?.isEnabled = enabled
    }
}
