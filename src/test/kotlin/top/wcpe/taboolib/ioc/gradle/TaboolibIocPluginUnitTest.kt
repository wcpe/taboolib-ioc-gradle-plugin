package top.wcpe.taboolib.ioc.gradle

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import top.wcpe.taboolib.ioc.gradle.analysis.AnalyzeTaboolibIocBeansTask
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
        // 织入计划任务（阶段①）：同样默认禁用
        val planTask = project.tasks.findByName("planTaboolibIocAop")
        assertNotNull(planTask)
        assertTrue(!planTask!!.enabled, "织入计划任务默认关闭")
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

    /**
     * §2.4 第 4 条：`weaving` 进入 `analyzeTaboolibIocBeans` 的 `@Input` 后，
     * 改开关必须改变任务输入快照（即触发任务重跑），否则诊断结论会随织入状态变化却不重跑。
     */
    @Test
    fun weavingSwitchIsRegisteredAsAnalysisTaskInput() {
        val project: Project = ProjectBuilder.builder()
            .withName("plugin-weaving-input")
            .withProjectDir(Files.createTempDirectory("plugin-weaving-input").toFile())
            .build()

        project.pluginManager.apply(TaboolibIocPlugin::class.java)

        val task = project.tasks.getByName("analyzeTaboolibIocBeans") as AnalyzeTaboolibIocBeansTask
        val extension: TaboolibIocExtension = project.extensions.getByType(TaboolibIocExtension::class.java)

        val beforeProperties = task.inputs.properties.toMap()
        assertEquals(false, beforeProperties["weaving"], "weaving 应作为 @Input 出现在任务输入中")
        assertFalse(task.weaving.get())

        extension.weaving.set(true)

        assertTrue(task.weaving.get(), "扩展开关应经 convention 传入任务")
        val afterProperties = task.inputs.properties.toMap()
        assertEquals(true, afterProperties["weaving"], "weaving 输入应随扩展开关翻转")
        assertNotEquals(beforeProperties, afterProperties, "改 weaving 必须触发任务输入变化（任务重跑）")
    }

    /**
     * §2.4 第 7/8 条：`weaving=false`（默认）时诊断任务**不消费计划文件** → 任务图与既有行为逐字一致；
     * 且 `planTaboolibIocAop` 存在但被禁用。
     */
    @Test
    fun analyzeTaskHasNoWeavePlanInputWhenWeavingDisabled() {
        val project: Project = ProjectBuilder.builder()
            .withName("plugin-plan-input")
            .withProjectDir(Files.createTempDirectory("plugin-plan-input").toFile())
            .build()

        project.pluginManager.apply(TaboolibIocPlugin::class.java)

        val task = project.tasks.getByName("analyzeTaboolibIocBeans") as AnalyzeTaboolibIocBeansTask
        assertFalse(task.weaving.get(), "weaving 默认关闭")
        assertFalse(task.weavePlanFile.isPresent, "weaving=false 时 analyze 不应有 plan 输入（任务图零改动）")
        assertFalse(
            project.tasks.getByName("planTaboolibIocAop").enabled,
            "weaving=false 时 plan 任务应为禁用（SKIPPED）",
        )
    }
}