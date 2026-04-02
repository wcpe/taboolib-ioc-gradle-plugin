package top.wcpe.taboolib.ioc.gradle

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import top.wcpe.taboolib.ioc.gradle.analysis.BeanKind
import top.wcpe.taboolib.ioc.gradle.analysis.BytecodeBeanIndexBuilder
import top.wcpe.taboolib.ioc.gradle.analysis.InjectionPointKind

class BytecodeBeanIndexBuilderUnitTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun buildsBeanAndInjectionIndexesFromCompiledClasses() {
        val classesDir = StaticDiagnosisFixtureSources.compileJavaSources(tempDir)

        val index = BytecodeBeanIndexBuilder.build(listOf(classesDir))

        assertTrue(index.beanIndex.any { it.beanName == "namedProcessor" && it.kind == BeanKind.FACTORY_METHOD })
        assertTrue(index.beanIndex.any { it.beanName == "greetingPrimaryOne" && it.primary })
        assertTrue(index.beanIndex.any { it.beanName == "staticDiagnosisConfiguration" && it.kind == BeanKind.CLASS })
        assertTrue(
            index.injectionPointIndex.any {
                it.kind == InjectionPointKind.CONSTRUCTOR_PARAMETER && it.dependencyType == "fixture.included.scan.MissingService"
            },
        )
        assertTrue(
            index.injectionPointIndex.any {
                it.kind == InjectionPointKind.FIELD && !it.required && it.dependencyType == "fixture.included.scan.RuntimeOnlyService"
            },
        )
        assertTrue(
            index.injectionPointIndex.any {
                it.kind == InjectionPointKind.METHOD_PARAMETER && it.declarationName == "setSingleMethodService"
            },
        )
        assertEquals(listOf("fixture.included.scan"), index.componentScans.single().basePackages)
    }
}