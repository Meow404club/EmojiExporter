package top.brokestar.emojiexporter.lsposed

import android.util.Log

/**
 * 搜商城/搜表情：hook IEmotionSearchManagerService。
 * 102：hook 安装走 HookBridge，反射走 Reflect。
 */
object StoreSearchHook {
    private const val TAG = "EmojiHook/Search"
    @Volatile private var lastKeyword: String = ""
    @Volatile private var lastItems: List<Map<String, String>> = emptyList()
    @Volatile private var searchServiceInstance: Any? = null

    fun init() { initReflective(QqRuntime.classLoader, null) }

    fun initReflective(explicitCl: ClassLoader?, logger: Any?) {
        val cl = explicitCl ?: QqRuntime.classLoader
        if (cl == null) { log(logger, true, "no classLoader"); return }
        val svc = QqRuntime.kEmotionSearchService
        if (svc == null) { log(logger, true, "no EmotionSearchService class"); return }
        log(logger, false, "init svc=${svc.name} cl=$cl")
        hookViaBridge(svc, logger)
    }

    private fun hookViaBridge(svc: Class<*>, logger: Any?) {
        // ctor —— 捕获 service 实例，用于主动触发搜索
        svc.declaredConstructors.forEach { ctor ->
            HookBridge.hookAfter(ctor) { thisObj, _, _ ->
                searchServiceInstance = thisObj
                log(logger, false, "captured search service")
            }
        }
        svc.declaredMethods.forEach { m ->
            when (m.name) {
                // 注意：不 hook pushEmotionSearchTask —— 它会同步触发 requestData → native 回调 →
                // handleGetHotPicSearchResult（同样被 hook），reentrant hook 在同线程会死锁。
                // lastKeyword 在 triggerSearch 里已设，push hook 只用于被动记录，非必要且有害，去掉。
                "onSearchCallBack", "notifySearchCallBack", "handleGetHotPicSearchResult" ->
                    HookBridge.hookAfter(m) { _, args, _ -> tryCaptureResult(args[0], logger) }
            }
        }
        log(logger, false, "hooked search service ${svc.name} via Bridge")
    }

    private fun tryCaptureResult(result: Any?, logger: Any?) {
        if (result == null) return
        try {
            // 结果对象有两种形态：
            //  - EmotionSearchResult（应用层，notifySearchCallBack 参数）：列表字段 itemList
            //  - EmojiHotPicSearchRspBody（底层 native，handleGetHotPicSearchResult 参数）：列表字段 infoArray
            val list = (Reflect.getObjectField(result, "itemList") as? List<*>)
                ?: (Reflect.getObjectField(result, "infoArray") as? List<*>)
                ?: (Reflect.callMethodOrNull(result, "getItemList") as? List<*>)
                ?: (Reflect.callMethodOrNull(result, "getInfoArray") as? List<*>)
                ?: return
            val mapped = list.mapNotNull { item ->
                try {
                    val mall = Reflect.getObjectField(item, "mallEmojiInfo")
                    // 包 ID 读取（对照 QQ 9.16.0 EmotionSearchItem + EmojiHotPicSearchEmojiInfo）：
                    //  1) item.appid（String，应用层）—— 两条转换路径都会写，最稳定
                    //  2) item.packageID（int，native 层 EmojiHotPicSearchEmojiInfo.packageID）
                    //  3) mallEmojiInfo.mallEmojiPackId（int）—— 仅商城结果
                    val packId: String? = (Reflect.getObjectField(item, "appid") as? String)?.takeIf { it.isNotBlank() && it != "0" }
                        ?: Reflect.getObjectField(item, "packageID")?.toString()?.takeIf { it.isNotBlank() && it != "0" }
                        ?: mall?.let { Reflect.getObjectField(it, "mallEmojiPackId")?.toString() }?.takeIf { it.isNotBlank() && it != "0" }
                    if (packId.isNullOrBlank()) return@mapNotNull null
                    val picId = (Reflect.getObjectField(item, "picId") as? String)
                        ?: (mall?.let { Reflect.getObjectField(it, "mallEmojiPicId") as? String })
                        ?: ""
                    // 包名优先（srcName 是来源包名；name 是单个表情名）
                    val packName = Reflect.getObjectField(item, "srcName") as? String
                        ?: Reflect.getObjectField(item, "name") as? String
                    mapOf("epId" to packId, "eId" to picId, "name" to (packName ?: ""))
                } catch (_: Throwable) { null }
            }
            if (mapped.isNotEmpty()) {
                lastItems = mapped.filter { it["epId"]!!.isNotBlank() }
                log(logger, false, "captured ${lastItems.size} search items for kw=$lastKeyword (raw ${list.size})")
                persistSearchToFile(lastKeyword, lastItems)
            } else if (list.isNotEmpty()) {
                log(logger, true, "search callback got ${list.size} items but mapped empty (mallEmojiInfo/packId mismatch?) first=${list.firstOrNull()?.javaClass?.name}")
            }
        } catch (t: Throwable) { log(logger, true, "tryCaptureResult error: ${t.message}") }
    }

