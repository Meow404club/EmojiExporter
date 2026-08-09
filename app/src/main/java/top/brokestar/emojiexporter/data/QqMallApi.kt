package top.brokestar.emojiexporter.data

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object QqMallApi {
    private const val TAG = "QqMallApi"
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()

    /**
     * 读取 QQ shared_prefs 里的 emoji_rpc.xml 原文（含 token 与 port）。
     * KernelSU/Magisk 对授予 root 的 app 做 mount namespace 隔离，其 root shell 看不到其他 app 的
     * /data/data。用 nsenter -t 1 -m 进入 init(PID 1)的 mount namespace 即可访问全局文件系统；
     * nsenter 不可用时回退直接 cat（适用于未做 namespace 隔离的环境）。
     */
    private fun readRpcPrefs(): String? {
        val f = "/data/data/com.tencent.mobileqq/shared_prefs/emoji_rpc.xml"
        val out = com.topjohnwu.superuser.Shell.cmd("nsenter -t 1 -m cat '$f' 2>/dev/null").exec().out.joinToString("")
        if (out.isNotBlank()) return out
        return com.topjohnwu.superuser.Shell.cmd("cat '$f' 2>/dev/null").exec().out.joinToString("").takeIf { it.isNotBlank() }
    }

    private fun httpPort(context: Context): Int {
        val out = readRpcPrefs() ?: return 8080
        val m = Regex("http_port[^0-9]*([0-9]+)").find(out)
        return m?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 8080..8081 } ?: 8080
    }

    private fun token(context: Context): String? {
        val out = readRpcPrefs() ?: return null
        // shared_prefs is XML: <string name="auth_token">xxxx</string>
        val m = Regex("auth_token[^>]*>([^<]+)<").find(out)
        return m?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun baseUrl(context: Context): String = "http://127.0.0.1:" + httpPort(context)

    fun health(context: Context): String? {
        return try {
            val tok = token(context)
            val req = Request.Builder().url(baseUrl(context) + "/health").apply { if (!tok.isNullOrBlank()) header("X-Auth-Token", tok) }.get().build()
            val resp = client.newCall(req).execute()
            resp.body?.string()
        } catch (e: Throwable) { Log.w(TAG, "health failed", e); null }
    }

    fun fetchTabsHttp(context: Context, business: Int = 0, force: Boolean = false): List<EpMeta>? {
        return try {
            val tok = token(context)
            var url = baseUrl(context) + "/qq/emoticon/tabs?business=" + business
            if (force) url += "&forceRefresh=1"
            val req = Request.Builder().url(url).apply { if (!tok.isNullOrBlank()) header("X-Auth-Token", tok) }.get().build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return null
            if (!resp.isSuccessful) { Log.w(TAG, "tabs http " + resp.code + " " + body); return null }
            val jo = JSONObject(body)
            val arr = jo.optJSONArray("packs") ?: return null
            val out = mutableListOf<EpMeta>()
            for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); val epId = o.optString("epId"); if (epId.isNotBlank()) out.add(EpMeta(epId, o.optString("name").takeIf { it.isNotBlank() }, o.optString("coverUrl").takeIf { it.isNotBlank() })) }
            if (out.isEmpty()) null else out
        } catch (e: Throwable) { Log.w(TAG, "fetchTabsHttp failed", e); null }
    }

    fun fetchSearchHttp(context: Context, kw: String, pageSession: String = ""): SearchPage? {
        return try {
            val tok = token(context)
            var url = baseUrl(context) + "/qq/emoticon/search?kw=" + URLEncoder.encode(kw, "UTF-8")
            if (pageSession.isNotBlank()) url += "&page_session=" + URLEncoder.encode(pageSession, "UTF-8")
            val req = Request.Builder().url(url).apply { if (!tok.isNullOrBlank()) header("X-Auth-Token", tok) }.get().build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return null
            if (!resp.isSuccessful) { Log.w(TAG, "search http " + resp.code + " " + body); return null }
            val jo = JSONObject(body)
            val arr = jo.optJSONArray("items") ?: return null
            val out = mutableListOf<EpMeta>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i); val epId = o.optString("epId")
                if (epId.isNotBlank()) {
                    // coverUrl 严格只放真实封面 URL（Coil 能加载的 http URL），eId 不混入
                    val cover = o.optString("coverUrl").takeIf { it.startsWith("http") }
                    out.add(EpMeta(epId, o.optString("name").takeIf { it.isNotBlank() }, cover))
                }
            }
            if (out.isEmpty()) null else SearchPage(out, jo.optString("page_session"), jo.optBoolean("is_end", true), jo.optString("source"))
        } catch (e: Throwable) { Log.w(TAG, "fetchSearchHttp failed", e); null }
    }

    /** 搜索结果页（带翻页游标与是否到底）。 */
    data class SearchPage(val items: List<EpMeta>, val nextPageSession: String, val isEnd: Boolean, val source: String)

    /** 单个表情项（来自 /qq/emoticon/items）。 */
    data class EmojiItem(val eId: String, val name: String?, val width: Int, val height: Int, val isAPNG: Boolean)

    /** 获取某表情包的完整表情清单（走 QQ 内部 syncGetSubEmoticonsByPackageId，覆盖 CDN 没有的专属包）。 */
    fun fetchItems(context: Context, epId: String): List<EmojiItem>? {
        return try {
            val tok = token(context)
            val url = baseUrl(context) + "/qq/emoticon/items?epId=" + URLEncoder.encode(epId, "UTF-8")
            val req = Request.Builder().url(url).apply { if (!tok.isNullOrBlank()) header("X-Auth-Token", tok) }.get().build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return null
            if (!resp.isSuccessful) { Log.w(TAG, "items http ${resp.code} $body"); return null }
            val jo = JSONObject(body)
            val arr = jo.optJSONArray("items") ?: return emptyList()
            val out = mutableListOf<EmojiItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val eId = o.optString("eId")
                if (eId.isNotBlank()) out.add(EmojiItem(eId, o.optString("name").takeIf { it.isNotBlank() }, o.optInt("width"), o.optInt("height"), o.optBoolean("isAPNG")))
            }
            out
        } catch (e: Throwable) { Log.w(TAG, "fetchItems failed", e); null }
    }

    /** 拼接单张表情图片的 hook URL（QQ 内部下载+解密）。type: aio/thu/big。 */
    fun imageUrl(context: Context, epId: String, eId: String, type: String = "aio"): String {
        return baseUrl(context) + "/qq/emoticon/image?epId=" + URLEncoder.encode(epId, "UTF-8") +
            "&eId=" + URLEncoder.encode(eId, "UTF-8") + "&type=" + type +
            (token(context)?.let { "&_t=$it" } ?: "")
    }

    /** 把 hook 返回的相对路径(如 /qq/emoticon/image?...) 补全为带 host+token 的绝对 URL。 */
    fun absUrl(context: Context, relativePath: String): String {
        val t = token(context) ?: ""
        val sep = if (relativePath.contains("?")) "&" else "?"
        return baseUrl(context) + relativePath + (if (t.isNotBlank()) "${sep}_t=$t" else "")
    }

    fun getTicket(context: Context, domain: String = "gxh.vip.qq.com"): JSONObject? {
        return try {
            val tok = token(context)
            val url = baseUrl(context) + "/qq/ticket?domain=" + URLEncoder.encode(domain, "UTF-8")
            val req = Request.Builder().url(url).apply { if (!tok.isNullOrBlank()) header("X-Auth-Token", tok) }.get().build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return null
            if (!resp.isSuccessful) { Log.w(TAG, "ticket http " + resp.code + " " + body); return null }
            JSONObject(body)
        } catch (e: Throwable) { Log.w(TAG, "getTicket failed", e); null }
    }
}
