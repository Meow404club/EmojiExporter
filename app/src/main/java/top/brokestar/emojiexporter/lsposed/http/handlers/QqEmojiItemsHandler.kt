package top.brokestar.emojiexporter.lsposed.http.handlers

import android.util.Log
import top.brokestar.emojiexporter.lsposed.QqRuntime
import top.brokestar.emojiexporter.lsposed.Reflect
import top.brokestar.emojiexporter.lsposed.http.BaseHandler
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import org.json.JSONArray
import org.json.JSONObject

/**
 * 通过 QQ 内部能力获取表情清单与图片（覆盖 CDN 没有的 QQ 专属包，如"叶洛洛"）。
 *
 * - GET /qq/emoticon/items?epId=X
 *   返回该包的表情清单 [{eId, name, width, height, isAPNG}]。
 *   内部走 IEmoticonManagerService.syncGetSubEmoticonsByPackageId(epId)（本地 DB 查询，同步）。
 *
 * - GET /qq/emoticon/image?epId=X&eId=Y[&type=aio|thu|big]
 *   返回单张表情图片字节（PNG/GIF）。内部走：
 *   1) 若本地加密文件已存在 → 直接解密返回（C61409e.m232350q）
 *   2) 否则触发 IEmojiManagerService.downloadAIOEmoticon 下载，轮询等待，再解密返回
 */
class QqEmojiItemsHandler(token: String) : BaseHandler(token) {
    private companion object { const val TAG = "EmojiItemsHandler" }

    override fun onGet(session: IHTTPSession): NanoHTTPD.Response {
        requireHookReady()?.let { return it }
        val uri = session.uri
        val epId = session.parameters["epId"]?.firstOrNull()
        if (epId.isNullOrBlank()) return badRequest("missing epId")
        return when (uri) {
            "/qq/emoticon/items" -> handleItems(epId)
            "/qq/emoticon/image" -> {
                val eId = session.parameters["eId"]?.firstOrNull()
                if (eId.isNullOrBlank()) return badRequest("missing eId")
                handleImage(epId, eId, session.parameters["type"]?.firstOrNull() ?: "big")
            }
            else -> notFound(uri)
        }
    }

    /**
     * 返回包内表情清单。清单不在本地时，触发整包下载并阻塞轮询（最长 15s），
     * 命中即返回；超时返回提示，由调用方（ExportManager）走 CDN/本地兜底。
     */
    private fun handleItems(epId: String): NanoHTTPD.Response {
        val cl = QqRuntime.classLoader ?: return error("no classLoader", null)
        return try {
            val rt = QqRuntime.appRuntime() ?: return error("no appRuntime", null)
            val svcClass = Class.forName("com.tencent.mobileqq.emosm.api.IEmoticonManagerService", false, cl)
            val svc = Reflect.callMethodOrNull(rt, "getRuntimeService", svcClass, "") ?: return error("no emoticon service", null)
            // syncGetSubEmoticonsByPackageId(epId) → List<Emoticon>（本地 DB 查询，同步）
            var list = Reflect.callMethodOrNull(svc, "syncGetSubEmoticonsByPackageId", epId) as? List<*>
            if (list.isNullOrEmpty()) {
                // 本地无清单：触发整包下载（含未添加包），然后阻塞轮询等待清单入库
                Log.i(TAG, "handleItems: local list empty, triggering download epId=$epId")
                tryTriggerDownload(rt, cl, epId)
                val deadline = System.currentTimeMillis() + 15000
                while (System.currentTimeMillis() < deadline && (list == null || list.isEmpty())) {
                    Thread.sleep(500)
                    list = Reflect.callMethodOrNull(svc, "syncGetSubEmoticonsByPackageId", epId) as? List<*>
                }
                if (list.isNullOrEmpty()) {
                    Log.w(TAG, "handleItems: still empty after 15s poll epId=$epId")
                    val body = JSONObject().apply {
                        put("epId", epId); put("count", 0); put("items", JSONArray())
                        put("hint", "本地无清单，已触发 QQ 同步，请稍后重试")
                    }.toString()
                    return okRaw(body)
                }
                Log.i(TAG, "handleItems: list arrived after poll epId=$epId count=${list.size}")
            }
            buildItemsResponse(epId, list)
        } catch (e: Throwable) {
            Log.w(TAG, "handleItems error epId=$epId", e); error("items error: ${e.message}", null)
        }
    }

