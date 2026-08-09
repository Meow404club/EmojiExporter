package top.brokestar.emojiexporter.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class EpMeta(val epId: String, val name: String?, val coverUrl: String?)

/** 第一层·搜商城：公开 HTTP（失败则降级为“榜单/已知 epId 本地过滤”） */
object SearchApi {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    /**
     * 关键词搜索。优先尝试已知公开搜索 CGI；若 404/空则返回空，由上层做本地过滤兜底。
     * 两个候选端点需真机联调确认，此处保留可插拔结构。
     */
    suspend fun search(keyword: String): List<EpMeta> = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) return@withContext emptyList()
        val kw = keyword.trim()
        // 候选1：表情中心搜索（需实测；若不存在则 404 忽略）
        val candidates = listOf(
            "https://gxh.vip.qq.com/cgi-bin/emotion/search?keyword=${enc(kw)}",
            "https://i.gtimg.cn/qqshow/admindata/comdata/search/${enc(kw)}.json",
        )
        for (url in candidates) {
            try {
                val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string() ?: return@use
                    val list = parseEpList(body)
                    if (list.isNotEmpty()) return@withContext list
                }
            } catch (_: Exception) {}
        }
        emptyList()
    }

    /** 已知热门/榜单 epId 做本地过滤兜底（搜不到时 UI 会提示“输入 epId 直达”） */
    fun knownEpIds(): List<String> = emptyList() // 首版留空，联调后填入榜单抓取的 epId 列表

    private fun parseEpList(body: String): List<EpMeta> {
        // 宽松抽取：所有 "epId":"12345" / "epid":12345
        return Regex("\"e?pid\"\\s*:\\s*\"?(\\d+)\"?", RegexOption.IGNORE_CASE)
            .findAll(body).map { EpMeta(it.groupValues[1], null, null) }.toList()
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
