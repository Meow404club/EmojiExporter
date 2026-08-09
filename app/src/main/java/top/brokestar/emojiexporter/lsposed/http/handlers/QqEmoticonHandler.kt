package top.brokestar.emojiexporter.lsposed.http.handlers

import top.brokestar.emojiexporter.lsposed.HookFile
import top.brokestar.emojiexporter.lsposed.QqRuntime
import top.brokestar.emojiexporter.lsposed.Reflect
import top.brokestar.emojiexporter.lsposed.http.BaseHandler
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import org.json.JSONArray
import org.json.JSONObject

class QqEmoticonHandler(token: String) : BaseHandler(token) {
    override fun onGet(session: IHTTPSession): NanoHTTPD.Response {
        requireHookReady()?.let { return it }
        val params = session.parameters
        fun qp(name: String): String? = params[name]?.firstOrNull()
        val business = qp("business")?.toIntOrNull() ?: 0
        val force = qp("forceRefresh") == "1" || qp("force") == "1"
        // 1) active fetch via RuntimeService
        val packs = fetchTabs(business, force)
        val arr = JSONArray()
        packs.forEach { m -> arr.put(JSONObject().apply { put("epId", m["epId"]); put("name", m["name"] ?: ""); put("coverUrl", m["coverUrl"] ?: "") }) }
        // persist mirror for Shell Cat fallback
        try { HookFile.writeJson(HookFile.NAME_INSTALLED, arr.toString(), null) } catch (_: Throwable) {}
        val body = JSONObject().apply { put("business", business); put("count", arr.length()); put("packs", arr); if (packs.isEmpty()) put("hint", "empty: tabCache cold, forceRefresh=1 triggers BQMallSvc subCmd2") }.toString()
        return okRaw(body)
    }
    private fun fetchTabs(business: Int, force: Boolean): List<Map<String, String>> {
        // Try direct RuntimeService sync first (fast path, no network)
        val direct = fetchViaRuntimeService(business)
        if (direct.isNotEmpty() || !force) return direct
        // Force path: trigger BQMallSvc.TabOpReq subCmd2 via EmoticonHandler.mo221172c and wait a bit for callback
        try {
            QqRuntime.currentUin() ?: return direct
            val rt = QqRuntime.appRuntime() ?: return direct
            val cl = QqRuntime.classLoader ?: return direct
            val handlerClass = Reflect.findClassOrNull("com.tencent.mobileqq.app.EmoticonHandler", cl)
            if (handlerClass != null) {
                val handler = Reflect.callMethodOrNull(rt, "getBusinessHandler", handlerClass)
                if (handler != null) {
                    // mo221172c(timestamp=0, segment=0, business, fetchSeq)
                    val triggered = Reflect.callMethodOrNull(handler, "mo221172c", 0, 0, business, 0)
                        ?: Reflect.callMethodOrNull(handler, "mo219632c", 0, 0, business, 0)
                    Thread.sleep(2000)
                    val retry = fetchViaRuntimeService(business)
                    if (retry.isNotEmpty()) return retry
                }
            }
        } catch (_: Throwable) {}
        return direct
    }
    private fun fetchViaRuntimeService(business: Int): List<Map<String, String>> {
        return try {
            val cl = QqRuntime.classLoader ?: return emptyList()
            val rt = QqRuntime.appRuntime() ?: return emptyList()
            val svcClass = Class.forName("com.tencent.mobileqq.emosm.api.IEmoticonManagerService", false, cl)
            val svc = Reflect.callMethodOrNull(rt, "getRuntimeService", svcClass, "") ?: return emptyList()
            var list: List<*>? = Reflect.callMethodOrNull(svc, "syncGetTabEmoticonPackages", business) as? List<*>
            if (list == null) list = Reflect.callMethodOrNull(svc, "syncGetTabEmoticonPackages") as? List<*>
            if (list.isNullOrEmpty()) return emptyList()
            list.mapNotNull { mapPackage(it, svc) }.filter { it["epId"]!!.isNotBlank() }
        } catch (_: Throwable) { emptyList() }
    }

    /** EmoticonPackage → Map。封面优先 imageUrl；为空时按 epId 直接拼 CDN 模板（无需逐包取清单，主页一次拉完）。 */
    private fun mapPackage(pkg: Any?, svc: Any?): Map<String, String>? {
        if (pkg == null) return null
        return try {
            val epId = Reflect.getObjectField(pkg, "epId") as? String
                ?: Reflect.getObjectField(pkg, "epid") as? String
                ?: return null
            val name = Reflect.getObjectField(pkg, "name") as? String
            val imageUrl = (Reflect.getObjectField(pkg, "imageUrl") as? String)?.takeIf { it.startsWith("http") }
            // imageUrl 不可信时，直接拼 QQ 固定 CDN 封面规则（shard = epId % 10），覆盖绝大多数包
            val cover = imageUrl ?: "https://gxh.vip.qq.com/club/item/parcel/img/parcel/${shard(epId)}/$epId/200x200.png"
            mapOf("epId" to epId, "name" to (name ?: ""), "coverUrl" to cover)
        } catch (_: Throwable) { null }
    }

    private fun shard(epId: String): Long {
        val id = epId.trim().toLongOrNull() ?: return 0
        return id % 10
    }
}
