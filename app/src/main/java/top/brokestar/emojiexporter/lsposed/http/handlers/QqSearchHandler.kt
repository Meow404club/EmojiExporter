package top.brokestar.emojiexporter.lsposed.http.handlers

import top.brokestar.emojiexporter.lsposed.HookFile
import top.brokestar.emojiexporter.lsposed.MallSearchSso
import top.brokestar.emojiexporter.lsposed.http.BaseHandler
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import org.json.JSONArray
import org.json.JSONObject

/**
 * 表情商城搜索：走 MallSearchSso（WebSSOAgent uniAgent，cmd=OidbSvcTrpcJsapiTcp.0x94c3_0，
 * 与 QQ H5 商城完全等价的搜索通道，返回"表情专辑"包级结果）。
 *
 * 翻页：基于响应的 page_session 游标，客户端传 page_session=上一页返回值拉下一页。
 */
class QqSearchHandler(token: String) : BaseHandler(token) {
    override fun onGet(session: IHTTPSession): NanoHTTPD.Response {
        requireHookReady()?.let { return it }
        val qp = session.parameters
        fun q(name: String): String? = qp[name]?.firstOrNull()
        val kw = q("kw") ?: q("keyword") ?: q("q") ?: return badRequest("missing kw/keyword")
        val sessionCursor = q("page_session") ?: ""

        val packs = searchViaMall(kw, sessionCursor)
        if (packs != null) {
            val arr = JSONArray()
            packs.forEach { m ->
                arr.put(JSONObject().apply {
                    put("epId", m["epId"]); put("eId", m["eId"] ?: "")
                    put("name", m["name"] ?: ""); put("coverUrl", m["coverUrl"] ?: "")
                    put("count", (m["count"] ?: "0").toIntOrNull() ?: 0)
                })
            }
            try { HookFile.writeJson(HookFile.NAME_LAST_SEARCH, JSONObject().apply { put("keyword", kw); put("items", arr) }.toString(), null) } catch (_: Throwable) {}
            val body = JSONObject().apply {
                put("keyword", kw); put("count", arr.length()); put("items", arr)
                put("source", "mall"); put("page_session", MallSearchSso.lastPageSession())
                put("is_end", MallSearchSso.lastIsEnd())
            }.toString()
            return okRaw(body)
        }
        val body = JSONObject().apply { put("keyword", kw); put("count", 0); put("items", JSONArray()); put("source", "none"); put("hint", "搜索服务不可用，可直接输入 epId 直达") }.toString()
        return okRaw(body)
    }

    private fun searchViaMall(kw: String, sessionCursor: String): List<Map<String, String>>? {
        return try {
            // 传 logger（MallSearchSso 的 gLogger）让诊断日志进 module log
            val triggered = MallSearchSso.trigger(kw, sessionCursor, MallSearchSso.loggerRef())
            if (!triggered) return null
            val json = MallSearchSso.await(kw, sessionCursor, 9000) ?: return null
            MallSearchSso.parsePacks(json)
        } catch (e: Throwable) {
            android.util.Log.e("QqSearchHandler", "searchViaMall error", e)
            null
        }
    }
}
