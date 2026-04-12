package top.wcpe.taboolib.ioc.gradle

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.tasks.bundling.Jar
import org.gradle.testfixtures.ProjectBuilder
import top.wcpe.taboolib.ioc.gradle.backend.PackagingBackendId
import top.wcpe.taboolib.ioc.gradle.backend.TabooLibBackend
import top.wcpe.taboolib.ioc.gradle.model.ModuleDependencySpec
import top.wcpe.taboolib.ioc.gradle.model.ProjectDependencySpec
import top.wcpe.taboolib.ioc.gradle.model.ResolvedIocConfiguration
import top.wcpe.taboolib.ioc.gradle.model.TargetPackageResolution

class TabooLibBackendUnitTest {

    @Test
    fun configureReturnsFailureWhenTaboolibPluginMissing() {
        val project = buildProject("backend-no-plugin")
        val resolver = createResolver(project)

        val result = TabooLibBackend.configure(project, resolver, moduleConfiguration())

        assertFalse(result.configured)
        assertContains(result.message, "未应用 io.izzel.taboolib")
    }

    @Test
    fun configureReturnsFailureWhenCurrentProjectIsSubproject() {
        val project = buildProject("backend-subproject")
        applyFakeTaboolib(project)
        val resolver = createResolver(project)

        val result = TabooLibBackend.configure(project, resolver, moduleConfiguration(skipBecauseSubproject = true))

        assertFalse(result.configured)
        assertContains(result.message, "已被视为子模块")
    }

    @Test
    fun configureReturnsFailureWhenTabooConfigurationIsMissing() {
        val project = buildProject("backend-no-taboo")
        project.extensions.extraProperties[FakeTaboolibPlugin.CREATE_TABOO_CONFIGURATION_PROPERTY] = false
        applyFakeTaboolib(project)
        val resolver = createResolver(project)

        val result = TabooLibBackend.configure(project, resolver, moduleConfiguration())

        assertFalse(result.configured)
        assertContains(result.message, "未找到 taboo 配置")
    }

    @Test
    fun configureReturnsFailureWhenTaboolibExtensionIsMissing() {
        val project = buildProject("backend-no-extension")
        project.extensions.extraProperties[FakeTaboolibPlugin.CREATE_TABOOLIB_EXTENSION_PROPERTY] = false
        applyFakeTaboolib(project)
        val resolver = createResolver(project)

        val result = TabooLibBackend.configure(project, resolver, moduleConfiguration())

        assertFalse(result.configured)
        assertContains(result.message, "未找到 taboolib 扩展对象")
    }

    @Test
    fun configureAddsRelocationAndJarTaskDependencyForProjectDependency() {
        val rootProject = buildProject("backend-project-success")
        rootProject.pluginManager.apply("java")
        val dependencyProject = ProjectBuilder.builder()
            .withName("ioc-lib")
            .withProjectDir(Files.createTempDirectory("backend-ioc-lib").toFile())
            .withParent(rootProject)
            .build()
        dependencyProject.pluginManager.apply("java")
        applyFakeTaboolib(rootProject)
        val resolver = createResolver(rootProject)

        val result = TabooLibBackend.configure(rootProject, resolver, projectConfiguration(":ioc-lib"))
        val extension = rootProject.extensions.getByName("taboolib") as FakeTaboolibExtension
        val jarTask = rootProject.tasks.getByName("jar") as Jar
        val dependencyJarTask = dependencyProject.tasks.getByName("jar")

        assertTrue(result.configured)
        assertEquals("com.example.demo.ioc", extension.relocation[TaboolibIocResolver.SOURCE_PACKAGE])
        assertTrue(jarTask.taskDependencies.getDependencies(jarTask).contains(dependencyJarTask))
    }

    @Test
    fun configureFailsWhenRelocationConflictsWithExistingRule() {
        val project = buildProject("backend-relocation-conflict")
        applyFakeTaboolib(project)
        val resolver = createResolver(project)
        val extension = project.extensions.getByName("taboolib") as FakeTaboolibExtension
        extension.relocation[TaboolibIocResolver.SOURCE_PACKAGE] = "com.manual.override"

        val error = assertFailsWith<TaboolibIocConfigurationException> {
            TabooLibBackend.configure(project, resolver, moduleConfiguration())
        }

        assertContains(error.message ?: "", "手写 relocate 与自动 IoC relocate 冲突")
    }

