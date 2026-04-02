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

    fun verify(
        project: Project,
        resolver: TaboolibIocResolver,
        configuration: ResolvedIocConfiguration,
    )
}