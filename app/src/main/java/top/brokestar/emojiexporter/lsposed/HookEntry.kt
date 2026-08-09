package top.brokestar.emojiexporter.lsposed

import android.os.Build
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * LSP 102 专用入口（Forest/Sen 的 HookEntry 102 模板迁移）：
 * onPackageReady 之后 rovo89 兼容层已注入，此时可安全 import de.robv.*，但 hook 走 XposedModule.hook().intercept（见 HookBridge）。
 */
class HookEntry : XposedModule() {

    companion object {
        const val TAG = "EmojiHook102"
        const val TARGET = "com.tencent.mobileqq"
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        try {
            this.log(Log.INFO, TAG, "onModuleLoaded: process=${param.processName}")
            this.log(Log.INFO, TAG, "framework: $frameworkName($frameworkVersionCode) API $apiVersion")
            val hasProp: (Long) -> Boolean = { prop -> frameworkProperties.and(prop) != 0L }
            try {
                this.log(Log.INFO, TAG, "system supported: ${hasProp(PROP_CAP_SYSTEM)} remote supported: ${hasProp(PROP_CAP_REMOTE)} api protection: ${hasProp(PROP_RT_API_PROTECTION)}")
            } catch (_: Throwable) {}
        } catch (t: Throwable) {
            try { this.log(Log.ERROR, TAG, "onModuleLoaded error: ", t) } catch (_: Throwable) { Log.e(TAG, "onModuleLoaded error", t) }
        }
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        try {
            if (param.packageName != TARGET) return
            if (!param.isFirstPackage) {
                this.log(Log.INFO, TAG, "onPackageLoaded: skip non-first pkg=${param.packageName}")
                return
            }
            this.log(Log.INFO, TAG, "onPackageLoaded: ${param.packageName} isFirst=${param.isFirstPackage}")
            QqRuntime.onPackageLoadedEarly(param)
        } catch (t: Throwable) {
            try { this.log(Log.ERROR, TAG, "onPackageLoaded error: ", t) } catch (_: Throwable) { Log.e(TAG, "onPackageLoaded error", t) }
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        try {
            this.log(Log.INFO, TAG, "onPackageReady ENTERED: ${param.packageName} isFirst=${param.isFirstPackage}")
            if (param.packageName != TARGET) return
            if (!param.isFirstPackage) {
                this.log(Log.WARN, TAG, "onPackageReady: skip non-first package ${param.packageName}")
                return
            }
            this.log(Log.INFO, TAG, "onPackageReady: ${param.packageName} cl=${param.classLoader}")
            // 102：通过 HookBridge 装 hook；Application 创建完成前 appContext 不可用，HTTP server 在 onCreate 回调里补启
            HookBridge.attach(this)
            QqRuntime.onPackageReady(param)
            val procName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) android.app.Application.getProcessName() else param.packageName
            val fake = FakeLoadPackageParam(param.packageName, procName, param.classLoader, param.applicationInfo, param.isFirstPackage)
            this.log(Log.INFO, TAG, "onPackageReady: process=$procName -> EmojiAppHook.onPackageReady")
            EmojiAppHook.onPackageReady(this, fake, EmojiAppHook.moduleLogger(this))
            this.log(Log.INFO, TAG, "onPackageReady: EmojiAppHook done")
            // HTTP server 由 EmojiAppHook.hookAppOnCreate 在 Application.onCreate 后启动（此时 appContext 才可用）
        } catch (e: Throwable) {
            try { this.log(Log.ERROR, TAG, "onPackageReady error: ", e) } catch (_: Throwable) { Log.e(TAG, "onPackageReady error", e) }
        }
    }

    data class FakeLoadPackageParam(
        val packageName: String,
        val processName: String,
        val classLoader: ClassLoader,
        val appInfo: android.content.pm.ApplicationInfo,
        val isFirstPackage: Boolean
    )
}
