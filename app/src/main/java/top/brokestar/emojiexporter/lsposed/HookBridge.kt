package top.brokestar.emojiexporter.lsposed

import android.util.Log
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Member

/**
 * libxposed 102 hook 桥：统一通过 XposedModule.hook(Executable).intercept { chain } 安装 hook。
 *
 * 102 API 不提供 XposedHelpers 反射工具（见 Reflect.kt 说明）；hook 安装走 interceptor-chain 模型，
 * 业务方法调用与字段访问一律用标准反射（Reflect）。
 */
object HookBridge {
    private const val TAG = "EmojiBridge"
    @Volatile var module: XposedModule? = null

    fun attach(m: XposedModule) { module = m; Log.i(TAG, "attach $m") }

    fun hookAfter(member: Member, onAfter: (thisObj: Any?, args: Array<Any?>, result: Any?) -> Unit) {
        val mod = module ?: run { Log.w(TAG, "hookAfter no module attached"); return }
        try {
            mod.hook(member as java.lang.reflect.Executable).intercept { chain ->
                val res = chain.proceed()
                try { onAfter(chain.thisObject, chain.args.toTypedArray(), res) } catch (t: Throwable) { Log.w(TAG, "onAfter error", t) }
                res
            }
            Log.i(TAG, "hooked After ${memberName(member)}")
            try { mod.log(Log.INFO, TAG, "hooked After ${memberName(member)}") } catch (_: Throwable) {}
        } catch (t: Throwable) {
            Log.w(TAG, "hookAfter failed ${memberName(member)}", t)
        }
    }

    fun hookBefore(member: Member, onBefore: (thisObj: Any?, args: Array<Any?>) -> Boolean) {
        val mod = module ?: run { Log.w(TAG, "hookBefore no module attached"); return }
        try {
            val rt = (member as? java.lang.reflect.Method)?.returnType
            mod.hook(member as java.lang.reflect.Executable).intercept { chain ->
                val go = try { onBefore(chain.thisObject, chain.args.toTypedArray()) } catch (_: Throwable) { true }
                if (go) chain.proceed() else defaultValue(rt)
            }
            Log.i(TAG, "hooked Before ${memberName(member)}")
        } catch (t: Throwable) {
            Log.w(TAG, "hookBefore failed", t)
        }
    }

    private fun memberName(m: Member): String {
        val params = (m as? java.lang.reflect.Method)?.parameterTypes?.joinToString { it.simpleName }
            ?: (m as? java.lang.reflect.Constructor<*>)?.parameterTypes?.joinToString { it.simpleName }
        return "${m.declaringClass.simpleName}.${m.name}($params)"
    }

    private fun defaultValue(t: Class<*>?): Any? = when (t) {
        Boolean::class.javaPrimitiveType, Boolean::class.java -> false
        Int::class.javaPrimitiveType, Int::class.java -> 0
        Long::class.javaPrimitiveType, Long::class.java -> 0L
        else -> null
    }
}
