package top.brokestar.emojiexporter.lsposed

import android.os.Bundle
import top.brokestar.emojiexporter.lsposed.http.BaseHandler
import org.json.JSONArray
import org.json.JSONObject

/**
 * 表情商城搜索（真正的"按关键词搜表情包"）。
 *
 * QQ 商城搜索走 H5 JSAPI mqq.data.ssoRequest（cmd=OidbSvcTrpcJsapiTcp.0x94c3_0，body 是 JSON）。
 * 这条流量走 QQ 的 SSO 通道（WebSSOAgent），不是传统 OIDB PB。
 *
 * 实现（不依赖 WebView，不依赖 QFix 重定向的 PB 类）：
 *  - 发送：手写 PB 编码 WebSSOAgent$UniSsoServerReq（reqdata=JSON 字符串），
 *    new NewIntent(ctx, C78297am.class) + extras(extra_cmd/extra_data) + startServlet。
 *    servlet 会自动套 4 字节长度前缀并发出。
 *  - 接收：hook MSFServlet.onReceive(FromServiceMsg)，按 cmd 过滤。但 C78297am.onReceive
 *    自己会解包并 notifyObserver —— 我们用 BusinessObserver 接收回调（接口可直接实例化）。
 *
 * 响应 JSON：{ is_end, total_size, page_session, list:[{itemid,...}], emojilist:[...] }
 * list 是"表情专辑"（包级），itemid 是 32 位 hex，封面走 gxh CDN。
 */
object MallSearchSso {
    private const val TAG = "EmojiHook/MallSearch"
    const val CMD = "OidbSvcTrpcJsapiTcp.0x94c3_0"
    // sendRequest 路径（mqq.data.ssoRequest 的真实 Java 实现）：
    // - servlet: com.tencent.biz.p（TrpcProtoServlet，jadx: C22100p）
    // - cmd 重写: MQUpdateSvc_<reversed-host>.web.<原cmd>，zb.vip.qq.com → com_qq_vip_zb
    private const val SERVLET = "com.tencent.biz.p"
    private const val HOST = "zb.vip.qq.com"
    private val REWRITTEN_CMD = "MQUpdateSvc_" + HOST.split(".").reversed().joinToString("_") + ".web." + CMD

    @Volatile private var lastResult: JSONObject? = null
    @Volatile private var lastKeyword: String = ""
    @Volatile private var lastSession: String = ""
    @Volatile private var gLogger: Any? = null

    /** 不需要 hook servlet 接收（走 observer 回调），init 仅记录 logger。 */
    fun init(logger: Any?) {
        gLogger = logger
        log(logger, false, "init ok (observer-based, no servlet hook needed)")
    }

    /** 暴露全局 logger 供外部（QqSearchHandler）触发时复用，让诊断日志进 module log。 */
    fun loggerRef(): Any? = gLogger

