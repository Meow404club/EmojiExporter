package top.brokestar.emojiexporter.lsposed.http.handlers

import android.text.TextUtils
import top.brokestar.emojiexporter.lsposed.QqRuntime
import top.brokestar.emojiexporter.lsposed.Reflect
import top.brokestar.emojiexporter.lsposed.http.BaseHandler
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class QqTicketHandler(token: String) : BaseHandler(token) {
    override fun onGet(session: IHTTPSession): NanoHTTPD.Response {
        requireHookReady()?.let { return it }
        val uri = session.uri
        val params = session.parameters
        fun qp(name: String): String? = params[name]?.firstOrNull()
        if (uri == "/qq/sign") {
            val url = qp("url") ?: return badRequest("missing url")
            return handleSign(url)
        }
        val domain = qp("domain") ?: "gxh.vip.qq.com"
        return handleTicket(domain)
    }
    private fun handleTicket(domain: String): NanoHTTPD.Response {
        val cl = QqRuntime.classLoader ?: return error("no classLoader", null)
        try {
            val uin = QqRuntime.currentUin() ?: return error("no uin (not logged in)", null)
            val pskey = getPskeySync(domain, uin, cl)
            val gtk = pskey?.let { getGTK(it) }
            val skey = tryGetSkey(uin, cl)
            val cookie = buildString { if (!pskey.isNullOrEmpty()) append("p_skey=" + pskey + "; "); if (!skey.isNullOrEmpty()) append("skey=" + skey + "; "); append("uin=o" + uin + "; p_uin=o" + uin) }
            val body = JSONObject().apply { put("uin", uin); put("domain", domain); put("pskey", pskey ?: JSONObject.NULL); put("skey", skey ?: JSONObject.NULL); if (gtk != null) put("gtk", gtk) else put("gtk", JSONObject.NULL); put("g_tk", gtk ?: JSONObject.NULL); put("cookie", cookie); put("a2", tryGetA2(uin, cl) ?: JSONObject.NULL) }.toString()
            return okRaw(body)
        } catch (e: Exception) { return error(e.message ?: "ticket error", null) }
    }
    private fun handleSign(rawUrl: String): NanoHTTPD.Response {
        QqRuntime.classLoader ?: return error("no classLoader", null)
        val domain = try { java.net.URL(rawUrl).host ?: "gxh.vip.qq.com" } catch (_: Throwable) { "gxh.vip.qq.com" }
        val uin = QqRuntime.currentUin() ?: return error("no uin", null)
        val pskey = getPskeySync(domain, uin, QqRuntime.classLoader!!)
        val gtk = pskey?.let { getGTK(it) }
        val signedUrl = if (gtk != null) { if (rawUrl.contains("?")) rawUrl + "&g_tk=" + gtk else rawUrl + "?g_tk=" + gtk } else rawUrl
        val skey = tryGetSkey(uin, QqRuntime.classLoader!!)
        val cookie = buildString { if (!pskey.isNullOrEmpty()) append("p_skey=" + pskey + "; "); if (!skey.isNullOrEmpty()) append("skey=" + skey + "; "); append("uin=o" + uin + "; p_uin=o" + uin) }
        val body = JSONObject().apply { put("url", rawUrl); put("signedUrl", signedUrl); put("domain", domain); put("gtk", gtk ?: JSONObject.NULL); put("g_tk", gtk ?: JSONObject.NULL); put("cookie", cookie); put("pskey", pskey ?: JSONObject.NULL) }.toString()
        return okRaw(body)
    }

    private fun getPskeySync(domain: String, uin: String, cl: ClassLoader): String? {
        try {
            val inst = QqRuntime.appRuntime() ?: return getPskeyViaTicketManager(domain, uin, cl)
            val pskeyMgrClass = Reflect.findClassOrNull("com.tencent.mobileqq.pskey.api.IPskeyManager", cl)
            val svc = if (pskeyMgrClass != null) Reflect.callMethodOrNull(inst, "getRuntimeService", pskeyMgrClass, "all") else null
            if (svc != null) {
                val latch = CountDownLatch(1)
                var out: String? = null
                val cbClass = Reflect.findClassOrNull("mqq.manager.TicketManager\$IPskeyManager", cl)
                if (cbClass != null) {
                    try {
                        val m = svc.javaClass.methods.firstOrNull { it.name == "getPskey" && it.parameterCount == 2 }
                        if (m != null) {
                            val domains = arrayOf(domain)
                            val cb = java.lang.reflect.Proxy.newProxyInstance(cl, arrayOf(cbClass)) { _, method, args ->
                                if (method.name == "onSuccess" || method.name == "onGetPskeySuccess") {
                                    val map = args?.getOrNull(0) as? Map<*, *>
                                    out = map?.get(domain) as? String
                                    latch.countDown()
                                } else if (method.name == "onFailed" || method.name == "onError") {
                                    latch.countDown()
                                }
                                null
                            }
                            m.invoke(svc, domains, cb)
                            latch.await(2000, TimeUnit.MILLISECONDS)
                            if (!out.isNullOrEmpty()) return out
                        }
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
        return getPskeyViaTicketManager(domain, uin, cl)
    }

    private fun getPskeyViaTicketManager(domain: String, uin: String, cl: ClassLoader): String? {
        return try {
            val tmClass = Reflect.findClass("mqq.manager.TicketManager", cl)
            val rt = QqRuntime.appRuntime() ?: return null
            val tm = Reflect.callMethodOrNull(rt, "getManager", tmClass) ?: Reflect.callMethodOrNull(rt, "getManager", 100) ?: return null
            Reflect.callMethodOrNull(tm, "getPskey", uin, domain) as? String
                ?: Reflect.callMethodOrNull(tm, "getPskey", uin, 16L, arrayOf(domain), null) as? String
        } catch (_: Throwable) { null }
    }

    private fun tryGetSkey(uin: String, cl: ClassLoader): String? {
        return try {
            val rt = QqRuntime.appRuntime() ?: return null
            val tmClass = Reflect.findClass("mqq.manager.TicketManager", cl)
            val tm = Reflect.callMethodOrNull(rt, "getManager", tmClass) ?: return null
            Reflect.callMethodOrNull(tm, "getSkey", uin) as? String ?: Reflect.callMethodOrNull(tm, "getRealSkey", uin) as? String
        } catch (_: Throwable) { null }
    }

    private fun tryGetA2(uin: String, cl: ClassLoader): String? {
        return try {
            val rt = QqRuntime.appRuntime() ?: return null
            val tmClass = Reflect.findClass("mqq.manager.TicketManager", cl)
            val tm = Reflect.callMethodOrNull(rt, "getManager", tmClass) ?: return null
            Reflect.callMethodOrNull(tm, "getA2", uin) as? String
        } catch (_: Throwable) { null }
    }

    private fun getGTK(pskey: String): Int { if (TextUtils.isEmpty(pskey)) return 5381; var h = 5381; for (i in pskey.indices) h += (h shl 5) + pskey[i].code; return Int.MAX_VALUE and h }
}