    private fun log(logger: Any?, isWarn: Boolean, msg: String) {
        if (isWarn) Log.w(TAG, msg) else Log.i(TAG, msg)
        try { (logger as? EmojiAppHook.Logger)?.let { if (isWarn) it.w(msg) else it.i(msg) } } catch (_: Throwable) {}
    }

    fun getLastSearch(): Pair<String, List<Map<String, String>>> {
        // 注意：lastItems 为空时不从文件覆盖 lastKeyword —— 否则 awaitResult 轮询期间
        // （setLastKeyword 已设当前 kw，但结果还没回来）会把 lastKeyword 覆盖成旧文件的空值。
        if (lastItems.isEmpty() && lastKeyword.isEmpty()) {
            loadSearchFromFile()?.let { (k, v) -> lastKeyword = k; lastItems = v }
        }
        return lastKeyword to lastItems
    }

    /** 供外部（QqSearchHandler fallback 路径）在 push 前设置当前 keyword，使 tryCaptureResult 能匹配。 */
    fun setLastKeyword(kw: String) { lastKeyword = kw }

    private fun persistSearchToFile(kw: String, items: List<Map<String, String>>) {
        try {
            val json = items.joinToString(",", "[", "]") { "{\"epId\":\"${it["epId"]}\",\"eId\":\"${it["eId"] ?: ""}\"}" }
            val body = "{\"keyword\":\"${kw.replace("\"", "\\\"")}\",\"items\":$json}"
            // 写到 QQ filesDir（HookFile）—— App 用 root(nsenter) 读取
            HookFile.writeJson(HookFile.NAME_LAST_SEARCH, body, null)
        } catch (_: Throwable) {}
    }
    private fun loadSearchFromFile(): Pair<String, List<Map<String, String>>>? = try {
        val txt = HookFile.readJson(HookFile.NAME_LAST_SEARCH) ?: java.io.File("/data/local/tmp/emoji_last_search.json").takeIf { it.exists() }?.readText() ?: return null
        val kw = Regex("\"keyword\"\\s*:\\s*\"([^\"]*)\"").find(txt)?.groupValues?.get(1) ?: ""
        val items = Regex("\"epId\"\\s*:\\s*\"(\\d+)\"").findAll(txt).map { mapOf("epId" to it.groupValues[1], "eId" to "") }.toList()
        if (items.isEmpty()) null else kw to items
    } catch (_: Throwable) { null }

    fun triggerSearch(keyword: String): Boolean {
        val svc = searchServiceInstance ?: return false
        return try {
            val cl = QqRuntime.classLoader ?: return false
            val taskClass = Class.forName("com.tencent.mobileqq.emosm.api.IEmotionSearchManagerService\$EmotionSearchTask", false, cl)
            // 构造函数 EmotionSearchTask(int taskType, String searchKeyWords) —— taskType=1 表示搜索
            val task = Reflect.newInstance(taskClass, 1, keyword)
            try { Reflect.setObjectField(task, "firstTimePullCount", 32) } catch (_: Throwable) {}
            try {
                val sceneCl = Class.forName("com.tencent.qqnt.kernel.nativeinterface.EmojiHotPicSearchSceneType", false, cl)
                val v = Reflect.getStaticObjectField(sceneCl, "KHOTPICPANEL")
                if (v != null) Reflect.setObjectField(task, "sceneType", v)
            } catch (_: Throwable) {}
            try { Reflect.setObjectField(task, "isSupportMall", true) } catch (_: Throwable) {}
            lastKeyword = keyword
            // 先 resetData 清掉旧 task 状态（避免 isSameTask 拦截），再 push
            try { Reflect.callMethod(svc, "resetData") } catch (_: Throwable) {}
            Reflect.callMethod(svc, "pushEmotionSearchTask", task)
            Log.i(TAG, "triggered search for $keyword")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "triggerSearch failed", e)
            false
        }
    }

    /** 阻塞等待搜索结果：轮询内存里的 lastItems（trigger 在独立线程跑，捕获后写入）。同进程直读内存。 */
    fun awaitResult(kw: String, timeoutMs: Long = 8000): List<Map<String, String>> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val (capKw, items) = getLastSearch()
            if (capKw == kw && items.isNotEmpty()) return items
            try { Thread.sleep(200) } catch (_: Throwable) {}
        }
        return emptyList()
    }
}
