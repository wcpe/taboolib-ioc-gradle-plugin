package top.wcpe.taboolib.ioc.gradle.weaving

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode

/**
 * 编译期 AOP 织入器（ASM）。
 *
 * ## 织入形态：把原方法体搬到合成方法，原方法改为转发
 *
 * ```java
 * // 织入前
 * public int echo(int v) { return v + seed; }
 *
 * // 织入后
 * public int echo(int v) {
 *     return ((Integer) AopWeavingRuntime.invoke(this, "echo", "echo$ioc$original", "(I)I",
 *             new Object[]{Integer.valueOf(v)})).intValue();
 * }
 * public synthetic int echo$ioc$original(int v) { return v + seed; }
 * ```
 *
 * 为什么这样最划算：
 * - **不需要子类**，因此 `final` 类 / `final` 方法 / 私有构造器统统不是问题（这正是它优于
 *   CGLIB 式子类代理的地方）；
 * - **不需要 ClassLoader 去解析继承关系**（读时 EXPAND_FRAMES + 写时 COMPUTE_MAXS，
 *   帧原样搬运），因此可在纯构建期离线完成；
 * - 具体类（不实现任何接口）因此也能被切面命中。
 *
 * ## 幂等性
 *
 * 类一旦织入就会实现 [WOVEN_TARGET_INTERNAL]，再次织入会被直接跳过；
 * 方法名带 `$ioc$original` 的合成方法也不会被二次处理。因此构建任务可安全重跑。
 *
 * ## 不做的事
 *
 * - 不织入 `static` / `private` / `abstract` / `native` / 合成（桥接）方法，也不织入构造器；
 * - 不织入带 `@NoAspect` 的类与方法；
 * - 合成方法上的注解一律**清除**，避免容器把 `@PostConstruct` / `@Inject` 之类再认一遍。
 */
internal object AopWeaver {

    /** 与运行期 `AopWeavingRuntime.ORIGINAL_SUFFIX` 必须完全一致。 */
    const val ORIGINAL_SUFFIX: String = "\$ioc\$original"

    private const val RUNTIME_INTERNAL = "top/wcpe/taboolib/ioc/aop/AopWeavingRuntime"
    private const val INVOKE_DESC =
        "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;"
    private const val OBJECT_INTERNAL = "java/lang/Object"

    /** 织入结果：字节码（null = 无需改动）与织入的方法数。 */
    data class Outcome(val bytes: ByteArray?, val wovenMethods: Int)

