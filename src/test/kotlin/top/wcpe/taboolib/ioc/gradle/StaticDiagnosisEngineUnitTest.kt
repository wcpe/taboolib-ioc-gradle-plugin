package top.wcpe.taboolib.ioc.gradle

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import top.wcpe.taboolib.ioc.gradle.analysis.BytecodeBeanIndexBuilder
import top.wcpe.taboolib.ioc.gradle.analysis.DiagnosticSeverity
import top.wcpe.taboolib.ioc.gradle.analysis.StaticDiagnosisEngine

class StaticDiagnosisEngineUnitTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun evaluatesEnabledConditionsAndGenericCandidatesWithoutFalseWarnings() {
        val classesDir = StaticDiagnosisFixtureSources.compileJavaSources(tempDir)
        val index = BytecodeBeanIndexBuilder.build(listOf(classesDir), listOf(tempDir.resolve("src")))
        val report = StaticDiagnosisEngine.analyze(
            projectPath = ":fixture",
            index = index,
            projectProperties = mapOf("feature.enabled" to "on"),
        )

        assertFalse(report.diagnostics.any { it.ownerClassName.endsWith("EnabledConditionalConsumer") })
        assertFalse(report.diagnostics.any { it.ownerClassName.endsWith("MissingClassConditionalConsumer") })
        assertFalse(report.diagnostics.any { it.ownerClassName.endsWith("BeanConditionalConsumer") })
        assertFalse(report.diagnostics.any { it.ownerClassName.endsWith("GenericStringConsumer") })
    }

    @Test
    fun treatsDisabledConditionalCandidatesAsMissingBeanErrors() {
        val classesDir = StaticDiagnosisFixtureSources.compileJavaSources(tempDir.resolve("disabled-conditions"))
        val index = BytecodeBeanIndexBuilder.build(listOf(classesDir), listOf(tempDir.resolve("disabled-conditions/src")))
        val report = StaticDiagnosisEngine.analyze(
            projectPath = ":fixture",
            index = index,
            projectProperties = mapOf("feature.enabled" to "on"),
        )

        val conditionalOnly = report.diagnostics.single {
            it.ownerClassName.endsWith("ConditionalOnlyConsumer")
        }
        assertEquals(DiagnosticSeverity.ERROR, conditionalOnly.severity)
        assertEquals("missing-bean", conditionalOnly.rule)

        val unknownConditional = report.diagnostics.single {
            it.ownerClassName.endsWith("UnknownConditionalConsumer")
        }
        assertEquals(DiagnosticSeverity.WARNING, unknownConditional.severity)
        assertEquals("conditional-bean-only", unknownConditional.rule)
        assertTrue(unknownConditional.candidateBeans.contains("unknownConditionalService"))
    }
}