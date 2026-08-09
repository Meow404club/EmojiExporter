package top.brokestar.emojiexporter.export

import android.content.Context
import android.os.Environment
import top.brokestar.emojiexporter.data.CdnDownloader
import top.brokestar.emojiexporter.data.EmotionsmFileStore
import top.brokestar.emojiexporter.data.QqMallApi
import top.brokestar.emojiexporter.data.SecurityUtileCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class ExportProgress(val epId: String, val done: Int, val total: Int, val msg: String)

class ExportManager(private val ctx: Context) {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    /** 导出整包：清单走 hook(QQ内部)→CDN；单张走 hook(QQ下载解密)→CDN→本地XOR。并发下载。 */
    suspend fun exportPackage(epId: String, name: String?, onProgress: (ExportProgress) -> Unit): File {
        val outDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "EmojiExporter/${epId}${name?.let { "-$it" } ?: ""}"
        )
        outDir.mkdirs()

        val items = withContext(Dispatchers.IO) { loadEIds(epId) }
        if (items.isEmpty()) throw IllegalStateException("未找到 $epId 的表情清单（QQ 内部与 CDN 均为空）")

        var done = 0
        withContext(Dispatchers.IO) {
            items.chunked(4).forEach { chunk ->
                chunk.map { item ->
                    async {
                        // 文件名优先用表情名，清理非法字符；无名则用 eId
                        val safeName = item.name?.takeIf { it.isNotBlank() }
                            ?.replace(Regex("[\\\\/:*?\"<>|]"), "_")?.trim()
                        val dst = File(outDir, "${safeName ?: item.eId}.gif")
                        // 重名时加序号
                        val finalDst = if (dst.exists()) File(outDir, "${safeName ?: item.eId}_${item.eId.take(6)}.gif") else dst
                        val ok = downloadOne(epId, item.eId, finalDst)
                        synchronized(this@ExportManager) {
                            done++
                            onProgress(ExportProgress(epId, done, items.size, if (ok) "${item.name ?: item.eId} ok" else "${item.name ?: item.eId} 失败"))
                        }
                    }
                }.awaitAll()
            }
        }
        File(outDir, "manifest.json").writeText(
            """{"epId":"$epId","name":${name?.let { "\"$it\"" } ?: "null"},"count":${items.size},"items":${items.joinToString(",", "[", "]") { "{\"eId\":\"${it.eId}\",\"name\":\"${it.name ?: ""}\"}" }}}"""
        )
        return outDir
    }

    private data class EmojiEntry(val eId: String, val name: String?)

    /** eId 清单（带名）：优先 QQ 内部（hook），fallback CDN xydata，再 fallback 本地 .jtmp。 */
    private suspend fun loadEIds(epId: String): List<EmojiEntry> {
        // 1) hook：QQ 内部 syncGetSubEmoticonsByPackageId（覆盖专属包）
        val hookItems = QqMallApi.fetchItems(ctx, epId)
        if (!hookItems.isNullOrEmpty()) return hookItems.map { EmojiEntry(it.eId, it.name) }
        // 2) CDN xydata（无名）
        val cdn = CdnDownloader.fetchXydata(epId)
        if (cdn.isNotEmpty()) return cdn.map { EmojiEntry(it, null) }
        // 3) 本地 .jtmp（无名）
        val root = EmotionsmFileStore.resolveRoot()
        if (root != null) {
            val jtmp = File("$root/$epId/$epId.jtmp")
            if (jtmp.exists()) {
                val ids = Regex("\"eId\"\\s*:\\s*\"(\\d+)\"").findAll(jtmp.readText()).map { it.groupValues[1] }.toList()
                if (ids.isNotEmpty()) return ids.map { EmojiEntry(it, null) }
            }
        }
        return emptyList()
    }

    /** 单张下载：hook(QQ下载解密) → CDN → 本地 XOR。 */
    private suspend fun downloadOne(epId: String, eId: String, dst: File): Boolean {
        // 1) hook image 端点（QQ 内部下载 + 解密，覆盖最广）
        if (downloadHookImage(epId, eId, dst)) return true
        // 2) CDN 公开直链
        if (CdnDownloader.downloadBest(epId, eId, dst)) return true
        // 3) 本地 .emotionsm XOR 还原
        if (EmotionsmFileStore.exists(epId, eId) && EmotionsmFileStore.exportOne(epId, eId, dst)) return true
        return false
    }

    /** 从 hook /qq/emoticon/image 下载单张（QQ 内部下载+解密，返回明文图片）。 */
    private fun downloadHookImage(epId: String, eId: String, dst: File): Boolean = try {
        val url = QqMallApi.imageUrl(ctx, epId, eId, "big")
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return false
            val bytes = resp.body?.bytes() ?: return false
            if (bytes.isEmpty()) return false
            dst.parentFile?.mkdirs(); dst.writeBytes(bytes); true
        }
    } catch (_: Exception) { false }
}
