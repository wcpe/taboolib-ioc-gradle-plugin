package top.wcpe.taboolib.ioc.gradle.analysis

internal object StaticDiagnosisEngine {

    fun analyze(
        projectPath: String,
        index: BytecodeAnalysisIndex,
        typeAliases: List<TypeAliasDefinition> = emptyList(),
        projectProperties: Map<String, String> = emptyMap(),
    ): StaticAnalysisReport {
        val hierarchy = TypeHierarchy(index.classIndex)
        val beanConditionStates = index.beanIndex.associateWith { bean ->
            evaluateConditions(bean, index.beanIndex, hierarchy, projectProperties)
        }
        val diagnostics = (
            index.injectionPointIndex.flatMap { injectionPoint ->
                analyzeInjectionPoint(injectionPoint, index.beanIndex, index.componentScans, hierarchy, beanConditionStates)
            } + index.missingInjectCandidateIndex.flatMap { candidate ->
                analyzeMissingInjectCandidate(candidate, index.beanIndex, index.componentBeanTypes, hierarchy)
            }
            ).sortedWith(compareBy({ it.severity.name }, { it.ownerClassName }, { it.declarationName }, { it.rule }))

        return StaticAnalysisReport(
            projectPath = projectPath,
            beanIndex = index.beanIndex,
            injectionPointIndex = index.injectionPointIndex,
            componentScans = index.componentScans,
            typeAliasIndex = typeAliases,
            diagnostics = diagnostics,
        )
    }

    private fun analyzeInjectionPoint(
        injectionPoint: InjectionPointDefinition,
        beans: List<BeanDefinition>,
        componentScans: List<ComponentScanDefinition>,
        hierarchy: TypeHierarchy,
        beanConditionStates: Map<BeanDefinition, ConditionEvaluationState>,
    ): List<StaticDiagnostic> {
        val assignableCandidates = beans.filter {
            hierarchy.isAssignable(it.exposedType, injectionPoint.dependencyType) && hierarchy.isGenericMatch(it, injectionPoint)
        }
        val inScanCandidates = applyComponentScan(assignableCandidates, componentScans)
        val activeCandidates = inScanCandidates.filter { beanConditionStates[it] == ConditionEvaluationState.ENABLED }
        val unknownConditionCandidates = inScanCandidates.filter { beanConditionStates[it] == ConditionEvaluationState.UNKNOWN }
        val disabledConditionCandidates = inScanCandidates.filter { beanConditionStates[it] == ConditionEvaluationState.DISABLED }

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

            val namedTypedCandidates = namedCandidates.filter {
                hierarchy.isAssignable(it.exposedType, injectionPoint.dependencyType) && hierarchy.isGenericMatch(it, injectionPoint)
            }
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

            val namedActiveCandidates = namedInScanCandidates.filter { beanConditionStates[it] == ConditionEvaluationState.ENABLED }
            if (namedActiveCandidates.isEmpty()) {
                val namedUnknownCandidates = namedInScanCandidates.filter { beanConditionStates[it] == ConditionEvaluationState.UNKNOWN }
                if (namedUnknownCandidates.isNotEmpty()) {
                    return listOf(
                        diagnostic(
                            severity = DiagnosticSeverity.WARNING,
                            rule = "conditional-bean-only",
                            injectionPoint = injectionPoint,
                            message = "限定名称 '$qualifierName' 的依赖当前只能由条件 Bean 满足，但条件无法被静态完全判定。",
                            candidateBeans = namedUnknownCandidates.map { it.beanName },
                        ),
                    )
                }
                val namedDisabledCandidates = namedInScanCandidates.filter { beanConditionStates[it] == ConditionEvaluationState.DISABLED }
                if (namedDisabledCandidates.isNotEmpty()) {
                    return listOf(
                        diagnostic(
                            severity = DiagnosticSeverity.ERROR,
                            rule = "missing-bean",
                            injectionPoint = injectionPoint,
                            message = "限定名称 '$qualifierName' 的 Bean 存在，但其条件在当前构建下不满足，依赖 ${injectionPoint.dependencyType} 仍然缺失。",
                            candidateBeans = namedDisabledCandidates.map { it.beanName },
                        ),
                    )
                }
            }

            return emptyList()
        }

