package top.wcpe.taboolib.ioc.gradle.backend

import top.wcpe.taboolib.ioc.gradle.TaboolibIocResolver
import top.wcpe.taboolib.ioc.gradle.model.ResolvedIocConfiguration

/**
 * 打包接管校验所需的只读快照。
 *
 * 由插件在**配置阶段**采集，执行阶段仅依据该快照判定。快照只含基础类型，
 * 因此可以随任务状态一起被 Gradle 配置缓存序列化 —— 这是校验任务能够在
 * `--configuration-cache` 下正常工作的前提（持有 Project/resolver 会导致
 * 任务状态无法序列化，配置缓存无法存储）。
 */
internal data class BackendVerificationInput(
    /** 当前工程是否已应用 `io.izzel.taboolib`。 */
    val taboolibPluginApplied: Boolean,
    /** 当前工程是否因 `taboolib.subproject=true` 被跳过接管。 */
    val skipBecauseSubproject: Boolean,
    /** IoC 源包名。 */
    val sourcePackage: String,
    /** 期望的 relocate 目标包名。 */
    val expectedRelocation: String,
    /** 配置阶段实际读到的 relocate 目标包名，缺失时为 null。 */
    val actualRelocation: String?,
)

/**
 * 采集打包接管校验所需的快照。
 *
 * 采集**必须**在配置阶段完成：relocate 的实际值来自 taboolib 扩展，
 * 若推迟到执行阶段读取，就等于让校验任务间接持有 Project，配置缓存将无法存储任务状态。
 * 插件与单测都通过本函数构造快照，确保两处的取值口径一致。
 */
internal fun collectBackendVerification(
    resolver: TaboolibIocResolver,
    configuration: ResolvedIocConfiguration,
): BackendVerificationInput {
    return BackendVerificationInput(
        taboolibPluginApplied = resolver.isTaboolibPluginApplied(),
        skipBecauseSubproject = configuration.skipBecauseSubproject,
        sourcePackage = configuration.sourcePackage,
        expectedRelocation = configuration.targetPackage.relocationTarget,
        actualRelocation = resolver.readExistingRelocations()[configuration.sourcePackage],
    )
}
