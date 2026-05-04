package top.wcpe.taboolib.ioc.gradle

import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertNotNull

/**
 * 为新增诊断规则提供测试数据源
 */
internal object DiagnosticRulesFixtureSources {

    /**
     * 循环依赖测试用例
     */
    fun cycleDependencySources(): Map<String, String> {
        return baseAnnotations() + mapOf(
            // 简单的两节点循环：A -> B -> A (构造函数)
            "fixture/cycle/ServiceA.java" to """
                package fixture.cycle;
                
                import fixture.scan.annotations.Bean;
                import fixture.scan.annotations.Inject;
                
                @Bean
                class ServiceA {
                    @Inject
                    public ServiceA(ServiceB serviceB) {}
                }
            """.trimIndent(),
            
            "fixture/cycle/ServiceB.java" to """
                package fixture.cycle;
                
                import fixture.scan.annotations.Bean;
                import fixture.scan.annotations.Inject;
                
                @Bean
                class ServiceB {
                    @Inject
                    public ServiceB(ServiceA serviceA) {}
                }
            """.trimIndent(),
            
            // 三节点循环：C -> D -> E -> C (构造函数)
            "fixture/cycle/ServiceC.java" to """
                package fixture.cycle;
                
                import fixture.scan.annotations.Bean;
                import fixture.scan.annotations.Inject;
                
                @Bean
                class ServiceC {
                    @Inject
                    public ServiceC(ServiceD serviceD) {}
                }
            """.trimIndent(),
            
            "fixture/cycle/ServiceD.java" to """
                package fixture.cycle;
                
                import fixture.scan.annotations.Bean;
                import fixture.scan.annotations.Inject;
                
                @Bean
                class ServiceD {
                    @Inject
                    public ServiceD(ServiceE serviceE) {}
                }
            """.trimIndent(),
            
            "fixture/cycle/ServiceE.java" to """
                package fixture.cycle;
                
                import fixture.scan.annotations.Bean;
                import fixture.scan.annotations.Inject;
                
                @Bean
                class ServiceE {
                    @Inject
                    public ServiceE(ServiceC serviceC) {}
                }
            """.trimIndent(),
            
            // 字段循环：F -> G -> F (字段注入)
            "fixture/cycle/ServiceF.java" to """
                package fixture.cycle;
                
                import fixture.scan.annotations.Bean;
                import fixture.scan.annotations.Inject;
                import fixture.scan.annotations.Scope;
                
                @Bean
                @Scope("singleton")
                class ServiceF {
                    @Inject
                    ServiceG serviceG;
                }
            """.trimIndent(),
            
            "fixture/cycle/ServiceG.java" to """
                package fixture.cycle;
                
                import fixture.scan.annotations.Bean;
                import fixture.scan.annotations.Inject;
                import fixture.scan.annotations.Scope;
                
                @Bean
                @Scope("singleton")
                class ServiceG {
                    @Inject
                    ServiceF serviceF;
                }
            """.trimIndent(),
            
            // 混合循环：H -> I -> H (H 构造函数，I 字段)
            "fixture/cycle/ServiceH.java" to """
                package fixture.cycle;
                
                import fixture.scan.annotations.Bean;
                import fixture.scan.annotations.Inject;
                
                @Bean
                class ServiceH {
                    @Inject
                    public ServiceH(ServiceI serviceI) {}
                }
            """.trimIndent(),
            
            "fixture/cycle/ServiceI.java" to """
                package fixture.cycle;
                
                import fixture.scan.annotations.Bean;
                import fixture.scan.annotations.Inject;
                
                @Bean
                class ServiceI {
                    @Inject
                    ServiceH serviceH;
                }
            """.trimIndent(),
            
            // Prototype 字段循环：J -> K -> J (不可解析)
            "fixture/cycle/ServiceJ.java" to """
                package fixture.cycle;
                
                import fixture.scan.annotations.Bean;
                import fixture.scan.annotations.Inject;
                import fixture.scan.annotations.Scope;
                
                @Bean
                @Scope("prototype")
                class ServiceJ {
                    @Inject
                    ServiceK serviceK;
                }
            """.trimIndent(),
            
            "fixture/cycle/ServiceK.java" to """
                package fixture.cycle;
                
                import fixture.scan.annotations.Bean;
                import fixture.scan.annotations.Inject;
                import fixture.scan.annotations.Scope;
                
                @Bean
                @Scope("prototype")
                class ServiceK {
                    @Inject
                    ServiceJ serviceJ;
                }
            """.trimIndent(),
        )
    }

