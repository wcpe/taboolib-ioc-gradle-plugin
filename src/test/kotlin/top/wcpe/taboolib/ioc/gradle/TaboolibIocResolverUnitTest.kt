package top.wcpe.taboolib.ioc.gradle

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import top.wcpe.taboolib.ioc.gradle.backend.PackagingBackendId
import top.wcpe.taboolib.ioc.gradle.model.ModuleDependencySpec
import top.wcpe.taboolib.ioc.gradle.model.ProjectDependencySpec

class TaboolibIocResolverUnitTest {

    @Test
    fun explicitTargetPackageHasHighestPriority() {
        val project: Project = buildProject("resolver-explicit")
        project.group = "com.example.project"
        val extension = createExtension(project).apply {
            targetPackage.set("com.example.explicit")
            iocVersion.set("1.2.3")
        }
        project.extensions.add("taboolib", FakeTaboolibExtension(env = FakeTaboolibEnv("com.example.env")))

        val resolved = TaboolibIocResolver(project, extension).resolve()

        assertEquals("taboolibIoc.targetPackage", resolved.targetPackage.source)
        assertEquals("com.example.explicit.ioc", resolved.targetPackage.relocationTarget)
        assertEquals(
            ModuleDependencySpec("top.wcpe.taboolib.ioc.properties", "taboolib-ioc", "1.2.3"),
            resolved.dependencySpec,
        )
    }

    @Test
    fun taboolibEnvGroupIsUsedWhenExplicitTargetMissing() {
        val project: Project = buildProject("resolver-env")
        project.group = "com.example.project"
        val extension = createExtension(project)
        project.extensions.add("taboolib", FakeTaboolibExtension(env = FakeTaboolibEnv("com.example.env")))

        val resolved = TaboolibIocResolver(project, extension).resolve()

        assertEquals("taboolib.env.group", resolved.targetPackage.source)
        assertEquals("com.example.env.ioc", resolved.targetPackage.relocationTarget)
    }

    @Test
    fun projectGroupIsUsedWhenNoHigherPriorityTargetExists() {
        val project: Project = buildProject("resolver-project-group")
        project.group = "com.example.project"
        val extension = createExtension(project)

        val resolved = TaboolibIocResolver(project, extension).resolve()

        assertEquals("project.group", resolved.targetPackage.source)
        assertEquals("com.example.project.ioc", resolved.targetPackage.relocationTarget)
    }

    @Test
    fun resolveFailsWhenNoTargetPackageSourceExists() {
        val project = buildProject("resolver-no-target")
        val extension = createExtension(project)

        val error = assertFailsWith<TaboolibIocConfigurationException> {
            TaboolibIocResolver(project, extension).resolve()
        }

        assertContains(error.message ?: "", "无法推导 IoC 目标包")
    }

    @Test
    fun resolveUsesProjectDependencyWhenLocalProjectExists() {
        val rootProject: Project = buildProject("resolver-local-root")
        rootProject.group = "com.example.root"
        ProjectBuilder.builder()
            .withName("ioc-lib")
            .withProjectDir(Files.createTempDirectory("ioc-lib-child").toFile())
            .withParent(rootProject)
            .build()
        val extension = createExtension(rootProject).apply {
            useLocalProject(":ioc-lib")
        }

        val resolved = TaboolibIocResolver(rootProject, extension).resolve()

        val dependencySpec = assertIs<ProjectDependencySpec>(resolved.dependencySpec)
        assertEquals(":ioc-lib", dependencySpec.path)
        assertEquals("project(:ioc-lib)", dependencySpec.displayName)
    }

    @Test
    fun resolveFailsWhenLocalProjectAndExternalNotationAreBothConfigured() {
        val project: Project = buildProject("resolver-conflict")
        project.group = "com.example.project"
        val extension = createExtension(project).apply {
            useLocalProject(":ioc-lib")
            dependency("com.example:demo:1.0.0")
        }

        val error = assertFailsWith<TaboolibIocConfigurationException> {
            TaboolibIocResolver(project, extension).resolve()
        }

        assertContains(error.message ?: "", "不能同时设置")
    }

    @Test
    fun resolveFailsWhenConfiguredLocalProjectDoesNotExist() {
        val project: Project = buildProject("resolver-missing-local")
        project.group = "com.example.project"
        val extension = createExtension(project).apply {
            useLocalProject(":ioc-lib")
        }

        val error = assertFailsWith<TaboolibIocConfigurationException> {
            TaboolibIocResolver(project, extension).resolve()
        }

        assertContains(error.message ?: "", "未找到本地联调项目")
    }

    @Test
    fun resolveUsesExternalDependencyNotationWhenConfigured() {
        val project: Project = buildProject("resolver-module")
        project.group = "com.example.project"
        val extension = createExtension(project).apply {
            dependency("com.example:demo:2.0.0")
        }

        val resolved = TaboolibIocResolver(project, extension).resolve()

        assertEquals(ModuleDependencySpec("com.example", "demo", "2.0.0"), resolved.dependencySpec)
    }

    @Test
    fun readsExistingRelocationsAndSubprojectFlagFromTaboolibExtension() {
        val project = buildProject("resolver-taboolib-state")
        val extension = createExtension(project)
        project.extensions.add(
            "taboolib",
            FakeTaboolibExtension(
                subproject = true,
                relocation = linkedMapOf("top.wcpe.taboolib.ioc.properties" to "com.example.demo.ioc"),
            ),
        )
        val resolver = TaboolibIocResolver(project, extension)

        assertTrue(resolver.isTaboolibSubproject())
        assertEquals(
            mapOf("top.wcpe.taboolib.ioc.properties" to "com.example.demo.ioc"),
            resolver.readExistingRelocations(),
        )
    }

    @Test
    fun returnsEmptyRelocationsWhenTaboolibExtensionIsMissing() {
        val project = buildProject("resolver-empty-relocations")
        val extension = createExtension(project)
        val resolver = TaboolibIocResolver(project, extension)

        assertFalse(resolver.isTaboolibSubproject())
        assertTrue(resolver.readExistingRelocations().isEmpty())
    }

    private fun buildProject(name: String): Project {
        return ProjectBuilder.builder()
            .withName(name)
            .withProjectDir(Files.createTempDirectory(name).toFile())
            .build()
    }

    private fun createExtension(project: Project): TaboolibIocExtension {
        return project.extensions.create("taboolibIoc", TaboolibIocExtension::class.java).apply {
            autoTakeover.set(true)
            backend.set(PackagingBackendId.TABOOLIB)
            iocVersion.set("1.0.0-SNAPSHOT")
        }
    }

    private data class FakeTaboolibEnv(
        val group: String? = null,
    )

    private data class FakeTaboolibExtension(
        val subproject: Boolean = false,
        val relocation: MutableMap<String, String> = linkedMapOf(),
        val env: FakeTaboolibEnv = FakeTaboolibEnv(),
    )
}