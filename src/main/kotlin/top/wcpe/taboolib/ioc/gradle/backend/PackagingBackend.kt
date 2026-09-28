package top.wcpe.taboolib.ioc.gradle.backend

import org.gradle.api.Project
import top.wcpe.taboolib.ioc.gradle.TaboolibIocResolver
import top.wcpe.taboolib.ioc.gradle.model.ResolvedIocConfiguration

internal interface PackagingBackend {

    val id: PackagingBackendId

    fun configure(
        project: Project,
        resolver: TaboolibIocResolver,
        configuration: ResolvedIocConfiguration,
    ): BackendConfigurationResult

    /**
     * 校验打包接管是否生效。
     *
     * 入参是配置阶段采集的只读快照（[BackendVerificationInput]），而不是 Project/resolver：
     * 校验发生在执行阶段，若任务持有 Project，其状态便无法被配置缓存序列化。
     */
    fun verify(input: BackendVerificationInput)
}