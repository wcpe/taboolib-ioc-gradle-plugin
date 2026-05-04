package top.wcpe.taboolib.ioc.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.wcpe.taboolib.ioc.gradle.analysis.*

class RefreshScopeAnalysisUnitTest {

    @Test
    fun warnsWhenRefreshScopeBeanHasResourceFieldsWithoutPreDestroy() {
        val bean = createBean("dbService", "com.example.DbService", scope = "refresh")
        val classEntry = createClassEntry("com.example.DbService", fields = listOf(
            FieldInfo("connection", "java.sql.Connection", "Ljava/sql/Connection;"),
        ))

        val diagnostics = analyzeRefreshScope(bean, classEntry)

        assertEquals(1, diagnostics.size)
        val d = diagnostics.first()
        assertEquals(DiagnosticSeverity.WARNING, d.severity)
        assertEquals("refresh-scope-missing-predestroy", d.rule)
        assertTrue(d.message.contains("@RefreshScope"))
    }

    @Test
    fun noWarnWhenRefreshScopeBeanHasPreDestroy() {
        val bean = createBean(
            "dbService", "com.example.DbService", scope = "refresh",
            preDestroyMethods = listOf("cleanup"),
        )
        val classEntry = createClassEntry("com.example.DbService", fields = listOf(
            FieldInfo("connection", "java.sql.Connection", "Ljava/sql/Connection;"),
        ))

        val diagnostics = analyzeRefreshScope(bean, classEntry)

        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun noWarnWhenRefreshScopeBeanHasNoResourceFields() {
        val bean = createBean("configService", "com.example.ConfigService", scope = "refresh")
        val classEntry = createClassEntry("com.example.ConfigService", fields = listOf(
            FieldInfo("config", "java.lang.String", "Ljava/lang/String;"),
        ))

        val diagnostics = analyzeRefreshScope(bean, classEntry)

        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun noWarnForNonRefreshScopeBean() {
        val bean = createBean("dbService", "com.example.DbService", scope = "singleton")
        val classEntry = createClassEntry("com.example.DbService", fields = listOf(
            FieldInfo("connection", "java.sql.Connection", "Ljava/sql/Connection;"),
        ))

        val diagnostics = analyzeRefreshScope(bean, classEntry)

        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun detectsAllCommonResourceTypes() {
        val resourceTypes = listOf(
            "java.sql.Connection",
            "java.io.InputStream",
            "java.io.OutputStream",
            "java.io.Reader",
            "java.io.Writer",
            "java.net.Socket",
            "java.nio.channels.Channel",
            "javax.sql.DataSource",
        )

        for (resourceType in resourceTypes) {
            val bean = createBean("service", "com.example.Service", scope = "refresh")
            val classEntry = createClassEntry("com.example.Service", fields = listOf(
                FieldInfo("resource", resourceType, "L${resourceType.replace('.', '/')};"),
            ))
            val diagnostics = analyzeRefreshScope(bean, classEntry)
            assertEquals(1, diagnostics.size, "应检测到资源类型: $resourceType")
        }
    }

    // 直接调用 StaticDiagnosisEngine 的逻辑（通过构造 index 并调用 analyze）
    private fun analyzeRefreshScope(bean: BeanDefinition, classEntry: ClassIndexEntry): List<StaticDiagnostic> {
        val index = BytecodeAnalysisIndex(
            classIndex = listOf(classEntry),
            beanIndex = listOf(bean),
            injectionPointIndex = emptyList(),
            missingInjectCandidateIndex = emptyList(),
            componentBeanTypes = emptyList(),
            componentScans = emptyList(),
        )
        val report = StaticDiagnosisEngine.analyze(":test", index)
        return report.diagnostics.filter { it.rule == "refresh-scope-missing-predestroy" }
    }

    private fun createBean(
        beanName: String,
        className: String,
        scope: String? = null,
        preDestroyMethods: List<String> = emptyList(),
    ) = BeanDefinition(
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
        lifecycleMethods = LifecycleMethodsInfo(preDestroyMethods = preDestroyMethods),
    )

    private fun createClassEntry(className: String, fields: List<FieldInfo> = emptyList()) = ClassIndexEntry(
        className = className,
        packageName = className.substringBeforeLast('.'),
        sourceFile = null,
        superClassName = null,
        interfaceNames = emptyList(),
        fields = fields,
    )
}
