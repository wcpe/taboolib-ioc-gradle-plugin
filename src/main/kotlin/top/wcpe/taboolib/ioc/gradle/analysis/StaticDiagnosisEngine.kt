package top.wcpe.taboolib.ioc.gradle.analysis

internal object StaticDiagnosisEngine {

    fun analyze(projectPath: String, index: BytecodeAnalysisIndex): StaticAnalysisReport {
        val hierarchy = TypeHierarchy(index.classIndex)
        val diagnostics = index.injectionPointIndex.flatMap { injectionPoint ->
            analyzeInjectionPoint(injectionPoint, index.beanIndex, index.componentScans, hierarchy)
        }.sortedWith(compareBy({ it.severity.name }, { it.ownerClassName }, { it.declarationName }, { it.rule }))

        return StaticAnalysisReport(
            projectPath = projectPath,
            beanIndex = index.beanIndex,
            injectionPointIndex = index.injectionPointIndex,
            componentScans = index.componentScans,
            diagnostics = diagnostics,
        )
    }

    private fun analyzeInjectionPoint(
        injectionPoint: InjectionPointDefinition,
        beans: List<BeanDefinition>,
        componentScans: List<ComponentScanDefinition>,
        hierarchy: TypeHierarchy,
    ): List<StaticDiagnostic> {
        val assignableCandidates = beans.filter { hierarchy.isAssignable(it.exposedType, injectionPoint.dependencyType) }
        val inScanCandidates = applyComponentScan(assignableCandidates, componentScans)
        val unconditionalCandidates = inScanCandidates.filter { it.conditionalAnnotations.isEmpty() }
        val conditionalCandidates = inScanCandidates.filter { it.conditionalAnnotations.isNotEmpty() }

        val qualifierName = injectionPoint.qualifierName
        if (qualifierName != null) {
            val namedCandidates = beans.filter { it.beanName == qualifierName }
            if (namedCandidates.isEmpty()) {
                return listOf(
                    diagnostic(
                        severity = DiagnosticSeverity.ERROR,
                        rule = "named-bean-not-found",
                        injectionPoint = injectionPoint,
                        message = "限定名称 '$qualifierName' 对应的 Bean 不存在。",
                    ),
                )
            }

            val namedTypedCandidates = namedCandidates.filter { hierarchy.isAssignable(it.exposedType, injectionPoint.dependencyType) }
            if (namedTypedCandidates.isEmpty()) {
                return listOf(
                    diagnostic(
                        severity = DiagnosticSeverity.ERROR,
                        rule = "named-bean-type-mismatch",
                        injectionPoint = injectionPoint,
                        message = "限定名称 '$qualifierName' 对应的 Bean 类型与依赖 ${injectionPoint.dependencyType} 不兼容。",
                        candidateBeans = namedCandidates.map { it.beanName },
                    ),
                )
            }

            val namedInScanCandidates = applyComponentScan(namedTypedCandidates, componentScans)
            if (namedInScanCandidates.isEmpty()) {
                return listOf(
                    diagnostic(
                        severity = DiagnosticSeverity.WARNING,
                        rule = "component-scan-may-exclude",
                        injectionPoint = injectionPoint,
                        message = "限定名称 '$qualifierName' 的候选 Bean 可能被 @ComponentScan 排除。",
                        candidateBeans = namedTypedCandidates.map { it.beanName },
                    ),
                )
            }

            val namedUnconditionalCandidates = namedInScanCandidates.filter { it.conditionalAnnotations.isEmpty() }
            if (namedUnconditionalCandidates.isEmpty()) {
                return listOf(
                    diagnostic(
                        severity = DiagnosticSeverity.WARNING,
                        rule = "conditional-bean-only",
                        injectionPoint = injectionPoint,
                        message = "限定名称 '$qualifierName' 的依赖当前只能由条件 Bean 满足。",
                        candidateBeans = namedInScanCandidates.map { it.beanName },
                    ),
                )
            }

            return emptyList()
        }

        if (unconditionalCandidates.isEmpty()) {
            return when {
                conditionalCandidates.isNotEmpty() -> listOf(
                    diagnostic(
                        severity = DiagnosticSeverity.WARNING,
                        rule = "conditional-bean-only",
                        injectionPoint = injectionPoint,
                        message = "依赖 ${injectionPoint.dependencyType} 当前只能由条件 Bean 满足。",
                        candidateBeans = conditionalCandidates.map { it.beanName },
                    ),
                )

                assignableCandidates.isNotEmpty() -> listOf(
                    diagnostic(
                        severity = DiagnosticSeverity.WARNING,
                        rule = "component-scan-may-exclude",
                        injectionPoint = injectionPoint,
                        message = "依赖 ${injectionPoint.dependencyType} 的候选 Bean 可能被 @ComponentScan 排除。",
                        candidateBeans = assignableCandidates.map { it.beanName },
                    ),
                )

                !injectionPoint.required -> listOf(
                    diagnostic(
                        severity = DiagnosticSeverity.WARNING,
                        rule = "runtime-manual-bean-only",
                        injectionPoint = injectionPoint,
                        message = "依赖 ${injectionPoint.dependencyType} 只能靠运行时手动 Bean 补足。",
                    ),
                )

                else -> listOf(
                    diagnostic(
                        severity = DiagnosticSeverity.ERROR,
                        rule = "missing-bean",
                        injectionPoint = injectionPoint,
                        message = "缺少可满足依赖 ${injectionPoint.dependencyType} 的 Bean。",
                    ),
                )
            }
        }

        val primaryCandidates = unconditionalCandidates.filter { it.primary }
        if (primaryCandidates.size > 1) {
            return listOf(
                diagnostic(
                    severity = DiagnosticSeverity.ERROR,
                    rule = "multiple-primary-beans",
                    injectionPoint = injectionPoint,
                    message = "依赖 ${injectionPoint.dependencyType} 存在多个 @Primary 候选。",
                    candidateBeans = primaryCandidates.map { it.beanName },
                ),
            )
        }

        if (unconditionalCandidates.size > 1 && primaryCandidates.isEmpty()) {
            return listOf(
                diagnostic(
                    severity = DiagnosticSeverity.WARNING,
                    rule = "multiple-candidates-unqualified",
                    injectionPoint = injectionPoint,
                    message = "依赖 ${injectionPoint.dependencyType} 存在多个候选 Bean，但当前注入点未限定名称。",
                    candidateBeans = unconditionalCandidates.map { it.beanName },
                ),
            )
        }

        return emptyList()
    }

