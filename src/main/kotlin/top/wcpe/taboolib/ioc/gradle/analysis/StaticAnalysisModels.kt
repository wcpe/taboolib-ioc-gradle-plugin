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

internal enum class CycleDependencyKind {
    CONSTRUCTOR,  // 构造函数循环
    FIELD,        // 字段循环
    MIXED,        // 混合
}

internal enum class DiagnosticSeverity {
    ERROR,
    WARNING,
    INFO,
}

internal enum class ConditionEvaluationState {
    ENABLED,
    DISABLED,
    UNKNOWN,
}

internal data class FieldInfo(
    val name: String,
    val type: String,
    val descriptor: String,
)

/** 类上采集到的方法（不含构造器与合成方法），供 AOP 静默失效规则组做方法级匹配 */
internal data class CollectedMethodInfo(
    val name: String,
    val isPrivate: Boolean,
    val isStatic: Boolean,
)

internal data class LifecycleMethodsInfo(
    val postConstructMethods: List<String> = emptyList(),
    val preDestroyMethods: List<String> = emptyList(),
    val postEnableMethods: List<String> = emptyList(),
)

internal data class DependencyReference(
    val targetBeanName: String?,  // 如果有 @Named
    val targetType: String,
    val kind: InjectionPointKind,  // 构造函数、字段、方法
    val declarationName: String,   // 字段名或参数名
)

internal data class DependencyCycle(
    val path: List<String>,  // Bean 名称路径
    val kind: CycleDependencyKind,
    val resolvable: Boolean,  // 是否可通过早期暴露解决
    /**
     * K6：环是否已被「接口类型 @Lazy」边真正断开。
     *
     * 运行时由 `LazyProxyFactory` 生成动态代理，首次方法调用才解析目标 Bean，
     * 因此构造期不会递归。此时该环**运行时不会失败**，静态侧应放行（不阻断构建）。
     */
    val brokenByLazy: Boolean = false,
)

internal data class ClassIndexEntry(
    val className: String,
    val packageName: String,
    val sourceFile: String?,
    val superClassName: String?,
    val interfaceNames: List<String>,
    val genericSuperTypes: List<String> = emptyList(),
    val fields: List<FieldInfo> = emptyList(),
    val isInterface: Boolean = false,
    val isAbstract: Boolean = false,
    val methods: List<CollectedMethodInfo> = emptyList(),
)

internal data class ConditionDescriptor(
    val annotationName: String,
    val attributes: Map<String, Any>,
)

internal data class ConstructorMetadata(
    val hasExplicitInjectConstructor: Boolean,
    val totalConstructorCount: Int,
    val runtimeSelectedConstructorHasParameters: Boolean,
    val runtimeSelectedConstructorHasNonNullableParameters: Boolean,
    val hasNoArgConstructor: Boolean = false,
)

internal data class TypeAliasDefinition(
    val packageName: String,
    val aliasName: String,
    val targetType: String,
    val sourceFile: String,
)

internal data class LifecycleMethodDetail(
    val annotation: String,
    val methodName: String,
    val parameterCount: Int,
    val isStatic: Boolean,
)

/**
 * @Aspect 类中通知方法上的切点表达式（采集自 @Before/@After/@Around/@AfterReturning/@AfterThrowing）。
 */
internal data class AspectAdviceDefinition(
    val adviceAnnotation: String,
    val methodName: String,
    val expression: String,
    /** 通知方法参数类型（advice-signature-invalid 规则判定用） */
    val parameterTypes: List<String> = emptyList(),
)

/**
 * @Aspect 切面类的静态索引：@Pointcut 命名切点 + 各通知方法表达式。
 * 供 pointcut-expression-invalid 等规则复刻 AspectScanner/PointcutExpression 的运行时判定。
 */
internal data class AspectDefinition(
    val aspectClassName: String,
    val packageName: String,
    val sourceFile: String?,
    val pointcutMethods: Map<String, String>,
    val advices: List<AspectAdviceDefinition>,
)

/**
 * @Value 字段静态索引（表达式 + 目标字段类型），
 * 供 value-expression-unresolved-placeholder / value-type-unsupported 规则使用。
 */
internal data class ValueFieldDefinition(
    val ownerClassName: String,
    val fieldName: String,
    val expression: String,
    val targetType: String,
    val packageName: String,
    val sourceFile: String?,
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
    val constructorMetadata: ConstructorMetadata? = null,
    val stereotypeAnnotation: String? = null,
    val scope: String? = null,
    val lifecycleMethods: LifecycleMethodsInfo = LifecycleMethodsInfo(),
    val dependencies: List<DependencyReference> = emptyList(),
    /** FACTORY_METHOD 专用：宿主类是否为 @Configuration（运行时只在 @Configuration 分支扫描 @Bean 方法） */
    val factoryHostIsConfiguration: Boolean? = null,
    /** FACTORY_METHOD 专用：方法返回类型是否为 void（运行时 require(returnType != Void.TYPE) 直接抛异常） */
    val factoryMethodReturnsVoid: Boolean = false,
    /** 生命周期方法签名明细（含参数个数），供 lifecycle-method-signature-invalid 规则判定 */
    val lifecycleMethodDetails: List<LifecycleMethodDetail> = emptyList(),
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
    /**
     * K6 修复：注入点是否被 @Lazy 标注且其 value 为 true。
     *
     * 运行时 `InjectField.lazy = lazy?.value == true`（ClassScanner.kt:182 / Injector.kt:205），
     * 且 `@Lazy(false)` 语义为「不延迟」—— 因此采集层必须读注解的 `value` 属性，
     * 而不是仅判断注解存在。默认值 false，避免打爆既有构造点。
     */
    val lazy: Boolean = false,
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
    val aspectIndex: List<AspectDefinition> = emptyList(),
    val valueFieldIndex: List<ValueFieldDefinition> = emptyList(),
)

internal data class StaticAnalysisReport(
    val projectPath: String,
    val beanIndex: List<BeanDefinition>,
    val injectionPointIndex: List<InjectionPointDefinition>,
    val componentScans: List<ComponentScanDefinition>,
    val typeAliasIndex: List<TypeAliasDefinition>,
    val diagnostics: List<StaticDiagnostic>,
)