        if (activeCandidates.isEmpty()) {
            return when {
                unknownConditionCandidates.isNotEmpty() -> listOf(
                    diagnostic(
                        severity = DiagnosticSeverity.WARNING,
                        rule = "conditional-bean-only",
                        injectionPoint = injectionPoint,
                        message = "依赖 ${injectionPoint.dependencyType} 当前只能由条件 Bean 满足，但条件无法被静态完全判定。",
                        candidateBeans = unknownConditionCandidates.map { it.beanName },
                    ),
                )

                disabledConditionCandidates.isNotEmpty() -> listOf(
                    diagnostic(
                        severity = DiagnosticSeverity.ERROR,
                        rule = "missing-bean",
                        injectionPoint = injectionPoint,
                        message = "依赖 ${injectionPoint.dependencyType} 存在条件 Bean 候选，但这些条件在当前构建下均不满足，因此依赖仍然缺失。",
                        candidateBeans = disabledConditionCandidates.map { it.beanName },
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

        val primaryCandidates = activeCandidates.filter { it.primary }
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

        if (activeCandidates.size > 1 && primaryCandidates.isEmpty()) {
            return listOf(
                diagnostic(
                    severity = DiagnosticSeverity.WARNING,
                    rule = "multiple-candidates-unqualified",
                    injectionPoint = injectionPoint,
                    message = "依赖 ${injectionPoint.dependencyType} 存在多个候选 Bean，但当前注入点未限定名称。",
                    candidateBeans = activeCandidates.map { it.beanName },
                ),
            )
        }

        return emptyList()
    }

    private fun analyzeMissingInjectCandidate(
        candidate: InjectionPointDefinition,
        beans: List<BeanDefinition>,
        componentBeanTypes: List<String>,
        hierarchy: TypeHierarchy,
    ): List<StaticDiagnostic> {
        val componentCandidates = beans.filter { bean ->
            bean.kind == BeanKind.CLASS &&
                bean.exposedType in componentBeanTypes &&
                hierarchy.isAssignable(bean.exposedType, candidate.dependencyType) &&
                hierarchy.isGenericMatch(bean, candidate)
        }
        if (componentCandidates.isEmpty()) {
            return emptyList()
        }
        return listOf(
            diagnostic(
                severity = DiagnosticSeverity.ERROR,
                rule = "missing-inject-annotation",
                injectionPoint = candidate,
                message = "字段 ${candidate.declarationName} 引用了可注入的 @Component Bean 类型 ${candidate.dependencyType}，但未声明 @Inject 注解。",
                candidateBeans = componentCandidates.map { it.beanName },
            ),
        )
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
            sourceFile = injectionPoint.sourceFile,
            sourcePath = injectionPoint.sourcePath,
            sourceLine = injectionPoint.sourceLine,
            sourceColumn = injectionPoint.sourceColumn,
            injectionPointKind = injectionPoint.kind,
            parameterIndex = injectionPoint.parameterIndex,
            dependencyType = injectionPoint.dependencyType,
            message = message,
            candidateBeans = candidateBeans.distinct().sorted(),
        )
    }

    private fun evaluateConditions(
        bean: BeanDefinition,
        beans: List<BeanDefinition>,
        hierarchy: TypeHierarchy,
        projectProperties: Map<String, String>,
    ): ConditionEvaluationState {
        if (bean.conditions.isEmpty()) {
            return ConditionEvaluationState.ENABLED
        }
        var unknown = false
        bean.conditions.forEach { condition ->
            when (evaluateCondition(condition, beans, hierarchy, projectProperties)) {
                ConditionEvaluationState.DISABLED -> return ConditionEvaluationState.DISABLED
                ConditionEvaluationState.UNKNOWN -> unknown = true
                ConditionEvaluationState.ENABLED -> Unit
            }
        }
        return if (unknown) ConditionEvaluationState.UNKNOWN else ConditionEvaluationState.ENABLED
    }

    private fun evaluateCondition(
        condition: ConditionDescriptor,
        beans: List<BeanDefinition>,
        hierarchy: TypeHierarchy,
        projectProperties: Map<String, String>,
    ): ConditionEvaluationState {
        return when (condition.annotationName) {
            "ConditionalOnProperty" -> evaluateConditionalOnProperty(condition, projectProperties)
            "ConditionalOnClass" -> evaluateConditionalOnClass(condition, hierarchy, negate = false)
            "ConditionalOnMissingClass" -> evaluateConditionalOnClass(condition, hierarchy, negate = true)
            "ConditionalOnBean" -> evaluateConditionalOnBean(condition, beans, hierarchy, negate = false)
            "ConditionalOnMissingBean" -> evaluateConditionalOnBean(condition, beans, hierarchy, negate = true)
            else -> ConditionEvaluationState.UNKNOWN
        }
    }

    private fun evaluateConditionalOnProperty(
        condition: ConditionDescriptor,
        projectProperties: Map<String, String>,
    ): ConditionEvaluationState {
        val propertyNames = attributeValues(condition.attributes, "name", "value")
        if (propertyNames.isEmpty()) {
            return ConditionEvaluationState.UNKNOWN
        }
        val havingValue = (condition.attributes["havingValue"] as? String)?.trim().orEmpty()
        val matchIfMissing = condition.attributes["matchIfMissing"] as? Boolean ?: false
        val satisfied = propertyNames.all { propertyName ->
            val propertyValue = projectProperties[propertyName]?.trim()
            when {
                propertyValue == null -> matchIfMissing
                havingValue.isNotEmpty() -> propertyValue == havingValue
                else -> !propertyValue.equals("false", ignoreCase = true)
            }
        }
        return if (satisfied) ConditionEvaluationState.ENABLED else ConditionEvaluationState.DISABLED
    }

    private fun evaluateConditionalOnClass(
        condition: ConditionDescriptor,
        hierarchy: TypeHierarchy,
        negate: Boolean,
    ): ConditionEvaluationState {
        val classNames = attributeValues(condition.attributes, "name", "value", "type")
        if (classNames.isEmpty()) {
            return ConditionEvaluationState.UNKNOWN
        }
        val present = classNames.all { hierarchy.hasClass(it) }
        return when {
            negate && !present -> ConditionEvaluationState.ENABLED
            negate && present -> ConditionEvaluationState.DISABLED
            !negate && present -> ConditionEvaluationState.ENABLED
            else -> ConditionEvaluationState.DISABLED
        }
    }

    private fun evaluateConditionalOnBean(
        condition: ConditionDescriptor,
        beans: List<BeanDefinition>,
        hierarchy: TypeHierarchy,
        negate: Boolean,
    ): ConditionEvaluationState {
        val beanNames = attributeValues(condition.attributes, "name", "beanName")
        val beanTypes = attributeValues(condition.attributes, "type", "value")
        if (beanNames.isEmpty() && beanTypes.isEmpty()) {
            return ConditionEvaluationState.UNKNOWN
        }
        val namesMatch = beanNames.all { expectedName -> beans.any { it.beanName == expectedName } }
        val typesMatch = beanTypes.all { expectedType ->
            beans.any { hierarchy.isAssignable(it.exposedType, expectedType) }
        }
        val matched = namesMatch && typesMatch
        return when {
            negate && !matched -> ConditionEvaluationState.ENABLED
            negate && matched -> ConditionEvaluationState.DISABLED
            !negate && matched -> ConditionEvaluationState.ENABLED
            else -> ConditionEvaluationState.DISABLED
        }
    }

    private fun attributeValues(attributes: Map<String, Any>, vararg keys: String): List<String> {
        return keys.asSequence()
            .mapNotNull { attributes[it] }
            .flatMap { value ->
                when (value) {
                    is String -> sequenceOf(value)
                    is Iterable<*> -> value.asSequence().filterIsInstance<String>()
                    else -> emptySequence()
                }
            }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    private class TypeHierarchy(classEntries: List<ClassIndexEntry>) {

        private val index = classEntries.associateBy { it.className }
        private val genericSuperTypeIndex = classEntries.associate { entry ->
            entry.className to entry.genericSuperTypes.map { normalizeTypeName(it) }
        }

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

        fun isGenericMatch(bean: BeanDefinition, injectionPoint: InjectionPointDefinition): Boolean {
            val dependencyGenericType = injectionPoint.dependencyGenericType?.let(::normalizeTypeName) ?: return true
            val candidateGenericTypes = buildList {
                bean.exposedGenericType?.let { add(normalizeTypeName(it)) }
                addAll(genericSuperTypeIndex[bean.exposedType].orEmpty())
            }
            if (candidateGenericTypes.isEmpty()) {
                return true
            }
            return candidateGenericTypes.any { it == dependencyGenericType }
        }

        fun hasClass(className: String): Boolean {
            return index.containsKey(className) || runCatching {
                Class.forName(className, false, StaticDiagnosisEngine::class.java.classLoader)
            }.isSuccess
        }

        private fun normalizeTypeName(typeName: String): String {
            return typeName.replace(" ", "")
        }
    }
}