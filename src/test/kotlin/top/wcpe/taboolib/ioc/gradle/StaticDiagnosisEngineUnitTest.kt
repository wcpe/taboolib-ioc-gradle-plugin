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


    @Test
    fun detectsMultiplePrimaryBeans() {
        val beanA = makeBean("beanA", "com.example.ServiceA", primary = true)
        val beanB = makeBean("beanB", "com.example.ServiceA", primary = true)
        val ip = makeInjectionPoint("com.example.Consumer", "svc", "com.example.ServiceA", InjectionPointKind.FIELD)
        val index = BytecodeAnalysisIndex(
            classIndex = emptyList(),
            beanIndex = listOf(beanA, beanB),
            injectionPointIndex = listOf(ip),
            missingInjectCandidateIndex = emptyList(),
            componentBeanTypes = emptyList(),
            componentScans = emptyList(),
        )
        val report = StaticDiagnosisEngine.analyze(":test", index)
        val diag = report.diagnostics.single { it.rule == "multiple-primary-beans" }
        assertEquals(DiagnosticSeverity.ERROR, diag.severity)
        assertTrue(diag.candidateBeans.containsAll(listOf("beanA", "beanB")))
    }

    @Test
    fun detectsMultipleCandidatesWithoutQualifier() {
        val beanA = makeBean("beanA", "com.example.ServiceA")
        val beanB = makeBean("beanB", "com.example.ServiceA")
        val ip = makeInjectionPoint("com.example.Consumer", "svc", "com.example.ServiceA", InjectionPointKind.FIELD)
        val index = BytecodeAnalysisIndex(
            classIndex = emptyList(),
            beanIndex = listOf(beanA, beanB),
            injectionPointIndex = listOf(ip),
            missingInjectCandidateIndex = emptyList(),
            componentBeanTypes = emptyList(),
            componentScans = emptyList(),
        )
        val report = StaticDiagnosisEngine.analyze(":test", index)
        val diag = report.diagnostics.single { it.rule == "multiple-candidates-unqualified" }
        assertEquals(DiagnosticSeverity.WARNING, diag.severity)
        assertTrue(diag.candidateBeans.containsAll(listOf("beanA", "beanB")))
    }

    @Test
    fun detectsNamedBeanNotFound() {
        val ip = makeInjectionPoint("com.example.Consumer", "svc", "com.example.ServiceA", InjectionPointKind.FIELD, qualifierName = "nonexistent")
        val index = BytecodeAnalysisIndex(
            classIndex = emptyList(),
            beanIndex = emptyList(),
            injectionPointIndex = listOf(ip),
            missingInjectCandidateIndex = emptyList(),
            componentBeanTypes = emptyList(),
            componentScans = emptyList(),
        )
        val report = StaticDiagnosisEngine.analyze(":test", index)
        val diag = report.diagnostics.single { it.rule == "named-bean-not-found" }
        assertEquals(DiagnosticSeverity.ERROR, diag.severity)
    }

    @Test
    fun detectsNamedBeanTypeMismatch() {
        val bean = makeBean("myBean", "com.example.OtherService")
        val ip = makeInjectionPoint("com.example.Consumer", "svc", "com.example.ServiceA", InjectionPointKind.FIELD, qualifierName = "myBean")
        val index = BytecodeAnalysisIndex(
            classIndex = emptyList(),
            beanIndex = listOf(bean),
            injectionPointIndex = listOf(ip),
            missingInjectCandidateIndex = emptyList(),
            componentBeanTypes = emptyList(),
            componentScans = emptyList(),
        )
        val report = StaticDiagnosisEngine.analyze(":test", index)
        val diag = report.diagnostics.single { it.rule == "named-bean-type-mismatch" }
        assertEquals(DiagnosticSeverity.ERROR, diag.severity)
        assertTrue(diag.candidateBeans.contains("myBean"))
    }

    @Test
    fun reportsRuntimeManualBeanOnlyForOptionalMissingDependency() {
        val ip = makeInjectionPoint("com.example.Consumer", "svc", "com.example.ServiceA", InjectionPointKind.FIELD, required = false)
        val index = BytecodeAnalysisIndex(
            classIndex = emptyList(),
            beanIndex = emptyList(),
            injectionPointIndex = listOf(ip),
            missingInjectCandidateIndex = emptyList(),
            componentBeanTypes = emptyList(),
            componentScans = emptyList(),
        )
        val report = StaticDiagnosisEngine.analyze(":test", index)
        val diag = report.diagnostics.single { it.rule == "runtime-manual-bean-only" }
        assertEquals(DiagnosticSeverity.WARNING, diag.severity)
    }

    private fun makeBean(beanName: String, className: String, primary: Boolean = false, scope: String? = null) = BeanDefinition(
        ownerClassName = className,
        declarationName = beanName,
        beanName = beanName,
        exposedType = className,
        packageName = className.substringBeforeLast('.'),
        sourceFile = null,
        kind = BeanKind.CLASS,
        exposedGenericType = null,
        primary = primary,
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
        qualifierName: String? = null,
        required: Boolean = true,
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
        qualifierName = qualifierName,
        required = required,
    )
}