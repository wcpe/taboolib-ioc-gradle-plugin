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

    @Suppress("unused")
    private fun secret(): Int = 42

    fun callSecret(): Int = secret()

    var touched: Boolean = false
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
