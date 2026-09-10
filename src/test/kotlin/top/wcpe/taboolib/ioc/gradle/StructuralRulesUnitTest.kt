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
import top.wcpe.taboolib.ioc.gradle.analysis.BytecodeAnalysisIndex
import top.wcpe.taboolib.ioc.gradle.analysis.BytecodeBeanIndexBuilder
import top.wcpe.taboolib.ioc.gradle.analysis.ClassIndexEntry
import top.wcpe.taboolib.ioc.gradle.analysis.CollectedMethodInfo
import top.wcpe.taboolib.ioc.gradle.analysis.ConstructorMetadata
import top.wcpe.taboolib.ioc.gradle.analysis.AspectAdviceDefinition
import top.wcpe.taboolib.ioc.gradle.analysis.AspectDefinition
import top.wcpe.taboolib.ioc.gradle.analysis.BeanDefinition
import top.wcpe.taboolib.ioc.gradle.analysis.DiagnosticSeverity
import top.wcpe.taboolib.ioc.gradle.analysis.LifecycleMethodDetail
import top.wcpe.taboolib.ioc.gradle.analysis.StaticDiagnosisEngine
import top.wcpe.taboolib.ioc.gradle.analysis.ValueFieldDefinition

/**
 * 构建前检查 P0 十条规则的静态引擎测试（对应静态诊断引擎的 10 条 P0 结构规则）。
 * 每条规则命中都代表「运行时容器初始化失败 / Bean 创建失败 / 静默失效」。
 */
class StructuralRulesUnitTest {

    @TempDir
    lateinit var tempDir: Path

    // ==================== 采集层 ====================

    @Test
    fun collectsStructureFactsFromCompiledClasses() {
        val classesDir = compileStructuralFixtures(tempDir.resolve("collect"))
        val index = BytecodeBeanIndexBuilder.build(listOf(classesDir), listOf(tempDir.resolve("collect/src")))

        // 接口 / 抽象类标记被采集
        val interfaceEntry = index.classIndex.single { it.className == "fixture.structural.InterfaceComponent" }
        assertTrue(interfaceEntry.isInterface)
        val abstractEntry = index.classIndex.single { it.className == "fixture.structural.AbstractComponent" }
        assertTrue(abstractEntry.isAbstract)

        // 枚举的 superClassName 是 java.lang.Enum
        val enumEntry = index.classIndex.single { it.className == "fixture.structural.ColorEnum" }
        assertEquals("java.lang.Enum", enumEntry.superClassName)

        // 多构造器元数据
        val multiCtor = index.beanIndex.single { it.ownerClassName == "fixture.structural.MultiCtorBean" }
        val metadata = assertNotNull(multiCtor.constructorMetadata)
        assertEquals(2, metadata.totalConstructorCount)
        assertEquals(false, metadata.hasExplicitInjectConstructor)
        assertEquals(false, metadata.hasNoArgConstructor)

        val injectCtor = index.beanIndex.single { it.ownerClassName == "fixture.structural.InjectCtorBean" }
        assertTrue(assertNotNull(injectCtor.constructorMetadata).hasExplicitInjectConstructor)

        // 生命周期方法签名明细
        val lifecycleBean = index.beanIndex.single { it.ownerClassName == "fixture.structural.LifecycleBean" }
        assertEquals(
            listOf(
                LifecycleMethodDetail("PostConstruct", "ok", 0, false),
                LifecycleMethodDetail("PostConstruct", "bad", 1, false),
            ),
            lifecycleBean.lifecycleMethodDetails,
        )

        // @Bean 宿主是否 @Configuration + void 返回
        val outsideBean = index.beanIndex.single {
            it.kind == BeanKind.FACTORY_METHOD && it.declarationName == "makeGreeting"
        }
        assertEquals(false, outsideBean.factoryHostIsConfiguration)

        val voidBean = index.beanIndex.single {
            it.kind == BeanKind.FACTORY_METHOD && it.declarationName == "voidInit"
        }
        assertEquals(true, voidBean.factoryHostIsConfiguration)
        assertEquals(true, voidBean.factoryMethodReturnsVoid)

        val insideBean = index.beanIndex.single {
            it.kind == BeanKind.FACTORY_METHOD && it.declarationName == "greeting"
        }
        assertEquals(true, insideBean.factoryHostIsConfiguration)
        assertEquals(false, insideBean.factoryMethodReturnsVoid)

        // @Value 字段采集
        val valueFields = index.valueFieldIndex.filter { it.ownerClassName == "fixture.structural.ValueHolder" }
        assertEquals(4, valueFields.size)
        assertTrue(valueFields.any { it.fieldName == "url" && it.expression == "jdbc:\${db.host}/x" })
        assertTrue(valueFields.any { it.fieldName == "port" && it.targetType == "int" })
        assertTrue(valueFields.any { it.fieldName == "id" && it.targetType == "java.util.UUID" })

        // @Aspect 切面采集
        val aspect = index.aspectIndex.single { it.aspectClassName == "fixture.structural.LogAspect" }
        assertEquals(mapOf("svcPointcut" to "execution(fixture.structural.Svc.*.doIt)"), aspect.pointcutMethods)
        assertEquals(4, aspect.advices.size)
        assertTrue(aspect.advices.any { it.adviceAnnotation == "Before" && it.expression == "svcPointcut" })
        assertTrue(aspect.advices.any { it.adviceAnnotation == "Around" && it.expression == "greet" })

        // H1：载体注解合并回同名字段（字段本身无注解）
        assertTrue(
            index.injectionPointIndex.any {
                it.ownerClassName == "fixture.structural.CarrierHost" &&
                    it.declarationName == "name" &&
                    it.dependencyType == "fixture.structural.ServerApi"
            },
            "Kotlin 载体方法上的 @Inject 应合并回 name 字段",
        )

        // H3：companion 载体跨类合并到外部类静态字段
        assertTrue(
            index.injectionPointIndex.any {
                it.ownerClassName == "fixture.structural.CompanionHost" &&
                    it.declarationName == "svc" &&
                    it.dependencyType == "fixture.structural.ServerApi"
            },
            "Companion 载体上的 @Inject 应合并到外部类静态字段",
        )

        // H2：嵌套类不再被 $ 过滤跳过
        assertTrue(
            index.beanIndex.any { it.ownerClassName == "fixture.structural.OuterWithNested\$NestedComponent" },
            "嵌套类应被纳入索引",
        )

        // 方法清单采集（AOP 规则地基）
        val workerEntry = index.classIndex.single { it.className == "fixture.structural.ConcreteWorker" }
        assertTrue(workerEntry.methods.any { it.name == "doWork" && !it.isPrivate })
        assertTrue(workerEntry.methods.any { it.name == "secret" && it.isPrivate })

        // 通知方法参数类型采集
        val aopAspect = index.aspectIndex.single { it.aspectClassName == "fixture.structural.AopAspect" }
        val goodAround = aopAspect.advices.single { it.methodName == "goodAround" }
        assertEquals(listOf("top.wcpe.taboolib.ioc.bean.MethodInvocation"), goodAround.parameterTypes)
    }

