package top.wcpe.taboolib.ioc.gradle.weaving

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.MethodNode
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
        assertSameResult(plain, woven, "tryCatchFinally", arrayOf(Int::class.javaPrimitiveType), 3)
        assertSameResult(plain, woven, "tryCatchFinally", arrayOf(Int::class.javaPrimitiveType), -1)
        assertSameResult(plain, woven, "loopSum", arrayOf(Int::class.javaPrimitiveType), 5)
        assertSameResult(plain, woven, "switchLike", arrayOf(Int::class.javaPrimitiveType), 7)
        assertSameResult(plain, woven, "switchLike", arrayOf(Int::class.javaPrimitiveType), 42)
        assertSameResult(plain, woven, "synchronizedIncrement", arrayOf<Class<*>?>())
        assertSameResult(plain, woven, "useResource", arrayOf(String::class.java), "A")

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

    /**
     * §2.4 第 6 条：**计划↔织入一致性**。`plan` 是唯一决策函数，`weave = plan + apply`，
     * 因此「计划列了哪些方法」与「实际织入了哪些方法」必须逐格相等（锁死「计划不是第二次实现」）。
     */
    @Test
    fun `计划与织入的方法集合逐格相等`() {
        val bytes = bytesOf(WeaveFixture::class.java)
        val decision = requireNotNull(AopWeaver.plan(bytes, allMethodsAdvice))
        assertEquals(WeaveOutcome.WOVEN, decision.outcome)
        assertFalse(decision.alreadyWoven)
        val plannedNames = decision.wovenMethods.map { it.methodName }.toSortedSet()

        val outcome = AopWeaver.weave(bytes, allMethodsAdvice)
        assertNotNull(outcome.bytes)
        val wovenNames = readNode(outcome.bytes!!).methods
            .filter { it.name.endsWith(AopWeaver.ORIGINAL_SUFFIX) }
            .map { it.name.removeSuffix(AopWeaver.ORIGINAL_SUFFIX) }
            .toSortedSet()

        assertEquals(plannedNames, wovenNames, "计划与织入必须由同一函数保证一致")
    }

    /** 计划对类级 `@NoAspect` 记 `SKIPPED(NO_ASPECT_CLASS)`（供诊断归因）。 */
    @Test
    fun `plan 对类级 NoAspect 记 SKIPPED`() {
        val advices = listOf(allMethodsAdvice.first().copy(classPattern = "NoAspectClassFixture"))
        val decision = requireNotNull(AopWeaver.plan(bytesOf(NoAspectClassFixture::class.java), advices))
        assertEquals(WeaveOutcome.SKIPPED, decision.outcome)
        assertEquals(WeaveSkipReason.NO_ASPECT_CLASS, decision.skipReason)
    }

    @Test
    fun `plan 不得把名字含后缀的普通方法误判为残渣`() {
        // 织入器产出的合成方法名形如 `<原名>$ioc$original`，判据必须是 endsWith。
        // 用 contains 的话，用户随手写一个名字里含该子串的方法就会让整类被判为残渣、
        // 此后永久跳过织入，而计划记成「已织入」、诊断据此抑制告警 —— 通知不执行且毫无提示。
        val node = ClassNode()
        node.version = Opcodes.V17
        node.access = Opcodes.ACC_PUBLIC
        node.name = "fixture/Trap"
        node.superName = "java/lang/Object"
        val method = MethodNode(Opcodes.ACC_PUBLIC, "handle\$ioc\$originalExtra", "()V", null, null)
        method.instructions.add(InsnNode(Opcodes.RETURN))
        method.maxStack = 0
        method.maxLocals = 1
        node.methods.add(method)
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)

        val decision = requireNotNull(
            AopWeaver.plan(
                writer.toByteArray(),
                listOf(
                    ResolvedAdvice(
                        aspectClassName = "top.wcpe.TestAspect",
                        adviceAnnotation = "Around",
                        adviceMethodName = "around",
                        classPattern = "*",
                        methodPattern = "*",
                    )
                ),
            )
        )
        assertFalse(
            decision.alreadyWoven,
            "名字只是含该子串、并非织入器产出的合成方法，不得让整类被判为残渣",
        )
    }

    /**
     * §2.4 第 11 条（锁定假阳性症状 2）：读到「已织入字节」时，`plan` 必须记
     * `outcome=WOVEN, alreadyWoven=true`（**绝不是 `SKIPPED`**，`skipReason` 不得为 `ALREADY_WOVEN`），
     * 且 `wovenMethods` 由 `*\$ioc\$original` 合成方法重建。
     */
    @Test
    fun `plan 对已织入残渣记 WOVEN 而非 SKIPPED`() {
        val first = AopWeaver.weave(bytesOf(WeaveFixture::class.java), allMethodsAdvice)
        assertNotNull(first.bytes)

        val decision = requireNotNull(AopWeaver.plan(first.bytes!!, allMethodsAdvice))
        assertEquals(WeaveOutcome.WOVEN, decision.outcome, "残渣必须记为 WOVEN")
        assertTrue(decision.alreadyWoven, "残渣应带 alreadyWoven=true 审计位")
        assertNull(decision.skipReason, "残渣的 skipReason 必须为 null（不得有 ALREADY_WOVEN）")
        assertTrue(decision.wovenMethods.isNotEmpty(), "应从合成原方法重建 wovenMethods")
        assertTrue(
            decision.wovenMethods.any { it.methodName == "greet" },
            "重建的方法名应去掉合成后缀",
        )
    }

    @Test
    fun `切面类自身不被织入`() {
        // 通配切点的 classPattern 是 `*`，切面自己的通知方法同样满足资格判据。织入后运行期反射
        // 调用通知会落在转发体上、再次按注册表匹配到同一个通知 —— 通知体被多套一层，实现里再反射
        // 调用通知就是自递归。这里用 WeaveFixture 冒充切面：判据是「类名命中某条通知的 aspectClassName」。
        val advice = listOf(
            ResolvedAdvice(
                aspectClassName = WeaveFixture::class.java.name,
                adviceAnnotation = "Around",
                adviceMethodName = "around",
                classPattern = "*",
                methodPattern = "*",
            ),
        )
        val decision = requireNotNull(AopWeaver.plan(bytesOf(WeaveFixture::class.java), advice))
        assertEquals(WeaveOutcome.SKIPPED, decision.outcome)
        assertEquals(WeaveSkipReason.ASPECT_CLASS, decision.skipReason)
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
