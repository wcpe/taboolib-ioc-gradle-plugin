package top.wcpe.taboolib.ioc.gradle

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import org.junit.jupiter.api.io.TempDir
import top.wcpe.taboolib.ioc.gradle.analysis.BytecodeBeanIndexBuilder
import top.wcpe.taboolib.ioc.gradle.analysis.StaticDiagnosisEngine

class StaticDiagnosisEngineUnitTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun evaluatesEnabledConditionsAndGenericCandidatesWithoutFalseWarnings() {
        val classesDir = StaticDiagnosisFixtureSources.compileJavaSources(tempDir)
        val index = BytecodeBeanIndexBuilder.build(listOf(classesDir))
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
}