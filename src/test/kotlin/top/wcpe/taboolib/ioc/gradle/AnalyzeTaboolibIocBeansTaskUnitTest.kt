package top.wcpe.taboolib.ioc.gradle

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.io.TempDir
import top.wcpe.taboolib.ioc.gradle.analysis.AnalyzeTaboolibIocBeansTask
import top.wcpe.taboolib.ioc.gradle.analysis.BytecodeBeanIndexBuilder
import top.wcpe.taboolib.ioc.gradle.weaving.AopWeavePlanner
import top.wcpe.taboolib.ioc.gradle.weaving.AopWeaver
import top.wcpe.taboolib.ioc.gradle.weaving.ClassWeaveDecision
import top.wcpe.taboolib.ioc.gradle.weaving.WeaveOutcome
import top.wcpe.taboolib.ioc.gradle.weaving.WeavePlan
import top.wcpe.taboolib.ioc.gradle.weaving.WeavePlanJson

class AnalyzeTaboolibIocBeansTaskUnitTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun writesStructuredStaticDiagnosisReport() {
        val classesDir = StaticDiagnosisFixtureSources.compileJavaSources(tempDir.resolve("compiled"))
        val project = ProjectBuilder.builder()
            .withName("analysis-task")
            .withProjectDir(Files.createTempDirectory("analysis-task").toFile())
            .build()

        val task = project.tasks.create("analyzeTaboolibIocBeans", AnalyzeTaboolibIocBeansTask::class.java)
        val reportFile = tempDir.resolve("report.json").toFile()
        task.classDirectories.from(classesDir.toFile())
        task.sourceDirectories.from(tempDir.resolve("compiled/src").toFile())
        task.failOnError.set(false)
        task.failOnWarning.set(false)
        task.projectPropertiesInput.put("feature.enabled", "on")
        task.projectPath.set(project.path)
        task.reportFile.set(reportFile)

        task.generateReport()

