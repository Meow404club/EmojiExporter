package top.brokestar.emojiexporter.lsposed

import android.content.pm.ApplicationInfo
import android.util.Log
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * 捕获 ClassLoader + 解析核心 QQ 类，提供 AppRuntime / 当前 uin 的统一获取。
 * libxposed 102：onPackageReady 时 classloader 就绪，用 Class.forName 解析 QQ 类。
 */
object QqRuntime {
    private const val TAG = "EmojiHook"

    @Volatile var classLoader: ClassLoader? = null
        private set
    @Volatile var modulePath: String? = null
    @Volatile var appInfo: ApplicationInfo? = null
    @Volatile var packageName: String? = null
    @Volatile var appContext: android.content.Context? = null

    private val lock = Any()

    @Volatile var kEmoticonManagerService: Class<*>? = null
    @Volatile var kEmotionSearchService: Class<*>? = null
    @Volatile var kEmoticonHandler: Class<*>? = null
    @Volatile var kTicketManager: Class<*>? = null

    fun onPackageLoadedEarly(param: PackageLoadedParam) {
        synchronized(lock) {
            if (classLoader == null) classLoader = param.defaultClassLoader
            if (appInfo == null) appInfo = param.applicationInfo
            packageName = param.packageName
        }
        Log.i(TAG, "onPackageLoadedEarly cl=${param.defaultClassLoader} pkg=${param.packageName}")
    }

    fun onPackageReady(param: PackageReadyParam) {
        synchronized(lock) {
            classLoader = param.classLoader
            appInfo = param.applicationInfo
            packageName = param.packageName
        }
        Log.i(TAG, "onPackageReady cl=${param.classLoader} pkg=${param.packageName}")
    }

    fun init(cl: ClassLoader) {
        synchronized(lock) {
            val same = classLoader === cl
            classLoader = cl
            if (same) Log.i(TAG, "init re-enter same cl=$cl")
        }
        // 捕获 QQ Application Context（用于 filesDir 写入，App 通过 root 读取）
        // 注意：onPackageReady 早于 Application.onCreate，此时 currentApplication 多半为 null；
        // 这里尽力尝试一次，真正拿到 Context 的时机在 hookAppOnCreate 里（Application 创建完成后回调）。
        obtainAppContext(cl)
        Log.i(TAG, "QqRuntime init with " + cl + " ctx=" + appContext?.packageName + " filesDir=" + runCatching{appContext?.filesDir}.getOrNull())
        // 解析 QQ 核心 service 类（用标准反射 Class.forName）
        kEmoticonManagerService = findFirst(
            "com.tencent.mobileqq.emosm.api.impl.EmoticonManagerServiceImpl",
            "com.tencent.mobileqq.emosm.api.IEmoticonManagerService",
        )
        kEmotionSearchService = findFirst(
            "com.tencent.mobileqq.emosm.api.impl.EmotionSearchManagerServiceImpl",
            "com.tencent.mobileqq.emosm.api.IEmotionSearchManagerService",
        )
        kEmoticonHandler = findFirst("com.tencent.mobileqq.app.EmoticonHandler")
        kTicketManager = findFirst("mqq.manager.TicketManager", "mqq.app.TicketManagerImpl")
        Log.i(TAG, "resolved: emoticonMgr=${kEmoticonManagerService?.name} search=${kEmotionSearchService?.name} handler=${kEmoticonHandler?.name} ticket=${kTicketManager?.name}")
    }

    fun isReady(): Boolean = classLoader != null

    /**
     * 尽力获取 QQ Application Context。可在 Application.onCreate 回调里重复调用，拿到后缓存。
     * onPackageReady 早于 Application.onCreate，首次调用多半拿不到 —— 这是正常的，靠 hookAppOnCreate 补齐。
     * 返回 true 表示成功获取（含已缓存的情形）。
     */
    fun obtainAppContext(cl: ClassLoader? = classLoader): Boolean {
        if (appContext != null) return true
        // 1) ActivityThread.currentApplication() —— Application 创建完成后最可靠
        try {
            val atClazz = Class.forName("android.app.ActivityThread")
            val curApp = atClazz.getMethod("currentApplication").invoke(null) as? android.content.Context
            if (curApp != null) { appContext = curApp; Log.i(TAG, "obtainAppContext via ActivityThread=$curApp"); return true }
        } catch (_: Throwable) {}
        // 2) MobileQQ.getMobileQQ() 静态实例（ContextWrapper）
        try {
            val c = Class.forName("mqq.app.MobileQQ", false, cl ?: ClassLoader.getSystemClassLoader())
            val inst = c.getMethod("getMobileQQ").invoke(null) as? android.content.Context
            if (inst != null) { appContext = inst; Log.i(TAG, "obtainAppContext via MobileQQ.getMobileQQ=$inst"); return true }
        } catch (_: Throwable) {}
        Log.i(TAG, "obtainAppContext not yet available (Application not created)")
        return false
    }

    /**
     * 获取 QQ AppRuntime 实例（线程安全，可在任意线程调用）。
     * 顺序：peekAppRuntime()（非阻塞）→ waitAppRuntime()（阻塞）→ mAppRuntime 字段。
     * 注意 waitAppRuntime() 是无参方法 —— 不能传 null，否则误匹配有参重载。
     */
    fun appRuntime(): Any? {
        val cl = classLoader ?: return null
        return try {
            val mobileQQCls = Class.forName("mqq.app.MobileQQ", false, cl)
            val inst = mobileQQCls.getMethod("getMobileQQ").invoke(null) ?: return null
            // 1) peekAppRuntime —— 非阻塞
            var rt: Any? = try { mobileQQCls.getMethod("peekAppRuntime").invoke(inst) } catch (_: Throwable) { null }
            // 2) waitAppRuntime() 无参 —— 阻塞等待就绪
            if (rt == null) try { rt = mobileQQCls.getMethod("waitAppRuntime").invoke(inst) } catch (_: Throwable) {}
            // 3) mAppRuntime 字段
            if (rt == null) try { val f = mobileQQCls.getDeclaredField("mAppRuntime"); f.isAccessible = true; rt = f.get(inst) } catch (_: Throwable) {}
            rt
        } catch (_: Throwable) { null }
    }

    /** 当前登录账号 uin（来自 AppRuntime.getAccount）。未登录返回 null。 */
    fun currentUin(): String? {
        val rt = appRuntime() ?: return null
        return try {
            val uin = Reflect.callMethodOrNull(rt, "getAccount") as? String
            uin?.takeIf { it.isNotBlank() && it != "0" }
        } catch (_: Throwable) { null }
    }

    private fun findFirst(vararg names: String): Class<*>? {
        val cl = classLoader ?: return null
        for (n in names) {
            try {
                val c = Class.forName(n, false, cl)
                Log.i(TAG, "found $n -> $c")
                return c
            } catch (_: Throwable) {}
        }
        Log.w(TAG, "not found any of ${names.toList()}")
        return null
    }
}
