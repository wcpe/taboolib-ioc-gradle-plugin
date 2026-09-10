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

    /**
     * B-P1-03（N6）：Gradle Problems API 既无 INFO 也无 ADVICE 时的 INFO 哨兵。
     * 收到哨兵表示「显式省略 severity 设置」，让该问题保持 Problems API 的默认级别，
     * 而不是把它伪装成 WARNING。
     */
    private val INFO_SEVERITY_OMITTED = Any()

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
                    logger = logger,
                    contextualLabel = contextualLabel,
                    solution = solution,
                    ruleDisplayName = ruleDisplayName,
                )

                hasMethod(problems, "forNamespace", String::class.java) -> reportWithLegacyApi(
                    problems = problems,
                    diagnostics = diagnostics,
                    logger = logger,
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
        logger: Logger,
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
            runCatching {
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
            }.onFailure { error ->
                // 逐条隔离：单条诊断上报失败（如 severity 映射、spec 字段不兼容）不得殃及整批
                logger.warn(
                    "[taboolibIoc] 单条诊断 Problems 上报失败（已跳过，不影响其他诊断）: rule=${diagnostic.rule}, " +
                        "owner=${diagnostic.ownerClassName}: ${error.javaClass.simpleName}: ${error.message}",
                )
            }
        }
    }

    private fun reportWithLegacyApi(
        problems: Problems,
        diagnostics: List<StaticDiagnostic>,
        logger: Logger,
        contextualLabel: (StaticDiagnostic) -> String,
        solution: (StaticDiagnostic) -> String,
        ruleDisplayName: (String) -> String,
    ) {
        val reporter = problems.javaClass.getMethod("forNamespace", String::class.java)
            .invoke(problems, PROBLEM_NAMESPACE)
        val actionClass = Class.forName("org.gradle.api.Action")
        val reportingMethod = reporter.javaClass.getMethod("reporting", actionClass)

        diagnostics.forEach { diagnostic ->
            runCatching {
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
            }.onFailure { error ->
                // 逐条隔离：单条诊断上报失败不得殃及整批
                logger.warn(
                    "[taboolibIoc] 单条诊断 Problems 上报失败（已跳过，不影响其他诊断）: rule=${diagnostic.rule}, " +
                        "owner=${diagnostic.ownerClassName}: ${error.javaClass.simpleName}: ${error.message}",
                )
            }
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
        val severity = severityValue(diagnostic.severity)
        if (severity !== INFO_SEVERITY_OMITTED) {
            invokePublic(
                target = spec,
                methodName = "severity",
                parameterTypes = arrayOf(resolveSeverityClass()),
                severity,
            )
        }
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
        val constants = resolveSeverityClass().enumConstants.filterIsInstance<Enum<*>>()
        // D1 修复：Gradle Problems API 的 Severity 枚举没有 INFO 值，
        // 此前按枚举名精确匹配会在遇到 INFO 诊断时抛 NoSuchElementException，
        // 连累整批诊断的 Problems 上报全部失效。
        //
        // B-P1-03（N6）：INFO 必须保持「最低级/信息级」，**不得拔高为 WARNING** ——
        // Gradle 8.6+ 用 ADVICE（信息级）；更老版本没有 ADVICE 时**不映射 severity**
        // （返回哨兵对象，见 configureCommonSpec 跳过 severity 调用），而不是退回 WARNING，
        // 否则真实的 INFO 规则（thread-scope-usage-warning）会在 IDE 展示面被伪装成警告。
        // 注意：这是展示面修复，门禁逻辑（AnalyzeTaboolibIocBeansTask 读原生 DiagnosticSeverity）不受影响。
        val candidateNames = when (severity) {
            DiagnosticSeverity.INFO -> listOf("INFO", "ADVICE")
            else -> listOf(severity.name)
        }
        return candidateNames.firstNotNullOfOrNull { name -> constants.firstOrNull { it.name == name } }
            ?: if (severity == DiagnosticSeverity.INFO) {
                INFO_SEVERITY_OMITTED
            } else {
                throw IllegalStateException(
                    "Gradle Problems API Severity 枚举中找不到可用值: 尝试过 $candidateNames，实际 ${constants.map { it.name }}",
                )
            }
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