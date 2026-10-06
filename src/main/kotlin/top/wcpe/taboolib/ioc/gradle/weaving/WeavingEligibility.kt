package top.wcpe.taboolib.ioc.gradle.weaving

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.MethodNode

/**
 * **织入侧内部**的「方法是否可被编译期织入」方法级原语。
 *
 * ## 定位（已降级，勿再当成跨侧契约）
 *
 * 早期设计曾把它当作「诊断侧与织入侧共享的单一真源」。该定位**已被实证推翻**：方法级谓词
 * 只覆盖 **access 维度**，覆盖不到 **match 级语义**（方法声明位置 / 继承未覆写、`@NoAspect`、
 * 通配），维度会逐轮涌现。因此「单一真源」已上移到**整类织入决策层** → [WeavePlan]（由
 * [AopWeaver.plan] 产出的事实）；诊断侧**不再**引用本类，改读计划。
 *
 * 本类现在**仅供织入侧内部**（[AopWeaver.plan] / [AopWeaver.weave] 用它筛方法）。
 *
 * ## 判据（逐条对齐原 `AopWeaver.isWeavable`）
 *
 * 1. 必须具备 `ACC_PUBLIC`（`protected` / 包内可见方法不满足）；
 * 2. 排除 `ACC_STATIC`；
 * 3. 排除 `ACC_ABSTRACT`；
 * 4. 排除 `ACC_NATIVE`；
 * 5. 排除 `ACC_SYNTHETIC` / `ACC_BRIDGE`；
 * 6. 排除名称以 `<` 开头（构造器 / 类初始化）与 `access$` 开头的合成访问器；
 * 7. 排除名称含 [AopWeaver.ORIGINAL_SUFFIX]（已搬运的原方法体合成方法）。
 *
 * 依赖 [AopWeaver.ORIGINAL_SUFFIX] 常量本身，保证与织入器**逐字一致**。
 */
internal object WeavingEligibility {

    /** 用原始访问位与方法名判定织入资格（适用于尚未构造 `MethodNode` 的场景，如静态采集层）。 */
    fun isEligible(access: Int, name: String): Boolean {
        if ((access and Opcodes.ACC_PUBLIC) == 0) return false
        if ((access and Opcodes.ACC_STATIC) != 0) return false
        if ((access and Opcodes.ACC_ABSTRACT) != 0) return false
        if ((access and Opcodes.ACC_NATIVE) != 0) return false
        if ((access and (Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE)) != 0) return false
        if (name.startsWith("<") || name.startsWith("access$")) return false
        // 与 AopWeaver 的残渣判据同一口径：只有**结尾**是该后缀才是织入器产出的合成方法。
        // 用 contains 会把名字里恰好含该子串的普通方法一并排除，那些方法从此不再被织入。
        if (name.endsWith(AopWeaver.ORIGINAL_SUFFIX)) return false
        return true
    }

    /** 用 ASM `MethodNode` 判定织入资格（织入侧的调用入口）。 */
    fun isEligible(method: MethodNode): Boolean = isEligible(method.access, method.name)
}
