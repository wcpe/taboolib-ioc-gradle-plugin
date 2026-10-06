package top.wcpe.taboolib.ioc.gradle.analysis

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.problems.Problems
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import top.wcpe.taboolib.ioc.gradle.weaving.WeavePlan
import top.wcpe.taboolib.ioc.gradle.weaving.WeavePlanJson
import java.io.File
import javax.inject.Inject

@CacheableTask
abstract class AnalyzeTaboolibIocBeansTask : DefaultTask() {

    companion object {
        private const val MAX_FAILURE_DETAILS = 12
    }

    @get:Inject
    abstract val problems: Problems

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classDirectories: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dependencyArtifacts: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectories: ConfigurableFileCollection

    @get:Input
    abstract val failOnError: Property<Boolean>

    @get:Input
    abstract val failOnWarning: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val projectPropertiesInput: MapProperty<String, String>

    /**
     * 编译期 AOP 织入开关（`taboolibIoc { weaving }`）。
     *
     * 进入 `@Input` 后，改开关会触发本任务重跑（诊断结论随织入状态变化）。
     * 默认由扩展的 `weaving` 约定提供，保持可覆盖、惰性求值。
     */
    @get:Input
    abstract val weaving: Property<Boolean>

    /**
     * 织入计划文件（`aop-weave-plan.json`），由 `planTaboolibIocAop` 产出。
     *
     * **仅 `weaving=true` 时**由插件设置为 `planTaboolibIocAop` 的输出（携带任务依赖）；
     * `weaving=false` 时不设置 → 任务图与既有行为逐字一致。
     *
     * 声明为 **`@InputFiles`**（而不是 `@InputFile`）是刻意的：`weaving=true` 时计划任务仍可能
     * 被 `-x planTaboolibIocAop` 排除或 `enabled=false`，文件永不产出；而 `@InputFile` 一旦有值却缺文件，
     * Gradle 会直接以「输入文件不存在」失败，报错原因与真实原因（计划任务被排除）毫无关系。
     * 改按允许缺失的形态接收后，缺失只是「没有计划」，与下面的保守语义严格一致。
     *
     * 该文件是 `aop-target-not-proxied` 抑制判据的**唯一事实来源**：缺失 / 解析失败 / schema 不符
     * 一律视为「无计划」→ 诊断保守不抑制（**绝不退回预测**）。
     */
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val weavePlanFile: RegularFileProperty