    @Test
    fun flagsAopSilentFailures() {
        val methodInvocation = "top.wcpe.taboolib.ioc.bean.MethodInvocation"
        val index = BytecodeAnalysisIndex(
            classIndex = listOf(
                classEntry(
                    "com.example.ConcreteWorker",
                    methods = listOf(
                        CollectedMethodInfo("doWork", isPrivate = false, isStatic = false),
                        CollectedMethodInfo("secret", isPrivate = true, isStatic = false),
                    ),
                ),
                classEntry(
                    "com.example.WorkerApi",
                    isInterface = true,
                    methods = listOf(CollectedMethodInfo("doWork", isPrivate = false, isStatic = false)),
                ),
                classEntry(
                    "com.example.InterfaceWorker",
                    methods = listOf(CollectedMethodInfo("doWork", isPrivate = false, isStatic = false)),
                ),
            ),
            beanIndex = listOf(
                bean("concreteWorker", "com.example.ConcreteWorker"),
                bean("interfaceWorker", "com.example.InterfaceWorker"),
                bean(
                    "worker",
                    "com.example.AopConfig",
                    kind = BeanKind.FACTORY_METHOD,
                    factoryHostIsConfiguration = true,
                ).copy(exposedType = "com.example.WorkerApi"),
            ),
            injectionPointIndex = emptyList(),
            missingInjectCandidateIndex = emptyList(),
            componentBeanTypes = emptyList(),
            componentScans = emptyList(),
            aspectIndex = listOf(
                AspectDefinition(
                    aspectClassName = "com.example.LogAspect",
                    packageName = "com.example",
                    sourceFile = null,
                    pointcutMethods = mapOf("workerPointcut" to "execution(com.example.ConcreteWorker.doWork)"),
                    advices = listOf(
                        AspectAdviceDefinition("Around", "goodAround", "workerPointcut", listOf(methodInvocation)),
                        AspectAdviceDefinition("Before", "onWorkerApi", "execution(com.example.WorkerApi.doWork)"),
                        AspectAdviceDefinition("Before", "onSecret", "execution(com.example.ConcreteWorker.secret)"),
                    ),
                ),
            ),
            valueFieldIndex = emptyList(),
        )
        val report = StaticDiagnosisEngine.analyze(":test", index)

        // 无接口具体类被切面命中 → JDK 代理跳过、通知永不执行
        val notProxied = report.diagnostics.filter { it.rule == "aop-target-not-proxied" }
        assertEquals(1, notProxied.size)
        assertEquals("com.example.ConcreteWorker", notProxied.single().ownerClassName)

        // 有接口的 Bean 不应误报
        assertTrue(report.diagnostics.none { it.rule == "aop-target-not-proxied" && it.ownerClassName == "com.example.InterfaceWorker" })

        // @Bean 声明接口返回类型 → 运行时按声明类型收集接口必为空，代理永不生效
        val factoryReturn = report.diagnostics.filter { it.rule == "aop-factory-bean-interface-return" }
        assertEquals(1, factoryReturn.size)
        assertEquals("worker", factoryReturn.single().declarationName)

        // private 方法切点 → 运行时只匹配 public，静默死规则
        val privatePointcut = report.diagnostics.filter { it.rule == "aop-private-method-pointcut" }
        assertEquals(1, privatePointcut.size)

        // 本测试内的切点目标均存在 → 不应有 target-not-found
        assertTrue(report.diagnostics.none { it.rule == "pointcut-target-not-found" })
        // 签名合法 → 不应有 signature-invalid
        assertTrue(report.diagnostics.none { it.rule == "advice-signature-invalid" })
    }

