package top.wcpe.taboolib.ioc.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.wcpe.taboolib.ioc.gradle.analysis.*

class ThreadScopeAnalysisUnitTest {

    @Test
    fun providesInfoForThreadScopeBeans() {
        val bean = createBean("requestContext", "com.example.RequestContext", scope = "thread")
        val report = analyze(bean)
        val diagnostics = report.filter { it.rule == "thread-scope-usage-warning" }

        assertEquals(1, diagnostics.size)
        val d = diagnostics.first()
        assertEquals(DiagnosticSeverity.INFO, d.severity)
        assertTrue(d.message.contains("@ThreadScope") || d.message.contains("clearCurrentThread"))
        assertEquals("com.example.RequestContext", d.ownerClassName)
    }

    @Test
    fun noWarnForNonThreadScopeBeans() {
        val bean = createBean("normalService", "com.example.NormalService", scope = "singleton")
        val diagnostics = analyze(bean).filter { it.rule == "thread-scope-usage-warning" }
        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun noWarnForBeanWithNullScope() {
        val bean = createBean("defaultService", "com.example.DefaultService", scope = null)
        val diagnostics = analyze(bean).filter { it.rule == "thread-scope-usage-warning" }
        assertTrue(diagnostics.isEmpty())
    }

    private fun analyze(bean: BeanDefinition): List<StaticDiagnostic> {
        val index = BytecodeAnalysisIndex(
            classIndex = emptyList(),
            beanIndex = listOf(bean),
            injectionPointIndex = emptyList(),
            missingInjectCandidateIndex = emptyList(),
            componentBeanTypes = emptyList(),
            componentScans = emptyList(),
        )
        return StaticDiagnosisEngine.analyze(":test", index).diagnostics
    }

    private fun createBean(beanName: String, className: String, scope: String?) = BeanDefinition(
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
}
