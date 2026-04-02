package top.wcpe.taboolib.ioc.gradle

import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertNotNull

internal object StaticDiagnosisFixtureSources {

    fun javaSources(): Map<String, String> {
        return linkedMapOf(
            "fixture/scan/annotations/Bean.java" to annotationSource(
                name = "Bean",
                body = """
                String value() default "";
                String name() default "";
                String beanName() default "";
                """.trimIndent(),
                targets = "TYPE, METHOD",
            ),
            "fixture/scan/annotations/Configuration.java" to annotationSource(
                name = "Configuration",
                body = "",
                targets = "TYPE",
            ),
            "fixture/scan/annotations/Primary.java" to annotationSource(
                name = "Primary",
                body = "",
                targets = "TYPE, METHOD",
            ),
            "fixture/scan/annotations/Order.java" to annotationSource(
                name = "Order",
                body = "int value();",
                targets = "TYPE, METHOD",
            ),
            "fixture/scan/annotations/Named.java" to annotationSource(
                name = "Named",
                body = "String value();",
                targets = "FIELD, PARAMETER",
            ),
            "fixture/scan/annotations/Resource.java" to annotationSource(
                name = "Resource",
                body = """
                String name() default "";
                boolean required() default true;
                """.trimIndent(),
                targets = "FIELD, PARAMETER",
            ),
            "fixture/scan/annotations/Inject.java" to annotationSource(
                name = "Inject",
                body = "boolean required() default true;",
                targets = "CONSTRUCTOR, FIELD, METHOD, PARAMETER",
            ),
            "fixture/scan/annotations/ComponentScan.java" to annotationSource(
                name = "ComponentScan",
                body = """
                String[] value() default {};
                String[] basePackages() default {};
                """.trimIndent(),
                targets = "TYPE",
            ),
            "fixture/scan/annotations/ConditionalOnProperty.java" to annotationSource(
                name = "ConditionalOnProperty",
                body = """
                String[] name() default {};
                String[] value() default {};
                String havingValue() default "";
                boolean matchIfMissing() default false;
                """.trimIndent(),
                targets = "TYPE, METHOD",
            ),
            "fixture/scan/annotations/ConditionalOnClass.java" to annotationSource(
                name = "ConditionalOnClass",
                body = """
                String[] name() default {};
                String[] value() default {};
                String[] type() default {};
                """.trimIndent(),
                targets = "TYPE, METHOD",
            ),
            "fixture/scan/annotations/ConditionalOnMissingClass.java" to annotationSource(
                name = "ConditionalOnMissingClass",
                body = """
                String[] name() default {};
                String[] value() default {};
                String[] type() default {};
                """.trimIndent(),
                targets = "TYPE, METHOD",
            ),
            "fixture/scan/annotations/ConditionalOnBean.java" to annotationSource(
                name = "ConditionalOnBean",
                body = """
                String[] name() default {};
                String[] beanName() default {};
                String[] value() default {};
                String[] type() default {};
                """.trimIndent(),
                targets = "TYPE, METHOD",
            ),
            "fixture/scan/annotations/ConditionalOnMissingBean.java" to annotationSource(
                name = "ConditionalOnMissingBean",
                body = """
                String[] name() default {};
                String[] beanName() default {};
                String[] value() default {};
                String[] type() default {};
                """.trimIndent(),
                targets = "TYPE, METHOD",
            ),
            "fixture/included/scan/Contracts.java" to """
                package fixture.included.scan;

                interface MissingService {}
                interface PaymentProcessor {}
                interface AuditService {}
                interface GreetingService {}
                interface ConditionalService {}
                interface RuntimeOnlyService {}
                interface SingleMethodService {}
                interface EnabledConditionalService {}
                interface MissingClassConditionalService {}
                interface BeanConditionalService {}
                interface MessageBox<T> {}
            """.trimIndent(),
            "fixture/included/scan/ScannedGateway.java" to """
                package fixture.included.scan;

                public interface ScannedGateway {}
            """.trimIndent(),
            "fixture/included/scan/Beans.java" to """
                package fixture.included.scan;

                import fixture.scan.annotations.Bean;
                import fixture.scan.annotations.Primary;

                @Bean(name = "wrongType")
                class WrongTypeAuditService implements AuditService {}

                @Bean
                @Primary
                class GreetingPrimaryOne implements GreetingService {}

                @Bean
                @Primary
                class GreetingPrimaryTwo implements GreetingService {}

                @Bean(name = "primaryProcessor")
                class NamedPaymentProcessor implements PaymentProcessor {}

                @Bean
                class AuditServiceOne implements AuditService {}

                @Bean
                class AuditServiceTwo implements AuditService {}

                @Bean
                class SingleMethodServiceImpl implements SingleMethodService {}

                class StringMessageBox implements MessageBox<String> {}

                class IntegerMessageBox implements MessageBox<Integer> {}
            """.trimIndent(),
            "fixture/included/scan/StaticDiagnosisConfiguration.java" to """
                package fixture.included.scan;

                import fixture.scan.annotations.Bean;
                import fixture.scan.annotations.ConditionalOnBean;
                import fixture.scan.annotations.ConditionalOnClass;
                import fixture.scan.annotations.ConditionalOnMissingClass;
                import fixture.scan.annotations.ComponentScan;
                import fixture.scan.annotations.ConditionalOnProperty;
                import fixture.scan.annotations.Configuration;

                @Configuration
                @ComponentScan(basePackages = {"fixture.included.scan"})
                class StaticDiagnosisConfiguration {

                    @Bean(name = "namedProcessor")
                    PaymentProcessor namedProcessor() {
                        return new NamedPaymentProcessor();
                    }

                    @Bean
                    @ConditionalOnProperty(name = "feature.conditional")
                    ConditionalService conditionalService() {
                        return new ConditionalServiceImpl();
                    }

                    @Bean
                    @ConditionalOnProperty(name = "feature.enabled", havingValue = "on")
                    EnabledConditionalService enabledConditionalService() {
                        return new EnabledConditionalServiceImpl();
                    }

                    @Bean
                    @ConditionalOnMissingClass(name = "fixture.missing.Dependency")
                    MissingClassConditionalService missingClassConditionalService() {
                        return new MissingClassConditionalServiceImpl();
                    }

                    @Bean
                    @ConditionalOnBean(type = "fixture.included.scan.PaymentProcessor")
                    BeanConditionalService beanConditionalService() {
                        return new BeanConditionalServiceImpl();
                    }

                    @Bean
                    @ConditionalOnClass(name = "java.lang.String")
                    MessageBox<String> stringMessageBox() {
                        return new StringMessageBox();
                    }

                    @Bean
                    MessageBox<Integer> integerMessageBox() {
                        return new IntegerMessageBox();
                    }

                    @Bean
                    MethodInjectedBean methodInjectedBean(AuditService auditService) {
                        return new MethodInjectedBean(auditService);
                    }
                }

                class ConditionalServiceImpl implements ConditionalService {}

                class EnabledConditionalServiceImpl implements EnabledConditionalService {}

                class MissingClassConditionalServiceImpl implements MissingClassConditionalService {}

                class BeanConditionalServiceImpl implements BeanConditionalService {}

                class MethodInjectedBean {
                    MethodInjectedBean(AuditService auditService) {}
                }
            """.trimIndent(),
            "fixture/included/scan/Consumers.java" to """
                package fixture.included.scan;

                import fixture.scan.annotations.Bean;
                import fixture.scan.annotations.Inject;
                import fixture.scan.annotations.Named;
                import fixture.scan.annotations.Resource;

                @Bean
                class MissingBeanConsumer {
                    MissingBeanConsumer(MissingService missingService) {}
                }

                @Bean
                class MissingNamedConsumer {
                    MissingNamedConsumer(@Named("ghostProcessor") PaymentProcessor paymentProcessor) {}
                }

                @Bean
                class NamedTypeMismatchConsumer {
                    @Resource(name = "wrongType")
                    PaymentProcessor paymentProcessor;
                }

                @Bean
                class MultiplePrimaryConsumer {
                    MultiplePrimaryConsumer(GreetingService greetingService) {}
                }

                @Bean
                class MultipleCandidatesConsumer {
                    MultipleCandidatesConsumer(AuditService auditService) {}
                }

                @Bean
                class ConditionalOnlyConsumer {
                    ConditionalOnlyConsumer(ConditionalService conditionalService) {}
                }

                @Bean
                class EnabledConditionalConsumer {
                    EnabledConditionalConsumer(EnabledConditionalService enabledConditionalService) {}
                }

                @Bean
                class MissingClassConditionalConsumer {
                    MissingClassConditionalConsumer(MissingClassConditionalService missingClassConditionalService) {}
                }

                @Bean
                class BeanConditionalConsumer {
                    BeanConditionalConsumer(BeanConditionalService beanConditionalService) {}
                }

                @Bean
                class GenericStringConsumer {
                    GenericStringConsumer(MessageBox<String> messageBox) {}
                }

                @Bean
                class RuntimeManualConsumer {
                    @Inject(required = false)
                    RuntimeOnlyService runtimeOnlyService;
                }

                @Bean
                class ComponentScanExcludedConsumer {
                    ComponentScanExcludedConsumer(ScannedGateway scannedGateway) {}
                }

                @Bean
                class InjectMethodConsumer {
                    @Inject
                    void setSingleMethodService(SingleMethodService singleMethodService) {}
                }
            """.trimIndent(),
            "fixture/excluded/scan/OutsideScanGateway.java" to """
                package fixture.excluded.scan;

                import fixture.included.scan.ScannedGateway;
                import fixture.scan.annotations.Bean;

                @Bean
                public class OutsideScanGateway implements ScannedGateway {}
            """.trimIndent(),
        )
    }

    fun writeJavaSources(rootDir: Path) {
        javaSources().forEach { (relativePath, content) ->
            val file = rootDir.resolve(relativePath)
            file.parent.createDirectories()
            file.writeText(content + System.lineSeparator())
        }
    }

    fun compileJavaSources(rootDir: Path): Path {
        val sourceDir = rootDir.resolve("src")
        val outputDir = rootDir.resolve("classes")
        sourceDir.createDirectories()
        outputDir.createDirectories()
        writeJavaSources(sourceDir)

        val compiler = ToolProvider.getSystemJavaCompiler()
        assertNotNull(compiler, "当前环境未提供 JavaCompiler，无法编译静态诊断测试样例。")

        val sourceFiles = Files.walk(sourceDir).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".java") }
                .map { it.toFile().path }
                .sorted()
                .toList()
        }
        val compilationArguments = mutableListOf("-d", outputDir.toString())
        compilationArguments.addAll(sourceFiles)
        val exitCode = compiler.run(null, null, null, *compilationArguments.toTypedArray())
        check(exitCode == 0) { "静态诊断测试样例编译失败，退出码=$exitCode" }
        return outputDir
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