package top.wcpe.taboolib.ioc.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.gradle.api.tasks.TaskProvider
import top.wcpe.taboolib.ioc.gradle.analysis.AnalyzeTaboolibIocBeansTask
import top.wcpe.taboolib.ioc.gradle.backend.BackendConfigurationResult
import top.wcpe.taboolib.ioc.gradle.backend.PackagingBackend
import top.wcpe.taboolib.ioc.gradle.backend.PackagingBackendId
import top.wcpe.taboolib.ioc.gradle.backend.StandaloneBackend
import top.wcpe.taboolib.ioc.gradle.backend.TabooLibBackend
import top.wcpe.taboolib.ioc.gradle.model.ResolvedIocConfiguration
import top.wcpe.taboolib.ioc.gradle.model.ProjectDependencySpec

class TaboolibIocPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("taboolibIoc", TaboolibIocExtension::class.java)
        applyConventions(project, extension)

        val resolver = TaboolibIocResolver(project, extension)
        registerAnalysisTask(project, extension, resolver)
        val doctorTask = registerDoctorTask(project)
        val verifyTask = registerVerifyTask(project)
        registerDependencyHooks(project, extension, resolver)

        var resolvedConfiguration: Result<ResolvedIocConfiguration> = Result.failure(
            TaboolibIocConfigurationException("Taboolib IoC 尚未完成项目评估。")
        )
        var backendResult = BackendConfigurationResult(configured = false, message = "尚未完成项目评估。")

        doctorTask.configure { task ->
            task.doLast {
                val latestResolution = runCatching { resolver.resolve() }
                project.logger.lifecycle("[taboolibIocDoctor] backend = ${extension.backend.get()}")
                project.logger.lifecycle("[taboolibIocDoctor] autoTakeover = ${extension.autoTakeover.get()}")
                project.logger.lifecycle("[taboolibIocDoctor] taboolibApplied = ${resolver.isTaboolibPluginApplied()}")
                project.logger.lifecycle("[taboolibIocDoctor] taboolibSubproject = ${resolver.isTaboolibSubproject()}")
                latestResolution.onSuccess {
                    project.logger.lifecycle("[taboolibIocDoctor] dependency = ${it.dependencySpec.displayName}")
                    project.logger.lifecycle(
                        "[taboolibIocDoctor] relocation = ${it.sourcePackage} -> ${it.targetPackage.relocationTarget} (${it.targetPackage.source})"
                    )
                }.onFailure {
                    project.logger.lifecycle("[taboolibIocDoctor] resolution = FAILED: ${it.message}")
                }
                project.logger.lifecycle("[taboolibIocDoctor] configured = ${backendResult.configured}")
                project.logger.lifecycle("[taboolibIocDoctor] message = ${backendResult.message}")
            }
        }

        verifyTask.configure { task ->
            task.doLast {
                if (!extension.autoTakeover.get()) {
                    project.logger.lifecycle("taboolibIoc.autoTakeover=false，已跳过自动接管验证。")
                    return@doLast
                }

                val configuration = resolvedConfiguration.getOrElse { throw it }
                if (configuration.skipBecauseSubproject) {
                    project.logger.lifecycle("taboolib.subproject=true，已跳过当前子模块的 IoC 自动接管验证。")
                    return@doLast
                }
                if (!backendResult.configured) {
                    throw TaboolibIocConfigurationException(backendResult.message)
                }
                backendFor(configuration.backendId).verify(project, resolver, configuration)
            }
        }

        project.afterEvaluate {
            val backend = backendFor(extension.backend.get())
            resolvedConfiguration = runCatching { resolver.resolve() }
            backendResult = if (!extension.autoTakeover.get()) {
                BackendConfigurationResult(
                    configured = false,
                    message = "taboolibIoc.autoTakeover=false，已跳过自动接管。",
                )
            } else {
                resolvedConfiguration.fold(
                    onSuccess = { configuration -> backend.configure(project, resolver, configuration) },
                    onFailure = { throwable ->
                        BackendConfigurationResult(
                            configured = false,
                            message = throwable.message ?: "IoC 配置解析失败。",
                        )
                    },
                )
            }
            attachVerificationHooks(project, verifyTask)
        }
    }

    private fun applyConventions(project: Project, extension: TaboolibIocExtension) {
        extension.autoTakeover.convention(readBooleanProperty(project, "taboolib.ioc.auto-takeover") ?: true)
        extension.backend.convention(
            PackagingBackendId.parse(project.providers.gradleProperty("taboolib.ioc.backend").orNull)
                ?: PackagingBackendId.TABOOLIB,
        )
        extension.iocVersion.convention(
            project.provider {
                readStringProperty(project, TaboolibIocResolver.IOC_VERSION_PROPERTY)
                    ?: defaultIocVersion()
            },
        )
        extension.analysisFailOnError.convention(
            readBooleanProperty(project, "taboolib.ioc.analysis.fail-on-error") ?: true,
        )
        extension.analysisFailOnWarning.convention(
            readBooleanProperty(project, "taboolib.ioc.analysis.fail-on-warning") ?: false,
        )
    }

    private fun registerDoctorTask(
        project: Project,
    ): TaskProvider<Task> {
        return project.tasks.register("taboolibIocDoctor") { task ->
            task.group = "taboolib ioc"
            task.description = "Prints the resolved Taboolib IoC backend, dependency and relocation diagnostics."
        }
    }

    private fun registerVerifyTask(
        project: Project,
    ): TaskProvider<Task> {
        return project.tasks.register("verifyTaboolibIoc") { task ->
            task.group = "taboolib ioc"
            task.description = "Verifies that Taboolib IoC auto takeover is configured before packaging tasks run."
        }
    }

    private fun registerAnalysisTask(
        project: Project,
        extension: TaboolibIocExtension,
        resolver: TaboolibIocResolver,
    ): TaskProvider<AnalyzeTaboolibIocBeansTask> {
        val taskProvider = project.tasks.register("analyzeTaboolibIocBeans", AnalyzeTaboolibIocBeansTask::class.java) { task ->
            task.group = "taboolib ioc"
            task.description = "Builds bean and injection indexes from compiled classes and writes a static diagnosis report."
            task.reportFile.convention(project.layout.buildDirectory.file("reports/taboolib-ioc/static-diagnosis.json"))
            task.failOnError.convention(extension.analysisFailOnError)
            task.failOnWarning.convention(extension.analysisFailOnWarning)
            task.projectPropertiesInput.convention(
                project.provider {
                    project.properties.entries.associate { (key, value) -> key.toString() to value.toString() }
                },
            )
        }

        project.pluginManager.withPlugin("java-base") {
            val javaExtension = project.extensions.findByType(JavaPluginExtension::class.java) ?: return@withPlugin
            val mainSourceSet = javaExtension.sourceSets.findByName("main") ?: return@withPlugin
            taskProvider.configure { task ->
                task.classDirectories.from(mainSourceSet.output.classesDirs)
                task.sourceDirectories.from(mainSourceSet.allSource.srcDirs)
                task.dependsOn(project.tasks.named(mainSourceSet.classesTaskName))
            }
            project.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME).configure { task ->
                task.dependsOn(taskProvider)
            }
        }

        project.pluginManager.withPlugin(TaboolibIocResolver.TABOOLIB_PLUGIN_ID) {
            project.configurations.matching { it.name == TaboolibIocResolver.TABOO_CONFIGURATION_NAME }.all { configuration ->
                taskProvider.configure { task ->
                    task.dependencyArtifacts.from(configuration)
                    task.dependsOn(configuration.buildDependencies)
                }
            }
        }

        project.afterEvaluate {
            val resolution = runCatching { resolver.resolve() }.getOrNull() ?: return@afterEvaluate
            val dependencySpec = resolution.dependencySpec
            if (dependencySpec !is ProjectDependencySpec) {
                return@afterEvaluate
            }
            val dependencyProject = project.findProject(dependencySpec.path) ?: return@afterEvaluate
            val javaExtension = dependencyProject.extensions.findByType(JavaPluginExtension::class.java) ?: return@afterEvaluate
            val mainSourceSet = javaExtension.sourceSets.findByName("main") ?: return@afterEvaluate
            taskProvider.configure { task ->
                task.classDirectories.from(mainSourceSet.output.classesDirs)
                task.sourceDirectories.from(mainSourceSet.allSource.srcDirs)
                task.dependsOn(dependencyProject.tasks.named(mainSourceSet.classesTaskName))
            }
        }
        return taskProvider
    }

    private fun registerDependencyHooks(
        project: Project,
        extension: TaboolibIocExtension,
        resolver: TaboolibIocResolver,
    ) {
        project.pluginManager.withPlugin(TaboolibIocResolver.TABOOLIB_PLUGIN_ID) {
            project.configurations.matching { it.name == TaboolibIocResolver.TABOO_CONFIGURATION_NAME }.all { configuration ->
                configuration.withDependencies {
                    if (!extension.autoTakeover.get()) {
                        return@withDependencies
                    }

                    val resolved = runCatching { resolver.resolve() }.getOrElse {
                        return@withDependencies
                    }
                    if (resolved.backendId != PackagingBackendId.TABOOLIB || resolved.skipBecauseSubproject) {
                        return@withDependencies
                    }

                    TabooLibBackend.ensureDependency(project, configuration, resolved.dependencySpec)
                }
            }
        }
    }

    private fun attachVerificationHooks(project: Project, verifyTask: TaskProvider<Task>) {
        val guardedTaskNames = setOf("jar", "assemble", "build")
        project.tasks.matching { it.name in guardedTaskNames }.configureEach { task ->
            task.dependsOn(verifyTask)
        }
    }

    private fun backendFor(backendId: PackagingBackendId): PackagingBackend {
        return when (backendId) {
            PackagingBackendId.TABOOLIB -> TabooLibBackend
            PackagingBackendId.STANDALONE -> StandaloneBackend
        }
    }

    private fun readBooleanProperty(project: Project, name: String): Boolean? {
        return project.providers.gradleProperty(name)
            .orNull
            ?.trim()
            ?.takeUnless { it.isEmpty() }
            ?.toBooleanStrictOrNull()
    }

    private fun readStringProperty(project: Project, name: String): String? {
        val gradleProperty = project.providers.gradleProperty(name)
            .orNull
            ?.trim()
            ?.takeUnless { it.isEmpty() }
        if (gradleProperty != null) {
            return gradleProperty
        }
        return project.findProperty(name)
            ?.toString()
            ?.trim()
            ?.takeUnless { it.isEmpty() }
    }

    private fun defaultIocVersion(): String {
        return TaboolibIocPluginVersionLocator.resolveBundledVersion()
            ?: TaboolibIocResolver.DEFAULT_IOC_VERSION
    }
}