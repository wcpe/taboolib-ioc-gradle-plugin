package top.wcpe.taboolib.ioc.gradle

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import top.wcpe.taboolib.ioc.gradle.backend.PackagingBackendId

class TaboolibIocPluginUnitTest {

    @Test
    fun applyingPluginRegistersExtensionAndDiagnosticTasks() {
        val project: Project = ProjectBuilder.builder()
            .withName("plugin-registration")
            .withProjectDir(Files.createTempDirectory("plugin-registration").toFile())
            .build()

        project.pluginManager.apply(TaboolibIocPlugin::class.java)

        assertNotNull(project.extensions.findByName("taboolibIoc"))
        assertNotNull(project.tasks.findByName("analyzeTaboolibIocBeans"))
        assertNotNull(project.tasks.findByName("taboolibIocDoctor"))
        assertNotNull(project.tasks.findByName("verifyTaboolibIoc"))
        // 编译期 AOP 织入任务（②）：默认禁用，但必须注册出来
        val weaveTask = project.tasks.findByName("weaveTaboolibIocAop")
        assertNotNull(weaveTask)
        assertTrue(!weaveTask!!.enabled, "织入默认关闭")
    }

    @Test
    fun applyingPluginSetsExpectedConventions() {
        val project: Project = ProjectBuilder.builder()
            .withName("plugin-conventions")
            .withProjectDir(Files.createTempDirectory("plugin-conventions").toFile())
            .build()
        project.version = "2.0.0"

        project.pluginManager.apply(TaboolibIocPlugin::class.java)
        val extension: TaboolibIocExtension = project.extensions.getByType(TaboolibIocExtension::class.java)

        assertTrue(extension.autoTakeover.get())
        assertEquals(PackagingBackendId.TABOOLIB, extension.backend.get())
        assertNotEquals("2.0.0", extension.iocVersion.get())
        assertEquals(
            TaboolibIocPluginVersionLocator.resolveBundledVersion() ?: TaboolibIocResolver.DEFAULT_IOC_VERSION,
            extension.iocVersion.get(),
        )
        assertTrue(!extension.weaving.get(), "编译期织入默认关闭")
    }
}