    @Test
    fun ensureDependencyAddsAndDeduplicatesExternalDependency() {
        val project = buildProject("backend-module-dependency")
        val tabooConfiguration = project.configurations.create(TaboolibIocResolver.TABOO_CONFIGURATION_NAME)
        val dependencySpec = ModuleDependencySpec("top.wcpe.taboolib.ioc", "taboolib-ioc", "1.0.0")

        TabooLibBackend.ensureDependency(project, tabooConfiguration, dependencySpec)
        TabooLibBackend.ensureDependency(project, tabooConfiguration, dependencySpec)

        val dependencies = tabooConfiguration.dependencies.filterIsInstance<ExternalDependency>()
        assertEquals(1, dependencies.size)
        assertEquals("top.wcpe.taboolib.ioc", dependencies.single().group)
        assertEquals("taboolib-ioc", dependencies.single().name)
        assertEquals("1.0.0", dependencies.single().version)
    }

    @Test
    fun ensureDependencyRejectsConflictingExternalDependency() {
        val project = buildProject("backend-module-conflict")
        val tabooConfiguration = project.configurations.create(TaboolibIocResolver.TABOO_CONFIGURATION_NAME)
        project.dependencies.add(TaboolibIocResolver.TABOO_CONFIGURATION_NAME, "top.wcpe.taboolib.ioc:taboolib-ioc:2.0.0")

        val error = assertFailsWith<TaboolibIocConfigurationException> {
            TabooLibBackend.ensureDependency(
                project,
                tabooConfiguration,
                ModuleDependencySpec("top.wcpe.taboolib.ioc", "taboolib-ioc", "1.0.0"),
            )
        }

        assertContains(error.message ?: "", "重复的 IoC 模块依赖")
    }

    @Test
    fun ensureTestDependencyAddsAndDeduplicatesOfficialTestModule() {
        val project = buildProject("backend-test-module-dependency")
        project.pluginManager.apply("java")
        val testImplementationConfiguration = project.configurations.getByName("testImplementation")
        val dependencySpec = ModuleDependencySpec(
            TaboolibIocResolver.DEFAULT_IOC_GROUP,
            TaboolibIocResolver.DEFAULT_IOC_TEST_ARTIFACT,
            TaboolibIocResolver.DEFAULT_IOC_VERSION,
        )

        TabooLibBackend.ensureTestDependency(project, testImplementationConfiguration, dependencySpec)
        TabooLibBackend.ensureTestDependency(project, testImplementationConfiguration, dependencySpec)

        val dependencies = testImplementationConfiguration.dependencies.filterIsInstance<ExternalDependency>()
        assertEquals(1, dependencies.size)
        assertEquals(TaboolibIocResolver.DEFAULT_IOC_GROUP, dependencies.single().group)
        assertEquals(TaboolibIocResolver.DEFAULT_IOC_TEST_ARTIFACT, dependencies.single().name)
        assertEquals(TaboolibIocResolver.DEFAULT_IOC_VERSION, dependencies.single().version)
    }

    @Test
    fun ensureTestDependencyRejectsConflictingOfficialTestModuleVersion() {
        val project = buildProject("backend-test-module-conflict")
        project.pluginManager.apply("java")
        val testImplementationConfiguration = project.configurations.getByName("testImplementation")
        project.dependencies.add(
            testImplementationConfiguration.name,
            "${TaboolibIocResolver.DEFAULT_IOC_GROUP}:${TaboolibIocResolver.DEFAULT_IOC_TEST_ARTIFACT}:9.9.9",
        )

        val error = assertFailsWith<TaboolibIocConfigurationException> {
            TabooLibBackend.ensureTestDependency(
                project,
                testImplementationConfiguration,
                ModuleDependencySpec(
                    TaboolibIocResolver.DEFAULT_IOC_GROUP,
                    TaboolibIocResolver.DEFAULT_IOC_TEST_ARTIFACT,
                    TaboolibIocResolver.DEFAULT_IOC_VERSION,
                ),
            )
        }

        assertContains(error.message ?: "", "重复的 IoC 测试模块依赖")
    }

    @Test
    fun ensureDependencyAddsProjectDependency() {
        val rootProject = buildProject("backend-project-dependency")
        ProjectBuilder.builder()
            .withName("ioc-lib")
            .withProjectDir(Files.createTempDirectory("backend-project-dependency-lib").toFile())
            .withParent(rootProject)
            .build()
        val tabooConfiguration = rootProject.configurations.create(TaboolibIocResolver.TABOO_CONFIGURATION_NAME)

        TabooLibBackend.ensureDependency(rootProject, tabooConfiguration, ProjectDependencySpec(":ioc-lib"))

        val dependencies = tabooConfiguration.dependencies.filterIsInstance<ProjectDependency>()
        assertEquals(1, dependencies.size)
        assertEquals(":ioc-lib", dependencies.single().dependencyProject.path)
    }