    /** 把 List<Emoticon> 映射成标准 JSON 响应。 */
    private fun buildItemsResponse(epId: String, list: List<*>): NanoHTTPD.Response {
        val arr = JSONArray()
        for (e in list) {
            if (e == null) continue
            val o = JSONObject()
            o.put("eId", Reflect.getObjectField(e, "eId") ?: "")
            o.put("name", Reflect.getObjectField(e, "name") ?: JSONObject.NULL)
            o.put("width", Reflect.getObjectField(e, "width") ?: 0)
            o.put("height", Reflect.getObjectField(e, "height") ?: 0)
            o.put("isAPNG", Reflect.getObjectField(e, "isAPNG") ?: false)
            arr.put(o)
        }
        val body = JSONObject().apply { put("epId", epId); put("count", arr.length()); put("items", arr) }.toString()
        return okRaw(body)
    }

    /**
     * 触发整包下载：三层尝试。
     *
     * Stage 0（新增，主路径）：com.tencent.mobileqq.emoticon.api.IEmojiManagerService.startDownloadEmoji(Bundle)
     *   —— JSAPI mqq.invoke("emoji","startDownloadEmoji") 的底层实现。对未添加包会自构 EmoticonPackage
     *      并调 pullEmoticonPackage 真正下载表情文件到本地（不污染账号收藏）。
     *   Bundle key（反编 EmojiManagerServiceImpl 确认）：id(int)=epId, businessType(int)=0, sceneType(int)=0
     *
     * Stage 1（兜底）：qqnt.emotion.IEmojiManagerService.startDownloadEmosmJson(epId, 0) —— 仅拉清单 JSON
     * Stage 2（兜底）：IEmoticonManagerService.syncEmoticonPackageById(epId) —— 触发入库
     */
    private fun tryTriggerDownload(rt: Any, cl: ClassLoader, epId: String) {
        // Stage 0：整包下载（对未添加包也生效）
        triggerStartDownloadEmoji(rt, cl, epId)
        // Stage 1：拉清单 JSON（兜底，即使 Stage 0 成功也无害）
        try {
            val emojiCls = Class.forName("com.tencent.qqnt.emotion.api.IEmojiManagerService", false, cl)
            val emojiSvc = Reflect.callMethodOrNull(rt, "getRuntimeService", emojiCls)
            if (emojiSvc != null) {
                Reflect.callMethodOrNull(emojiSvc, "startDownloadEmosmJson", epId, 0)
                Log.i(TAG, "triggered startDownloadEmosmJson epId=$epId")
            }
        } catch (_: Throwable) {}
        // Stage 2：触发服务端拉取入库
        try {
            val svcClass = Class.forName("com.tencent.mobileqq.emosm.api.IEmoticonManagerService", false, cl)
            val svc = Reflect.callMethodOrNull(rt, "getRuntimeService", svcClass, "")
            Reflect.callMethodOrNull(svc, "syncEmoticonPackageById", epId)
            Log.i(TAG, "triggered syncEmoticonPackageById epId=$epId")
        } catch (_: Throwable) {}
    }

