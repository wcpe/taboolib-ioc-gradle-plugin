package top.wcpe.taboolib.ioc.gradle.weaving

import top.wcpe.taboolib.ioc.gradle.analysis.AspectAdviceDefinition
import top.wcpe.taboolib.ioc.gradle.analysis.AspectDefinition

/**
 * 织入计划相关模型与「切点匹配」的构建期实现。
 *
 * ## 为什么构建期要自己实现一遍匹配
 *
 * 运行期 `PointcutExpression.matches` 与这里必须**判定一致**，否则会出现
 * 「构建期织入了但运行期不匹配（白织）」或「运行期匹配但没织入（漏切）」。
 * 因此本文件的匹配规则逐条对齐 `top.wcpe.taboolib.ioc.bean.PointcutExpression`：
 * 类模式支持精确全限定名 / 精确简单名 / `包..*` 前缀 / `*`；方法模式支持精确名 / `*`。
 */
internal data class ResolvedAdvice(
    val aspectClassName: String,
    val adviceAnnotation: String,
    val adviceMethodName: String,
    val classPattern: String,
    val methodPattern: String,
) {
    fun matches(className: String, methodName: String): Boolean =
        matchesClass(className) && matchesMethod(methodName)

    private fun matchesClass(className: String): Boolean {
        if (classPattern == "*") return true
        if (classPattern.endsWith("..*")) {
            return className.startsWith(classPattern.dropLast(3))
        }
        return className == classPattern || className.substringAfterLast('.') == classPattern
    }

    private fun matchesMethod(methodName: String): Boolean =
        methodPattern == "*" || methodName == methodPattern
}

internal object AopWeavePlanner {

    /** 类级排除注解（构建期用描述符比对，此时尚未 relocate）。 */
    const val NO_ASPECT_DESCRIPTOR: String = "Ltop/wcpe/taboolib/ioc/annotation/NoAspect;"

    private const val EXECUTION_PREFIX = "execution("

    /**
     * 把静态索引里的切面定义解析成可匹配的通知列表。
     *
     * 解析规则对齐 `AspectScanner`：`@Around("somePointcut")` 这种**命名切点引用**
     * 通过 `@Pointcut` 方法名查表；表达式支持带或不带 `execution(...)` 包裹。
     */
    fun resolve(aspects: List<AspectDefinition>): List<ResolvedAdvice> {
        val resolved = ArrayList<ResolvedAdvice>(aspects.size * 2)
        for (aspect in aspects) {
            for (advice in aspect.advices) {
                val parsed = parse(aspect, advice) ?: continue
                resolved.add(
                    ResolvedAdvice(
                        aspectClassName = aspect.aspectClassName,
                        adviceAnnotation = advice.adviceAnnotation,
                        adviceMethodName = advice.methodName,
                        classPattern = parsed.first,
                        methodPattern = parsed.second,
                    )
                )
            }
        }
        return resolved
    }

    private fun parse(aspect: AspectDefinition, advice: AspectAdviceDefinition): Pair<String, String>? {
        val trimmed = advice.expression.trim()
        val resolved = if (!trimmed.contains('.') && !trimmed.contains('(')) {
            aspect.pointcutMethods[trimmed] ?: return null
        } else {
            trimmed
        }
        var expr = resolved.trim()
        if (expr.startsWith(EXECUTION_PREFIX) && expr.endsWith(")")) {
            expr = expr.substring(EXECUTION_PREFIX.length, expr.length - 1).trim()
        }
        val lastDot = expr.lastIndexOf('.')
        if (lastDot <= 0) return null
        return expr.substring(0, lastDot) to expr.substring(lastDot + 1)
    }
}
