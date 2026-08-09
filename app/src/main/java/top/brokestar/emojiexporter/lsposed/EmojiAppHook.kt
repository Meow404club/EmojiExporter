package top.brokestar.emojiexporter.lsposed

import android.util.Log
import io.github.libxposed.api.XposedModule

/**
 * libxposed 102 模块在 QQ 进程的初始化编排。
 *
 * 生命周期：HookEntry.onPackageReady → 本类.onPackageReady → safeInitAll（装 hook）→
 * hookAppOnCreate（QQ Application 创建完成后补齐 appContext / HTTP server / 主动枚举）。
 */
object EmojiAppHook {
    private const val TAG = "EmojiAppHook"
    private const val TARGET = "com.tencent.mobileqq"
    @Volatile private var hooked = false
    @Volatile private var appCreateHooked = false

    fun onPackageReady(module: XposedModule, param: HookEntry.FakeLoadPackageParam, logger: Logger) {
        if (hooked) {
            logger.w("already hooked, skip onPackageReady")
            return
        }
        if (param.packageName != TARGET) return
        if (param.processName != TARGET) {
            logger.w("skip non-main process ${param.processName}")
            return
        }
        logger.i("onPackageReady init cl=${param.classLoader} proc=${param.processName}")
        HookBridge.attach(module)
        doHook(param.classLoader, logger)
    }

    private fun doHook(cl: ClassLoader, logger: Logger) {
        hooked = true
        logger.i("doHook via=libxposed102 cl=$cl")
        try { logger.i("-> QqRuntime.init"); QqRuntime.init(cl); logger.i("QqRuntime done") } catch (t: Throwable) { logger.e("QqRuntime.init error", t) }
        try { logger.i("-> InstalledPacksHook.init"); InstalledPacksHook.initReflective(cl, logger); logger.i("InstalledPacksHook done") } catch (t: Throwable) { logger.e("InstalledPacksHook.init error", t) }
        try { logger.i("-> MallSearchSso.init"); MallSearchSso.init(logger); logger.i("MallSearchSso done") } catch (t: Throwable) { logger.e("MallSearchSso.init error", t) }
        try { logger.i("-> IpcBridge.start"); IpcBridge.start(); logger.i("IpcBridge done — hook complete") } catch (t: Throwable) { logger.e("IpcBridge.start error", t) }
        // onPackageReady 早于 Application.onCreate：此时 appContext / AppRuntime 多半未就绪。
        // hook Application.onCreate，在 QQ Application 真正创建后补齐 Context + HTTP server + 重新触发主动枚举。
        hookAppOnCreate(cl, logger)
    }

    private fun hookAppOnCreate(cl: ClassLoader, logger: Logger) {
        if (appCreateHooked) return
        appCreateHooked = true
        try {
            // 优先 hook mqq.app.MobileQQ.onCreate（QQ 的 Application），失败则 hook 通用 Application.onCreate
            val appClazz = Reflect.findClassOrNull("mqq.app.MobileQQ", cl) ?: android.app.Application::class.java
            val member = try { appClazz.getDeclaredMethod("onCreate") } catch (_: Throwable) { appClazz.getMethod("onCreate") }
            HookBridge.hookAfter(member as java.lang.reflect.Method) { thisObj, _, _ ->
                try {
                    val ctx = thisObj as? android.content.Context
                    if (ctx != null) QqRuntime.appContext = ctx
                    QqRuntime.obtainAppContext(cl)
                    logger.i("Application.onCreate fired ctx=${QqRuntime.appContext?.packageName} filesDir=${runCatching { QqRuntime.appContext?.filesDir }.getOrNull()}")
                    // 启动 HTTP server（onPackageReady 时因无 Context 而跳过，这里补上）
                    val mod = HookBridge.module
                    val ctx2 = QqRuntime.appContext
                    if (mod != null && ctx2 != null) {
                        try {
                            top.brokestar.emojiexporter.lsposed.http.HookHttpServerManager.startIfNeeded(mod, ctx2, 8080)
                            logger.i("HookHttpServer start after onCreate port=" + top.brokestar.emojiexporter.lsposed.http.HookHttpServerManager.actualPort)
                        } catch (e: Throwable) { logger.e("HookHttpServer start after onCreate failed", e) }
                    }
                    // 重新触发已添加包主动枚举（此时 AppRuntime/Service 多半已就绪）
                    try { InstalledPacksHook.triggerActiveFetch(logger) } catch (_: Throwable) {}
                } catch (t: Throwable) { logger.e("Application.onCreate afterHook error", t) }
            }
            logger.i("hooked ${appClazz.name}.onCreate (deferred init)")
        } catch (e: Throwable) {
            logger.e("hookAppOnCreate failed", e)
        }
    }

    /** Logger 抽象 —— 同时写 logcat 与 XposedModule 日志（后者进 /data/adb/lspd/log）。 */
    interface Logger {
        fun i(msg: String)
        fun w(msg: String)
        fun e(msg: String, t: Throwable?)
    }

    /** 供 HookEntry 构造的 Logger，把日志同时送入 LSPosed 模块日志。 */
    fun moduleLogger(module: XposedModule): Logger = object : Logger {
        override fun i(msg: String) { Log.i(TAG, msg); try { module.log(Log.INFO, TAG, msg) } catch (_: Throwable) {} }
        override fun w(msg: String) { Log.w(TAG, msg); try { module.log(Log.WARN, TAG, msg) } catch (_: Throwable) {} }
        override fun e(msg: String, t: Throwable?) { Log.e(TAG, msg, t); try { module.log(Log.ERROR, TAG, msg, t) } catch (_: Throwable) {} }
    }
}