    @Test
    fun flagsInvalidAdviceSignatures() {
        val methodInvocation = "top.wcpe.taboolib.ioc.bean.MethodInvocation"
        val index = BytecodeAnalysisIndex(
            classIndex = emptyList(),
            beanIndex = emptyList(),
            injectionPointIndex = emptyList(),
            missingInjectCandidateIndex = emptyList(),
            componentBeanTypes = emptyList(),
            componentScans = emptyList(),
            aspectIndex = listOf(
                AspectDefinition(
                    aspectClassName = "com.example.BadAspect",
                    packageName = "com.example",
                    sourceFile = null,
                    pointcutMethods = emptyMap(),
                    advices = listOf(
                        // 合法：@Around 恰好 1 个 MethodInvocation
                        AspectAdviceDefinition("Around", "okAround", "execution(com.example.Svc.run)", listOf(methodInvocation)),
                        // 非法：@Around 0 参
                        AspectAdviceDefinition("Around", "noArgAround", "execution(com.example.Svc.run)", emptyList()),
                        // 非法：@Around 参数类型不对
                        AspectAdviceDefinition("Around", "wrongTypeAround", "execution(com.example.Svc.run)", listOf("java.lang.String")),
                        // 非法：@AfterReturning 2 参
                        AspectAdviceDefinition(
                            "AfterReturning",
                            "twoParams",
                            "execution(com.example.Svc.run)",
                            listOf(methodInvocation, methodInvocation),
                        ),
                        // 合法：@AfterReturning 0 参
                        AspectAdviceDefinition("AfterReturning", "okAfter", "execution(com.example.Svc.run)", emptyList()),
                    ),
                ),
            ),
            valueFieldIndex = emptyList(),
        )
        val report = StaticDiagnosisEngine.analyze(":test", index)

        val errors = report.diagnostics.filter { it.rule == "advice-signature-invalid" }
        assertEquals(3, errors.size)
        assertTrue(errors.any { it.declarationName == "noArgAround" })
        assertTrue(errors.any { it.declarationName == "wrongTypeAround" })
        assertTrue(errors.any { it.declarationName == "twoParams" })
        assertEquals(DiagnosticSeverity.ERROR, errors.first().severity)
    }

