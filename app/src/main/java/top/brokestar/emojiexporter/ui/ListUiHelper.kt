package top.brokestar.emojiexporter.ui

import android.content.Intent
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import top.brokestar.emojiexporter.export.ExportManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 列表 Fragment 共用工具：详情页跳转、单包导出、批量导出。
 */
object ListUiHelper {

    /** 进入表情包详情页。 */
    fun openDetail(frag: Fragment, item: PackItem) {
        val ctx = frag.context ?: return
        ctx.startActivity(Intent(ctx, PackDetailActivity::class.java).apply {
            putExtra("epId", item.epId)
            putExtra("name", item.title)
            putExtra("coverUrl", item.coverUrl)
        })
    }

    /** 导出单个表情包。 */
    fun exportOne(frag: Fragment, item: PackItem, onResult: (File) -> Unit = {}) {
        val ctx = frag.requireContext()
        frag.lifecycleScope.launch {
            try {
                val dir = ExportManager(ctx).exportPackage(item.epId, item.title) { }
                Toast.makeText(ctx, "已导出到 ${dir.absolutePath}", Toast.LENGTH_LONG).show()
                onResult(dir)
            } catch (e: Throwable) {
                Toast.makeText(ctx, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 批量导出所有选中项，完成后调 onDone。 */
    fun batchExport(frag: Fragment, adapter: PackAdapter, onDone: () -> Unit) {
        val packs = adapter.selectedPacks()
        if (packs.isEmpty()) return
        val ctx = frag.requireContext()
        frag.lifecycleScope.launch {
            var ok = 0
            for (p in packs) {
                try {
                    withContext(Dispatchers.IO) { ExportManager(ctx).exportPackage(p.epId, p.title) { } }
                    ok++
                } catch (_: Throwable) {}
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(ctx, "批量导出完成 $ok/${packs.size}", Toast.LENGTH_LONG).show()
                onDone()
            }
        }
    }
}
