package top.brokestar.emojiexporter.lsposed

import android.util.Log

/**
 * 已添加表情包：hook IEmoticonManagerService.syncGetTabEmoticonPackages / syncFindEmoticonPackageById
 * （被动捕获），并主动通过 AppRuntime.getRuntimeService 调用取数（双保障）。
 *
 * 102：hook 安装走 HookBridge（XposedModule.hook），反射一律走 Reflect（不依赖 legacy XposedHelpers）。
 */
object InstalledPacksHook {
    private const val TAG = "EmojiHook/Installed"
    @Volatile private var lastResult: List<Map<String, String>> = emptyList()
    @Volatile private var hookTried = false
    @Volatile private var fetchRunning = false

    data class EpMeta(val epId: String, val name: String?, val coverUrl: String?)

    fun init() { initReflective(QqRuntime.classLoader, null) }

    fun initReflective(explicitCl: ClassLoader?, logger: Any?) {
        val cl = explicitCl ?: QqRuntime.classLoader
        if (cl == null) { log(logger, true, "init: no classLoader"); return }
        if (hookTried) return
        hookTried = true
        val impl = QqRuntime.kEmoticonManagerService
        val ifaces = listOfNotNull(
            impl,
            runCatching { Class.forName("com.tencent.mobileqq.emosm.api.IEmoticonManagerService", false, cl) }.getOrNull()
        ).distinct()
        val svcName = ifaces.joinToString(",") { it.name }
        log(logger, false, "init: tryHook cl=$cl candidates=$svcName")
        for (cand in ifaces) {
            hookOne(cand, "syncGetTabEmoticonPackages", 0, logger)
            hookOne(cand, "syncGetTabEmoticonPackages", 1, logger)
            hookOne(cand, "syncFindEmoticonPackageById", 1, logger)
            hookOne(cand, "syncFindEmoticonPackageById", 2, logger)
        }
        log(logger, false, "init done hooks on $svcName")
        // 主动枚举：onPackageReady 时 AppRuntime 多半未就绪；Application.onCreate 后会再触发一次。
        startActiveFetchLoop(logger)
    }

    /** 后台重试循环：onPackageReady 后立即启动，等 AppRuntime 就绪再 fetch。 */
    private fun startActiveFetchLoop(logger: Any?) {
        if (fetchRunning) return
        fetchRunning = true
        Thread({
            try {
                for (attempt in 1..6) {
                    Thread.sleep(if (attempt == 1) 1800 else 2500)
                    if (lastResult.isNotEmpty()) return@Thread
                    val got = fetchViaRuntimeService(logger)
                    if (got.isNotEmpty()) {
                        lastResult = got
                        persistToFile(got, logger)
                        log(logger, false, "active fetch attempt $attempt got ${got.size} packs")
                        return@Thread
                    }
                    log(logger, true, "active fetch attempt $attempt empty — retry / open emotion panel")
                }
                log(logger, true, "active fetch gives up after retries — will rely on passive hook when you open emotion panel")
            } catch (t: Throwable) { log(logger, true, "active fetch error ${t.message}") }
            fetchRunning = false
        }, "EmojiInstalledActiveFetch").apply { isDaemon = true }.start()
    }

    /**
     * 外部（Application.onCreate 回调）触发的一次性主动枚举。
     * 此时 AppRuntime 通常已就绪，能补上 init 时 fetch 不到的情况。
     */
    fun triggerActiveFetch(logger: Any?) {
        if (lastResult.isNotEmpty()) return  // 已有数据，无需重复
        Thread({
            try {
                val got = fetchViaRuntimeService(logger)
                if (got.isNotEmpty()) {
                    lastResult = got
                    persistToFile(got, logger)
                    log(logger, false, "onCreate active fetch got ${got.size} packs")
                } else {
                    log(logger, true, "onCreate active fetch still empty")
                }
            } catch (t: Throwable) { log(logger, true, "onCreate active fetch error ${t.message}") }
        }, "EmojiInstalledOnCreateFetch").apply { isDaemon = true }.start()
    }

