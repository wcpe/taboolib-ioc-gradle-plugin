package top.wcpe.taboolib.ioc.gradle

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import top.wcpe.taboolib.ioc.gradle.analysis.*

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
        assertFalse(report.diagnostics.any { it.ownerClassName.endsWith(".ComponentConsumer") })
        assertFalse(report.diagnostics.any { it.ownerClassName.endsWith(".InitializedComponentConsumer") })
        assertFalse(report.diagnostics.any { it.ownerClassName.endsWith(".ManualAssignedComponentConsumer") })
        assertFalse(report.diagnostics.any { it.ownerClassName.endsWith(".KotlinObjectInitializedComponentConsumer") })
        assertFalse(report.diagnostics.any { it.ownerClassName.endsWith(".KotlinObjectLikeConsumer") })
    }

    @Test
    fun reportsMissingInjectAnnotationForReferencedComponentFields() {
        val classesDir = StaticDiagnosisFixtureSources.compileJavaSources(tempDir.resolve("missing-inject"))
        val index = BytecodeBeanIndexBuilder.build(listOf(classesDir), listOf(tempDir.resolve("missing-inject/src")))
        val report = StaticDiagnosisEngine.analyze(
            projectPath = ":fixture",
            index = index,
            projectProperties = mapOf("feature.enabled" to "on"),
        )

        val fieldReference = report.diagnostics.single {
            it.ownerClassName.endsWith("MissingInjectComponentConsumer")
        }
        assertEquals(DiagnosticSeverity.ERROR, fieldReference.severity)
        assertEquals("missing-inject-annotation", fieldReference.rule)
        assertTrue(fieldReference.candidateBeans.contains("componentService"))

        val kotlinObjectReference = report.diagnostics.single {
            it.ownerClassName.endsWith("KotlinObjectMissingInjectConsumer")
        }
        assertEquals(DiagnosticSeverity.ERROR, kotlinObjectReference.severity)
        assertEquals("missing-inject-annotation", kotlinObjectReference.rule)
        assertTrue(kotlinObjectReference.candidateBeans.contains("componentService"))
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

    @Test
    fun detectsCircularDependenciesInAnalysis() {
        val beanA = makeBean("beanA", "com.example.A")
        val beanB = makeBean("beanB", "com.example.B")
        val ipAtoB = makeInjectionPoint("com.example.A", "beanB", "com.example.B", InjectionPointKind.CONSTRUCTOR_PARAMETER)
        val ipBtoA = makeInjectionPoint("com.example.B", "beanA", "com.example.A", InjectionPointKind.CONSTRUCTOR_PARAMETER)

        val index = BytecodeAnalysisIndex(
            classIndex = emptyList(),
            beanIndex = listOf(beanA, beanB),
            injectionPointIndex = listOf(ipAtoB, ipBtoA),
            missingInjectCandidateIndex = emptyList(),
            componentBeanTypes = emptyList(),
            componentScans = emptyList(),
        )
        val report = StaticDiagnosisEngine.analyze(":test", index)
        val cycleDiag = report.diagnostics.filter { it.rule == "circular-dependency-detected" }

        assertEquals(1, cycleDiag.size)
        assertEquals(DiagnosticSeverity.ERROR, cycleDiag.first().severity)
        assertTrue(cycleDiag.first().candidateBeans.containsAll(listOf("beanA", "beanB")))
    }

    @Test
    fun analyzesRefreshScopeResourceManagement() {
        val bean = makeBean("dbService", "com.example.DbService", scope = "refresh")
        val classEntry = ClassIndexEntry(
            className = "com.example.DbService",
            packageName = "com.example",
            sourceFile = null,
            superClassName = null,
            interfaceNames = emptyList(),
            fields = listOf(FieldInfo("conn", "java.sql.Connection", "Ljava/sql/Connection;")),
        )
        val index = BytecodeAnalysisIndex(
            classIndex = listOf(classEntry),
            beanIndex = listOf(bean),
            injectionPointIndex = emptyList(),
            missingInjectCandidateIndex = emptyList(),
            componentBeanTypes = emptyList(),
            componentScans = emptyList(),
        )
        val report = StaticDiagnosisEngine.analyze(":test", index)
        val diag = report.diagnostics.single { it.rule == "refresh-scope-missing-predestroy" }

        assertEquals(DiagnosticSeverity.WARNING, diag.severity)
        assertEquals("com.example.DbService", diag.ownerClassName)
    }

    @Test
    fun providesThreadScopeUsageHints() {
        val bean = makeBean("reqCtx", "com.example.RequestContext", scope = "thread")
        val index = BytecodeAnalysisIndex(
            classIndex = emptyList(),
            beanIndex = listOf(bean),
            injectionPointIndex = emptyList(),
            missingInjectCandidateIndex = emptyList(),
            componentBeanTypes = emptyList(),
            componentScans = emptyList(),
        )
        val report = StaticDiagnosisEngine.analyze(":test", index)
        val diag = report.diagnostics.single { it.rule == "thread-scope-usage-warning" }

        assertEquals(DiagnosticSeverity.INFO, diag.severity)
        assertEquals("com.example.RequestContext", diag.ownerClassName)
    }

    private fun makeBean(beanName: String, className: String, scope: String? = null) = BeanDefinition(
        ownerClassName = className,
        declarationName = beanName,
        beanName = beanName,
        exposedType = className,
        packageName = className.substringBeforeLast('.'),
        sourceFile = null,
        kind = BeanKind.CLASS,
        exposedGenericType = null,
        primary = false,
        order = null,
        conditionalAnnotations = emptyList(),
        conditions = emptyList(),
        scope = scope,
    )

    private fun makeInjectionPoint(
        ownerClass: String,
        declName: String,
        depType: String,
        kind: InjectionPointKind,
    ) = InjectionPointDefinition(
        ownerClassName = ownerClass,
        declarationName = declName,
        dependencyType = depType,
        dependencyGenericType = null,
        ownerPackage = ownerClass.substringBeforeLast('.'),
        sourceFile = null,
        sourcePath = null,
        sourceLine = null,
        sourceColumn = null,
        kind = kind,
        parameterIndex = null,
        qualifierName = null,
        required = true,
    )
}