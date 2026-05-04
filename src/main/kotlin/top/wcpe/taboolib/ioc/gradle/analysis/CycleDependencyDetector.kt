package top.wcpe.taboolib.ioc.gradle.analysis

internal object CycleDependencyDetector {

    fun detectCycles(beans: List<BeanDefinition>, injectionPoints: List<InjectionPointDefinition>): List<DependencyCycle> {
        val beanMap = beans.associateBy { it.beanName }
        val beansByType = beans.groupBy { it.exposedType }
        val graph = buildGraph(beans, beansByType, injectionPoints)
        val cycles = mutableListOf<DependencyCycle>()
        val visited = mutableSetOf<String>()
        val recursionStack = mutableSetOf<String>()
        val path = mutableListOf<String>()

        for (bean in beans) {
            if (bean.beanName !in visited) {
                dfs(bean.beanName, graph, beanMap, visited, recursionStack, path, cycles)
            }
        }

        return normalizeCycles(cycles)
    }

    private fun buildGraph(
        beans: List<BeanDefinition>,
        beansByType: Map<String, List<BeanDefinition>>,
        injectionPoints: List<InjectionPointDefinition>,
    ): Map<String, List<DependencyEdge>> {
        val graph = beans.associate { it.beanName to mutableListOf<DependencyEdge>() }

        for (ip in injectionPoints) {
            val ownerBean = beans.find { it.exposedType == ip.ownerClassName } ?: continue
            val targetBeans = if (ip.qualifierName != null) {
                beans.filter { it.beanName == ip.qualifierName }
            } else {
                beansByType[ip.dependencyType] ?: emptyList()
            }
            for (target in targetBeans) {
                graph[ownerBean.beanName]?.add(DependencyEdge(target.beanName, ip.kind))
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
        val hasConstructor = edges.any { it.kind == InjectionPointKind.CONSTRUCTOR_PARAMETER }
        val hasField = edges.any { it.kind == InjectionPointKind.FIELD || it.kind == InjectionPointKind.METHOD_PARAMETER }
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

    private fun normalizeCycles(cycles: List<DependencyCycle>): List<DependencyCycle> {
        val seen = mutableSetOf<String>()
        return cycles.filter { cycle ->
            val nodes = cycle.path
            val min = nodes.minOrNull() ?: return@filter false
            val idx = nodes.indexOf(min)
            val key = (nodes.drop(idx) + nodes.take(idx)).joinToString(",")
            seen.add(key)
        }
    }

    private data class DependencyEdge(val targetBeanName: String, val kind: InjectionPointKind)
}