    /**
     * **唯一决策函数**（无副作用，不写回字节码）：给定字节码与通知，判定该类是否会织入、织入哪些方法。
     *
     * `weave()` 必须**先调用它**、再按 `outcome == WOVEN` 机械落地（`weave = plan + apply`）。
     * 这样「计划列了什么」与「实际织入了什么」由**同一个函数**保证一致，而不是靠两份逻辑 + 测试兜底。
     *
     * @return 与 AOP 相关的类的决策；`null` 表示该类**与 AOP 无关**（未被任何通知的类模式命中、
     *   非类级 `@NoAspect`、非残渣），不登记进计划（使报告规模与「切点目标数」同阶）。
     */
    fun plan(classBytes: ByteArray, advices: List<ResolvedAdvice>): ClassWeaveDecision? {
        val node = ClassNode()
        ClassReader(classBytes).accept(node, ClassReader.EXPAND_FRAMES)
        val className = node.name.replace('/', '.')

        // 残渣（上一轮就地织入留下的字节）：类已实现 WovenTarget，或存在 *$ioc$original 合成方法。
        // 语义是「该类确已被织入」→ outcome=WOVEN + alreadyWoven=true（**绝不是 SKIPPED**）。
        // 判据必须是 endsWith：织入器产出的名字是 `<原名><后缀>`。用 contains 的话，用户随手写一个
        // 名字里含该子串的方法（`fun handle$ioc$originalExtra()`）就会让整类被判为残渣、
        // 此后永久跳过织入，且计划记成「已织入」、诊断据此抑制告警 —— 通知不执行且毫无提示。
        val hasWovenMarker = node.interfaces?.contains(WEAVE_TARGET_INTERNAL) == true
        val hasOriginalMethod = node.methods.any { it.name.endsWith(ORIGINAL_SUFFIX) }
        if (hasWovenMarker || hasOriginalMethod) {
            return ClassWeaveDecision(
                className = className,
                outcome = WeaveOutcome.WOVEN,
                alreadyWoven = true,
                skipReason = null,
                wovenMethods = residueWovenMethods(node, advices, className),
            )
        }

        // 切面类自身不参与织入：通配切点的 classPattern 是 `*`，切面自己的通知方法同样满足资格
        // 判据。织入后运行期反射调用通知会落在转发体上、再次进入 invoke 并按当前注册表匹配到同一个
        // 通知，通知体被多套一层；实现里再反射调用通知方法就是自递归。诊断侧本就显式跳过切面 Bean
        // （「切面 Bean 自身不被代理」），这里对齐同一口径。
        // 放在残渣判定**之后**：已经织入过的类仍应如实记为 WOVEN，而不是被改判成 SKIPPED。
        // 切面清单直接由 advices 自带的 aspectClassName 推出，与计划侧同源，无需另加签名参数。
        if (className in advices.map { it.aspectClassName }.toSet()) {
            return ClassWeaveDecision(
                className = className,
                outcome = WeaveOutcome.SKIPPED,
                skipReason = WeaveSkipReason.ASPECT_CLASS,
            )
        }

        if (node.name.endsWith("module-info") || node.name.endsWith("package-info")) {
            return ClassWeaveDecision(
                className = className,
                outcome = WeaveOutcome.SKIPPED,
                skipReason = WeaveSkipReason.MODULE_INFO,
            )
        }

        val classNoAspect = hasNoAspect(node.visibleAnnotations) || hasNoAspect(node.invisibleAnnotations)
        val matchedByClassPattern = advices.any { it.matchesClass(className) }
        if (!classNoAspect && !matchedByClassPattern) {
            return null // 与 AOP 无关的普通类：不登记
        }

        if ((node.access and Opcodes.ACC_INTERFACE) != 0) {
            return ClassWeaveDecision(
                className = className,
                outcome = WeaveOutcome.SKIPPED,
                skipReason = WeaveSkipReason.INTERFACE,
            )
        }
        if (classNoAspect) {
            return ClassWeaveDecision(
                className = className,
                outcome = WeaveOutcome.SKIPPED,
                skipReason = WeaveSkipReason.NO_ASPECT_CLASS,
            )
        }

        val eligible = node.methods.filter { isWeavable(it) }
        val woven = eligible.filter { method ->
            !hasNoAspect(method.visibleAnnotations) &&
                !hasNoAspect(method.invisibleAnnotations) &&
                advices.any { it.matches(className, method.name) }
        }
        if (woven.isEmpty()) {
            // 方法级 @NoAspect 把唯一匹配的方法排除掉时，归因到 @NoAspect（否则归因到「无可织入方法」）。
            val noAspectExcluded = eligible.any { method ->
                (hasNoAspect(method.visibleAnnotations) || hasNoAspect(method.invisibleAnnotations)) &&
                    advices.any { it.matches(className, method.name) }
            }
            return ClassWeaveDecision(
                className = className,
                outcome = WeaveOutcome.SKIPPED,
                skipReason = if (noAspectExcluded) {
                    WeaveSkipReason.NO_ASPECT_CLASS
                } else {
                    WeaveSkipReason.NO_ELIGIBLE_MATCHING_METHOD
                },
            )
        }
        return ClassWeaveDecision(
            className = className,
            outcome = WeaveOutcome.WOVEN,
            alreadyWoven = false,
            skipReason = null,
            wovenMethods = woven.map { method ->
                WovenMethod(
                    methodName = method.name,
                    descriptor = method.desc,
                    matchedAdvices = matchedAdviceKeys(advices, className, method.name),
                )
            },
        )
    }

