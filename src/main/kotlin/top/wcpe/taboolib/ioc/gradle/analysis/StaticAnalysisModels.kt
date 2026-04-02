package top.wcpe.taboolib.ioc.gradle.analysis

internal enum class BeanKind {
    CLASS,
    FACTORY_METHOD,
}

internal enum class InjectionPointKind {
    CONSTRUCTOR_PARAMETER,
    FIELD,
    METHOD_PARAMETER,
}

internal enum class DiagnosticSeverity {
    ERROR,
    WARNING,
}

internal data class ClassIndexEntry(
    val className: String,
    val packageName: String,
    val sourceFile: String?,
    val superClassName: String?,
    val interfaceNames: List<String>,
)

internal data class BeanDefinition(
    val ownerClassName: String,
    val declarationName: String,
    val beanName: String,
    val exposedType: String,
    val packageName: String,
    val sourceFile: String?,
    val kind: BeanKind,
    val primary: Boolean,
    val order: Int?,
    val conditionalAnnotations: List<String>,
)

internal data class InjectionPointDefinition(
    val ownerClassName: String,
    val declarationName: String,
    val dependencyType: String,
    val ownerPackage: String,
    val sourceFile: String?,
    val kind: InjectionPointKind,
    val qualifierName: String?,
    val required: Boolean,
)

internal data class ComponentScanDefinition(
    val ownerClassName: String,
    val packageName: String,
    val sourceFile: String?,
    val basePackages: List<String>,
)

internal data class StaticDiagnostic(
    val severity: DiagnosticSeverity,
    val rule: String,
    val ownerClassName: String,
    val declarationName: String,
    val dependencyType: String,
    val message: String,
    val candidateBeans: List<String> = emptyList(),
)

internal data class BytecodeAnalysisIndex(
    val classIndex: List<ClassIndexEntry>,
    val beanIndex: List<BeanDefinition>,
    val injectionPointIndex: List<InjectionPointDefinition>,
    val componentScans: List<ComponentScanDefinition>,
)

internal data class StaticAnalysisReport(
    val projectPath: String,
    val beanIndex: List<BeanDefinition>,
    val injectionPointIndex: List<InjectionPointDefinition>,
    val componentScans: List<ComponentScanDefinition>,
    val diagnostics: List<StaticDiagnostic>,
)