    @Test
    fun flagsPointcutTargetNotFound() {
        val index = BytecodeAnalysisIndex(
            classIndex = listOf(
                classEntry(
                    "com.example.ConcreteWorker",
                    methods = listOf(CollectedMethodInfo("doWork", isPrivate = false, isStatic = false)),
                ),
            ),
            beanIndex = emptyList(),
            injectionPointIndex = emptyList(),
            missingInjectCandidateIndex = emptyList(),
            componentBeanTypes = emptyList(),
            componentScans = emptyList(),
            aspectIndex = listOf(
                AspectDefinition(
                    aspectClassName = "com.example.LogAspect",
                    packageName = "com.example",
                    sourceFile = null,
                    pointcutMethods = emptyMap(),
                    advices = listOf(
                        // 类不存在
                        AspectAdviceDefinition("Before", "onGhostClass", "execution(com.example.NoSuchService.run)"),
                        // 类存在但方法不存在
                        AspectAdviceDefinition("Before", "onGhostMethod", "execution(com.example.ConcreteWorker.ghost)"),
                        // 方法通配 → 不检查方法存在性
                        AspectAdviceDefinition("Before", "allMethods", "execution(com.example.ConcreteWorker.*)"),
                    ),
                ),
            ),
            valueFieldIndex = emptyList(),
        )
        val report = StaticDiagnosisEngine.analyze(":test", index)

        val notFound = report.diagnostics.filter { it.rule == "pointcut-target-not-found" }
        assertEquals(2, notFound.size)
        assertEquals(DiagnosticSeverity.WARNING, notFound.first().severity)
    }

    @Test
    fun deduplicatesSameClassScannedFromMultipleRoots() {
        val dirA = compileStructuralFixtures(tempDir.resolve("dup-a"))
        // dir B 使用字节码有差异的变体（MultiCtorBean 加 @Primary），
        // 证明「同一 FQCN 不同字节码」也能按 (owner, declaration, beanName) 去重
        val dirB = compileStructuralFixtures(tempDir.resolve("dup-b"), markMultiCtorPrimary = true)

        val index = BytecodeBeanIndexBuilder.build(listOf(dirA, dirB), emptyList())

        val classEntries = index.classIndex.filter { it.className == "fixture.structural.MultiCtorBean" }
        assertEquals(1, classEntries.size, "同一 FQCN 出现在多个扫描根时应按类名去重（项目输出优先）")
        val beans = index.beanIndex.filter { it.ownerClassName == "fixture.structural.MultiCtorBean" }
        assertEquals(1, beans.size, "去重后不应残留同 beanName 的假重复")
    }

    // ==================== 引擎层 ====================

    @Test
    fun flagsInterfaceAbstractAndEnumComponents() {
        val index = BytecodeAnalysisIndex(
            classIndex = listOf(
                classEntry("com.example.IfaceComponent", isInterface = true),
                classEntry("com.example.AbstractComponent", isAbstract = true),
                classEntry("com.example.ColorEnum", superClassName = "java.lang.Enum"),
                classEntry("com.example.NormalComponent"),
            ),
            beanIndex = listOf(
                bean("ifaceComponent", "com.example.IfaceComponent"),
                bean("abstractComponent", "com.example.AbstractComponent"),
                bean("colorEnum", "com.example.ColorEnum"),
                bean("normalComponent", "com.example.NormalComponent"),
            ),
            injectionPointIndex = emptyList(),
            missingInjectCandidateIndex = emptyList(),
            componentBeanTypes = emptyList(),
            componentScans = emptyList(),
        )
        val report = StaticDiagnosisEngine.analyze(":test", index)

        val errors = report.diagnostics.filter { it.rule == "bean-type-not-instantiable" }
        assertEquals(3, errors.size)
        assertEquals(DiagnosticSeverity.ERROR, errors.first().severity)
        assertTrue(errors.any { it.ownerClassName == "com.example.IfaceComponent" })
        assertTrue(errors.any { it.ownerClassName == "com.example.AbstractComponent" })
        assertTrue(errors.any { it.ownerClassName == "com.example.ColorEnum" })
    }

