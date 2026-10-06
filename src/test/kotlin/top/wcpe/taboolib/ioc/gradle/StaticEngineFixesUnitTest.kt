package top.wcpe.taboolib.ioc.gradle

import java.nio.file.Files
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.objectweb.asm.Opcodes
import top.wcpe.taboolib.ioc.gradle.analysis.AspectAdviceDefinition
import top.wcpe.taboolib.ioc.gradle.analysis.AspectDefinition
import top.wcpe.taboolib.ioc.gradle.analysis.BeanDefinition
import top.wcpe.taboolib.ioc.gradle.analysis.BeanKind
import top.wcpe.taboolib.ioc.gradle.analysis.BytecodeAnalysisIndex
import top.wcpe.taboolib.ioc.gradle.analysis.BytecodeBeanIndexBuilder
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
                        methods = listOf(CollectedMethodInfo("ping", access = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC)),
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
                        methods = listOf(CollectedMethodInfo("ping", access = Opcodes.ACC_PUBLIC)),
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

    @Test
    @DisplayName("具体类依赖不应被实现的参数化接口误拒绝")
    fun acceptsConcreteDependencyWithParameterizedSupertype() {
        val diagnostics = genericDiagnostics("com.example.Impl", "com.example.Impl")
        assertFalse(diagnostics.any { it.rule == "missing-bean" }, "具体类依赖只需通过可赋值检查")
    }

    @Test
    @DisplayName("原始接口依赖不应被实现的参数化接口误拒绝")
    fun acceptsRawInterfaceDependencyWithParameterizedImplementation() {
        val diagnostics = genericDiagnostics("com.example.Port", "com.example.Port")
        assertFalse(diagnostics.any { it.rule == "missing-bean" }, "无类型参数的接口依赖应允许实现类")
    }

    @Test
    @DisplayName("兼容的参数化接口依赖继续通过")
    fun acceptsMatchingParameterizedDependency() {
        val diagnostics = genericDiagnostics("com.example.Port", "com.example.Port<java.lang.String>")
        assertFalse(diagnostics.any { it.rule == "missing-bean" }, "相同泛型实参应继续匹配")
    }

    @Test
    @DisplayName("不兼容的参数化接口依赖继续阻断")
    fun rejectsIncompatibleParameterizedDependency() {
        val diagnostics = genericDiagnostics("com.example.Port", "com.example.Port<java.lang.Long>")
        assertTrue(diagnostics.any { it.rule == "missing-bean" }, "不兼容泛型实参不能被普通类型修复放过")
    }

    @Test
    @DisplayName("泛型实参不匹配保持 ERROR 且说明使用点 ClassCastException")
    fun keepsErrorAndExplainsErasureConsequenceForGenericMismatch() {
        // 严重度裁决：运行期按擦除匹配、不读泛型（taboolib-ioc 全仓无泛型反射 API），注入这一步
        // 确实会成功；但注入的 Bean 与声明的类型实参不自洽，依赖类型实参的使用点会被编译器插入
        // checkcast → 运行期抛 ClassCastException。这属于「运行时失败」而非「静默降级」，
        // 与 README 的严重度对齐原则不冲突，因此刻意不降为 WARNING。
        val diagnostics = genericDiagnostics("com.example.Port", "com.example.Port<java.lang.Long>")
        val diagnostic = diagnostics.single { it.rule == "missing-bean" }
        assertEquals(DiagnosticSeverity.ERROR, diagnostic.severity, "泛型实参不匹配不是静默降级，不得降级为 WARNING")
        assertTrue(diagnostic.message.contains("ClassCastException"), "必须写明真实后果，否则这条 ERROR 无法操作")
        assertTrue(diagnostic.message.contains("擦除"), "必须点明运行期按擦除注入，否则用户会误以为可以忽略")
    }

    @Test
    @DisplayName("压根没有候选时的 missing-bean 不得套用泛型不匹配的说明")
    fun keepsPlainMessageWhenNoCandidateExistsAtAll() {
        // 两种失败的排查方向完全不同：一个是「泛型对不上」，一个是「没有候选」。
        val diagnostics = genericDiagnostics("com.example.Port", "com.example.Port<java.lang.Long>", emptyBeans = true)
        val diagnostic = diagnostics.single { it.rule == "missing-bean" }
        assertTrue(!diagnostic.message.contains("ClassCastException"), "无候选时不得套用泛型不匹配的说明")
    }

    @Test
    @DisplayName("不可赋值的普通类型依赖继续阻断")
    fun rejectsNonAssignableConcreteDependency() {
        val diagnostics = genericDiagnostics("com.example.Other", "com.example.Other")
        assertTrue(diagnostics.any { it.rule == "missing-bean" }, "普通类型仍须满足类型可赋值关系")
    }

    @Test
    @DisplayName("缺少组件的普通类型依赖继续阻断")
    fun rejectsConcreteDependencyWithoutBean() {
        val diagnostics = genericDiagnostics("com.example.Impl", "com.example.Impl", emptyBeans = true)
        assertTrue(diagnostics.any { it.rule == "missing-bean" }, "无候选组件不能被普通类型修复放过")
    }

    // ==================== 泛型校验口径（星投影 / 通配符边界 / 嵌套实参） ====================

    /**
     * 运行期只用擦除类型做 `Class.isAssignableFrom`，从不读泛型信息，
     * 因此 `Port<?>`（Kotlin 星投影 `Port<*>` 经反射即为 `?`）没有具体实参，
     * 必须与裸类型同样放行；否则会与候选的 `Port<String>` 判不等而误报 ERROR。
     */
    @Test
    @DisplayName("无界通配符依赖（星投影 Port<*>）必须放行")
    fun acceptsUnboundedWildcardDependency() {
        val diagnostics = genericDiagnostics("com.example.Port", "com.example.Port<?>")
        assertFalse(
            diagnostics.any { it.rule == "missing-bean" },
            "Port<?> 没有具体实参，运行期按擦除类型注入必然成功，静态不得误报 missing-bean",
        )
    }

    /**
     * 带边界的通配符不属于「无具体实参」：它携带必须参与比较的边界信息，
     * 这里按采集侧 normalizeTypeName 的口径退化为边界类型后比较。
     */
    @Test
    @DisplayName("带边界通配符按边界类型比较：边界一致放行")
    fun comparesBoundedWildcardDependencyByBound() {
        val extendsDiagnostics = genericDiagnostics("com.example.Port", "com.example.Port<? extends java.lang.String>")
        assertFalse(
            extendsDiagnostics.any { it.rule == "missing-bean" },
            "Port<? extends String> 的边界与候选实参一致，应放行",
        )
        val superDiagnostics = genericDiagnostics("com.example.Port", "com.example.Port<? super java.lang.String>")
        assertFalse(
            superDiagnostics.any { it.rule == "missing-bean" },
            "Port<? super String> 的边界与候选实参一致，应放行",
        )
    }

    @Test
    @DisplayName("带边界通配符的边界不匹配时仍然阻断")
    fun rejectsBoundedWildcardDependencyWithDifferentBound() {
        val diagnostics = genericDiagnostics("com.example.Port", "com.example.Port<? extends java.lang.CharSequence>")
        assertTrue(
            diagnostics.any { it.rule == "missing-bean" },
            "带边界的通配符不属于「无具体实参」，边界 CharSequence 与候选实参 String 不一致时应保持阻断",
        )
    }

    /**
     * 嵌套实参必须递归归一化：此前只归一化顶层实参、对嵌套实参调 canonical() 抹平类型参数，
     * 于是 `Map<String, Port<Integer>>` 与 `Map<String, Port<String>>` 被判相等（漏报），
     * 而同层 `Port<Long>` 与 `Port<String>` 却报 ERROR（口径矛盾）。
     */
    @Test
    @DisplayName("嵌套泛型实参不一致时必须阻断")
    fun rejectsNestedGenericArgumentMismatch() {
        val diagnostics = genericDiagnostics(
            type = "java.util.Map",
            genericType = "java.util.Map<java.lang.String,com.example.Port<java.lang.Long>>",
            candidateType = "com.example.NestedImpl",
            candidateInterfaces = listOf("java.util.Map"),
            candidateGenericSuperTypes = listOf("java.util.Map<java.lang.String,com.example.Port<java.lang.String>>"),
        )
        assertTrue(
            diagnostics.any { it.rule == "missing-bean" },
            "嵌套实参 Port<Long> 与候选 Port<String> 不一致，不得因内层被抹平而放行",
        )
    }

    @Test
    @DisplayName("嵌套泛型实参一致时继续通过")
    fun acceptsMatchingNestedGenericArgument() {
        val diagnostics = genericDiagnostics(
            type = "java.util.Map",
            genericType = "java.util.Map<java.lang.String,com.example.Port<java.lang.String>>",
            candidateType = "com.example.NestedImpl",
            candidateInterfaces = listOf("java.util.Map"),
            candidateGenericSuperTypes = listOf("java.util.Map<java.lang.String,com.example.Port<java.lang.String>>"),
        )
        assertFalse(
            diagnostics.any { it.rule == "missing-bean" },
            "嵌套实参完全一致时不得误报",
        )
    }

    // ==================== 泛型链路的端到端用例（真实编译 + 真实反射富化） ====================

    /**
     * 端到端：以上泛型用例都是手工拼字符串（直接给 dependencyGenericType 赋值），
     * 不经过采集层的真实反射富化。这里真实编译 Java 夹具，让
     * `BytecodeBeanIndexBuilder.build` 走一遍反射富化，再交给引擎判定：
     * 裸类型 `Port port` 与 `Port<?> wildcardPort` 放行、`Port<String>` 命中、
     * `Port<Long>` 仍被 missing-bean 拦下。
     */
    @Test
    @DisplayName("端到端：裸类型依赖放行、不兼容泛型实参仍阻断")
    fun matchesGenericsThroughRealReflectionEnrichment() {
        val classesDir = compileGenericFixtures(tempDirPath())
        val index = BytecodeBeanIndexBuilder.build(listOf(classesDir))

        // 先确认夹具确实经过了反射富化链路（否则本用例会退化成「手工拼串」测试）
        val wildcardInjection = index.injectionPointIndex.single {
            it.ownerClassName == "fixture.generic.GenericConsumer" && it.declarationName == "wildcardPort"
        }
        assertEquals(
            "fixture.generic.Port<?>",
            wildcardInjection.dependencyGenericType,
            "无界通配符字段的泛型串必须由反射富化得到，而不是 null",
        )
        val rawInjection = index.injectionPointIndex.single {
            it.ownerClassName == "fixture.generic.GenericConsumer" && it.declarationName == "rawPort"
        }
        assertEquals(
            "fixture.generic.Port",
            rawInjection.dependencyGenericType,
            "裸类型字段反射出的泛型串就是擦除类型本身，不带任何类型实参",
        )

        val missingBeans = StaticDiagnosisEngine.analyze(":test", index).diagnostics
            .filter { it.rule == "missing-bean" && it.ownerClassName == "fixture.generic.GenericConsumer" }
        assertEquals(
            listOf("longPort"),
            missingBeans.map { it.declarationName }.distinct(),
            "只有 Port<Long> 应被阻断：裸类型 Port 与 Port<?> 必须放行，Port<String> 命中 StringPort",
        )
        assertTrue(
            missingBeans.all { it.severity == DiagnosticSeverity.ERROR },
            "泛型实参不匹配仍是 ERROR",
        )
    }

    /**
     * 端到端：数组参数在反射侧是 `[Lfixture.generic.Port;`，在 ASM 侧是 `fixture.generic.Port[]`，
     * 两者直接比字符串会永远匹配不到构造器，参数泛型富化静默失效（dependencyGenericType = null）。
     */
    @Test
    @DisplayName("端到端：数组参数的泛型富化不再因类名形态差异失效")
    fun enrichesArrayParameterGenericTypeThroughRealReflection() {
        val classesDir = compileGenericFixtures(tempDirPath())
        val index = BytecodeBeanIndexBuilder.build(listOf(classesDir))

        val arrayParameter = index.injectionPointIndex.single {
            it.ownerClassName == "fixture.generic.ArrayParamHolder" && it.kind == InjectionPointKind.CONSTRUCTOR_PARAMETER
        }
        assertEquals("fixture.generic.Port[]", arrayParameter.dependencyType, "数组参数的擦除类型由 ASM 产出")
        assertEquals(
            "fixture.generic.Port<java.lang.String>[]",
            arrayParameter.dependencyGenericType,
            "数组参数必须匹配到构造器并富化出泛型串，否则泛型校验被静默跳过",
        )
    }

    private fun genericDiagnostics(
        type: String,
        genericType: String,
        emptyBeans: Boolean = false,
        candidateType: String = "com.example.Impl",
        candidateInterfaces: List<String> = listOf("com.example.Port"),
        candidateGenericSuperTypes: List<String> = listOf("com.example.Port<java.lang.String>"),
    ) =
        StaticDiagnosisEngine.analyze(
            ":test",
            index(
                beans = if (emptyBeans) emptyList() else listOf(bean("impl", candidateType)),
                injections = listOf(injection("com.example.Consumer", "service", type, lazy = false)
                    .copy(dependencyGenericType = genericType)),
                classIndex = listOf(
                    classEntry("com.example.Port", isInterface = true),
                    classEntry(candidateType).copy(
                        interfaceNames = candidateInterfaces,
                        genericSuperTypes = candidateGenericSuperTypes,
                    ),
                ),
            ),
        ).diagnostics

    /**
     * 真实编译一批 Java 夹具，供端到端用例走「反射富化 → 引擎判定」的完整链路。
     * 注解按简单名（Component / Inject）识别，与采集层规则一致，故这里用本地最小定义。
     */
    /**
     * 端到端：泛型父类型必须按**传递闭包**收集，并把每一层的实参逐层代入。
     *
     * `StringMulti extends BaseMulti<String>` 且 `BaseMulti<T> implements Multi<T>` 时，与注入点
     * 比对的那一项 `Multi<String>` 在任何一层的**直接**父类型里都不出现。只取直接父类型会把合法
     * 候选判成「泛型实参不一致」而踢出，报出阻断构建的假阳性 `missing-bean`。
     */
    @Test
    @DisplayName("端到端：二层参数化继承仍能命中泛型依赖")
    fun matchesGenericsAcrossParameterizedHierarchy() {
        val classesDir = compileGenericFixtures(tempDirPath())
        val index = BytecodeBeanIndexBuilder.build(listOf(classesDir))

        val missingBeans = StaticDiagnosisEngine.analyze(":test", index).diagnostics
            .filter { it.rule == "missing-bean" && it.ownerClassName == "fixture.generic.MultiConsumer" }
        assertEquals(
            emptyList(),
            missingBeans.map { it.declarationName },
            "StringMulti 的直接父类型是 BaseMulti<String>，其 Multi<String> 只能由传递闭包得到",
        )
    }

    private fun compileGenericFixtures(rootDir: java.nio.file.Path): java.nio.file.Path {
        val sources = linkedMapOf(
            "fixture/generic/Component.java" to """
                package fixture.generic;

                import java.lang.annotation.*;

                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.TYPE)
                public @interface Component {}
            """.trimIndent(),
            "fixture/generic/Inject.java" to """
                package fixture.generic;

                import java.lang.annotation.*;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.FIELD, ElementType.CONSTRUCTOR})
                public @interface Inject {}
            """.trimIndent(),
            "fixture/generic/Port.java" to """
                package fixture.generic;

                public interface Port<T> {}
            """.trimIndent(),
            "fixture/generic/StringPort.java" to """
                package fixture.generic;

                @Component
                public class StringPort implements Port<String> {}
            """.trimIndent(),
            "fixture/generic/GenericConsumer.java" to """
                package fixture.generic;

                @Component
                public class GenericConsumer {

                    @Inject
                    private Port rawPort;

                    @Inject
                    private Port<?> wildcardPort;

                    @Inject
                    private Port<String> stringPort;

                    @Inject
                    private Port<Long> longPort;
                }
            """.trimIndent(),
            "fixture/generic/ArrayParamHolder.java" to """
                package fixture.generic;

                @Component
                public class ArrayParamHolder {

                    @Inject
                    public ArrayParamHolder(Port<String>[] ports) {}
                }
            """.trimIndent(),
            "fixture/generic/Multi.java" to """
                package fixture.generic;

                public interface Multi<T> {}
            """.trimIndent(),
            "fixture/generic/BaseMulti.java" to """
                package fixture.generic;

                public abstract class BaseMulti<T> implements Multi<T> {}
            """.trimIndent(),
            "fixture/generic/StringMulti.java" to """
                package fixture.generic;

                @Component
                public class StringMulti extends BaseMulti<String> {}
            """.trimIndent(),
            "fixture/generic/MultiConsumer.java" to """
                package fixture.generic;

                @Component
                public class MultiConsumer {

                    @Inject
                    private Multi<String> multi;
                }
            """.trimIndent(),
        )

        val sourceDir = rootDir.resolve("src")
        val classesDir = rootDir.resolve("classes")
        sourceDir.createDirectories()
        classesDir.createDirectories()
        sources.forEach { (relativePath, content) ->
            val file = sourceDir.resolve(relativePath)
            file.parent.createDirectories()
            file.writeText(content + System.lineSeparator())
        }

        val compiler = ToolProvider.getSystemJavaCompiler()
        assertNotNull(compiler, "需要 JDK 提供的 Java 编译器")
        val fileManager = compiler.getStandardFileManager(null, null, Charsets.UTF_8)
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val javaFiles = Files.walk(sourceDir).use { stream ->
            stream.filter { it.toString().endsWith(".java") }.map { it.toFile() }.toList()
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
            "generic fixture 编译失败: " + diagnostics.diagnostics.joinToString("; ") { it.toString() },
        )
        return classesDir
    }

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