        val report = reportFile.readText()
        assertContains(report, "beanIndex")
        assertContains(report, "injectionPointIndex")
        // 抑制与降级必须随报告落盘：否则「报告里为什么少了某条诊断」无从追溯。
        assertContains(report, "suppressedMissingInjections")
        assertContains(report, "sourceIndexDegradations")
        assertContains(report, "missing-bean")
        assertContains(report, "named-bean-not-found")
        assertContains(report, "named-bean-type-mismatch")
        assertContains(report, "multiple-primary-beans")
        assertContains(report, "multiple-candidates-unqualified")
        assertContains(report, "conditional-bean-only")
        assertContains(report, "runtime-manual-bean-only")
        assertContains(report, "component-scan-may-exclude")
        assertContains(report, "missing-inject-annotation")
        assertContains(report, "dependencyGenericType")
        assertContains(report, "conditions")
        assertContains(report, "sourcePath")
        assertContains(report, "sourceLine")
    }

    /**
     * §2.4 第 2 条（任务级）：把 `planTaboolibIocAop` 的产物接到诊断任务 `weavePlanFile` 后，
     * `weaving=true` 时 `aop-target-not-proxied` 必须被抑制（证明诊断确实在读计划，而非另做预测）。
     */
    @Test
    fun suppressesTargetNotProxiedWhenWeavePlanProvided() {
        val classesDir = StaticDiagnosisFixtureSources.compileAopWeavingSources(tempDir.resolve("weaving-plan"))
        val sourceDir = tempDir.resolve("weaving-plan/src")
        val index = BytecodeBeanIndexBuilder.build(listOf(classesDir), listOf(sourceDir))
        val advices = AopWeavePlanner.resolve(index.aspectIndex)
        val decisions = Files.walk(classesDir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }
                .map { file -> AopWeaver.plan(Files.readAllBytes(file), advices) }
                .filter { it != null }
                .map { it!! }
                .toList()
        }
        val planFile = tempDir.resolve("aop-weave-plan.json")
        WeavePlanJson.write(WeavePlan(adviceCount = advices.size, classes = decisions), planFile)

        val project = ProjectBuilder.builder()
            .withName("analysis-weaving-plan")
            .withProjectDir(Files.createTempDirectory("analysis-weaving-plan").toFile())
            .build()

        val task = project.tasks.create("analyzeTaboolibIocBeans", AnalyzeTaboolibIocBeansTask::class.java)
        task.classDirectories.from(classesDir.toFile())
        task.sourceDirectories.from(sourceDir.toFile())
        task.failOnError.set(false)
        task.failOnWarning.set(false)
        task.projectPath.set(project.path)
        task.weaving.set(true)
        task.weavePlanFile.set(planFile.toFile())
        val reportFile = tempDir.resolve("weaving-plan-report.json").toFile()
        task.reportFile.set(reportFile)

        task.generateReport()

        val report = reportFile.readText()
        assertFalse(
            report.contains("\"rule\": \"aop-target-not-proxied\""),
            "有计划时不应报 aop-target-not-proxied（诊断读计划事实）",
        )
    }

    /**
     * 残渣态（`WOVEN` + `alreadyWoven=true`）下，若计划无法确定织入了哪些方法（`wovenMethods` 为空，
     * 即哨兵情形），诊断必须**保守上报**，而不是把该类的全部命中通知一律当成已实现。
     *
     * 旧实现把 `realized` 整体替换为「该 bean 的全部命中通知」，等价于对该类无条件抑制：
     * 方法级 `@NoAspect`、父类声明而子类未覆写这类永远不会被织入的方法所命中的通知会被一起压掉；
     * 更严重的是源码未改时「第一次构建报、第二次不报」—— 结论随构建历史漂移。
     *
     * 夹具特意带上一个 `execution(*.*)` 全通配切点：`*` 在诊断侧无条件匹配，若允许它在
     * `forwarded` 为空（哨兵态）时短路，哨兵态仍会被整类抑制 —— 那条路径与精确方法名不同源，
     * 必须一并钉住。
     */
    @Test
    fun keepsTargetNotProxiedWhenResiduePlanCannotTellWhatWasWoven() {
        val classesDir = StaticDiagnosisFixtureSources.compileAopWeavingWildcardSources(tempDir.resolve("residue-plan"))
        val sourceDir = tempDir.resolve("residue-plan/src")
        val index = BytecodeBeanIndexBuilder.build(listOf(classesDir), listOf(sourceDir))
        val advices = AopWeavePlanner.resolve(index.aspectIndex)
        val decisions = Files.walk(classesDir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }
                .map { file -> AopWeaver.plan(Files.readAllBytes(file), advices) }
                .filter { it != null }
                .map { it!! }
                .map { decision ->
                    if (decision.outcome == WeaveOutcome.WOVEN) {
                        // 残渣态 + 计划无法确定方法清单：只有计划自报的方法名才允许当作已实现。
                        ClassWeaveDecision(
                            className = decision.className,
                            outcome = decision.outcome,
                            alreadyWoven = true,
                            wovenMethods = emptyList(),
                        )
                    } else {
                        decision
                    }
                }
                .toList()
        }
        val planFile = tempDir.resolve("aop-weave-plan-residue.json")
        WeavePlanJson.write(WeavePlan(adviceCount = advices.size, classes = decisions), planFile)

        val project = ProjectBuilder.builder()
            .withName("analysis-residue-plan")
            .withProjectDir(Files.createTempDirectory("analysis-residue-plan").toFile())
            .build()

        val task = project.tasks.create("analyzeTaboolibIocBeans", AnalyzeTaboolibIocBeansTask::class.java)
        task.classDirectories.from(classesDir.toFile())
        task.sourceDirectories.from(sourceDir.toFile())
        task.failOnError.set(false)
        task.failOnWarning.set(false)
        task.projectPath.set(project.path)
        task.weaving.set(true)
        task.weavePlanFile.set(planFile.toFile())
        val reportFile = tempDir.resolve("residue-plan-report.json").toFile()
        task.reportFile.set(reportFile)

        task.generateReport()

        val report = reportFile.readText()
        assertTrue(
            report.contains("\"rule\": \"aop-target-not-proxied\""),
            "残渣态下计划无法确定织入了什么时必须保守上报，不得整类抑制",
        )
        // 只断言「有这条规则」是不够的：旧写法允许 `*` 通配短路，而同类里往往还有别的精确切点
        // 通知仍会被报出，报告照样非空、测试照样绿。必须断言**通配通知本身**也在未实现清单里。
        assertTrue(
            report.contains("WildcardAspect#any"),
            "`*` 通配通知不得在 forwarded 为空（哨兵态）时被当作已实现",
        )
    }

    @Test
    fun failsWhenErrorGateEnabled() {
        val classesDir = StaticDiagnosisFixtureSources.compileJavaSources(tempDir.resolve("error-gate"))
        val project = ProjectBuilder.builder()
            .withName("analysis-error-gate")
            .withProjectDir(Files.createTempDirectory("analysis-error-gate").toFile())
            .build()

        val task = project.tasks.create("analyzeTaboolibIocBeans", AnalyzeTaboolibIocBeansTask::class.java)
        task.classDirectories.from(classesDir.toFile())
        task.sourceDirectories.from(tempDir.resolve("error-gate/src").toFile())
        task.failOnError.set(true)
        task.failOnWarning.set(false)
        task.projectPropertiesInput.put("feature.enabled", "on")
        task.projectPath.set(project.path)
        task.reportFile.set(tempDir.resolve("error-gate-report.json").toFile())

        val error = assertFailsWith<Exception> {
            task.generateReport()
        }

        assertContains(error.message ?: "", "failOnError=true")
        assertContains(error.message ?: "", "问题明细已按 IDE 可识别格式输出到上方日志")
        assertContains(error.message ?: "", "MissingBeanConsumer#constructor[0]")
        assertContains(error.message ?: "", "missing-bean")
        assertContains(error.message ?: "", "MissingInjectComponentConsumer#componentService")
        assertContains(error.message ?: "", "missing-inject-annotation")
        assertContains(error.message ?: "", "source: Consumers.java")
    }

    @Test
    fun failsWhenWarningGateEnabled() {
        val classesDir = StaticDiagnosisFixtureSources.compileJavaSources(tempDir.resolve("warning-gate"))
        val project = ProjectBuilder.builder()
            .withName("analysis-warning-gate")
            .withProjectDir(Files.createTempDirectory("analysis-warning-gate").toFile())
            .build()

        val task = project.tasks.create("analyzeTaboolibIocBeans", AnalyzeTaboolibIocBeansTask::class.java)
        task.classDirectories.from(classesDir.toFile())
        task.sourceDirectories.from(tempDir.resolve("warning-gate/src").toFile())
        task.failOnError.set(false)
        task.failOnWarning.set(true)
        task.projectPropertiesInput.put("feature.enabled", "on")
        task.projectPath.set(project.path)
        task.reportFile.set(tempDir.resolve("warning-gate-report.json").toFile())

        val error = assertFailsWith<Exception> {
            task.generateReport()
        }

        assertContains(error.message ?: "", "failOnWarning=true")
        assertContains(error.message ?: "", "问题明细已按 IDE 可识别格式输出到上方日志")
        assertContains(error.message ?: "", "conditional-bean-only")
    }
}