    @Test
    fun flagsMultipleConstructorsWithoutInjectOrNoArg() {
        val broken = bean(
            "brokenBean",
            "com.example.BrokenBean",
            constructorMetadata = ConstructorMetadata(
                hasExplicitInjectConstructor = false,
                totalConstructorCount = 2,
                runtimeSelectedConstructorHasParameters = false,
                runtimeSelectedConstructorHasNonNullableParameters = false,
                hasNoArgConstructor = false,
            ),
        )
        val hasInject = bean(
            "hasInjectBean",
            "com.example.HasInjectBean",
            constructorMetadata = ConstructorMetadata(
                hasExplicitInjectConstructor = true,
                totalConstructorCount = 2,
                runtimeSelectedConstructorHasParameters = true,
                runtimeSelectedConstructorHasNonNullableParameters = true,
                hasNoArgConstructor = false,
            ),
        )
        val hasNoArg = bean(
            "hasNoArgBean",
            "com.example.HasNoArgBean",
            constructorMetadata = ConstructorMetadata(
                hasExplicitInjectConstructor = false,
                totalConstructorCount = 2,
                runtimeSelectedConstructorHasParameters = false,
                runtimeSelectedConstructorHasNonNullableParameters = false,
                hasNoArgConstructor = true,
            ),
        )
        val singleCtor = bean(
            "singleCtorBean",
            "com.example.SingleCtorBean",
            constructorMetadata = ConstructorMetadata(
                hasExplicitInjectConstructor = false,
                totalConstructorCount = 1,
                runtimeSelectedConstructorHasParameters = true,
                runtimeSelectedConstructorHasNonNullableParameters = true,
                hasNoArgConstructor = false,
            ),
        )
        val index = BytecodeAnalysisIndex(
            classIndex = emptyList(),
            beanIndex = listOf(broken, hasInject, hasNoArg, singleCtor),
            injectionPointIndex = emptyList(),
            missingInjectCandidateIndex = emptyList(),
            componentBeanTypes = emptyList(),
            componentScans = emptyList(),
        )
        val report = StaticDiagnosisEngine.analyze(":test", index)

        val errors = report.diagnostics.filter { it.rule == "bean-no-resolvable-constructor" }
        assertEquals(1, errors.size)
        assertEquals("com.example.BrokenBean", errors.single().ownerClassName)
    }

    @Test
    fun flagsUnknownScopeButAcceptsNormalizedAndKnownScopes() {
        val index = BytecodeAnalysisIndex(
            classIndex = emptyList(),
            beanIndex = listOf(
                bean("weirdScope", "com.example.WeirdScope", scope = "request"),
                bean("upperScope", "com.example.UpperScope", scope = "REFRESH"),
                bean("knownScope", "com.example.KnownScope", scope = "tenant"),
                bean("normalScope", "com.example.NormalScope", scope = null),
            ),
            injectionPointIndex = emptyList(),
            missingInjectCandidateIndex = emptyList(),
            componentBeanTypes = emptyList(),
            componentScans = emptyList(),
        )
        val report = StaticDiagnosisEngine.analyze(
            ":test",
            index,
            projectProperties = mapOf("taboolib.ioc.knownScopes" to "tenant"),
        )

        val errors = report.diagnostics.filter { it.rule == "unknown-bean-scope" }
        assertEquals(1, errors.size)
        assertEquals("com.example.WeirdScope", errors.single().ownerClassName)
    }

    @Test
    fun flagsLifecycleMethodsWithParameters() {
        val index = BytecodeAnalysisIndex(
            classIndex = emptyList(),
            beanIndex = listOf(
                bean(
                    "lifecycleBean",
                    "com.example.LifecycleBean",
                    lifecycleMethodDetails = listOf(
                        LifecycleMethodDetail("PostConstruct", "ok", 0, false),
                        LifecycleMethodDetail("PostConstruct", "bad", 1, false),
                        LifecycleMethodDetail("PreDestroy", "alsoBad", 2, true),
                    ),
                ),
            ),
            injectionPointIndex = emptyList(),
            missingInjectCandidateIndex = emptyList(),
            componentBeanTypes = emptyList(),
            componentScans = emptyList(),
        )
        val report = StaticDiagnosisEngine.analyze(":test", index)

        val errors = report.diagnostics.filter { it.rule == "lifecycle-method-signature-invalid" }
        assertEquals(2, errors.size)
        assertTrue(errors.any { it.declarationName == "bad" })
        assertTrue(errors.any { it.declarationName == "alsoBad" })
    }

