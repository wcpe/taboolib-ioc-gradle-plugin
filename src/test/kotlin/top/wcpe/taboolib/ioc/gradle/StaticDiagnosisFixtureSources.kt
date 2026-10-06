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
            "fixture/scan/annotations/Component.java" to annotationSource(
                name = "Component",
                body = """
                String value() default "";
                String name() default "";
                String beanName() default "";
                """.trimIndent(),
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
            "fixture/scan/annotations/Metadata.java" to annotationSource(
                name = "Metadata",
                body = "",
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
            "fixture/scan/annotations/ConditionalOnExpression.java" to annotationSource(
                name = "ConditionalOnExpression",
                body = "String value();",
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
                interface UnknownConditionalService {}
                interface MessageBox<T> {}
            """.trimIndent(),
            "fixture/included/scan/ScannedGateway.java" to """
                package fixture.included.scan;

                public interface ScannedGateway {}
            """.trimIndent(),
            "fixture/included/scan/Beans.java" to """
                package fixture.included.scan;

                import fixture.scan.annotations.Bean;
                import fixture.scan.annotations.Component;
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

                @Component
                class ComponentService {}

                class StringMessageBox implements MessageBox<String> {}

                class IntegerMessageBox implements MessageBox<Integer> {}
            """.trimIndent(),
            "fixture/included/scan/StaticDiagnosisConfiguration.java" to """
                package fixture.included.scan;

                import fixture.scan.annotations.Bean;
                import fixture.scan.annotations.ConditionalOnBean;
                import fixture.scan.annotations.ConditionalOnClass;
                import fixture.scan.annotations.ConditionalOnExpression;
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
                    @ConditionalOnExpression("${'$'}{feature.dynamic:true}")
                    UnknownConditionalService unknownConditionalService() {
                        return new UnknownConditionalServiceImpl();
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

                class UnknownConditionalServiceImpl implements UnknownConditionalService {}

                class MethodInjectedBean {
                    MethodInjectedBean(AuditService auditService) {}
                }
            """.trimIndent(),
            "fixture/included/scan/Consumers.java" to """
                package fixture.included.scan;

                import fixture.scan.annotations.Bean;
                import fixture.scan.annotations.Component;
                import fixture.scan.annotations.Inject;
                import fixture.scan.annotations.Metadata;
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
                class UnknownConditionalConsumer {
                    UnknownConditionalConsumer(UnknownConditionalService unknownConditionalService) {}
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

                @Component
                class ComponentConsumer {
                    ComponentConsumer(ComponentService componentService) {}
                }

                class MissingInjectComponentConsumer {
                    ComponentService componentService;

                    void message() {
                        componentService.toString();
                    }
                }

                class InitializedComponentConsumer {
                    ComponentService componentService = new ComponentService();
                }

                class ManualAssignedComponentConsumer {
                    ComponentService componentService;

                    ManualAssignedComponentConsumer() {
                        this.componentService = new ComponentService();
                    }
                }

                @Metadata
                class KotlinObjectInitializedComponentConsumer {
                    static final KotlinObjectInitializedComponentConsumer INSTANCE = new KotlinObjectInitializedComponentConsumer();

                    static ComponentService componentService = new ComponentService();

                    private KotlinObjectInitializedComponentConsumer() {}
                }

                @Metadata
                class KotlinObjectLikeConsumer {
                    static final KotlinObjectLikeConsumer INSTANCE = new KotlinObjectLikeConsumer();

                    @Inject
                    static ComponentService componentService;

                    private KotlinObjectLikeConsumer() {}
                }

                @Metadata
                class KotlinObjectMissingInjectConsumer {
                    static final KotlinObjectMissingInjectConsumer INSTANCE = new KotlinObjectMissingInjectConsumer();

                    static ComponentService componentService;

                    private KotlinObjectMissingInjectConsumer() {}

                    void message() {
                        componentService.toString();
                    }
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
        writeSources(javaSources(), rootDir)
    }

    /**
     * 阶段①专用 fixture：一个「无接口、具备 public 实例方法」的 Bean + 命中它的 `@Aspect`，
     * 以及「切点只命中 private / static 方法」的对照 Bean 与切面。
     *
     * 用于锁定 AOP 静默失效规则随 `weaving` 开关的条件化行为（§2.4 第 2、3 条）。
     */
    fun aopWeavingSources(): Map<String, String> {
        return linkedMapOf(
            "fixture/aopweave/annotations/Bean.java" to annotationSource(
                name = "Bean",
                body = """
                String value() default "";
                String name() default "";
                String beanName() default "";
                """.trimIndent(),
                targets = "TYPE, METHOD",
                packageName = "fixture.aopweave.annotations",
            ),
            "fixture/aopweave/annotations/Aspect.java" to annotationSource(
                name = "Aspect",
                body = "",
                targets = "TYPE",
                packageName = "fixture.aopweave.annotations",
            ),
            "fixture/aopweave/annotations/Before.java" to annotationSource(
                name = "Before",
                body = "String value();",
                targets = "METHOD",
                packageName = "fixture.aopweave.annotations",
            ),
            "fixture/aopweave/ConcreteService.java" to """
                package fixture.aopweave;

                import fixture.aopweave.annotations.Bean;

                @Bean
                public class ConcreteService {
                    public String run() {
                        return "run";
                    }
                }
            """.trimIndent(),
            "fixture/aopweave/PrivateMethodService.java" to """
                package fixture.aopweave;

                import fixture.aopweave.annotations.Bean;

                @Bean
                public class PrivateMethodService {
                    public String visible() {
                        return "visible";
                    }

                    private String hidden() {
                        return "hidden";
                    }
                }
            """.trimIndent(),
            "fixture/aopweave/StaticApi.java" to """
                package fixture.aopweave;

                public interface StaticApi {
                    String instanceCall();
                }
            """.trimIndent(),
            "fixture/aopweave/StaticMethodService.java" to """
                package fixture.aopweave;

                import fixture.aopweave.annotations.Bean;

                @Bean
                public class StaticMethodService implements StaticApi {
                    @Override
                    public String instanceCall() {
                        return "instance";
                    }

                    public static String staticCall() {
                        return "static";
                    }
                }
            """.trimIndent(),
            "fixture/aopweave/ConcreteServiceAspect.java" to """
                package fixture.aopweave;

                import fixture.aopweave.annotations.Aspect;
                import fixture.aopweave.annotations.Before;

                @Aspect
                public class ConcreteServiceAspect {
                    @Before("execution(fixture.aopweave.ConcreteService.run)")
                    public void beforeRun() {
                    }
                }
            """.trimIndent(),
            "fixture/aopweave/PrivateMethodAspect.java" to """
                package fixture.aopweave;

                import fixture.aopweave.annotations.Aspect;
                import fixture.aopweave.annotations.Before;

                @Aspect
                public class PrivateMethodAspect {
                    @Before("execution(fixture.aopweave.PrivateMethodService.hidden)")
                    public void beforeHidden() {
                    }
                }
            """.trimIndent(),
            "fixture/aopweave/StaticMethodAspect.java" to """
                package fixture.aopweave;

                import fixture.aopweave.annotations.Aspect;
                import fixture.aopweave.annotations.Before;

                @Aspect
                public class StaticMethodAspect {
                    @Before("execution(fixture.aopweave.StaticMethodService.staticCall)")
                    public void beforeStatic() {
                    }
                }
            """.trimIndent(),
        )
    }

    /**
     * 阶段①**反向反例** fixture：无接口类 + **非 public**（protected / 包内可见 / native）方法，
     * 被 `@Aspect` 按方法名精确命中。
     *
     * 这些方法满足 `!isPrivate` 但**不满足 `ACC_PUBLIC`**（native 另被 `ACC_NATIVE` 排除），
     * 因此编译期织入**不会**碰它们 → `aop-target-not-proxied` 在 `weaving=true` 时**必须保留**。
     * 用于锁死「把织入资格放宽成 `!isPrivate` 近似」造成的静默漏报。
     */
    fun aopWeavingIneligibleSources(): Map<String, String> {
        return linkedMapOf(
            "fixture/awineligible/annotations/Bean.java" to annotationSource(
                name = "Bean",
                body = """
                String value() default "";
                String name() default "";
                String beanName() default "";
                """.trimIndent(),
                targets = "TYPE, METHOD",
                packageName = "fixture.awineligible.annotations",
            ),
            "fixture/awineligible/annotations/Aspect.java" to annotationSource(
                name = "Aspect",
                body = "",
                targets = "TYPE",
                packageName = "fixture.awineligible.annotations",
            ),
            "fixture/awineligible/annotations/Before.java" to annotationSource(
                name = "Before",
                body = "String value();",
                targets = "METHOD",
                packageName = "fixture.awineligible.annotations",
            ),
            "fixture/awineligible/ProtectedMethodService.java" to """
                package fixture.awineligible;

                import fixture.awineligible.annotations.Bean;

                @Bean
                public class ProtectedMethodService {
                    public String visible() {
                        return "visible";
                    }

                    protected String guarded() {
                        return "guarded";
                    }
                }
            """.trimIndent(),
            "fixture/awineligible/PackagePrivateMethodService.java" to """
                package fixture.awineligible;

                import fixture.awineligible.annotations.Bean;

                @Bean
                public class PackagePrivateMethodService {
                    public String visible() {
                        return "visible";
                    }

                    String internal() {
                        return "internal";
                    }
                }
            """.trimIndent(),
            "fixture/awineligible/NativeMethodService.java" to """
                package fixture.awineligible;

                import fixture.awineligible.annotations.Bean;

                @Bean
                public class NativeMethodService {
                    public String visible() {
                        return "visible";
                    }

                    public native String nativeCall();
                }
            """.trimIndent(),
            "fixture/awineligible/ProtectedMethodAspect.java" to """
                package fixture.awineligible;

                import fixture.awineligible.annotations.Aspect;
                import fixture.awineligible.annotations.Before;

                @Aspect
                public class ProtectedMethodAspect {
                    @Before("execution(fixture.awineligible.ProtectedMethodService.guarded)")
                    public void beforeGuarded() {
                    }
                }
            """.trimIndent(),
            "fixture/awineligible/PackagePrivateMethodAspect.java" to """
                package fixture.awineligible;

                import fixture.awineligible.annotations.Aspect;
                import fixture.awineligible.annotations.Before;

                @Aspect
                public class PackagePrivateMethodAspect {
                    @Before("execution(fixture.awineligible.PackagePrivateMethodService.internal)")
                    public void beforeInternal() {
                    }
                }
            """.trimIndent(),
            "fixture/awineligible/NativeMethodAspect.java" to """
                package fixture.awineligible;

                import fixture.awineligible.annotations.Aspect;
                import fixture.awineligible.annotations.Before;

                @Aspect
                public class NativeMethodAspect {
                    @Before("execution(fixture.awineligible.NativeMethodService.nativeCall)")
                    public void beforeNative() {
                    }
                }
            """.trimIndent(),
        )
    }

    /**
     * 阶段①**事实驱动**回归 fixture（F1/F2）：
     *
     * - **F1（继承未覆写）**：`Child extends Parent`，`Parent.public inherited()` 未被覆写，
     *   切点命中 `Child.inherited`。引擎按被织类**自身声明**的方法名匹配 → 计划里 `Child` 为
     *   `SKIPPED`，诊断必须**仍报** `aop-target-not-proxied`（且原因命中「声明在父类」）。
     * - **F2（`@NoAspect`）**：类级 `@NoAspect` 与**方法级** `@NoAspect` 两个变体。
     *   引擎织入阶段显式跳过 → 计划里为 `SKIPPED(NO_ASPECT_CLASS)`，诊断必须**仍报**。
     *
     * 两个反例都是旧「预测」路径会静默漏报、而「读事实」路径必须报出的场景（§2.1 / §2.3）。
     */
    fun aopWeavingFactSources(): Map<String, String> {
        return linkedMapOf(
            "fixture/aopfact/annotations/Bean.java" to annotationSource(
                name = "Bean",
                body = """
                String value() default "";
                String name() default "";
                String beanName() default "";
                """.trimIndent(),
                targets = "TYPE, METHOD",
                packageName = "fixture.aopfact.annotations",
            ),
            "fixture/aopfact/annotations/Aspect.java" to annotationSource(
                name = "Aspect",
                body = "",
                targets = "TYPE",
                packageName = "fixture.aopfact.annotations",
            ),
            "fixture/aopfact/annotations/Before.java" to annotationSource(
                name = "Before",
                body = "String value();",
                targets = "METHOD",
                packageName = "fixture.aopfact.annotations",
            ),
            // 与织入器描述符 `Ltop/wcpe/taboolib/ioc/annotation/NoAspect;` 逐字同包同名
            "top/wcpe/taboolib/ioc/annotation/NoAspect.java" to """
                package top.wcpe.taboolib.ioc.annotation;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.METHOD})
                public @interface NoAspect {
                }
            """.trimIndent(),
            // ── F1：继承未覆写 ──
            "fixture/aopfact/Parent.java" to """
                package fixture.aopfact;

                public class Parent {
                    public String inherited() {
                        return "parent";
                    }
                }
            """.trimIndent(),
            "fixture/aopfact/Child.java" to """
                package fixture.aopfact;

                import fixture.aopfact.annotations.Bean;

                @Bean
                public class Child extends Parent {
                }
            """.trimIndent(),
            "fixture/aopfact/InheritedAspect.java" to """
                package fixture.aopfact;

                import fixture.aopfact.annotations.Aspect;
                import fixture.aopfact.annotations.Before;

                @Aspect
                public class InheritedAspect {
                    @Before("execution(fixture.aopfact.Child.inherited)")
                    public void beforeInherited() {
                    }
                }
            """.trimIndent(),
            // ── F2：类级 @NoAspect ──
            "fixture/aopfact/NoAspectSvc.java" to """
                package fixture.aopfact;

                import fixture.aopfact.annotations.Bean;
                import top.wcpe.taboolib.ioc.annotation.NoAspect;

                @Bean
                @NoAspect
                public class NoAspectSvc {
                    public String run() {
                        return "run";
                    }
                }
            """.trimIndent(),
            "fixture/aopfact/NoAspectSvcAspect.java" to """
                package fixture.aopfact;

                import fixture.aopfact.annotations.Aspect;
                import fixture.aopfact.annotations.Before;

                @Aspect
                public class NoAspectSvcAspect {
                    @Before("execution(fixture.aopfact.NoAspectSvc.run)")
                    public void beforeRun() {
                    }
                }
            """.trimIndent(),
            // ── F2：方法级 @NoAspect ──
            "fixture/aopfact/MethodLevelNoAspectSvc.java" to """
                package fixture.aopfact;

                import fixture.aopfact.annotations.Bean;
                import top.wcpe.taboolib.ioc.annotation.NoAspect;

                @Bean
                public class MethodLevelNoAspectSvc {
                    @NoAspect
                    public String run() {
                        return "run";
                    }
                }
            """.trimIndent(),
            "fixture/aopfact/MethodLevelNoAspectSvcAspect.java" to """
                package fixture.aopfact;

                import fixture.aopfact.annotations.Aspect;
                import fixture.aopfact.annotations.Before;

                @Aspect
                public class MethodLevelNoAspectSvcAspect {
                    @Before("execution(fixture.aopfact.MethodLevelNoAspectSvc.run)")
                    public void beforeRun() {
                    }
                }
            """.trimIndent(),
        )
    }

    fun writeSources(sources: Map<String, String>, rootDir: Path) {
        sources.forEach { (relativePath, content) ->
            val file = rootDir.resolve(relativePath)
            file.parent.createDirectories()
            file.writeText(content + System.lineSeparator())
        }
    }

    fun compileJavaSources(rootDir: Path): Path = compileSources(javaSources(), rootDir)

    fun compileAopWeavingSources(rootDir: Path): Path = compileSources(aopWeavingSources(), rootDir)

    /**
     * AOP 织入夹具 + 一个**全通配**切点（`execution(*.*)`）的变体。
     *
     * 用于钉住「计划无法确定织入了什么时，`*` 通配通知也必须保守上报」：`*` 的匹配在诊断侧
     * 是无条件成立的，若允许它在 `forwarded` 为空时短路，哨兵态就会被整类抑制。
     */
    fun compileAopWeavingWildcardSources(rootDir: Path): Path =
        compileSources(
            aopWeavingSources() + mapOf(
                "fixture/aopweave/WildcardAspect.java" to """
                    package fixture.aopweave;

                    import fixture.aopweave.annotations.Aspect;
                    import fixture.aopweave.annotations.Before;

                    @Aspect
                    public class WildcardAspect {
                        @Before("execution(*.*)")
                        public void any() {
                        }
                    }
                """.trimIndent(),
            ),
            rootDir,
        )


    fun compileAopWeavingIneligibleSources(rootDir: Path): Path =
        compileSources(aopWeavingIneligibleSources(), rootDir)

    fun compileAopWeavingFactSources(rootDir: Path): Path =
        compileSources(aopWeavingFactSources(), rootDir)

    /**
     * AOP 织入夹具变体：把继承用例的切点从**子类**改为**父类**
     * （`execution(fixture.aopfact.Child.inherited)` → `execution(fixture.aopfact.Parent.inherited)`）。
     *
     * 用于钉住反方向：`Child extends Parent` 不覆写 `inherited()`，自身没有可织入的声明方法，
     * 计划里要么没有它、要么 SKIPPED —— 原先它连 `matched` 都进不去，后续判定一步都走不到。
     */
    fun compileAopWeavingParentPointcutSources(rootDir: Path): Path =
        compileSources(
            aopWeavingFactSources() + mapOf(
                "fixture/aopfact/InheritedAspect.java" to """
                    package fixture.aopfact;

                    import fixture.aopfact.annotations.Aspect;
                    import fixture.aopfact.annotations.Before;

                    @Aspect
                    public class InheritedAspect {
                        @Before("execution(fixture.aopfact.Parent.inherited)")
                        public void beforeInherited() {
                        }
                    }
                """.trimIndent(),
            ),
            rootDir,
        )

    private fun compileSources(sources: Map<String, String>, rootDir: Path): Path {
        val sourceDir = rootDir.resolve("src")
        val outputDir = rootDir.resolve("classes")
        sourceDir.createDirectories()
        outputDir.createDirectories()
        writeSources(sources, sourceDir)

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

    private fun annotationSource(
        name: String,
        body: String,
        targets: String,
        packageName: String = "fixture.scan.annotations",
    ): String {
        val targetList = targets.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(", ") { "ElementType.$it" }
        return """
            package $packageName;

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