    private fun hookOne(target: Class<*>, method: String, argCount: Int, logger: Any?) {
        val member: java.lang.reflect.Method? = when (method) {
            "syncGetTabEmoticonPackages" -> when (argCount) {
                0 -> target.declaredMethods.firstOrNull { it.name == method && it.parameterCount == 0 }
                    ?: target.methods.firstOrNull { it.name == method && it.parameterCount == 0 }
                1 -> target.declaredMethods.firstOrNull { it.name == method && it.parameterCount == 1 }
                    ?: target.methods.firstOrNull { it.name == method && it.parameterCount == 1 }
                else -> null
            }
            "syncFindEmoticonPackageById" -> when (argCount) {
                1 -> target.declaredMethods.firstOrNull { it.name == method && it.parameterCount == 1 }
                2 -> target.declaredMethods.firstOrNull { it.name == method && it.parameterCount == 2 }
                else -> null
            }
            else -> null
        }
        if (member == null) { log(logger, true, "hook $method/$argCount not found on ${target.name}"); return }
        HookBridge.hookAfter(member) { _, _, result -> onCaptured(result, method, logger) }
        log(logger, false, "hooked $method/$argCount on ${target.name}")
    }

    private fun onCaptured(result: Any?, method: String, logger: Any?) {
        if (result == null) return
        try {
            val list: List<*> = when (result) { is List<*> -> result; else -> listOf(result) }
            val mapped = list.mapNotNull { pkg -> mapPackage(pkg) }.filter { it["epId"]!!.isNotBlank() }
            if (mapped.isEmpty()) return
            // 累积合并（按 epId 去重）：syncFindEmoticonPackageById 每次只返回 1 个，需合并而非覆盖。
            val merged = LinkedHashMap<String, Map<String, String>>()
            for (m in lastResult) merged[m["epId"]!!] = m
            for (m in mapped) merged[m["epId"]!!] = m
            lastResult = merged.values.toList()
            log(logger, false, "captured +${mapped.size} via $method (total ${lastResult.size})")
            persistToFile(lastResult, logger)
        } catch (e: Throwable) { log(logger, true, "after $method error ${e.message}") }
    }

    private fun fetchViaRuntimeService(logger: Any?): List<Map<String, String>> {
        // 全程纯反射（Reflect）—— 不用 de.robv...XposedHelpers：102 下它是混淆壳，后台线程加载会 NoClassDefFoundError。
        return try {
            val cl = QqRuntime.classLoader ?: run { log(logger, true, "fetch: no classLoader"); return emptyList() }
            // 1) MobileQQ.getMobileQQ() —— 静态无参
            val mobileQQCls = Class.forName("mqq.app.MobileQQ", false, cl)
            val inst = mobileQQCls.getMethod("getMobileQQ").invoke(null) ?: run { log(logger, true, "fetch: getMobileQQ null"); return emptyList() }
            // 2) 拿 AppRuntime：peekAppRuntime()（非阻塞）→ waitAppRuntime()（阻塞）→ mAppRuntime 字段
            var rt: Any? = null
            var rtErr: Throwable? = null
            rt = try { mobileQQCls.getMethod("peekAppRuntime").invoke(inst) } catch (e: Throwable) { rtErr = e; null }
            if (rt == null) try { rt = mobileQQCls.getMethod("waitAppRuntime").invoke(inst) } catch (e: Throwable) { rtErr = e }
            if (rt == null) try {
                val f = mobileQQCls.getDeclaredField("mAppRuntime"); f.isAccessible = true; rt = f.get(inst)
            } catch (e: Throwable) { rtErr = e }
            if (rt == null) { log(logger, true, "fetch: no AppRuntime (${rtErr?.javaClass?.simpleName}: ${rtErr?.message})"); return emptyList() }
            // 3) rt.getRuntimeService(IEmoticonManagerService.class, "") —— 注意选 mobileqq.emosm（旧架构，有 syncGetTabEmoticonPackages）
            val svcIfCls = Class.forName("com.tencent.mobileqq.emosm.api.IEmoticonManagerService", false, cl)
            val svc = try {
                rt.javaClass.getMethod("getRuntimeService", Class::class.java, String::class.java).invoke(rt, svcIfCls, "")
            } catch (e: Throwable) { log(logger, true, "fetch: getRuntimeService(2arg) failed: ${e.message}"); null }
                ?: try {
                    rt.javaClass.getMethod("getRuntimeService", Class::class.java).invoke(rt, svcIfCls)
                } catch (e: Throwable) { log(logger, true, "fetch: getRuntimeService(1arg) failed: ${e.message}"); null }
            if (svc == null) return emptyList()
            // 4) svc.syncGetTabEmoticonPackages(0) 或 ()
            var list: List<*>? = null
            try {
                val m = svc.javaClass.getMethod("syncGetTabEmoticonPackages", Int::class.javaPrimitiveType)
                list = m.invoke(svc, 0) as? List<*>
            } catch (_: Throwable) {}
            if (list == null) try { list = svc.javaClass.getMethod("syncGetTabEmoticonPackages").invoke(svc) as? List<*> } catch (_: Throwable) {}
            if (list.isNullOrEmpty()) { log(logger, true, "fetch: syncGetTabEmoticonPackages returned empty/null"); return emptyList() }
            val mapped = list.mapNotNull { pkg -> mapPackage(pkg) }.filter { it["epId"]!!.isNotBlank() }
            log(logger, false, "fetch: runtime service returned ${list.size} raw -> ${mapped.size} mapped")
            mapped
        } catch (t: Throwable) { log(logger, true, "fetch error: ${t.javaClass.simpleName}: ${t.message}"); emptyList() }
    }

