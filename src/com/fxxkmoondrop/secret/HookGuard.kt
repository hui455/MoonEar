package com.hui455.moonear

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

/**
 * Hook 返回值类型安全化（3.0.3）。
 *
 * 为什么需要：libxposed / LSPosed 的 Hook 桥按目标方法的**返回类型**解包回调返回值。
 * 目标方法返回基本类型（如 `boolean`）而我们的回调返回 null 时，桥内会做
 * `((Boolean) null).booleanValue()` 之类的解包 —— 直接抛异常。
 * 这种异常发生在**宿主进程**（GMS / Settings / SystemUI）里，后果是宿主崩溃，
 * 而不是我们自己的模块出错。
 *
 * 这套 Hook 里大量使用的是「不满足条件就 early-return null 放行原逻辑」的写法
 * （模块只增强、不替代）。对返回 void 的方法，null 恰好正确；
 * 但对少数返回 boolean/int 的方法就是一颗雷。
 *
 * 因此所有 early-return 一律走 [nullSafe]：由目标方法的真实返回类型决定
 * 该给什么安全值 —— void/引用类型给 null（语义正确），基本类型给该类型默认值并记警告，
 * 便于日后定位是哪个 Hook 走了「未接管」路径。
 */
object HookGuard {

    private const val TAG = "HookGuard"

    /** 早退放行：按目标方法返回类型给出安全返回值（void/引用类型 = null）。 */
    @JvmStatic
    fun nullSafe(chain: XposedInterface.Chain?): Any? = coerce(chain, null)

    /** 显式返回值：类型与目标方法不符时回落安全值；相符则原样返回。 */
    @JvmStatic
    fun safe(chain: XposedInterface.Chain?, result: Any?): Any? = coerce(chain, result)

    private fun coerce(chain: XposedInterface.Chain?, result: Any?): Any? {
        val exec = try {
            chain?.executable
        } catch (_: Throwable) {
            null
        }
        // 拿不到方法元数据（Constructor 或异常）：构造器无返回值，null 正确。
        val rt: Class<*> = (exec as? Method)?.returnType ?: return null
        if (rt == Void.TYPE) return null          // void：null 即正确
        if (!rt.isPrimitive) return result        // 引用类型：null 合法
        if (matches(rt, result)) return result
        val fallback = primitiveDefault(rt)
        Log.w(TAG, "hook " + exec.declaringClass.name + "." + exec.name + " 对基本类型 " +
                rt.name + " 返回了 " + (if (result == null) "null" else result.javaClass.name) +
                "，已回落 " + fallback)
        return fallback
    }

    /** Kotlin 的 Boolean/Int 等装箱类型与目标基本类型是否可直接解包。 */
    private fun matches(rt: Class<*>, v: Any?): Boolean = when (rt.name) {
        "boolean" -> v is Boolean
        "int" -> v is Int
        "long" -> v is Long
        "short" -> v is Short
        "byte" -> v is Byte
        "char" -> v is Char
        "float" -> v is Float
        "double" -> v is Double
        else -> true
    }

    private fun primitiveDefault(rt: Class<*>): Any = when (rt.name) {
        "boolean" -> false
        "int" -> 0
        "long" -> 0L
        "short" -> 0.toShort()
        "byte" -> 0.toByte()
        "char" -> '\u0000'
        "float" -> 0f
        "double" -> 0.0
        else -> 0
    }
}
