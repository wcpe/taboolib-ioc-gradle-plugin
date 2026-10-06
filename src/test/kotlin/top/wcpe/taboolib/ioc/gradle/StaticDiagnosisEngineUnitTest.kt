package top.wcpe.taboolib.ioc.gradle

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.Opcodes
import top.wcpe.taboolib.ioc.gradle.analysis.*
import top.wcpe.taboolib.ioc.gradle.weaving.*

class StaticDiagnosisEngineUnitTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun evaluatesEnabledConditionsAndGenericCandidatesWithoutFalseWarnings() {
        val classesDir = StaticDiagnosisFixtureSources.compileJavaSources(tempDir)
        val index = BytecodeBeanIndexBuilder.build(listOf(classesDir), listOf(tempDir.resolve("src")))
        val report = StaticDiagnosisEngine.analyze(
            projectPath = ":fixture",
            index = index,
            projectProperties = mapOf("feature.enabled" to "on"),
        )

        assertFalse(report.diagnostics.any { it.ownerClassName.endsWith("EnabledConditionalConsumer") })
        assertFalse(report.diagnostics.any { it.ownerClassName.endsWith("MissingClassConditionalConsumer") })
        assertFalse(report.diagnostics.any { it.ownerClassName.endsWith("BeanConditionalConsumer") })
        assertFalse(report.diagnostics.any { it.ownerClassName.endsWith("GenericStringConsumer") })
        assertFalse(report.diagnostics.any { it.ownerClassName.endsWith(".ComponentConsumer") })
        assertFalse(report.diagnostics.any { it.ownerClassName.endsWith(".InitializedComponentConsumer") })
        assertFalse(report.diagnostics.any { it.ownerClassName.endsWith(".ManualAssignedComponentConsumer") })
        assertFalse(report.diagnostics.any { it.ownerClassName.endsWith(".KotlinObjectInitializedComponentConsumer") })
        assertFalse(report.diagnostics.any { it.ownerClassName.endsWith(".KotlinObjectLikeConsumer") })
    }

    @Test
    fun reportsMissingInjectAnnotationForReferencedComponentFields() {
        val classesDir = StaticDiagnosisFixtureSources.compileJavaSources(tempDir.resolve("missing-inject"))
        val index = BytecodeBeanIndexBuilder.build(listOf(classesDir), listOf(tempDir.resolve("missing-inject/src")))
        val report = StaticDiagnosisEngine.analyze(
            projectPath = ":fixture",
            index = index,
            projectProperties = mapOf("feature.enabled" to "on"),
        )

        val fieldReference = report.diagnostics.single {
            it.ownerClassName.endsWith("MissingInjectComponentConsumer")
        }
        assertEquals(DiagnosticSeverity.ERROR, fieldReference.severity)
        assertEquals("missing-inject-annotation", fieldReference.rule)
        assertTrue(fieldReference.candidateBeans.contains("componentService"))

        val kotlinObjectReference = report.diagnostics.single {
            it.ownerClassName.endsWith("KotlinObjectMissingInjectConsumer")
        }
        assertEquals(DiagnosticSeverity.ERROR, kotlinObjectReference.severity)
        assertEquals("missing-inject-annotation", kotlinObjectReference.rule)
        assertTrue(kotlinObjectReference.candidateBeans.contains("componentService"))
    }

    @Test
    fun treatsDisabledConditionalCandidatesAsMissingBeanErrors() {
        val classesDir = StaticDiagnosisFixtureSources.compileJavaSources(tempDir.resolve("disabled-conditions"))
        val index = BytecodeBeanIndexBuilder.build(listOf(classesDir), listOf(tempDir.resolve("disabled-conditions/src")))
        val report = StaticDiagnosisEngine.analyze(
            projectPath = ":fixture",
            index = index,
            projectProperties = mapOf("feature.enabled" to "on"),
        )

        val conditionalOnly = report.diagnostics.single {
            it.ownerClassName.endsWith("ConditionalOnlyConsumer")
        }
        assertEquals(DiagnosticSeverity.ERROR, conditionalOnly.severity)
        assertEquals("missing-bean", conditionalOnly.rule)

        val unknownConditional = report.diagnostics.single {
            it.ownerClassName.endsWith("UnknownConditionalConsumer")
        }
        assertEquals(DiagnosticSeverity.WARNING, unknownConditional.severity)
        assertEquals("conditional-bean-only", unknownConditional.rule)
        assertTrue(unknownConditional.candidateBeans.contains("unknownConditionalService"))
    }

    @Test
    fun detectsCircularDependenciesInAnalysis() {
        val beanA = makeBean("beanA", "com.example.A")
        val beanB = makeBean("beanB", "com.example.B")
        val ipAtoB = makeInjectionPoint("com.example.A", "beanB", "com.example.B", InjectionPointKind.CONSTRUCTOR_PARAMETER)
        val ipBtoA = makeInjectionPoint("com.example.B", "beanA", "com.example.A", InjectionPointKind.CONSTRUCTOR_PARAMETER)

        val index = BytecodeAnalysisIndex(
            classIndex = emptyList(),
            beanIndex = listOf(beanA, beanB),
            injectionPointIndex = listOf(ipAtoB, ipBtoA),
            missingInjectCandidateIndex = emptyList(),
            componentBeanTypes = emptyList(),
            componentScans = emptyList(),
        )
        val report = StaticDiagnosisEngine.analyze(":test", index)
        val cycleDiag = report.diagnostics.filter { it.rule == "circular-dependency-detected" }

        assertEquals(1, cycleDiag.size)
        assertEquals(DiagnosticSeverity.ERROR, cycleDiag.first().severity)
        assertTrue(cycleDiag.first().candidateBeans.containsAll(listOf("beanA", "beanB")))
    }

    @Test
    fun analyzesRefreshScopeResourceManagement() {
        val bean = makeBean("dbService", "com.example.DbService", scope = "refresh")
        val classEntry = ClassIndexEntry(
            className = "com.example.DbService",
            packageName = "com.example",
            sourceFile = null,
            superClassName = null,
            interfaceNames = emptyList(),
            fields = listOf(FieldInfo("conn", "java.sql.Connection", "Ljava/sql/Connection;")),
        )
        val index = BytecodeAnalysisIndex(
            classIndex = listOf(classEntry),
            beanIndex = listOf(bean),
            injectionPointIndex = emptyList(),
            missingInjectCandidateIndex = emptyList(),
            componentBeanTypes = emptyList(),
            componentScans = emptyList(),
        )
        val report = StaticDiagnosisEngine.analyze(":test", index)
        val diag = report.diagnostics.single { it.rule == "refresh-scope-missing-predestroy" }

        assertEquals(DiagnosticSeverity.WARNING, diag.severity)
        assertEquals("com.example.DbService", diag.ownerClassName)
    }

    @Test
    fun providesThreadScopeUsageHints() {
        val bean = makeBean("reqCtx", "com.example.RequestContext", scope = "thread")
        val index = BytecodeAnalysisIndex(
            classIndex = emptyList(),
            beanIndex = listOf(bean),
            injectionPointIndex = emptyList(),
            missingInjectCandidateIndex = emptyList(),
            componentBeanTypes = emptyList(),
            componentScans = emptyList(),
        )
        val report = StaticDiagnosisEngine.analyze(":test", index)
        val diag = report.diagnostics.single { it.rule == "thread-scope-usage-warning" }

        assertEquals(DiagnosticSeverity.INFO, diag.severity)
        assertEquals("com.example.RequestContext", diag.ownerClassName)
    }


    @Test
    fun detectsMultiplePrimaryBeans() {
        val beanA = makeBean("beanA", "com.example.ServiceA", primary = true)
        val beanB = makeBean("beanB", "com.example.ServiceA", primary = true)
        val ip = makeInjectionPoint("com.example.Consumer", "svc", "com.example.ServiceA", InjectionPointKind.FIELD)
        val index = BytecodeAnalysisIndex(
            classIndex = emptyList(),
            beanIndex = listOf(beanA, beanB),
            injectionPointIndex = listOf(ip),
            missingInjectCandidateIndex = emptyList(),
            componentBeanTypes = emptyList(),
            componentScans = emptyList(),
        )
        val report = StaticDiagnosisEngine.analyze(":test", index)
        val diag = report.diagnostics.single { it.rule == "multiple-primary-beans" }
        assertEquals(DiagnosticSeverity.ERROR, diag.severity)
        assertTrue(diag.candidateBeans.containsAll(listOf("beanA", "beanB")))
    }

    @Test
    fun detectsMultipleCandidatesWithoutQualifier() {
        val beanA = makeBean("beanA", "com.example.ServiceA")
        val beanB = makeBean("beanB", "com.example.ServiceA")
        val ip = makeInjectionPoint("com.example.Consumer", "svc", "com.example.ServiceA", InjectionPointKind.FIELD)
        val index = BytecodeAnalysisIndex(
            classIndex = emptyList(),
            beanIndex = listOf(beanA, beanB),
            injectionPointIndex = listOf(ip),
            missingInjectCandidateIndex = emptyList(),
            componentBeanTypes = emptyList(),
            componentScans = emptyList(),
        )
        val report = StaticDiagnosisEngine.analyze(":test", index)
        val diag = report.diagnostics.single { it.rule == "multiple-candidates-unqualified" }
        assertEquals(DiagnosticSeverity.WARNING, diag.severity)
        assertTrue(diag.candidateBeans.containsAll(listOf("beanA", "beanB")))
    }

    @Test
    fun detectsNamedBeanNotFound() {
        val ip = makeInjectionPoint("com.example.Consumer", "svc", "com.example.ServiceA", InjectionPointKind.FIELD, qualifierName = "nonexistent")
        val index = BytecodeAnalysisIndex(
            classIndex = emptyList(),
            beanIndex = emptyList(),
            injectionPointIndex = listOf(ip),
            missingInjectCandidateIndex = emptyList(),
            componentBeanTypes = emptyList(),
            componentScans = emptyList(),
        )
        val report = StaticDiagnosisEngine.analyze(":test", index)
        val diag = report.diagnostics.single { it.rule == "named-bean-not-found" }
        assertEquals(DiagnosticSeverity.ERROR, diag.severity)
    }

    @Test
    fun detectsNamedBeanTypeMismatch() {
        val bean = makeBean("myBean", "com.example.OtherService")
        val ip = makeInjectionPoint("com.example.Consumer", "svc", "com.example.ServiceA", InjectionPointKind.FIELD, qualifierName = "myBean")
        val index = BytecodeAnalysisIndex(
            classIndex = emptyList(),
            beanIndex = listOf(bean),
            injectionPointIndex = listOf(ip),
            missingInjectCandidateIndex = emptyList(),
            componentBeanTypes = emptyList(),
            componentScans = emptyList(),
        )
        val report = StaticDiagnosisEngine.analyze(":test", index)
        val diag = report.diagnostics.single { it.rule == "named-bean-type-mismatch" }
        assertEquals(DiagnosticSeverity.ERROR, diag.severity)
        assertTrue(diag.candidateBeans.contains("myBean"))
    }

    @Test
    fun reportsRuntimeManualBeanOnlyForOptionalMissingDependency() {
        val ip = makeInjectionPoint("com.example.Consumer", "svc", "com.example.ServiceA", InjectionPointKind.FIELD, required = false)
        val index = BytecodeAnalysisIndex(
            classIndex = emptyList(),
            beanIndex = emptyList(),
            injectionPointIndex = listOf(ip),
            missingInjectCandidateIndex = emptyList(),
            componentBeanTypes = emptyList(),
            componentScans = emptyList(),
        )
        val report = StaticDiagnosisEngine.analyze(":test", index)
        val diag = report.diagnostics.single { it.rule == "runtime-manual-bean-only" }
        assertEquals(DiagnosticSeverity.WARNING, diag.severity)
    }

    // ==================== 阶段①：诊断感知 weaving 开关 ====================

    /**
     * §2.4 第 2 条：同一份「无接口 Bean + 命中切面」fixture。
     * - `weaving=false` → `static-diagnosis.json` 含 `aop-target-not-proxied`（原文案，逐字）；
     * - `weaving=true` 且计划把该 bean 记为 `WOVEN` → 抑制（不含）。
     *
     * fixture 里 PrivateMethodService 的切点只命中 private（不产生 target-not-proxied），
     * StaticMethodService 实现了接口（同样不产生 target-not-proxied），
     * 因此 `aop-target-not-proxied` 的唯一来源就是 ConcreteService，可直接对 JSON 做全局断言。
     */
    @Test
    fun aopTargetNotProxiedSuppressedWhenWeavePlanContainsBean() {
        val classesDir = StaticDiagnosisFixtureSources.compileAopWeavingSources(tempDir.resolve("aop-weaving"))
        val index = BytecodeBeanIndexBuilder.build(listOf(classesDir), listOf(tempDir.resolve("aop-weaving/src")))
        val plan = buildWeavePlan(classesDir, index)

        // 计划事实：ConcreteService 被记为 WOVEN，且含命中方法 run。
        val decision = plan.classes.single { it.className == "fixture.aopweave.ConcreteService" }
        assertEquals(WeaveOutcome.WOVEN, decision.outcome, "计划应记 ConcreteService 为 WOVEN")
        assertTrue(decision.wovenMethods.any { it.methodName == "run" }, "计划应含被织入的 run 方法")

        val withoutWeaving = StaticDiagnosisEngine.analyze(projectPath = ":fixture", index = index, weaving = false)
        assertEquals(
            listOf("fixture.aopweave.ConcreteService"),
            withoutWeaving.diagnostics.filter { it.rule == "aop-target-not-proxied" }.map { it.ownerClassName },
            "weaving=false 时 ConcreteService 应报 aop-target-not-proxied",
        )
        val withoutJson = writeReport(withoutWeaving, "static-diagnosis-without-weaving.json")
        assertTrue(
            withoutJson.contains("\"rule\": \"aop-target-not-proxied\""),
            "weaving=false 的 static-diagnosis.json 应含 aop-target-not-proxied，实际规则：${rulesOf(withoutWeaving)}",
        )

        val withWeaving = StaticDiagnosisEngine.analyze(
            projectPath = ":fixture",
            index = index,
            weaving = true,
            weavePlan = plan,
        )
        assertTrue(
            withWeaving.diagnostics.none { it.rule == "aop-target-not-proxied" },
            "weaving=true 且命中通知全部在计划中时应抑制 aop-target-not-proxied，实际规则：${rulesOf(withWeaving)}",
        )
        val withJson = writeReport(withWeaving, "static-diagnosis-with-weaving.json")
        assertFalse(
            withJson.contains("\"rule\": \"aop-target-not-proxied\""),
            "weaving=true 的 static-diagnosis.json 不应含 aop-target-not-proxied，实际规则：${rulesOf(withWeaving)}",
        )
    }

    /**
     * §2.4 第 9 条：`weaving=true` 但**缺计划**（缺失 / 解析失败 / schema 不符）→ 保守不抑制，且不崩溃。
     * **绝不退回预测**。此时属**异常**分支，message 在基线后追加「织入链路未跑通」后缀。
     */
    @Test
    fun aopTargetNotProxiedNotSuppressedWhenWeavePlanMissing() {
        val classesDir = StaticDiagnosisFixtureSources.compileAopWeavingSources(tempDir.resolve("aop-weaving-noplan"))
        val index = BytecodeBeanIndexBuilder.build(listOf(classesDir), listOf(tempDir.resolve("aop-weaving-noplan/src")))

        val report = StaticDiagnosisEngine.analyze(projectPath = ":fixture", index = index, weaving = true, weavePlan = null)
        assertEquals(
            listOf("fixture.aopweave.ConcreteService"),
            report.diagnostics.filter { it.rule == "aop-target-not-proxied" }.map { it.ownerClassName },
            "无计划时必须保守不抑制（缺失计划不回退预测）",
        )
        val diag = report.diagnostics.single {
            it.rule == "aop-target-not-proxied" && it.ownerClassName == "fixture.aopweave.ConcreteService"
        }
        // 断言「后缀形式」包含（不用整串相等，避免日后文案微调就红）
        assertTrue(
            diag.message.contains("已开启编译期织入，但本次未产出可用的织入计划"),
            "weaving=true 无计划时应追加织入链路提示，实际：${diag.message}",
        )
        assertTrue(diag.message.contains("planTaboolibIocAop"), "提示应点名 planTaboolibIocAop 任务")
        assertTrue(diag.message.contains("aop-weave-plan.json"), "提示应点名计划文件")
    }

    /**
     * §2.4 第 7 条：`weaving=false` 的 `aop-target-not-proxied` message 必须与 HEAD **逐字一致**
     * （一个字符不许变），即**不得**追加任何织入相关后缀。
     */
    @Test
    fun aopTargetNotProxiedBaselineMessageUnchangedWhenWeavingDisabled() {
        val classesDir = StaticDiagnosisFixtureSources.compileAopWeavingSources(tempDir.resolve("aop-weaving-baseline"))
        val index = BytecodeBeanIndexBuilder.build(listOf(classesDir), listOf(tempDir.resolve("aop-weaving-baseline/src")))

        val report = StaticDiagnosisEngine.analyze(projectPath = ":fixture", index = index, weaving = false)
        val diag = report.diagnostics.single {
            it.rule == "aop-target-not-proxied" && it.ownerClassName == "fixture.aopweave.ConcreteService"
        }
        assertEquals(
            "有 1 个切面通知命中 Bean fixture.aopweave.ConcreteService，" +
                "但该类没有实现任何接口，运行时 JDK 动态代理将跳过包装，通知永不执行。" +
                "请为其抽取接口，或调整切点表达式。",
            diag.message,
            "weaving=false 的 message 必须与 HEAD 逐字一致（不得追加后缀）",
        )
    }

    /**
     * §2.4 第 3 条（高危反例锁定）：`weaving=true` 时，切点只命中 private / static 的
     * `aop-private-method-pointcut` / `aop-static-method-pointcut` **必须继续上报**。
     *
     * 根因：`AopWeaver.isWeavable` 显式要求 `ACC_PUBLIC` 且排除 `ACC_STATIC`，private/static
     * 方法在织入下依然不可被切面命中。写反会造成「开了织入反而漏报真 bug」。
     */
    @Test
    fun privateAndStaticPointcutRulesRemainWhenWeavingEnabled() {
        val classesDir = StaticDiagnosisFixtureSources.compileAopWeavingSources(tempDir.resolve("aop-weaving-static"))
        val index = BytecodeBeanIndexBuilder.build(listOf(classesDir), listOf(tempDir.resolve("aop-weaving-static/src")))

        val report = StaticDiagnosisEngine.analyze(projectPath = ":fixture", index = index, weaving = true)
        val privateRules = report.diagnostics.filter { it.rule == "aop-private-method-pointcut" }
        val staticRules = report.diagnostics.filter { it.rule == "aop-static-method-pointcut" }

        assertEquals(
            listOf("fixture.aopweave.PrivateMethodAspect"),
            privateRules.map { it.ownerClassName },
            "weaving=true 时 aop-private-method-pointcut 必须保留",
        )
        assertEquals(
            listOf("fixture.aopweave.StaticMethodAspect"),
            staticRules.map { it.ownerClassName },
            "weaving=true 时 aop-static-method-pointcut 必须保留",
        )

        val json = writeReport(report, "static-diagnosis-static-private.json")
        assertTrue(json.contains("\"rule\": \"aop-private-method-pointcut\""), "JSON 应含 aop-private-method-pointcut")
        assertTrue(json.contains("\"rule\": \"aop-static-method-pointcut\""), "JSON 应含 aop-static-method-pointcut")
    }

    /**
     * §2.3.5 原因 3：`weaving=true` 且计划把该类记为 `SKIPPED(NO_ELIGIBLE_MATCHING_METHOD)`
     * （此处仅命中 static 方法且无接口）→ 保留 WARNING，message 归因到「命中的方法不具备织入资格」。
     */
    @Test
    fun aopTargetNotProxiedRetainedForNonEligibleMethodWithPlan() {
        val index = BytecodeAnalysisIndex(
            classIndex = listOf(
                classEntry(
                    "com.example.StaticOnlyService",
                    methods = listOf(
                        CollectedMethodInfo("ping", access = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC),
                    ),
                ),
            ),
            beanIndex = listOf(makeBean("staticOnlyService", "com.example.StaticOnlyService")),
            injectionPointIndex = emptyList(),
            missingInjectCandidateIndex = emptyList(),
            componentBeanTypes = emptyList(),
            componentScans = emptyList(),
            aspectIndex = listOf(
                AspectDefinition(
                    aspectClassName = "com.example.StaticOnlyAspect",
                    packageName = "com.example",
                    sourceFile = null,
                    pointcutMethods = emptyMap(),
                    advices = listOf(
                        AspectAdviceDefinition("Before", "onPing", "execution(com.example.StaticOnlyService.ping)"),
                    ),
                ),
            ),
            valueFieldIndex = emptyList(),
        )
        val plan = WeavePlan(
            adviceCount = 1,
            classes = listOf(
                ClassWeaveDecision(
                    className = "com.example.StaticOnlyService",
                    outcome = WeaveOutcome.SKIPPED,
                    skipReason = WeaveSkipReason.NO_ELIGIBLE_MATCHING_METHOD,
                ),
            ),
        )

        val report = StaticDiagnosisEngine.analyze(projectPath = ":fixture", index = index, weaving = true, weavePlan = plan)
        val diag = report.diagnostics.single { it.rule == "aop-target-not-proxied" }
        assertEquals(DiagnosticSeverity.WARNING, diag.severity)
        assertEquals("com.example.StaticOnlyService", diag.ownerClassName)
        assertTrue(
            diag.message.contains("命中的方法不具备织入资格"),
            "保留 WARNING 时应归因到「无织入资格」，实际 message：${diag.message}",
        )
        assertEquals(listOf("com.example.StaticOnlyAspect#onPing"), diag.candidateBeans, "只列未生效的通知")
    }

    /**
     * 规则处置表：`aop-factory-bean-interface-return` 在 `weaving=true` 时**降级为 INFO**（保留提示，不阻断）。
     */
    @Test
    fun factoryBeanInterfaceReturnDowngradesToInfoWhenWeavingEnabled() {
        fun indexWithFactoryBean(): BytecodeAnalysisIndex = BytecodeAnalysisIndex(
            classIndex = listOf(
                classEntry(
                    "com.example.WorkerApi",
                    isInterface = true,
                    methods = listOf(CollectedMethodInfo("doWork", access = Opcodes.ACC_PUBLIC)),
                ),
                classEntry("com.example.AopConfig"),
            ),
            beanIndex = listOf(
                makeBean("worker", "com.example.AopConfig").copy(
                    kind = BeanKind.FACTORY_METHOD,
                    exposedType = "com.example.WorkerApi",
                    factoryHostIsConfiguration = true,
                ),
            ),
            injectionPointIndex = emptyList(),
            missingInjectCandidateIndex = emptyList(),
            componentBeanTypes = emptyList(),
            componentScans = emptyList(),
            aspectIndex = listOf(
                AspectDefinition(
                    aspectClassName = "com.example.WorkerAspect",
                    packageName = "com.example",
                    sourceFile = null,
                    pointcutMethods = emptyMap(),
                    advices = listOf(
                        AspectAdviceDefinition("Before", "onWorker", "execution(com.example.WorkerApi.doWork)"),
                    ),
                ),
            ),
            valueFieldIndex = emptyList(),
        )

        val withoutWeaving = StaticDiagnosisEngine.analyze(projectPath = ":fixture", index = indexWithFactoryBean(), weaving = false)
        assertEquals(
            DiagnosticSeverity.WARNING,
            withoutWeaving.diagnostics.single { it.rule == "aop-factory-bean-interface-return" }.severity,
        )

        val withWeaving = StaticDiagnosisEngine.analyze(projectPath = ":fixture", index = indexWithFactoryBean(), weaving = true)
        assertEquals(
            DiagnosticSeverity.INFO,
            withWeaving.diagnostics.single { it.rule == "aop-factory-bean-interface-return" }.severity,
        )
    }

    /**
     * 反向反例（锁死「织入资格被放宽成 `!isPrivate` 近似」的静默漏报）：
     * 无接口类 + **protected / 包内可见 / native** 方法被切点精确命中。
     *
     * 这些方法满足 `!isPrivate` 但**不满足 `ACC_PUBLIC`**（native 另被 `ACC_NATIVE` 排除），
     * 编译期织入不会碰它们 → `aop-target-not-proxied` 在 `weaving=true` 时**必须保留**，
     * 且 owner 精确等于对应 fixture 类（防止被别的规则/噪声吃掉）。
     */
    @Test
    fun aopTargetNotProxiedRetainedForNonPublicMethodsWhenWeavingEnabled() {
        val classesDir = StaticDiagnosisFixtureSources.compileAopWeavingIneligibleSources(tempDir.resolve("aop-ineligible"))
        val index = BytecodeBeanIndexBuilder.build(listOf(classesDir), listOf(tempDir.resolve("aop-ineligible/src")))
        val plan = buildWeavePlan(classesDir, index)

        val expectedOwners = setOf(
            "fixture.awineligible.ProtectedMethodService",
            "fixture.awineligible.PackagePrivateMethodService",
            "fixture.awineligible.NativeMethodService",
        )
        // 计划事实：三者均为 SKIPPED(NO_ELIGIBLE_MATCHING_METHOD)（非 public / native 不具备织入资格）。
        expectedOwners.forEach { owner ->
            val decision = plan.classes.single { it.className == owner }
            assertEquals(WeaveOutcome.SKIPPED, decision.outcome, "$owner 计划应为 SKIPPED")
            assertEquals(
                WeaveSkipReason.NO_ELIGIBLE_MATCHING_METHOD,
                decision.skipReason,
                "$owner 计划跳过原因应为 NO_ELIGIBLE_MATCHING_METHOD",
            )
        }

        val withWeaving = StaticDiagnosisEngine.analyze(
            projectPath = ":fixture",
            index = index,
            weaving = true,
            weavePlan = plan,
        )
        val retainedOwners = withWeaving.diagnostics
            .filter { it.rule == "aop-target-not-proxied" }
            .map { it.ownerClassName }
            .toSet()

        assertEquals(
            expectedOwners,
            retainedOwners,
            "weaving=true 时 protected/包内/native 方法命中的无接口类必须保留 aop-target-not-proxied",
        )

        val json = writeReport(withWeaving, "static-diagnosis-ineligible-weaving.json")
        assertTrue(
            json.contains("\"rule\": \"aop-target-not-proxied\""),
            "weaving=true 的 static-diagnosis.json 必须仍含 aop-target-not-proxied；实际规则：${rulesOf(withWeaving)}",
        )
        expectedOwners.forEach { owner ->
            assertTrue(json.contains("\"ownerClassName\": \"$owner\""), "JSON 应含 owner $owner")
        }
    }

    /**
     * 反向反例（abstract 方法）：`WeavingEligibility` 排除 `ACC_ABSTRACT`，
     * 故只命中 abstract 方法的无接口类在 `weaving=true` 时仍保留 `aop-target-not-proxied`。
     */
    @Test
    fun aopTargetNotProxiedRetainedForAbstractMethodWhenWeavingEnabled() {
        val index = BytecodeAnalysisIndex(
            classIndex = listOf(
                classEntry(
                    "com.example.AbstractService",
                    methods = listOf(
                        CollectedMethodInfo("pending", access = Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT),
                    ),
                ),
            ),
            beanIndex = listOf(makeBean("abstractService", "com.example.AbstractService")),
            injectionPointIndex = emptyList(),
            missingInjectCandidateIndex = emptyList(),
            componentBeanTypes = emptyList(),
            componentScans = emptyList(),
            aspectIndex = listOf(
                AspectDefinition(
                    aspectClassName = "com.example.AbstractAspect",
                    packageName = "com.example",
                    sourceFile = null,
                    pointcutMethods = emptyMap(),
                    advices = listOf(
                        AspectAdviceDefinition("Before", "onPending", "execution(com.example.AbstractService.pending)"),
                    ),
                ),
            ),
            valueFieldIndex = emptyList(),
        )
        val plan = WeavePlan(
            adviceCount = 1,
            classes = listOf(
                ClassWeaveDecision(
                    className = "com.example.AbstractService",
                    outcome = WeaveOutcome.SKIPPED,
                    skipReason = WeaveSkipReason.NO_ELIGIBLE_MATCHING_METHOD,
                ),
            ),
        )

        val report = StaticDiagnosisEngine.analyze(projectPath = ":fixture", index = index, weaving = true, weavePlan = plan)
        val diag = report.diagnostics.single { it.rule == "aop-target-not-proxied" }
        assertEquals(DiagnosticSeverity.WARNING, diag.severity)
        assertEquals("com.example.AbstractService", diag.ownerClassName)
        assertTrue(
            diag.message.contains("命中的方法不具备织入资格"),
            "保留 WARNING 时应归因到「无织入资格」，实际 message：${diag.message}",
        )
    }

    /**

     * §2.4 第 3 条（F1，静默漏报锁定）：`Child extends Parent`，`Parent.public inherited()`
     * 未被覆写，切点 `execution(fixture.aopfact.Child.inherited)`。
     *
     * 引擎只按被织类**自身声明**的方法名匹配 → 计划里 `Child` 为 `SKIPPED(NO_ELIGIBLE_MATCHING_METHOD)`；
     * `weaving=true` 时 `aop-target-not-proxied` **仍必须报**，owner 精确等于 `Child`，
     * 且 message 命中「声明在父类 … 而未在 … 中声明/覆写」。
     */
    @Test
    fun aopTargetNotProxiedNotSuppressedWhenPointcutMatchesInheritedMethodNotDeclaredInChild() {
        val classesDir = StaticDiagnosisFixtureSources.compileAopWeavingFactSources(tempDir.resolve("aop-fact"))
        val index = BytecodeBeanIndexBuilder.build(listOf(classesDir), listOf(tempDir.resolve("aop-fact/src")))
        val plan = buildWeavePlan(classesDir, index)

        val childDecision = plan.classes.single { it.className == "fixture.aopfact.Child" }
        assertEquals(WeaveOutcome.SKIPPED, childDecision.outcome, "Child 计划应为 SKIPPED")
        assertEquals(
            WeaveSkipReason.NO_ELIGIBLE_MATCHING_METHOD,
            childDecision.skipReason,
            "Child 跳过原因应为 NO_ELIGIBLE_MATCHING_METHOD",
        )

        val report = StaticDiagnosisEngine.analyze(
            projectPath = ":fixture",
            index = index,
            weaving = true,
            weavePlan = plan,
        )
        val diag = report.diagnostics.single {
            it.rule == "aop-target-not-proxied" && it.ownerClassName == "fixture.aopfact.Child"
        }
        assertEquals(DiagnosticSeverity.WARNING, diag.severity)
        assertTrue(
            diag.message.contains("声明在父类 fixture.aopfact.Parent 而未在 fixture.aopfact.Child 中声明/覆写"),
            "F1 message 应精确到「声明在父类」，实际：${diag.message}",
        )
        assertEquals(listOf("fixture.aopfact.InheritedAspect#beforeInherited"), diag.candidateBeans)
    }

    /**
     * §2.4 第 4 条（F2，静默漏报锁定）：类级与**方法级** `@NoAspect` 两个变体。
     *
     * 引擎织入阶段显式跳过 → 计划里为 `SKIPPED(NO_ASPECT_CLASS)`；
     * `weaving=true` 时 `aop-target-not-proxied` **仍必须报**，message 命中「@NoAspect…显式跳过」。
     */
    @Test
    fun aopTargetNotProxiedNotSuppressedWhenTargetClassHasNoAspect() {
        val classesDir = StaticDiagnosisFixtureSources.compileAopWeavingFactSources(tempDir.resolve("aop-fact-noaspect"))
        val index = BytecodeBeanIndexBuilder.build(listOf(classesDir), listOf(tempDir.resolve("aop-fact-noaspect/src")))
        val plan = buildWeavePlan(classesDir, index)

        listOf("fixture.aopfact.NoAspectSvc", "fixture.aopfact.MethodLevelNoAspectSvc").forEach { owner ->
            val decision = plan.classes.single { it.className == owner }
            assertEquals(WeaveOutcome.SKIPPED, decision.outcome, "$owner 计划应为 SKIPPED")
            assertEquals(WeaveSkipReason.NO_ASPECT_CLASS, decision.skipReason, "$owner 跳过原因应为 NO_ASPECT_CLASS")
        }

        val report = StaticDiagnosisEngine.analyze(
            projectPath = ":fixture",
            index = index,
            weaving = true,
            weavePlan = plan,
        )
        listOf("fixture.aopfact.NoAspectSvc", "fixture.aopfact.MethodLevelNoAspectSvc").forEach { owner ->
            val diag = report.diagnostics.single { it.rule == "aop-target-not-proxied" && it.ownerClassName == owner }
            assertEquals(DiagnosticSeverity.WARNING, diag.severity, "$owner 应保留 WARNING")
            assertTrue(diag.message.contains("@NoAspect"), "$owner message 应命中 @NoAspect，实际：${diag.message}")
        }
    }

    /**
     * §2.4 第 5 条（ALL 语义 / 部分织入）：一个 bean 命中 2 条通知，计划只织入其中 1 条 →
     * `unrealized` 非空 → **仍报**，且 `candidateBeans` **只列未生效的那条**。
     */
    @Test
    fun aopTargetNotProxiedReportsOnlyUnrealizedAdvices() {
        val index = BytecodeAnalysisIndex(
            classIndex = listOf(
                classEntry(
                    "com.example.PartialService",
                    methods = listOf(CollectedMethodInfo("run", access = Opcodes.ACC_PUBLIC)),
                ),
            ),
            beanIndex = listOf(makeBean("partialService", "com.example.PartialService")),
            injectionPointIndex = emptyList(),
            missingInjectCandidateIndex = emptyList(),
            componentBeanTypes = emptyList(),
            componentScans = emptyList(),
            aspectIndex = listOf(
                AspectDefinition(
                    aspectClassName = "com.example.AspectA",
                    packageName = "com.example",
                    sourceFile = null,
                    pointcutMethods = emptyMap(),
                    advices = listOf(AspectAdviceDefinition("Before", "a", "execution(com.example.PartialService.run)")),
                ),
                AspectDefinition(
                    aspectClassName = "com.example.AspectB",
                    packageName = "com.example",
                    sourceFile = null,
                    pointcutMethods = emptyMap(),
                    advices = listOf(AspectAdviceDefinition("Before", "b", "execution(com.example.PartialService.run)")),
                ),
            ),
            valueFieldIndex = emptyList(),
        )
        // 计划只织入 AspectA#a → AspectB#b 未生效。
        val plan = WeavePlan(
            adviceCount = 2,
            classes = listOf(
                ClassWeaveDecision(
                    className = "com.example.PartialService",
                    outcome = WeaveOutcome.WOVEN,
                    wovenMethods = listOf(
                        WovenMethod("run", "()Ljava/lang/String;", listOf("com.example.AspectA#a")),
                    ),
                ),
            ),
        )

        val report = StaticDiagnosisEngine.analyze(projectPath = ":fixture", index = index, weaving = true, weavePlan = plan)
        val diag = report.diagnostics.single { it.rule == "aop-target-not-proxied" }
        assertEquals(DiagnosticSeverity.WARNING, diag.severity)
        assertEquals(
            listOf("com.example.AspectB#b"),
            diag.candidateBeans,
            "ALL 语义下只列真正未生效的通知",
        )
        assertTrue(diag.message.contains("未能接管其中 1 个通知"), "message 应报未接管数，实际：${diag.message}")
    }

    /**
     * §2.4 第 12 条（诊断对残渣免疫、可复现）：同一工程目录，把 `ConcreteService` 就地织入后
     * 重建索引（模拟 build2 编译 UP-TO-DATE 读到脏字节）→ `aop-target-not-proxied` 结论
     * 与「从未织入的干净构建」**逐字段一致**（`WovenTarget` 不出现在任何规则结论里）。
     */
    @Test
    fun diagnosisInvariantUnderWovenByteResidue() {
        val cleanDir = StaticDiagnosisFixtureSources.compileAopWeavingSources(tempDir.resolve("residue-clean"))
        val cleanIndex = BytecodeBeanIndexBuilder.build(listOf(cleanDir), listOf(tempDir.resolve("residue-clean/src")))
        val cleanReport = StaticDiagnosisEngine.analyze(projectPath = ":fixture", index = cleanIndex, weaving = false)

        // 复制编译产物到独立目录，就地织入 ConcreteService（模拟残渣）。
        val wovenDir = tempDir.resolve("residue-woven/classes")
        copyDirectory(cleanDir, wovenDir)
        val advices = AopWeavePlanner.resolve(cleanIndex.aspectIndex)
        val concreteClass = wovenDir.resolve("fixture/aopweave/ConcreteService.class")
        val outcome = AopWeaver.weave(Files.readAllBytes(concreteClass), advices)
        assertTrue(outcome.bytes != null, "ConcreteService 应被织入（构造残渣）")
        Files.write(concreteClass, outcome.bytes!!)

        val wovenIndex = BytecodeBeanIndexBuilder.build(listOf(wovenDir), listOf(tempDir.resolve("residue-clean/src")))
        val wovenReport = StaticDiagnosisEngine.analyze(projectPath = ":fixture", index = wovenIndex, weaving = false)

        fun owners(report: StaticAnalysisReport) =
            report.diagnostics.filter { it.rule == "aop-target-not-proxied" }.map { it.ownerClassName }.sorted()

        assertEquals(
            owners(cleanReport),
            owners(wovenReport),
            "诊断结论必须与「字节码是否已织入」无关（残渣免疫）",
        )
        // 残渣不得泄漏进任何规则结论（owner / dependency / candidate 都不应出现 WovenTarget）。
        val wovenJson = writeReport(wovenReport, "static-diagnosis-residue.json")
        assertFalse(
            wovenJson.contains("WovenTarget"),
            "WovenTarget 不应出现在任何规则结论里",
        )
    }

    private fun buildWeavePlan(classesDir: Path, index: BytecodeAnalysisIndex): WeavePlan {
        val advices = AopWeavePlanner.resolve(index.aspectIndex)
        val decisions = Files.walk(classesDir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }
                .map { file -> AopWeaver.plan(Files.readAllBytes(file), advices) }
                .filter { it != null }
                .map { it!! }
                .toList()
        }
        return WeavePlan(adviceCount = advices.size, classes = decisions)
    }

    private fun copyDirectory(source: Path, target: Path) {
        Files.walk(source).use { stream ->
            stream.forEach { sourcePath ->
                val targetPath = target.resolve(source.relativize(sourcePath).toString())
                if (Files.isDirectory(sourcePath)) {
                    Files.createDirectories(targetPath)
                } else {
                    targetPath.parent?.let { Files.createDirectories(it) }
                    Files.copy(sourcePath, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun classEntry(
        className: String,
        isInterface: Boolean = false,
        methods: List<CollectedMethodInfo> = emptyList(),
    ) = ClassIndexEntry(
        className = className,
        packageName = className.substringBeforeLast('.'),
        sourceFile = null,
        superClassName = null,
        interfaceNames = emptyList(),
        isInterface = isInterface,
        methods = methods,
    )

    private fun writeReport(report: StaticAnalysisReport, fileName: String): String {
        val file = tempDir.resolve(fileName)
        StaticAnalysisJsonWriter.write(report, file)
        return Files.readString(file)
    }

    private fun rulesOf(report: StaticAnalysisReport): List<String> =
        report.diagnostics.map { it.rule }.distinct().sorted()

    private fun makeBean(beanName: String, className: String, primary: Boolean = false, scope: String? = null) = BeanDefinition(
        ownerClassName = className,
        declarationName = beanName,
        beanName = beanName,
        exposedType = className,
        packageName = className.substringBeforeLast('.'),
        sourceFile = null,
        kind = BeanKind.CLASS,
        exposedGenericType = null,
        primary = primary,
        order = null,
        conditionalAnnotations = emptyList(),
        conditions = emptyList(),
        scope = scope,
    )

    private fun makeInjectionPoint(
        ownerClass: String,
        declName: String,
        depType: String,
        kind: InjectionPointKind,
        qualifierName: String? = null,
        required: Boolean = true,
    ) = InjectionPointDefinition(
        ownerClassName = ownerClass,
        declarationName = declName,
        dependencyType = depType,
        dependencyGenericType = null,
        ownerPackage = ownerClass.substringBeforeLast('.'),
        sourceFile = null,
        sourcePath = null,
        sourceLine = null,
        sourceColumn = null,
        kind = kind,
        parameterIndex = null,
        qualifierName = qualifierName,
        required = required,
    )
    /**
     * 反方向锁定：切点写**父类型**（`execution(fixture.aopfact.Parent.inherited)`），Bean 是子类
     * `Child extends Parent` 且不覆写 `inherited()`。
     *
     * 子类自身没有可织入的声明方法，计划里要么没有它、要么 SKIPPED；而父类那边织入出来的转发体会被
     * 子类实例分派到，是否命中通知取决于运行期匹配口径（该口径在本仓库外，无法判定）。按保守方向
     * 必须上报，不得因为「类名对不上 classPattern」就在 matched 阶段被丢掉而全程静默。
     */
    @Test
    fun aopTargetNotProxiedReportedWhenPointcutTargetsParentOfBean() {
        val classesDir = StaticDiagnosisFixtureSources
            .compileAopWeavingParentPointcutSources(tempDir.resolve("aop-parent"))
        val index = BytecodeBeanIndexBuilder.build(listOf(classesDir), listOf(tempDir.resolve("aop-parent/src")))
        val plan = buildWeavePlan(classesDir, index)

        val report = StaticDiagnosisEngine.analyze(
            projectPath = ":fixture",
            index = index,
            weaving = true,
            weavePlan = plan,
        )
        val diag = report.diagnostics.single {
            it.rule == "aop-target-not-proxied" && it.ownerClassName == "fixture.aopfact.Child"
        }
        assertEquals(DiagnosticSeverity.WARNING, diag.severity)
    }
}