    /**
     * `weave = plan + apply`：先取 [plan] 的决策，**仅当** `outcome == WOVEN` 且非残渣时按该决策机械落地。
     * 本函数**不得**自带第二套 `isWeavable` / `matches` / `@NoAspect` 判断。
     */
    fun weave(classBytes: ByteArray, advices: List<ResolvedAdvice>): Outcome {
        val decision = plan(classBytes, advices) ?: return Outcome(null, 0)
        if (decision.outcome != WeaveOutcome.WOVEN || decision.alreadyWoven) return Outcome(null, 0)
        if (decision.wovenMethods.isEmpty()) return Outcome(null, 0)

        val node = ClassNode()
        ClassReader(classBytes).accept(node, ClassReader.EXPAND_FRAMES)
        val targets = decision.wovenMethods.mapNotNull { woven ->
            node.methods.firstOrNull { it.name == woven.methodName && it.desc == woven.descriptor }
        }
        if (targets.isEmpty()) return Outcome(null, 0)

        targets.forEach { splitAndForward(node, it) }
        if (node.interfaces == null) node.interfaces = mutableListOf()
        if (!node.interfaces.contains(WEAVE_TARGET_INTERNAL)) node.interfaces.add(WEAVE_TARGET_INTERNAL)

        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        return Outcome(writer.toByteArray(), targets.size)
    }

    /** 计划键：`<切面FQCN>#<通知方法名>`（与诊断侧 `expected` 的构造逐字一致）。 */
    private fun matchedAdviceKeys(advices: List<ResolvedAdvice>, className: String, methodName: String): List<String> =
        advices.filter { it.matches(className, methodName) }
            .map { "${it.aspectClassName}#${it.adviceMethodName}" }

    /** 从残渣字节重建 `wovenMethods`：扫描 `*$ioc$original` 合成方法，用同一 matcher 重匹配通知键。 */
    private fun residueWovenMethods(
        node: ClassNode,
        advices: List<ResolvedAdvice>,
        className: String,
    ): List<WovenMethod> =
        node.methods
            .filter { it.name.endsWith(ORIGINAL_SUFFIX) }
            .map { method ->
                val originalName = method.name.removeSuffix(ORIGINAL_SUFFIX)
                WovenMethod(
                    methodName = originalName,
                    descriptor = method.desc,
                    matchedAdvices = matchedAdviceKeys(advices, className, originalName)
                        .ifEmpty { listOf(ALREADY_WOVEN_SENTINEL) },
                )
            }

    private fun hasNoAspect(annotations: List<org.objectweb.asm.tree.AnnotationNode>?): Boolean =
        annotations?.any { it.desc == AopWeavePlanner.NO_ASPECT_DESCRIPTOR } == true

    private fun isWeavable(method: MethodNode): Boolean = WeavingEligibility.isEligible(method)

    /** 把原方法体搬到合成方法，并让原方法转为调用运行期入口。 */
    private fun splitAndForward(owner: ClassNode, method: MethodNode) {
        val original = MethodNode(
            method.access or Opcodes.ACC_SYNTHETIC,
            method.name + ORIGINAL_SUFFIX,
            method.desc,
            method.signature,
            method.exceptions?.toTypedArray(),
        )
        // 指令 / 异常表 / 局部变量表整体搬走（此处是**移交引用**，随后把原方法的槽位清空）
        original.instructions = method.instructions
        original.tryCatchBlocks = method.tryCatchBlocks
        original.localVariables = method.localVariables
        original.maxStack = method.maxStack
        original.maxLocals = method.maxLocals
        // 合成方法上不能留注解，否则容器会把 @PostConstruct / @Inject 等再认一遍
        original.visibleAnnotations = null
        original.invisibleAnnotations = null
        original.visibleParameterAnnotations = null
        original.invisibleParameterAnnotations = null
        original.visibleTypeAnnotations = null
        original.invisibleTypeAnnotations = null

        // 原方法保留自身注解（@NoAspect 等运行期仍需可见），只把方法体换成转发
        method.instructions = buildForwardInstructions(method)
        method.tryCatchBlocks = mutableListOf()
        method.localVariables = null
        method.maxStack = 0
        method.maxLocals = 0
        method.visibleTypeAnnotations = null
        method.invisibleTypeAnnotations = null

        owner.methods.add(original)
    }