    /**
     * 发起商城搜索。pskey 来自 TicketManager 本地缓存（为空时也发，部分场景服务端不强校验）。
     * @return true 表示请求已投递，结果异步到达 lastResult。
     */
    fun trigger(keyword: String, pageSession: String, logger: Any?): Boolean {
        val cl = QqRuntime.classLoader ?: run { log(logger, true, "trigger: no classLoader"); return false }
        val rt = QqRuntime.appRuntime() ?: run { log(logger, true, "trigger: no appRuntime"); return false }
        return try {
            val pskey = fetchPskey(cl, rt)
            log(logger, false, "trigger pskey=${if (pskey.isNullOrEmpty()) "empty" else "len=${pskey.length}"}")

            // reqdata 结构（对齐 QQDataModule.uniAgent 的真实封装）：
            //   {data:{...业务参数, login_sig}, option:{设备信息}}
            // 服务端从 reqdata.data 读取业务字段（query_str 等）。
            // 业务 JSON（sendRequest 路径：业务参数直接放 WebSsoRequestBody.data，不套 data/option）
            val params = JSONObject()
            params.put("platformId", 3)
            params.put("query_str", keyword)
            params.put("version", "9.16.0")
            params.put("page_session", pageSession)
            params.put("not_need_emojilist", false)
            val loginSig = JSONObject()
            loginSig.put("appid", 338)
            loginSig.put("pskey", pskey ?: "")
            params.put("login_sig", loginSig)
            val jsonStr = params.toString()

            // 构造 WebSsoRequestBody PB（手写）：type=0(fixed), data=jsonStr, login_sig={type=27,sig=pskey,appid=338}
            val reqBytes = buildWebSsoRequestBody(jsonStr, pskey, 338)
            log(logger, false, "trigger reqBytes=${reqBytes.size} pskey=${if (pskey.isNullOrEmpty()) "empty" else "ok"}")

            // NewIntent(ctx, com.tencent.biz.p) + extras(cmd/data) + observer + startServlet
            val servletCls = Class.forName(SERVLET, true, cl)
            QqRuntime.obtainAppContext(cl)
            val ctx = QqRuntime.appContext
            if (ctx == null) { log(logger, true, "trigger: no appContext"); return false }
            val newIntentCls = Class.forName("mqq.app.NewIntent", true, cl)
            val ctor = newIntentCls.getDeclaredConstructor(android.content.Context::class.java, java.lang.Class::class.java)
            ctor.isAccessible = true
            val intent = ctor.newInstance(ctx, servletCls)

            // extras（sendRequest 路径 key：cmd / data，不是 extra_cmd/extra_data）
            val putExtraStr = android.content.Intent::class.java.getMethod("putExtra", String::class.java, String::class.java)
            val putExtraBytes = android.content.Intent::class.java.getMethod("putExtra", String::class.java, ByteArray::class.java)
            putExtraStr.invoke(intent, "cmd", REWRITTEN_CMD)
            putExtraBytes.invoke(intent, "data", reqBytes)

            // observer：BusinessObserver 是接口，可直接实例化。
            val obsCls = Class.forName("mqq.observer.BusinessObserver", true, cl)
            val observer = java.lang.reflect.Proxy.newProxyInstance(cl, arrayOf(obsCls)) { _, method, args ->
                if (method.name == "onReceive") try {
                    handleObserverResponse(args[0] as Int, args[1] as Boolean, args[2] as Bundle, keyword, pageSession)
                } catch (e: Throwable) { log(gLogger, true, "observer onReceive error: ${e.message}") }
                null
            }
            newIntentCls.getMethod("setObserver", obsCls).invoke(intent, observer)

            // 清旧结果，记录当前请求
            lastResult = null
            lastKeyword = keyword
            lastSession = pageSession

            // appRuntime.startServlet(intent)
            Reflect.callMethodOrNull(rt, "startServlet", intent)
            log(logger, false, "triggered mall search kw=$keyword via $SERVLET")
            true
        } catch (e: Throwable) { log(logger, true, "trigger failed: ${e.javaClass.simpleName}: ${e.message}"); false }
    }

    /** observer 回调：解 WebSsoResponseBody PB（field3=ret, field4=data JSON），写入 lastResult。 */
    private fun handleObserverResponse(code: Int, success: Boolean, bundle: Bundle, keyword: String, session: String) {
        if (!success) {
            val em = bundle.getString("data_error_msg")
            val rc = bundle.getInt("data_error_code")
            log(gLogger, true, "mall search fail rc=$rc msg=$em")
            return
        }
        val data = bundle.getByteArray("data")
        if (data == null || data.isEmpty()) { log(gLogger, true, "observer: empty data"); return }
        // WebSsoResponseBody: field3=ret(varint), field4=data(string, JSON)
        val rspdata = extractPbField(data, 4)
        if (rspdata == null || rspdata.isEmpty()) { log(gLogger, true, "observer: data field empty"); return }
        try {
            val json = JSONObject(String(rspdata, Charsets.UTF_8))
            lastResult = json
            log(gLogger, false, "captured mall search: list=${json.optJSONArray("list")?.length() ?: 0} isEnd=${json.optBoolean("is_end")}")
        } catch (e: Throwable) { log(gLogger, true, "observer json parse error: ${e.message}") }
    }

