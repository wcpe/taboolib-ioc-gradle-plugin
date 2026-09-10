package top.wcpe.taboolib.ioc.gradle.analysis

internal object CycleDependencyDetector {

    fun detectCycles(
        beans: List<BeanDefinition>,
        injectionPoints: List<InjectionPointDefinition>,
        classIndex: List<ClassIndexEntry> = emptyList(),
    ): List<DependencyCycle> {
        val beanMap = beans.associateBy { it.beanName }
        val beansByType = beans.groupBy { it.exposedType }
        val interfaceTypes = collectInterfaceTypes(classIndex)
        val graph = buildGraph(beans, beansByType, injectionPoints, interfaceTypes)
        val breakableBeans = collectLazyBreakableBeans(beans, injectionPoints, interfaceTypes)
        val cycles = mutableListOf<DependencyCycle>()
        val visited = mutableSetOf<String>()
        val recursionStack = mutableSetOf<String>()
        val path = mutableListOf<String>()

        for (bean in beans) {
            if (bean.beanName !in visited) {
                dfs(bean.beanName, graph, beanMap, visited, recursionStack, path, cycles)
            }
        }

        return normalizeCycles(cycles, breakableBeans)
    }

    /**
     * K6 修复：收集「运行时会被 JDK 动态代理包装」的类型集合。
     *
     * 运行时 `LazyProxyFactory.canProxy(type) = type.isInterface`（LazyProxyFactory.kt:21）且
     * `createProxy` 对非接口类型直接 `require` 抛异常（:33）；`FieldInjector.injectLazyField`
     * 对非接口类型会 **warning + 静默回退为立即注入**（FieldInjector.kt:54-62）。
     * 因此只有「依赖目标类型是接口」的 @Lazy 边才能真正断开环。
     *
     * 接口集合由 classIndex 的 `isInterface` 提供（唯一权威来源）；
     * 无 classIndex 时（纯图单测）退回「依赖声明类型 ≠ 候选 Bean 暴露类型」的近似判据。
     */
    private fun collectInterfaceTypes(
        classIndex: List<ClassIndexEntry>,
    ): Set<String> {
        if (classIndex.isEmpty()) {
            return emptySet()
        }
        return classIndex.filter { it.isInterface }.map { it.className }.toSet()
    }

    private fun collectLazyBreakableBeans(
        beans: List<BeanDefinition>,
        injectionPoints: List<InjectionPointDefinition>,
        interfaceTypes: Set<String>,
    ): Set<String> {
        val beansByType = beans.groupBy { it.exposedType }
        val breakable = mutableSetOf<String>()
        for (ip in injectionPoints) {
            if (!ip.lazy) continue
            for (target in resolveTargetBeans(ip, beans, beansByType)) {
                if (canBreakByLazy(ip, target, interfaceTypes)) {
                    breakable += target.beanName
                }
            }
        }
        return breakable
    }

    /**
     * 判断一条 @Lazy 边能否真正断开环。
     *
     * 陷阱 1（最关键）：跳过边的条件必须是 `ip.lazy && 依赖类型是接口`，**不能只判 ip.lazy**。
     * 非接口类型的 @Lazy 在运行时会回退为立即注入（FieldInjector.kt:54-62），环并未断开；
     * 静态只按 ip.lazy 过滤会把真环误过滤 → 引入漏报（比误报更糟）。
     */
    private fun canBreakByLazy(
        ip: InjectionPointDefinition,
        targetBean: BeanDefinition,
        interfaceTypes: Set<String>,
    ): Boolean {
        if (!ip.lazy) return false
        return isInterfaceDependency(ip.dependencyType, targetBean, interfaceTypes)
    }

    private fun isInterfaceDependency(
        dependencyType: String,
        targetBean: BeanDefinition,
        interfaceTypes: Set<String>,
    ): Boolean {
        if (dependencyType in interfaceTypes) return true
        if (interfaceTypes.isEmpty()) {
            // 纯图单测（无 classIndex）：以「依赖声明类型 ≠ 候选暴露类型」近似接口注入
            return dependencyType != targetBean.exposedType
        }
        return false
    }

    private fun resolveTargetBeans(
        ip: InjectionPointDefinition,
        beans: List<BeanDefinition>,
        beansByType: Map<String, List<BeanDefinition>>,
    ): List<BeanDefinition> {
        return if (ip.qualifierName != null) {
            beans.filter { it.beanName == ip.qualifierName }
        } else {
            beansByType[ip.dependencyType] ?: emptyList()
        }
    }

