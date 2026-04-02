package top.wcpe.taboolib.ioc.gradle.backend

import org.gradle.api.Project
import top.wcpe.taboolib.ioc.gradle.TaboolibIocConfigurationException
import top.wcpe.taboolib.ioc.gradle.TaboolibIocResolver
import top.wcpe.taboolib.ioc.gradle.model.ResolvedIocConfiguration

internal object StandaloneBackend : PackagingBackend {

    override val id: PackagingBackendId = PackagingBackendId.STANDALONE

    override fun configure(
        project: Project,
        resolver: TaboolibIocResolver,
        configuration: ResolvedIocConfiguration,
    ): BackendConfigurationResult {
        return BackendConfigurationResult(
            configured = false,
            message = "StandaloneBackend 仅保留为后续扩展点，当前版本尚未实现独立打包引擎。",
        )
    }

    override fun verify(
        project: Project,
        resolver: TaboolibIocResolver,
        configuration: ResolvedIocConfiguration,
    ) {
        throw TaboolibIocConfigurationException(
            "StandaloneBackend 仅保留接口边界。请改用 backend = TABOOLIB，或暂时关闭 autoTakeover。",
        )
    }
}