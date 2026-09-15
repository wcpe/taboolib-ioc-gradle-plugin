package top.wcpe.taboolib.ioc.gradle.weaving

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import top.wcpe.taboolib.ioc.aop.AopWeavingRuntime
import top.wcpe.taboolib.ioc.aop.WovenTarget

/**
 * ② 织入器的验证：**真的执行一遍织入后的字节码**。
 *
 * 测试源集里放了与真实同名同签名的桩（`AopWeavingRuntime` / `WovenTarget` / `NoAspect`），
 * 因此可以把织入后的类加载起来、逐个调用各签名的方法，并与未织入的原类逐一对拍 ——
 * 覆盖拆装箱、局部变量槽位（long/double 占两槽）、void/数组返回、栈映射帧等细节。
 */
class AopWeaverTest {

    private val allMethodsAdvice = listOf(
        ResolvedAdvice(
            aspectClassName = "top.wcpe.TestAspect",
            adviceAnnotation = "Around",
            adviceMethodName = "around",
            classPattern = "WeaveFixture",
            methodPattern = "*",
        )
    )

    @Test
    fun `织入后类被标记且原方法体搬到合成方法`() {
        val outcome = weaveFixture(WeaveFixture::class.java, allMethodsAdvice)
        assertNotNull(outcome.bytes)

        val woven = readNode(outcome.bytes!!)
        assertTrue(
            woven.interfaces.contains("top/wcpe/taboolib/ioc/aop/WovenTarget"),
            "织入后的类应实现 WovenTarget 标记接口"
        )
        assertTrue(woven.methods.any { it.name == "greet${AopWeaver.ORIGINAL_SUFFIX}" }, "应生成合成原方法")
        assertTrue(woven.methods.any { it.name == "addInts${AopWeaver.ORIGINAL_SUFFIX}" }, "应生成合成原方法")
        // 合成方法上不能残留注解（否则容器会把 @PostConstruct/@Inject 再认一遍）
        val synthetic = woven.methods.first { it.name == "greet${AopWeaver.ORIGINAL_SUFFIX}" }
        assertNull(synthetic.visibleAnnotations)
        assertNull(synthetic.invisibleAnnotations)
    }

    @Test
    fun `织入后各签名方法的行为与未织入时完全一致（真实执行对拍）`() {
        AopWeavingRuntime.reset()
        val wovenClass = loadWoven(WeaveFixture::class.java, allMethodsAdvice)
        val plain = WeaveFixture()
        val woven = wovenClass.getDeclaredConstructor().newInstance()

        assertSameResult(
            plain, woven, "addInts",
            arrayOf(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType), 3, 4
        )
        assertSameResult(plain, woven, "greet", arrayOf(String::class.java), "taboolib")
        assertSameResult(plain, woven, "addLongs", arrayOf(Long::class.javaPrimitiveType, Int::class.javaPrimitiveType), 7L, 3)
        assertSameResult(plain, woven, "isPositive", arrayOf(Int::class.javaPrimitiveType), 5)
        assertSameResult(plain, woven, "half", arrayOf(Double::class.javaPrimitiveType), 9.0)
        assertSameResult(plain, woven, "echoChar", arrayOf(Char::class.javaPrimitiveType), 'x')
        assertSameResult(plain, woven, "echoByte", arrayOf(Byte::class.javaPrimitiveType), 7.toByte())
        assertSameResult(plain, woven, "echoShort", arrayOf(Short::class.javaPrimitiveType), 9.toShort())
        assertSameResult(plain, woven, "doubleFloat", arrayOf(Float::class.javaPrimitiveType), 1.5f)
        assertSameResult(plain, woven, "makeArray", arrayOf(Int::class.javaPrimitiveType), 4)
        assertSameResult(plain, woven, "nullable", arrayOf(String::class.java), "abc")
        assertSameResult(plain, woven, "nullable", arrayOf(String::class.java), null as String?)
        assertSameResult(plain, woven, "fourArgs", arrayOf(Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, String::class.java, Double::class.javaPrimitiveType), 1, 2L, "three", 4.5)

        // void 方法：真实执行一次，确认副作用生效且没有返回值问题
        val doNothing = wovenClass.getMethod("doNothing")
        doNothing.invoke(woven)
        assertEquals(true, wovenClass.getMethod("getTouched").invoke(woven))

        // 每次调用都必须经过织入入口
        synchronized(AopWeavingRuntime.calls) {
            assertTrue(AopWeavingRuntime.calls.size >= 15, "每次调用都应经过 AopWeavingRuntime（实际 ${AopWeavingRuntime.calls.size}）")
            assertTrue(AopWeavingRuntime.calls.any { it.methodName == "addInts" && it.args?.contentEquals(arrayOf(3, 4)) == true })
        }
    }

