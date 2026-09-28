package top.wcpe.taboolib.ioc.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.gradle.api.tasks.TaskProvider
import top.wcpe.taboolib.ioc.gradle.analysis.AnalyzeTaboolibIocBeansTask
import top.wcpe.taboolib.ioc.gradle.backend.BackendConfigurationResult
import top.wcpe.taboolib.ioc.gradle.backend.collectBackendVerification
import top.wcpe.taboolib.ioc.gradle.backend.PackagingBackend
import top.wcpe.taboolib.ioc.gradle.backend.PackagingBackendId
import top.wcpe.taboolib.ioc.gradle.backend.StandaloneBackend
import top.wcpe.taboolib.ioc.gradle.backend.TabooLibBackend
import top.wcpe.taboolib.ioc.gradle.model.ResolvedIocConfiguration
import top.wcpe.taboolib.ioc.gradle.model.ProjectDependencySpec
import top.wcpe.taboolib.ioc.gradle.task.TaboolibIocDoctorTask
import top.wcpe.taboolib.ioc.gradle.task.VerifyTaboolibIocTask
import top.wcpe.taboolib.ioc.gradle.weaving.WeaveTaboolibIocAopTask

class TaboolibIocPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("taboolibIoc", TaboolibIocExtension::class.java)
        applyConventions(project, extension)

        val resolver = TaboolibIocResolver(project, extension)
        val analysisTask = registerAnalysisTask(project, extension, resolver)
        val weaveTask = registerWeaveTask(project, extension)
        weaveTask.configure { it.mustRunAfter(analysisTask) }
        attachWeavingHooks(project, weaveTask)
        val doctorTask = registerDoctorTask(project)
        val verifyTask = registerVerifyTask(project)
        registerDependencyHooks(project, extension, resolver)

        project.afterEvaluate {
            val backend = backendFor(extension.backend.get())
            // 解析结果与后端接管结果只在本回调内使用（任务输入在此固化），无需提前声明。
            val resolvedConfiguration = runCatching { resolver.resolve() }
            val backendResult = if (!extension.autoTakeover.get()) {
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
            // 诊断与校验任务的输入在此固化：执行阶段不再触碰 Project/resolver，
            // 任务状态才能被 Gradle 配置缓存序列化。
            applyDoctorInputs(doctorTask, extension, resolver, resolvedConfiguration, backendResult)
            applyVerificationInputs(verifyTask, extension, resolver, resolvedConfiguration, backendResult)
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
        extension.weaving.convention(
            readBooleanProperty(project, "taboolib.ioc.weaving") ?: false,
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
    ): TaskProvider<TaboolibIocDoctorTask> {
        return project.tasks.register("taboolibIocDoctor", TaboolibIocDoctorTask::class.java) { task ->
            task.group = "taboolib ioc"
            task.description = "Prints the resolved Taboolib IoC backend, dependency and relocation diagnostics."
        }
    }

    private fun registerVerifyTask(
        project: Project,
    ): TaskProvider<VerifyTaboolibIocTask> {
        return project.tasks.register("verifyTaboolibIoc", VerifyTaboolibIocTask::class.java) { task ->
            task.group = "taboolib ioc"
            task.description = "Verifies that Taboolib IoC auto takeover is configured before packaging tasks run."
        }
    }

    /**
     * 把诊断文本固化到 doctor 任务。
     *
     * 原先的打印逻辑写在 `doLast` 闭包里，闭包捕获了 Project，任务状态因此无法被
     * Gradle 配置缓存序列化；现在改为配置阶段生成文本、执行阶段只输出。
     */
    private fun applyDoctorInputs(
        doctorTask: TaskProvider<TaboolibIocDoctorTask>,
        extension: TaboolibIocExtension,
        resolver: TaboolibIocResolver,
        resolution: Result<ResolvedIocConfiguration>,
        backendResult: BackendConfigurationResult,
    ) {
        val lines = mutableListOf<String>()
        lines += "[taboolibIocDoctor] backend = ${extension.backend.get()}"
        lines += "[taboolibIocDoctor] autoTakeover = ${extension.autoTakeover.get()}"
        lines += "[taboolibIocDoctor] taboolibApplied = ${resolver.isTaboolibPluginApplied()}"
        lines += "[taboolibIocDoctor] taboolibSubproject = ${resolver.isTaboolibSubproject()}"
        resolution.onSuccess { configuration ->
            lines += "[taboolibIocDoctor] dependency = ${configuration.dependencySpec.displayName}"
            resolver.resolveTestDependencySpec(configuration.dependencySpec)?.let { testDependencySpec ->
                lines += "[taboolibIocDoctor] testDependency = ${testDependencySpec.displayName}"
            }
            lines += "[taboolibIocDoctor] relocation = ${configuration.sourcePackage} -> " +
                "${configuration.targetPackage.relocationTarget} (${configuration.targetPackage.source})"
        }.onFailure { failure ->
            lines += "[taboolibIocDoctor] resolution = FAILED: ${failure.message}"
        }
        lines += "[taboolibIocDoctor] configured = ${backendResult.configured}"
        lines += "[taboolibIocDoctor] message = ${backendResult.message}"
        doctorTask.configure { task -> task.diagnosticLines.set(lines) }
    }

    private fun applyVerificationInputs(
        verifyTask: TaskProvider<VerifyTaboolibIocTask>,
        extension: TaboolibIocExtension,
        resolver: TaboolibIocResolver,
        resolution: Result<ResolvedIocConfiguration>,
        backendResult: BackendConfigurationResult,
    ) {
        verifyTask.configure { task ->
            task.autoTakeover.set(extension.autoTakeover)
            task.backendConfigured.set(backendResult.configured)
            task.backendMessage.set(backendResult.message)
            resolution.exceptionOrNull()?.let { failure ->
                task.resolutionFailureMessage.set(failure.message ?: failure.javaClass.simpleName)
            }
            resolution.getOrNull()?.let { configuration ->
                // 快照只能在配置阶段采集（relocate 实际值此刻才可读），执行阶段只做判定。
                val snapshot = collectBackendVerification(resolver, configuration)
                task.backendId.set(configuration.backendId)
                task.taboolibPluginApplied.set(snapshot.taboolibPluginApplied)
                task.skipBecauseSubproject.set(snapshot.skipBecauseSubproject)
                task.sourcePackage.set(snapshot.sourcePackage)
                task.expectedRelocation.set(snapshot.expectedRelocation)
                // 读不到实际 relocate 时不设置该属性（@Optional），执行阶段据此判定为「尚未生效」。
                snapshot.actualRelocation?.let { actual -> task.actualRelocation.set(actual) }
            }
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
            // 工程路径必须在配置阶段固化：执行阶段读 Task.project 会破坏配置缓存。
            task.projectPath.set(project.path)
            task.failOnError.convention(extension.analysisFailOnError)
            task.failOnWarning.convention(extension.analysisFailOnWarning)
            task.projectPropertiesInput.convention(
                project.provider {
                    project.properties.entries.mapNotNull { (key, value) ->
                        val strKey = key.toString()
                        if (value == null) strKey to "null"
                        else {
                            val strValue = try {
                                value.toString()
                            } catch (_: Throwable) {
                                value.javaClass.name
                            }
                            strKey to strValue
                        }
                    }.toMap()
                },
            )
        }

        project.pluginManager.withPlugin("java-base") {
            val javaExtension = project.extensions.findByType(JavaPluginExtension::class.java) ?: return@withPlugin
            val mainSourceSet = javaExtension.sourceSets.findByName("main") ?: return@withPlugin
            taskProvider.configure { task ->
                task.classDirectories.from(mainSourceSet.output.classesDirs)
                task.dependencyArtifacts.from(mainSourceSet.compileClasspath)
                task.sourceDirectories.from(mainSourceSet.allSource.srcDirs)
                task.dependsOn(project.tasks.named(mainSourceSet.classesTaskName))
                task.dependsOn(mainSourceSet.compileClasspath)
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

            project.pluginManager.withPlugin("java-base") {
                project.configurations.matching { it.name == "testImplementation" }.all { configuration ->
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

                        val testDependencySpec = resolver.resolveTestDependencySpec(resolved.dependencySpec)
                            ?: return@withDependencies
                        TabooLibBackend.ensureTestDependency(project, configuration, testDependencySpec)
                    }
                }
            }
        }
    }

    /**
     * 把 `verifyTaboolibIoc` 挂到打包任务（jar / assemble / build）之前。
     *
     * 应用顺序守护：
     * - 若消费方在 `io.izzel.taboolib` **之后**才 apply 本插件，`afterEvaluate` 中
     *   taboolib 的 relocate 可能尚未写回、或本插件的配置回调晚于打包任务注册时机，
     *   此时 verify 会因「relocate 未生效」误报失败。
     * - 因此这里不硬绑定任务依赖，而是通过 [project.afterEvaluate] 里的
     *   `resolvedConfiguration` 判定：仅当自动接管**确实已生效**（backend 已 configured、
     *   且非 subproject 跳过）时才挂依赖；否则在 verify 内部优雅跳过并打印诊断。
     * - 同时显式要求 taboolib 插件已应用（否则给出明确错误而非静默通过）。
     */
    private fun registerWeaveTask(
        project: Project,
        extension: TaboolibIocExtension,
    ): TaskProvider<WeaveTaboolibIocAopTask> {
        // 惰性解析：插件可能被应用在尚未启用 java 插件的工程上（例如单测的 ProjectBuilder），
        // 此时直接取 JavaPluginExtension/sourceSets 会抛异常。classesDirs 自带 builtBy，
        // 解析时机在任务图计算阶段，任务依赖仍会被自动推断。
        val classDirectories = project.files(
            java.util.concurrent.Callable {
                project.extensions.findByType(JavaPluginExtension::class.java)
                    ?.sourceSets
                    ?.findByName("main")
                    ?.output
                    ?.classesDirs
            }
        )
        return project.tasks.register("weaveTaboolibIocAop", WeaveTaboolibIocAopTask::class.java) { task ->
            task.group = "taboolib ioc"
            task.description = "Weaves AOP advice into matched methods at build time (works for concrete classes)."
            // 用 Gradle 原生 enabled：关闭时任务直接 SKIPPED，不进 TaskAction
            task.enabled = extension.weaving.get()
            task.classDirectories.from(classDirectories)
        }
    }

    /** 织入必须早于打包任务（jar / assemble / build / taboolibMainTask）。 */
    private fun attachWeavingHooks(project: Project, weaveTask: TaskProvider<WeaveTaboolibIocAopTask>) {
        val guardedTaskNames = setOf("jar", "assemble", "build", "taboolibMainTask")
        project.tasks.matching { it.name in guardedTaskNames }.configureEach { task ->
            task.dependsOn(weaveTask)
        }
    }

    private fun attachVerificationHooks(project: Project, verifyTask: TaskProvider<VerifyTaboolibIocTask>) {
        val guardedTaskNames = setOf("jar", "assemble", "build")
        // 通过 matching + configureEach 延迟到任务真正注册后再绑定，避免顺序敏感。
        project.tasks.matching { it.name in guardedTaskNames }.configureEach { task ->
            // 仅在自动接管已生效时绑定校验，避免「插件已应用但接管未生效」的误报阻断打包。
            if (isTakeoverEffective(project)) {
                task.dependsOn(verifyTask)
            }
        }
    }

    /**
     * 判断当前工程的 IoC 自动接管是否已实际生效（可安全执行 relocate 校验）。
     * 读取 [Project.getExtensions] 上的 taboolibIoc 扩展配置与 taboolib 插件状态。
     */
    private fun isTakeoverEffective(project: Project): Boolean {
        val extension = project.extensions.findByType(TaboolibIocExtension::class.java) ?: return false
        if (!extension.autoTakeover.getOrElse(true)) {
            return false
        }
        // 未应用 taboolib 插件时接管无法完成，交由 verify 内部给出明确错误。
        if (!project.pluginManager.hasPlugin(TaboolibIocResolver.TABOOLIB_PLUGIN_ID)) {
            return true
        }
        val resolver = TaboolibIocResolver(project, extension)
        return !resolver.isTaboolibSubproject()
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