package top.wcpe.taboolib.ioc.gradle.analysis

internal object RefreshScopeAnalyzer {

    private val RESOURCE_TYPES = setOf(
        "java.sql.Connection",
        "java.io.InputStream",
        "java.io.OutputStream",
        "java.io.Reader",
        "java.io.Writer",
        "java.net.Socket",
        "java.nio.channels.Channel",
        "java.util.concurrent.ExecutorService",
    )

    fun analyze(beans: List<BeanDefinition>, classIndex: List<ClassIndexEntry>): List<StaticDiagnostic> {
        val hierarchy = buildTypeHierarchy(classIndex)
        return beans
            .filter { it.scope == "refresh" }
            .mapNotNull { bean -> analyzeBean(bean, hierarchy) }
    }

    private fun analyzeBean(bean: BeanDefinition, hierarchy: Map<String, Set<String>>): StaticDiagnostic? {
        val classEntry = hierarchy.keys.firstOrNull { it == bean.exposedType } ?: return null
        // 找到 bean 对应的 ClassIndexEntry 来获取字段信息
        return null // 将在集成时通过 classIndex 查找
    }

    fun analyzeWithClassIndex(beans: List<BeanDefinition>, classIndex: List<ClassIndexEntry>): List<StaticDiagnostic> {
        val classMap = classIndex.associateBy { it.className }
        return beans
            .filter { it.scope == "refresh" }
            .mapNotNull { bean ->
                val classEntry = classMap[bean.exposedType] ?: return@mapNotNull null
                val resourceFields = classEntry.fields.filter { field ->
                    isResourceType(field.type)
                }
                if (resourceFields.isEmpty()) return@mapNotNull null
                if (bean.lifecycleMethods.preDestroyMethods.isNotEmpty()) return@mapNotNull null

                StaticDiagnostic(
                    severity = DiagnosticSeverity.WARNING,
                    rule = "refresh-scope-resource-leak-risk",
                    ownerClassName = bean.ownerClassName,
                    declarationName = bean.declarationName,
                    sourceFile = bean.sourceFile,
                    sourcePath = null,
                    sourceLine = null,
                    sourceColumn = null,
                    injectionPointKind = InjectionPointKind.FIELD,
                    parameterIndex = null,
                    dependencyType = bean.exposedType,
                    message = "RefreshScope Bean 包含资源字段 [${resourceFields.joinToString { "${it.name}: ${it.type}" }}]，但未定义 @PreDestroy 方法，可能导致资源泄漏。",
                )
            }
    }

    private fun isResourceType(type: String): Boolean = type in RESOURCE_TYPES

    private fun buildTypeHierarchy(classIndex: List<ClassIndexEntry>): Map<String, Set<String>> {
        return classIndex.associate { entry ->
            entry.className to buildSet {
                add(entry.className)
                entry.superClassName?.let { add(it) }
                addAll(entry.interfaceNames)
            }
        }
    }
}
