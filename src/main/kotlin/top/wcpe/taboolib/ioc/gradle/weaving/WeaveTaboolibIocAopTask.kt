package top.wcpe.taboolib.ioc.gradle.weaving

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import top.wcpe.taboolib.ioc.gradle.analysis.BytecodeBeanIndexBuilder
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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

    /**
     * 织入计划（`aop-weave-plan.json`）——**仅用于日志对账**，不参与织入决策（决策恒由 [AopWeaver] 做出）。
     *
     * 由插件在 `weaving=true` 时设置为 `planTaboolibIocAop` 的输出（携带任务依赖 + 顺序保证）；
     * `weaving=false` 时不设置（`@Optional`）。读取失败 / 缺失不影响织入，仅省略日志中的计划计数。
     */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val planFile: RegularFileProperty

    // 是否执行由 Gradle 原生 `Task.enabled` 控制（插件按扩展属性 `weaving` 设置），
    // 不要自定义名为 `enabled` 的属性 —— 会与 `Task.getEnabled()` 冲突。

    @TaskAction
    fun weave() {
        val roots = classDirectories.files.filter { it.isDirectory }
        if (roots.isEmpty()) {
            logger.lifecycle("[taboolibIocWeaveAop] 没有编译输出目录，跳过")
            return
        }
        val plannedWovenClasses = planFile.orNull
            ?.asFile
            ?.toPath()
            ?.let { WeavePlanJson.read(it) }
            ?.wovenClassCount

        val advices = try {
            AopWeavePlanner.resolve(BytecodeBeanIndexBuilder.build(roots.map { it.toPath() }).aspectIndex)
        } catch (t: Throwable) {
            // 同样不能只 warn 后跳过：切面索引建不出来意味着这次构建**一个类都不会被织入**，
            // 而计划早已写出并仍宣称 WOVEN，诊断据此抑制 aop-target-not-proxied —— 依然静默。
            // 索引侧对单个读不动的 class 没有容错，所以这条路径很容易被一个损坏文件触发。
            throw GradleException("[taboolibIocWeaveAop] 解析切面索引失败，无法织入：${t.message}", t)
        }
        if (advices.isEmpty()) {
            logger.lifecycle("[taboolibIocWeaveAop] 未发现任何切面通知，跳过")
            return
        }

        var wovenClasses = 0
        var wovenMethods = 0
        val failures = mutableListOf<String>()
        roots.forEach { root ->
            classFiles(root).forEach { file ->
                val outcome = try {
                    AopWeaver.weave(file.readBytes(), advices)
                } catch (t: Throwable) {
                    failures += "${file.name}：${t.message}"
                    return@forEach
                }
                if (outcome.bytes != null) {
                    writeAtomically(file, outcome.bytes)
                    wovenClasses++
                    wovenMethods += outcome.wovenMethods
                }
            }
        }
        val planSuffix = plannedWovenClasses?.let { "（计划 $it 个类）" }.orEmpty()
        // 先算出片段再拼接：写成 `a + if (c) x else "" + z` 时 else 分支会把 `+ z` 一起吞掉，
        // 结果是「恰好发生织入失败时反而丢掉切面通知条数」——最需要信息的时候信息最少。
        val failureSuffix = if (failures.isEmpty()) "" else "（${failures.size} 个类织入失败）"
        logger.lifecycle(
            "[taboolibIocWeaveAop] 完成：织入 $wovenClasses 个类$planSuffix / $wovenMethods 个方法" +
                "$failureSuffix；切面通知 ${advices.size} 条",
        )

        // 织入失败必须让构建失败。诊断侧的 `aop-target-not-proxied` 抑制**只依据计划**，而计划在织入
        // **之前**就已写出：容忍失败的话，失败的类在计划里仍是 WOVEN，抑制照旧生效、报告一片干净，
        // 用户拿到的是「切面静默不执行且毫无提示」的产物。
        if (failures.isNotEmpty()) {
            val detail = failures.take(5).joinToString("；") +
                (if (failures.size > 5) "；……另有 ${failures.size - 5} 个" else "")
            throw GradleException("[taboolibIocWeaveAop] ${failures.size} 个类织入失败，已中止构建：$detail")
        }
    }

    /** 先写同目录临时文件再原子改名：中途失败不会留下半截 class 让后续构建只能跳过它。 */
    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeBytes(bytes)
        Files.move(
            temp.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    private fun classFiles(root: File): Sequence<File> =
        root.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .filterNot { it.name == "module-info.class" || it.name == "package-info.class" }
}