    @Test
    fun flagsBeanMethodOutsideConfigurationAndVoidReturn() {
        val index = BytecodeAnalysisIndex(
            classIndex = emptyList(),
            beanIndex = listOf(
                bean(
                    "makeGreeting",
                    "com.example.NotConfigHost",
                    kind = BeanKind.FACTORY_METHOD,
                    factoryHostIsConfiguration = false,
                ),
                bean(
                    "voidInit",
                    "com.example.ValidConfig",
                    kind = BeanKind.FACTORY_METHOD,
                    factoryHostIsConfiguration = true,
                    factoryMethodReturnsVoid = true,
                ),
                bean(
                    "greeting",
                    "com.example.ValidConfig",
                    kind = BeanKind.FACTORY_METHOD,
                    factoryHostIsConfiguration = true,
                ),
            ),
            injectionPointIndex = emptyList(),
            missingInjectCandidateIndex = emptyList(),
            componentBeanTypes = emptyList(),
            componentScans = emptyList(),
        )
        val report = StaticDiagnosisEngine.analyze(":test", index)

        val outside = report.diagnostics.filter { it.rule == "bean-method-outside-configuration" }
        assertEquals(1, outside.size)
        assertEquals("com.example.NotConfigHost", outside.single().ownerClassName)

        val voidReturn = report.diagnostics.filter { it.rule == "bean-method-void-return" }
        assertEquals(1, voidReturn.size)
        assertEquals("voidInit", voidReturn.single().declarationName)
    }

    @Test
    fun flagsValuePlaceholderAndTypeIssues() {
        val index = BytecodeAnalysisIndex(
            classIndex = emptyList(),
            beanIndex = emptyList(),
            injectionPointIndex = emptyList(),
            missingInjectCandidateIndex = emptyList(),
            componentBeanTypes = emptyList(),
            componentScans = emptyList(),
            aspectIndex = emptyList(),
            valueFieldIndex = listOf(
                valueField("prefixedUrl", "jdbc:\${db.host}/x", "java.lang.String"),
                valueField("wholePlaceholder", "\${port}", "int"),
                valueField("defaultedPlaceholder", "\${port:8080}", "int"),
                valueField("uuidField", "\${flag}", "java.util.UUID"),
                valueField("listField", "\${names}", "java.util.List"),
            ),
        )
        val report = StaticDiagnosisEngine.analyze(":test", index)

        val placeholders = report.diagnostics.filter { it.rule == "value-expression-unresolved-placeholder" }
        assertEquals(1, placeholders.size)
        assertEquals("prefixedUrl", placeholders.single().declarationName)

        val types = report.diagnostics.filter { it.rule == "value-type-unsupported" }
        assertEquals(2, types.size)
        assertTrue(types.any { it.declarationName == "uuidField" })
        assertTrue(types.any { it.declarationName == "listField" })
    }

    @Test
    fun flagsInvalidPointcutExpressions() {
        val index = BytecodeAnalysisIndex(
            classIndex = emptyList(),
            beanIndex = emptyList(),
            injectionPointIndex = emptyList(),
            missingInjectCandidateIndex = emptyList(),
            componentBeanTypes = emptyList(),
            componentScans = emptyList(),
            aspectIndex = listOf(
                AspectDefinition(
                    aspectClassName = "com.example.LogAspect",
                    packageName = "com.example",
                    sourceFile = null,
                    pointcutMethods = mapOf("svcPointcut" to "execution(com.example.Svc.*.doIt)"),
                    advices = listOf(
                        AspectAdviceDefinition("Before", "okReference", "svcPointcut"),
                        AspectAdviceDefinition("Before", "ghostReference", "ghostPointcut"),
                        AspectAdviceDefinition("Around", "bareName", "greet"),
                        AspectAdviceDefinition("Before", "validExecution", "execution(com.example.Svc.doIt)"),
                        AspectAdviceDefinition("Before", "validWildcard", "execution(*.doIt)"),
                        AspectAdviceDefinition("After", "emptyExpression", ""),
                    ),
                ),
            ),
            valueFieldIndex = emptyList(),
        )
        val report = StaticDiagnosisEngine.analyze(":test", index)

        val errors = report.diagnostics.filter { it.rule == "pointcut-expression-invalid" }
        assertEquals(3, errors.size)
        assertTrue(errors.any { it.declarationName == "ghostReference" })
        assertTrue(errors.any { it.declarationName == "bareName" })
        assertTrue(errors.any { it.declarationName == "emptyExpression" })
        assertEquals(DiagnosticSeverity.ERROR, errors.first().severity)
    }

    @Test
    fun flagsDuplicateBeanNames() {
        val index = BytecodeAnalysisIndex(
            classIndex = emptyList(),
            beanIndex = listOf(
                bean("cache", "com.example.a.CacheService"),
                bean("cache", "com.example.b.CacheService"),
                bean("unique", "com.example.a.UniqueService"),
            ),
            injectionPointIndex = emptyList(),
            missingInjectCandidateIndex = emptyList(),
            componentBeanTypes = emptyList(),
            componentScans = emptyList(),
        )
        val report = StaticDiagnosisEngine.analyze(":test", index)

        val duplicates = report.diagnostics.filter { it.rule == "duplicate-bean-name" }
        assertEquals(1, duplicates.size)
        // K21 修复：运行时 ComponentVisitor 对重名 Bean 静默跳过后者、插件仍能启动，
        // 故静态严重度由 ERROR 降为 WARNING（避免 failOnError 误阻断）。
        assertEquals(DiagnosticSeverity.WARNING, duplicates.single().severity)
        assertTrue(duplicates.single().message.contains("com.example.a.CacheService"))
        assertTrue(duplicates.single().message.contains("com.example.b.CacheService"))
    }

