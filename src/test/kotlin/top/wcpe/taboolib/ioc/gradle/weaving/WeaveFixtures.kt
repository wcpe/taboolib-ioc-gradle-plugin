package top.wcpe.taboolib.ioc.gradle.weaving

import top.wcpe.taboolib.ioc.annotation.NoAspect

/** 覆盖各种签名（原始类型 / 对象 / void / 数组 / 长参）的织入夹具。 */
open class WeaveFixture {

    fun addInts(a: Int, b: Int): Int = a + b

    fun greet(name: String): String = "hello, $name"

    fun doNothing() {
        touched = true
    }

    fun addLongs(a: Long, b: Int): Long = a + b

    fun isPositive(value: Int): Boolean = value > 0

    fun half(value: Double): Double = value / 2

    fun echoChar(value: Char): Char = value

    fun echoByte(value: Byte): Byte = value

    fun echoShort(value: Short): Short = value

    fun doubleFloat(value: Float): Float = value * 2f

    fun makeArray(value: Int): IntArray = intArrayOf(value, value + 1)

    fun nullable(name: String?): String? = name?.uppercase()

    fun fourArgs(a: Int, b: Long, c: String, d: Double): String = "$a|$b|$c|$d"

    /** 控制流覆盖：try/catch/finally —— 异常表必须随方法体一起搬到合成方法且偏移自洽。 */
    fun tryCatchFinally(value: Int): String {
        return try {
            if (value < 0) throw IllegalArgumentException("negative") else "ok:$value"
        } catch (e: IllegalArgumentException) {
            "caught:${e.message}"
        } finally {
            touched = true
        }
    }

    /** 控制流覆盖：回边与循环头的栈映射帧。 */
    fun loopSum(limit: Int): Int {
        var total = 0
        for (i in 1..limit) {
            total += i
        }
        var cursor = limit
        while (cursor > 0) {
            total += cursor
            cursor--
        }
        return total
    }

    /** 控制流覆盖：`when` 的分支会产出 tableswitch；非连续分支产出 lookupswitch。 */
    fun switchLike(value: Int): String = when (value) {
        1 -> "one"
        2 -> "two"
        7 -> "seven"
        else -> "other"
    }

    /** 控制流覆盖：ACC_SYNCHRONIZED 会被转发体与合成体同时带上锁（可重入，语义不变）。 */
    @Synchronized
    fun synchronizedIncrement(): Int = ++counter

    /**
     * 控制流覆盖：`use` 展开后异常表里有多条 handler（主体路径与 close 路径）。
     *
     * 刻意用 JDK 的 `StringReader` 而不是夹具自己的匿名内部类：对拍测试会用独立类加载器加载
     * 织入后的字节码，而匿名内部类由 app 加载器加载，跨加载器访问它的包私有构造器会抛
     * IllegalAccessError —— 那是挂具的产物，不是织入器的问题。
     */
    fun useResource(text: String): String =
        java.io.StringReader(text).use { reader -> "read:" + reader.read() }

    @Suppress("unused")
    private fun secret(): Int = 42

    fun callSecret(): Int = secret()

    var touched: Boolean = false

    private var counter: Int = 0
}

/** 类级 @NoAspect：不应被织入。 */
@NoAspect
open class NoAspectClassFixture {
    fun greet(name: String): String = "hi, $name"
}

/** 方法级 @NoAspect：只有被标注的方法不织入。 */
open class NoAspectMethodFixture {

    @NoAspect
    fun excluded(name: String): String = "excluded:$name"

    fun included(name: String): String = "included:$name"
}
