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

internal enum class ConditionEvaluationState {
    ENABLED,
    DISABLED,
    UNKNOWN,
}

internal data class ClassIndexEntry(
    val className: String,
    val packageName: String,
    val sourceFile: String?,
    val superClassName: String?,
    val interfaceNames: List<String>,
    val genericSuperTypes: List<String> = emptyList(),
)

internal data class ConditionDescriptor(
    val annotationName: String,
    val attributes: Map<String, Any>,
)

internal data class TypeAliasDefinition(
    val packageName: String,
    val aliasName: String,
    val targetType: String,
    val sourceFile: String,
)

internal data class BeanDefinition(
    val ownerClassName: String,
    val declarationName: String,
    val beanName: String,
    val exposedType: String,
    val packageName: String,
    val sourceFile: String?,
    val kind: BeanKind,
    val exposedGenericType: String?,
    val primary: Boolean,
    val order: Int?,
    val conditionalAnnotations: List<String>,
    val conditions: List<ConditionDescriptor>,
)

internal data class InjectionPointDefinition(
    val ownerClassName: String,
    val declarationName: String,
    val dependencyType: String,
    val dependencyGenericType: String?,
    val ownerPackage: String,
    val sourceFile: String?,
    val sourcePath: String?,
    val sourceLine: Int?,
    val sourceColumn: Int?,
    val kind: InjectionPointKind,
    val parameterIndex: Int?,
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
    val sourceFile: String?,
    val sourcePath: String?,
    val sourceLine: Int?,
    val sourceColumn: Int?,
    val injectionPointKind: InjectionPointKind,
    val parameterIndex: Int?,
    val dependencyType: String,
    val message: String,
    val candidateBeans: List<String> = emptyList(),
)

internal data class BytecodeAnalysisIndex(
    val classIndex: List<ClassIndexEntry>,
    val beanIndex: List<BeanDefinition>,
    val injectionPointIndex: List<InjectionPointDefinition>,
    val missingInjectCandidateIndex: List<InjectionPointDefinition>,
    val componentBeanTypes: List<String>,
    val componentScans: List<ComponentScanDefinition>,
)

internal data class StaticAnalysisReport(
    val projectPath: String,
    val beanIndex: List<BeanDefinition>,
    val injectionPointIndex: List<InjectionPointDefinition>,
    val componentScans: List<ComponentScanDefinition>,
    val typeAliasIndex: List<TypeAliasDefinition>,
    val diagnostics: List<StaticDiagnostic>,
)