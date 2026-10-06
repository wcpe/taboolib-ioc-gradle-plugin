package top.wcpe.taboolib.ioc.gradle

import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.io.TempDir
import top.wcpe.taboolib.ioc.gradle.analysis.BeanKind
import top.wcpe.taboolib.ioc.gradle.analysis.BytecodeAnalysisIndex
import top.wcpe.taboolib.ioc.gradle.analysis.BytecodeBeanIndexBuilder
import top.wcpe.taboolib.ioc.gradle.analysis.DiagnosticSeverity
import top.wcpe.taboolib.ioc.gradle.analysis.InjectionPointKind
import top.wcpe.taboolib.ioc.gradle.analysis.StaticDiagnosisEngine
import top.wcpe.taboolib.ioc.gradle.companionfixture.CompanionInjectionHolder

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
        // 泛型层级不再对全部扫描类预解析：此前对依赖 jar 里上万个与 IoC 无关的类逐个反射加载，
        // 占该任务耗时的一半以上。现在只预解析会被 TypeHierarchy 查询的 Bean 暴露类型
        // （见 BytecodeBeanIndexBuilder.enrichGenericMetadata），其余类型由 TypeHierarchy
        // 在查询时用扫描类加载器按需补齐。故此处不再断言非 Bean 类型（StringMessageBox）的
        // genericSuperTypes —— 该值的消费方只有 isGenericMatch，而它只查 Bean 暴露类型。
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

    @Test
    fun extractsScopeAnnotationsFromBeans() {
        val classesDir = compileJavaSources(
            rootDir = tempDir.resolve("scope"),
            sources = mapOf(
                "fixture/scan/annotations/Bean.java" to simpleAnnotationSource("Bean", "TYPE"),
                "fixture/scan/annotations/RefreshScope.java" to simpleAnnotationSource("RefreshScope", "TYPE"),
                "fixture/scan/annotations/ThreadScope.java" to simpleAnnotationSource("ThreadScope", "TYPE"),
                "fixture/scan/annotations/Prototype.java" to simpleAnnotationSource("Prototype", "TYPE"),
                "fixture/scope/ScopeBeans.java" to """
                    package fixture.scope;

                    import fixture.scan.annotations.Bean;
                    import fixture.scan.annotations.RefreshScope;
                    import fixture.scan.annotations.ThreadScope;
                    import fixture.scan.annotations.Prototype;

                    @Bean
                    class SingletonBean {}

                    @Bean
                    @RefreshScope
                    class RefreshBean {}

                    @Bean
                    @ThreadScope
                    class ThreadBean {}

                    @Bean
                    @Prototype
                    class PrototypeBean {}
                """.trimIndent(),
            ),
        )

        val index = BytecodeBeanIndexBuilder.build(listOf(classesDir))

        assertEquals("singleton", index.beanIndex.first { it.beanName == "singletonBean" }.scope)
        assertEquals("refresh", index.beanIndex.first { it.beanName == "refreshBean" }.scope)
        assertEquals("thread", index.beanIndex.first { it.beanName == "threadBean" }.scope)
        assertEquals("prototype", index.beanIndex.first { it.beanName == "prototypeBean" }.scope)
    }

    @Test
    fun extractsLifecycleMethodsFromBeans() {
        val classesDir = compileJavaSources(
            rootDir = tempDir.resolve("lifecycle"),
            sources = mapOf(
                "fixture/scan/annotations/Bean.java" to simpleAnnotationSource("Bean", "TYPE"),
                "fixture/scan/annotations/PostConstruct.java" to simpleAnnotationSource("PostConstruct", "METHOD"),
                "fixture/scan/annotations/PreDestroy.java" to simpleAnnotationSource("PreDestroy", "METHOD"),
                "fixture/lifecycle/LifecycleBean.java" to """
                    package fixture.lifecycle;

                    import fixture.scan.annotations.Bean;
                    import fixture.scan.annotations.PostConstruct;
                    import fixture.scan.annotations.PreDestroy;

                    @Bean
                    class LifecycleBean {
                        @PostConstruct
                        void init() {}

                        @PreDestroy
                        void destroy() {}
                    }
                """.trimIndent(),
            ),
        )

        val index = BytecodeBeanIndexBuilder.build(listOf(classesDir))
        val bean = index.beanIndex.first { it.beanName == "lifecycleBean" }

        assertTrue(bean.lifecycleMethods.postConstructMethods.contains("init"))
        assertTrue(bean.lifecycleMethods.preDestroyMethods.contains("destroy"))
    }

    @Test
    fun extractsFieldInfoFromClasses() {
        val classesDir = compileJavaSources(
            rootDir = tempDir.resolve("fields"),
            sources = mapOf(
                "fixture/fields/FieldClass.java" to """
                    package fixture.fields;

                    import java.util.List;

                    class FieldClass {
                        int count;
                        String name;
                        List<String> items;
                    }
                """.trimIndent(),
            ),
        )

        val index = BytecodeBeanIndexBuilder.build(listOf(classesDir))
        val entry = index.classIndex.first { it.className == "fixture.fields.FieldClass" }
        val fieldNames = entry.fields.map { it.name }

        assertTrue(fieldNames.contains("count"))
        assertTrue(fieldNames.contains("name"))
        assertTrue(fieldNames.contains("items"))
        assertEquals("int", entry.fields.first { it.name == "count" }.type)
        assertEquals("java.lang.String", entry.fields.first { it.name == "name" }.type)
        assertEquals("java.util.List", entry.fields.first { it.name == "items" }.type)
    }

    @Test
    fun extractsDependenciesFromInjectionPoints() {
        val classesDir = compileJavaSources(
            rootDir = tempDir.resolve("deps"),
            sources = mapOf(
                "fixture/scan/annotations/Bean.java" to simpleAnnotationSource("Bean", "TYPE"),
                "fixture/scan/annotations/Inject.java" to simpleAnnotationSource("Inject", "CONSTRUCTOR, FIELD, METHOD"),
                "fixture/scan/annotations/Named.java" to """
                    package fixture.scan.annotations;

                    import java.lang.annotation.ElementType;
                    import java.lang.annotation.Retention;
                    import java.lang.annotation.RetentionPolicy;
                    import java.lang.annotation.Target;

                    @Retention(RetentionPolicy.CLASS)
                    @Target({ElementType.FIELD, ElementType.PARAMETER})
                    public @interface Named {
                        String value();
                    }
                """.trimIndent(),
                "fixture/deps/DepBeans.java" to """
                    package fixture.deps;

                    import fixture.scan.annotations.Bean;
                    import fixture.scan.annotations.Inject;
                    import fixture.scan.annotations.Named;

                    interface ServiceA {}
                    interface ServiceB {}
                    interface ServiceC {}

                    @Bean
                    class CtorInjectedBean {
                        CtorInjectedBean(ServiceA serviceA) {}
                    }

                    @Bean
                    class FieldInjectedBean {
                        @Inject
                        ServiceB serviceB;
                    }

                    @Bean
                    class NamedInjectedBean {
                        NamedInjectedBean(@Named("myServiceC") ServiceC serviceC) {}
                    }
                """.trimIndent(),
            ),
        )

        val index = BytecodeBeanIndexBuilder.build(listOf(classesDir))

        val ctorBean = index.beanIndex.first { it.beanName == "ctorInjectedBean" }
        assertTrue(ctorBean.dependencies.any { it.targetType == "fixture.deps.ServiceA" && it.kind == InjectionPointKind.CONSTRUCTOR_PARAMETER })

        val fieldBean = index.beanIndex.first { it.beanName == "fieldInjectedBean" }
        assertTrue(fieldBean.dependencies.any { it.targetType == "fixture.deps.ServiceB" && it.kind == InjectionPointKind.FIELD })

        val namedBean = index.beanIndex.first { it.beanName == "namedInjectedBean" }
        assertTrue(namedBean.dependencies.any { it.targetType == "fixture.deps.ServiceC" && it.targetBeanName == "myServiceC" })
    }

    // ==================== C-P2-03：采集层字段直接断言 ====================

    /**
     * C-P2-03：**直接**断言 `isStatic` 判定 —— 普通（非 Kotlin object、非 companion）类上的
     * `static` 注入字段必须被过滤掉，仅保留实例字段。
     *
     * 运行时 `FieldInjector.injectFields` 对实例调用 `field.set(instance, value)`，
     * 普通类上没有 object 单例、static 字段无归属实例；采集层必须与运行时一致地跳过它，
     * 否则会产生「静态字段被当成可注入点」的假阳性。
     */
    @Test
    fun filtersStaticFieldsOnPlainClassesButKeepsInstanceFields() {
        val classesDir = compileJavaSources(
            rootDir = tempDir.resolve("staticfilter"),
            sources = mapOf(
                "fixture/scan/annotations/Bean.java" to simpleAnnotationSource("Bean", "TYPE"),
                "fixture/scan/annotations/Inject.java" to simpleAnnotationSource("Inject", "FIELD"),
                "fixture/staticfilter/StaticFieldBean.java" to """
                    package fixture.staticfilter;

                    import fixture.scan.annotations.Bean;
                    import fixture.scan.annotations.Inject;

                    @Bean
                    class StaticFieldBean {
                        @Inject
                        static Object staticDep;

                        @Inject
                        Object instanceDep;
                    }
                """.trimIndent(),
            ),
        )

        val index = BytecodeBeanIndexBuilder.build(listOf(classesDir))
        val fieldPoints = index.injectionPointIndex.filter {
            it.ownerClassName == "fixture.staticfilter.StaticFieldBean" &&
                it.kind == InjectionPointKind.FIELD
        }
        val names = fieldPoints.map { it.declarationName }

        assertTrue(names.contains("instanceDep"), "普通类的实例 @Inject 字段必须被采集")
        assertTrue(
            !names.contains("staticDep"),
            "普通类（非 Kotlin object / 非 companion）上的 static @Inject 字段必须被过滤（isStatic 直接判定）",
        )
    }

    /**
     * C-P2-03：**直接**断言嵌套类不被跳过 —— 顶层类内的静态嵌套类若带 @Bean，应正常采集。
     *
     * （匿名类 / 纯数字后缀合成类才应跳过；有名字的嵌套类必须保留。）
     */
    @Test
    fun collectsNamedNestedClassesAndSkipsAnonymousOnes() {
        val classesDir = compileJavaSources(
            rootDir = tempDir.resolve("nested"),
            sources = mapOf(
                "fixture/scan/annotations/Bean.java" to simpleAnnotationSource("Bean", "TYPE"),
                "fixture/nested/Outer.java" to """
                    package fixture.nested;

                    import fixture.scan.annotations.Bean;

                    class Outer {
                        @Bean
                        static class NestedBean {
                            Object dep;
                        }

                        @Bean
                        static class AnonymousFactory {
                            static Runnable make() {
                                return new Runnable() {
                                    @Override
                                    public void run() {}
                                };
                            }
                        }
                    }
                """.trimIndent(),
            ),
        )

        val index = BytecodeBeanIndexBuilder.build(listOf(classesDir))
        val classNames = index.classIndex.map { it.className }

        assertTrue(
            classNames.contains("fixture.nested.Outer${'$'}NestedBean"),
            "有名字的静态嵌套类必须被采集（H2 修复：不得因含 '\$' 被整体跳过）",
        )
        assertTrue(
            index.beanIndex.any { it.ownerClassName == "fixture.nested.Outer${'$'}NestedBean" },
            "嵌套 @Bean 类必须进入 beanIndex",
        )
        assertTrue(
            classNames.none { it == "fixture.nested.Outer${'$'}AnonymousFactory${'$'}1" },
            "匿名类（纯数字末段）必须被跳过，不得进入 classIndex",
        )
    }

    @Test
    @DisplayName("嵌套字段的初始化与手工赋值按真实源码排除")
    fun filtersNestedInitializedAndManuallyAssignedFields() {
        val index = nestedInjectionIndex()
        val owners = setOf("Initialized${'$'}State", "Manual${'$'}State", "Deep${'$'}Middle${'$'}State")
        assertFalse(index.missingInjectCandidateIndex.any { it.ownerClassName.removePrefix("fixture.nested.") in owners },
            "已由业务初始化或手工装配的嵌套字段不应成为遗漏注入候选")
        val diagnostics = StaticDiagnosisEngine.analyze(":fixture", index).diagnostics
        assertFalse(diagnostics.any { it.ownerClassName.removePrefix("fixture.nested.") in owners },
            "上述嵌套字段不应产生错误诊断")
    }

    @Test
    @DisplayName("真正遗漏注入的嵌套与外层字段继续报错")
    fun keepsMissingInjectionInNestedAndOuterScopes() {
        val index = nestedInjectionIndex()
        val diagnostics = StaticDiagnosisEngine.analyze(":fixture", index).diagnostics
            .filter { it.rule == "missing-inject-annotation" }
        assertEquals(setOf("fixture.nested.Missing${'$'}State", "fixture.nested.SameName"),
            diagnostics.map { it.ownerClassName }.toSet(), "不能用别的所有者初值抑制真实遗漏")
        assertTrue(diagnostics.all { it.severity == DiagnosticSeverity.ERROR }, "真实遗漏必须继续阻断构建")
        assertTrue(diagnostics.all { it.sourcePath != null && it.sourceLine != null }, "诊断应包含实际源码位置")
    }

    @Test
    @DisplayName("显式注入的嵌套字段仍作为真正注入点分析")
    fun retainsExplicitInjectionInNestedClass() {
        val index = nestedInjectionIndex()
        val owner = "fixture.nested.Explicit${'$'}State"
        val point = index.injectionPointIndex.single { it.ownerClassName == owner && it.declarationName == "service" }
        assertEquals(InjectionPointKind.FIELD, point.kind, "显式字段注入类型必须保留")
        assertNotNull(point.sourcePath, "嵌套注入点应能定位源码")
        assertFalse(index.missingInjectCandidateIndex.any { it.ownerClassName == owner }, "显式注入不能被当作遗漏")
        assertFalse(StaticDiagnosisEngine.analyze(":fixture", index).diagnostics.any { it.ownerClassName == owner },
            "存在兼容组件时显式注入应通过")
    }

    @Test
    @DisplayName("源文件缺失时不能无依据抑制嵌套遗漏注入")
    fun keepsNestedMissingInjectionWithoutSourceInput() {
        val index = nestedInjectionIndex(includeSources = false)
        val diagnostics = StaticDiagnosisEngine.analyze(":fixture", index).diagnostics
        assertTrue(diagnostics.any { it.ownerClassName == "fixture.nested.Missing${'$'}State" &&
            it.rule == "missing-inject-annotation" && it.severity == DiagnosticSeverity.ERROR },
            "缺少源码事实时仍须保留真实遗漏诊断")
    }

    @Test
    @DisplayName("编译后的匿名类初值不能掩盖外层遗漏注入")
    fun keepsOuterMissingInjectionBesideAnonymousClass() {
        val source = """
            class AnonymousOuter {
                Runnable callback = new Runnable() {
                    Service service = null;
                    @Override public void run() {}
                };
                Service service;
            }
        """.trimIndent()
        val index = nestedInjectionIndex(additionalSource = source)
        assertTrue(index.missingInjectCandidateIndex.any { it.ownerClassName == "fixture.nested.AnonymousOuter" &&
            it.declarationName == "service" }, "匿名类初值不能排除外层真实遗漏候选")
        assertTrue(StaticDiagnosisEngine.analyze(":fixture", index).diagnostics.any {
            it.ownerClassName == "fixture.nested.AnonymousOuter" && it.rule == "missing-inject-annotation" &&
                it.severity == DiagnosticSeverity.ERROR }, "外层真实遗漏必须继续阻断构建")
    }

    @Test
    @DisplayName("真实 Kotlin 伴生静态注入字段保持精确源码行号")
    fun locatesActualKotlinCompanionBackingFieldDeclaration() {
        val classes = Path.of(CompanionInjectionHolder::class.java.protectionDomain.codeSource.location.toURI())
        val sources = Path.of("src/test/kotlin").toAbsolutePath()
        val sourceFile = sources.resolve("top/wcpe/taboolib/ioc/gradle/companionfixture/CompanionInjectionFixtures.kt")
        val expectedLine = Files.readAllLines(sourceFile).indexOfFirst { it.contains("var dep: String? = null") } + 1
        val index = BytecodeBeanIndexBuilder.build(listOf(classes), listOf(sources))
        val point = index.injectionPointIndex.single {
            it.ownerClassName == CompanionInjectionHolder::class.java.name && it.declarationName == "dep"
        }
        assertEquals(expectedLine, point.sourceLine, "静态伴生字段必须定位到实际声明，不能退回宿主类行")
        assertTrue(point.sourcePath?.endsWith("CompanionInjectionFixtures.kt") == true, "应定位到真实夹具源文件")
    }

    private fun nestedInjectionIndex(includeSources: Boolean = true, additionalSource: String = ""): BytecodeAnalysisIndex {
        val root = tempDir.resolve("nested-injection")
        val classes = compileJavaSources(root, mapOf(
            "fixture/scan/annotations/Component.java" to simpleAnnotationSource("Component", "TYPE"),
            "fixture/scan/annotations/Inject.java" to simpleAnnotationSource("Inject", "FIELD"),
            "fixture/nested/NestedConsumers.java" to nestedConsumersSource() + "\n" + additionalSource,
        ))
        return BytecodeBeanIndexBuilder.build(listOf(classes), if (includeSources) listOf(root.resolve("src")) else emptyList())
    }

    private fun nestedConsumersSource(): String = """
        package fixture.nested;
        import fixture.scan.annotations.Component;
        import fixture.scan.annotations.Inject;
        @Component class Service {}
    """.trimIndent() + "\n" + nestedInitializedConsumers() + "\n" + nestedMissingConsumers()

    private fun nestedInitializedConsumers(): String = """
        class Initialized {
            static class State { Service service = null; }
        }
        class Manual {
            static class State {
                Service service;
                void register() { service = new Service(); }
            }
        }
        class Deep {
            static class Middle {
                static class State { Service service = null; }
            }
        }
    """.trimIndent()

    private fun nestedMissingConsumers(): String = """
        class Missing {
            static class State { Service service; }
        }
        class Explicit {
            static class State { @Inject Service service; }
        }
        class SameName {
            Service service;
            static class State { Service service = null; }
        }
    """.trimIndent()

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
