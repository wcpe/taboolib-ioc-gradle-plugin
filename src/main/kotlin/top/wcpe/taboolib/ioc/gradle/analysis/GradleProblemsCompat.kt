package top.wcpe.taboolib.ioc.gradle.analysis

import org.gradle.api.Action
import org.gradle.api.logging.Logger
import org.gradle.api.problems.Problems

internal object GradleProblemsCompat {

    private const val PROBLEM_NAMESPACE = "taboolib-ioc"
    private const val PLUGIN_GROUP_NAME = "taboolib-ioc"
    private const val PLUGIN_GROUP_DISPLAY_NAME = "Taboolib IoC"
    private const val STATIC_DIAGNOSIS_GROUP_NAME = "static-diagnosis"
    private const val STATIC_DIAGNOSIS_GROUP_DISPLAY_NAME = "Static Diagnosis"

    fun reportStaticDiagnostics(
        problems: Problems,
        diagnostics: List<StaticDiagnostic>,
        logger: Logger,
        contextualLabel: (StaticDiagnostic) -> String,
        solution: (StaticDiagnostic) -> String,
        ruleDisplayName: (String) -> String,
    ) {
        if (diagnostics.isEmpty()) {
            return
        }
        runCatching {
            when {
                hasMethod(problems, "getReporter") -> reportWithModernApi(
                    problems = problems,
                    diagnostics = diagnostics,
                    contextualLabel = contextualLabel,
                    solution = solution,
                    ruleDisplayName = ruleDisplayName,
                )

                hasMethod(problems, "forNamespace", String::class.java) -> reportWithLegacyApi(
                    problems = problems,
                    diagnostics = diagnostics,
                    contextualLabel = contextualLabel,
                    solution = solution,
                    ruleDisplayName = ruleDisplayName,
                )

                else -> logger.info("[taboolibIoc] 当前 Gradle 版本未提供可识别的 Problems API，已跳过结构化问题上报。")
            }
        }.onFailure { error ->
            logger.warn(
                "[taboolibIoc] Problems API 上报失败，已降级为仅输出日志与报告文件：${error.javaClass.simpleName}: ${error.message}",
            )
        }
    }

    private fun reportWithModernApi(
        problems: Problems,
        diagnostics: List<StaticDiagnostic>,
        contextualLabel: (StaticDiagnostic) -> String,
        solution: (StaticDiagnostic) -> String,
        ruleDisplayName: (String) -> String,
    ) {
        val reporter = problems.javaClass.getMethod("getReporter").invoke(problems)
        val actionClass = Class.forName("org.gradle.api.Action")
        val problemGroupClass = Class.forName("org.gradle.api.problems.ProblemGroup")
        val problemIdClass = Class.forName("org.gradle.api.problems.ProblemId")
        val createPluginGroup = problemGroupClass.getMethod("create", String::class.java, String::class.java)
        val createChildGroup = problemGroupClass.getMethod(
            "create",
            String::class.java,
            String::class.java,
            problemGroupClass,
        )
        val createProblemId = problemIdClass.getMethod(
            "create",
            String::class.java,
            String::class.java,
            problemGroupClass,
        )
        val reportMethod = reporter.javaClass.getMethod("report", problemIdClass, actionClass)
        val pluginGroup = createPluginGroup.invoke(null, PLUGIN_GROUP_NAME, PLUGIN_GROUP_DISPLAY_NAME)
        val staticDiagnosisGroup = createChildGroup.invoke(
            null,
            STATIC_DIAGNOSIS_GROUP_NAME,
            STATIC_DIAGNOSIS_GROUP_DISPLAY_NAME,
            pluginGroup,
        )

        diagnostics.forEach { diagnostic ->
            val problemId = createProblemId.invoke(
                null,
                diagnostic.rule,
                ruleDisplayName(diagnostic.rule),
                staticDiagnosisGroup,
            )
            reportMethod.invoke(
                reporter,
                problemId,
                actionOf { spec ->
                    configureCommonSpec(
                        spec = spec,
                        diagnostic = diagnostic,
                        contextualLabel = contextualLabel(diagnostic),
                        solution = solution(diagnostic),
                    )
                },
            )
        }
    }

