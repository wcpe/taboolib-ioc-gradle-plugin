package top.wcpe.taboolib.ioc.gradle.weaving

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import top.wcpe.taboolib.ioc.gradle.analysis.BytecodeBeanIndexBuilder
import java.io.File

/**
 * 织入计划任务（`planTaboolibIocAop`）。
 *
 * 对 `classDirectories` 中每个类调用 [AopWeaver.plan]（**只读决策、绝不写回字节码**），
 * 把结果落盘为 `build/taboolib-ioc/aop-weave-plan.json`。该文件是
 * `aop-target-not-proxied` 抑制判据的**唯一事实来源**（诊断侧读它，不再预测引擎行为）。
 *
 * 关键顺序：`plan → analyze → weave`。`plan` 必须早于两者——它必须读**原始**字节码；
 * `analyze` 也必须早于 `weave`（读原始字节码）。
 *
 * 默认**关闭**（由插件按扩展属性 `weaving` 设置 `Task.enabled`），避免 `weaving=false` 时改变任务图。
 *
 * 残渣：若读到的是上一轮就地织入留下的字节，[AopWeaver.plan] 会识别为
 * `outcome=WOVEN, alreadyWoven=true`（**不是 SKIPPED**），从而不会对诊断产生假阳性。
 *
 * ## 为什么本任务不把 `weaving` 声明为 `@Input`（约定，勿"顺手修"）
 *
 * 本任务的重跑**不**靠 `weaving` 输入变化触发，而是靠：
 * 1. `Task.enabled` 翻转（插件按扩展属性 `weaving` 设置 `enabled`）：`false` 时任务直接 SKIPPED、
 *    重新启用后会执行；
 * 2. `classDirectories`（`@InputFiles`）内容变更。
 *
 * 计划的**输出**是幂等的（`aop-weave-plan.json` 内容只由 `classDirectories` + 切面索引决定），
 * 故 `weaving` 变 true→false→true 且源码未变时，即便计划文件被判 UP-TO-DATE 也仍然有效；
 * 且 `analyzeTaboolibIocBeans` 仅在 `weaving=true` 时消费该文件（`weaving=false` 时磁盘上的旧计划被忽略）。
 * 后人若给本任务补 `weaving` `@Input`，请确认不是在修一个并不存在的 bug。
 */
@DisableCachingByDefault(because = "Reads compiled classes and writes the AOP weaving plan")
abstract class PlanTaboolibIocAopTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classDirectories: ConfigurableFileCollection

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun plan() {
        val outputPath = reportFile.get().asFile.toPath()
        val advices = resolveAdvices()
        val decisions = if (advices.isEmpty()) emptyList() else collectDecisions(advices)

        // 始终落盘（即使 0 条通知 / 0 个类）：`analyze` 把该文件作为 @InputFile，必须存在。
        WeavePlanJson.write(WeavePlan(adviceCount = advices.size, classes = decisions), outputPath)

        val wovenCount = decisions.count { it.outcome == WeaveOutcome.WOVEN }
        logger.lifecycle(
            "[taboolibIocPlanAop] 织入计划已产出：通知 ${advices.size} 条，" +
                "织入类 $wovenCount / 登记类 ${decisions.size}；plan=$outputPath"
        )
    }

    private fun resolveAdvices(): List<ResolvedAdvice> {
        val roots = classDirectories.files.filter { it.isDirectory }
        if (roots.isEmpty()) {
            logger.lifecycle("[taboolibIocPlanAop] 没有编译输出目录，产出空计划")
            return emptyList()
        }
        return try {
            AopWeavePlanner.resolve(BytecodeBeanIndexBuilder.build(roots.map { it.toPath() }).aspectIndex)
        } catch (t: Throwable) {
            logger.warn("[taboolibIocPlanAop] 解析切面索引失败，产出空计划（诊断将保守不抑制）：${t.message}")
            emptyList()
        }
    }

    private fun collectDecisions(advices: List<ResolvedAdvice>): List<ClassWeaveDecision> {
        val decisions = mutableListOf<ClassWeaveDecision>()
        classDirectories.files.filter { it.isDirectory }.forEach { root ->
            classFiles(root).forEach { file ->
                val decision = try {
                    AopWeaver.plan(file.readBytes(), advices)
                } catch (t: Throwable) {
                    logger.warn("[taboolibIocPlanAop] 读取/解析失败，跳过 ${file.name}：${t.message}")
                    null
                }
                if (decision != null) {
                    decisions += decision
                }
            }
        }
        return decisions
    }

    private fun classFiles(root: File): Sequence<File> =
        root.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
}