    @Test
    fun `织入是幂等的`() {
        val first = weaveFixture(WeaveFixture::class.java, allMethodsAdvice)
        assertNotNull(first.bytes)
        val second = AopWeaver.weave(first.bytes!!, allMethodsAdvice)
        assertNull(second.bytes, "已织入的类不应被二次织入")
        assertEquals(0, second.wovenMethods)
    }

    @Test
    fun `类级 NoAspect 不织入`() {
        val outcome = weaveFixture(NoAspectClassFixture::class.java, allMethodsAdvice)
        assertNull(outcome.bytes)
        assertEquals(0, outcome.wovenMethods)
    }

    @Test
    fun `方法级 NoAspect 只跳过被标注的方法`() {
        val advices = listOf(allMethodsAdvice.first().copy(classPattern = "NoAspectMethodFixture"))
        val wovenClass = loadWoven(NoAspectMethodFixture::class.java, advices)
        val woven = wovenClass.getDeclaredConstructor().newInstance()

        assertTrue(
            wovenClass.methods.any { it.name == "excluded${AopWeaver.ORIGINAL_SUFFIX}" } == false,
            "@NoAspect 方法不应被织入"
        )
        assertTrue(wovenClass.methods.any { it.name == "included${AopWeaver.ORIGINAL_SUFFIX}" }, "未排除的方法应被织入")

        assertEquals("excluded:y", wovenClass.getMethod("excluded", String::class.java).invoke(woven, "y"))
        assertEquals("included:y", wovenClass.getMethod("included", String::class.java).invoke(woven, "y"))
    }

    @Test
    fun `private 方法不被织入`() {
        val outcome = weaveFixture(WeaveFixture::class.java, allMethodsAdvice)
        val woven = readNode(outcome.bytes!!)
        assertFalse(
            woven.methods.any { it.name == "secret${AopWeaver.ORIGINAL_SUFFIX}" },
            "private 方法不应被织入（否则合成方法访问不到）"
        )
        assertTrue(
            woven.methods.any { it.name == "callSecret${AopWeaver.ORIGINAL_SUFFIX}" },
            "调用 private 方法的 public 方法应被织入"
        )
    }

    // ── helpers ──

    private fun weaveFixture(clazz: Class<*>, advices: List<ResolvedAdvice>) =
        AopWeaver.weave(bytesOf(clazz), advices)

    private fun bytesOf(clazz: Class<*>): ByteArray =
        clazz.getResourceAsStream("/" + clazz.name.replace('.', '/') + ".class")!!.readBytes()

    private fun readNode(bytes: ByteArray): ClassNode {
        val node = ClassNode()
        ClassReader(bytes).accept(node, 0)
        return node
    }

    private fun loadWoven(clazz: Class<*>, advices: List<ResolvedAdvice>): Class<*> {
        val outcome = weaveFixture(clazz, advices)
        assertNotNull(outcome.bytes, "应当发生织入")
        val loader = object : ClassLoader(clazz.classLoader) {
            fun define(name: String, bytes: ByteArray): Class<*> = defineClass(name, bytes, 0, bytes.size)
        }
        return loader.define(clazz.name, outcome.bytes!!)
    }

    private fun assertSameResult(plain: Any, woven: Any, name: String, types: Array<Class<*>?>, vararg args: Any?) {
        // 注意：织入后的类由子 ClassLoader 定义，Method 必须从各自实例的类上取
        val expected = plain.javaClass.getMethod(name, *types).invoke(plain, *args)
        val actual = woven.javaClass.getMethod(name, *types).invoke(woven, *args)
        if (expected is IntArray && actual is IntArray) {
            assertEquals(expected.toList(), actual.toList(), "$name 结果不一致")
        } else {
            assertEquals(expected, actual, "$name 结果不一致")
        }
    }
}
