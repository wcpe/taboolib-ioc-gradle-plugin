package top.wcpe.taboolib.ioc.gradle

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.io.TempDir
import top.wcpe.taboolib.ioc.gradle.analysis.AnalyzeTaboolibIocBeansTask

class AnalyzeTaboolibIocBeansTaskUnitTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun writesStructuredStaticDiagnosisReport() {
        val classesDir = StaticDiagnosisFixtureSources.compileJavaSources(tempDir.resolve("compiled"))
        val project = ProjectBuilder.builder()
            .withName("analysis-task")
            .withProjectDir(Files.createTempDirectory("analysis-task").toFile())
            .build()

        val task = project.tasks.create("analyzeTaboolibIocBeans", AnalyzeTaboolibIocBeansTask::class.java)
        val reportFile = tempDir.resolve("report.json").toFile()
        task.classDirectories.from(classesDir.toFile())
        task.sourceDirectories.from(tempDir.resolve("compiled/src").toFile())
        task.failOnError.set(false)
        task.failOnWarning.set(false)
        task.projectPropertiesInput.put("feature.enabled", "on")
        task.reportFile.set(reportFile)

        task.generateReport()

        val report = reportFile.readText()
        assertContains(report, "beanIndex")
        assertContains(report, "injectionPointIndex")
        assertContains(report, "missing-bean")
        assertContains(report, "named-bean-not-found")
        assertContains(report, "named-bean-type-mismatch")
        assertContains(report, "multiple-primary-beans")
        assertContains(report, "multiple-candidates-unqualified")
        assertContains(report, "conditional-bean-only")
        assertContains(report, "runtime-manual-bean-only")
        assertContains(report, "component-scan-may-exclude")
        assertContains(report, "dependencyGenericType")
        assertContains(report, "conditions")
        assertContains(report, "sourcePath")
        assertContains(report, "sourceLine")
    }

    @Test
    fun failsWhenErrorGateEnabled() {
        val classesDir = StaticDiagnosisFixtureSources.compileJavaSources(tempDir.resolve("error-gate"))
        val project = ProjectBuilder.builder()
            .withName("analysis-error-gate")
            .withProjectDir(Files.createTempDirectory("analysis-error-gate").toFile())
            .build()

        val task = project.tasks.create("analyzeTaboolibIocBeans", AnalyzeTaboolibIocBeansTask::class.java)
        task.classDirectories.from(classesDir.toFile())
        task.sourceDirectories.from(tempDir.resolve("error-gate/src").toFile())
        task.failOnError.set(true)
        task.failOnWarning.set(false)
        task.projectPropertiesInput.put("feature.enabled", "on")
        task.reportFile.set(tempDir.resolve("error-gate-report.json").toFile())

        val error = assertFailsWith<Exception> {
            task.generateReport()
        }

        assertContains(error.message ?: "", "failOnError=true")
        assertContains(error.message ?: "", "问题明细已按 IDE 可识别格式输出到上方日志")
        assertContains(error.message ?: "", "MissingBeanConsumer#constructor[0]")
        assertContains(error.message ?: "", "missing-bean")
        assertContains(error.message ?: "", "source: Consumers.java")
    }

    @Test
    fun failsWhenWarningGateEnabled() {
        val classesDir = StaticDiagnosisFixtureSources.compileJavaSources(tempDir.resolve("warning-gate"))
        val project = ProjectBuilder.builder()
            .withName("analysis-warning-gate")
            .withProjectDir(Files.createTempDirectory("analysis-warning-gate").toFile())
            .build()

        val task = project.tasks.create("analyzeTaboolibIocBeans", AnalyzeTaboolibIocBeansTask::class.java)
        task.classDirectories.from(classesDir.toFile())
        task.sourceDirectories.from(tempDir.resolve("warning-gate/src").toFile())
        task.failOnError.set(false)
        task.failOnWarning.set(true)
        task.projectPropertiesInput.put("feature.enabled", "on")
        task.reportFile.set(tempDir.resolve("warning-gate-report.json").toFile())

        val error = assertFailsWith<Exception> {
            task.generateReport()
        }

        assertContains(error.message ?: "", "failOnWarning=true")
        assertContains(error.message ?: "", "问题明细已按 IDE 可识别格式输出到上方日志")
        assertContains(error.message ?: "", "conditional-bean-only")
    }
}