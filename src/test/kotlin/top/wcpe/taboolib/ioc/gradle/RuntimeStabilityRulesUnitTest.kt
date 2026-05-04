package top.wcpe.taboolib.ioc.gradle

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import top.wcpe.taboolib.ioc.gradle.analysis.BytecodeBeanIndexBuilder
import top.wcpe.taboolib.ioc.gradle.analysis.DiagnosticSeverity
import top.wcpe.taboolib.ioc.gradle.analysis.StaticDiagnosisEngine

class RuntimeStabilityRulesUnitTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun ignoresMultipleConstructorsWhenRuntimeWillFallbackToNoArg() {
        val sources = RuntimeStabilityFixtureSources.multipleConstructorsWithNoArgFallback()
        val classesDir = RuntimeStabilityFixtureSources.compileJavaSources(tempDir, sources)
        val index = BytecodeBeanIndexBuilder.build(listOf(classesDir), listOf(tempDir.resolve("src")))
        val report = StaticDiagnosisEngine.analyze(
            projectPath = ":fixture",
            index = index,
            projectProperties = emptyMap(),
        )

        val constructorWarnings = report.diagnostics.filter {
            it.rule == "bean-constructor-not-explicitly-injected"
        }
        assertTrue(constructorWarnings.isEmpty(), "运行时会回退到无参构造时不应触发 constructor-not-explicitly-injected 警告")
    }

    @Test
    fun allowsSingleConstructorBeanWithoutExplicitInject() {
        val sources = RuntimeStabilityFixtureSources.singleConstructorWithoutInject()
        val classesDir = RuntimeStabilityFixtureSources.compileJavaSources(tempDir, sources)
        val index = BytecodeBeanIndexBuilder.build(listOf(classesDir), listOf(tempDir.resolve("src")))
        val report = StaticDiagnosisEngine.analyze(
            projectPath = ":fixture",
            index = index,
            projectProperties = emptyMap(),
        )

        val constructorWarnings = report.diagnostics.filter {
            it.rule == "bean-constructor-not-explicitly-injected"
        }
        assertTrue(constructorWarnings.isEmpty(), "单构造器 Bean 不应触发 constructor-not-explicitly-injected 警告")
    }

    @Test
    fun ignoresKotlinNonNullParametersWhenRuntimeWillFallbackToNoArg() {
        val sources = RuntimeStabilityFixtureSources.kotlinMultiConstructorsWithNoArgFallback()
        val classesDir = RuntimeStabilityFixtureSources.compileJavaSources(tempDir, sources)
        val index = BytecodeBeanIndexBuilder.build(listOf(classesDir), listOf(tempDir.resolve("src")))
        val report = StaticDiagnosisEngine.analyze(
            projectPath = ":fixture",
            index = index,
            projectProperties = emptyMap(),
        )

        val runtimeErrors = report.diagnostics.filter {
            it.rule == "bean-runtime-null-injection-risk"
        }
        assertTrue(runtimeErrors.isEmpty(), "运行时会回退到无参构造时不应触发 runtime-null-injection-risk")
    }

    @Test
    fun detectsComponentAnnotationWhenEnabled() {
        val sources = RuntimeStabilityFixtureSources.componentAnnotatedBean()
        val classesDir = RuntimeStabilityFixtureSources.compileJavaSources(tempDir, sources)
        val index = BytecodeBeanIndexBuilder.build(listOf(classesDir), listOf(tempDir.resolve("src")))
        val report = StaticDiagnosisEngine.analyze(
            projectPath = ":fixture",
            index = index,
            projectProperties = mapOf("taboolib.ioc.forbidComponentAnnotation" to "true"),
        )

        val warning = report.diagnostics.single {
            it.rule == "forbidden-component-annotation"
        }
        assertEquals(DiagnosticSeverity.WARNING, warning.severity)
        assertTrue(warning.ownerClassName.endsWith("ComponentBean"))
        assertTrue(warning.message.contains("@Component"))
    }

    @Test
    fun allowsComponentAnnotationByDefault() {
        val sources = RuntimeStabilityFixtureSources.componentAnnotatedBean()
        val classesDir = RuntimeStabilityFixtureSources.compileJavaSources(tempDir, sources)
        val index = BytecodeBeanIndexBuilder.build(listOf(classesDir), listOf(tempDir.resolve("src")))
        val report = StaticDiagnosisEngine.analyze(
            projectPath = ":fixture",
            index = index,
            projectProperties = emptyMap(),
        )

        val componentWarnings = report.diagnostics.filter {
            it.rule == "forbidden-component-annotation"
        }
        assertTrue(componentWarnings.isEmpty(), "默认情况下不应触发 forbidden-component-annotation 警告")
    }
}
