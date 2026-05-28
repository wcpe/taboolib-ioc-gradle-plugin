package top.wcpe.taboolib.ioc.gradle

import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import top.wcpe.taboolib.ioc.gradle.analysis.BeanKind
import top.wcpe.taboolib.ioc.gradle.analysis.BytecodeBeanIndexBuilder

/**
 * 回归测试：[BytecodeBeanIndexBuilder] 静态分析必须把 `@Service` / `@Repository` / `@Controller` / `@Aspect`
 * 视为与 `@Component` 等价的 Bean 注解，与运行时 `ClassScanner.isComponent` 行为对齐。
 *
 * 此前的 Bug：静态分析仅识别 `@Component`，导致使用 `@Service` / `@Repository` 的工程 `analyzeTaboolibIocBeans`
 * 报 `beanCount=0` 且依赖注入诊断全部 `[missing-bean]`。
 */
class StereotypeAnnotationsRegressionTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `service repository controller aspect should be recognized as component beans`() {
        val classesDir = compileFixture(tempDir)

        val index = BytecodeBeanIndexBuilder.build(listOf(classesDir), listOf(tempDir.resolve("src")))

        val expected = listOf(
            "fixture.stereo.MyComponent" to "myComponent",
            "fixture.stereo.MyService" to "myService",
            "fixture.stereo.MyRepository" to "myRepository",
            "fixture.stereo.MyController" to "myController",
            "fixture.stereo.MyAspect" to "myAspect",
        )

        for ((type, beanName) in expected) {
            assertTrue(
                index.componentBeanTypes.contains(type),
                "componentBeanTypes 应包含 $type，实际：${index.componentBeanTypes}",
            )
            assertTrue(
                index.beanIndex.any { it.beanName == beanName && it.kind == BeanKind.CLASS },
                "beanIndex 应包含 name=$beanName 的 CLASS 类型 Bean。",
            )
        }
    }

    @Test
    fun `service annotation explicit value should override default bean name`() {
        val classesDir = compileFixture(tempDir)

        val index = BytecodeBeanIndexBuilder.build(listOf(classesDir), listOf(tempDir.resolve("src")))

        assertTrue(
            index.beanIndex.any { it.beanName == "customNamedService" && it.kind == BeanKind.CLASS },
            "@Service(\"customNamedService\") 显式名称应被识别。",
        )
    }

    private fun compileFixture(rootDir: Path): Path {
        val sourceDir = rootDir.resolve("src")
        val outputDir = rootDir.resolve("classes")
        sourceDir.createDirectories()
        outputDir.createDirectories()

        val sources = mapOf(
            "fixture/scan/annotations/Component.java" to annotation("Component", "String value() default \"\";"),
            "fixture/scan/annotations/Service.java" to annotation("Service", "String value() default \"\";"),
            "fixture/scan/annotations/Repository.java" to annotation("Repository", "String value() default \"\";"),
            "fixture/scan/annotations/Controller.java" to annotation("Controller", "String value() default \"\";"),
            "fixture/scan/annotations/Aspect.java" to annotation("Aspect", "String value() default \"\";"),
            "fixture/stereo/Beans.java" to """
                package fixture.stereo;

                import fixture.scan.annotations.Component;
                import fixture.scan.annotations.Service;
                import fixture.scan.annotations.Repository;
                import fixture.scan.annotations.Controller;
                import fixture.scan.annotations.Aspect;

                @Component class MyComponent {}
                @Service class MyService {}
                @Repository class MyRepository {}
                @Controller class MyController {}
                @Aspect class MyAspect {}

                @Service("customNamedService") class NamedService {}
            """.trimIndent(),
        )
        sources.forEach { (relativePath, content) ->
            val file = sourceDir.resolve(relativePath)
            file.parent.createDirectories()
            file.writeText(content + System.lineSeparator())
        }

        val compiler = ToolProvider.getSystemJavaCompiler()
        assertNotNull(compiler, "当前环境未提供 JavaCompiler，无法编译回归测试样例。")
        val sourceFiles = Files.walk(sourceDir).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".java") }
                .map { it.toFile().path }
                .sorted()
                .toList()
        }
        val args = mutableListOf("-d", outputDir.toString()).apply { addAll(sourceFiles) }
        val exitCode = compiler.run(null, null, null, *args.toTypedArray())
        check(exitCode == 0) { "回归测试样例编译失败，退出码=$exitCode" }
        return outputDir
    }

    private fun annotation(name: String, body: String): String = """
        package fixture.scan.annotations;

        import java.lang.annotation.ElementType;
        import java.lang.annotation.Retention;
        import java.lang.annotation.RetentionPolicy;
        import java.lang.annotation.Target;

        @Retention(RetentionPolicy.CLASS)
        @Target({ElementType.TYPE})
        public @interface $name {
            $body
        }
    """.trimIndent()
}
