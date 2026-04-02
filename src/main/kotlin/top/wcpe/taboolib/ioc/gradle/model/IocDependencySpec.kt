package top.wcpe.taboolib.ioc.gradle.model

import top.wcpe.taboolib.ioc.gradle.TaboolibIocConfigurationException

internal sealed interface IocDependencySpec {
    val displayName: String
}

internal data class ModuleDependencySpec(
    val group: String,
    val name: String,
    val version: String,
) : IocDependencySpec {

    override val displayName: String = "$group:$name:$version"

    companion object {
        fun parse(notation: String): ModuleDependencySpec {
            val parts = notation.split(':')
            if (parts.size != 3 || parts.any { it.isBlank() }) {
                throw TaboolibIocConfigurationException(
                    "IoC 依赖坐标 '$notation' 不是合法的 group:name:version 形式。",
                )
            }
            return ModuleDependencySpec(parts[0], parts[1], parts[2])
        }
    }
}

internal data class ProjectDependencySpec(
    val path: String,
) : IocDependencySpec {

    override val displayName: String = "project($path)"
}