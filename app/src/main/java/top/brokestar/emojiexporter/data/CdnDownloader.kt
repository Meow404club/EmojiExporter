package top.brokestar.emojiexporter.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** 第二层：公开 CDN 直链（EmotionPanelConstans 模板，无需登录） */
object CdnDownloader {
    // EmotionPanelConstans.java:77-88
    private const val SMALL_GIF = "https://i.gtimg.cn/qqshow/admindata/comdata/vipSmallEmoji_item_[epId]/[eId].gif"
    private const val SMALL_PNG = "https://i.gtimg.cn/qqshow/admindata/comdata/vipSmallEmoji_item_[epId]/[eId].png"
    private const val PREVIEW_126 = "https://i.gtimg.cn/club/item/parcel/item/[eIdSub]/[eId]/126x126.png"
    private const val BIG_ENC = "https://i.gtimg.cn/club/item/parcel/item/[eIdSub]/[eId]/[w]_[h]"
    private const val XYDATA = "https://i.gtimg.cn/qqshow/admindata/comdata/vipSmallEmoji_item_[epId]/xydata.json"

    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    fun xydataUrl(epId: String) = XYDATA.replace("[epId]", epId)
    fun urlsFor(epId: String, eId: String, bigW: Int = 320, bigH: Int = 320): List<String> = listOf(
        SMALL_GIF.replace("[epId]", epId).replace("[eId]", eId),
        SMALL_PNG.replace("[epId]", epId).replace("[eId]", eId),
        PREVIEW_126.replace("[eIdSub]", eId.take(2)).replace("[eId]", eId),
        BIG_ENC.replace("[eIdSub]", eId.take(2)).replace("[eId]", eId).replace("[w]", bigW.toString()).replace("[h]", bigH.toString()),
    )

    suspend fun fetchXydata(epId: String): List<String> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(xydataUrl(epId)).header("User-Agent", "Mozilla/5.0").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext emptyList()
            val body = resp.body?.string() ?: return@withContext emptyList()
            // xydata.json: { "items": [...] } 或数组；做宽松解析：抽所有像 "eId":"123" 的值
            Regex("\"eId\"\\s*:\\s*\"(\\d+)\"").findAll(body).map { it.groupValues[1] }.toList().ifEmpty {
                Regex("\"id\"\\s*:\\s*\"?(\\d+)\"?").findAll(body).map { it.groupValues[1] }.toList()
            }
        }
    }

    suspend fun download(url: String, dst: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                val bytes = resp.body?.bytes() ?: return@withContext false
                if (bytes.isEmpty()) return@withContext false
                dst.parentFile?.mkdirs(); dst.writeBytes(bytes); true
            }
        } catch (_: Exception) { false }
    }

    /** 按优先级试多条 URL，首个成功即返回 */
    suspend fun downloadBest(epId: String, eId: String, dst: File): Boolean {
        for (u in urlsFor(epId, eId)) if (download(u, dst)) return true
        return false
    }
}