    // ==================== 构造辅助 ====================

    private fun bean(
        beanName: String,
        className: String,
        kind: BeanKind = BeanKind.CLASS,
        scope: String? = null,
        constructorMetadata: ConstructorMetadata? = null,
        factoryHostIsConfiguration: Boolean? = null,
        factoryMethodReturnsVoid: Boolean = false,
        lifecycleMethodDetails: List<LifecycleMethodDetail> = emptyList(),
    ) = BeanDefinition(
        ownerClassName = className,
        declarationName = beanName,
        beanName = beanName,
        exposedType = className,
        packageName = className.substringBeforeLast('.'),
        sourceFile = null,
        kind = kind,
        exposedGenericType = null,
        primary = false,
        order = null,
        conditionalAnnotations = emptyList(),
        conditions = emptyList(),
        constructorMetadata = constructorMetadata,
        scope = scope,
        factoryHostIsConfiguration = factoryHostIsConfiguration,
        factoryMethodReturnsVoid = factoryMethodReturnsVoid,
        lifecycleMethodDetails = lifecycleMethodDetails,
    )

    private fun classEntry(
        className: String,
        isInterface: Boolean = false,
        isAbstract: Boolean = false,
        superClassName: String? = null,
        methods: List<CollectedMethodInfo> = emptyList(),
    ) = ClassIndexEntry(
        className = className,
        packageName = className.substringBeforeLast('.'),
        sourceFile = null,
        superClassName = superClassName,
        interfaceNames = emptyList(),
        fields = emptyList(),
        isInterface = isInterface,
        isAbstract = isAbstract,
        methods = methods,
    )

    private fun valueField(fieldName: String, expression: String, targetType: String) = ValueFieldDefinition(
        ownerClassName = "com.example.ValueHolder",
        fieldName = fieldName,
        expression = expression,
        targetType = targetType,
        packageName = "com.example",
        sourceFile = null,
    )

    // ==================== Java fixture 编译 ====================

    private fun annotationSource(name: String, targets: String, body: String = ""): String {
        return """
            package fixture.structural.annotations;

            import java.lang.annotation.*;

            @Retention(RetentionPolicy.CLASS) @Target({$targets})
            public @interface $name { $body }
        """.trimIndent()
    }

