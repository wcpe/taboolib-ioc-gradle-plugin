package top.wcpe.taboolib.ioc.gradle.task

import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import top.wcpe.taboolib.ioc.gradle.TaboolibIocConfigurationException
import top.wcpe.taboolib.ioc.gradle.backend.BackendVerificationInput
import top.wcpe.taboolib.ioc.gradle.backend.PackagingBackendId
import top.wcpe.taboolib.ioc.gradle.backend.StandaloneBackend
import top.wcpe.taboolib.ioc.gradle.backend.TabooLibBackend

/**
 * 校验 IoC 自动接管是否已在打包（`jar` / `assemble` / `build`）前生效。
 *
 * 所有判定输入都由插件在**配置阶段**采集为基本类型属性，执行阶段不再访问
 * `Task.project` 与 resolver，因此任务状态可被 Gradle 配置缓存序列化。
 * 不要在这些属性之外引入任何持有 `Project` 的字段 —— 那会让配置缓存存储失败。
 */
@DisableCachingByDefault(because = "Only verifies packaging takeover, produces no cacheable output")
abstract class VerifyTaboolibIocTask : DefaultTask() {

    /** 是否启用自动接管（`taboolibIoc.autoTakeover`）。 */
    @get:Input
    abstract val autoTakeover: Property<Boolean>

    /** IoC 配置解析失败的原因；仅在解析失败时存在。 */
    @get:Input
    @get:Optional
    abstract val resolutionFailureMessage: Property<String>

    /** 解析成功时命中的打包后端。 */
    @get:Input
    @get:Optional
    abstract val backendId: Property<PackagingBackendId>

    /** 自动接管是否已完成配置。 */
    @get:Input
    @get:Optional
    abstract val backendConfigured: Property<Boolean>

    /** 自动接管未完成时的原因。 */
    @get:Input
    @get:Optional
    abstract val backendMessage: Property<String>

    /** 是否因 `taboolib.subproject=true` 跳过当前子模块的接管。 */
    @get:Input
    @get:Optional
    abstract val skipBecauseSubproject: Property<Boolean>

    /** 当前工程是否已应用 `io.izzel.taboolib`。 */
    @get:Input
    @get:Optional
    abstract val taboolibPluginApplied: Property<Boolean>

    /** IoC 源包名。 */
    @get:Input
    @get:Optional
    abstract val sourcePackage: Property<String>

    /** 期望的 relocate 目标包名。 */
    @get:Input
    @get:Optional
    abstract val expectedRelocation: Property<String>

    /** 配置阶段实际读到的 relocate 目标包名，缺失时不设置。 */
    @get:Input
    @get:Optional
    abstract val actualRelocation: Property<String>

    @TaskAction
    fun verify() {
        if (!autoTakeover.getOrElse(true)) {
            logger.lifecycle("taboolibIoc.autoTakeover=false，已跳过自动接管验证。")
            return
        }

        resolutionFailureMessage.orNull?.let { failure ->
            throw TaboolibIocConfigurationException(failure)
        }

        val resolvedBackendId = backendId.orNull
        if (resolvedBackendId == null) {
            throw TaboolibIocConfigurationException(
                backendMessage.getOrElse("IoC 自动接管尚未完成配置。"),
            )
        }
        if (skipBecauseSubproject.getOrElse(false)) {
            logger.lifecycle("taboolib.subproject=true，已跳过当前子模块的 IoC 自动接管验证。")
            return
        }
        if (!backendConfigured.getOrElse(false)) {
            throw TaboolibIocConfigurationException(
                backendMessage.getOrElse("IoC 自动接管尚未完成配置。"),
            )
        }

        val backend = when (resolvedBackendId) {
            PackagingBackendId.TABOOLIB -> TabooLibBackend
            PackagingBackendId.STANDALONE -> StandaloneBackend
        }
        backend.verify(
            BackendVerificationInput(
                taboolibPluginApplied = taboolibPluginApplied.getOrElse(false),
                skipBecauseSubproject = skipBecauseSubproject.getOrElse(false),
                sourcePackage = sourcePackage.getOrElse("<unknown>"),
                expectedRelocation = expectedRelocation.getOrElse("<unknown>"),
                actualRelocation = actualRelocation.orNull,
            ),
        )
    }
}