    /**
     * 当前工程路径。
     *
     * 由插件在配置阶段写入，**不能**在执行阶段读取 `Task.project`：
     * 后者会被 Gradle 配置缓存判定为不受支持的执行期 Project 访问。
     */
    @get:Input
    abstract val projectPath: Property<String>

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun generateReport() {
        val weavingEnabled = weaving.getOrElse(false)
        val scanRoots = (classDirectories.files + dependencyArtifacts.files)
            .filter { it.exists() }
            .map { it.toPath() }
        val sourceRoots = sourceDirectories.files.filter { it.exists() }.map { it.toPath() }
        val typeAliases = SourceTypeAliasIndexBuilder.build(sourceRoots)
        val index = BytecodeBeanIndexBuilder.build(scanRoots, sourceRoots)

        // 抑制与降级原本全静默：报告里少了某条诊断时无从解释。这里给一行可诊断的摘要，
        // 明细随报告落盘（sourceIndexDegradations / suppressedMissingInjections）。
        if (index.suppressedMissingInjections.isNotEmpty()) {
            logger.info(
                "[taboolibIoc] 已按源码事实抑制 ${index.suppressedMissingInjections.size} 条 missing-inject 候选" +
                    "（字段已有初值或已手工装配）；明细见报告 suppressedMissingInjections。",
            )
        }
        if (index.sourceIndexDegradations.isNotEmpty()) {
            logger.warn(
                "[taboolibIoc] 源码索引出现 ${index.sourceIndexDegradations.size} 条降级" +
                    "（目前只覆盖「同名类型冲突」这一种），诊断结论可能偏保守；明细见报告 sourceIndexDegradations。",
            )
        }

        // D2 修复：ConditionalOnClass 的类名指向被扫描工程及其依赖里的类。
        // 用扫描根构建 ClassLoader 供引擎探测，否则插件自身 ClassLoader 必然找不到这些类，
        // 条件被错误判为 DISABLED，连锁误报 missing-bean。
        //
        // B-P1-09（K9 残余）：parent 改为 contextClassLoader（Gradle worker 的上下文加载器），
        // 而非**插件** ClassLoader —— 后者会把消费方（被扫描工程）的同名类遮蔽，
        // 使引擎探测到插件自带的旧版本/同 FQCN 类，得到错误的类存在性与层次结构判定。
        // 注意：H5 泄漏已证伪（hasClass 只看 .isSuccess），这是**正确性**修正，不是泄漏修正。
        val parentClassLoader = Thread.currentThread().contextClassLoader
            ?: StaticDiagnosisEngine::class.java.classLoader
        val scanClassLoader = java.net.URLClassLoader(
            scanRoots.map { it.toUri().toURL() }.toTypedArray(),
            parentClassLoader,
        )
        // 织入计划（事实）：缺失 / 解析失败 / schema 不符 → null → 诊断保守不抑制。
        val weavePlan: WeavePlan? = weavePlanFile.orNull
            ?.asFile
            ?.toPath()
            ?.let { WeavePlanJson.read(it) }
        val report = scanClassLoader.use {
            StaticDiagnosisEngine.analyze(
                projectPath = projectPath.get(),
                index = index,
                typeAliases = typeAliases,
                projectProperties = projectPropertiesInput.getOrElse(emptyMap()),
                scanClassLoader = it,
                weaving = weavingEnabled,
                weavePlan = weavePlan,
            )
        }
        warnIfWovenResidue(index, weavingEnabled)

        val outputFile = reportFile.get().asFile.toPath()
        StaticAnalysisJsonWriter.write(report, outputFile)

        val errorCount = report.diagnostics.count { it.severity == DiagnosticSeverity.ERROR }
        val warningCount = report.diagnostics.count { it.severity == DiagnosticSeverity.WARNING }

        logger.lifecycle(
            "[analyzeTaboolibIocBeans] beans=${report.beanIndex.size}, injections=${report.injectionPointIndex.size}, " +
                "errors=$errorCount, warnings=$warningCount",
        )
        logger.lifecycle("[analyzeTaboolibIocBeans] report=${outputFile}")
        reportGradleProblems(report)
        emitIdeFriendlyDiagnostics(report)

        if (failOnError.get() && errorCount > 0) {
            throw GradleException(buildFailureMessage(report, DiagnosticSeverity.ERROR, outputFile.toString(), "failOnError"))
        }
        if (failOnWarning.get() && warningCount > 0) {
            throw GradleException(buildFailureMessage(report, DiagnosticSeverity.WARNING, outputFile.toString(), "failOnWarning"))
        }
    }

    /**
     * 残渣告警（§2.3.7）：读取 `classDirectories`（编译输出）时检出「已织入字节」即 `logger.warn`。
     *
     * `weaving=false` 时为**高危告警**（jar 可能已被织入却不符当前开关）。绝不静默。
     */
    private fun warnIfWovenResidue(index: BytecodeAnalysisIndex, weaving: Boolean) {
        val outputClassNames = classDirectories.files
            .filter { it.isDirectory }
            .flatMap { classNamesUnder(it) }
            .toSet()
        if (outputClassNames.isEmpty()) {
            return
        }
        val residue = WovenResidueDetector.detectResidueClassNames(index, outputClassNames)
        if (residue.isNotEmpty()) {
            logger.warn(WovenResidueDetector.warningMessage(residue, weaving))
        }
    }

    /** 收集目录下所有 `.class` 文件的点号 FQCN。 */
    private fun classNamesUnder(root: File): List<String> =
        root.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .map { file ->
                file.relativeTo(root).path
                    .removeSuffix(".class")
                    .replace(File.separatorChar, '.')
                    .replace('/', '.')
            }
            .toList()

