package top.wcpe.taboolib.ioc.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.wcpe.taboolib.ioc.gradle.analysis.BeanDefinition
import top.wcpe.taboolib.ioc.gradle.analysis.BeanKind
import top.wcpe.taboolib.ioc.gradle.analysis.CycleDependencyDetector
import top.wcpe.taboolib.ioc.gradle.analysis.CycleDependencyKind
import top.wcpe.taboolib.ioc.gradle.analysis.InjectionPointDefinition
import top.wcpe.taboolib.ioc.gradle.analysis.InjectionPointKind

class CycleDependencyDetectorUnitTest {

    @Test
    fun detectsSimpleTwoNodeCycle() {
        // A → B → A
        val beans = listOf(
            createBean("beanA", "com.example.A"),
            createBean("beanB", "com.example.B"),
        )
        
        val injectionPoints = listOf(
            createInjectionPoint("com.example.A", "beanB", "com.example.B", InjectionPointKind.FIELD),
            createInjectionPoint("com.example.B", "beanA", "com.example.A", InjectionPointKind.FIELD),
        )
        
        val cycles = CycleDependencyDetector.detectCycles(beans, injectionPoints)
        
        assertEquals(1, cycles.size)
        val cycle = cycles.first()
        assertEquals(2, cycle.path.size)
        assertTrue(cycle.path.containsAll(listOf("beanA", "beanB")))
        assertEquals(CycleDependencyKind.FIELD, cycle.kind)
        assertTrue(cycle.resolvable)
    }

    @Test
    fun detectsThreeNodeCycle() {
        // A → B → C → A
        val beans = listOf(
            createBean("beanA", "com.example.A"),
            createBean("beanB", "com.example.B"),
            createBean("beanC", "com.example.C"),
        )
        
        val injectionPoints = listOf(
            createInjectionPoint("com.example.A", "beanB", "com.example.B", InjectionPointKind.FIELD),
            createInjectionPoint("com.example.B", "beanC", "com.example.C", InjectionPointKind.FIELD),
            createInjectionPoint("com.example.C", "beanA", "com.example.A", InjectionPointKind.FIELD),
        )
        
        val cycles = CycleDependencyDetector.detectCycles(beans, injectionPoints)
        
        assertEquals(1, cycles.size)
        val cycle = cycles.first()
        assertEquals(3, cycle.path.size)
        assertTrue(cycle.path.containsAll(listOf("beanA", "beanB", "beanC")))
        assertEquals(CycleDependencyKind.FIELD, cycle.kind)
        assertTrue(cycle.resolvable)
    }

    @Test
    fun detectsConstructorCycle() {
        // A → B → A (构造函数循环)
        val beans = listOf(
            createBean("beanA", "com.example.A"),
            createBean("beanB", "com.example.B"),
        )
        
        val injectionPoints = listOf(
            createInjectionPoint("com.example.A", "beanB", "com.example.B", InjectionPointKind.CONSTRUCTOR_PARAMETER),
            createInjectionPoint("com.example.B", "beanA", "com.example.A", InjectionPointKind.CONSTRUCTOR_PARAMETER),
        )
        
        val cycles = CycleDependencyDetector.detectCycles(beans, injectionPoints)
        
        assertEquals(1, cycles.size)
        val cycle = cycles.first()
        assertEquals(CycleDependencyKind.CONSTRUCTOR, cycle.kind)
        assertFalse(cycle.resolvable)
    }

    @Test
    fun detectsMixedCycle() {
        // A → B (构造函数) → C (字段) → A (字段)
        val beans = listOf(
            createBean("beanA", "com.example.A"),
            createBean("beanB", "com.example.B"),
            createBean("beanC", "com.example.C"),
        )
        
        val injectionPoints = listOf(
            createInjectionPoint("com.example.A", "beanB", "com.example.B", InjectionPointKind.CONSTRUCTOR_PARAMETER),
            createInjectionPoint("com.example.B", "beanC", "com.example.C", InjectionPointKind.FIELD),
            createInjectionPoint("com.example.C", "beanA", "com.example.A", InjectionPointKind.FIELD),
        )
        
        val cycles = CycleDependencyDetector.detectCycles(beans, injectionPoints)
        
        assertEquals(1, cycles.size)
        val cycle = cycles.first()
        assertEquals(CycleDependencyKind.MIXED, cycle.kind)
        assertFalse(cycle.resolvable)
    }

