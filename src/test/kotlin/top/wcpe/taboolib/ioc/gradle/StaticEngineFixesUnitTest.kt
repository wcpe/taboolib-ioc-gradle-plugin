package top.wcpe.taboolib.ioc.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.wcpe.taboolib.ioc.gradle.analysis.AspectAdviceDefinition
import top.wcpe.taboolib.ioc.gradle.analysis.AspectDefinition
import top.wcpe.taboolib.ioc.gradle.analysis.BeanDefinition
import top.wcpe.taboolib.ioc.gradle.analysis.BeanKind
import top.wcpe.taboolib.ioc.gradle.analysis.BytecodeAnalysisIndex
import top.wcpe.taboolib.ioc.gradle.analysis.ClassIndexEntry
import top.wcpe.taboolib.ioc.gradle.analysis.CollectedMethodInfo
import top.wcpe.taboolib.ioc.gradle.analysis.CycleDependencyDetector
import top.wcpe.taboolib.ioc.gradle.analysis.DiagnosticSeverity
import top.wcpe.taboolib.ioc.gradle.analysis.FieldInfo
import top.wcpe.taboolib.ioc.gradle.analysis.InjectionPointDefinition
import top.wcpe.taboolib.ioc.gradle.analysis.InjectionPointKind
import top.wcpe.taboolib.ioc.gradle.analysis.StaticDiagnosisEngine
import top.wcpe.taboolib.ioc.gradle.analysis.TypeAliasDefinition

/**
 * E6 静态引擎修复批次的回归测试（K6 / K25 / K23 / K21 / C-P2-05 / B-P1-04 / B-P1-10）。
 *
 * 每一条都对应一个「本应为红」的真实缺陷：先构造能触发缺陷的输入，再断言修复后的行为。
 */
class StaticEngineFixesUnitTest {

    // ==================== K6：@Lazy 断边（P0） ====================

    /**
     * fixture ①：接口类型 @Lazy 环 → 运行时由 LazyProxyFactory 代理断开，
     * 静态侧必须**不报 ERROR**（降级为 WARNING）。
     */
    @Test
    fun lazyInterfaceCycleIsNotBlockingError() {
        val beans = listOf(
            bean("a", "com.example.A"),
            bean("b", "com.example.B"),
        )
        val injections = listOf(
            injection("com.example.A", "b", "com.example.B", lazy = true),
            injection("com.example.B", "a", "com.example.A", lazy = false),
        )
        // classIndex 判定 B 是接口
        val classIndex = listOf(
            classEntry("com.example.A", isInterface = false),
            classEntry("com.example.B", isInterface = true),
        )

        val cycles = CycleDependencyDetector.detectCycles(beans, injections, classIndex)
        assertEquals(1, cycles.size, "接口 @Lazy 环仍应被检测到（只是不阻断）")
        assertTrue(cycles.single().resolvable, "接口 @Lazy 断开的环应标记为可解析")

        val report = StaticDiagnosisEngine.analyze(":test", index(beans, injections, classIndex))
        val cycleDiags = report.diagnostics.filter { it.rule == "circular-dependency-detected" }
        assertEquals(1, cycleDiags.size)
        assertFalse(
            cycleDiags.any { it.severity == DiagnosticSeverity.ERROR },
            "接口 @Lazy 已断开的环不得触发 ERROR（否则 failOnError 会误阻断正确工程）",
        )
    }

    /**
     * fixture ②：**非接口**类型 @Lazy 环 → 运行时 warning + 回退立即注入，环并未断开，
     * 静态侧必须**仍报 ERROR**（陷阱 1：不能只判 ip.lazy）。
     *
     * 这里用 prototype 作用域（非 singleton）使得早期暴露无法解析，强制暴露真实强度：
     * 若静态只按 ip.lazy 过滤，该真环会被漏报。
     */
    @Test
    fun lazyConcreteClassCycleStillBlocks() {
        val beans = listOf(
            bean("a", "com.example.A", scope = "prototype"),
            bean("b", "com.example.B", scope = "prototype"),
        )
        val injections = listOf(
            injection("com.example.A", "b", "com.example.B", lazy = true),
            injection("com.example.B", "a", "com.example.A", lazy = false),
        )
        // 两者都是具体类，@Lazy 无法创建代理 → 环未断开
        val classIndex = listOf(
            classEntry("com.example.A", isInterface = false),
            classEntry("com.example.B", isInterface = false),
        )

        val cycles = CycleDependencyDetector.detectCycles(beans, injections, classIndex)
        assertEquals(1, cycles.size, "非接口 @Lazy 环仍必须被检测到（不得被过滤掉）")
        assertFalse(cycles.single().resolvable, "非接口 @Lazy 回退立即注入，环不可解析")

        val report = StaticDiagnosisEngine.analyze(":test", index(beans, injections, classIndex))
        val cycleDiags = report.diagnostics.filter { it.rule == "circular-dependency-detected" }
        assertEquals(1, cycleDiags.size)
        assertEquals(
            DiagnosticSeverity.ERROR,
            cycleDiags.single().severity,
            "非接口 @Lazy 未断开真环，必须保持 ERROR —— 否则引入漏报",
        )
    }

