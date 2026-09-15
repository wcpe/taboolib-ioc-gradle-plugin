package top.wcpe.taboolib.ioc.gradle.weaving

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import top.wcpe.taboolib.ioc.gradle.analysis.BytecodeBeanIndexBuilder
import java.io.File

/**
 * 编译期 AOP 织入任务（`weaveTaboolibIocAop`）。
 *
 * 默认**关闭**，需显式开启：
 *
 * ```kotlin
 * taboolibIoc {
 *     weaving(true)
 * }
 * ```
 *
 * 开启后：编译产出的类中，被切点命中的 public 实例方法会被改写为「转发到 AopWeavingRuntime」，
 * 原方法体搬到同名的合成方法里。于是**具体类（不实现接口）也能被切面命中**，
 * 且运行期不再创建代理 —— 调用开销只有一次装箱 + 一次静态跳转。
 *
 * 注释式说明：任务在 `jar` / `assemble` / `build` / `taboolibMainTask` 之前运行；
 * 与静态诊断任务用 `mustRunAfter` 排序（诊断看未织入的原始字节码）。
 */
@DisableCachingByDefault(because = "Weaves advice into compiled classes in place")
abstract class WeaveTaboolibIocAopTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classDirectories: ConfigurableFileCollection

    // 是否执行由 Gradle 原生 `Task.enabled` 控制（插件按扩展属性 `weaving` 设置），
    // 不要自定义名为 `enabled` 的属性 —— 会与 `Task.getEnabled()` 冲突。

    @TaskAction
    fun weave() {
        val roots = classDirectories.files.filter { it.isDirectory }
        if (roots.isEmpty()) {
            logger.lifecycle("[taboolibIocWeaveAop] 没有编译输出目录，跳过")
            return
        }

        val advices = try {
            AopWeavePlanner.resolve(BytecodeBeanIndexBuilder.build(roots.map { it.toPath() }).aspectIndex)
        } catch (t: Throwable) {
            logger.warn("[taboolibIocWeaveAop] 解析切面索引失败，跳过织入：${t.message}")
            return
        }
        if (advices.isEmpty()) {
            logger.lifecycle("[taboolibIocWeaveAop] 未发现任何切面通知，跳过")
            return
        }

        var wovenClasses = 0
        var wovenMethods = 0
        var failed = 0
        roots.forEach { root ->
            classFiles(root).forEach { file ->
                val outcome = try {
                    AopWeaver.weave(file.readBytes(), advices)
                } catch (t: Throwable) {
                    failed++
                    logger.warn("[taboolibIocWeaveAop] 织入失败，跳过 ${file.name}：${t.message}")
                    return@forEach
                }
                if (outcome.bytes != null) {
                    file.writeBytes(outcome.bytes)
                    wovenClasses++
                    wovenMethods += outcome.wovenMethods
                }
            }
        }
        logger.lifecycle(
            "[taboolibIocWeaveAop] 完成：织入 $wovenClasses 个类 / $wovenMethods 个方法" +
                if (failed > 0) "（$failed 个类织入失败已跳过）" else "" +
                "；切面通知 ${advices.size} 条"
        )
    }

    private fun classFiles(root: File): Sequence<File> =
        root.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .filterNot { it.name == "module-info.class" || it.name == "package-info.class" }
}