    private fun buildFailureMessage(
        report: StaticAnalysisReport,
        severity: DiagnosticSeverity,
        reportPath: String,
        gateName: String,
    ): String {
        val matchingDiagnostics = report.diagnostics.filter { it.severity == severity }
        val details = matchingDiagnostics.take(MAX_FAILURE_DETAILS)
            .joinToString(System.lineSeparator()) { formatDiagnostic(it) }
        val remaining = matchingDiagnostics.size - minOf(matchingDiagnostics.size, MAX_FAILURE_DETAILS)
        val overflowMessage = if (remaining > 0) {
            "${System.lineSeparator()}... 其余 $remaining 项请查看完整报告：$reportPath"
        } else {
            ""
        }
        val skipHint = when (severity) {
            DiagnosticSeverity.ERROR ->
                "如需只生成报告而不拦截 error，请显式配置 taboolibIoc { analysisFailOnError = false } 或 -P \"taboolib.ioc.analysis.fail-on-error=false\"。"

            DiagnosticSeverity.WARNING ->
                "如需只生成报告而不拦截 warning，请显式配置 taboolibIoc { analysisFailOnWarning = false } 或 -P \"taboolib.ioc.analysis.fail-on-warning=false\"。"

            DiagnosticSeverity.INFO -> ""
        }
        return listOf(
            "静态诊断发现 ${matchingDiagnostics.size} 个 ${severity.name.lowercase()}，$gateName=true，已中断构建。",
            "报告：$reportPath",
            "问题明细已按 IDE 可识别格式输出到上方日志。",
            if (details.isBlank()) "" else "摘要：${System.lineSeparator()}$details$overflowMessage",
            skipHint,
        ).filter { it.isNotBlank() }
            .joinToString(System.lineSeparator())
    }

    private fun emitIdeFriendlyDiagnostics(report: StaticAnalysisReport) {
        report.diagnostics.forEach { diagnostic ->
            val rendered = formatDiagnostic(diagnostic)
            when (diagnostic.severity) {
                DiagnosticSeverity.ERROR -> logger.error(rendered)
                DiagnosticSeverity.WARNING -> logger.warn(rendered)
                DiagnosticSeverity.INFO -> logger.info(rendered)
            }
        }
    }

    private fun reportGradleProblems(report: StaticAnalysisReport) {
        GradleProblemsCompat.reportStaticDiagnostics(
            problems = problems,
            diagnostics = report.diagnostics,
            logger = logger,
            contextualLabel = ::buildContextualLabel,
            solution = ::buildSolution,
            ruleDisplayName = ::ruleDisplayName,
        )
    }

    private fun buildContextualLabel(diagnostic: StaticDiagnostic): String {
        return "${ruleDisplayName(diagnostic.rule)}: ${diagnostic.ownerClassName}#${diagnostic.declarationName} -> ${diagnostic.dependencyType}"
    }

    private fun buildSolution(diagnostic: StaticDiagnostic): String {
        return when (diagnostic.rule) {
            "missing-bean" -> "补充可扫描且类型兼容的 Bean，或让现有条件 Bean 在当前构建下生效。"
            "named-bean-not-found" -> "补充对应名称的 Bean，或修正 @Named/@Resource 指定的名称。"
            "named-bean-type-mismatch" -> "让指定名称的 Bean 暴露兼容类型，或修正注入点的限定名称。"
            "multiple-primary-beans" -> "确保同一依赖只保留一个 @Primary，或改用限定名称注入。"
            "multiple-candidates-unqualified" -> "为注入点增加限定名称，或通过 @Primary 明确唯一候选。"
            "component-scan-may-exclude" -> "调整 @ComponentScan 范围，确保目标 Bean 包路径被扫描到。"
            "runtime-manual-bean-only" -> "如果依赖必须存在，请提供静态可见 Bean；否则保持 optional 注入。"
            "conditional-bean-only" -> "让条件成立，或提供一个不依赖条件的备用 Bean。"
            "missing-inject-annotation" -> "为该字段补充 @Inject/@Named/@Resource，或改为显式手动传入实例，不要直接裸引用 @Component Bean。"
            "bean-no-resolvable-constructor" -> "在目标构造器上标注 @Inject，或保留一个无参构造器。"
            "bean-type-not-instantiable" -> "移除接口/抽象类/枚举上的组件注解，或改为具体类。"
            "unknown-bean-scope" -> "改用内置作用域，或在构建前通过 taboolib.ioc.knownScopes 声明自定义作用域。"
            "lifecycle-method-signature-invalid" -> "把生命周期方法改为无参方法，需要外部资源请在 @PostConstruct 内通过依赖获取。"
            "bean-method-outside-configuration" -> "把 @Bean 方法移入 @Configuration 类，或给返回类型标注组件注解。"
            "bean-method-void-return" -> "@Bean 方法必须返回非 void 类型。"
            "value-expression-unresolved-placeholder" -> "让 @Value 表达式为整串单一 \${...} 占位符，或改为纯字面量。"
            "value-type-unsupported" -> "改用 String 或基本类型/包装类字段接收 @Value 值。"
            "pointcut-expression-invalid" -> "修正为 execution(类模式.方法模式) 格式，或引用本切面已声明的 @Pointcut 方法名。"
            "duplicate-bean-name" -> "重命名其中一个 Bean，或为注入点使用限定名称。"
            "advice-signature-invalid" -> "按运行时契约修正通知方法签名：@Around 必须恰好 1 个 MethodInvocation 参数，@AfterReturning/@AfterThrowing 最多 1 个参数。"
            "pointcut-target-not-found" -> "修正切点表达式的类名/方法名，或确认目标类在当前扫描范围内。"
            "aop-private-method-pointcut" -> "把目标方法改为 public，或调整切点表达式指向 public 方法。"
            "aop-static-method-pointcut" -> "把目标方法改为实例方法（JDK 动态代理只分派实例方法），或调整切点表达式。"
            "aop-target-not-proxied" -> "为目标 Bean 抽取接口，或调整切点表达式。"
            "aop-factory-bean-interface-return" -> "把 @Bean 方法返回类型改为具体实现类。"
            else -> "根据静态诊断报告修正对应 Bean 装配关系。"
        }
    }

