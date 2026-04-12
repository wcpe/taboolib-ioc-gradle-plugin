package top.wcpe.taboolib.ioc.gradle.backend

import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.ProjectDependency
import top.wcpe.taboolib.ioc.gradle.ReflectionSupport
import top.wcpe.taboolib.ioc.gradle.TaboolibIocConfigurationException
import top.wcpe.taboolib.ioc.gradle.TaboolibIocResolver
import top.wcpe.taboolib.ioc.gradle.model.IocDependencySpec
import top.wcpe.taboolib.ioc.gradle.model.ModuleDependencySpec
import top.wcpe.taboolib.ioc.gradle.model.ProjectDependencySpec
import top.wcpe.taboolib.ioc.gradle.model.ResolvedIocConfiguration

internal object TabooLibBackend : PackagingBackend {

    override val id: PackagingBackendId = PackagingBackendId.TABOOLIB

    override fun configure(
        project: Project,
        resolver: TaboolibIocResolver,
        configuration: ResolvedIocConfiguration,
    ): BackendConfigurationResult {
        if (!resolver.isTaboolibPluginApplied()) {
            return BackendConfigurationResult(
                configured = false,
                message = "当前工程未应用 io.izzel.taboolib。请先 apply io.izzel.taboolib，再启用 Taboolib IoC 自动接管。",
            )
        }

        if (configuration.skipBecauseSubproject) {
            return BackendConfigurationResult(
                configured = false,
                message = "检测到 taboolib.subproject=true，当前项目已被视为子模块，已跳过自动依赖注入与 relocate。",
            )
        }

        val tabooConfiguration = resolver.locateTabooConfiguration()
            ?: return BackendConfigurationResult(
                configured = false,
                message = "已应用 io.izzel.taboolib，但未找到 taboo 配置。请确认使用的是受支持的 TabooLib Gradle 插件版本。",
            )
        val taboolibExtension = resolver.locateTaboolibExtension()
            ?: return BackendConfigurationResult(
                configured = false,
                message = "已应用 io.izzel.taboolib，但未找到 taboolib 扩展对象。请确认插件应用顺序与版本。",
            )

        ensureRelocation(taboolibExtension, resolver, configuration)
        if (configuration.dependencySpec is ProjectDependencySpec) {
            ensureProjectDependencyTaskDependency(project, configuration.dependencySpec)
        }

        project.logger.lifecycle(
            "Taboolib IoC 已接管 ${project.path}: ${configuration.dependencySpec.displayName}, " +
                "${configuration.sourcePackage} -> ${configuration.targetPackage.relocationTarget}",
        )
        return BackendConfigurationResult(
            configured = true,
            message = "已通过 TabooLib 后端注册 IoC 依赖接管与 relocate。",
        )
    }

    override fun verify(
        project: Project,
        resolver: TaboolibIocResolver,
        configuration: ResolvedIocConfiguration,
    ) {
        if (!resolver.isTaboolibPluginApplied()) {
            throw TaboolibIocConfigurationException(
                "当前工程未应用 io.izzel.taboolib，无法完成 IoC 打包接管。请先 apply io.izzel.taboolib。",
            )
        }
        if (configuration.skipBecauseSubproject) {
            project.logger.lifecycle("taboolib.subproject=true，已跳过当前子模块的 IoC 自动接管验证。")
            return
        }

        val existingRelocation = resolver.readExistingRelocations()[configuration.sourcePackage]
        if (existingRelocation != configuration.targetPackage.relocationTarget) {
            throw TaboolibIocConfigurationException(
                "IoC relocate 尚未生效。期望 ${configuration.sourcePackage} -> ${configuration.targetPackage.relocationTarget}，实际为 ${existingRelocation ?: "<missing>"}。",
            )
        }
    }

    internal fun ensureDependency(
        project: Project,
        tabooConfiguration: org.gradle.api.artifacts.Configuration,
        dependencySpec: IocDependencySpec,
    ) {
        if (hasSameDependency(tabooConfiguration, dependencySpec)) {
            return
        }

        when (dependencySpec) {
            is ModuleDependencySpec -> {
                detectModuleDependencyConflict(tabooConfiguration, dependencySpec)
                project.dependencies.add(TaboolibIocResolver.TABOO_CONFIGURATION_NAME, dependencySpec.displayName)
            }

            is ProjectDependencySpec -> {
                detectProjectDependencyConflict(tabooConfiguration, dependencySpec)
                project.dependencies.add(
                    TaboolibIocResolver.TABOO_CONFIGURATION_NAME,
                    project.dependencies.project(mapOf("path" to dependencySpec.path)),
                )
            }
        }
    }

    internal fun ensureTestDependency(
        project: Project,
        testImplementationConfiguration: org.gradle.api.artifacts.Configuration,
        dependencySpec: ModuleDependencySpec,
    ) {
        if (hasSameDependency(testImplementationConfiguration, dependencySpec)) {
            return
        }

        detectTestModuleDependencyConflict(testImplementationConfiguration, dependencySpec)
        project.dependencies.add(testImplementationConfiguration.name, dependencySpec.displayName)
    }