    /** 把一个 EmoticonPackage 对象映射为 epId/name/coverUrl 的 Map。字段名对照 QQ 9.16.0 EmoticonPackage。 */
    private fun mapPackage(pkg: Any?): Map<String, String>? {
        if (pkg == null) return null
        return try {
            val epId = Reflect.getObjectField(pkg, "epId") as? String
                ?: Reflect.getObjectField(pkg, "epid") as? String
                ?: return null
            val name = Reflect.getObjectField(pkg, "name") as? String
            // imageUrl 服务端基本不填；填了也未必可信。可信走 imageUrl，否则直接拼 QQ 固定 CDN 封面规则。
            val imageUrl = (Reflect.getObjectField(pkg, "imageUrl") as? String)?.takeIf { it.startsWith("http") }
            val cover = imageUrl ?: "https://gxh.vip.qq.com/club/item/parcel/img/parcel/${shard(epId)}/$epId/200x200.png"
            mapOf("epId" to epId, "name" to (name ?: ""), "coverUrl" to cover)
        } catch (_: Throwable) { null }
    }

    private fun shard(epId: String): Long = (epId.trim().toLongOrNull() ?: 0) % 10

    private fun log(logger: Any?, isWarn: Boolean, msg: String) {
        if (isWarn) Log.w(TAG, msg) else Log.i(TAG, msg)
        try { (logger as? EmojiAppHook.Logger)?.let { if (isWarn) it.w(msg) else it.i(msg) } } catch (_: Throwable) {}
    }

    fun getInstalled(): List<EpMeta> {
        if (lastResult.isEmpty()) loadFromFile()?.let { lastResult = it }
        return lastResult.map { EpMeta(it["epId"]!!, it["name"]?.takeIf { s -> s.isNotBlank() }, it["coverUrl"]?.takeIf { s -> s.isNotBlank() }) }
    }

    private fun persistToFile(data: List<Map<String, String>>, logger: Any?) {
        try {
            val json = data.joinToString(",", "[", "]") { "{\"epId\":\"${it["epId"]}\",\"name\":\"${(it["name"] ?: "").replace("\"", "\\\"")}\",\"coverUrl\":\"${(it["coverUrl"] ?: "").replace("\"", "\\\"")}\"}" }
            val f = HookFile.installedFile() ?: run { log(logger, true, "persist: no file"); return }
            f.writeText(json); f.setReadable(true, false)
            log(logger, false, "persisted to ${f.absolutePath} (${json.length})")
        } catch (e: Throwable) { log(logger, true, "persist failed ${e.message}") }
    }
    private fun loadFromFile(): List<Map<String, String>>? = try {
        val txt = HookFile.readJson(HookFile.NAME_INSTALLED) ?: java.io.File("/data/local/tmp/emoji_installed.json").takeIf { it.exists() }?.readText() ?: return null
        Regex("\"epId\"\\s*:\\s*\"(\\d+)\"").findAll(txt).map { m ->
            val epId = m.groupValues[1]
            val name = Regex("\"name\"\\s*:\\s*\"([^\"]*)\"").find(txt.substringAfter("\"epId\":\"$epId\""))?.groupValues?.get(1) ?: ""
            mapOf("epId" to epId, "name" to name, "coverUrl" to "")
        }.toList().takeIf { it.isNotEmpty() }
    } catch (_: Throwable) { null }

    fun fetchActive(): List<EpMeta> = getInstalled()
}
