package top.wcpe.taboolib.ioc.gradle.integration

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import top.wcpe.taboolib.ioc.gradle.integration.fixtures.StaticDiagnosisCase
import top.wcpe.taboolib.ioc.gradle.integration.fixtures.StaticDiagnosisCases

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StaticDiagnosisMatrixTest {

    @TempDir
    lateinit var tempDir: Path

    private val cases = StaticDiagnosisCases.cases()

    private val matrix by lazy {
        val project = GradlePluginFixtureProject(tempDir.resolve("matrix-success"))
            .copyFixture("static-diagnosis-plugin")
        val result = project.build("analyzeTaboolibIocBeans")
        val report = project.readStaticDiagnosisReport()
        MatrixResult(
            output = result.output,
            report = report,
            diagnostics = report.diagnostics(),
        )
    }

    @Test
    fun matrixContainsMoreThanOneHundredCases() {
        assertTrue(cases.size >= 100, "静态诊断矩阵应至少包含 100 个测试用例，实际 ${cases.size}")
    }

    @TestFactory
    fun staticDiagnosisMatrix(): List<DynamicTest> = cases.map { case ->
        DynamicTest.dynamicTest(case.name) {
            assertCase(case, matrix.diagnostics)
        }
    }

    @TestFactory
    fun forbiddenComponentAnnotationCases(): List<DynamicTest> {
        val forbiddenProject = GradlePluginFixtureProject(tempDir.resolve("forbidden-component"))
            .copyFixture("forbidden-component-plugin")
        forbiddenProject.build("analyzeTaboolibIocBeans")
        val diagnostics = forbiddenProject.readStaticDiagnosisReport().diagnostics()
        return StaticDiagnosisCases.forbiddenComponentCases().map { case ->
            DynamicTest.dynamicTest(case.name) {
                assertCase(case, diagnostics)
            }
        }
    }

    @Test
    fun failOnErrorStopsBuildDuringStaticAnalysis() {
        val project = GradlePluginFixtureProject(tempDir.resolve("matrix-fail-on-error"))
            .copyFixture("static-diagnosis-plugin")
            .patchBuildScript("analysisFailOnError = false", "analysisFailOnError = true")

        val result = project.build("analyzeTaboolibIocBeans", expectFailure = true)

        assertContains(result.output, "failOnError=true")
        assertContains(result.output, "missing-bean")
        assertContains(result.output, "named-bean-not-found")
        assertContains(result.output, "multiple-primary-beans")
        assertContains(result.output, "circular-dependency-detected")
    }

    private fun assertCase(case: StaticDiagnosisCase, diagnostics: List<DiagnosticRecord>) {
        val ownerDiagnostics = diagnostics.filter { it.ownerClassName.endsWith(".${case.ownerSimpleName}") }
        if (case.expectedRule == null) {
            assertTrue(
                ownerDiagnostics.isEmpty(),
                "${case.ownerSimpleName} 是正确注入用例，不应产生诊断，实际: $ownerDiagnostics",
            )
            return
        }

        val diagnostic = ownerDiagnostics.firstOrNull {
            it.rule == case.expectedRule && it.severity == case.expectedSeverity
        }
        assertNotNull(
            diagnostic,
            "${case.ownerSimpleName} 应产生 ${case.expectedSeverity} ${case.expectedRule}，实际: $ownerDiagnostics",
        )
    }

    private data class MatrixResult(
        val output: String,
        val report: String,
        val diagnostics: List<DiagnosticRecord>,
    )
}