    /**
     * fixture ③：@Lazy(false) 环 → 语义是「不延迟」，采集层必须读出 value=false（陷阱 2），
     * 静态侧必须**仍报**（不得被当延迟边过滤掉）。
     *
     * 这里直接用采集层产物语义（ip.lazy=false）验证；`lazyFlag` 读 value 的行为由
     * `collectsLazyFlagFromAnnotationValue` 编译夹具测试覆盖。
     */
    @Test
    fun lazyFalseCycleStillBlocks() {
        val beans = listOf(
            bean("a", "com.example.A", scope = "prototype"),
            bean("b", "com.example.B", scope = "prototype"),
        )
        val injections = listOf(
            injection("com.example.A", "b", "com.example.B", lazy = false),
            injection("com.example.B", "a", "com.example.A", lazy = false),
        )
        val classIndex = listOf(
            classEntry("com.example.B", isInterface = true),
        )

        val cycles = CycleDependencyDetector.detectCycles(beans, injections, classIndex)
        assertEquals(1, cycles.size, "@Lazy(false) 环不得因误判为延迟而漏报")
        assertFalse(cycles.single().resolvable, "@Lazy(false) 等价于不延迟，环不可解析")

        val report = StaticDiagnosisEngine.analyze(":test", index(beans, injections, classIndex))
        val cycleDiags = report.diagnostics.filter { it.rule == "circular-dependency-detected" }
        assertEquals(1, cycleDiags.size)
        assertEquals(DiagnosticSeverity.ERROR, cycleDiags.single().severity)
    }

    /** 陷阱 2：采集层必须读 @Lazy 的 value 属性，而非只判注解存在（@Lazy(false) 不延迟）。 */
    @Test
    fun collectsLazyFlagFromAnnotationValue() {
        val classesDir = StaticEngineFixtureCompiler.compileLazyFixtures(tempDirPath())
        val index = top.wcpe.taboolib.ioc.gradle.analysis.BytecodeBeanIndexBuilder.build(listOf(classesDir))

        val lazyTrue = index.injectionPointIndex.single {
            it.ownerClassName == "fixture.lazy.LazyTrueHolder" && it.declarationName == "dep"
        }
        assertTrue(lazyTrue.lazy, "@Lazy（默认 value=true）应采集为 lazy=true")

        val lazyFalse = index.injectionPointIndex.single {
            it.ownerClassName == "fixture.lazy.LazyFalseHolder" && it.declarationName == "dep"
        }
        assertFalse(lazyFalse.lazy, "@Lazy(false) 语义为不延迟，必须采集为 lazy=false（不是仅判注解存在）")

        val lazyConstructor = index.injectionPointIndex.single {
            it.ownerClassName == "fixture.lazy.LazyCtorHolder" && it.kind == InjectionPointKind.CONSTRUCTOR_PARAMETER
        }
        assertTrue(lazyConstructor.lazy, "构造器参数上的 @Lazy 也应被采集")
    }

    // ==================== K25：static 切点（P1） ====================