    /**
     * 调 IEmojiManagerService.startDownloadEmoji(Bundle) 触发整包下载（含未添加包）。
     * 这是 H5 商城点"下载"按钮走的原生路径：对未添加包会 new EmoticonPackage + pullEmoticonPackage。
     * 不需要先 bq_add，不污染账号收藏。
     */
    private fun triggerStartDownloadEmoji(rt: Any, cl: ClassLoader, epId: String): Boolean {
        return try {
            val epIdInt = epId.trim().toIntOrNull() ?: run {
                Log.w(TAG, "triggerStartDownloadEmoji: epId not int: $epId"); return false
            }
            val cls = Class.forName("com.tencent.mobileqq.emoticon.api.IEmojiManagerService", false, cl)
            // getRuntimeService 有 1-arg 和 2-arg 重载，先试 2-arg（带 tag，与 emosm 一致），回退 1-arg
            val svc = Reflect.callMethodOrNull(rt, "getRuntimeService", cls, "")
                      ?: Reflect.callMethodOrNull(rt, "getRuntimeService", cls) ?: run {
                Log.w(TAG, "triggerStartDownloadEmoji: no IEmojiManagerService runtime service"); return false
            }
            val bundle = android.os.Bundle().apply {
                putInt("id", epIdInt)
                putInt("businessType", 0)   // 0=普通包
                putInt("sceneType", 0)
            }
            val result = Reflect.callMethodOrNull(svc, "startDownloadEmoji", bundle)
            Log.i(TAG, "triggered startDownloadEmoji epId=$epId result=$result")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "triggerStartDownloadEmoji failed epId=$epId: ${e.message}"); false
        }
    }

    /** 返回单张表情图片字节。type: aio(预览png) / thu(缩略png) / big(加密大图解密)。 */
    private fun handleImage(epId: String, eId: String, type: String): NanoHTTPD.Response {
        val cl = QqRuntime.classLoader ?: return error("no classLoader", null)
        return try {
            val rt = QqRuntime.appRuntime() ?: return error("no appRuntime", null)
            // 1) 拿本地文件路径（MarketFaceStorageUtil）
            val path = localPath(cl, epId, eId, type)
            if (path == null) return error("no path util", null)
            val f = java.io.File(path)
            // 2) 不存在则触发下载
            if (!f.exists() || f.length() == 0L) {
                val ok = triggerDownloadImage(rt, cl, epId, eId, type)
                if (!ok) return error("图片不存在且下载未触发 epId=$epId eId=$eId type=$type", null)
                // 轮询等待文件就绪（最多 8s）
                val deadline = System.currentTimeMillis() + 8000
                while (System.currentTimeMillis() < deadline && (!f.exists() || f.length() == 0L)) {
                    Thread.sleep(300)
                }
                if (!f.exists() || f.length() == 0L) return error("下载超时 epId=$epId eId=$eId", null)
            }
            // 3) 读取并解密（big 类型是加密文件，需解密；aio/thu 是明文 png 直接读）
            val bytes = if (type == "big") decryptFile(cl, path) else java.io.File(path).readBytes()
            if (bytes == null || bytes.isEmpty()) return error("解密失败/空 $path", null)
            val mime = guessMime(bytes, type)
            okBytes(bytes, mime)
        } catch (e: Throwable) {
            Log.w(TAG, "handleImage error epId=$epId eId=$eId", e); error("image error: ${e.message}", null)
        }
    }

    /** 通过 MarketFaceStorageUtil 拿本地文件路径。 */
    private fun localPath(cl: ClassLoader, epId: String, eId: String, type: String): String? {
        return try {
            val util = Class.forName("com.tencent.mobileqq.emoticon.data.MarketFaceStorageUtil", false, cl)
            val m = when (type) {
                "aio" -> "getAIOPreviewImagePath"
                "thu" -> "getPanelPreviewImagePath"
                else -> "getEmoticonImagePath"
            }
            Reflect.callStaticMethodOrNull(util, m, epId, eId) as? String
        } catch (e: Throwable) { Log.w(TAG, "localPath failed: ${e.message}"); null }
    }

    /** 触发 QQ 下载单个表情。构造 Emoticon 对象或用 taskType 直接下载。 */
    private fun triggerDownloadImage(rt: Any, cl: ClassLoader, epId: String, eId: String, type: String): Boolean {
        return try {
            // 取 Emoticon 对象（syncFindEmoticonById(epId, eId)）
            val svcClass = Class.forName("com.tencent.mobileqq.emosm.api.IEmoticonManagerService", false, cl)
            val svc = Reflect.callMethodOrNull(rt, "getRuntimeService", svcClass, "") ?: return false
            val emoticon = Reflect.callMethodOrNull(svc, "syncFindEmoticonById", epId, eId) ?: return false
            // IEmojiManagerService.downloadAIOEmoticon(emoticon, taskType)
            val emojiCls = Class.forName("com.tencent.qqnt.emotion.api.IEmojiManagerService", false, cl)
            val emojiSvc = Reflect.callMethodOrNull(rt, "getRuntimeService", emojiCls) ?: return false
            // taskType: aio=1, thu=2, big=4
            val taskType = when (type) { "aio" -> 1; "thu" -> 2; else -> 4 }
            val r = Reflect.callMethodOrNull(emojiSvc, "downloadAIOEmoticon", emoticon, taskType)
            Log.i(TAG, "downloadAIOEmoticon epId=$epId eId=$eId type=$type taskType=$taskType → $r")
            true
        } catch (e: Throwable) { Log.w(TAG, "triggerDownloadImage failed: ${e.message}"); false }
    }

    /**
     * 解密 .emotionsm 加密大图。
     * QQ 的加密（SecurityUtile.codeEmosmKey={0,1,0,1}）只作用于 GIF 头：
     *   - GIF header（13 字节：签名6 + 逻辑屏幕描述符7）
     *   - 全局色表（项数由 offset 10 的 packed 字节决定：2^((packed&7)+1) 项，每项 3 字节）
     * 之后的 LZW 数据流、扩展块、图像块均为明文，不做 XOR。
     * 之前误对整个文件 XOR，导致头部解密成功但明文数据段反被破坏。
     */
    private fun decryptFile(cl: ClassLoader, path: String): ByteArray? {
        return try {
            val raw = java.io.File(path).readBytes()
            if (raw.isEmpty()) return null
            // PNG（_aio 预览图等）不经加密，原样返回
            if (raw.size > 4 && raw[0] == 0x89.toByte() && raw[1] == 0x50.toByte() &&
                raw[2] == 0x4e.toByte() && raw[3] == 0x47.toByte()) return raw
            // 仅 GIF 走加密解密
            val isGif = raw.size > 6 &&
                (String(raw, 0, 6) == "GIF89a" || String(raw, 0, 6) == "GIF87a")
            // 注意：磁盘上的是密文，头部被 XOR 过，所以不能直接判 "GIF89a"
            // 判密文头部特征：密文 GIF89a = {0x47,0x48,0x46,0x39,0x39,0x60}
            val isEncGif = raw.size > 6 && raw[0] == 0x47.toByte() && raw[1] == 0x48.toByte() &&
                raw[2] == 0x46.toByte() && raw[3] == 0x39.toByte()
            if (isGif || (!isEncGif)) {
                // 已是明文 GIF 或非 GIF，原样返回
                return raw
            }
            // 加密 GIF：计算加密区长度 = 13(header) + 全局色表
            val packed = raw[10].toInt() and 0xff
            val hasGct = (packed and 0x80) != 0
            val gctItems = if (hasGct) 1 shl ((packed and 0x07) + 1) else 0
            val encLen = 13 + gctItems * 3
            val key = byteArrayOf(0, 1, 0, 1)
            for (i in 0 until minOf(encLen, raw.size)) {
                raw[i] = (raw[i].toInt() xor key[i % 4].toInt()).toByte()
            }
            raw
        } catch (e: Throwable) { Log.w(TAG, "decryptFile failed: ${e.message}"); null }
    }

    private fun guessMime(bytes: ByteArray, type: String): String {
        if (type != "big") return "image/png"
        // GIF 魔数 47 49 46 38
        return if (bytes.size > 3 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte()) "image/gif" else "image/png"
    }
}