    @Test
    fun detectsFieldCycleWithPrototypeScope() {
        // A → B → A (字段循环，但 B 是 prototype)
        val beans = listOf(
            createBean("beanA", "com.example.A", scope = "singleton"),
            createBean("beanB", "com.example.B", scope = "prototype"),
        )
        
        val injectionPoints = listOf(
            createInjectionPoint("com.example.A", "beanB", "com.example.B", InjectionPointKind.FIELD),
            createInjectionPoint("com.example.B", "beanA", "com.example.A", InjectionPointKind.FIELD),
        )
        
        val cycles = CycleDependencyDetector.detectCycles(beans, injectionPoints)
        
        assertEquals(1, cycles.size)
        val cycle = cycles.first()
        assertEquals(CycleDependencyKind.FIELD, cycle.kind)
        assertFalse(cycle.resolvable)  // prototype 不可解析
    }

    @Test
    fun detectsMultipleCycles() {
        // A → B → A 和 C → D → C
        val beans = listOf(
            createBean("beanA", "com.example.A"),
            createBean("beanB", "com.example.B"),
            createBean("beanC", "com.example.C"),
            createBean("beanD", "com.example.D"),
        )
        
        val injectionPoints = listOf(
            createInjectionPoint("com.example.A", "beanB", "com.example.B", InjectionPointKind.FIELD),
            createInjectionPoint("com.example.B", "beanA", "com.example.A", InjectionPointKind.FIELD),
            createInjectionPoint("com.example.C", "beanD", "com.example.D", InjectionPointKind.FIELD),
            createInjectionPoint("com.example.D", "beanC", "com.example.C", InjectionPointKind.FIELD),
        )
        
        val cycles = CycleDependencyDetector.detectCycles(beans, injectionPoints)
        
        assertEquals(2, cycles.size)
        assertTrue(cycles.all { it.kind == CycleDependencyKind.FIELD })
        assertTrue(cycles.all { it.resolvable })
    }

    @Test
    fun detectsNoCycleInAcyclicGraph() {
        // A → B → C (无循环)
        val beans = listOf(
            createBean("beanA", "com.example.A"),
            createBean("beanB", "com.example.B"),
            createBean("beanC", "com.example.C"),
        )
        
        val injectionPoints = listOf(
            createInjectionPoint("com.example.A", "beanB", "com.example.B", InjectionPointKind.FIELD),
            createInjectionPoint("com.example.B", "beanC", "com.example.C", InjectionPointKind.FIELD),
        )
        
        val cycles = CycleDependencyDetector.detectCycles(beans, injectionPoints)
        
        assertEquals(0, cycles.size)
    }

    @Test
    fun handlesQualifierNamedDependencies() {
        // A → B (通过 @Named("beanB")) → A
        val beans = listOf(
            createBean("beanA", "com.example.A"),
            createBean("beanB", "com.example.B"),
        )
        
        val injectionPoints = listOf(
            createInjectionPoint("com.example.A", "dep", "com.example.B", InjectionPointKind.FIELD, qualifierName = "beanB"),
            createInjectionPoint("com.example.B", "beanA", "com.example.A", InjectionPointKind.FIELD),
        )
        
        val cycles = CycleDependencyDetector.detectCycles(beans, injectionPoints)
        
        assertEquals(1, cycles.size)
        val cycle = cycles.first()
        assertTrue(cycle.path.containsAll(listOf("beanA", "beanB")))
    }

    @Test
    fun detectsMethodParameterAsFieldCycle() {
        // A → B (方法参数) → A (字段)
        val beans = listOf(
            createBean("beanA", "com.example.A"),
            createBean("beanB", "com.example.B"),
        )
        
        val injectionPoints = listOf(
            createInjectionPoint("com.example.A", "beanB", "com.example.B", InjectionPointKind.METHOD_PARAMETER),
            createInjectionPoint("com.example.B", "beanA", "com.example.A", InjectionPointKind.FIELD),
        )
        
        val cycles = CycleDependencyDetector.detectCycles(beans, injectionPoints)
        
        assertEquals(1, cycles.size)
        val cycle = cycles.first()
        assertEquals(CycleDependencyKind.FIELD, cycle.kind)  // 方法参数视为字段
        assertTrue(cycle.resolvable)
    }

    // Helper functions

    private fun createBean(
        beanName: String,
        className: String,
        scope: String? = "singleton",
    ): BeanDefinition {
        return BeanDefinition(
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

    private fun createInjectionPoint(
        ownerClassName: String,
        declarationName: String,
        dependencyType: String,
        kind: InjectionPointKind,
        qualifierName: String? = null,
    ): InjectionPointDefinition {
        return InjectionPointDefinition(
            ownerClassName = ownerClassName,
            declarationName = declarationName,
            dependencyType = dependencyType,
            dependencyGenericType = null,
            ownerPackage = ownerClassName.substringBeforeLast('.'),
            sourceFile = null,
            sourcePath = null,
            sourceLine = null,
            sourceColumn = null,
            kind = kind,
            parameterIndex = null,
            qualifierName = qualifierName,
            required = true,
        )
    }
}