    private fun buildForwardInstructions(method: MethodNode): InsnList {
        val insns = InsnList()
        val args = Type.getArgumentTypes(method.desc)
        val returnType = Type.getReturnType(method.desc)

        // key = 方法名 + 描述符：构建期就算好，运行期直接查表（避免每次调用反射/拼接字符串）
        insns.add(VarInsnNode(Opcodes.ALOAD, 0))
        insns.add(LdcInsnNode(method.name + method.desc))
        insns.add(LdcInsnNode(method.name + ORIGINAL_SUFFIX))

        if (args.isEmpty()) {
            insns.add(InsnNode(Opcodes.ACONST_NULL))
        } else {
            insns.add(intConstant(args.size))
            insns.add(TypeInsnNode(Opcodes.ANEWARRAY, OBJECT_INTERNAL))
            var slot = 1
            args.forEachIndexed { index, type ->
                insns.add(InsnNode(Opcodes.DUP))
                insns.add(intConstant(index))
                insns.add(VarInsnNode(type.getOpcode(Opcodes.ILOAD), slot))
                boxIfNeeded(insns, type)
                insns.add(InsnNode(Opcodes.AASTORE))
                slot += type.size
            }
        }

        insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME_INTERNAL, "invoke", INVOKE_DESC, false))
        appendReturn(insns, returnType)
        return insns
    }

    private fun appendReturn(insns: InsnList, returnType: Type) {
        when (returnType.sort) {
            Type.VOID -> {
                insns.add(InsnNode(Opcodes.POP))
                insns.add(InsnNode(Opcodes.RETURN))
            }

            Type.BOOLEAN -> unbox(insns, "java/lang/Boolean", "booleanValue", "()Z", Opcodes.IRETURN)
            Type.CHAR -> unbox(insns, "java/lang/Character", "charValue", "()C", Opcodes.IRETURN)
            Type.BYTE -> unbox(insns, "java/lang/Byte", "byteValue", "()B", Opcodes.IRETURN)
            Type.SHORT -> unbox(insns, "java/lang/Short", "shortValue", "()S", Opcodes.IRETURN)
            Type.INT -> unbox(insns, "java/lang/Integer", "intValue", "()I", Opcodes.IRETURN)
            Type.LONG -> unbox(insns, "java/lang/Long", "longValue", "()J", Opcodes.LRETURN)
            Type.FLOAT -> unbox(insns, "java/lang/Float", "floatValue", "()F", Opcodes.FRETURN)
            Type.DOUBLE -> unbox(insns, "java/lang/Double", "doubleValue", "()D", Opcodes.DRETURN)

            else -> {
                insns.add(TypeInsnNode(Opcodes.CHECKCAST, returnType.internalName))
                insns.add(InsnNode(Opcodes.ARETURN))
            }
        }
    }

    private fun unbox(insns: InsnList, owner: String, method: String, desc: String, opcode: Int) {
        insns.add(TypeInsnNode(Opcodes.CHECKCAST, owner))
        insns.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, method, desc, false))
        insns.add(InsnNode(opcode))
    }

    private fun boxIfNeeded(insns: InsnList, type: Type) {
        when (type.sort) {
            Type.BOOLEAN -> box(insns, "java/lang/Boolean", "(Z)Ljava/lang/Boolean;")
            Type.CHAR -> box(insns, "java/lang/Character", "(C)Ljava/lang/Character;")
            Type.BYTE -> box(insns, "java/lang/Byte", "(B)Ljava/lang/Byte;")
            Type.SHORT -> box(insns, "java/lang/Short", "(S)Ljava/lang/Short;")
            Type.INT -> box(insns, "java/lang/Integer", "(I)Ljava/lang/Integer;")
            Type.LONG -> box(insns, "java/lang/Long", "(J)Ljava/lang/Long;")
            Type.FLOAT -> box(insns, "java/lang/Float", "(F)Ljava/lang/Float;")
            Type.DOUBLE -> box(insns, "java/lang/Double", "(D)Ljava/lang/Double;")
            else -> Unit
        }
    }

    private fun box(insns: InsnList, owner: String, desc: String) {
        insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, owner, "valueOf", desc, false))
    }

    private fun intConstant(value: Int): org.objectweb.asm.tree.AbstractInsnNode = when (value) {
        in 0..5 -> InsnNode(Opcodes.ICONST_0 + value)
        in Byte.MIN_VALUE..Byte.MAX_VALUE -> IntInsnNode(Opcodes.BIPUSH, value)
        in Short.MIN_VALUE..Short.MAX_VALUE -> IntInsnNode(Opcodes.SIPUSH, value)
        else -> LdcInsnNode(value)
    }
}
