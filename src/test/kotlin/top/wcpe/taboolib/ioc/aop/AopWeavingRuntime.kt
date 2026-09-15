package top.wcpe.taboolib.ioc.aop

/**
 * 测试专用桩：等价于真实 `AopWeavingRuntime` 的**静态入口签名**。
 *
 * 织入器生成的字节码会 `INVOKESTATIC` 到这里；桩把调用记录下来，
 * 再用反射调用被搬到 `xxx$ioc$original` 的原始方法体 —— 于是可以在没有完整容器的情况下，
 * 真正执行一遍织入后的字节码（包括拆装箱、局部变量槽位、栈映射帧）。
 */
object AopWeavingRuntime {

    val calls = mutableListOf<Call>()

    data class Call(val target: Any, val methodName: String, val originalName: String, val descriptor: String, val args: Array<Any?>?)

    @JvmStatic
    fun invoke(target: Any, key: String, originalName: String, args: Array<Any?>?): Any? {
        val descriptor = key.substring(key.indexOf('('))
        val methodName = key.substring(0, key.indexOf('('))
        synchronized(calls) { calls.add(Call(target, methodName, originalName, descriptor, args)) }
        val parameterTypes = MethodDescriptor.params(descriptor, target.javaClass.classLoader)
        val method = target.javaClass.getMethod(originalName, *parameterTypes)
        method.isAccessible = true
        return method.invoke(target, *(args ?: emptyArray()))
    }

    fun reset() = synchronized(calls) { calls.clear() }
}

/** 测试专用桩：织入标记接口。 */
interface WovenTarget

/** 极简描述符解析（仅测试用）。 */
private object MethodDescriptor {
    fun params(descriptor: String, loader: ClassLoader): Array<Class<*>> {
        val types = ArrayList<Class<*>>()
        var i = 1
        while (i < descriptor.length && descriptor[i] != ')') {
            var dims = 0
            while (descriptor[i] == '[') {
                dims++
                i++
            }
            val (type, next) = when (descriptor[i]) {
                'L' -> descriptor.substring(i + 1, descriptor.indexOf(';', i)).replace('/', '.') to descriptor.indexOf(';', i) + 1
                'I' -> "int" to i + 1
                'J' -> "long" to i + 1
                'Z' -> "boolean" to i + 1
                'B' -> "byte" to i + 1
                'C' -> "char" to i + 1
                'S' -> "short" to i + 1
                'F' -> "float" to i + 1
                'D' -> "double" to i + 1
                else -> error("unsupported descriptor: $descriptor")
            }
            var clazz = when (type) {
                "int" -> Int::class.javaPrimitiveType!!
                "long" -> Long::class.javaPrimitiveType!!
                "boolean" -> Boolean::class.javaPrimitiveType!!
                "byte" -> Byte::class.javaPrimitiveType!!
                "char" -> Char::class.javaPrimitiveType!!
                "short" -> Short::class.javaPrimitiveType!!
                "float" -> Float::class.javaPrimitiveType!!
                "double" -> Double::class.javaPrimitiveType!!
                else -> Class.forName(type, false, loader)
            }
            repeat(dims) { clazz = java.lang.reflect.Array.newInstance(clazz, 0).javaClass }
            types.add(clazz)
            i = next
        }
        return types.toTypedArray()
    }
}
