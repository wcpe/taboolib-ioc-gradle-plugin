package top.wcpe.taboolib.ioc.gradle.analysis

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.problems.Problems
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

@DisableCachingByDefault(because = "Generates a static diagnosis report from compiled classes")
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

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun generateReport() {
        val scanRoots = (classDirectories.files + dependencyArtifacts.files)
            .filter { it.exists() }
            .map { it.toPath() }
        val sourceRoots = sourceDirectories.files.filter { it.exists() }.map { it.toPath() }
        val typeAliases = SourceTypeAliasIndexBuilder.build(sourceRoots)
        val index = BytecodeBeanIndexBuilder.build(scanRoots, sourceRoots)
        val report = StaticDiagnosisEngine.analyze(
            projectPath = project.path,
            index = index,
            typeAliases = typeAliases,
            projectProperties = projectPropertiesInput.getOrElse(emptyMap()),
        )
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