    private fun ruleDisplayName(rule: String): String {
        return when (rule) {
            "missing-bean" -> "Missing Bean"
            "named-bean-not-found" -> "Named Bean Not Found"
            "named-bean-type-mismatch" -> "Named Bean Type Mismatch"
            "multiple-primary-beans" -> "Multiple Primary Beans"
            "multiple-candidates-unqualified" -> "Multiple Candidates Unqualified"
            "component-scan-may-exclude" -> "Component Scan May Exclude"
            "runtime-manual-bean-only" -> "Runtime Manual Bean Only"
            "conditional-bean-only" -> "Conditional Bean Only"
            "missing-inject-annotation" -> "Missing Inject Annotation"
            "bean-no-resolvable-constructor" -> "Bean No Resolvable Constructor"
            "bean-type-not-instantiable" -> "Bean Type Not Instantiable"
            "unknown-bean-scope" -> "Unknown Bean Scope"
            "lifecycle-method-signature-invalid" -> "Lifecycle Method Signature Invalid"
            "bean-method-outside-configuration" -> "Bean Method Outside Configuration"
            "bean-method-void-return" -> "Bean Method Void Return"
            "value-expression-unresolved-placeholder" -> "Value Expression Unresolved Placeholder"
            "value-type-unsupported" -> "Value Type Unsupported"
            "pointcut-expression-invalid" -> "Pointcut Expression Invalid"
            "duplicate-bean-name" -> "Duplicate Bean Name"
            "advice-signature-invalid" -> "Advice Signature Invalid"
            "pointcut-target-not-found" -> "Pointcut Target Not Found"
            "aop-private-method-pointcut" -> "Aop Private Method Pointcut"
            "aop-static-method-pointcut" -> "Aop Static Method Pointcut"
            "aop-target-not-proxied" -> "Aop Target Not Proxied"
            "aop-factory-bean-interface-return" -> "Aop Factory Bean Interface Return"
            else -> rule
        }
    }

    private fun formatDiagnostic(diagnostic: StaticDiagnostic): String {
        val location = buildString {
            append(diagnostic.ownerClassName)
            append('#')
            append(diagnostic.declarationName)
            append(" [")
            append(diagnostic.injectionPointKind.name.lowercase().replace('_', '-'))
            diagnostic.parameterIndex?.let { append("#$it") }
            append(']')
        }
        val severityLabel = diagnostic.severity.name.lowercase()
        val primaryLine = diagnostic.sourcePath?.let { sourcePath ->
            val line = diagnostic.sourceLine ?: 1
            val column = diagnostic.sourceColumn ?: 1
            "$sourcePath:$line:$column: $severityLabel: [${diagnostic.rule}] ${diagnostic.message}"
        } ?: "$severityLabel: [${diagnostic.rule}] ${diagnostic.message}"
        val sourceLine = diagnostic.sourceFile?.let { "${System.lineSeparator()}  source: $it" }.orEmpty()
        val symbolLine = "${System.lineSeparator()}  symbol: $location -> ${diagnostic.dependencyType}"
        val candidatesLine = if (diagnostic.candidateBeans.isEmpty()) {
            ""
        } else {
            "${System.lineSeparator()}  candidates: ${diagnostic.candidateBeans.joinToString(", ")}"
        }
        return "$primaryLine$sourceLine$symbolLine$candidatesLine"
    }
}