    /**
     * RefreshScope 资源管理测试用例
     */
    fun refreshScopeSources(): Map<String, String> {
        return baseAnnotations() + mapOf(
            // RefreshScope Bean 有资源字段但无 @PreDestroy
            "fixture/refresh/DatabaseServiceWithoutDestroy.java" to """
                package fixture.refresh;
                
                import fixture.scan.annotations.Bean;
                import fixture.scan.annotations.RefreshScope;
                import java.sql.Connection;
                
                @Bean
                @RefreshScope
                class DatabaseServiceWithoutDestroy {
                    private Connection connection;
                }
            """.trimIndent(),
            
            // RefreshScope Bean 有资源字段且有 @PreDestroy
            "fixture/refresh/DatabaseServiceWithDestroy.java" to """
                package fixture.refresh;
                
                import fixture.scan.annotations.Bean;
                import fixture.scan.annotations.RefreshScope;
                import fixture.scan.annotations.PreDestroy;
                import java.sql.Connection;
                
                @Bean
                @RefreshScope
                class DatabaseServiceWithDestroy {
                    private Connection connection;
                    
                    @PreDestroy
                    public void cleanup() {}
                }
            """.trimIndent(),
            
            // RefreshScope Bean 无资源字段
            "fixture/refresh/SimpleRefreshService.java" to """
                package fixture.refresh;
                
                import fixture.scan.annotations.Bean;
                import fixture.scan.annotations.RefreshScope;
                
                @Bean
                @RefreshScope
                class SimpleRefreshService {
                    private String config;
                }
            """.trimIndent(),
            
            // RefreshScope Bean 有多种资源类型
            "fixture/refresh/MultiResourceService.java" to """
                package fixture.refresh;
                
                import fixture.scan.annotations.Bean;
                import fixture.scan.annotations.RefreshScope;
                import java.io.InputStream;
                import java.io.OutputStream;
                import java.net.Socket;
                
                @Bean
                @RefreshScope
                class MultiResourceService {
                    private InputStream inputStream;
                    private OutputStream outputStream;
                    private Socket socket;
                }
            """.trimIndent(),
        )
    }

    /**
     * ThreadScope 使用提示测试用例
     */
    fun threadScopeSources(): Map<String, String> {
        return baseAnnotations() + mapOf(
            // ThreadScope Bean
            "fixture/thread/RequestContext.java" to """
                package fixture.thread;
                
                import fixture.scan.annotations.Bean;
                import fixture.scan.annotations.ThreadScope;
                
                @Bean
                @ThreadScope
                class RequestContext {
                    private String requestId;
                }
            """.trimIndent(),
            
            // 非 ThreadScope Bean
            "fixture/thread/NormalService.java" to """
                package fixture.thread;
                
                import fixture.scan.annotations.Bean;
                
                @Bean
                class NormalService {
                    private String data;
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
        assertNotNull(compiler, "当前环境未提供 JavaCompiler，无法编译诊断规则测试样例。")

        val sourceFiles = Files.walk(sourceDir).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".java") }
                .map { it.toFile().path }
                .sorted()
                .toList()
        }
        val compilationArguments = mutableListOf("-d", outputDir.toString())
        compilationArguments.addAll(sourceFiles)
        val exitCode = compiler.run(null, null, null, *compilationArguments.toTypedArray())
        check(exitCode == 0) { "诊断规则测试样例编译失败，退出码=$exitCode" }
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
            "fixture/scan/annotations/Inject.java" to annotationSource(
                name = "Inject",
                body = "boolean required() default true;",
                targets = "CONSTRUCTOR, FIELD, METHOD, PARAMETER",
            ),
            "fixture/scan/annotations/Scope.java" to annotationSource(
                name = "Scope",
                body = "String value();",
                targets = "TYPE",
            ),
            "fixture/scan/annotations/RefreshScope.java" to annotationSource(
                name = "RefreshScope",
                body = "",
                targets = "TYPE",
            ),
            "fixture/scan/annotations/ThreadScope.java" to annotationSource(
                name = "ThreadScope",
                body = "",
                targets = "TYPE",
            ),
            "fixture/scan/annotations/PreDestroy.java" to annotationSource(
                name = "PreDestroy",
                body = "",
                targets = "METHOD",
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
