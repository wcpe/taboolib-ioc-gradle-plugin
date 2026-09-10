package top.wcpe.taboolib.ioc.gradle

import java.nio.file.Files
import java.nio.file.Path
import javax.tools.DiagnosticCollector
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * E6 修复批次的 Java 夹具编译器：为采集层 @Lazy 语义测试提供真实字节码。
 *
 * @Lazy 注解在这里以**本地最小定义**提供（fixture.lazy.Lazy），
 * 因为采集层按简单名 `Lazy` + `value` 属性识别，与运行时 FQCN 无关。
 */
internal object StaticEngineFixtureCompiler {

    fun compileLazyFixtures(root: Path): Path {
        val sources = linkedMapOf<String, String>()

        sources["fixture/lazy/Lazy.java"] = """
            package fixture.lazy;

            import java.lang.annotation.*;

            @Retention(RetentionPolicy.RUNTIME)
            @Target({ElementType.FIELD, ElementType.PARAMETER})
            public @interface Lazy { boolean value() default true; }
        """.trimIndent()

        sources["fixture/lazy/Component.java"] = """
            package fixture.lazy;

            import java.lang.annotation.*;

            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.TYPE)
            public @interface Component {}
        """.trimIndent()

        sources["fixture/lazy/Inject.java"] = """
            package fixture.lazy;

            import java.lang.annotation.*;

            @Retention(RetentionPolicy.RUNTIME)
            @Target({ElementType.CONSTRUCTOR, ElementType.FIELD, ElementType.METHOD})
            public @interface Inject {}
        """.trimIndent()

        sources["fixture/lazy/ServerApi.java"] = """
            package fixture.lazy;

            public interface ServerApi {}
        """.trimIndent()

        sources["fixture/lazy/LazyTrueHolder.java"] = """
            package fixture.lazy;

            @Component
            public class LazyTrueHolder {
                @Inject
                @Lazy
                private ServerApi dep;
            }
        """.trimIndent()

        sources["fixture/lazy/LazyFalseHolder.java"] = """
            package fixture.lazy;

            @Component
            public class LazyFalseHolder {
                @Inject
                @Lazy(false)
                private ServerApi dep;
            }
        """.trimIndent()

        sources["fixture/lazy/LazyCtorHolder.java"] = """
            package fixture.lazy;

            @Component
            public class LazyCtorHolder {
                @Inject
                public LazyCtorHolder(@Lazy ServerApi dep) {}
            }
        """.trimIndent()

        val srcDir = root.resolve("src")
        sources.forEach { (relative, content) ->
            val target = srcDir.resolve(relative)
            target.parent.createDirectories()
            target.writeText(content)
        }

        val classesDir = root.resolve("classes")
        classesDir.createDirectories()
        val compiler = ToolProvider.getSystemJavaCompiler()
        assertNotNull(compiler, "需要 JDK 提供的 Java 编译器")
        val fileManager = compiler.getStandardFileManager(null, null, Charsets.UTF_8)
        val diagnostics = DiagnosticCollector<javax.tools.JavaFileObject>()
        val javaFiles = Files.walk(srcDir).use { stream ->
            stream.filter { it.toString().endsWith(".java") }.map(Path::toFile).toList()
        }
        val task = compiler.getTask(
            null,
            fileManager,
            diagnostics,
            listOf("-d", classesDir.toString(), "-parameters"),
            null,
            fileManager.getJavaFileObjectsFromFiles(javaFiles),
        )
        val success = task.call() ?: false
        fileManager.close()
        assertTrue(
            success,
            "lazy fixture 编译失败: " + diagnostics.diagnostics.joinToString("; ") { it.toString() },
        )
        return classesDir
    }
}
