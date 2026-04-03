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
        assertContains(result.output, "analyzeTaboolibIocBeans")
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

    @Test
    fun analyzeTaskWritesRequestedStaticDiagnosisReport() {
        val project = FunctionalTestProject(tempDir.resolve("static-diagnosis")).writeFixture(
            FixtureOptions(
                applyMockTaboolib = false,
                autoTakeover = false,
                includeIocLibrary = false,
                localProjectPath = null,
                includeStaticDiagnosisSamples = true,
                analysisFailOnError = false,
                analysisFailOnWarning = false,
            ),
        )

        val result = project.build(":consumer:analyzeTaboolibIocBeans")
        val report = project.readRelativeFile("consumer/build/reports/taboolib-ioc/static-diagnosis.json")

        assertContains(result.output, "[analyzeTaboolibIocBeans]")
        assertContains(report, "beanIndex")
        assertContains(report, "injectionPointIndex")
        assertContains(report, "constructor_parameter")
        assertContains(report, "field")
        assertContains(report, "method_parameter")
        assertContains(report, "missing-bean")
        assertContains(report, "named-bean-not-found")
        assertContains(report, "named-bean-type-mismatch")
        assertContains(report, "multiple-primary-beans")
        assertContains(report, "multiple-candidates-unqualified")
        assertContains(report, "conditional-bean-only")
        assertContains(report, "runtime-manual-bean-only")
        assertContains(report, "component-scan-may-exclude")
    }

    @Test
    fun analyzeTaskFailsWhenFailOnErrorIsEnabled() {
        val project = FunctionalTestProject(tempDir.resolve("static-diagnosis-error-gate")).writeFixture(
            FixtureOptions(
                applyMockTaboolib = false,
                autoTakeover = false,
                includeIocLibrary = false,
                localProjectPath = null,
                includeStaticDiagnosisSamples = true,
                analysisFailOnError = true,
            ),
        )

        val result = project.build(":consumer:analyzeTaboolibIocBeans", expectFailure = true)
        val problemsReport = project.readRelativeFile("build/reports/problems/problems-report.html")

        assertContains(result.output, "failOnError=true")
        assertContains(result.output, "问题明细已按 IDE 可识别格式输出到上方日志")
        assertContains(result.output, "MissingBeanConsumer#constructor[0]")
        assertContains(result.output, "missing-bean")
        assertContains(result.output, "error: [missing-bean]")
        assertContains(result.output, "source: Consumers.java")
        assertContains(problemsReport, "Taboolib IoC")
        assertContains(problemsReport, "Static Diagnosis")
        assertContains(problemsReport, "Missing Bean")
        assertContains(problemsReport, "missing-bean")
    }

    @Test
    fun analyzeTaskFailsWhenFailOnWarningIsEnabled() {
        val project = FunctionalTestProject(tempDir.resolve("static-diagnosis-warning-gate")).writeFixture(
            FixtureOptions(
                applyMockTaboolib = false,
                autoTakeover = false,
                includeIocLibrary = false,
                localProjectPath = null,
                includeStaticDiagnosisSamples = true,
                analysisFailOnError = false,
                analysisFailOnWarning = true,
            ),
        )

        val result = project.build(":consumer:analyzeTaboolibIocBeans", expectFailure = true)

        assertContains(result.output, "failOnWarning=true")
        assertContains(result.output, "conditional-bean-only")
        assertContains(result.output, "warning: [conditional-bean-only]")
    }

    @Test
    fun buildFailsByDefaultWhenStaticDiagnosisReportsErrors() {
        val project = FunctionalTestProject(tempDir.resolve("static-diagnosis-build-gate")).writeFixture(
            FixtureOptions(
                applyMockTaboolib = false,
                autoTakeover = false,
                includeIocLibrary = false,
                localProjectPath = null,
                includeStaticDiagnosisSamples = true,
            ),
        )

        val result = project.build(":consumer:build", expectFailure = true)

        assertContains(result.output, ":consumer:analyzeTaboolibIocBeans")
        assertContains(result.output, "failOnError=true")
        assertContains(result.output, "MissingBeanConsumer#constructor[0]")
        assertContains(result.output, "source: Consumers.java")
    }
}