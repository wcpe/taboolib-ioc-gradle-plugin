package top.wcpe.taboolib.ioc.gradle

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.io.TempDir
import org.gradle.testkit.runner.TaskOutcome

class TaboolibIocPluginFunctionalTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun registersDiagnosticTasksWithoutTaboolib() {
        val project = FunctionalTestProject(tempDir.resolve("smoke")).writeFixture(
            FixtureOptions(
                applyMockTaboolib = false,
                includeIocLibrary = false,
                rootGroup = "com.example.smoke",
                consumerGroup = "com.example.smoke.consumer",
                localProjectPath = null,
            ),
        )

        val result = project.build(":consumer:tasks", "--all")
        assertContains(result.output, "analyzeTaboolibIocBeans")
        assertContains(result.output, "taboolibIocDoctor")
        assertContains(result.output, "verifyTaboolibIoc")
    }

    @Test
    fun doctorTaskReportsSuccessfulResolutionAndTakeoverState() {
        val project = FunctionalTestProject(tempDir.resolve("doctor-success")).writeFixture(
            FixtureOptions(),
        )

        val result = project.build(":consumer:taboolibIocDoctor")

        assertContains(result.output, "[taboolibIocDoctor] dependency = project(:ioc-lib)")
        assertContains(result.output, "[taboolibIocDoctor] configured = true")
        assertContains(result.output, "top.wcpe.taboolib.ioc -> com.example.root.ioc")
    }

    @Test
    fun doctorTaskReportsResolutionFailureWithoutCrashing() {
        val project = FunctionalTestProject(tempDir.resolve("doctor-failure")).writeFixture(
            FixtureOptions(
                rootGroup = null,
                consumerGroup = null,
                localProjectPath = null,
                includeIocLibrary = false,
            ),
        )

        val result = project.build(":consumer:taboolibIocDoctor")

        assertContains(result.output, "[taboolibIocDoctor] resolution = FAILED")
        assertContains(result.output, "[taboolibIocDoctor] configured = false")
    }

    @Test
    fun failsBuildWhenTaboolibPluginMissing() {
        val project = FunctionalTestProject(tempDir.resolve("missing-taboolib")).writeFixture(
            FixtureOptions(
                applyMockTaboolib = false,
                includeIocLibrary = false,
                rootGroup = "com.example.missing",
                consumerGroup = "com.example.missing.consumer",
                localProjectPath = null,
            ),
        )

        val result = project.build(":consumer:jar", expectFailure = true)
        assertContains(result.output, "当前工程未应用 io.izzel.taboolib")
    }

    @Test
    fun relocatesEmbeddedIocLibraryUsingExplicitTargetPackage() {
        val project = FunctionalTestProject(tempDir.resolve("explicit-target")).writeFixture(
            FixtureOptions(explicitTargetPackage = "com.example.explicit"),
        )

        project.build(":consumer:build")
        val entries = project.consumerJarEntries()
        assertTrue(entries.contains("com/example/explicit/ioc/SampleService.class"))
        assertFalse(entries.contains("top/wcpe/taboolib/ioc/SampleService.class"))
    }

    @Test
    fun fallsBackToTaboolibEnvGroupGradleProperty() {
        val project = FunctionalTestProject(tempDir.resolve("env-group-fallback")).writeFixture(
            FixtureOptions(
                explicitTargetPackage = null,
                rootGroup = null,
                consumerGroup = null,
                gradleProperties = mapOf("taboolib.env.group" to "com.example.fromprop"),
            ),
        )

        project.build(":consumer:build")
        val entries = project.consumerJarEntries()
        assertTrue(entries.contains("com/example/fromprop/ioc/SampleService.class"))
    }

    @Test
    fun fallsBackToProjectGroupForSubprojectBuild() {
        val project = FunctionalTestProject(tempDir.resolve("project-group-fallback")).writeFixture(
            FixtureOptions(
                explicitTargetPackage = null,
                rootGroup = "com.example.inherited",
                consumerGroup = null,
            ),
        )

        project.build(":consumer:build")
        val entries = project.consumerJarEntries()
        assertTrue(entries.contains("com/example/inherited/ioc/SampleService.class"))
    }

    @Test
    fun analyzeTaskHasNoWeavePlanInputWhenWeavingDisabled() {
        val project = FunctionalTestProject(tempDir.resolve("weaving-disabled-no-plan")).writeFixture(
            FixtureOptions(
                applyMockTaboolib = false,
                autoTakeover = false,
                includeIocLibrary = false,
                localProjectPath = null,
            ),
        )

        val result = project.build(":consumer:analyzeTaboolibIocBeans")
        // weaving=false 时不建立 plan 依赖边（plan 任务 SKIPPED，且不产计划文件）。
        assertFalse(result.output.contains(":consumer:planTaboolibIocAop"))
    }

    /**
     * §2.4 第 2/7 条（端到端）:`-Ptaboolib.ioc.weaving=true` 时建立 `plan → analyze` 顺序，
     * 且 `planTaboolibIocAop` 产出 `aop-weave-plan.json`（诊断抑制判据的唯一事实来源）。
     */
    @Test
    fun weavingEnabledProducesPlanBeforeAnalysis() {
        val project = FunctionalTestProject(tempDir.resolve("weaving-plan-order")).writeFixture(
            FixtureOptions(
                applyMockTaboolib = false,
                autoTakeover = false,
                includeIocLibrary = false,
                localProjectPath = null,
            ),
        )

        val result = project.build(":consumer:analyzeTaboolibIocBeans", "-Ptaboolib.ioc.weaving=true")
        val plan = project.readRelativeFile("consumer/build/taboolib-ioc/aop-weave-plan.json")

        assertContains(result.output, "[taboolibIocPlanAop]")
        assertContains(plan, "schemaVersion")
        val planIndex = result.output.indexOf(":consumer:planTaboolibIocAop")
        val analyzeIndex = result.output.indexOf(":consumer:analyzeTaboolibIocBeans")
        assertTrue(planIndex >= 0 && planIndex < analyzeIndex, "plan 必须先于 analyze 执行")
    }

    /**
     * 消费方在**自己的** `afterEvaluate {}` 里打开 weaving 时，「装配织入计划」必须与 `weaving` 的最终值
     * 对齐：否则计划任务根本不进任务图，analyze 却按 `weaving=true` 执行 —— 出现「有织入开关、没有织入计划」
     * 的静默不一致（诊断按保守语义不抑制，且没有任何提示）。
     */
    @Test
    fun weavingEnabledInsideConsumerAfterEvaluateStillProducesPlanBeforeAnalysis() {
        val project = FunctionalTestProject(tempDir.resolve("weaving-late-enable")).writeFixture(
            FixtureOptions(
                applyMockTaboolib = false,
                autoTakeover = false,
                includeIocLibrary = false,
                localProjectPath = null,
            ),
        )
        appendFixtureBuild(project, "consumer/build.gradle", """
            afterEvaluate {
                taboolibIoc {
                    weaving.set(true)
                }
            }
        """.trimIndent())

        val result = project.build(":consumer:analyzeTaboolibIocBeans")
        val plan = project.readRelativeFile("consumer/build/taboolib-ioc/aop-weave-plan.json")

        assertContains(result.output, "[taboolibIocPlanAop]")
        assertContains(plan, "schemaVersion")
        val planIndex = result.output.indexOf(":consumer:planTaboolibIocAop")
        val analyzeIndex = result.output.indexOf(":consumer:analyzeTaboolibIocBeans")
        assertTrue(planIndex >= 0 && planIndex < analyzeIndex, "plan 必须先于 analyze 执行")
    }

    /**
     * 计划文件必须按「允许缺失」的输入形态接收：`weaving=true` 时计划任务仍可能被 `-x` 排除，
     * 此时 analyze 应按「无计划」保守处理，而不是报「输入文件不存在」这种与真实原因无关的错误。
     */
    @Test
    fun analyzeToleratesMissingWeavePlanWhenPlanTaskExcluded() {
        val project = FunctionalTestProject(tempDir.resolve("weaving-plan-excluded")).writeFixture(
            FixtureOptions(
                applyMockTaboolib = false,
                autoTakeover = false,
                includeIocLibrary = false,
                localProjectPath = null,
            ),
        )

        val result = project.build(
            ":consumer:analyzeTaboolibIocBeans",
            "-Ptaboolib.ioc.weaving=true",
            "-x",
            ":consumer:planTaboolibIocAop",
        )

        assertFalse(
            Files.exists(tempDir.resolve("weaving-plan-excluded/consumer/build/taboolib-ioc/aop-weave-plan.json")),
            "被排除的 plan 任务不应产出计划文件",
        )
        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":consumer:analyzeTaboolibIocBeans")?.outcome,
            "计划缺失不能阻断分析（缺失即视为无计划）",
        )
        assertFalse(result.output.contains("expected to be present"), "缺失不得被报成输入文件不存在")
        assertContains(result.output, "[analyzeTaboolibIocBeans] report=")
    }

    @Test
    fun failsOnManualRelocationConflict() {
        val project = FunctionalTestProject(tempDir.resolve("relocation-conflict")).writeFixture(
            FixtureOptions(manualRelocationTarget = "com.manual.override")
        )

        val result = project.build(":consumer:build", expectFailure = true)
        assertContains(result.output, "手写 relocate 与自动 IoC relocate 冲突")
    }

    @Test
    fun skipsAutoTakeoverWhenDisabled() {
        val project = FunctionalTestProject(tempDir.resolve("disabled-auto-takeover")).writeFixture(
            FixtureOptions(autoTakeover = false),
        )

        project.build(":consumer:build")
        val entries = project.consumerJarEntries()
        assertFalse(entries.contains("top/wcpe/taboolib/ioc/SampleService.class"))
        assertFalse(entries.contains("com/example/root/ioc/SampleService.class"))
    }

    @Test
    fun verifyTaskLogsSkipMessageWhenAutoTakeoverDisabled() {
        val project = FunctionalTestProject(tempDir.resolve("disabled-verify-task")).writeFixture(
            FixtureOptions(autoTakeover = false),
        )

        val result = project.build(":consumer:verifyTaboolibIoc")

        assertContains(result.output, "taboolibIoc.autoTakeover=false，已跳过自动接管验证")
    }

    @Test
    fun skipsTakeoverWhenTaboolibMarksSubproject() {
        val project = FunctionalTestProject(tempDir.resolve("subproject-skip")).writeFixture(
            FixtureOptions(taboolibSubproject = true),
        )

        project.build(":consumer:build")
        val entries = project.consumerJarEntries()
        assertFalse(entries.contains("top/wcpe/taboolib/ioc/SampleService.class"))
        assertFalse(entries.contains("com/example/root/ioc/SampleService.class"))
    }

    @Test
    fun analyzeTaskWritesRequestedStaticDiagnosisReport() {
        val project = FunctionalTestProject(tempDir.resolve("static-diagnosis")).writeFixture(
            FixtureOptions(
                applyMockTaboolib = false,
                autoTakeover = false,
                includeIocLibrary = false,
                localProjectPath = null,
                includeStaticDiagnosisSamples = true,
                analysisFailOnError = false,
                analysisFailOnWarning = false,
            ),
        )

        val result = project.build(":consumer:analyzeTaboolibIocBeans")
        val report = project.readRelativeFile("consumer/build/reports/taboolib-ioc/static-diagnosis.json")

        assertContains(result.output, "[analyzeTaboolibIocBeans]")
        assertContains(report, "beanIndex")
        assertContains(report, "injectionPointIndex")
        assertContains(report, "constructor_parameter")
        assertContains(report, "field")
        assertContains(report, "method_parameter")
        assertContains(report, "missing-bean")
        assertContains(report, "named-bean-not-found")
        assertContains(report, "named-bean-type-mismatch")
        assertContains(report, "multiple-primary-beans")
        assertContains(report, "multiple-candidates-unqualified")
        assertContains(report, "conditional-bean-only")
        assertContains(report, "runtime-manual-bean-only")
        assertContains(report, "component-scan-may-exclude")
        assertContains(report, "missing-inject-annotation")
    }

    @Test
    fun analyzeTaskRunsOnGradle89() {
        val project = FunctionalTestProject(tempDir.resolve("static-diagnosis-gradle-89")).writeFixture(
            FixtureOptions(
                applyMockTaboolib = false,
                autoTakeover = false,
                includeIocLibrary = false,
                localProjectPath = null,
                includeStaticDiagnosisSamples = true,
                analysisFailOnError = false,
                analysisFailOnWarning = false,
            ),
        )

        val result = project.build(
            ":consumer:analyzeTaboolibIocBeans",
            gradleVersion = "8.9",
        )
        val report = project.readRelativeFile("consumer/build/reports/taboolib-ioc/static-diagnosis.json")

        assertContains(result.output, "[analyzeTaboolibIocBeans]")
        assertContains(report, "missing-bean")
        assertContains(report, "component-scan-may-exclude")
        assertContains(report, "missing-inject-annotation")
    }

    @Test
    fun kotlinDslConsumerCanConfigureVersionAndTargetPackage() {
        val project = FunctionalTestProject(tempDir.resolve("kotlin-dsl-consumer")).writeFixture(
            FixtureOptions(
                applyMockTaboolib = false,
                includeIocLibrary = false,
                localProjectPath = null,
                useKotlinDslConsumer = true,
                explicitIocVersion = "9.9.9",
                explicitTargetPackage = "com.example.kotlin",
            ),
        )

        val result = project.build(":consumer:taboolibIocDoctor")

        assertContains(result.output, "[taboolibIocDoctor] dependency = top.wcpe.taboolib.ioc:taboolib-ioc:9.9.9")
        assertContains(result.output, "[taboolibIocDoctor] testDependency = top.wcpe.taboolib.ioc:taboolib-ioc-test:9.9.9")
        assertContains(result.output, "top.wcpe.taboolib.ioc -> com.example.kotlin.ioc")
    }

    @Test
    fun analyzeTaskFailsWhenFailOnErrorIsEnabled() {
        val project = FunctionalTestProject(tempDir.resolve("static-diagnosis-error-gate")).writeFixture(
            FixtureOptions(
                applyMockTaboolib = false,
                autoTakeover = false,
                includeIocLibrary = false,
                localProjectPath = null,
                includeStaticDiagnosisSamples = true,
                analysisFailOnError = true,
            ),
        )

        val result = project.build(":consumer:analyzeTaboolibIocBeans", expectFailure = true)
        val problemsReport = project.readRelativeFile("build/reports/problems/problems-report.html")

        assertContains(result.output, "failOnError=true")
        assertContains(result.output, "问题明细已按 IDE 可识别格式输出到上方日志")
        assertContains(result.output, "MissingBeanConsumer#constructor[0]")
        assertContains(result.output, "missing-bean")
        assertContains(result.output, "error: [missing-bean]")
        assertContains(result.output, "MissingInjectComponentConsumer#componentService")
        assertContains(result.output, "missing-inject-annotation")
        assertContains(result.output, "error: [missing-inject-annotation]")
        assertContains(result.output, "source: Consumers.java")
        assertContains(problemsReport, "Taboolib IoC")
        assertContains(problemsReport, "Static Diagnosis")
        assertContains(problemsReport, "Missing Bean")
        assertContains(problemsReport, "missing-bean")
        assertContains(problemsReport, "Missing Inject Annotation")
        assertContains(problemsReport, "missing-inject-annotation")
    }

    @Test
    fun `analyzeTaboolibIocBeans reports error for missing bean dependency`() {
        val project = FunctionalTestProject(tempDir.resolve("ioc-missing-bean")).writeFixture(
            FixtureOptions(
                applyMockTaboolib = false,
                autoTakeover = false,
                includeIocLibrary = false,
                localProjectPath = null,
                includeStaticDiagnosisSamples = true,
                analysisFailOnError = false,
                analysisFailOnWarning = false,
            ),
        )

        val report = project.build(":consumer:analyzeTaboolibIocBeans")
            .let { project.readRelativeFile("consumer/build/reports/taboolib-ioc/static-diagnosis.json") }

        assertContains(report, "missing-bean")
        assertContains(report, "MissingBeanConsumer")
    }

    @Test
    fun `analyzeTaboolibIocBeans reports error for multiple primary beans`() {
        val project = FunctionalTestProject(tempDir.resolve("ioc-multiple-primary")).writeFixture(
            FixtureOptions(
                applyMockTaboolib = false,
                autoTakeover = false,
                includeIocLibrary = false,
                localProjectPath = null,
                includeStaticDiagnosisSamples = true,
                analysisFailOnError = false,
                analysisFailOnWarning = false,
            ),
        )

        val report = project.build(":consumer:analyzeTaboolibIocBeans")
            .let { project.readRelativeFile("consumer/build/reports/taboolib-ioc/static-diagnosis.json") }

        assertContains(report, "multiple-primary-beans")
        assertContains(report, "MultiplePrimaryConsumer")
    }

    @Test
    fun `analyzeTaboolibIocBeans reports error for named bean not found`() {
        val project = FunctionalTestProject(tempDir.resolve("ioc-named-not-found")).writeFixture(
            FixtureOptions(
                applyMockTaboolib = false,
                autoTakeover = false,
                includeIocLibrary = false,
                localProjectPath = null,
                includeStaticDiagnosisSamples = true,
                analysisFailOnError = false,
                analysisFailOnWarning = false,
            ),
        )

        val report = project.build(":consumer:analyzeTaboolibIocBeans")
            .let { project.readRelativeFile("consumer/build/reports/taboolib-ioc/static-diagnosis.json") }

        assertContains(report, "named-bean-not-found")
        assertContains(report, "MissingNamedConsumer")
    }

    @Test
    fun `analyzeTaboolibIocBeans passes for valid injection`() {
        val project = FunctionalTestProject(tempDir.resolve("ioc-valid-injection")).writeFixture(
            FixtureOptions(
                applyMockTaboolib = false,
                autoTakeover = false,
                includeIocLibrary = false,
                localProjectPath = null,
                analysisFailOnError = true,
                analysisFailOnWarning = true,
            ),
        )
        project.writeSourceFile(
            "consumer/src/main/java/fixture/valid/Annotations.java",
            """
            package fixture.valid;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.TYPE) @interface Bean {}
            @Retention(RetentionPolicy.CLASS) @Target({ElementType.CONSTRUCTOR, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER}) @interface Inject {}
            """.trimIndent(),
        )
        project.writeSourceFile(
            "consumer/src/main/java/fixture/valid/ValidBeans.java",
            """
            package fixture.valid;
            interface GreetingService {}
            @Bean class GreetingServiceImpl implements GreetingService {}
            @Bean class GreetingConsumer { GreetingConsumer(GreetingService svc) {} }
            """.trimIndent(),
        )

        val result = project.build(":consumer:analyzeTaboolibIocBeans")
        val report = project.readRelativeFile("consumer/build/reports/taboolib-ioc/static-diagnosis.json")

        assertContains(result.output, "[analyzeTaboolibIocBeans]")
        assertContains(report, "\"errorCount\": 0")
        assertContains(report, "\"warningCount\": 0")
    }

    @Test
    fun `analyzeTaboolibIocBeans reports error for constructor circular dependency`() {
        val project = FunctionalTestProject(tempDir.resolve("ioc-circular-dep")).writeFixture(
            FixtureOptions(
                applyMockTaboolib = false,
                autoTakeover = false,
                includeIocLibrary = false,
                localProjectPath = null,
                analysisFailOnError = false,
                analysisFailOnWarning = false,
            ),
        )
        project.writeSourceFile(
            "consumer/src/main/java/fixture/cycle/Annotations.java",
            """
            package fixture.cycle;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.TYPE) @interface Bean {}
            """.trimIndent(),
        )
        project.writeSourceFile(
            "consumer/src/main/java/fixture/cycle/CycleBeans.java",
            """
            package fixture.cycle;
            @Bean class AlphaService { AlphaService(BetaService b) {} }
            @Bean class BetaService { BetaService(AlphaService a) {} }
            """.trimIndent(),
        )

        val report = project.build(":consumer:analyzeTaboolibIocBeans")
            .let { project.readRelativeFile("consumer/build/reports/taboolib-ioc/static-diagnosis.json") }

        assertContains(report, "circular-dependency-detected")
    }

    @Test
    fun analyzeTaskFailsWhenFailOnWarningIsEnabled() {
        val project = FunctionalTestProject(tempDir.resolve("static-diagnosis-warning-gate")).writeFixture(
            FixtureOptions(
                applyMockTaboolib = false,
                autoTakeover = false,
                includeIocLibrary = false,
                localProjectPath = null,
                includeStaticDiagnosisSamples = true,
                analysisFailOnError = false,
                analysisFailOnWarning = true,
            ),
        )

        val result = project.build(":consumer:analyzeTaboolibIocBeans", expectFailure = true)

        assertContains(result.output, "failOnWarning=true")
        assertContains(result.output, "conditional-bean-only")
        assertContains(result.output, "warning: [conditional-bean-only]")
    }

    @Test
    fun buildFailsByDefaultWhenStaticDiagnosisReportsErrors() {
        val project = FunctionalTestProject(tempDir.resolve("static-diagnosis-build-gate")).writeFixture(
            FixtureOptions(
                applyMockTaboolib = false,
                autoTakeover = false,
                includeIocLibrary = false,
                localProjectPath = null,
                includeStaticDiagnosisSamples = true,
            ),
        )

        val result = project.build(":consumer:build", expectFailure = true)

        assertContains(result.output, ":consumer:analyzeTaboolibIocBeans")
        assertContains(result.output, "failOnError=true")
        assertContains(result.output, "MissingBeanConsumer#constructor[0]")
        assertContains(result.output, "MissingInjectComponentConsumer#componentService")
        assertContains(result.output, "missing-inject-annotation")
        assertContains(result.output, "source: Consumers.java")
    }

        @Test
        fun buildSucceedsAndStoresConfigurationCacheEntry() {
            val project = FunctionalTestProject(tempDir.resolve("configuration-cache")).writeFixture(
                FixtureOptions(),
            )

            // 预热：先让构建产物就位。否则首次构建会新产出 ioc-lib/build/libs/*.jar，
            // 配置缓存的文件系统探测会因此判定「不可复用」，掩盖真正要守的序列化缺陷。
            project.build(":consumer:build", "--no-configuration-cache")

            val result = project.build(
                ":consumer:build",
                "--configuration-cache",
                "--configuration-cache-problems=fail",
            )
            assertContains(result.output, "Configuration cache entry stored")

            // 第二次构建必须复用缓存条目：证明任务状态确实可序列化，而不是每次都被丢弃。
            val reused = project.build(
                ":consumer:build",
                "--configuration-cache",
                "--configuration-cache-problems=fail",
            )
            assertContains(reused.output, "Reusing configuration cache")
        }

        /**
         * 配置缓存回归：`taboolibIocDoctor` 的诊断文本改为配置阶段生成，
         * 该任务同样必须在配置缓存下可用，且输出内容保持不变。
         */
        @Test
        fun doctorTaskWorksWithConfigurationCache() {
            val project = FunctionalTestProject(tempDir.resolve("configuration-cache-doctor")).writeFixture(
                FixtureOptions(),
            )

            val result = project.build(
                ":consumer:taboolibIocDoctor",
                "--configuration-cache",
                "--configuration-cache-problems=fail",
            )

            assertContains(result.output, "[taboolibIocDoctor] configured = true")
            assertContains(result.output, "Configuration cache entry stored")
        }

    /**
     * Gradle 9.x 移除了 `ProjectDependency.getDependencyProject()`（9.x 只保留 `getPath()`），
     * 而插件是按 wrapper（8.14.4）的 `gradleApi()` 编译的：任何编译期直连项目依赖路径的写法
     * 都会「编译通过、运行期崩在 9.x」。只在 9.5.0 上跑不进入 TabooLib 后端的分析任务是假保证 ——
     * 必须让本地联调工程接管路径（`TabooLibBackend` 的 `ProjectDependencySpec` 分支：依赖注入去重、
     * 冲突检测、jar 任务依赖）在 9.5.0 上被真实执行。
     *
     * fixture 里额外声明 `taboo project(':ioc-lib')` 不是摆设：插件用 `withDependencies` 惰性注入，
     * 该回调只在 taboo 被解析时触发；若那一刻 taboo 里还没有 project 依赖，
     * 「读取已存在项目依赖路径」那几段代码根本不会求值 —— 测试会再次退化成假保证。
     * 这条声明同时对应真实用法：用户自己声明了本地联调依赖时，插件必须以去重收场，而不是重复注入。
     */
    @Test
    @DisplayName("Gradle 9.5.0 下本地联调工程接管完整可用")
    fun localProjectTakeoverWorksOnGradle95() {
        val project = FunctionalTestProject(tempDir.resolve("local-project-takeover-gradle-95")).writeFixture(FixtureOptions())
        appendFixtureBuild(project, "consumer/build.gradle", "dependencies { taboo project(':ioc-lib') }")
        writeTabooDependencyProbe(project, expectedPath = ":ioc-lib")

        val result = project.build(":consumer:build", gradleVersion = "9.5.0")
        val entries = project.consumerJarEntries()

        assertContains(result.output, ":consumer:verifyTaboolibIoc")
        assertTrue(entries.contains("com/example/root/ioc/SampleService.class"), "联调依赖必须被注入并按 IoC 目标包 relocate")
        assertFalse(entries.contains("top/wcpe/taboolib/ioc/SampleService.class"), "原始包名不得残留在产物中")
    }

    /**
     * 冲突检测同样在配置期读取既有的 project 依赖路径：声明了**另一个**本地工程时，
     * 必须给出插件自己的「多个 IoC project 依赖来源」错误，而不是让 9.x 上的 `NoSuchMethodError` 冒出来。
     */
    @Test
    @DisplayName("Gradle 9.5.0 下多个联调工程依赖给出明确冲突错误")
    fun conflictingLocalProjectDependencyFailsClearlyOnGradle95() {
        val project = FunctionalTestProject(tempDir.resolve("conflicting-local-project-gradle-95")).writeFixture(FixtureOptions())
        project.writeSourceFile("settings.gradle", project.readRelativeFile("settings.gradle") + "\ninclude 'other-lib'\n")
        project.writeSourceFile("other-lib/build.gradle", "plugins { id 'java' }")
        appendFixtureBuild(project, "consumer/build.gradle", "dependencies { taboo project(':other-lib') }")

        val result = project.build(":consumer:build", gradleVersion = "9.5.0", expectFailure = true)

        assertContains(result.output, "多个 IoC project 依赖来源")
        assertContains(result.output, ":other-lib")
        assertContains(result.output, ":ioc-lib")
    }

    @Test
    @DisplayName("普通直接项目依赖在三个 Gradle 版本提供真实 main 源码")
    fun analysisIndexesDirectBusinessProjectSourcesAcrossGradleVersions() {
        // 依赖中的显式空初始化合法；同时保护新旧 Gradle 项目依赖 API 的兼容。
        for (version in listOf("8.9", "8.14.4", "9.5.0")) {
            val project = dependencyProjectFixture("direct-$version", "implementation project(':library')")
            writeBusinessState(project)
            val result = project.build(":consumer:recordAnalysisSources", gradleVersion = version)
            val sources = project.readRelativeFile("consumer/build/analysis-sources.txt")
            assertEquals(TaskOutcome.SUCCESS, result.task(":consumer:analyzeTaboolibIocBeans")?.outcome, "合法手工状态不应阻断分析")
            assertContains(sources, "/library/src/main/java", message = "普通依赖必须提供对应 main 源码")
            assertFalse(project.readRelativeFile("consumer/build/reports/taboolib-ioc/static-diagnosis.json")
                .contains("missing-inject-annotation"), "已有初始化的字段不能误报漏注入")
        }
    }

    @Test
    @DisplayName("通过 api 传递的项目依赖提供被实际扫描的 main 源码")
    fun analysisIndexesTransitiveBusinessApiSources() {
        // 消费者只直接依赖中间模块，末级源码必须随真实 api 产物进入分析。
        val project = dependencyProjectFixture("transitive-api", "implementation project(':middle')", "api project(':library')")
        writeBusinessState(project)
        project.build(":consumer:recordAnalysisSources")
        val sources = project.readRelativeFile("consumer/build/analysis-sources.txt")
        assertContains(sources, "/middle/src/main/java", message = "直接业务模块源码必须纳入")
        assertContains(sources, "/library/src/main/java", message = "api 导出的实际传递模块源码必须纳入")
        assertFalse(project.readRelativeFile("consumer/build/reports/taboolib-ioc/static-diagnosis.json")
            .contains("missing-inject-annotation"), "跨两级依赖的手工状态仍应正确识别")
    }

    @Test
    @DisplayName("无关模块、纯运行依赖和测试源码不进入 main 分析输入")
    fun analysisExcludesUnrelatedRuntimeAndTestProjectSources() {
        // 源码补齐只跟随 main 实际产物，不能把工程所有源目录都当输入。
        val dependencies = "implementation project(':library'); runtimeOnly project(':runtime-only'); testImplementation project(':test-only')"
        val project = dependencyProjectFixture("source-boundaries", dependencies)
        project.writeSourceFile("library/src/test/java/fixture/TestsOnly.java", "package fixture; class TestsOnly {}")
        project.build(":consumer:recordAnalysisSources")
        val sources = project.readRelativeFile("consumer/build/analysis-sources.txt")
        assertContains(sources, "/library/src/main/java", message = "main 实际依赖必须纳入")
        assertFalse(sources.contains("/runtime-only/"), "只在运行期使用的模块不得增加 main 源码输入")
        assertFalse(sources.contains("/test-only/"), "只在测试期使用的模块不得增加 main 源码输入")
        assertFalse(sources.contains("/unrelated/"), "不能扫描全工程的无关模块")
        assertFalse(sources.contains("/src/test/"), "业务模块的测试源码不得参与 main 诊断")
    }

    @Test
    @DisplayName("未通过 api 导出的 implementation 模块不增加消费者源码输入")
    fun analysisExcludesImplementationHiddenByApiVariant() {
        // 中间模块的实现依赖不在消费者编译类路径，源码也不能越界补入。
        val project = dependencyProjectFixture("hidden-implementation", "implementation project(':middle')", "implementation project(':library')")
        project.build(":consumer:recordAnalysisSources")
        val sources = project.readRelativeFile("consumer/build/analysis-sources.txt")
        assertContains(sources, "/middle/src/main/java", message = "直接依赖仍应纳入")
        assertFalse(sources.contains("/library/src/main/"), "字节码不在消费者编译类路径时不能补入隐藏模块源码")
    }

    @Test
    @DisplayName("关闭传递依赖时不会补入被排除的项目源码")
    fun analysisHonorsNonTransitiveProjectDependency() {
        // 传递开关由既有 Gradle 类路径决定，源码补齐必须遵守相同结果。
        val project = dependencyProjectFixture("non-transitive", "implementation(project(':middle')) { transitive = false }", "api project(':library')")
        project.build(":consumer:recordAnalysisSources")
        val sources = project.readRelativeFile("consumer/build/analysis-sources.txt")
        assertContains(sources, "/middle/src/main/java", message = "关闭传递不能排除直接依赖")
        assertFalse(sources.contains("/library/src/main/"), "不能自行绕过真实类路径的 transitive=false")
    }

    @Test
    @DisplayName("依赖模块真正遗漏注入仍阻断分析并给出依赖源码定位")
    fun analysisReportsTrueMissingInjectInBusinessProject() {
        // 去掉初始化后是实际漏注入；补齐源码只改善定位，不能抑制错误。
        val project = dependencyProjectFixture("true-missing-inject", "implementation project(':library')")
        writeBusinessState(project, initialized = false)
        val result = project.build(":consumer:analyzeTaboolibIocBeans", expectFailure = true)
        val report = project.readRelativeFile("consumer/build/reports/taboolib-ioc/static-diagnosis.json")
        assertContains(result.output, "failOnError=true", message = "源码补齐不能关闭错误门禁")
        assertContains(report, "missing-inject-annotation", message = "真正漏注入必须保留错误")
        assertTrue(Regex("""["]sourcePath["]\s*:\s*["][^"]*ManualState\.java["]""").containsMatchIn(report),
            "错误位置必须来自依赖模块的真实源文件")
    }

    @Test
    @DisplayName("依赖源码输入支持配置缓存复用且源码变化后重新分析")
    fun dependencySourceInputsSupportConfigurationCacheAndInvalidation() {
        val project = dependencyProjectFixture("source-cache", "implementation project(':library')")
        writeBusinessState(project)
        // 先产出依赖 jar，避免新文件探测掩盖任务状态的配置缓存序列化问题。
        project.build(":consumer:analyzeTaboolibIocBeans", "--no-configuration-cache")
        val stored = project.build(":consumer:analyzeTaboolibIocBeans", "--configuration-cache", "--configuration-cache-problems=fail")
        assertContains(stored.output, "Configuration cache entry stored", message = "首次配置缓存必须保存")
        val reused = project.build(":consumer:analyzeTaboolibIocBeans", "--configuration-cache", "--configuration-cache-problems=fail")
        assertContains(reused.output, "Reusing configuration cache", message = "未改动时必须复用配置缓存")
        val source = "library/src/main/java/fixture/projectdependency/ManualState.java"
        project.writeSourceFile(source, project.readRelativeFile(source) + "\n// 仅改依赖源码，验证分析输入的失效。\n")
        val changed = project.build(":consumer:analyzeTaboolibIocBeans", "--configuration-cache", "--configuration-cache-problems=fail")
        assertContains(changed.output, "Reusing configuration cache", message = "源码输入变化不应要求重新配置工程")
        assertEquals(TaskOutcome.SUCCESS, changed.task(":consumer:analyzeTaboolibIocBeans")?.outcome,
            "依赖源码变化必须重新分析，不能复用旧任务结果")
    }

    private fun dependencyProjectFixture(name: String, consumerDependencies: String, middleDependencies: String = ""): FunctionalTestProject {
        val project = FunctionalTestProject(tempDir.resolve(name)).writeFixture(FixtureOptions(
            applyMockTaboolib = false, autoTakeover = false, includeIocLibrary = false, localProjectPath = null))
        project.writeSourceFile("settings.gradle", project.readRelativeFile("settings.gradle") +
            "\ninclude 'library', 'middle', 'runtime-only', 'test-only', 'unrelated'\n")
        for (module in listOf("library", "middle", "runtime-only", "test-only", "unrelated")) {
            project.writeSourceFile("$module/build.gradle", "plugins { id 'java-library' }; tasks.withType(JavaCompile) { options.encoding = 'UTF-8' }")
            val identifier = module.replace("-", "")
            project.writeSourceFile("$module/src/main/java/fixture/$identifier/Marker.java",
                "package fixture.$identifier; public class Marker {}")
        }
        appendFixtureBuild(project, "consumer/build.gradle", "dependencies { $consumerDependencies }")
        appendFixtureBuild(project, "middle/build.gradle", "dependencies { $middleDependencies }")
        writeSourceInputsObserver(project)
        return project
    }

    private fun writeBusinessState(project: FunctionalTestProject, initialized: Boolean = true) {
        val initializer = if (initialized) " = null" else ""
        project.writeSourceFile("library/src/main/java/fixture/projectdependency/ManualState.java", """
            package fixture.projectdependency;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.TYPE) @interface Component {}
            @Component class DependencyService {}
            public class ManualState {
                public DependencyService value$initializer;
            }
        """.trimIndent())
    }

    /**
     * 构造期**二次查询** `taboo` 依赖集合。
     *
     * 插件用 `withDependencies` 惰性注入联调依赖，因此只有再次查询依赖集合时，
     * `ProjectDependencySpec` 分支里「读取已存在的 project 依赖路径」（去重与冲突检测）才会真正执行 ——
     * 这正是 Gradle 9.x 上最容易抛 `NoSuchMethodError` 的那段。这里把结果做成硬断言，
     * 避免把「构建没崩」当成「该分支真的跑过」。
     */
    private fun writeTabooDependencyProbe(project: FunctionalTestProject, expectedPath: String) {
        appendFixtureBuild(project, "consumer/build.gradle", """
            afterEvaluate {
                def projectDependencies = configurations.taboo.allDependencies.findAll {
                    it instanceof org.gradle.api.artifacts.ProjectDependency
                }
                if (projectDependencies.size() != 1) {
                    throw new GradleException("[tabooProbe] 期望恰好 1 个本地联调 project 依赖，实际 " + projectDependencies.size() + " 个")
                }
                if (projectDependencies[0].path != '$expectedPath') {
                    throw new GradleException("[tabooProbe] 本地联调项目路径读取异常：" + projectDependencies[0].path)
                }
            }
        """.trimIndent())
    }

    private fun writeSourceInputsObserver(project: FunctionalTestProject) {
        // 观察任务只读取声明好的文件输入；执行期不访问 Project，避免污染配置缓存验证。
        appendFixtureBuild(project, "consumer/build.gradle", """
            abstract class AnalysisSourceInputs extends DefaultTask {
                @org.gradle.api.tasks.InputFiles
                @org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.RELATIVE)
                abstract org.gradle.api.file.ConfigurableFileCollection getSources()
                @org.gradle.api.tasks.OutputFile
                abstract org.gradle.api.file.RegularFileProperty getReportFile()
                @org.gradle.api.tasks.TaskAction
                void writeInputs() {
                    def target = reportFile.get().asFile
                    target.parentFile.mkdirs()
                    target.text = sources.files.collect { it.absolutePath.replace('\\', '/') }.sort().join('\n')
                }
            }
            def analysis = tasks.named('analyzeTaboolibIocBeans')
            tasks.register('recordAnalysisSources', AnalysisSourceInputs) {
                dependsOn(analysis)
                sources.from(analysis.map { it.sourceDirectories })
                reportFile.set(layout.buildDirectory.file('analysis-sources.txt'))
            }
        """.trimIndent())
    }

    private fun appendFixtureBuild(project: FunctionalTestProject, path: String, content: String) {
        project.writeSourceFile(path, project.readRelativeFile(path) + "\n" + content)
    }

}