    private fun ensureRelocation(
        taboolibExtension: Any,
        resolver: TaboolibIocResolver,
        configuration: ResolvedIocConfiguration,
    ) {
        val source = configuration.sourcePackage
        val target = configuration.targetPackage.relocationTarget
        val existingRelocations = resolver.readExistingRelocations()

        val conflict = findRelocationConflict(existingRelocations, source, target)
        if (conflict != null) {
            throw TaboolibIocConfigurationException(conflict)
        }

        if (existingRelocations[source] == target) {
            return
        }

        ReflectionSupport.invokeMethod(taboolibExtension, "relocate", source, target)
    }

    private fun hasSameDependency(
        configuration: org.gradle.api.artifacts.Configuration,
        dependencySpec: IocDependencySpec,
    ): Boolean {
        return when (dependencySpec) {
            is ModuleDependencySpec -> configuration.dependencies
                .filterIsInstance<ExternalDependency>()
                .any {
                    it.group == dependencySpec.group &&
                        it.name == dependencySpec.name &&
                        it.version == dependencySpec.version
                }

            is ProjectDependencySpec -> configuration.dependencies
                .filterIsInstance<ProjectDependency>()
                .any { it.dependencyProject.path == dependencySpec.path }
        }
    }

    private fun detectModuleDependencyConflict(
        configuration: org.gradle.api.artifacts.Configuration,
        dependencySpec: ModuleDependencySpec,
    ) {
        val conflict = configuration.dependencies.filterIsInstance<ExternalDependency>().firstOrNull {
            it.group == dependencySpec.group && it.name == dependencySpec.name && it.version != dependencySpec.version
        }
        if (conflict != null) {
            throw TaboolibIocConfigurationException(
                "检测到重复的 IoC 模块依赖：已存在 ${conflict.group}:${conflict.name}:${conflict.version}，自动接管想要注入 ${dependencySpec.displayName}。请保留一种版本来源。",
            )
        }
    }

    private fun detectProjectDependencyConflict(
        configuration: org.gradle.api.artifacts.Configuration,
        dependencySpec: ProjectDependencySpec,
    ) {
        val conflict = configuration.dependencies.filterIsInstance<ProjectDependency>().firstOrNull {
            it.dependencyProject.path != dependencySpec.path
        }
        if (conflict != null) {
            throw TaboolibIocConfigurationException(
                "检测到多个 IoC project 依赖来源：已存在 ${conflict.dependencyProject.path}，自动接管想要注入 ${dependencySpec.path}。请保留一个本地联调项目。",
            )
        }
    }

    private fun detectTestModuleDependencyConflict(
        configuration: org.gradle.api.artifacts.Configuration,
        dependencySpec: ModuleDependencySpec,
    ) {
        val conflict = configuration.dependencies.filterIsInstance<ExternalDependency>().firstOrNull {
            it.group == dependencySpec.group && it.name == dependencySpec.name && it.version != dependencySpec.version
        }
        if (conflict != null) {
            throw TaboolibIocConfigurationException(
                "检测到重复的 IoC 测试模块依赖：已存在 ${conflict.group}:${conflict.name}:${conflict.version}，自动接管想要注入 ${dependencySpec.displayName}。请保留一种版本来源。",
            )
        }
    }

    private fun ensureProjectDependencyTaskDependency(
        project: Project,
        dependencySpec: ProjectDependencySpec,
    ) {
        val dependencyProject = project.findProject(dependencySpec.path)
            ?: throw TaboolibIocConfigurationException(
                "未找到本地联调项目 ${dependencySpec.path}，无法为 jar 建立显式任务依赖。",
            )
        val dependencyJarTask = dependencyProject.tasks.named("jar")
        project.tasks.matching { it.name == "jar" }.configureEach { task ->
            task.dependsOn(dependencyJarTask)
        }
    }

    private fun findRelocationConflict(
        existingRelocations: Map<String, String>,
        source: String,
        target: String,
    ): String? {
        val exactConflict = existingRelocations[source]
        if (exactConflict != null && exactConflict != target) {
            return "检测到手写 relocate 与自动 IoC relocate 冲突：$source 已指向 $exactConflict，自动接管需要 $target。请删除手写配置或关闭 autoTakeover。"
        }

        val nestedConflict = existingRelocations.entries.firstOrNull {
            it.key != source && (source.startsWith("${it.key}.") || it.key.startsWith("$source.")) && it.value != target
        }
        if (nestedConflict != null) {
            return "检测到与 IoC 包前缀重叠的手写 relocate：${nestedConflict.key} -> ${nestedConflict.value}，它会影响自动规则 $source -> $target。请收敛为一套 relocate 规则。"
        }
        return null
    }
}