    private fun applyComponentScan(
        candidates: List<BeanDefinition>,
        componentScans: List<ComponentScanDefinition>,
    ): List<BeanDefinition> {
        if (componentScans.isEmpty()) {
            return candidates
        }
        val basePackages = componentScans.flatMap { it.basePackages }.distinct()
        if (basePackages.isEmpty()) {
            return candidates
        }
        return candidates.filter { bean ->
            basePackages.any { basePackage -> bean.packageName == basePackage || bean.packageName.startsWith("$basePackage.") }
        }
    }

    private fun diagnostic(
        severity: DiagnosticSeverity,
        rule: String,
        injectionPoint: InjectionPointDefinition,
        message: String,
        candidateBeans: List<String> = emptyList(),
    ): StaticDiagnostic {
        return StaticDiagnostic(
            severity = severity,
            rule = rule,
            ownerClassName = injectionPoint.ownerClassName,
            declarationName = injectionPoint.declarationName,
            dependencyType = injectionPoint.dependencyType,
            message = message,
            candidateBeans = candidateBeans.distinct().sorted(),
        )
    }

    private class TypeHierarchy(classEntries: List<ClassIndexEntry>) {

        private val index = classEntries.associateBy { it.className }

        fun isAssignable(candidateType: String, dependencyType: String): Boolean {
            if (candidateType == dependencyType) {
                return true
            }
            val visited = mutableSetOf<String>()
            val queue = ArrayDeque<String>()
            queue.add(candidateType)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                if (!visited.add(current)) {
                    continue
                }
                if (current == dependencyType) {
                    return true
                }
                val entry = index[current] ?: continue
                entry.superClassName?.let(queue::addLast)
                entry.interfaceNames.forEach(queue::addLast)
            }
            return false
        }
    }
}