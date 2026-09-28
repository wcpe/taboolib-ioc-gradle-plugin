package top.wcpe.taboolib.ioc.gradle.task

import org.gradle.api.DefaultTask
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * 打印 TabooLib IoC 的解析与接管诊断信息（`taboolibIocDoctor`）。
 *
 * 诊断文本由插件在**配置阶段**生成后写入 [diagnosticLines]，执行阶段只负责输出。
 * 这样任务不必持有 `Project`/resolver，可在 Gradle 配置缓存下正常工作；
 * 同时保证配置缓存命中（任务不重跑）时诊断内容仍与当次配置一致。
 */
@DisableCachingByDefault(because = "Only prints diagnostics, produces no cacheable output")
abstract class TaboolibIocDoctorTask : DefaultTask() {

    /** 逐行输出的诊断文本，行内容与顺序由插件在配置阶段决定。 */
    @get:Input
    abstract val diagnosticLines: ListProperty<String>

    @TaskAction
    fun report() {
        diagnosticLines.get().forEach { line -> logger.lifecycle(line) }
    }
}
