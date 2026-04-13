package top.wcpe.taboolib.ioc.gradle

import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

        val index = BytecodeBeanIndexBuilder.build(listOf(classesDir), listOf(tempDir.resolve("src")))

        assertTrue(index.beanIndex.any { it.beanName == "namedProcessor" && it.kind == BeanKind.FACTORY_METHOD })
        assertTrue(index.beanIndex.any { it.beanName == "greetingPrimaryOne" && it.primary })
        assertTrue(index.beanIndex.any { it.beanName == "staticDiagnosisConfiguration" && it.kind == BeanKind.CLASS })
        assertTrue(index.beanIndex.any { it.beanName == "componentService" && it.kind == BeanKind.CLASS })
        assertTrue(index.componentBeanTypes.contains("fixture.included.scan.ComponentService"))
        assertTrue(
            index.beanIndex.any {
                it.beanName == "stringMessageBox" && it.exposedGenericType == "fixture.included.scan.MessageBox<java.lang.String>"
            },
        )
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
                it.ownerClassName == "fixture.included.scan.ComponentConsumer" &&
                    it.kind == InjectionPointKind.CONSTRUCTOR_PARAMETER &&
                    it.dependencyType == "fixture.included.scan.ComponentService"
            },
        )
        assertTrue(
            index.injectionPointIndex.any {
                it.ownerClassName == "fixture.included.scan.KotlinObjectLikeConsumer" &&
                    it.kind == InjectionPointKind.FIELD &&
                    it.declarationName == "componentService" &&
                    it.dependencyType == "fixture.included.scan.ComponentService"
            },
        )
        assertTrue(
            index.missingInjectCandidateIndex.any {
                it.ownerClassName == "fixture.included.scan.MissingInjectComponentConsumer" &&
                    it.kind == InjectionPointKind.FIELD &&
                    it.declarationName == "componentService" &&
                    it.dependencyType == "fixture.included.scan.ComponentService"
            },
        )
        assertTrue(
            index.missingInjectCandidateIndex.any {
                it.ownerClassName == "fixture.included.scan.KotlinObjectMissingInjectConsumer" &&
                    it.kind == InjectionPointKind.FIELD &&
                    it.declarationName == "componentService" &&
                    it.dependencyType == "fixture.included.scan.ComponentService"
            },
        )
        assertTrue(index.missingInjectCandidateIndex.none { it.ownerClassName == "fixture.included.scan.InitializedComponentConsumer" })
        assertTrue(index.missingInjectCandidateIndex.none { it.ownerClassName == "fixture.included.scan.ManualAssignedComponentConsumer" })
        assertTrue(index.missingInjectCandidateIndex.none { it.ownerClassName == "fixture.included.scan.KotlinObjectInitializedComponentConsumer" })
        assertTrue(
            index.injectionPointIndex.any {
                it.kind == InjectionPointKind.METHOD_PARAMETER && it.declarationName == "setSingleMethodService"
            },
        )
        assertTrue(
            index.injectionPointIndex.any {
                it.declarationName == "constructor[0]" &&
                    it.dependencyGenericType == "fixture.included.scan.MessageBox<java.lang.String>"
            },
        )
        assertTrue(
            index.classIndex.any { entry ->
                entry.className == "fixture.included.scan.StringMessageBox" &&
                    entry.genericSuperTypes.contains("fixture.included.scan.MessageBox<java.lang.String>")
            },
        )
        assertEquals(listOf("fixture.included.scan"), index.componentScans.single().basePackages)
    }

    @Test
    fun skipsGenericMetadataEnrichmentWhenReferencedTypesAreMissingFromAnalysisClasspath() {
        val externalClassesDir = compileJavaSources(
            rootDir = tempDir.resolve("external"),
            sources = mapOf(
                "fixture/missing/ExternalGateway.java" to """
                    package fixture.missing;

                    public interface ExternalGateway {}
                """.trimIndent(),
            ),
        )
        val appRoot = tempDir.resolve("consumer")
        val appClassesDir = compileJavaSources(
            rootDir = appRoot,
            classpathEntries = listOf(externalClassesDir),
            sources = mapOf(
                "fixture/scan/annotations/Bean.java" to simpleAnnotationSource("Bean", "TYPE"),
                "fixture/scan/annotations/Inject.java" to simpleAnnotationSource("Inject", "FIELD"),
                "fixture/app/MissingClasspathConsumer.java" to """
                    package fixture.app;

                    import fixture.missing.ExternalGateway;
                    import fixture.scan.annotations.Bean;
                    import fixture.scan.annotations.Inject;

                    @Bean
                    class MissingClasspathConsumer {

                        @Inject
                        ExternalGateway gateway;
                    }
                """.trimIndent(),
            ),
        )

        val index = BytecodeBeanIndexBuilder.build(listOf(appClassesDir), listOf(appRoot.resolve("src")))
        val injection = index.injectionPointIndex.firstOrNull {
            it.ownerClassName == "fixture.app.MissingClasspathConsumer" &&
                it.kind == InjectionPointKind.FIELD &&
                it.declarationName == "gateway"
        }

        assertNotNull(injection)
        assertEquals("fixture.missing.ExternalGateway", injection.dependencyType)
        assertEquals(null, injection.dependencyGenericType)
    }

    private fun compileJavaSources(
        rootDir: Path,
        sources: Map<String, String>,
        classpathEntries: List<Path> = emptyList(),
    ): Path {
        val sourceDir = rootDir.resolve("src")
        val outputDir = rootDir.resolve("classes")
        sourceDir.createDirectories()
        outputDir.createDirectories()
        sources.forEach { (relativePath, content) ->
            val file = sourceDir.resolve(relativePath)
            file.parent.createDirectories()
            file.writeText(content + System.lineSeparator())
        }

        val compiler = ToolProvider.getSystemJavaCompiler()
        assertNotNull(compiler, "当前环境未提供 JavaCompiler，无法编译缺类回归测试样例。")
        val sourceFiles = Files.walk(sourceDir).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".java") }
                .map { it.toFile().path }
                .sorted()
                .toList()
        }
        val compilationArguments = mutableListOf("-d", outputDir.toString())
        if (classpathEntries.isNotEmpty()) {
            compilationArguments += listOf("-classpath", classpathEntries.joinToString(separator = System.getProperty("path.separator")) { it.toString() })
        }
        compilationArguments.addAll(sourceFiles)
        val exitCode = compiler.run(null, null, null, *compilationArguments.toTypedArray())
        check(exitCode == 0) { "缺类回归测试样例编译失败，退出码=$exitCode" }
        return outputDir
    }

    private fun simpleAnnotationSource(name: String, targets: String): String {
        val targetList = targets.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(", ") { "ElementType.$it" }
        return """
            package fixture.scan.annotations;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @Retention(RetentionPolicy.CLASS)
            @Target({$targetList})
            public @interface $name {
            }
        """.trimIndent()
    }
}