    private fun reportWithLegacyApi(
        problems: Problems,
        diagnostics: List<StaticDiagnostic>,
        contextualLabel: (StaticDiagnostic) -> String,
        solution: (StaticDiagnostic) -> String,
        ruleDisplayName: (String) -> String,
    ) {
        val reporter = problems.javaClass.getMethod("forNamespace", String::class.java)
            .invoke(problems, PROBLEM_NAMESPACE)
        val actionClass = Class.forName("org.gradle.api.Action")
        val reportingMethod = reporter.javaClass.getMethod("reporting", actionClass)

        diagnostics.forEach { diagnostic ->
            reportingMethod.invoke(
                reporter,
                actionOf { spec ->
                    invokePublic(
                        target = spec,
                        methodName = "id",
                        parameterTypes = arrayOf(String::class.java, String::class.java),
                        diagnostic.rule,
                        ruleDisplayName(diagnostic.rule),
                    )
                    configureCommonSpec(
                        spec = spec,
                        diagnostic = diagnostic,
                        contextualLabel = contextualLabel(diagnostic),
                        solution = solution(diagnostic),
                    )
                },
            )
        }
    }

    private fun configureCommonSpec(
        spec: Any,
        diagnostic: StaticDiagnostic,
        contextualLabel: String,
        solution: String,
    ) {
        invokePublic(
            target = spec,
            methodName = "contextualLabel",
            parameterTypes = arrayOf(String::class.java),
            contextualLabel,
        )
        invokePublic(
            target = spec,
            methodName = "details",
            parameterTypes = arrayOf(String::class.java),
            diagnostic.message,
        )
        invokePublic(
            target = spec,
            methodName = "severity",
            parameterTypes = arrayOf(resolveSeverityClass()),
            severityValue(diagnostic.severity),
        )
        invokePublic(
            target = spec,
            methodName = "solution",
            parameterTypes = arrayOf(String::class.java),
            solution,
        )
        when {
            !diagnostic.sourcePath.isNullOrBlank() -> {
                val sourcePath = diagnostic.sourcePath.orEmpty()
                invokePublic(
                    target = spec,
                    methodName = "lineInFileLocation",
                    parameterTypes = arrayOf(String::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
                    sourcePath,
                    diagnostic.sourceLine ?: 1,
                    diagnostic.sourceColumn ?: 1,
                )
            }

            !diagnostic.sourceFile.isNullOrBlank() -> {
                val sourceFile = diagnostic.sourceFile.orEmpty()
                invokePublic(
                    target = spec,
                    methodName = "fileLocation",
                    parameterTypes = arrayOf(String::class.java),
                    sourceFile,
                )
            }
        }
    }

    private fun resolveSeverityClass(): Class<*> {
        return Class.forName("org.gradle.api.problems.Severity")
    }

    private fun severityValue(severity: DiagnosticSeverity): Any {
        return resolveSeverityClass().enumConstants.first { (it as Enum<*>).name == severity.name }
    }

    private fun invokePublic(
        target: Any,
        methodName: String,
        parameterTypes: Array<Class<*>>,
        vararg arguments: Any,
    ): Any? {
        return target.javaClass.getMethod(methodName, *parameterTypes).invoke(target, *arguments)
    }

    private fun actionOf(block: (Any) -> Unit): Action<Any> {
        return Action { target -> block(target) }
    }

    private fun hasMethod(target: Any, methodName: String, vararg parameterTypes: Class<*>): Boolean {
        return runCatching {
            target.javaClass.getMethod(methodName, *parameterTypes)
        }.isSuccess
    }
}