    @Test
    fun ensureDependencyRejectsConflictingProjectDependency() {
        val rootProject = buildProject("backend-project-conflict")
        ProjectBuilder.builder()
            .withName("ioc-lib")
            .withProjectDir(Files.createTempDirectory("backend-project-conflict-ioc").toFile())
            .withParent(rootProject)
            .build()
        ProjectBuilder.builder()
            .withName("other-lib")
            .withProjectDir(Files.createTempDirectory("backend-project-conflict-other").toFile())
            .withParent(rootProject)
            .build()
        val tabooConfiguration = rootProject.configurations.create(TaboolibIocResolver.TABOO_CONFIGURATION_NAME)
        rootProject.dependencies.add(
            TaboolibIocResolver.TABOO_CONFIGURATION_NAME,
            rootProject.dependencies.project(mapOf("path" to ":other-lib")),
        )

        val error = assertFailsWith<TaboolibIocConfigurationException> {
            TabooLibBackend.ensureDependency(rootProject, tabooConfiguration, ProjectDependencySpec(":ioc-lib"))
        }

        assertContains(error.message ?: "", "多个 IoC project 依赖来源")
    }

    @Test
    fun verifyPassesWhenRelocationMatches() {
        val project = buildProject("backend-verify-success")
        applyFakeTaboolib(project)
        val resolver = createResolver(project)
        val extension = project.extensions.getByName("taboolib") as FakeTaboolibExtension
        extension.relocation[TaboolibIocResolver.SOURCE_PACKAGE] = "com.example.demo.ioc"

        TabooLibBackend.verify(project, resolver, moduleConfiguration())
    }

    @Test
    fun verifyThrowsWhenRelocationDoesNotMatch() {
        val project = buildProject("backend-verify-failure")
        applyFakeTaboolib(project)
        val resolver = createResolver(project)

        val error = assertFailsWith<TaboolibIocConfigurationException> {
            TabooLibBackend.verify(project, resolver, moduleConfiguration())
        }

        assertContains(error.message ?: "", "IoC relocate 尚未生效")
    }

    @Test
    fun verifySkipsSubprojectConfiguration() {
        val project = buildProject("backend-verify-subproject")
        applyFakeTaboolib(project)
        val resolver = createResolver(project)

        TabooLibBackend.verify(project, resolver, moduleConfiguration(skipBecauseSubproject = true))
    }

    private fun buildProject(name: String): Project {
        return ProjectBuilder.builder()
            .withName(name)
            .withProjectDir(Files.createTempDirectory(name).toFile())
            .build()
    }

    private fun applyFakeTaboolib(project: Project) {
        project.pluginManager.apply(TaboolibIocResolver.TABOOLIB_PLUGIN_ID)
    }

    private fun createResolver(project: Project): TaboolibIocResolver {
        val extension = project.extensions.findByType(TaboolibIocExtension::class.java)
            ?: project.extensions.create("taboolibIoc", TaboolibIocExtension::class.java).apply {
                autoTakeover.set(true)
                backend.set(PackagingBackendId.TABOOLIB)
                iocVersion.set(TaboolibIocResolver.DEFAULT_IOC_VERSION)
            }
        return TaboolibIocResolver(project, extension)
    }

    private fun moduleConfiguration(skipBecauseSubproject: Boolean = false): ResolvedIocConfiguration {
        return ResolvedIocConfiguration(
            backendId = PackagingBackendId.TABOOLIB,
            sourcePackage = TaboolibIocResolver.SOURCE_PACKAGE,
            targetPackage = TargetPackageResolution("com.example.demo", "project.group"),
            dependencySpec = ModuleDependencySpec(
                TaboolibIocResolver.DEFAULT_IOC_GROUP,
                TaboolibIocResolver.DEFAULT_IOC_ARTIFACT,
                TaboolibIocResolver.DEFAULT_IOC_VERSION,
            ),
            skipBecauseSubproject = skipBecauseSubproject,
        )
    }

    private fun projectConfiguration(path: String): ResolvedIocConfiguration {
        return ResolvedIocConfiguration(
            backendId = PackagingBackendId.TABOOLIB,
            sourcePackage = TaboolibIocResolver.SOURCE_PACKAGE,
            targetPackage = TargetPackageResolution("com.example.demo", "project.group"),
            dependencySpec = ProjectDependencySpec(path),
            skipBecauseSubproject = false,
        )
    }
}