package top.wcpe.taboolib.ioc.gradle.analysis

import java.lang.reflect.MalformedParameterizedTypeException
import top.wcpe.taboolib.ioc.gradle.weaving.ClassWeaveDecision
import top.wcpe.taboolib.ioc.gradle.weaving.WEAVING_MARKER_INTERFACES
import top.wcpe.taboolib.ioc.gradle.weaving.WeaveOutcome
import top.wcpe.taboolib.ioc.gradle.weaving.WeavePlan
import top.wcpe.taboolib.ioc.gradle.weaving.WeaveSkipReason

internal object StaticDiagnosisEngine {

    fun analyze(
        projectPath: String,
        index: BytecodeAnalysisIndex,
        typeAliases: List<TypeAliasDefinition> = emptyList(),
        projectProperties: Map<String, String> = emptyMap(),
        scanClassLoader: ClassLoader? = null,
        /**
         * 是否开启编译期 AOP 织入（`taboolibIoc { weaving }`）。
         *
         * 织入开启后，无接口的具体类也能被切面命中（`AopWeaver` 直接改写方法体），
         * 因此 AOP 静默失效规则组需要据此条件化，否则会对已开织入的用户产生误报。
         * 默认 false，保持既有构建行为不变。
         */
        weaving: Boolean = false,
        /**
         * 引擎自报的**织入计划**（事实），来自 `aop-weave-plan.json`。
         *
         * 抑制**只依据它**：`null`（缺失/解析失败/schema 不符）或 `weaving=false` → 保守不抑制
         * （与今天逐字一致），**绝不退回「预测引擎是否会织入」**（见 §2.3.4 / §2.3.6）。
         */
        weavePlan: WeavePlan? = null,
    ): StaticAnalysisReport {
        val hierarchy = TypeHierarchy(index.classIndex, scanClassLoader, typeAliases)
        val beanConditionStates = index.beanIndex.associateWith { bean ->
            evaluateConditions(bean, index.beanIndex, hierarchy, projectProperties)
        }
        val classEntryByClassName = index.classIndex.associateBy { it.className }
        val knownScopes = parseKnownScopes(projectProperties)
        val diagnostics = (
            index.injectionPointIndex.flatMap { injectionPoint ->
                analyzeInjectionPoint(injectionPoint, index.beanIndex, index.componentScans, hierarchy, beanConditionStates)
            } + index.missingInjectCandidateIndex.flatMap { candidate ->
                analyzeMissingInjectCandidate(candidate, index.beanIndex, index.componentBeanTypes, hierarchy)
            } + index.beanIndex.flatMap { bean ->
                analyzeRuntimeStability(bean, projectProperties) +
                analyzeRefreshScopeResources(bean, index.classIndex) +
                analyzeThreadScopeUsage(bean) +
                analyzeBeanStructure(bean, classEntryByClassName, knownScopes)
            } + analyzeCycleDependencies(index.beanIndex, index.injectionPointIndex, index.classIndex) +
            analyzeValueFields(index.valueFieldIndex) +
            analyzeAspects(index.aspectIndex) +
            analyzeDuplicateBeanNames(index.beanIndex) +
            analyzeAopSilentFailures(index.beanIndex, index.aspectIndex, classEntryByClassName, weaving, weavePlan)
            ).sortedWith(compareBy({ it.severity.name }, { it.ownerClassName }, { it.declarationName }, { it.rule }))

        return StaticAnalysisReport(
            projectPath = projectPath,
            beanIndex = index.beanIndex,
            injectionPointIndex = index.injectionPointIndex,
            componentScans = index.componentScans,
            typeAliasIndex = typeAliases,
            diagnostics = diagnostics,
            // 把抑制与降级一并带进报告：否则「结论为什么变了」在报告里无从追溯。
            sourceIndexDegradations = index.sourceIndexDegradations,
            suppressedMissingInjections = index.suppressedMissingInjections,
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
        // 仅类型可赋值、但泛型实参不匹配的候选。单独算一份是为了在报 missing-bean 时能说清
        // 「是泛型实参对不上」还是「压根没有候选」—— 两者的排查方向完全不同。
        val genericRejectedCandidates = applyComponentScan(
            beans.filter {
                hierarchy.isAssignable(it.exposedType, injectionPoint.dependencyType) &&
                    !hierarchy.isGenericMatch(it, injectionPoint)
            },
            componentScans,
        ).filter { beanConditionStates[it] == ConditionEvaluationState.ENABLED }
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
                    // 严重度刻意保持 ERROR，与 README 的「严重度对齐运行时行为」原则并不冲突：
                    // 运行期按**擦除**类型匹配、不读泛型（已核实 taboolib-ioc 全仓无泛型反射 API），
                    // 所以注入这一步确实会成功 —— 但注入进来的 Bean 与声明的类型实参不自洽，
                    // 任何依赖类型实参的使用点都会被编译器插入 checkcast，运行期抛 ClassCastException。
                    // 这属于「运行时失败」，不是「运行时静默降级」，因此不该降为 WARNING。
                    // 消息里必须写明这一点，否则用户看到「运行期能注入」会误以为可以忽略。
                    if (genericRejectedCandidates.isNotEmpty()) {
                        diagnostic(
                            severity = DiagnosticSeverity.ERROR,
                            rule = "missing-bean",
                            injectionPoint = injectionPoint,
                            message = "依赖 ${injectionPoint.dependencyType} 的类型实参与候选 Bean 的泛型实参不一致：" +
                                "运行期按擦除类型注入、注入本身会成功，但依赖类型实参的使用点会抛 ClassCastException。" +
                                "请核对注入点声明的类型实参与实际注册的 Bean。",
                            candidateBeans = genericRejectedCandidates.map { it.beanName },
                        )
                    } else {
                        diagnostic(
                            severity = DiagnosticSeverity.ERROR,
                            rule = "missing-bean",
                            injectionPoint = injectionPoint,
                            message = "缺少可满足依赖 ${injectionPoint.dependencyType} 的 Bean。",
                        )
                    },
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

    /**
     * D3 修复：与运行时 `top.wcpe.taboolib.ioc.bean.BeanScopes.normalize` 语义对齐
     * （trim + lowercase，空值回退 singleton）。静态侧必须用同一套语义比较 scope，
     * 否则 `@Scope("REFRESH")` 之类的写法会被漏判。
     */
    private fun normalizeScope(scope: String?): String {
        return scope?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "singleton"
    }

    private class TypeHierarchy(
        classEntries: List<ClassIndexEntry>,
        private val scanClassLoader: ClassLoader? = null,
        typeAliases: List<TypeAliasDefinition> = emptyList(),
    ) {

        private val index = classEntries.associateBy { it.className }
        private val genericSuperTypeIndex = classEntries.associate { entry ->
            entry.className to entry.genericSuperTypes.map { normalizeTypeName(it) }
        }

        /** A（性能）：按需补齐泛型父类型时的缓存，避免同一类型重复反射。 */
        private val lazyGenericSuperTypes = HashMap<String, List<String>>()

        /**
         * 取某类型的泛型父类/接口清单。
         *
         * 采集阶段只为 Bean 暴露类型预解析（见 BytecodeBeanIndexBuilder.enrichGenericMetadata），
         * 避免为上万个与 IoC 无关的依赖类付反射加载成本；其余类型若被后续规则查询到，
         * 这里用扫描类加载器按需补齐并缓存，结果与全量预解析一致。
         */
        private fun genericSuperTypesOf(typeName: String): List<String> {
            genericSuperTypeIndex[typeName]?.takeIf { it.isNotEmpty() }?.let { return it }
            val canonicalName = canonical(typeName)
            genericSuperTypeIndex[canonicalName]?.takeIf { it.isNotEmpty() }?.let { return it }
            return lazyGenericSuperTypes.getOrPut(canonicalName) { resolveGenericSuperTypes(canonicalName) }
        }

        private fun resolveGenericSuperTypes(className: String): List<String> {
            val classLoader = scanClassLoader ?: return emptyList()
            val clazz = try {
                Class.forName(className, false, classLoader)
            } catch (_: Throwable) {
                return emptyList()
            }
            // 泛型签名是惰性解析的：clazz.genericSuperclass / genericInterfaces 会在签名损坏时抛
            // GenericSignatureFormatError（LinkageError）/ MalformedParameterizedTypeException /
            // TypeNotPresentException。此前这两处裸露在 try 之外，异常会一路冒泡到 analyze 打断
            // 整个诊断任务；这里与采集侧 BytecodeBeanIndexBuilder.safelyResolveMetadata 对齐，
            // 失败时降级为「泛型父类型未知」，而不是让整个任务崩掉。
            return try {
                // 走与采集侧**同一个**传递闭包实现：两处各写一份「只取直接父类型」的版本，
                // 会让二层以上的参数化层级在字节码路径与反射回退路径上给出不同结论。
                BytecodeBeanIndexBuilder.collectGenericSuperTypes(clazz)
            } catch (_: LinkageError) {
                emptyList()
            } catch (_: TypeNotPresentException) {
                emptyList()
            } catch (_: MalformedParameterizedTypeException) {
                emptyList()
            }
        }

        /**
         * B-P1-04：typealias 归一化映射（aliasFqcn -> targetFqcn）。
         *
         * typealias 不影响字节码，运行时反射看到的永远是 target 类型；而静态采集到的
         * 源码级别名（如 `typealias MyServer = ServerApi`）只存在于源码索引。
         * 这里在类型解析入口统一归一化，使别名声明的依赖/候选类型能正确匹配。
         */
        private val aliasMap: Map<String, String> = typeAliases.associate { alias ->
            val aliasFqcn = aliasFqcn(alias)
            val rawTarget = normalizeTypeName(alias.targetType)
            val genericStart = rawTarget.indexOf('<')
            val targetFqcn = (if (genericStart >= 0) rawTarget.substring(0, genericStart) else rawTarget)
                .removeSuffix("...")
                .removeSuffix("[]")
                .trimEnd('?')
            aliasFqcn to targetFqcn
        }.filterValues { it.isNotEmpty() }.filterKeys { it.isNotEmpty() }

        private fun aliasFqcn(alias: TypeAliasDefinition): String {
            val name = alias.aliasName.trim()
            if (name.isEmpty()) return ""
            return when {
                name.contains('.') -> name
                alias.packageName.isNotEmpty() -> "${alias.packageName}.$name"
                else -> name
            }
        }

        /** 沿 alias 链解析直到不再是别名（带环保护），仅接受已知别名 */
        private fun resolveAlias(typeName: String): String {
            var current = normalizeTypeName(typeName)
            val guard = mutableSetOf<String>()
            while (current in aliasMap && guard.add(current)) {
                current = aliasMap.getValue(current)
            }
            return current
        }

        /** 剥离泛型实参/数组/通配符，得到裸类型名（数组维度在别名解析后再剥离，避免误伤别名） */
        private fun canonical(typeName: String): String {
            var name = normalizeTypeName(typeName)
            val genericStart = name.indexOf('<')
            if (genericStart >= 0) {
                name = name.substring(0, genericStart).trim()
            }
            name = name.removeSuffix("...")
            name = name.removeSuffix("[]")
            name = resolveAlias(name)
            // 别名解析完成后，若仍残留数组前缀/维度则剥离
            val arrayDepth = name.takeWhile { it == '[' }
            name = name.removePrefix(arrayDepth)
            name = name.trimEnd('?')
            return name
        }

        fun isAssignable(candidateType: String, dependencyType: String): Boolean {
            val resolvedCandidate = canonical(candidateType)
            val resolvedDependency = canonical(dependencyType)
            if (resolvedCandidate == resolvedDependency) {
                return true
            }
            val visited = mutableSetOf<String>()
            val queue = ArrayDeque<String>()
            queue.add(resolvedCandidate)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                if (!visited.add(current)) {
                    continue
                }
                if (current == resolvedDependency) {
                    return true
                }
                val entry = index[current] ?: continue
                entry.superClassName?.let { queue.addLast(canonical(it)) }
                entry.interfaceNames.forEach { queue.addLast(canonical(it)) }
            }
            return false
        }

        fun isGenericMatch(bean: BeanDefinition, injectionPoint: InjectionPointDefinition): Boolean {
            val dependencyGenericType = injectionPoint.dependencyGenericType?.let { normalizeTypeName(it) } ?: return true
            if (!hasConcreteTypeArgument(dependencyGenericType)) return true
            val resolvedDependencyGenericType = canonicalGeneric(dependencyGenericType)
            val candidateGenericTypes = buildList {
                bean.exposedGenericType?.let { add(normalizeTypeName(it)) }
                addAll(genericSuperTypesOf(bean.exposedType))
            }
            if (candidateGenericTypes.isEmpty()) {
                return true
            }
            return candidateGenericTypes.any { it == dependencyGenericType || canonicalGeneric(it) == resolvedDependencyGenericType }
        }

        /**
         * 依赖侧是否带有「具体类型实参」。
         *
         * 运行期 taboolib-ioc 只用擦除类型做 `Class.isAssignableFrom`，完全不读泛型信息
         * （已反编译发布制品确认 `getGenericType` / `getActualTypeArguments` 全仓零命中），
         * 因此 `Port`、`Port<?>`（Kotlin 星投影 `Port<*>` 经反射即为 `?`）这类**没有指定实参**
         * 的声明必须与裸类型同等放行；否则它们会与候选的 `Port<String>` 判不等而误报
         * missing-bean（ERROR，默认阻断构建）。
         *
         * 注意 `Port<? extends CharSequence>` 这类**带上界**的通配符不属于「无具体实参」：
         * 它携带了必须参与比较的边界信息（见 canonicalGenericArgument）。
         */
        private fun hasConcreteTypeArgument(genericType: String): Boolean {
            // 数组依赖（`Port<String>[]`）先剥掉数组维度，再判断其元素类型是否带具体实参
            var elementType = genericType
            while (elementType.endsWith("[]")) {
                elementType = elementType.removeSuffix("[]").trim()
            }
            val open = elementType.indexOf('<')
            if (open < 0 || !elementType.endsWith(">")) {
                return false
            }
            val args = splitTopLevel(elementType.substring(open + 1, elementType.length - 1))
            return args.any { it != "?" }
        }

        /**
         * 归一化泛型类型串：`java.util.List<MyAlias>` -> `java.util.List<fixture.ServerApi>`。
         *
         * 实参归一化必须**递归**。此前对嵌套实参只调 canonical() 抹平类型参数，
         * 于是 `Map<String, Port<Integer>>` 与 `Map<String, Port<String>>` 被判相等（漏报），
         * 而同层的 `Port<Long>` 与 `Port<String>` 却报 ERROR（口径矛盾）。
         */
        private fun canonicalGeneric(typeName: String): String {
            val compact = normalizeTypeName(typeName)
            val open = compact.indexOf('<')
            if (open < 0 || !compact.endsWith(">")) {
                return canonical(compact)
            }
            val raw = compact.substring(0, open)
            val args = compact.substring(open + 1, compact.length - 1)
            val normalizedArgs = splitTopLevel(args).joinToString(",") { canonicalGenericArgument(it) }
            return "${canonical(raw)}<$normalizedArgs>"
        }

        /**
         * 归一化单个类型实参：
         * - `?`（未指定通配符 / Kotlin 星投影）保留为 `?`，其放行由 hasConcreteTypeArgument 统一裁决；
         * - `? extends X` / `? super X` 按采集侧 BytecodeBeanIndexBuilder.normalizeTypeName 的口径
         *   退化为边界类型 X，使手工构造的源码串与反射富化产物（`Port<CharSequence>`）比较口径一致；
         * - 其余实参递归归一化，保留嵌套结构，让嵌套实参也参与比较。
         */
        private fun canonicalGenericArgument(argument: String): String {
            val compact = normalizeTypeName(argument)
            if (compact == "?") {
                return "?"
            }
            wildcardBound(compact)?.let { bound ->
                return if (bound.isEmpty()) "?" else canonicalGeneric(bound)
            }
            return canonicalGeneric(compact)
        }

        /** 取通配符 `? extends X` / `? super X` 的边界类型；非通配符返回 null，无边界通配符返回空串 */
        private fun wildcardBound(argument: String): String? {
            return when {
                argument.startsWith("?extends") -> argument.removePrefix("?extends").trim()
                argument.startsWith("?super") -> argument.removePrefix("?super").trim()
                else -> null
            }
        }

        private fun splitTopLevel(args: String): List<String> {
            val result = mutableListOf<String>()
            var depth = 0
            val current = StringBuilder()
            args.forEach { ch ->
                when (ch) {
                    '<' -> { depth++; current.append(ch) }
                    '>' -> { depth--; current.append(ch) }
                    ',' -> if (depth == 0) {
                        result += current.toString()
                        current.clear()
                    } else {
                        current.append(ch)
                    }
                    else -> current.append(ch)
                }
            }
            if (current.isNotEmpty()) {
                result += current.toString()
            }
            return result.map { it.trim() }.filter { it.isNotEmpty() }
        }

        /**
         * D2 修复：[ConditionalOnClass] 判定的类名指向的是**被扫描工程及其依赖**里的类，
         * 不是 Gradle 插件自身的类。此前用插件自身 ClassLoader 探测，
         * 除 JDK/系统类外必然 ClassNotFound → 条件被错误判为 DISABLED → 连锁误报 missing-bean。
         * 现在优先用扫描根（classDirectories + dependencyArtifacts）构建的 ClassLoader 探测，
         * 其 parent 为插件 ClassLoader，系统类仍可命中。
         *
         * B-P1-04：同时纳入 typealias 归一化 —— alias 名等价于其 target 是否可加载。
         */
        fun hasClass(className: String): Boolean {
            val resolved = canonical(className)
            if (index.containsKey(resolved) || index.containsKey(className)) {
                return true
            }
            val loader = scanClassLoader ?: return runCatching {
                Class.forName(resolved, false, StaticDiagnosisEngine::class.java.classLoader)
            }.isSuccess
            return runCatching { Class.forName(resolved, false, loader) }.isSuccess
        }

        private fun normalizeTypeName(typeName: String): String {
            return typeName.replace(" ", "")
        }
    }

    private fun analyzeRuntimeStability(
        bean: BeanDefinition,
        projectProperties: Map<String, String>,
    ): List<StaticDiagnostic> {
        if (bean.kind != BeanKind.CLASS) {
            return emptyList()
        }

        val diagnostics = mutableListOf<StaticDiagnostic>()
        val metadata = bean.constructorMetadata

        if (metadata != null) {
            // 只有在存在多个构造器时才有"容器可能选错"的风险
            // 单构造器场景下，容器会自动选择唯一的构造器进行注入
            val hasMultipleConstructors = metadata.totalConstructorCount > 1

            // Rule 1: bean-constructor-not-explicitly-injected
            // 只在多构造器场景下触发
            if (hasMultipleConstructors && metadata.runtimeSelectedConstructorHasParameters && !metadata.hasExplicitInjectConstructor) {
                diagnostics += StaticDiagnostic(
                    severity = DiagnosticSeverity.WARNING,
                    rule = "bean-constructor-not-explicitly-injected",
                    ownerClassName = bean.ownerClassName,
                    declarationName = bean.declarationName,
                    sourceFile = bean.sourceFile,
                    sourcePath = null,
                    sourceLine = null,
                    sourceColumn = null,
                    injectionPointKind = InjectionPointKind.CONSTRUCTOR_PARAMETER,
                    parameterIndex = null,
                    dependencyType = bean.exposedType,
                    message = "Bean 存在依赖型构造器，但未显式标注 @Inject constructor，可能在运行时被容器错误选构造或传入 null。",
                )
            }

            // Rule 2: bean-runtime-null-injection-risk
            // 只在多构造器场景下触发ERROR级别
            if (
                hasMultipleConstructors &&
                metadata.runtimeSelectedConstructorHasNonNullableParameters &&
                metadata.runtimeSelectedConstructorHasParameters &&
                !metadata.hasExplicitInjectConstructor
            ) {
                diagnostics += StaticDiagnostic(
                    severity = DiagnosticSeverity.ERROR,
                    rule = "bean-runtime-null-injection-risk",
                    ownerClassName = bean.ownerClassName,
                    declarationName = bean.declarationName,
                    sourceFile = bean.sourceFile,
                    sourcePath = null,
                    sourceLine = null,
                    sourceColumn = null,
                    injectionPointKind = InjectionPointKind.CONSTRUCTOR_PARAMETER,
                    parameterIndex = null,
                    dependencyType = bean.exposedType,
                    message = "静态依赖图可解析，但该 Kotlin 非空构造参数的注入入口不明确，存在运行时 NPE 风险。",
                )
            }
        }

        // Rule 3: forbidden-component-annotation
        // 只在显式启用时触发（通过项目属性 taboolib.ioc.forbidComponentAnnotation=true）
        val forbidComponent = projectProperties["taboolib.ioc.forbidComponentAnnotation"]?.equals("true", ignoreCase = true) == true
        if (forbidComponent && bean.stereotypeAnnotation == "Component") {
            diagnostics += StaticDiagnostic(
                severity = DiagnosticSeverity.WARNING,
                rule = "forbidden-component-annotation",
                ownerClassName = bean.ownerClassName,
                declarationName = bean.declarationName,
                sourceFile = bean.sourceFile,
                sourcePath = null,
                sourceLine = null,
                sourceColumn = null,
                injectionPointKind = InjectionPointKind.CONSTRUCTOR_PARAMETER,
                parameterIndex = null,
                dependencyType = bean.exposedType,
                message = "项目约定仅允许使用 @Service/@Repository/@Inject，检测到 @Component。",
            )
        }

        return diagnostics
    }

    private fun analyzeCycleDependencies(
        beans: List<BeanDefinition>,
        injectionPoints: List<InjectionPointDefinition>,
        classIndex: List<ClassIndexEntry>,
    ): List<StaticDiagnostic> {
        // K6 修复：把 classIndex 传入环检测器，使「依赖目标类型是否接口」可被静态精确判定，
        // 从而只对「接口类型 @Lazy」边断环（与运行时 LazyProxyFactory.canProxy(type) = type.isInterface 同源）。
        val cycles = CycleDependencyDetector.detectCycles(beans, injectionPoints, classIndex)
        return cycles.mapNotNull { cycle ->
            // resolvable=true 表示「运行时可由早期暴露解析」，或「环已被接口类型 @Lazy 代理断开」。
            // 两者运行时都不会失败，故静态降级为 WARNING（不触发 failOnError 阻断正确工程）。
            val severity = when {
                cycle.kind == CycleDependencyKind.CONSTRUCTOR -> DiagnosticSeverity.ERROR
                !cycle.resolvable -> DiagnosticSeverity.ERROR
                else -> DiagnosticSeverity.WARNING
            }

            val message = when {
                cycle.kind == CycleDependencyKind.CONSTRUCTOR ->
                    "检测到构造函数循环依赖，无法解析: ${cycle.path.joinToString(" -> ")}"
                cycle.resolvable ->
                    "检测到字段循环依赖，可由容器早期暴露解析（或已被 @Lazy 代理断开）: ${cycle.path.joinToString(" -> ")}"
                else ->
                    "检测到跨作用域循环依赖，无法解析: ${cycle.path.joinToString(" -> ")}"
            }

            // 环路径首节点理论上一定能对应到 Bean（路径由 Bean 名构成），但契约一旦变化，
            // 这里的 `!!` 会以 NPE 打断整个诊断任务；改为找不到就跳过这一条环诊断。
            val firstBean = beans.firstOrNull { it.beanName == cycle.path.first() } ?: return@mapNotNull null
            diagnostic(
                severity = severity,
                rule = "circular-dependency-detected",
                ownerClassName = firstBean.ownerClassName,
                declarationName = firstBean.declarationName,
                sourceFile = firstBean.sourceFile,
                message = message,
                candidateBeans = cycle.path,
            )
        }
    }

    private fun analyzeRefreshScopeResources(
        bean: BeanDefinition,
        classIndex: List<ClassIndexEntry>,
    ): List<StaticDiagnostic> {
        // D3 修复：scope 字符串必须与运行时 BeanScopes.normalize 语义一致（trim + lowercase），
        // 否则 @Scope("REFRESH") 之类的写法会被静态判定漏掉
        if (normalizeScope(bean.scope) != "refresh") return emptyList()

        val classEntry = classIndex.find { it.className == bean.ownerClassName } ?: return emptyList()
        val hasResourceFields = classEntry.fields.any { it.type in RESOURCE_TYPES }
        val hasPreDestroy = bean.lifecycleMethods.preDestroyMethods.isNotEmpty()

        if (hasResourceFields && !hasPreDestroy) {
            return listOf(
                diagnostic(
                    severity = DiagnosticSeverity.WARNING,
                    rule = "refresh-scope-missing-predestroy",
                    ownerClassName = bean.ownerClassName,
                    declarationName = bean.declarationName,
                    sourceFile = bean.sourceFile,
                    message = "@RefreshScope Bean 持有资源类型字段但缺少 @PreDestroy 方法，可能导致资源泄漏。",
                ),
            )
        }

        return emptyList()
    }

    private fun analyzeThreadScopeUsage(
        bean: BeanDefinition,
    ): List<StaticDiagnostic> {
        if (normalizeScope(bean.scope) != "thread") return emptyList()

        return listOf(
            diagnostic(
                severity = DiagnosticSeverity.INFO,
                rule = "thread-scope-usage-warning",
                ownerClassName = bean.ownerClassName,
                declarationName = bean.declarationName,
                sourceFile = bean.sourceFile,
                message = "@ThreadScope Bean 在线程池环境中需要手动调用 clearCurrentThread() 防止内存泄漏。",
            ),
        )
    }

    /**
     * P0 结构性规则组（对应构建前检查缺口报告 §5.2 序 1-6）：
     * 每一条命中都代表「运行时容器初始化失败 / Bean 创建失败」，且全部纯字节码可判定。
     */
    private fun analyzeBeanStructure(
        bean: BeanDefinition,
        classEntryByClassName: Map<String, ClassIndexEntry>,
        knownScopes: Set<String>,
    ): List<StaticDiagnostic> {
        val diagnostics = mutableListOf<StaticDiagnostic>()

        // ── unknown-bean-scope：运行时 LifecycleManager.validateScopes 对未注册作用域直接抛
        // IllegalStateException，容器初始化失败。白名单 = 标准与内置作用域 ∪ 项目声明的自定义作用域。
        val normalizedScope = normalizeScope(bean.scope)
        if (normalizedScope !in BUILTIN_SCOPES && normalizedScope !in knownScopes) {
            diagnostics += diagnostic(
                severity = DiagnosticSeverity.ERROR,
                rule = "unknown-bean-scope",
                ownerClassName = bean.ownerClassName,
                declarationName = bean.declarationName,
                sourceFile = bean.sourceFile,
                message = "Bean 声明了未注册的作用域 '${bean.scope}'，运行时容器初始化将直接失败。" +
                    "可用作用域: ${BUILTIN_SCOPES.joinToString()}, 或通过 taboolib.ioc.knownScopes 声明自定义作用域。",
            )
        }

        when (bean.kind) {
            BeanKind.CLASS -> {
                val classEntry = classEntryByClassName[bean.ownerClassName]

                // ── bean-type-not-instantiable：接口 / 抽象类 / 枚举无法 newInstance，
                // 运行时 ConstructorResolver 直接抛异常
                if (classEntry != null && (classEntry.isInterface || classEntry.isAbstract || classEntry.superClassName == "java.lang.Enum")) {
                    val kindLabel = when {
                        classEntry.isInterface -> "接口"
                        classEntry.superClassName == "java.lang.Enum" -> "枚举"
                        else -> "抽象类"
                    }
                    diagnostics += diagnostic(
                        severity = DiagnosticSeverity.ERROR,
                        rule = "bean-type-not-instantiable",
                        ownerClassName = bean.ownerClassName,
                        declarationName = bean.declarationName,
                        sourceFile = bean.sourceFile,
                        message = "$kindLabel ${bean.ownerClassName} 被声明为组件 Bean，但容器无法实例化它。" +
                            "请移除组件注解，或改为具体类。",
                    )
                }

                // ── bean-no-resolvable-constructor：多个构造器、无 @Inject、无无参构造器时，
                // 运行时 ConstructorResolver 走到兜底分支抛 NoSuchMethodException，容器初始化失败。
                // （Kotlin @JvmOverloads / 手写重载都会产生多个真实构造器）
                val metadata = bean.constructorMetadata
                if (metadata != null &&
                    metadata.totalConstructorCount > 1 &&
                    !metadata.hasExplicitInjectConstructor &&
                    !metadata.hasNoArgConstructor
                ) {
                    diagnostics += diagnostic(
                        severity = DiagnosticSeverity.ERROR,
                        rule = "bean-no-resolvable-constructor",
                        ownerClassName = bean.ownerClassName,
                        declarationName = bean.declarationName,
                        sourceFile = bean.sourceFile,
                        message = "Bean 存在 ${metadata.totalConstructorCount} 个构造器，但既无 @Inject 标注也无无参构造器，" +
                            "运行时容器无法确定使用哪个构造器，初始化将直接失败。" +
                            "请在目标构造器上标注 @Inject，或保留一个无参构造器。",
                    )
                }

                // ── lifecycle-method-signature-invalid：运行时 Injector 以零参硬调用生命周期方法，
                // 带参方法（含 Kotlin suspend 编译出的 Continuation 参数）会抛 IllegalArgumentException
                bean.lifecycleMethodDetails
                    .filter { it.parameterCount != 0 }
                    .forEach { detail ->
                        diagnostics += diagnostic(
                            severity = DiagnosticSeverity.ERROR,
                            rule = "lifecycle-method-signature-invalid",
                            ownerClassName = bean.ownerClassName,
                            declarationName = detail.methodName,
                            sourceFile = bean.sourceFile,
                            message = "@${detail.annotation} 方法 ${detail.methodName} 声明了 ${detail.parameterCount} 个参数，" +
                                "但运行时以零参反射调用，该 Bean 的创建将直接失败。请改为无参方法。",
                        )
                    }
            }

            BeanKind.FACTORY_METHOD -> {
                // ── bean-method-outside-configuration：运行时只在 @Configuration 分支扫描 @Bean 方法，
                // 其他宿主上的 @Bean 方法永远不会被注册，且会掩盖真实的 missing-bean
                if (bean.factoryHostIsConfiguration == false) {
                    diagnostics += diagnostic(
                        severity = DiagnosticSeverity.ERROR,
                        rule = "bean-method-outside-configuration",
                        ownerClassName = bean.ownerClassName,
                        declarationName = bean.declarationName,
                        sourceFile = bean.sourceFile,
                        message = "@Bean 方法 ${bean.declarationName} 声明在非 @Configuration 类 ${bean.ownerClassName} 上，" +
                            "运行时永远不会注册该 Bean。请将方法移入 @Configuration 类，或改用组件注解标注返回类型。",
                    )
                }

                // ── bean-method-void-return：运行时 require(returnType != Void.TYPE) 直接抛
                // IllegalArgumentException，容器初始化失败（Kotlin Unit 方法编译为 void）
                if (bean.factoryMethodReturnsVoid) {
                    diagnostics += diagnostic(
                        severity = DiagnosticSeverity.ERROR,
                        rule = "bean-method-void-return",
                        ownerClassName = bean.ownerClassName,
                        declarationName = bean.declarationName,
                        sourceFile = bean.sourceFile,
                        message = "@Bean 方法 ${bean.declarationName} 返回 void（Kotlin Unit 同样编译为 void），" +
                            "运行时容器初始化将直接失败。@Bean 方法必须返回非 void 类型。",
                    )
                }
            }
        }

        return diagnostics
    }

    /**
     * P0 C 组规则：@Value 表达式与目标类型（对应缺口报告序 7-8）。
     * 运行时失败模式均为「静默失败」：表达式被当字面量注入，或类型转换失败后字段保持 null/0/false。
     */
    private fun analyzeValueFields(valueFields: List<ValueFieldDefinition>): List<StaticDiagnostic> {
        return valueFields.flatMap { field ->
            buildList {
                // value-expression-unresolved-placeholder：运行时 resolveExpression 用
                // PLACEHOLDER_REGEX.matchEntire —— 只有整串恰为一个 ${...} 才走占位符解析，
                // 拼了前缀/后缀的表达式会被原样注入为字面量
                if (field.expression.contains("\${") && !PLACEHOLDER_ENTIRE_REGEX.matches(field.expression)) {
                    add(
                        diagnostic(
                            severity = DiagnosticSeverity.ERROR,
                            rule = "value-expression-unresolved-placeholder",
                            ownerClassName = field.ownerClassName,
                            declarationName = field.fieldName,
                            sourceFile = field.sourceFile,
                            message = "@Value 表达式 \"${field.expression}\" 包含 \${...} 占位符但不是整串单一占位符，" +
                                "运行时会整串作为字面量注入（占位符不会被解析）。" +
                                "请改为整串单一占位符，或去掉 \${...} 改用纯字面量。",
                        ),
                    )
                }

                // value-type-unsupported：运行时 convertType 走 else 分支只打 warning 并返回 null，
                // Kotlin 非空字段会静默保持默认值
                if (field.targetType !in SUPPORTED_VALUE_TYPES) {
                    add(
                        diagnostic(
                            severity = DiagnosticSeverity.ERROR,
                            rule = "value-type-unsupported",
                            ownerClassName = field.ownerClassName,
                            declarationName = field.fieldName,
                            sourceFile = field.sourceFile,
                            message = "@Value 字段 ${field.fieldName} 的目标类型 ${field.targetType} 不受支持，" +
                                "运行时转换失败后字段将静默保持默认值。仅支持 String 与基本类型及其包装类。",
                        ),
                    )
                }
            }
        }
    }

    /**
     * P0 E 组规则：pointcut-expression-invalid（对应缺口报告序 9）。
     * 运行时 AspectScanner 解析切点在容器初始化期执行，非法表达式会让插件 enable 直接失败。
     * 此处逐条复刻 PointcutExpression.parse 与 AspectScanner.resolveExpression 的判定。
     */
    private fun analyzeAspects(aspects: List<AspectDefinition>): List<StaticDiagnostic> {
        return aspects.flatMap { aspect ->
            aspect.advices.mapNotNull { advice ->
                val trimmed = advice.expression.trim()
                val failureReason = when {
                    // 无 . 无 ( → 运行时视为 @Pointcut 名称引用，未声明则原样进入 parse 后崩溃
                    !trimmed.contains('.') && !trimmed.contains('(') -> {
                        if (trimmed.isEmpty() || trimmed !in aspect.pointcutMethods) {
                            "表达式 '${advice.expression}' 不是合法切点，也不是本切面中已声明的 @Pointcut 名称"
                        } else {
                            null
                        }
                    }

                    else -> {
                        var expr = trimmed
                        if (expr.startsWith("execution(") && expr.endsWith(")")) {
                            expr = expr.substring(10, expr.length - 1).trim()
                        }
                        // 复刻 PointcutExpression.parse 的 require(lastDot > 0)
                        if (expr.lastIndexOf('.') <= 0) {
                            "切点表达式 '${advice.expression}' 语法非法，运行时解析将直接抛出异常"
                        } else {
                            null
                        }
                    }
                }
                failureReason?.let {
                    diagnostic(
                        severity = DiagnosticSeverity.ERROR,
                        rule = "pointcut-expression-invalid",
                        ownerClassName = aspect.aspectClassName,
                        declarationName = advice.methodName,
                        sourceFile = aspect.sourceFile,
                        message = "@${advice.adviceAnnotation} 通知方法 ${advice.methodName}: $it，" +
                            "容器初始化切面时将直接失败。合法格式: execution(类模式.方法模式) 或已声明的 @Pointcut 名称。",
                    )
                }
            }
        }
    }

    /**
     * P0 G 组规则：duplicate-bean-name（对应缺口报告序 10）。
     * 运行时 ComponentVisitor 遇到重名 Bean 静默跳过后者，谁先注册取决于 jar 内类迭代顺序，
     * 换构建环境结果可能翻转。
     */
    /**
     * K21：duplicate-bean-name。
     * 运行时 ComponentVisitor 遇到重名 Bean **静默跳过后者**（ComponentVisitor.kt:142 `contains` 跳过），
     * **不报错不告警**，插件仍能启动。因此静态严重度对齐为 WARNING（合法场景：同 FQCN 跨扫描根、
     * `@Bean(name=)` 覆盖、object 与 @Component 同名），避免 failOnError 误阻断。
     */
    private fun analyzeDuplicateBeanNames(beans: List<BeanDefinition>): List<StaticDiagnostic> {
        return beans.groupBy { it.beanName }
            .filter { it.value.size > 1 }
            .flatMap { (_, duplicates) ->
                val first = duplicates.first()
                listOf(
                    diagnostic(
                        severity = DiagnosticSeverity.WARNING,
                        rule = "duplicate-bean-name",
                        ownerClassName = first.ownerClassName,
                        declarationName = first.declarationName,
                        sourceFile = first.sourceFile,
                        message = "存在 ${duplicates.size} 个同名 Bean '${first.beanName}'，运行时先注册者胜出、" +
                            "其余被静默丢弃，且取决于 jar 内类顺序。请重命名或使用限定名称。重复定义: " +
                            duplicates.joinToString(", ") { it.ownerClassName },
                        candidateBeans = duplicates.map { it.beanName },
                    ),
                )
            }
    }

    private fun parseKnownScopes(projectProperties: Map<String, String>): Set<String> {
        return projectProperties["taboolib.ioc.knownScopes"]
            ?.split(',')
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()
    }

    // ==================== P1：AOP 静默失效规则组 ====================

    /** 切点目标类的切面通知（ advisor 索引的基本单元），表达式已解析并剥离 execution 外壳 */
    private data class ResolvedAdvice(
        val aspectClassName: String,
        val advice: AspectAdviceDefinition,
        val classPattern: String,
        val methodPattern: String,
    )

    /**
     * P1 AOP 规则组（对应缺口分析报告 E 组）。
     * 运行时 AopProxyFactory 只建 JDK 动态代理：无接口目标仅打 warning 后返回原实例，
     * 通知**永不执行**且不报错 —— 本组规则把这类静默失效提前到构建期。
     *
     * @param weaving 编译期 AOP 织入是否开启：
     *  - `aop-target-not-proxied` 的抑制**只依据 [weavePlan]（事实）**：`weaving=false` 或
     *    无计划 → 保守不抑制（与今天逐字一致）；`weaving=true` 且有计划 → 按 **ALL** 语义
     *    （命中的通知**全部**在计划中才抑制，否则报出未生效者）。**不做任何预测**。
     *  - `aop-factory-bean-interface-return` 降级为 INFO（保留提示，不阻断）；
     *  - **`aop-private/static-method-pointcut` 不随 `weaving` 变化**：`WeavingEligibility`
     *    显式要求 ACC_PUBLIC 且排除 ACC_STATIC，private/static 方法在织入下依然不可被命中，
     *    必须继续上报（否则「开了织入反而漏报死规则」）。
     * @param weavePlan 引擎自报的织入计划；`null` = 无计划（缺失/解析失败）→ 保守不抑制。
     */
    private fun analyzeAopSilentFailures(
        beans: List<BeanDefinition>,
        aspects: List<AspectDefinition>,
        classEntryByClassName: Map<String, ClassIndexEntry>,
        weaving: Boolean = false,
        weavePlan: WeavePlan? = null,
    ): List<StaticDiagnostic> {
        if (aspects.isEmpty()) {
            return emptyList()
        }
        val aspectClassNames = aspects.map { it.aspectClassName }.toSet()
        val advices = aspects.flatMap { aspect ->
            aspect.advices.mapNotNull { advice ->
                resolveAdviceExpression(aspect, advice)?.let { (classPattern, methodPattern) ->
                    ResolvedAdvice(aspect.aspectClassName, advice, classPattern, methodPattern)
                }
                // 解析失败的切点已由 pointcut-expression-invalid 上报，此处跳过
            }
        }
        if (advices.isEmpty()) {
            return emptyList()
        }

        val diagnostics = mutableListOf<StaticDiagnostic>()

        // ── advice-signature-invalid：运行时按通知类型区分失败模式 ——
        //  · @Around 签名错 → InterceptorChain 反射调用时**直接抛异常**，切面初始化失败 → ERROR；
        //  · @AfterReturning/@AfterThrowing 签名错 → 被 runCatching 吞掉、**静默失效**，不崩溃 → WARNING。
        // B-P1-02（K23）：静态严重度必须与运行时对齐，否则会把静默失效误报为阻断性 ERROR。
        aspects.forEach { aspect ->
            aspect.advices.forEach { advice ->
                val invalid = when (advice.adviceAnnotation) {
                    "Around" ->
                        advice.parameterTypes.size != 1 ||
                            advice.parameterTypes.singleOrNull() != METHOD_INVOCATION_TYPE

                    "AfterReturning", "AfterThrowing" -> advice.parameterTypes.size > 1

                    else -> null
                }
                if (invalid == true) {
                    val severity = when (advice.adviceAnnotation) {
                        "Around" -> DiagnosticSeverity.ERROR
                        // @AfterReturning/@AfterThrowing 签名错在运行时被静默吞掉，仅告警
                        else -> DiagnosticSeverity.WARNING
                    }
                    diagnostics += diagnostic(
                        severity = severity,
                        rule = "advice-signature-invalid",
                        ownerClassName = aspect.aspectClassName,
                        declarationName = advice.methodName,
                        sourceFile = aspect.sourceFile,
                        message = "@${advice.adviceAnnotation} 通知方法 ${advice.methodName} 的签名不合法" +
                            "（当前参数: ${advice.parameterTypes.ifEmpty { listOf("无") }.joinToString(", ")}），" +
                            if (advice.adviceAnnotation == "Around") {
                                "运行时切面初始化将直接失败。@Around 必须恰好声明 1 个 ${METHOD_INVOCATION_TYPE.substringAfterLast('.')} 参数。"
                            } else {
                                "运行时该通知将被静默跳过（不抛异常），通知永不执行。@${advice.adviceAnnotation} 只允许 0 或 1 个参数。"
                            },
                    )
                }
            }
        }

        // ── pointcut-target-not-found / aop-private-method-pointcut
        advices.forEach { advice ->
            val className = advice.classPattern
            if (className != "*" && !className.endsWith("..*")) {
                val targetEntry = classEntryByClassName.values.firstOrNull { entry ->
                    entry.className == className || entry.className.substringAfterLast('.') == className
                }
                if (targetEntry == null) {
                    diagnostics += diagnostic(
                        severity = DiagnosticSeverity.WARNING,
                        rule = "pointcut-target-not-found",
                        ownerClassName = advice.aspectClassName,
                        declarationName = advice.advice.methodName,
                        sourceFile = advice.aspectClassName.let { classEntryByClassName[it]?.sourceFile },
                        message = "切点目标类 '$className' 在当前扫描范围内不存在，该通知将永远匹配不到任何 Bean。",
                        candidateBeans = listOf(advice.methodPattern),
                    )
                } else if (advice.methodPattern != "*") {
                    val publicMatch = hierarchyHasMethod(targetEntry, advice.methodPattern, classEntryByClassName, privateOnly = false)
                    val privateMatch = hierarchyHasMethod(targetEntry, advice.methodPattern, classEntryByClassName, privateOnly = true)
                    // B-P1-01（K25）：static 方法在 getMethods() 可见，AdvisorRegistry 会命中并创建代理，
                    // 但 JDK 动态代理只分派**实例**方法 —— static 调用永不进入 handler，是纯静默死规则。
                    val instanceMatch = hierarchyHasInstanceMethod(targetEntry, advice.methodPattern, classEntryByClassName)
                    val staticMatch = hierarchyHasStaticMethod(targetEntry, advice.methodPattern, classEntryByClassName)
                    when {
                        !publicMatch && !privateMatch -> diagnostics += diagnostic(
                            severity = DiagnosticSeverity.WARNING,
                            rule = "pointcut-target-not-found",
                            ownerClassName = advice.aspectClassName,
                            declarationName = advice.advice.methodName,
                            sourceFile = classEntryByClassName[advice.aspectClassName]?.sourceFile,
                            message = "切点目标 ${targetEntry.className}.${advice.methodPattern} 不存在，该通知将永远匹配不到任何方法。",
                            candidateBeans = listOf(targetEntry.className),
                        )

                        // 运行时 AdvisorRegistry 只遍历 targetClass.methods（仅 public），
                        // JDK 代理也拦不到 private —— 只命中 private 的切点是静默死规则
                        !publicMatch && privateMatch -> diagnostics += diagnostic(
                            severity = DiagnosticSeverity.WARNING,
                            rule = "aop-private-method-pointcut",
                            ownerClassName = advice.aspectClassName,
                            declarationName = advice.advice.methodName,
                            sourceFile = classEntryByClassName[advice.aspectClassName]?.sourceFile,
                            message = "切点命中的方法 ${targetEntry.className}.${advice.methodPattern} 是 private，" +
                                "运行时 AdvisorRegistry 只匹配 public 方法且代理无法拦截私有方法，该通知永不执行。",
                            candidateBeans = listOf(targetEntry.className),
                        )

                        // K25：切点只命中 static 方法（无任何实例方法匹配）→ JDK 代理只分派实例方法，
                        // 该通知永不执行，属静默死规则。
                        !instanceMatch && staticMatch -> diagnostics += diagnostic(
                            severity = DiagnosticSeverity.WARNING,
                            rule = "aop-static-method-pointcut",
                            ownerClassName = advice.aspectClassName,
                            declarationName = advice.advice.methodName,
                            sourceFile = classEntryByClassName[advice.aspectClassName]?.sourceFile,
                            message = "切点命中的方法 ${targetEntry.className}.${advice.methodPattern} 是 static，" +
                                "JDK 动态代理只分派实例方法，static 调用永远不会进入通知处理器，该通知永不执行。",
                            candidateBeans = listOf(targetEntry.className),
                        )

                        else -> Unit
                    }
                }
            }
        }

        // ── aop-target-not-proxied / aop-factory-bean-interface-return
        //
        // 抑制来源 = **引擎自报的织入计划（事实）**，诊断侧**不做任何预测**（§2.3 / §7）。
        //  · `weaving=false` 或「无计划」（缺失 / 解析失败 / schema 不符）→ 保守不抑制，输出与 HEAD 逐字一致；
        //  · `weaving=true` 且有计划 → **ALL 语义**：命中的通知**全部**出现在计划的 `WOVEN` 条目中才抑制，
        //    否则只报出真正未生效者（`candidateBeans = unrealized`），并把原因精确到 @NoAspect / 继承未覆写 / 无织入资格。
        val planIndex: Map<String, ClassWeaveDecision> =
            if (weaving) weavePlan?.classes?.associateBy { it.className }.orEmpty() else emptyMap()
        val planAvailable = weaving && weavePlan != null

        beans.forEach { bean ->
            if (bean.ownerClassName in aspectClassNames) {
                return@forEach // 切面 Bean 自身不被代理
            }
            val matched = advices.filter { adviceMatchesBean(it, bean, classEntryByClassName) }
            if (matched.isEmpty()) {
                return@forEach
            }
            when (bean.kind) {
                BeanKind.CLASS -> {
                    if (collectExposedInterfaces(bean, classEntryByClassName).isEmpty()) {
                        if (!planAvailable) {
                            // weaving=false 或「无计划」（缺失 / 解析失败 / schema 不符）：保守不抑制。
                            // **基线 message 与 HEAD 逐字一致**，`weaving=false` 一个字符都不加；
                            // **仅当 `weaving=true`**（即「已开启织入却未产出可用计划」这一**异常**）时，
                            // 在基线后追加独立后缀，提示这是织入链路未跑通而非用户切点写法问题（§2.3.4 修正口径）。
                            val baselineMessage = "有 ${matched.size} 个切面通知命中 Bean ${bean.exposedType}，" +
                                "但该类没有实现任何接口，运行时 JDK 动态代理将跳过包装，通知永不执行。" +
                                "请为其抽取接口，或调整切点表达式。"
                            val message = if (weaving) {
                                baselineMessage + WEAVE_PLAN_MISSING_SUFFIX
                            } else {
                                baselineMessage
                            }
                            diagnostics += diagnostic(
                                severity = DiagnosticSeverity.WARNING,
                                rule = "aop-target-not-proxied",
                                ownerClassName = bean.ownerClassName,
                                declarationName = bean.declarationName,
                                sourceFile = bean.sourceFile,
                                message = message,
                                candidateBeans = matched.map { "${it.aspectClassName}#${it.advice.methodName}" },
                            )
                        } else {
                            val planEntry = planIndex[bean.exposedType]
                            // 残渣态（`WOVEN` + `alreadyWoven=true`）：计划只能扫 `*$ioc$original` 合成方法，
                            // 重匹配可能为空并落入哨兵，因此**不能**直接读 `matchedAdvices`（会欠计 → 假阳性）。
                            // 但同样**不能**把该 bean 命中的通知一律当成已实现：方法级 `@NoAspect`、父类声明
                            // 子类未覆写这类「永远不会被织入」的方法所命中的通知会被一起压掉，而它们的可见性
                            // 还会随构建历史漂移（源码未改，第一次构建报、第二次构建不报）——那等于把本类
                            // 「读计划事实、结论可复现」的立论推翻。
                            // 正解：按计划里**真实被转发的方法名**求交，只有命中这些方法的通知才算已实现。
                            val realized: Set<String> = when {
                                planEntry == null -> emptySet()
                                planEntry.outcome == WeaveOutcome.WOVEN && planEntry.alreadyWoven -> {
                                    // 计划只能报出「确实被转发过的方法名」，求交只认这些名字：
                                    // - 不允许 `*` 通配短路。forwarded 为空即哨兵态（计划无法确定织入了哪些
                                    //   方法），此时任何通知都**无法证明**已实现，必须保守上报；旧写法在这里
                                    //   无条件放行 `*`，把「无法确定」退化成「整类抑制」，正好与上面的取舍相反。
                                    // - 不再并上计划的 matchedAdvices。那是第二套匹配权威：它由计划侧用同一
                                    //   matcher 重匹配得到，键集与左侧过滤器恒等，当前是死代码；一旦 matcher
                                    //   将来扩展（前缀通配等），两边会静默分叉成过度抑制通道且没有测试会变红。
                                    val forwarded = planEntry.wovenMethods.map { it.methodName }.toSet()
                                    if (forwarded.isEmpty()) {
                                        emptySet()
                                    } else {
                                        // `*` 同样不能放行：它匹配该类的**每一个**方法，而计划只转发了
                                        // 其中一部分（forwarded 是残渣里真实存在的合成方法名）。只要有一个
                                        // 被 `*` 命中的方法没被转发，这条通知就没有被完整实现 —— 例如新增的
                                        // execution 全通配切点会命中 load()，而残渣里从未转发过它。
                                        // 按保守方向（宁可多报）：`*` 一律不计入已实现。
                                        matched.filter { it.methodPattern != "*" && it.methodPattern in forwarded }
                                            .map { "${it.aspectClassName}#${it.advice.methodName}" }
                                            .toSet()
                                    }
                                }

                                else -> planEntry.wovenMethods.flatMap { it.matchedAdvices }.toSet()
                            }
                            // ALL 语义：只要有任一条命中通知未被织入，就报告那条通知。
                            val unrealized = matched.filter { advice ->
                                "${advice.aspectClassName}#${advice.advice.methodName}" !in realized
                            }
                            if (unrealized.isNotEmpty()) {
                                val unrealizedKeys = unrealized
                                    .map { "${it.aspectClassName}#${it.advice.methodName}" }
                                    .sorted()
                                diagnostics += diagnostic(
                                    severity = DiagnosticSeverity.WARNING,
                                    rule = "aop-target-not-proxied",
                                    ownerClassName = bean.ownerClassName,
                                    declarationName = bean.declarationName,
                                    sourceFile = bean.sourceFile,
                                    message = buildUnrealizedMessage(
                                        bean = bean,
                                        totalMatched = matched.size,
                                        unrealizedKeys = unrealizedKeys,
                                        unrealizedAdvices = unrealized,
                                        planEntry = planEntry,
                                        classEntryByClassName = classEntryByClassName,
                                    ),
                                    candidateBeans = unrealizedKeys,
                                )
                            }
                        }
                    }
                }

                BeanKind.FACTORY_METHOD -> {
                    val returnEntry = classEntryByClassName[bean.exposedType]
                    if (returnEntry?.isInterface == true) {
                        // 织入开启时降级为 INFO：实际返回的具体实现若在当前模块编译产物内会被织入，
                        // 仅当实现来自依赖 jar 时才真正失效，故保留提示但不阻断构建。
                        val severity = if (weaving) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING
                        val weaveHint = if (weaving) {
                            "（已开启织入：若实际返回的具体实现属于当前模块编译产物将被编译期织入，此处仅作提示。）"
                        } else {
                            "请把返回类型改为具体实现类。"
                        }
                        diagnostics += diagnostic(
                            severity = severity,
                            rule = "aop-factory-bean-interface-return",
                            ownerClassName = bean.ownerClassName,
                            declarationName = bean.declarationName,
                            sourceFile = bean.sourceFile,
                            message = "@Bean 方法 ${bean.declarationName} 声明的返回类型 ${bean.exposedType} 是接口，" +
                                "且有 ${matched.size} 个切面通知命中，但运行时按声明类型收集接口必为空，代理永不生效。" +
                                weaveHint,
                            candidateBeans = matched.map { "${it.aspectClassName}#${it.advice.methodName}" },
                        )
                    }
                }
            }
        }

        return diagnostics
    }

    /** 复刻 AspectScanner.resolveExpression + PointcutExpression.parse 的表达式解析，失败返回 null（已另行上报） */
    private fun resolveAdviceExpression(aspect: AspectDefinition, advice: AspectAdviceDefinition): Pair<String, String>? {
        val trimmed = advice.expression.trim()
        val resolved = if (!trimmed.contains('.') && !trimmed.contains('(')) {
            aspect.pointcutMethods[trimmed] ?: return null
        } else {
            trimmed
        }
        var expr = resolved
        if (expr.startsWith("execution(") && expr.endsWith(")")) {
            expr = expr.substring(10, expr.length - 1).trim()
        }
        val lastDot = expr.lastIndexOf('.')
        if (lastDot <= 0) {
            return null
        }
        return expr.substring(0, lastDot) to expr.substring(lastDot + 1)
    }

    /** 复刻 PointcutExpression.matchesClass */
    private fun pointcutClassMatches(classPattern: String, className: String): Boolean {
        return when {
            classPattern == "*" -> true
            classPattern.endsWith("..*") -> className.startsWith(classPattern.dropLast(3))
            else -> className == classPattern || className.substringAfterLast('.') == classPattern
        }
    }

    /** 复刻 AdvisorRegistry.findMatchingAdvisors 的「类匹配 + public 方法名匹配」判定 */
    private fun adviceMatchesBean(
        advice: ResolvedAdvice,
        bean: BeanDefinition,
        classEntryByClassName: Map<String, ClassIndexEntry>,
    ): Boolean {
        val entry = classEntryByClassName[bean.exposedType]
        if (pointcutClassMatches(advice.classPattern, bean.exposedType)) {
            if (advice.methodPattern == "*") {
                return true
            }
            return entry != null &&
                hierarchyHasMethod(entry, advice.methodPattern, classEntryByClassName, privateOnly = false)
        }
        // 切点写的是**父类型**、Bean 是子类的情形：`S extends Base` 且不覆写 `save()` 时，
        // 切在 `Base.save` 上的转发体会被 S 的实例分派到，但 S 自身没有可织入的声明方法 ——
        // 计划里要么没有 S、要么 SKIPPED。原先这里直接返回 false，S 连 `matched` 都进不去，
        // 后面 unrealized/realized 那套判定一步都走不到，全程静默。按保守方向（宁可多报）纳入。
        // 只认「声明在父类型且**未被本类覆写**」的方法：本类覆写了的话那个覆写体会被正常织入，
        // 报出来就是假阳性。
        val matchedSuper = hierarchyMatchSuperType(entry ?: return false, advice.classPattern, classEntryByClassName)
            ?: return false
        if (advice.methodPattern == "*") {
            return true
        }
        if (!hierarchyHasMethod(matchedSuper, advice.methodPattern, classEntryByClassName, privateOnly = false)) {
            return false
        }
        return entry.methods.none { it.name == advice.methodPattern && !it.isPrivate }
    }

    /** 沿父类链找到第一个类名命中切点类模式的祖先条目；找不到返回 null（只走 superClassName，与 hierarchyHasMethod 同口径）。 */
    private fun hierarchyMatchSuperType(
        entry: ClassIndexEntry,
        classPattern: String,
        classEntryByClassName: Map<String, ClassIndexEntry>,
    ): ClassIndexEntry? {
        var current = entry.superClassName?.let { classEntryByClassName[it] }
        val visited = mutableSetOf<String>()
        while (current != null && visited.add(current.className)) {
            if (pointcutClassMatches(classPattern, current.className)) {
                return current
            }
            current = current.superClassName?.let { classEntryByClassName[it] }
        }
        return null
    }

    /**
     * 沿父类链查找方法；privateOnly=true 时只认 private（用于 private 切点判定）。
     *
     * **F3 已知口径（本次不收紧，仅加注）**：非 private 分支用 `!isPrivate` 近似「public」，
     * 因此 protected / 包内方法的切点也会被当作「命中」。这是 pre-existing 行为，属
     * `pointcut-target-not-found` / `aop-private-method-pointcut` 的既有语义，与本次「抑制来源」
     * 改造无关——**本次不改动其行为**（后续任务 `aop-nonpublic-method-pointcut` 再收紧，见 §8 R19）。
     */
    private fun hierarchyHasMethod(
        entry: ClassIndexEntry,
        methodName: String,
        classEntryByClassName: Map<String, ClassIndexEntry>,
        privateOnly: Boolean,
    ): Boolean {
        var current: ClassIndexEntry? = entry
        val visited = mutableSetOf<String>()
        while (current != null && visited.add(current.className)) {
            val hit = if (privateOnly) {
                current.methods.any { it.name == methodName && it.isPrivate }
            } else {
                current.methods.any { it.name == methodName && !it.isPrivate }
            }
            if (hit) {
                return true
            }
            current = current.superClassName?.let { classEntryByClassName[it] }
        }
        return false
    }

    /**
     * B-P1-01（K25）：沿父类链查找**实例**方法（非 static）。
     * 只要存在实例方法匹配，JDK 代理即可分派，切点不是死规则。
     */
    private fun hierarchyHasInstanceMethod(
        entry: ClassIndexEntry,
        methodName: String,
        classEntryByClassName: Map<String, ClassIndexEntry>,
    ): Boolean {
        var current: ClassIndexEntry? = entry
        val visited = mutableSetOf<String>()
        while (current != null && visited.add(current.className)) {
            if (current.methods.any { it.name == methodName && !it.isStatic }) {
                return true
            }
            current = current.superClassName?.let { classEntryByClassName[it] }
        }
        return false
    }

    /** B-P1-01（K25）：沿父类链查找 static 方法 */
    private fun hierarchyHasStaticMethod(
        entry: ClassIndexEntry,
        methodName: String,
        classEntryByClassName: Map<String, ClassIndexEntry>,
    ): Boolean {
        var current: ClassIndexEntry? = entry
        val visited = mutableSetOf<String>()
        while (current != null && visited.add(current.className)) {
            if (current.methods.any { it.name == methodName && it.isStatic }) {
                return true
            }
            current = current.superClassName?.let { classEntryByClassName[it] }
        }
        return false
    }

    /**
     * 复刻 AopProxyFactory.collectInterfaces：收集类型自身及父类链上的全部接口。
     *
     * **诊断残渣免疫契约（§2.3.7 / §7）**：必须**剔除**织入器自己加上的标记接口
     * （`WovenTarget` / `InjectionWovenTarget`）。这些接口不属于业务类型；若不过滤，
     * 任何「按接口判定」的规则都会随「字节码是否已被就地织入」而漂移 → 结论不可复现。
     */
    private fun collectExposedInterfaces(
        bean: BeanDefinition,
        classEntryByClassName: Map<String, ClassIndexEntry>,
    ): List<String> {
        val interfaces = linkedSetOf<String>()
        var current: ClassIndexEntry? = classEntryByClassName[bean.exposedType]
        val visited = mutableSetOf<String>()
        while (current != null && visited.add(current.className)) {
            interfaces.addAll(current.interfaceNames.filterNot { it in WEAVING_MARKER_INTERFACES })
            current = current.superClassName?.let { classEntryByClassName[it] }
        }
        return interfaces.toList()
    }

    /**
     * `weaving=true` 且 `unrealized` 非空时的精确 message（§2.3.5）：模板 + 原因（按优先级判定）。
     *
     * 原因优先级：① 计划标注 `NO_ASPECT_CLASS`（类/方法带 `@NoAspect`）；
     * ② 某未生效通知的方法模式名在**该类自身**未声明、但在**父类链**中存在（继承未覆写）；
     * ③ 兜底：命中的方法不具备织入资格。
     */
    private fun buildUnrealizedMessage(
        bean: BeanDefinition,
        totalMatched: Int,
        unrealizedKeys: List<String>,
        unrealizedAdvices: List<ResolvedAdvice>,
        planEntry: ClassWeaveDecision?,
        classEntryByClassName: Map<String, ClassIndexEntry>,
    ): String {
        val className = bean.exposedType
        val reason = when {
            planEntry?.skipReason == WeaveSkipReason.NO_ASPECT_CLASS ->
                "该类标注了 @NoAspect，引擎在织入阶段显式跳过该类。若确需该通知生效，请移除 @NoAspect。"

            else -> {
                val inherited = classEntryByClassName[className]?.let { entry ->
                    findInheritedNotOverridden(entry, unrealizedAdvices, classEntryByClassName)
                }
                if (inherited != null) {
                    "切点命中的方法 ${inherited.first} 声明在父类 ${inherited.second} 而未在 $className 中声明/覆写；" +
                        "引擎只按被织类【自身声明】的方法名匹配，故不会织入。" +
                        "在 $className 中覆写它即可被织入。" +
                        "注意：把切点类模式改成 ${inherited.second} 并不能消除本条告警 —— 父类被织入的" +
                        "转发体仍会被 $className 的实例分派到，而是否命中通知取决于运行期匹配口径，" +
                        "构建期无法判定，因此这里按保守方向保留提示。"
                } else {
                    "命中的方法不具备织入资格（须为 public 且非 static/abstract/native/合成/桥接，且位于本模块编译产物内）。"
                }
            }
        }
        return "有 $totalMatched 个切面通知命中 Bean $className，" +
            "但该类没有实现任何接口，运行时 JDK 动态代理将跳过包装；" +
            "本次编译期织入亦未能接管其中 ${unrealizedKeys.size} 个通知（${unrealizedKeys.joinToString(", ")}），" +
            "这些通知将永不执行。原因：$reason"
    }

    /**
     * §2.3.5 原因 2：某未生效通知的方法模式名，在**该类自身**未声明、但在**父类链**中存在。
     *
     * 返回 `(方法名, 声明它的父类 FQCN)`；不存在则返回 `null`。
     */
    private fun findInheritedNotOverridden(
        entry: ClassIndexEntry,
        unrealizedAdvices: List<ResolvedAdvice>,
        classEntryByClassName: Map<String, ClassIndexEntry>,
    ): Pair<String, String>? {
        unrealizedAdvices.forEach { advice ->
            val methodPattern = advice.methodPattern
            if (methodPattern == "*") return@forEach
            if (entry.methods.any { it.name == methodPattern }) return@forEach
            var current = entry.superClassName?.let { classEntryByClassName[it] }
            val visited = mutableSetOf<String>()
            while (current != null && visited.add(current.className)) {
                if (current.methods.any { it.name == methodPattern }) {
                    return methodPattern to current.className
                }
                current = current.superClassName?.let { classEntryByClassName[it] }
            }
        }
        return null
    }

    private fun diagnostic(
        severity: DiagnosticSeverity,
        rule: String,
        ownerClassName: String,
        declarationName: String,
        sourceFile: String?,
        message: String,
        candidateBeans: List<String> = emptyList(),
    ): StaticDiagnostic {
        return StaticDiagnostic(
            severity = severity,
            rule = rule,
            ownerClassName = ownerClassName,
            declarationName = declarationName,
            sourceFile = sourceFile,
            sourcePath = null,
            sourceLine = null,
            sourceColumn = null,
            injectionPointKind = InjectionPointKind.CONSTRUCTOR_PARAMETER,
            parameterIndex = null,
            dependencyType = ownerClassName,
            message = message,
            candidateBeans = candidateBeans.distinct().sorted(),
        )
    }

    /**
     * `weaving=true` 但**未产出可用织入计划**时，追加到 `aop-target-not-proxied` 基线 message 后的后缀。
     *
     * 这是**异常**分支（插件在 `weaving=true` 时会把 `planTaboolibIocAop` 置为 enabled，正常情况下计划必然存在）：
     * 提示用户「织入链路没跑通」而非「切点写法问题」。**仅** `weaving=true` 时追加，
     * `weaving=false` 的 message 保持与 HEAD 逐字一致（引导只进 message，不进 `buildSolution`）。
     */
    private const val WEAVE_PLAN_MISSING_SUFFIX: String =
        " 已开启编译期织入，但本次未产出可用的织入计划（aop-weave-plan.json），" +
            "该 Bean 命中的通知不会被编译期织入接管；请检查 planTaboolibIocAop 任务是否执行、" +
            "build/taboolib-ioc/aop-weave-plan.json 是否已生成且可解析。"

    private val RESOURCE_TYPES = setOf(
        "java.sql.Connection",
        "java.io.InputStream",
        "java.io.OutputStream",
        "java.io.Reader",
        "java.io.Writer",
        "java.net.Socket",
        "java.nio.channels.Channel",
        "javax.sql.DataSource",
        // C-P2-05：此前引擎生效白名单缺 ExecutorService（原 RefreshScopeAnalyzer 有 9 项、
        // 引擎只 8 项），@RefreshScope Bean 持有线程池字段时漏报资源泄漏风险。
        "java.util.concurrent.ExecutorService",
    )

    /** 内置作用域白名单（对齐运行时 BeanScopes 的 standard + builtin） */
    private val BUILTIN_SCOPES = setOf("singleton", "prototype", "thread", "refresh")

    /** 与运行时 ValueResolver.PLACEHOLDER_REGEX 一致，用于整串匹配判定 */
    private val PLACEHOLDER_ENTIRE_REGEX = Regex("\\$\\{([^}]+)}")

    /** 运行时 ValueResolver.convertType 支持的目标类型（Kotlin 原始类型 + 包装类） */
    private val SUPPORTED_VALUE_TYPES = setOf(
        "java.lang.String",
        "int", "java.lang.Integer",
        "long", "java.lang.Long",
        "double", "java.lang.Double",
        "float", "java.lang.Float",
        "boolean", "java.lang.Boolean",
        "short", "java.lang.Short",
        "byte", "java.lang.Byte",
    )

    /** 运行时 @Around 通知的唯一合法参数类型 */
    private const val METHOD_INVOCATION_TYPE = "top.wcpe.taboolib.ioc.bean.MethodInvocation"
}
