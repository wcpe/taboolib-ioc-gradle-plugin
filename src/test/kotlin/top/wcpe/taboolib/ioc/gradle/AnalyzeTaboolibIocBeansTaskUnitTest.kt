package top.wcpe.taboolib.ioc.gradle

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
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
    }
}