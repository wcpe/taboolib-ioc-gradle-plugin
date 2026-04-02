package top.wcpe.taboolib.ioc.gradle

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import top.wcpe.taboolib.ioc.gradle.backend.PackagingBackendId
import top.wcpe.taboolib.ioc.gradle.model.IocDependencySpec
import top.wcpe.taboolib.ioc.gradle.model.ModuleDependencySpec
import top.wcpe.taboolib.ioc.gradle.model.ProjectDependencySpec
import top.wcpe.taboolib.ioc.gradle.model.ResolvedIocConfiguration
import top.wcpe.taboolib.ioc.gradle.model.TargetPackageResolution

internal class TaboolibIocResolver(
    private val project: Project,
    private val extension: TaboolibIocExtension,
) {

    fun resolve(): ResolvedIocConfiguration {
        return ResolvedIocConfiguration(
            backendId = extension.backend.get(),
            sourcePackage = SOURCE_PACKAGE,
            targetPackage = resolveTargetPackage(),
            dependencySpec = resolveDependencySpec(),
            skipBecauseSubproject = isTaboolibSubproject(),
        )
    }

    fun isTaboolibPluginApplied(): Boolean {
        return project.pluginManager.hasPlugin(TABOOLIB_PLUGIN_ID)
    }

    fun isTaboolibSubproject(): Boolean {
        val extensionObject = locateTaboolibExtension() ?: return false
        return ReflectionSupport.readProperty(extensionObject, "subproject") as? Boolean ?: false
    }

    fun locateTaboolibExtension(): Any? {
        return project.extensions.findByName(TABOOLIB_EXTENSION_NAME)
    }

    fun locateTabooConfiguration(): Configuration? {
        return project.configurations.findByName(TABOO_CONFIGURATION_NAME)
    }

    fun readExistingRelocations(): Map<String, String> {
        val extensionObject = locateTaboolibExtension() ?: return emptyMap()
        val rawRelocations = ReflectionSupport.readProperty(extensionObject, "relocation")
            ?: ReflectionSupport.readProperty(extensionObject, "relocations")
            ?: return emptyMap()
        if (rawRelocations !is Map<*, *>) {
            return emptyMap()
        }
        return rawRelocations.entries
            .filter { it.key is String && it.value is String }
            .associate { it.key as String to it.value as String }
    }

    fun readTaboolibEnvGroup(): String? {
        val extensionObject = locateTaboolibExtension() ?: return null
        val env = ReflectionSupport.readProperty(extensionObject, "env") ?: return null
        return ReflectionSupport.readProperty(env, "group")?.toString()?.trim().takeUnless { it.isNullOrEmpty() }
    }

    private fun resolveTargetPackage(): TargetPackageResolution {
        val explicitTarget = extension.targetPackage.orNull?.trim().takeUnless { it.isNullOrEmpty() }
        if (explicitTarget != null) {
            return TargetPackageResolution(validatePackageName(explicitTarget), "taboolibIoc.targetPackage")
        }

        val gradlePropertyTarget = project.providers.gradleProperty(TABOOLIB_ENV_GROUP_PROPERTY)
            .orNull
            ?.trim()
            .takeUnless { it.isNullOrEmpty() }
        if (gradlePropertyTarget != null) {
            return TargetPackageResolution(validatePackageName(gradlePropertyTarget), TABOOLIB_ENV_GROUP_PROPERTY)
        }

        val taboolibEnvTarget = readTaboolibEnvGroup()
        if (taboolibEnvTarget != null) {
            return TargetPackageResolution(validatePackageName(taboolibEnvTarget), "taboolib.env.group")
        }

        val projectGroup = project.group.toString().trim().takeUnless { it.isBlank() || it == "unspecified" }
        if (projectGroup != null) {
            return TargetPackageResolution(validatePackageName(projectGroup), "project.group")
        }

        throw TaboolibIocConfigurationException(
            "无法推导 IoC 目标包。请配置 taboolibIoc.targetPackage，或在 gradle.properties 中设置 taboolib.env.group，或设置 project.group。"
        )
    }

    private fun resolveDependencySpec(): IocDependencySpec {
        val localProjectPath = extension.localProjectPath.orNull?.trim().takeUnless { it.isNullOrEmpty() }
        val dependencyNotation = extension.dependencyNotation.orNull?.trim().takeUnless { it.isNullOrEmpty() }

        if (localProjectPath != null && dependencyNotation != null) {
            throw TaboolibIocConfigurationException(
                "taboolibIoc.localProjectPath 与 taboolibIoc.dependencyNotation 不能同时设置，请只保留一种 IoC 依赖来源。"
            )
        }

        if (localProjectPath != null) {
            if (project.findProject(localProjectPath) == null) {
                throw TaboolibIocConfigurationException(
                    "未找到本地联调项目 $localProjectPath。请确认该路径已包含在 settings.gradle 中。"
                )
            }
            return ProjectDependencySpec(localProjectPath)
        }

        if (dependencyNotation != null) {
            return ModuleDependencySpec.parse(dependencyNotation)
        }

        val version = extension.iocVersion.orNull?.trim().takeUnless { it.isNullOrEmpty() }
            ?: throw TaboolibIocConfigurationException(
                "无法确定 IoC 依赖版本。请配置 taboolibIoc.iocVersion 或 gradle.properties 中的 $IOC_VERSION_PROPERTY。"
            )
        return ModuleDependencySpec(DEFAULT_IOC_GROUP, DEFAULT_IOC_ARTIFACT, version)
    }

    private fun validatePackageName(value: String): String {
        if (!PACKAGE_REGEX.matches(value)) {
            throw TaboolibIocConfigurationException(
                "目标包 '$value' 不是合法的 Java/Kotlin 包名。请改为形如 com.example.plugin 的值。"
            )
        }
        return value
    }

    internal companion object {
        const val DEFAULT_IOC_ARTIFACT = "taboolib-ioc"
        const val DEFAULT_IOC_GROUP = "top.wcpe.taboolib.ioc"
        const val DEFAULT_IOC_VERSION = "1.0.0-SNAPSHOT"
        const val IOC_VERSION_PROPERTY = "taboolib.ioc.version"
        const val SOURCE_PACKAGE = "top.wcpe.taboolib.ioc"
        const val TABOO_CONFIGURATION_NAME = "taboo"
        const val TABOOLIB_ENV_GROUP_PROPERTY = "taboolib.env.group"
        const val TABOOLIB_EXTENSION_NAME = "taboolib"
        const val TABOOLIB_PLUGIN_ID = "io.izzel.taboolib"

        private val PACKAGE_REGEX = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")
    }
}