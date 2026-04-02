package top.wcpe.taboolib.ioc.gradle

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class TaboolibIocPluginFunctionalTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun registersDiagnosticTasksWithoutTaboolib() {
        val project = FunctionalTestProject(tempDir.resolve("smoke")).writeFixture(
            FixtureOptions(
                applyMockTaboolib = false,
                includeIocLibrary = false,
                rootGroup = "com.example.smoke",
                consumerGroup = "com.example.smoke.consumer",
                localProjectPath = null,
            ),
        )

        val result = project.build(":consumer:tasks", "--all")
        assertContains(result.output, "taboolibIocDoctor")
        assertContains(result.output, "verifyTaboolibIoc")
    }

    @Test
    fun doctorTaskReportsSuccessfulResolutionAndTakeoverState() {
        val project = FunctionalTestProject(tempDir.resolve("doctor-success")).writeFixture(
            FixtureOptions(),
        )

        val result = project.build(":consumer:taboolibIocDoctor")

        assertContains(result.output, "[taboolibIocDoctor] dependency = project(:ioc-lib)")
        assertContains(result.output, "[taboolibIocDoctor] configured = true")
        assertContains(result.output, "top.wcpe.taboolib.ioc -> com.example.root.ioc")
    }

    @Test
    fun doctorTaskReportsResolutionFailureWithoutCrashing() {
        val project = FunctionalTestProject(tempDir.resolve("doctor-failure")).writeFixture(
            FixtureOptions(
                rootGroup = null,
                consumerGroup = null,
                localProjectPath = null,
                includeIocLibrary = false,
            ),
        )

        val result = project.build(":consumer:taboolibIocDoctor")

        assertContains(result.output, "[taboolibIocDoctor] resolution = FAILED")
        assertContains(result.output, "[taboolibIocDoctor] configured = false")
    }

    @Test
    fun failsBuildWhenTaboolibPluginMissing() {
        val project = FunctionalTestProject(tempDir.resolve("missing-taboolib")).writeFixture(
            FixtureOptions(
                applyMockTaboolib = false,
                includeIocLibrary = false,
                rootGroup = "com.example.missing",
                consumerGroup = "com.example.missing.consumer",
                localProjectPath = null,
            ),
        )

        val result = project.build(":consumer:jar", expectFailure = true)
        assertContains(result.output, "当前工程未应用 io.izzel.taboolib")
    }

    @Test
    fun relocatesEmbeddedIocLibraryUsingExplicitTargetPackage() {
        val project = FunctionalTestProject(tempDir.resolve("explicit-target")).writeFixture(
            FixtureOptions(explicitTargetPackage = "com.example.explicit"),
        )

        project.build(":consumer:build")
        val entries = project.consumerJarEntries()
        assertTrue(entries.contains("com/example/explicit/ioc/SampleService.class"))
        assertFalse(entries.contains("top/wcpe/taboolib/ioc/SampleService.class"))
    }

    @Test
    fun fallsBackToTaboolibEnvGroupGradleProperty() {
        val project = FunctionalTestProject(tempDir.resolve("env-group-fallback")).writeFixture(
            FixtureOptions(
                explicitTargetPackage = null,
                rootGroup = null,
                consumerGroup = null,
                gradleProperties = mapOf("taboolib.env.group" to "com.example.fromprop"),
            ),
        )

        project.build(":consumer:build")
        val entries = project.consumerJarEntries()
        assertTrue(entries.contains("com/example/fromprop/ioc/SampleService.class"))
    }

    @Test
    fun fallsBackToProjectGroupForSubprojectBuild() {
        val project = FunctionalTestProject(tempDir.resolve("project-group-fallback")).writeFixture(
            FixtureOptions(
                explicitTargetPackage = null,
                rootGroup = "com.example.inherited",
                consumerGroup = null,
            ),
        )

        project.build(":consumer:build")
        val entries = project.consumerJarEntries()
        assertTrue(entries.contains("com/example/inherited/ioc/SampleService.class"))
    }

    @Test
    fun failsOnManualRelocationConflict() {
        val project = FunctionalTestProject(tempDir.resolve("relocation-conflict")).writeFixture(
            FixtureOptions(manualRelocationTarget = "com.manual.override")
        )

        val result = project.build(":consumer:build", expectFailure = true)
        assertContains(result.output, "手写 relocate 与自动 IoC relocate 冲突")
    }

    @Test
    fun skipsAutoTakeoverWhenDisabled() {
        val project = FunctionalTestProject(tempDir.resolve("disabled-auto-takeover")).writeFixture(
            FixtureOptions(autoTakeover = false),
        )

        project.build(":consumer:build")
        val entries = project.consumerJarEntries()
        assertFalse(entries.contains("top/wcpe/taboolib/ioc/SampleService.class"))
        assertFalse(entries.contains("com/example/root/ioc/SampleService.class"))
    }

    @Test
    fun verifyTaskLogsSkipMessageWhenAutoTakeoverDisabled() {
        val project = FunctionalTestProject(tempDir.resolve("disabled-verify-task")).writeFixture(
            FixtureOptions(autoTakeover = false),
        )

        val result = project.build(":consumer:verifyTaboolibIoc")

        assertContains(result.output, "taboolibIoc.autoTakeover=false，已跳过自动接管验证")
    }

    @Test
    fun skipsTakeoverWhenTaboolibMarksSubproject() {
        val project = FunctionalTestProject(tempDir.resolve("subproject-skip")).writeFixture(
            FixtureOptions(taboolibSubproject = true),
        )

        project.build(":consumer:build")
        val entries = project.consumerJarEntries()
        assertFalse(entries.contains("top/wcpe/taboolib/ioc/SampleService.class"))
        assertFalse(entries.contains("com/example/root/ioc/SampleService.class"))
    }
}