    private fun compileStructuralFixtures(root: Path, markMultiCtorPrimary: Boolean = false): Path {
        val sources = linkedMapOf<String, String>()

        fun declare(name: String, targets: String, body: String = "") {
            sources["fixture/structural/annotations/$name.java"] = annotationSource(name, targets, body)
        }

        declare("Component", "ElementType.TYPE", body = "String value() default \"\";")
        declare("Bean", "ElementType.TYPE, ElementType.METHOD", body = "String value() default \"\";")
        declare("Configuration", "ElementType.TYPE")
        declare("Scope", "ElementType.TYPE, ElementType.METHOD", body = "String value();")
        declare("Value", "ElementType.FIELD", body = "String value();")
        declare("Aspect", "ElementType.TYPE")
        declare("Pointcut", "ElementType.METHOD", body = "String value();")
        declare("Before", "ElementType.METHOD", body = "String value();")
        declare("AfterReturning", "ElementType.METHOD", body = "String value();")
        declare("Around", "ElementType.METHOD", body = "String value();")
        declare("Inject", "ElementType.CONSTRUCTOR, ElementType.FIELD, ElementType.METHOD")
        declare("PostConstruct", "ElementType.METHOD")
        declare("Primary", "ElementType.TYPE, ElementType.METHOD")

        val multiCtorHeader = if (markMultiCtorPrimary) {
            "@Component\n            @Primary\n            class MultiCtorBean {"
        } else {
            "@Component\n            class MultiCtorBean {"
        }

        sources["fixture/structural/StructuralBeans.java"] = """
            package fixture.structural;

            import fixture.structural.annotations.*;

            @Component
            interface InterfaceComponent {}

            @Component
            abstract class AbstractComponent {}

            @Component
            enum ColorEnum { RED }

            $multiCtorHeader
                MultiCtorBean(String a) {}
                MultiCtorBean(String a, int b) {}
            }

            @Component
            class SingleCtorBean {
                SingleCtorBean(String a) {}
            }

            @Component
            class InjectCtorBean {
                @Inject InjectCtorBean(String a) {}
                InjectCtorBean(String a, int b) {}
            }

            @Component
            @Scope("request")
            class WeirdScopeBean {}

            @Component
            class LifecycleBean {
                @PostConstruct void ok() {}
                @PostConstruct void bad(int x) {}
            }

            class GreetingService {}

            interface ServerApi {}

            // H1 模拟：Kotlin 属性注解编译到合成载体方法，字段本身无注解
            @Component
            class CarrierHost {
                ServerApi name;

                @Inject
                private static void getName${'$'}annotations() {}
            }

            // H3 模拟：@JvmField companion 属性 —— backing field 在外部类静态字段，载体在 Companion 类
            class CompanionHost {
                static ServerApi svc;

                static final class Companion {
                    @Inject
                    private static void getSvc${'$'}annotations() {}
                }
            }

            // H2 模拟：嵌套类不再被 $ 过滤跳过
            @Component
            class OuterWithNested {
                @Component
                class NestedComponent {}
            }

            @Component
            class ConcreteWorker {
                public void doWork() {}
                private void secret() {}
            }

            interface WorkerApi {
                void doWork();
            }

            @Component
            class InterfaceWorker implements WorkerApi {
                @Override
                public void doWork() {}
            }

            @Configuration
            class AopConfig {
                @Bean WorkerApi worker() { return null; }
            }

            @Aspect
            class AopAspect {
                @Pointcut("execution(fixture.structural.ConcreteWorker.doWork)") void workerPointcut() {}
                @Around("workerPointcut") top.wcpe.taboolib.ioc.bean.MethodInvocation goodAround(top.wcpe.taboolib.ioc.bean.MethodInvocation invocation) { return invocation; }
                @Around("greet") void badAround() {}
                @AfterReturning("execution(fixture.structural.MissingService.run)") void twoParams(top.wcpe.taboolib.ioc.bean.MethodInvocation a, top.wcpe.taboolib.ioc.bean.MethodInvocation b) {}
                @Before("execution(fixture.structural.ConcreteWorker.secret)") void onSecret() {}
                @Before("execution(fixture.structural.WorkerApi.doWork)") void onWorkerApi() {}
                @Before("execution(fixture.structural.GhostTarget.run)") void onGhost() {}
            }

            @Component
            class NotConfigHost {
                @Bean GreetingService makeGreeting() { return new GreetingService(); }
            }

            @Configuration
            class ValidConfig {
                @Bean GreetingService greeting() { return new GreetingService(); }
                @Bean void voidInit() {}
            }

            @Component
            class ValueHolder {
                @Value("jdbc:${'$'}{db.host}/x") String url;
                @Value("${'$'}{port}") int port;
                @Value("${'$'}{flag}") java.util.UUID id;
                @Value("literal") boolean literalBool;
            }

            @Aspect
            class LogAspect {
                @Pointcut("execution(fixture.structural.Svc.*.doIt)") void svcPointcut() {}
                @Before("svcPointcut") void okReference() {}
                @Before("ghostPointcut") void ghostReference() {}
                @Around("greet") void bareName() {}
                @Before("execution(fixture.structural.GreetingService.*.ok)") void validExecution() {}
            }
        """.trimIndent()

        sources["top/wcpe/taboolib/ioc/bean/MethodInvocation.java"] = """
            package top.wcpe.taboolib.ioc.bean;

            public class MethodInvocation {}
        """.trimIndent()

        val srcDir = root.resolve("src")
        sources.forEach { relative, content ->
            val target = srcDir.resolve(relative)
            target.parent.createDirectories()
            target.writeText(content)
        }

        val classesDir = root.resolve("classes")
        classesDir.createDirectories()
        val compiler = ToolProvider.getSystemJavaCompiler()
        assertNotNull(compiler, "需要 JDK 提供的 Java 编译器")
        val fileManager = compiler.getStandardFileManager(null, null, Charsets.UTF_8)
        val diagnostics = javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>()
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
            "fixture 编译失败: " + diagnostics.diagnostics.joinToString("; ") { it.toString() },
        )
        return classesDir
    }
}