    /** 轮询等待结果。 */
    fun await(keyword: String, session: String, timeoutMs: Long = 8000): JSONObject? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (lastKeyword == keyword && lastSession == session && lastResult != null) return lastResult
            try { Thread.sleep(200) } catch (_: Throwable) {}
        }
        return null
    }

    fun consume(): JSONObject? { val r = lastResult; lastResult = null; return r }

    /**
     * 构造 WebSsoRequestBody PB（手写，对齐 SSOWebviewPlugin.sendRequest）。
     * 结构：{ type:0, data:JSON字符串, login_sig:{type:27, sig:pskey, appid:338} }
     * 字段 tag：type=field2(tag16), data=field3(tag26), login_sig=field5(tag42)
     * login_sig 内：type=field1(tag8), sig=field2(tag18), appid=field3(tag24)
     */
    private fun buildWebSsoRequestBody(jsonStr: String, pskey: String?, appid: Int): ByteArray {
        val json = jsonStr.toByteArray(Charsets.UTF_8)
        return java.io.ByteArrayOutputStream().apply {
            writeVarintField(2, 0L)                                    // type=0 (tag16)
            writeLenField(3, json)                                     // data=JSON (tag26)
            if (!pskey.isNullOrEmpty()) {
                // login_sig 嵌套 message
                val sig = java.io.ByteArrayOutputStream().apply {
                    writeVarintField(1, 27L)                          // type=27 (tag8)
                    writeLenField(2, pskey.toByteArray(Charsets.UTF_8)) // sig=pskey (tag18)
                    writeVarintField(3, appid.toLong())                // appid (tag24)
                }.toByteArray()
                writeLenField(5, sig)                                  // login_sig (tag42)
            }
        }.toByteArray()
    }

    // ===== PB 编码工具（手写，不依赖 QQ 类） =====
    private fun writeVarintBytes(v: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var x = v
        while (x ushr 7 != 0L) { out.write((x and 0x7F).toInt() or 0x80); x = x ushr 7 }
        out.write(x.toInt()); return out.toByteArray()
    }
    private fun java.io.ByteArrayOutputStream.writeVarint(v: Long) { write(writeVarintBytes(v)) }
    private fun java.io.ByteArrayOutputStream.writeLenField(fieldNo: Int, data: ByteArray) {
        write((fieldNo shl 3) or 2); writeVarint(data.size.toLong()); write(data)
    }
    private fun java.io.ByteArrayOutputStream.writeVarintField(fieldNo: Int, v: Long) {
        write((fieldNo shl 3) or 0); writeVarint(v)
    }

    /** 从 PB 提取指定 field 的 length-delimited 字节（wire type 2）。 */
    private fun extractPbField(data: ByteArray, fieldNo: Int): ByteArray? {
        var i = 0
        while (i < data.size) {
            val (tag, n1) = readVarint(data, i); i += n1
            val cur = (tag shr 3).toInt(); val wt = (tag and 7).toInt()
            if (cur == fieldNo && wt == 2) {
                val (len, n2) = readVarint(data, i); i += n2
                if (i + len > data.size) return null
                return data.copyOfRange(i, i + len.toInt())
            }
            i = skipField(data, i, wt) ?: return null
        }
        return null
    }
    /** 从 PB 提取指定 field 的 varint 值（wire type 0）。 */
    private fun extractPbVarint(data: ByteArray, fieldNo: Int): Long? {
        var i = 0
        while (i < data.size) {
            val (tag, n1) = readVarint(data, i); i += n1
            val cur = (tag shr 3).toInt(); val wt = (tag and 7).toInt()
            if (cur == fieldNo && wt == 0) { val (v, _) = readVarint(data, i); return v }
            i = skipField(data, i, wt) ?: return null
        }
        return null
    }
    private fun readVarint(data: ByteArray, off: Int): Pair<Long, Int> {
        var r = 0L; var s = 0; var p = off
        while (p < data.size) {
            val b = data[p].toInt(); r = r or ((b and 0x7F).toLong() shl s); p++
            if (b and 0x80 == 0) return r to (p - off)
            s += 7
        }
        throw IllegalStateException("varint unterminated")
    }
    private fun skipField(data: ByteArray, off: Int, wireType: Int): Int? = when (wireType) {
        0 -> { var p = off; while (p < data.size && data[p].toInt() and 0x80 != 0) p++; p + 1 }
        1 -> off + 8
        2 -> { val (len, n) = readVarint(data, off); off + n + len.toInt() }
        5 -> off + 4
        else -> null
    }

    /**
     * 取 pskey：商城搜索的 login_sig.pskey 实际是 zb.vip.qq.com 域名的 cookie 字段 p_skey
     * （HAR 直读确认）。WebView 的 cookie 存在 android.webkit.CookieManager，直接读。
     * 必须在主线程调用（CookieManager 要求），由调用方保证。
     */
    /**
     * 取 p_skey：商城搜索的 login_sig.pskey 是 zb.vip.qq.com 域名的 p_skey。
     * 来源是 TicketManager 本地缓存的 _pskey_map（用户进过表情商城就有）。
     * 优先 zb.vip.qq.com，回退 gxh.vip.qq.com。
     */
    private fun fetchPskey(cl: ClassLoader, rt: Any): String? {
        for (domain in listOf("zb.vip.qq.com", "gxh.vip.qq.com", "vip.qq.com")) {
            val pskey = readTicketPskey(cl, rt, domain)
            if (!pskey.isNullOrEmpty()) return pskey
        }
        log(gLogger, true, "fetchPskey empty (need to open emoji mall in QQ first)")
        return null
    }

    private fun readTicketPskey(cl: ClassLoader, rt: Any, domain: String): String? = try {
        val uin = Reflect.callMethodOrNull(rt, "getCurrentAccountUin") as? String
            ?: Reflect.callMethodOrNull(rt, "getAccount") as? String ?: return null
        val ticketMgr = Reflect.callMethodOrNull(rt, "getManager", 2) ?: return null
        val tmCls = Class.forName("mqq.manager.TicketManager", true, cl)
        val m = tmCls.getMethod("getPskey", String::class.java, String::class.java)
        m.invoke(ticketMgr, uin, domain) as? String
    } catch (e: Throwable) { null }

    /** 把商城 JSON 结果解析成包级 List<Map>。list 项是"表情专辑"，itemid 当 epId 用。
     *  服务端不返回封面 URL，按 itemid 拼 CDN 模板（img/parcel/{id%10}/{id}/200x200.png，与已添加列表同源，实测有效）。 */
    fun parsePacks(json: JSONObject): List<Map<String, String>> {
        val arr = json.optJSONArray("list") ?: JSONArray()
        val out = mutableListOf<Map<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val itemid = o.optString("itemid").takeIf { it.isNotBlank() }
                ?: o.optString("id").takeIf { it.isNotBlank() } ?: continue
            if (itemid.isBlank()) continue
            val name = o.optString("name").ifBlank { o.optString("title") }.ifBlank { o.optString("text") }
            val idLong = itemid.trim().toLongOrNull()
            val cover = if (idLong != null) {
                // 实测有效：gxh.vip.qq.com/club/item/parcel/img/parcel/{id%10}/{id}/200x200.png
                "https://gxh.vip.qq.com/club/item/parcel/img/parcel/${idLong % 10}/$itemid/200x200.png"
            } else ""
            val count = o.optInt("count", o.optInt("size", 0))
            out.add(linkedMapOf("epId" to itemid, "eId" to "", "name" to name, "coverUrl" to cover, "count" to count.toString()))
        }
        return out
    }

    fun lastPageSession(): String = (lastResult?.optString("page_session") ?: "").ifBlank { "" }
    fun lastIsEnd(): Boolean = lastResult?.optBoolean("is_end", true) ?: true

    private fun log(logger: Any?, warn: Boolean, msg: String) {
        try { (logger as? EmojiAppHook.Logger)?.let { if (warn) it.w(msg) else it.i(msg) } } catch (_: Throwable) {}
    }
}