    private fun buildGraph(
        beans: List<BeanDefinition>,
        beansByType: Map<String, List<BeanDefinition>>,
        injectionPoints: List<InjectionPointDefinition>,
        interfaceTypes: Set<String>,
    ): Map<String, List<DependencyEdge>> {
        val graph = beans.associate { it.beanName to mutableListOf<DependencyEdge>() }

        for (ip in injectionPoints) {
            val ownerBean = beans.find { it.exposedType == ip.ownerClassName } ?: continue
            for (target in resolveTargetBeans(ip, beans, beansByType)) {
                val breakable = canBreakByLazy(ip, target, interfaceTypes)
                graph[ownerBean.beanName]?.add(DependencyEdge(target.beanName, ip.kind, breakable))
            }
        }

        return graph
    }

    private fun dfs(
        current: String,
        graph: Map<String, List<DependencyEdge>>,
        beanMap: Map<String, BeanDefinition>,
        visited: MutableSet<String>,
        recursionStack: MutableSet<String>,
        path: MutableList<String>,
        cycles: MutableList<DependencyCycle>,
    ) {
        visited.add(current)
        recursionStack.add(current)
        path.add(current)

        for (edge in graph[current] ?: emptyList()) {
            val target = edge.targetBeanName
            if (target in recursionStack) {
                val cycleStart = path.indexOf(target)
                val cyclePath = path.subList(cycleStart, path.size).toList()
                val cycleEdges = extractEdges(cyclePath + target, graph)
                val kind = analyzeKind(cycleEdges)
                cycles.add(DependencyCycle(cyclePath, kind, isResolvable(cyclePath, kind, beanMap)))
            } else if (target !in visited) {
                dfs(target, graph, beanMap, visited, recursionStack, path, cycles)
            }
        }

        recursionStack.remove(current)
        path.removeLast()
    }

    private fun extractEdges(path: List<String>, graph: Map<String, List<DependencyEdge>>): List<DependencyEdge> {
        return (0 until path.size - 1).mapNotNull { i ->
            graph[path[i]]?.find { it.targetBeanName == path[i + 1] }
        }
    }

    private fun analyzeKind(edges: List<DependencyEdge>): CycleDependencyKind {
        // K6：由接口类型 @Lazy 断开的边不参与「构造器环」判定——
        // 运行时代理工厂在构造期不解析目标 Bean，故它等价于一条可延迟的字段边。
        val effectiveEdges = edges.filterNot { it.lazyBreakable }
        val hasConstructor = effectiveEdges.any { it.kind == InjectionPointKind.CONSTRUCTOR_PARAMETER }
        val hasField = effectiveEdges.any { it.kind == InjectionPointKind.FIELD || it.kind == InjectionPointKind.METHOD_PARAMETER }
        return when {
            hasConstructor && hasField -> CycleDependencyKind.MIXED
            hasConstructor -> CycleDependencyKind.CONSTRUCTOR
            else -> CycleDependencyKind.FIELD
        }
    }

    private fun isResolvable(path: List<String>, kind: CycleDependencyKind, beanMap: Map<String, BeanDefinition>): Boolean {
        if (kind != CycleDependencyKind.FIELD) return false
        return path.all { name ->
            val bean = beanMap[name]
            bean != null && (bean.scope == null || bean.scope == "singleton")
        }
    }

    private fun normalizeCycles(cycles: List<DependencyCycle>, breakableBeans: Set<String>): List<DependencyCycle> {
        val seen = mutableSetOf<String>()
        return cycles.filter { cycle ->
            val nodes = cycle.path
            val min = nodes.minOrNull() ?: return@filter false
            val idx = nodes.indexOf(min)
            val key = (nodes.drop(idx) + nodes.take(idx)).joinToString(",")
            seen.add(key)
        }.map { cycle ->
            // K6 修复（陷阱 3）：环上任意一环节点由「接口类型的 @Lazy 边」注入时，
            // 运行时由动态代理在首次调用时才解析目标 Bean，构造期不会递归，
            // 因此该环在运行时不会失败（即便环上存在 prototype/thread 等非 singleton 节点）。
            // 标记 brokenByLazy，并同步把 resolvable 置真，供引擎据此降级/放行。
            if (isBrokenByLazy(cycle.path, breakableBeans)) {
                cycle.copy(resolvable = true, brokenByLazy = true)
            } else {
                cycle
            }
        }
    }

    private fun isBrokenByLazy(path: List<String>, breakableBeans: Set<String>): Boolean {
        if (breakableBeans.isEmpty()) return false
        // path 末位为重复的起点；环上节点为 path 的前 n-1 项
        return path.dropLast(1).any { it in breakableBeans }
    }

    private data class DependencyEdge(
        val targetBeanName: String,
        val kind: InjectionPointKind,
        /** K6：该边是否为「接口类型 @Lazy」边——运行时会被 LazyProxyFactory 代理，从而断开环 */
        val lazyBreakable: Boolean = false,
    )
}
