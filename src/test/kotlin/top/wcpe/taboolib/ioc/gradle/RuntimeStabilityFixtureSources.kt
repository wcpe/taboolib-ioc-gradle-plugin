package top.wcpe.taboolib.ioc.gradle

import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertNotNull

internal object RuntimeStabilityFixtureSources {

    fun multipleConstructorsWithNoArgFallback(): Map<String, String> {
        return baseAnnotations() + mapOf(
            "fixture/runtime/MultiConstructorBean.java" to """
                package fixture.runtime;

                import fixture.scan.annotations.Bean;

                @Bean
                class MultiConstructorBean {
                    private final String dependency;

                    public MultiConstructorBean() {
                        this.dependency = "default";
                    }

                    public MultiConstructorBean(String dependency) {
                        this.dependency = dependency;
                    }
                }
            """.trimIndent(),
        )
    }

    fun singleConstructorWithoutInject(): Map<String, String> {
        return baseAnnotations() + mapOf(
            "fixture/runtime/SingleConstructorBean.java" to """
                package fixture.runtime;

                import fixture.scan.annotations.Bean;

                @Bean
                class SingleConstructorBean {
                    private final String dependency;

                    public SingleConstructorBean(String dependency) {
                        this.dependency = dependency;
                    }
                }
            """.trimIndent(),
        )
    }

    fun kotlinMultiConstructorsWithNoArgFallback(): Map<String, String> {
        return baseAnnotations() + mapOf(
            "fixture/runtime/KotlinMultiConstructorBean.java" to """
                package fixture.runtime;

                import fixture.scan.annotations.Bean;
                import fixture.scan.annotations.Metadata;

                @Bean
                @Metadata
                class KotlinMultiConstructorBean {
                    private final String dependency;

                    public KotlinMultiConstructorBean() {
                        this.dependency = "default";
                    }

                    public KotlinMultiConstructorBean(String dependency) {
                        this.dependency = dependency;
                    }
                }
            """.trimIndent(),
        )
    }

    fun componentAnnotatedBean(): Map<String, String> {
        return baseAnnotations() + mapOf(
            "fixture/runtime/ComponentBean.java" to """
                package fixture.runtime;

                import fixture.scan.annotations.Component;

                @Component
                class ComponentBean {
                    public ComponentBean() {}
                }
            """.trimIndent(),
        )
    }

    fun compileJavaSources(rootDir: Path, sources: Map<String, String>): Path {
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
        assertNotNull(compiler, "当前环境未提供 JavaCompiler，无法编译运行时稳定性测试样例。")

        val sourceFiles = Files.walk(sourceDir).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".java") }
                .map { it.toFile().path }
                .sorted()
                .toList()
        }
        val compilationArguments = mutableListOf("-d", outputDir.toString())
        compilationArguments.addAll(sourceFiles)
        val exitCode = compiler.run(null, null, null, *compilationArguments.toTypedArray())
        check(exitCode == 0) { "运行时稳定性测试样例编译失败，退出码=$exitCode" }
        return outputDir
    }

    private fun baseAnnotations(): Map<String, String> {
        return mapOf(
            "fixture/scan/annotations/Bean.java" to annotationSource(
                name = "Bean",
                body = """
                String value() default "";
                String name() default "";
                String beanName() default "";
                """.trimIndent(),
                targets = "TYPE, METHOD",
            ),
            "fixture/scan/annotations/Component.java" to annotationSource(
                name = "Component",
                body = """
                String value() default "";
                String name() default "";
                String beanName() default "";
                """.trimIndent(),
                targets = "TYPE",
            ),
            "fixture/scan/annotations/Inject.java" to annotationSource(
                name = "Inject",
                body = "boolean required() default true;",
                targets = "CONSTRUCTOR, FIELD, METHOD, PARAMETER",
            ),
            "fixture/scan/annotations/Metadata.java" to annotationSource(
                name = "Metadata",
                body = "",
                targets = "TYPE",
            ),
        )
    }

    private fun annotationSource(name: String, body: String, targets: String): String {
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
                $body
            }
        """.trimIndent()
    }
}
