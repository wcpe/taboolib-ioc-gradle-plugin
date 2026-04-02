package top.wcpe.taboolib.ioc.gradle.model

import top.wcpe.taboolib.ioc.gradle.backend.PackagingBackendId

internal data class ResolvedIocConfiguration(
    val backendId: PackagingBackendId,
    val sourcePackage: String,
    val targetPackage: TargetPackageResolution,
    val dependencySpec: IocDependencySpec,
    val skipBecauseSubproject: Boolean,
)