    @Test
    fun flagsStaticOnlyMethodPointcut() {
        val report = StaticDiagnosisEngine.analyze(
            ":test",
            BytecodeAnalysisIndex(
                classIndex = listOf(
                    classEntry(
                        "com.example.StaticWorker",
                        isInterface = false,
                        methods = listOf(CollectedMethodInfo("ping", isPrivate = false, isStatic = true)),
                    ),
                ),
                beanIndex = listOf(bean("staticWorker", "com.example.StaticWorker")),
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
                            AspectAdviceDefinition("Before", "onPing", "execution(com.example.StaticWorker.ping)"),
                        ),
                    ),
                ),
            ),
        )
        val staticDiags = report.diagnostics.filter { it.rule == "aop-static-method-pointcut" }
        assertEquals(1, staticDiags.size, "只匹配 static 方法的切点应告警（JDK 代理永不进入 handler）")
        assertEquals(DiagnosticSeverity.WARNING, staticDiags.single().severity)
    }

    @Test
    fun doesNotFlagInstanceMethodPointcut() {
        val report = StaticDiagnosisEngine.analyze(
            ":test",
            BytecodeAnalysisIndex(
                classIndex = listOf(
                    classEntry(
                        "com.example.InstanceWorker",
                        isInterface = false,
                        methods = listOf(CollectedMethodInfo("ping", isPrivate = false, isStatic = false)),
                    ),
                ),
                beanIndex = listOf(bean("instanceWorker", "com.example.InstanceWorker")),
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
                            AspectAdviceDefinition("Before", "onPing", "execution(com.example.InstanceWorker.ping)"),
                        ),
                    ),
                ),
            ),
        )
        assertTrue(
            report.diagnostics.none { it.rule == "aop-static-method-pointcut" },
            "命中实例方法的切点不得误报 static 死规则",
        )
    }

    // ==================== K23：通知签名严重度对齐（P1） ====================

    @Test
    fun aroundSignatureErrorIsError() {
        val report = analyzeAdvice("Around", "badAround", emptyList())
        val diag = report.single { it.rule == "advice-signature-invalid" }
        assertEquals(DiagnosticSeverity.ERROR, diag.severity, "@Around 签名错运行时会抛异常 → ERROR")
    }

    @Test
    fun afterReturningSignatureErrorIsWarning() {
        val methodInvocation = "top.wcpe.taboolib.ioc.bean.MethodInvocation"
        val report = analyzeAdvice("AfterReturning", "badAfter", listOf(methodInvocation, methodInvocation))
        val diag = report.single { it.rule == "advice-signature-invalid" }
        assertEquals(
            DiagnosticSeverity.WARNING,
            diag.severity,
            "@AfterReturning 签名错运行时被静默吞掉 → WARNING（不得误报为 ERROR）",
        )
    }

    @Test
    fun afterThrowingSignatureErrorIsWarning() {
        val methodInvocation = "top.wcpe.taboolib.ioc.bean.MethodInvocation"
        val report = analyzeAdvice("AfterThrowing", "badThrowing", listOf(methodInvocation, methodInvocation))
        val diag = report.single { it.rule == "advice-signature-invalid" }
        assertEquals(DiagnosticSeverity.WARNING, diag.severity)
    }

    // ==================== K21：duplicate-bean-name 降级（P1） ====================

    @Test
    fun duplicateBeanNameIsWarningNotError() {
        val beans = listOf(
            bean("dup", "com.example.a.DupBean"),
            bean("dup", "com.example.b.DupBean"),
        )
        val report = StaticDiagnosisEngine.analyze(":test", index(beans, emptyList(), emptyList()))
        val diag = report.diagnostics.single { it.rule == "duplicate-bean-name" }
        assertEquals(
            DiagnosticSeverity.WARNING,
            diag.severity,
            "运行时重名 Bean 静默跳过后者、插件仍能启动 → 静态应为 WARNING（否则 failOnError 误阻断）",
        )
    }

    // ==================== C-P2-05：ExecutorService 资源类型（P2） ====================

    @Test
    fun detectsExecutorServiceAsRefreshResource() {
        val bean = bean("poolService", "com.example.PoolService", scope = "refresh")
        val classIndex = listOf(
            ClassIndexEntry(
                className = "com.example.PoolService",
                packageName = "com.example",
                sourceFile = null,
                superClassName = null,
                interfaceNames = emptyList(),
                fields = listOf(FieldInfo("pool", "java.util.concurrent.ExecutorService", "L...;")),
            ),
        )
        val report = StaticDiagnosisEngine.analyze(
            ":test",
            BytecodeAnalysisIndex(
                classIndex = classIndex,
                beanIndex = listOf(bean),
                injectionPointIndex = emptyList(),
                missingInjectCandidateIndex = emptyList(),
                componentBeanTypes = emptyList(),
                componentScans = emptyList(),
            ),
        )
        val diag = report.diagnostics.single { it.rule == "refresh-scope-missing-predestroy" }
        assertEquals(DiagnosticSeverity.WARNING, diag.severity)
    }

    // ==================== B-P1-04：typeAlias 归一化（P1） ====================

    @Test
    fun resolvesTypeAliasForInjectionCandidate() {
        // @Component class ServerImpl implements ServerApi；依赖声明为 typealias MyServer = ServerApi
        val classIndex = listOf(
            classEntry("com.example.ServerApi", isInterface = true),
            classEntry("com.example.ServerImpl", isInterface = false, superClassName = null)
                .copy(interfaceNames = listOf("com.example.ServerApi")),
        )
        val beans = listOf(bean("serverImpl", "com.example.ServerImpl").copy(exposedType = "com.example.ServerImpl"))
        val injections = listOf(
            injection("com.example.Client", "server", "com.example.MyServer", lazy = false),
        )
        val typeAliases = listOf(
            TypeAliasDefinition(
                packageName = "com.example",
                aliasName = "MyServer",
                targetType = "com.example.ServerApi",
                sourceFile = "Client.kt",
            ),
        )
        val report = StaticDiagnosisEngine.analyze(
            ":test",
            index(beans, injections, classIndex),
            typeAliases = typeAliases,
        )
        assertTrue(
            report.diagnostics.none { it.rule == "missing-bean" },
            "typealias 应被归一化到 target 类型，从而命中接口实现 Bean（否则误报 missing-bean）",
        )
    }

    // ==================== B-P1-10：relocate 裸前缀冲突（P1） ====================

    @Test
    fun detectsSiblingPackageRelocationConflict() {
        val method = Class.forName("top.wcpe.taboolib.ioc.gradle.backend.TabooLibBackend")
        val findConflict = method.getDeclaredMethod(
            "findRelocationConflict",
            Map::class.java,
            String::class.java,
            String::class.java,
        ).apply { isAccessible = true }
        val backend = method.getField("INSTANCE").get(null)

        // 兄弟包 iocx 是 ioc 的裸前缀延伸 —— 引擎会越界替换，必须报冲突
        val conflict = findConflict.invoke(
            backend,
            mapOf("top.wcpe.taboolib.iocx" to "shadow.iocx"),
            "top.wcpe.taboolib.ioc",
            "shadow.ioc",
        ) as String?
        assertTrue(conflict != null, "裸前缀兄弟包 iocx 与 ioc 重叠，应报冲突（旧包段边界判定会漏报）")

        // 无关包不应误报
        val noConflict = findConflict.invoke(
            backend,
            mapOf("com.other.lib" to "shadow.other"),
            "top.wcpe.taboolib.ioc",
            "shadow.ioc",
        ) as String?
        assertTrue(noConflict == null, "无关包不得误报冲突")
    }

    // ==================== 辅助 ====================

    private fun analyzeAdvice(annotation: String, method: String, parameterTypes: List<String>): List<top.wcpe.taboolib.ioc.gradle.analysis.StaticDiagnostic> {
        val report = StaticDiagnosisEngine.analyze(
            ":test",
            BytecodeAnalysisIndex(
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
                            AspectAdviceDefinition(annotation, method, "execution(com.example.Svc.run)", parameterTypes),
                        ),
                    ),
                ),
            ),
        )
        return report.diagnostics.filter { it.rule == "advice-signature-invalid" }
    }

    private fun index(
        beans: List<BeanDefinition>,
        injections: List<InjectionPointDefinition>,
        classIndex: List<ClassIndexEntry>,
    ) = BytecodeAnalysisIndex(
        classIndex = classIndex,
        beanIndex = beans,
        injectionPointIndex = injections,
        missingInjectCandidateIndex = emptyList(),
        componentBeanTypes = emptyList(),
        componentScans = emptyList(),
    )

    private fun bean(
        beanName: String,
        className: String,
        scope: String? = null,
    ) = BeanDefinition(
        ownerClassName = className,
        declarationName = beanName,
        beanName = beanName,
        exposedType = className,
        packageName = className.substringBeforeLast('.'),
        sourceFile = null,
        kind = BeanKind.CLASS,
        exposedGenericType = null,
        primary = false,
        order = null,
        conditionalAnnotations = emptyList(),
        conditions = emptyList(),
        scope = scope,
    )

    private fun classEntry(
        className: String,
        isInterface: Boolean = false,
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
        methods = methods,
    )

    private fun injection(
        ownerClassName: String,
        declarationName: String,
        dependencyType: String,
        lazy: Boolean,
    ) = InjectionPointDefinition(
        ownerClassName = ownerClassName,
        declarationName = declarationName,
        dependencyType = dependencyType,
        dependencyGenericType = null,
        ownerPackage = ownerClassName.substringBeforeLast('.'),
        sourceFile = null,
        sourcePath = null,
        sourceLine = null,
        sourceColumn = null,
        kind = InjectionPointKind.FIELD,
        parameterIndex = null,
        qualifierName = null,
        required = true,
        lazy = lazy,
    )

    private fun tempDirPath(): java.nio.file.Path =
        java.nio.file.Files.createTempDirectory("static-engine-fixes")
}
