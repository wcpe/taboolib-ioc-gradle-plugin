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

    private const val WOVEN_TARGET_INTERNAL = "top/wcpe/taboolib/ioc/aop/WovenTarget"
    private const val RUNTIME_INTERNAL = "top/wcpe/taboolib/ioc/aop/AopWeavingRuntime"
    private const val INVOKE_DESC =
        "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;"
    private const val OBJECT_INTERNAL = "java/lang/Object"

    /** 织入结果：字节码（null = 无需改动）与织入的方法数。 */
    data class Outcome(val bytes: ByteArray?, val wovenMethods: Int)

    fun weave(classBytes: ByteArray, advices: List<ResolvedAdvice>): Outcome {
        val node = ClassNode()
        ClassReader(classBytes).accept(node, ClassReader.EXPAND_FRAMES)

        if ((node.access and Opcodes.ACC_INTERFACE) != 0) return Outcome(null, 0)
        if (node.interfaces?.contains(WOVEN_TARGET_INTERNAL) == true) return Outcome(null, 0)
        if (hasNoAspect(node.visibleAnnotations) || hasNoAspect(node.invisibleAnnotations)) return Outcome(null, 0)
        if (node.name.endsWith("module-info") || node.name.endsWith("package-info")) return Outcome(null, 0)

        val className = node.name.replace('/', '.')
        val candidates = node.methods.filter { isWeavable(it) }
        val woven = ArrayList<MethodNode>(4)
        for (method in candidates) {
            if (hasNoAspect(method.visibleAnnotations) || hasNoAspect(method.invisibleAnnotations)) continue
            if (advices.none { it.matches(className, method.name) }) continue
            woven.add(method)
        }
        if (woven.isEmpty()) return Outcome(null, 0)

        woven.forEach { splitAndForward(node, it) }
        if (node.interfaces == null) node.interfaces = mutableListOf()
        if (!node.interfaces.contains(WOVEN_TARGET_INTERNAL)) node.interfaces.add(WOVEN_TARGET_INTERNAL)

        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        return Outcome(writer.toByteArray(), woven.size)
    }

    private fun hasNoAspect(annotations: List<org.objectweb.asm.tree.AnnotationNode>?): Boolean =
        annotations?.any { it.desc == AopWeavePlanner.NO_ASPECT_DESCRIPTOR } == true

    private fun isWeavable(method: MethodNode): Boolean {
        if ((method.access and Opcodes.ACC_PUBLIC) == 0) return false
        if ((method.access and Opcodes.ACC_STATIC) != 0) return false
        if ((method.access and Opcodes.ACC_ABSTRACT) != 0) return false
        if ((method.access and Opcodes.ACC_NATIVE) != 0) return false
        if ((method.access and (Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE)) != 0) return false
        if (method.name.startsWith("<") || method.name.startsWith("access$")) return false
        if (method.name.contains(ORIGINAL_SUFFIX)) return false
        return true
    }

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
