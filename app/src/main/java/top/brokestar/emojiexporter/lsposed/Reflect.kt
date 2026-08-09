package top.brokestar.emojiexporter.lsposed

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 纯 Java 反射工具，替代 legacy 的 de.robv.android.xposed.XposedHelpers。
 *
 * 背景见 libxposed 102 API：102 明确禁止 de.robv.android.xposed（它在 102 下是混淆壳，
 * 在非主线程首次引用会 NoClassDefFoundError）。102 官方不提供反射工具类，
 * 查找类/调用方法/访问字段一律用标准 java.lang.reflect。本 object 就是这套薄封装。
 *
 * 全部方法对访问检查做 setAccessible(true)，并在匹配重载时处理基本类型（int/long/boolean）。
 * 调用方按需 try/catch —— 内部不吞异常，失败直接抛出，便于上层诊断。
 */
object Reflect {

    fun findClass(name: String, cl: ClassLoader?): Class<*> = Class.forName(name, false, cl)

    fun findClassOrNull(name: String, cl: ClassLoader?): Class<*>? = try { Class.forName(name, false, cl) } catch (_: Throwable) { null }

    /** 沿继承链查找声明方法（含父类/接口），按方法名 + 参数个数 + 运行时类型可赋值性匹配。 */
    fun findMethod(clz: Class<*>, name: String, vararg args: Any?): Method? {
        val argTypes = args.map { it?.javaClass }
        // 先精确搜 declaredMethods（含 private），沿继承链向上
        var c: Class<*>? = clz
        while (c != null && c != Any::class.java) {
            for (m in c.declaredMethods) {
                if (m.name != name) continue
                if (!argsMatch(m.parameterTypes, argTypes, args)) continue
                m.isAccessible = true
                return m
            }
            c = c.superclass
        }
        // 再搜 public methods（接口默认方法等）
        for (m in clz.methods) {
            if (m.name != name && argsMatch(m.parameterTypes, argTypes, args)) { m.isAccessible = true; return m }
            if (m.name == name && argsMatch(m.parameterTypes, argTypes, args)) { m.isAccessible = true; return m }
        }
        return null
    }

    fun callMethod(obj: Any?, name: String, vararg args: Any?): Any? {
        val m = findMethod(obj?.javaClass ?: return null, name, *args) ?: error("method $name not found on ${obj!!.javaClass.name} with ${args.size} args")
        return m.invoke(obj, *args)
    }

    fun callMethodOrNull(obj: Any?, name: String, vararg args: Any?): Any? = try { callMethod(obj, name, *args) } catch (_: Throwable) { null }

    fun callStaticMethod(clz: Class<*>, name: String, vararg args: Any?): Any? {
        val m = findMethod(clz, name, *args) ?: error("static method $name not found on ${clz.name}")
        m.isAccessible = true
        return m.invoke(null, *args)
    }

    fun callStaticMethodOrNull(clz: Class<*>, name: String, vararg args: Any?): Any? = try { callStaticMethod(clz, name, *args) } catch (_: Throwable) { null }

    /** 沿继承链查找字段（按名字，忽略类型）。 */
    private fun findField(clz: Class<*>, name: String): Field? {
        var c: Class<*>? = clz
        while (c != null && c != Any::class.java) {
            try { return c.getDeclaredField(name).apply { isAccessible = true } } catch (_: NoSuchFieldException) {}
            c = c.superclass
        }
        return null
    }

    fun getObjectField(obj: Any?, name: String): Any? {
        val f = findField(obj?.javaClass ?: return null, name) ?: return null
        return f.get(obj)
    }

    fun setObjectField(obj: Any?, name: String, value: Any?) {
        val f = findField(obj?.javaClass ?: return, name) ?: return
        f.set(obj, value)
    }

    fun getStaticObjectField(clz: Class<*>, name: String): Any? = findField(clz, name)?.get(null)

    fun newInstance(clz: Class<*>, vararg args: Any?): Any {
        val argTypes = args.map { it?.javaClass }
        var c: Class<*>? = clz
        while (c != null && c != Any::class.java) {
            for (ctor in c.declaredConstructors) {
                if (argsMatch(ctor.parameterTypes, argTypes, args)) {
                    ctor.isAccessible = true
                    return ctor.newInstance(*args)
                }
            }
            c = c.superclass
        }
        error("constructor not found on ${clz.name} with ${args.size} args")
    }

    fun newInstanceOrNull(clz: Class<*>, vararg args: Any?): Any? = try { newInstance(clz, *args) } catch (_: Throwable) { null }

    /**
     * 判断实际参数能否匹配形参类型列表。处理基本类型 boxing（int 形参可接受 Integer 实参）。
     * 也允许 null 实参匹配任意非基本类型形参。
     */
    private fun argsMatch(paramTypes: Array<Class<*>>, argTypes: List<Class<*>?>, args: Array<out Any?>): Boolean {
        if (paramTypes.size != args.size) return false
        for (i in paramTypes.indices) {
            val pt = paramTypes[i]
            val arg = args[i]
            if (arg == null) {
                if (pt.isPrimitive) return false  // 基本类型不接受 null
                continue
            }
            val at = arg.javaClass
            if (!assignable(pt, at)) return false
        }
        return true
    }

    /** pt 是否能接受 at 类型（处理基本类型 boxing：int 接受 Integer）。 */
    private fun assignable(pt: Class<*>, at: Class<*>): Boolean {
        if (pt == at || pt.isAssignableFrom(at)) return true
        if (pt.isPrimitive) return when (pt) {
            Int::class.javaPrimitiveType -> at == Integer::class.java
            Long::class.javaPrimitiveType -> at == Long::class.java || at == Integer::class.java
            Boolean::class.javaPrimitiveType -> at == Boolean::class.java
            Short::class.javaPrimitiveType -> at == Short::class.java
            Byte::class.javaPrimitiveType -> at == Byte::class.java
            Float::class.javaPrimitiveType -> at == Float::class.java || at == Double::class.java
            Double::class.javaPrimitiveType -> at == Double::class.java || at == Float::class.java
            Char::class.javaPrimitiveType -> at == Character::class.java
            else -> false
        }
        return false
    }
}
