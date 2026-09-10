package top.wcpe.taboolib.ioc.gradle.analysis

internal object StaticDiagnosisEngine {

    fun analyze(
        projectPath: String,
        index: BytecodeAnalysisIndex,
        typeAliases: List<TypeAliasDefinition> = emptyList(),
        projectProperties: Map<String, String> = emptyMap(),
        scanClassLoader: ClassLoader? = null,
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
            analyzeAopSilentFailures(index.beanIndex, index.aspectIndex, classEntryByClassName)
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
            val resolvedDependencyGenericType = canonicalGeneric(dependencyGenericType)
            val candidateGenericTypes = buildList {
                bean.exposedGenericType?.let { add(normalizeTypeName(it)) }
                addAll(genericSuperTypeIndex[bean.exposedType].orEmpty())
            }
            if (candidateGenericTypes.isEmpty()) {
                return true
            }
            return candidateGenericTypes.any { it == dependencyGenericType || canonicalGeneric(it) == resolvedDependencyGenericType }
        }

        /** 对泛型实参逐个归一化：`java.util.List<MyAlias>` -> `java.util.List<fixture.ServerApi>` */
        private fun canonicalGeneric(typeName: String): String {
            val compact = normalizeTypeName(typeName)
            val open = compact.indexOf('<')
            if (open < 0 || !compact.endsWith(">")) {
                return canonical(compact)
            }
            val raw = compact.substring(0, open)
            val args = compact.substring(open + 1, compact.length - 1)
            val normalizedArgs = splitTopLevel(args).joinToString(",") { canonical(it) }
            return "${canonical(raw)}<$normalizedArgs>"
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
        return cycles.map { cycle ->
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

            val firstBean = beans.find { it.beanName == cycle.path.first() }!!
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
     */
    private fun analyzeAopSilentFailures(
        beans: List<BeanDefinition>,
        aspects: List<AspectDefinition>,
        classEntryByClassName: Map<String, ClassIndexEntry>,
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
                        diagnostics += diagnostic(
                            severity = DiagnosticSeverity.WARNING,
                            rule = "aop-target-not-proxied",
                            ownerClassName = bean.ownerClassName,
                            declarationName = bean.declarationName,
                            sourceFile = bean.sourceFile,
                            message = "有 ${matched.size} 个切面通知命中 Bean ${bean.exposedType}，" +
                                "但该类没有实现任何接口，运行时 JDK 动态代理将跳过包装，通知永不执行。" +
                                "请为其抽取接口，或调整切点表达式。",
                            candidateBeans = matched.map { "${it.aspectClassName}#${it.advice.methodName}" },
                        )
                    }
                }

                BeanKind.FACTORY_METHOD -> {
                    val returnEntry = classEntryByClassName[bean.exposedType]
                    if (returnEntry?.isInterface == true) {
                        diagnostics += diagnostic(
                            severity = DiagnosticSeverity.WARNING,
                            rule = "aop-factory-bean-interface-return",
                            ownerClassName = bean.ownerClassName,
                            declarationName = bean.declarationName,
                            sourceFile = bean.sourceFile,
                            message = "@Bean 方法 ${bean.declarationName} 声明的返回类型 ${bean.exposedType} 是接口，" +
                                "且有 ${matched.size} 个切面通知命中，但运行时按声明类型收集接口必为空，代理永不生效。" +
                                "请把返回类型改为具体实现类。",
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
        if (!pointcutClassMatches(advice.classPattern, bean.exposedType)) {
            return false
        }
        if (advice.methodPattern == "*") {
            return true
        }
        val entry = classEntryByClassName[bean.exposedType] ?: return false
        return hierarchyHasMethod(entry, advice.methodPattern, classEntryByClassName, privateOnly = false)
    }

    /** 沿父类链查找方法；privateOnly=true 时只认 private（用于 private 切点判定） */
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

    /** 复刻 AopProxyFactory.collectInterfaces：收集类型自身及父类链上的全部接口 */
    private fun collectExposedInterfaces(
        bean: BeanDefinition,
        classEntryByClassName: Map<String, ClassIndexEntry>,
    ): List<String> {
        val interfaces = linkedSetOf<String>()
        var current: ClassIndexEntry? = classEntryByClassName[bean.exposedType]
        val visited = mutableSetOf<String>()
        while (current != null && visited.add(current.className)) {
            interfaces.addAll(current.interfaceNames)
            current = current.superClassName?.let { classEntryByClassName[it] }
        }
        return interfaces.toList()
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
