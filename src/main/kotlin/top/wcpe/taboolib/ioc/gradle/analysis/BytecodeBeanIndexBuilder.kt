package top.wcpe.taboolib.ioc.gradle.analysis

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.jar.JarFile
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

internal object BytecodeBeanIndexBuilder {

    /**
     * C-P2-21：Taboolib IoC 注入相关注解的 FQCN 白名单。
     * 采集层此前用简单名匹配，跨包同名注解会被误命中。
     */
    private val IOC_INJECTION_ANNOTATIONS = setOf(
        "top.wcpe.taboolib.ioc.annotation.Inject",
        "top.wcpe.taboolib.ioc.annotation.Named",
        "top.wcpe.taboolib.ioc.annotation.Resource",
    )

    /** C-P2-21：非 Taboolib IoC 的同名注解 FQCN 黑名单（明确排除） */
    private val FOREIGN_INJECT_FQCNS = setOf(
        "com.google.inject.Inject",
        "javax.inject.Inject",
        "jakarta.inject.Inject",
        "org.springframework.beans.factory.annotation.Autowired",
    )
    private val FOREIGN_NAMED_FQCNS = setOf(
        "javax.inject.Named",
        "jakarta.inject.Named",
        "com.google.inject.name.Named",
    )
    private val FOREIGN_RESOURCE_FQCNS = setOf(
        "javax.annotation.Resource",
        "jakarta.annotation.Resource",
    )

    fun build(
        classpathEntries: Iterable<Path>,
        sourceDirectories: Iterable<Path> = emptyList(),
    ): BytecodeAnalysisIndex {
        val existingEntries = classpathEntries.filter { Files.exists(it) }.distinct()

        // 两阶段采集：先解析全部类得到 collector（保留插入顺序，项目输出在前），
        // 再逐个构建索引 —— 第二阶段需要跨类读取 Companion 类的 $annotations 载体（H3）
        val collectors = LinkedHashMap<String, ClassCollector>()
        existingEntries.forEach { entry ->
            when {
                Files.isDirectory(entry) -> scanDirectory(entry, collectors)
                Files.isRegularFile(entry) && entry.toString().endsWith(".jar") -> scanJar(entry, collectors)
            }
        }

        val classIndex = mutableListOf<ClassIndexEntry>()
        val beanIndex = mutableListOf<BeanDefinition>()
        val injectionPointIndex = mutableListOf<InjectionPointDefinition>()
        val missingInjectCandidateIndex = mutableListOf<InjectionPointDefinition>()
        val componentBeanTypes = mutableListOf<String>()
        val componentScans = mutableListOf<ComponentScanDefinition>()
        val aspectIndex = mutableListOf<AspectDefinition>()
        val valueFieldIndex = mutableListOf<ValueFieldDefinition>()

        collectors.values.forEach { collector ->
            val companionCarriers = collectors[collector.collectorClassName + "\$Companion"]?.carrierAnnotations().orEmpty()
            val scannedClass = collector.toScannedClass(companionCarriers) ?: return@forEach
            classIndex += scannedClass.classIndexEntry
            beanIndex += scannedClass.beanDefinitions
            injectionPointIndex += scannedClass.injectionPoints
            missingInjectCandidateIndex += scannedClass.missingInjectCandidates
            componentBeanTypes += scannedClass.componentBeanTypes
            componentScans += scannedClass.componentScans
            aspectIndex += scannedClass.aspectDefinitions
            valueFieldIndex += scannedClass.valueFields
        }

        val rawIndex = BytecodeAnalysisIndex(
            classIndex = classIndex.distinct().sortedBy { it.className },
            beanIndex = beanIndex.distinct().sortedWith(compareBy({ it.ownerClassName }, { it.declarationName }, { it.beanName })),
            injectionPointIndex = injectionPointIndex.distinct().sortedWith(compareBy({ it.ownerClassName }, { it.declarationName }, { it.kind.name })),
            missingInjectCandidateIndex = missingInjectCandidateIndex.distinct().sortedWith(compareBy({ it.ownerClassName }, { it.declarationName }, { it.kind.name })),
            componentBeanTypes = componentBeanTypes.distinct().sorted(),
            componentScans = componentScans.distinct().sortedBy { it.ownerClassName },
            aspectIndex = aspectIndex.distinct().sortedBy { it.aspectClassName },
            valueFieldIndex = valueFieldIndex.distinct().sortedWith(compareBy({ it.ownerClassName }, { it.fieldName })),
        )
        val genericIndex = enrichGenericMetadata(rawIndex, existingEntries)
        return enrichSourceLocations(deduplicateCrossEntryDuplicates(genericIndex), sourceDirectories)
    }

    /**
     * duplicate-classpath-copy 修复：同一份类可能同时出现在多个扫描根里
     * （例如 compileClasspath 与 taboo 依赖各带一份，且字节码因版本不同而不完全一致，
     * 全等 distinct() 无法去重），导致 beanIndex 出现同 beanName 的假重复，
     * 连锁触发 duplicate-bean-name / multiple-primary-beans 假阳性。
     * 这里按类名 / Bean 三元组去重并保留**先出现**的条目
     * （扫描根顺序为 project 输出在前、依赖在后，即项目输出优先）。
     */
    private fun deduplicateCrossEntryDuplicates(index: BytecodeAnalysisIndex): BytecodeAnalysisIndex {
        return index.copy(
            classIndex = index.classIndex.distinctBy { it.className },
            beanIndex = index.beanIndex.distinctBy {
                listOf(it.ownerClassName, it.declarationName, it.beanName, it.exposedType, it.kind.name)
            },
            injectionPointIndex = index.injectionPointIndex.distinctBy {
                listOf(
                    it.ownerClassName,
                    it.declarationName,
                    it.dependencyType,
                    it.kind.name,
                    it.parameterIndex?.toString().orEmpty(),
                    it.qualifierName.orEmpty(),
                    it.required.toString(),
                    it.lazy.toString(),
                )
            },
            missingInjectCandidateIndex = index.missingInjectCandidateIndex.distinctBy {
                listOf(it.ownerClassName, it.declarationName, it.dependencyType, it.kind.name, it.parameterIndex?.toString().orEmpty())
            },
            valueFieldIndex = index.valueFieldIndex.distinctBy { listOf(it.ownerClassName, it.fieldName, it.expression) },
        )
    }

    private fun enrichSourceLocations(index: BytecodeAnalysisIndex, sourceDirectories: Iterable<Path>): BytecodeAnalysisIndex {
        val sourceLocationIndex = SourceLocationIndexBuilder.build(sourceDirectories)
        return index.copy(
            injectionPointIndex = index.injectionPointIndex.map { injection ->
                val location = sourceLocationIndex.resolve(injection)
                injection.copy(
                    sourcePath = location?.sourcePath,
                    sourceLine = location?.sourceLine,
                    sourceColumn = location?.sourceColumn,
                )
            },
            missingInjectCandidateIndex = index.missingInjectCandidateIndex.mapNotNull { candidate ->
                val fieldAnalysis = sourceLocationIndex.analyzeField(candidate)
                if (fieldAnalysis != null) {
                    if (fieldAnalysis.hasInitializer || fieldAnalysis.hasManualAssignment) {
                        return@mapNotNull null
                    }
                    return@mapNotNull candidate.copy(
                        sourcePath = fieldAnalysis.location.sourcePath,
                        sourceLine = fieldAnalysis.location.sourceLine,
                        sourceColumn = fieldAnalysis.location.sourceColumn,
                    )
                }
                val location = sourceLocationIndex.resolve(candidate)
                candidate.copy(
                    sourcePath = location?.sourcePath,
                    sourceLine = location?.sourceLine,
                    sourceColumn = location?.sourceColumn,
                )
            },
        )
    }

    private fun scanDirectory(
        root: Path,
        collectors: LinkedHashMap<String, ClassCollector>,
    ) {
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }
                .forEach { classFile -> scanClassBytes(Files.readAllBytes(classFile), collectors) }
        }
    }

    private fun scanJar(
        jarPath: Path,
        collectors: LinkedHashMap<String, ClassCollector>,
    ) {
        JarFile(jarPath.toFile()).use { jarFile ->
            jarFile.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".class") }
                .forEach { entry ->
                    jarFile.getInputStream(entry).use { input ->
                        scanClassBytes(input.readBytes(), collectors)
                    }
                }
        }
    }

    private fun scanClassBytes(
        classBytes: ByteArray,
        collectors: LinkedHashMap<String, ClassCollector>,
    ) {
        val collector = ClassCollector()
        ClassReader(classBytes).accept(collector, ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES)
        if (collector.collectorClassName.isNotEmpty()) {
            // 同一 FQCN 出现在多个扫描根时保留先出现的（项目输出优先），与去重策略一致
            collectors.putIfAbsent(collector.collectorClassName, collector)
        }
    }

    private fun enrichGenericMetadata(index: BytecodeAnalysisIndex, scanRoots: List<Path>): BytecodeAnalysisIndex {
        if (scanRoots.isEmpty()) {
            return index
        }
        val urls = scanRoots.map { it.toUri().toURL() }.toTypedArray()
        URLClassLoader(urls, BytecodeBeanIndexBuilder::class.java.classLoader).use { classLoader ->
            val updatedClassIndex = index.classIndex.map { entry ->
                loadClass(entry.className, classLoader)?.let { clazz ->
                    entry.copy(genericSuperTypes = safelyResolveMetadata(emptyList()) { collectGenericSuperTypes(clazz) })
                } ?: entry
            }
            val updatedBeans = index.beanIndex.map { bean -> enrichBeanDefinition(bean, classLoader) }
            val updatedInjections = index.injectionPointIndex.map { injection -> enrichInjectionPoint(injection, classLoader) }
            val updatedMissingInjectCandidates = index.missingInjectCandidateIndex.map { candidate ->
                enrichInjectionPoint(candidate, classLoader)
            }
            return index.copy(
                classIndex = updatedClassIndex,
                beanIndex = updatedBeans,
                injectionPointIndex = updatedInjections,
                missingInjectCandidateIndex = updatedMissingInjectCandidates,
            )
        }
    }

    private fun loadClass(className: String, classLoader: ClassLoader): Class<*>? {
        return safelyResolveMetadata<Class<*>?>(null) {
            Class.forName(className, false, classLoader)
        }
    }

    private fun collectGenericSuperTypes(clazz: Class<*>): List<String> {
        return buildList {
            clazz.genericSuperclass
                ?.typeName
                ?.takeUnless { it == "java.lang.Object" }
                ?.let { add(normalizeTypeName(it)) }
            clazz.genericInterfaces
                .map { normalizeTypeName(it.typeName) }
                .forEach(::add)
        }.distinct().sorted()
    }

    private fun enrichBeanDefinition(bean: BeanDefinition, classLoader: ClassLoader): BeanDefinition {
        if (bean.kind != BeanKind.FACTORY_METHOD) {
            return bean
        }
        val ownerClass = loadClass(bean.ownerClassName, classLoader) ?: return bean
        val genericType = safelyResolveMetadata<String?>(null) {
            val method = ownerClass.declaredMethods.firstOrNull {
                it.name == bean.declarationName && it.returnType.name == bean.exposedType
            } ?: return@safelyResolveMetadata null
            method.genericReturnType.typeName
        }
        return bean.copy(exposedGenericType = genericType?.let(::normalizeTypeName))
    }

    private fun enrichInjectionPoint(injection: InjectionPointDefinition, classLoader: ClassLoader): InjectionPointDefinition {
        val ownerClass = loadClass(injection.ownerClassName, classLoader) ?: return injection
        val genericType = safelyResolveMetadata<String?>(null) {
            when (injection.kind) {
                InjectionPointKind.FIELD -> ownerClass.declaredFields.firstOrNull {
                    it.name == injection.declarationName
                }?.genericType?.typeName

                InjectionPointKind.CONSTRUCTOR_PARAMETER -> ownerClass.declaredConstructors.firstOrNull { constructor ->
                    val parameterIndex = injection.parameterIndex ?: return@firstOrNull false
                    parameterIndex in constructor.parameterTypes.indices && constructor.parameterTypes[parameterIndex].name == injection.dependencyType
                }?.genericParameterTypes?.getOrNull(injection.parameterIndex ?: -1)?.typeName

                InjectionPointKind.METHOD_PARAMETER -> ownerClass.declaredMethods.firstOrNull { method ->
                    val parameterIndex = injection.parameterIndex ?: return@firstOrNull false
                    method.name == injection.declarationName &&
                        parameterIndex in method.parameterTypes.indices &&
                        method.parameterTypes[parameterIndex].name == injection.dependencyType
                }?.genericParameterTypes?.getOrNull(injection.parameterIndex ?: -1)?.typeName
            }
        }
        return injection.copy(dependencyGenericType = genericType?.let(::normalizeTypeName))
    }

    private fun <T> safelyResolveMetadata(defaultValue: T, block: () -> T): T {
        return try {
            block()
        } catch (_: LinkageError) {
            defaultValue
        } catch (_: TypeNotPresentException) {
            defaultValue
        } catch (_: java.lang.reflect.MalformedParameterizedTypeException) {
            defaultValue
        }
    }

    private fun normalizeTypeName(typeName: String): String {
        return typeName
            .replace("?extends", "")
            .replace("?super", "")
            .replace("? extends ", "")
            .replace("? super ", "")
            .replace(" ", "")
    }

    private data class ScannedClass(
        val classIndexEntry: ClassIndexEntry,
        val beanDefinitions: List<BeanDefinition>,
        val injectionPoints: List<InjectionPointDefinition>,
        val missingInjectCandidates: List<InjectionPointDefinition>,
        val componentBeanTypes: List<String>,
        val componentScans: List<ComponentScanDefinition>,
        val aspectDefinitions: List<AspectDefinition>,
        val valueFields: List<ValueFieldDefinition>,
    )

    private data class CapturedAnnotation(
        val className: String,
        val simpleName: String,
        val values: Map<String, Any>,
    )

    private data class PendingField(
        val access: Int,
        val name: String,
        val type: String,
        val annotations: List<CapturedAnnotation>,
        /** 该字段的注解是否来自 Companion 类的 $annotations 载体（H3 跨类合并） */
        val fromCompanion: Boolean = false,
    ) {
        val isFinal: Boolean
            get() = access and Opcodes.ACC_FINAL != 0

        val isStatic: Boolean
            get() = access and Opcodes.ACC_STATIC != 0
    }

    private data class PendingMethod(
        val access: Int,
        val name: String,
        val returnType: String,
        val parameterTypes: List<String>,
        val annotations: List<CapturedAnnotation>,
        val parameterAnnotations: List<List<CapturedAnnotation>>,
    ) {
        val isPrivate: Boolean
            get() = access and Opcodes.ACC_PRIVATE != 0

        val isStaticMethod: Boolean
            get() = access and Opcodes.ACC_STATIC != 0
    }

    private class ClassCollector : ClassVisitor(Opcodes.ASM9) {

        private var className: String = ""
        private var packageName: String = ""
        private var sourceFile: String? = null
        private var superClassName: String? = null
        private var interfaceNames: List<String> = emptyList()
        private var classAccess: Int = 0
        private var skipped: Boolean = false

        private val classAnnotations = mutableListOf<CapturedAnnotation>()
        private val fields = mutableListOf<PendingField>()
        private val methods = mutableListOf<PendingMethod>()
        private var scopeAnnotation: String? = null

        /** H1：Kotlin 属性注解的合成载体方法（`xxx$annotations` / `getXxx$annotations` 等）→ (方法名, 注解) */
        private val annotationCarriers = mutableListOf<Pair<String, List<CapturedAnnotation>>>()

        /** 供 build() 第二阶段读取（companion 跨类合并与以类名建索引用） */
        val collectorClassName: String
            get() = className

        override fun visit(
            version: Int,
            access: Int,
            name: String,
            signature: String?,
            superName: String?,
            interfaces: Array<out String>?,
        ) {
            className = name.replace('/', '.')
            packageName = className.substringBeforeLast('.', missingDelimiterValue = "")
            superClassName = superName?.replace('/', '.')?.takeUnless { it == "java.lang.Object" }
            interfaceNames = interfaces.orEmpty().map { it.replace('/', '.') }
            classAccess = access
            // H2 修复：此前 `className.contains('$')` 会跳过全部嵌套类与 companion object。
            // 现在只跳过匿名类（简单名最后一个 $ 段为空或纯数字，如 Foo$1、Foo$main$1）与合成类
            val simpleName = name.substringAfterLast('/')
            val lastSegment = simpleName.substringAfterLast('$', simpleName)
            skipped = access and Opcodes.ACC_SYNTHETIC != 0 ||
                lastSegment.isEmpty() ||
                lastSegment.all { it.isDigit() }
        }

        override fun visitSource(source: String?, debug: String?) {
            sourceFile = source
        }

        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor {
            return captureAnnotation(descriptor) { classAnnotations += it }
        }

        override fun visitField(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            value: Any?,
        ): FieldVisitor? {
            if (skipped || access and Opcodes.ACC_SYNTHETIC != 0) {
                return null
            }
            val annotations = mutableListOf<CapturedAnnotation>()
            return object : FieldVisitor(Opcodes.ASM9) {
                override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor {
                    return captureAnnotation(descriptor) { annotations += it }
                }

                override fun visitEnd() {
                    fields += PendingField(
                        access = access,
                        name = name,
                        type = Type.getType(descriptor).className,
                        annotations = annotations.toList(),
                    )
                }
            }
        }

        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor? {
            // H1 修复：Kotlin 属性注解编译到合成载体方法 `xxx$annotations` / `getXxx$annotations` 等，
            // 此前 ACC_SYNTHETIC 一刀切过滤导致 Kotlin 属性上的 @Inject/@Value 等注解整体漏读。
            // 现在放行载体方法（仍不入 methods 索引），把其注解合并回同名字段。
            val isAnnotationCarrier = name.endsWith("\$annotations")
            if (skipped ||
                (access and Opcodes.ACC_SYNTHETIC != 0 && !isAnnotationCarrier) ||
                access and Opcodes.ACC_BRIDGE != 0
            ) {
                return null
            }
            val annotations = mutableListOf<CapturedAnnotation>()
            val parameterTypes = Type.getArgumentTypes(descriptor).map { it.className }
            val parameterAnnotations = MutableList(parameterTypes.size) { mutableListOf<CapturedAnnotation>() }
            return object : MethodVisitor(Opcodes.ASM9) {
                override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor {
                    return captureAnnotation(descriptor) { annotations += it }
                }

                override fun visitParameterAnnotation(parameter: Int, descriptor: String, visible: Boolean): AnnotationVisitor {
                    return captureAnnotation(descriptor) { annotation ->
                        if (parameter in parameterAnnotations.indices) {
                            parameterAnnotations[parameter] += annotation
                        }
                    }
                }

                override fun visitEnd() {
                    if (isAnnotationCarrier) {
                        annotationCarriers += name to annotations.toList()
                        return
                    }
                    methods += PendingMethod(
                        access = access,
                        name = name,
                        returnType = Type.getReturnType(descriptor).className,
                        parameterTypes = parameterTypes,
                        annotations = annotations.toList(),
                        parameterAnnotations = parameterAnnotations.map { it.toList() },
                    )
                }
            }
        }

        fun toScannedClass(companionCarriers: Map<String, List<CapturedAnnotation>> = emptyMap()): ScannedClass? {
            if (skipped || className.isEmpty()) {
                return null
            }
            mergeCarrierAnnotations(companionCarriers)

            val classIndexEntry = ClassIndexEntry(
                className = className,
                packageName = packageName,
                sourceFile = sourceFile,
                superClassName = superClassName,
                interfaceNames = interfaceNames,
                fields = extractFieldsInfo(),
                isInterface = classAccess and Opcodes.ACC_INTERFACE != 0,
                isAbstract = classAccess and Opcodes.ACC_ABSTRACT != 0,
                methods = extractMethodsInfo(),
            )
            val isKotlinObjectSingleton = isKotlinObjectSingleton()
            val classBeanStereotype = classAnnotations.beanClassStereotype()
            val isBeanClass = classBeanStereotype != null
            val classConditions = classAnnotations.conditionDescriptors()
            val beans = mutableListOf<BeanDefinition>()
            val componentBeanTypes = mutableListOf<String>()
            if (isBeanClass) {
                if (classBeanStereotype == "Component") {
                    componentBeanTypes += className
                }
                val constructorMetadata = buildConstructorMetadata(isBeanClass)
                val scope = extractScope()
                val lifecycleMethods = extractLifecycleMethods()
                val dependencies = extractDependencies(isBeanClass)
                
                beans += BeanDefinition(
                    ownerClassName = className,
                    declarationName = className.substringAfterLast('.'),
                    beanName = resolveBeanName(classAnnotations, defaultBeanName(className.substringAfterLast('.'))),
                    exposedType = className,
                    packageName = packageName,
                    sourceFile = sourceFile,
                    kind = BeanKind.CLASS,
                    exposedGenericType = null,
                    primary = classAnnotations.hasAnnotation("Primary"),
                    order = classAnnotations.annotationValue("Order", "value") as? Int,
                    conditionalAnnotations = classConditions.map { it.annotationName },
                    conditions = classConditions,
                    constructorMetadata = constructorMetadata,
                    stereotypeAnnotation = classBeanStereotype,
                    scope = scope,
                    lifecycleMethods = lifecycleMethods,
                    lifecycleMethodDetails = extractLifecycleMethodDetails(),
                    dependencies = dependencies,
                )
            }

            val scans = classAnnotations.findAnnotation("ComponentScan")
                ?.let { annotation ->
                    val packages = componentScanPackages(annotation).ifEmpty {
                        if (packageName.isEmpty()) emptyList() else listOf(packageName)
                    }
                    listOf(
                        ComponentScanDefinition(
                            ownerClassName = className,
                            packageName = packageName,
                            sourceFile = sourceFile,
                            basePackages = packages,
                        ),
                    )
                }
                ?: emptyList()

            methods.filter { it.annotations.hasAnnotation("Bean") }.forEach { method ->
                val methodConditions = (classConditions + method.annotations.conditionDescriptors())
                    .distinctBy { it.annotationName + it.attributes.toString() }
                val methodScope = extractMethodScope(method.annotations)

                beans += BeanDefinition(
                    ownerClassName = className,
                    declarationName = method.name,
                    beanName = resolveBeanName(method.annotations, method.name),
                    exposedType = method.returnType,
                    packageName = packageName,
                    sourceFile = sourceFile,
                    kind = BeanKind.FACTORY_METHOD,
                    exposedGenericType = null,
                    primary = classAnnotations.hasAnnotation("Primary") || method.annotations.hasAnnotation("Primary"),
                    order = (method.annotations.annotationValue("Order", "value") as? Int)
                        ?: (classAnnotations.annotationValue("Order", "value") as? Int),
                    conditionalAnnotations = methodConditions.map { it.annotationName },
                    conditions = methodConditions,
                    scope = methodScope,
                    lifecycleMethods = LifecycleMethodsInfo(), // @Bean 方法不需要生命周期方法
                    dependencies = emptyList(), // @Bean 方法的依赖通过参数注入，已在 injectionPointIndex 中
                    // 运行时 ConfigurationScanner 只在 @Configuration 分支被调用，
                    // 非 @Configuration 宿主上的 @Bean 方法永远不会被注册（bean-method-outside-configuration）
                    factoryHostIsConfiguration = classAnnotations.hasAnnotation("Configuration"),
                    factoryMethodReturnsVoid = method.returnType == "void",
                )
            }

            val injections = mutableListOf<InjectionPointDefinition>()
            fields.filter { field ->
                field.annotations.containsInjectionMetadata() &&
                    (!field.isStatic || isKotlinObjectSingleton || field.fromCompanion) &&
                    field.name != "INSTANCE"
            }.forEach { field ->
                injections += InjectionPointDefinition(
                    ownerClassName = className,
                    declarationName = field.name,
                    dependencyType = field.type,
                    dependencyGenericType = null,
                    ownerPackage = packageName,
                    sourceFile = sourceFile,
                    sourcePath = null,
                    sourceLine = null,
                    sourceColumn = null,
                    kind = InjectionPointKind.FIELD,
                    parameterIndex = null,
                    qualifierName = field.annotations.qualifierName(),
                    required = field.annotations.requiredFlag(defaultValue = true),
                    lazy = field.annotations.lazyFlag(),
                )
            }
            val missingInjectCandidates = fields.filter { field ->
                !field.annotations.containsInjectionMetadata() &&
                    !field.isFinal &&
                    (!field.isStatic || isKotlinObjectSingleton) &&
                    field.name != "INSTANCE"
            }.map { field ->
                InjectionPointDefinition(
                    ownerClassName = className,
                    declarationName = field.name,
                    dependencyType = field.type,
                    dependencyGenericType = null,
                    ownerPackage = packageName,
                    sourceFile = sourceFile,
                    sourcePath = null,
                    sourceLine = null,
                    sourceColumn = null,
                    kind = InjectionPointKind.FIELD,
                    parameterIndex = null,
                    qualifierName = null,
                    required = true,
                    lazy = false,
                )
            }

            selectConstructorMethods(isBeanClass).forEach { constructor ->
                constructor.parameterTypes.forEachIndexed { index, parameterType ->
                    val parameterAnnotations = constructor.parameterAnnotations.getOrElse(index) { emptyList() }
                    injections += InjectionPointDefinition(
                        ownerClassName = className,
                        declarationName = "constructor[$index]",
                        dependencyType = parameterType,
                        dependencyGenericType = null,
                        ownerPackage = packageName,
                        sourceFile = sourceFile,
                        sourcePath = null,
                        sourceLine = null,
                        sourceColumn = null,
                        kind = InjectionPointKind.CONSTRUCTOR_PARAMETER,
                        parameterIndex = index,
                        qualifierName = parameterAnnotations.qualifierName(),
                        required = parameterAnnotations.requiredFlag(
                            defaultValue = constructor.annotations.requiredFlag(defaultValue = true),
                        ),
                        lazy = parameterAnnotations.lazyFlag(),
                    )
                }
            }

            methods.filter { method ->
                method.name != "<init>" && (
                    method.annotations.hasAnnotation("Bean") ||
                        method.annotations.hasAnnotation("Inject") ||
                        method.parameterAnnotations.any { it.containsInjectionMetadata() }
                    )
            }.forEach { method ->
                method.parameterTypes.forEachIndexed { index, parameterType ->
                    val parameterAnnotations = method.parameterAnnotations.getOrElse(index) { emptyList() }
                    injections += InjectionPointDefinition(
                        ownerClassName = className,
                        declarationName = method.name,
                        dependencyType = parameterType,
                        dependencyGenericType = null,
                        ownerPackage = packageName,
                        sourceFile = sourceFile,
                        sourcePath = null,
                        sourceLine = null,
                        sourceColumn = null,
                        kind = InjectionPointKind.METHOD_PARAMETER,
                        parameterIndex = index,
                        qualifierName = parameterAnnotations.qualifierName(),
                        required = parameterAnnotations.requiredFlag(
                            defaultValue = method.annotations.requiredFlag(defaultValue = true),
                        ),
                        lazy = parameterAnnotations.lazyFlag(),
                    )
                }
            }

            return ScannedClass(
                classIndexEntry = classIndexEntry,
                beanDefinitions = beans,
                injectionPoints = injections,
                missingInjectCandidates = missingInjectCandidates,
                componentBeanTypes = componentBeanTypes,
                componentScans = scans,
                aspectDefinitions = listOfNotNull(extractAspectDefinition()),
                valueFields = extractValueFields(),
            )
        }

        private fun selectConstructorMethods(isBeanClass: Boolean): List<PendingMethod> {
            val constructor = resolveRuntimeConstructor(isBeanClass) ?: return emptyList()
            return if (constructor.parameterTypes.isNotEmpty()) listOf(constructor) else emptyList()
        }

        private fun resolveRuntimeConstructor(isBeanClass: Boolean): PendingMethod? {
            if (!isBeanClass) {
                return null
            }
            val constructors = methods.filter { it.name == "<init>" }
            if (constructors.isEmpty()) {
                return null
            }
            val annotatedConstructors = constructors.filter { it.annotations.hasAnnotation("Inject") }
            if (annotatedConstructors.isNotEmpty()) {
                return annotatedConstructors.first()
            }
            if (constructors.size == 1) {
                return constructors.first()
            }
            return constructors.firstOrNull { it.parameterTypes.isEmpty() }
        }

        private fun buildConstructorMetadata(isBeanClass: Boolean): ConstructorMetadata? {
            if (!isBeanClass) {
                return null
            }
            val allConstructors = methods.filter { it.name == "<init>" }
            val hasExplicitInject = allConstructors.any { it.annotations.hasAnnotation("Inject") }
            val runtimeConstructor = resolveRuntimeConstructor(isBeanClass)
            val runtimeSelectedConstructorHasParameters = runtimeConstructor?.parameterTypes?.isNotEmpty() == true
            val runtimeSelectedConstructorHasNonNullableParameters =
                classAnnotations.hasAnnotation("Metadata") && runtimeSelectedConstructorHasParameters

            return ConstructorMetadata(
                hasExplicitInjectConstructor = hasExplicitInject,
                totalConstructorCount = allConstructors.size,
                runtimeSelectedConstructorHasParameters = runtimeSelectedConstructorHasParameters,
                runtimeSelectedConstructorHasNonNullableParameters = runtimeSelectedConstructorHasNonNullableParameters,
                hasNoArgConstructor = allConstructors.any { it.parameterTypes.isEmpty() },
            )
        }

        private fun isKotlinObjectSingleton(): Boolean {
            if (!classAnnotations.hasAnnotation("Metadata")) {
                return false
            }
            val hasInstanceField = fields.any { field ->
                field.name == "INSTANCE" && field.type == className && field.isStatic && field.isFinal
            }
            val hasPrivateNoArgConstructor = methods.any { method ->
                method.name == "<init>" && method.parameterTypes.isEmpty() && method.isPrivate
            }
            return hasInstanceField && hasPrivateNoArgConstructor
        }

        private fun extractScope(): String {
            // 检查 @Scope 注解
            classAnnotations.findAnnotation("Scope")?.let { annotation ->
                return annotation.values["value"] as? String ?: "singleton"
            }
            
            // 检查其他作用域注解
            when {
                classAnnotations.hasAnnotation("RefreshScope") -> return "refresh"
                classAnnotations.hasAnnotation("ThreadScope") -> return "thread"
                classAnnotations.hasAnnotation("Prototype") -> return "prototype"
            }
            
            // 默认为 singleton
            return "singleton"
        }

        private fun extractMethodScope(methodAnnotations: List<CapturedAnnotation>): String {
            // 检查方法级别的 @Scope 注解
            methodAnnotations.findAnnotation("Scope")?.let { annotation ->
                return annotation.values["value"] as? String ?: "singleton"
            }
            
            // 检查其他作用域注解
            when {
                methodAnnotations.hasAnnotation("RefreshScope") -> return "refresh"
                methodAnnotations.hasAnnotation("ThreadScope") -> return "thread"
                methodAnnotations.hasAnnotation("Prototype") -> return "prototype"
            }
            
            // 如果方法上没有，则使用类级别的作用域
            return extractScope()
        }

        private fun extractLifecycleMethods(): LifecycleMethodsInfo {
            val postConstructMethods = mutableListOf<String>()
            val preDestroyMethods = mutableListOf<String>()
            val postEnableMethods = mutableListOf<String>()

            methods.forEach { method ->
                when {
                    method.annotations.hasAnnotation("PostConstruct") -> postConstructMethods += method.name
                    method.annotations.hasAnnotation("PreDestroy") -> preDestroyMethods += method.name
                    method.annotations.hasAnnotation("PostEnable") -> postEnableMethods += method.name
                }
            }

            return LifecycleMethodsInfo(
                postConstructMethods = postConstructMethods,
                preDestroyMethods = preDestroyMethods,
                postEnableMethods = postEnableMethods,
            )
        }

        /**
         * 生命周期方法签名明细（含参数个数与是否静态）。
         * 运行时 Injector 以 `method.invoke(instance)` 零参硬调用，
         * 带参方法（含 Kotlin suspend 编译出的 Continuation 参数）会抛 IllegalArgumentException 使 Bean 创建失败。
         */
        private fun extractLifecycleMethodDetails(): List<LifecycleMethodDetail> {
            return methods.flatMap { method ->
                sequence {
                    if (method.annotations.hasAnnotation("PostConstruct")) {
                        yield(LifecycleMethodDetail("PostConstruct", method.name, method.parameterTypes.size, method.isStaticMethod))
                    }
                    if (method.annotations.hasAnnotation("PreDestroy")) {
                        yield(LifecycleMethodDetail("PreDestroy", method.name, method.parameterTypes.size, method.isStaticMethod))
                    }
                    if (method.annotations.hasAnnotation("PostEnable")) {
                        yield(LifecycleMethodDetail("PostEnable", method.name, method.parameterTypes.size, method.isStaticMethod))
                    }
                }
            }
        }

        /**
         * 采集 @Aspect 切面类的 @Pointcut 命名切点与通知方法表达式，
         * 供静态引擎复刻 AspectScanner/PointcutExpression 的运行时解析判定。
         */
        private fun extractAspectDefinition(): AspectDefinition? {
            if (!classAnnotations.hasAnnotation("Aspect")) {
                return null
            }
            val pointcutMethods = methods
                .filter { it.annotations.hasAnnotation("Pointcut") }
                .associate { method ->
                    method.name to (method.annotations.findAnnotation("Pointcut")?.values?.get("value") as? String).orEmpty()
                }
            val adviceAnnotations = listOf("Before", "After", "Around", "AfterReturning", "AfterThrowing")
            val advices = methods.flatMap { method ->
                adviceAnnotations.mapNotNull { adviceName ->
                    val expression = method.annotations.findAnnotation(adviceName)?.values?.get("value") as? String
                        ?: return@mapNotNull null
                    AspectAdviceDefinition(
                        adviceAnnotation = adviceName,
                        methodName = method.name,
                        expression = expression,
                        parameterTypes = method.parameterTypes,
                    )
                }
            }
            return AspectDefinition(
                aspectClassName = className,
                packageName = packageName,
                sourceFile = sourceFile,
                pointcutMethods = pointcutMethods,
                advices = advices,
            )
        }

        /**
         * 采集 @Value 字段（表达式 + 目标类型）。
         * 运行时 ValueResolver 用 PLACEHOLDER_REGEX.matchEntire 判定占位符：
         * 包含 ${...} 但不满足整串匹配的表达式会被当作纯字面量注入。
         */
        private fun extractValueFields(): List<ValueFieldDefinition> {
            return fields.mapNotNull { field ->
                val expression = field.annotations.findAnnotation("Value")?.values?.get("value") as? String
                    ?: return@mapNotNull null
                ValueFieldDefinition(
                    ownerClassName = className,
                    fieldName = field.name,
                    expression = expression,
                    targetType = field.type,
                    packageName = packageName,
                    sourceFile = sourceFile,
                )
            }
        }

        private fun extractDependencies(isBeanClass: Boolean): List<DependencyReference> {
            if (!isBeanClass) {
                return emptyList()
            }

            val dependencies = mutableListOf<DependencyReference>()

            // 从构造函数参数提取依赖
            val constructor = resolveRuntimeConstructor(isBeanClass)
            constructor?.let { ctor ->
                ctor.parameterTypes.forEachIndexed { index, paramType ->
                    val paramAnnotations = ctor.parameterAnnotations.getOrElse(index) { emptyList() }
                    dependencies += DependencyReference(
                        targetBeanName = paramAnnotations.qualifierName(),
                        targetType = paramType,
                        kind = InjectionPointKind.CONSTRUCTOR_PARAMETER,
                        declarationName = "constructor[$index]",
                    )
                }
            }

            // 从 @Inject 字段提取依赖
            val isKotlinObjectSingleton = isKotlinObjectSingleton()
            fields.filter { field ->
                field.annotations.containsInjectionMetadata() &&
                    (!field.isStatic || isKotlinObjectSingleton || field.fromCompanion) &&
                    field.name != "INSTANCE"
            }.forEach { field ->
                dependencies += DependencyReference(
                    targetBeanName = field.annotations.qualifierName(),
                    targetType = field.type,
                    kind = InjectionPointKind.FIELD,
                    declarationName = field.name,
                )
            }

            // 从 @Inject 方法参数提取依赖
            methods.filter { method ->
                method.name != "<init>" && (
                    method.annotations.hasAnnotation("Inject") ||
                        method.parameterAnnotations.any { it.containsInjectionMetadata() }
                    )
            }.forEach { method ->
                method.parameterTypes.forEachIndexed { index, paramType ->
                    val paramAnnotations = method.parameterAnnotations.getOrElse(index) { emptyList() }
                    dependencies += DependencyReference(
                        targetBeanName = paramAnnotations.qualifierName(),
                        targetType = paramType,
                        kind = InjectionPointKind.METHOD_PARAMETER,
                        declarationName = method.name,
                    )
                }
            }

            return dependencies
        }

        private fun extractFieldsInfo(): List<FieldInfo> {
            return fields.map { field ->
                FieldInfo(
                    name = field.name,
                    type = field.type,
                    descriptor = field.type, // 使用类型作为描述符
                )
            }
        }

        /** 方法清单（不含构造器与合成方法），供 AOP 静默失效规则组做方法级匹配 */
        private fun extractMethodsInfo(): List<CollectedMethodInfo> {
            return methods.filter { it.name != "<init>" }.map { method ->
                CollectedMethodInfo(
                    name = method.name,
                    isPrivate = method.isPrivate,
                    isStatic = method.isStaticMethod,
                )
            }
        }

        /** 把本类 $annotations 载体方法按属性名归并：`name$annotations` / `getName$annotations` / `isX` / `setX` */
        fun carrierAnnotations(): Map<String, List<CapturedAnnotation>> {
            val map = HashMap<String, MutableList<CapturedAnnotation>>()
            for ((methodName, annotations) in annotationCarriers) {
                val base = methodName.removeSuffix("\$annotations")
                val candidates = mutableListOf(base)
                for (prefix in listOf("get", "set", "is")) {
                    if (base.length > prefix.length && base.startsWith(prefix) && base[prefix.length].isUpperCase()) {
                        candidates += base.removePrefix(prefix).replaceFirstChar { it.lowercaseChar() }
                    }
                }
                for (candidate in candidates) {
                    map.getOrPut(candidate) { mutableListOf() }.addAll(annotations)
                }
            }
            return map
        }

        /**
         * H1/H3：把 $annotations 载体注解合并回同名字段。
         * - 本类载体：Kotlin `var name` 的注解在 `getName$annotations` / `name$annotations` 上；
         * - companion 载体（H3）：@JvmField companion 属性的 backing field 在外部类的静态字段上，
         *   而注解载体在 Companion 类 —— 由 build() 第二阶段传入。
         */
        private fun mergeCarrierAnnotations(companionCarriers: Map<String, List<CapturedAnnotation>>) {
            if (annotationCarriers.isEmpty() && companionCarriers.isEmpty()) {
                return
            }
            val extraFor = HashMap<String, List<CapturedAnnotation>>()
            carrierAnnotations().forEach { (propertyName, annotations) ->
                extraFor[propertyName] = annotations
            }
            companionCarriers.forEach { (propertyName, annotations) ->
                extraFor[propertyName] = (extraFor[propertyName].orEmpty() + annotations)
                    .distinctBy { it.simpleName }
            }
            for (index in fields.indices) {
                val field = fields[index]
                val extra = extraFor[field.name].orEmpty()
                    .filter { new -> field.annotations.none { it.simpleName == new.simpleName } }
                if (extra.isEmpty()) {
                    continue
                }
                fields[index] = field.copy(
                    annotations = field.annotations + extra,
                    fromCompanion = companionCarriers.containsKey(field.name),
                )
            }
        }

        private fun captureAnnotation(descriptor: String, onComplete: (CapturedAnnotation) -> Unit): AnnotationVisitor {
            val annotationClassName = Type.getType(descriptor).className
            val values = linkedMapOf<String, Any>()
            return object : AnnotationVisitor(Opcodes.ASM9) {
                override fun visit(name: String?, value: Any?) {
                    if (name != null && value != null) {
                        values[name] = normalizeAnnotationValue(value)
                    }
                }

                override fun visitEnum(name: String?, descriptor: String?, value: String?) {
                    if (name != null && value != null) {
                        values[name] = value
                    }
                }

                override fun visitArray(name: String?): AnnotationVisitor {
                    val arrayValues = mutableListOf<Any>()
                    return object : AnnotationVisitor(Opcodes.ASM9) {
                        override fun visit(name: String?, value: Any?) {
                            if (value != null) {
                                arrayValues += normalizeAnnotationValue(value)
                            }
                        }

                        override fun visitEnd() {
                            values[name ?: "value"] = arrayValues.toList()
                        }
                    }
                }

                override fun visitEnd() {
                    onComplete(
                        CapturedAnnotation(
                            className = annotationClassName,
                            simpleName = annotationClassName.substringAfterLast('.'),
                            values = values.toMap(),
                        ),
                    )
                }
            }
        }

        private fun normalizeAnnotationValue(value: Any): Any {
            return when (value) {
                is Type -> value.className
                else -> value
            }
        }
    }

    /**
     * C-P2-21：注入元数据判定改用**注解 FQCN**，避免跨包同名注解（如第三方
     * `com.foo.Inject` / `com.bar.Named` / `com.baz.Resource`）被简单名误命中，
     * 从而把非 IoC 字段误当成注入点。
     *
     * 同时保留简单名回退：工程自带注解、或注解类未被扫描到时仍可识别，
     * 避免因 FQCN 缺失而漏采集现有合法注入点。
     */
    private fun List<CapturedAnnotation>.containsInjectionMetadata(): Boolean {
        return IOC_INJECTION_ANNOTATIONS.any { fqcn -> hasAnnotationFqcn(fqcn) } ||
            (hasAnnotation("Inject") && !looksForeign(FOREIGN_INJECT_FQCNS)) ||
            (hasAnnotation("Named") && !looksForeign(FOREIGN_NAMED_FQCNS)) ||
            (hasAnnotation("Resource") && !looksForeign(FOREIGN_RESOURCE_FQCNS))
    }

    /** 同名但**非 Taboolib IoC** 的注解 FQCN 黑名单（跨包同名误命中防护） */
    private fun List<CapturedAnnotation>.looksForeign(foreignFqcns: Set<String>): Boolean {
        val annotation = firstOrNull { it.simpleName in setOf("Inject", "Named", "Resource") }
        return annotation != null && annotation.className in foreignFqcns
    }

    private fun List<CapturedAnnotation>.beanClassStereotype(): String? {
        return when {
            hasAnnotation("Component") -> "Component"
            hasAnnotation("Service") -> "Component"
            hasAnnotation("Repository") -> "Component"
            hasAnnotation("Controller") -> "Component"
            hasAnnotation("Aspect") -> "Component"
            hasAnnotation("Configuration") -> "Configuration"
            hasAnnotation("Bean") -> "Bean"
            else -> null
        }
    }

    private fun List<CapturedAnnotation>.conditionDescriptors(): List<ConditionDescriptor> {
        return filter { it.simpleName.startsWith("Conditional") }
            .map { ConditionDescriptor(annotationName = it.simpleName, attributes = it.values) }
    }

    private fun List<CapturedAnnotation>.qualifierName(): String? {
        val named = findAnnotation("Named")?.values?.get("value") as? String
        val resource = findAnnotation("Resource")?.values?.get("name") as? String
        return listOf(named, resource).firstOrNull { !it.isNullOrBlank() }
    }

    private fun List<CapturedAnnotation>.requiredFlag(defaultValue: Boolean): Boolean {
        val injectRequired = findAnnotation("Inject")?.values?.get("required") as? Boolean
        val resourceRequired = findAnnotation("Resource")?.values?.get("required") as? Boolean
        return injectRequired ?: resourceRequired ?: defaultValue
    }

    /**
     * K6 修复：采集 @Lazy 注入标记。
     *
     * 陷阱 2：`@Lazy(val value: Boolean = true)` 的默认值为 true，但 `@Lazy(false)` 语义是
     * 「**不延迟**」。因此必须读取注解的 `value` 属性（与运行时
     * `ClassScanner.resolveInjectFields` 的 `lazy?.value == true` 逐字同源），
     * **不能**只判断 `hasAnnotation("Lazy")` —— 否则会把 `@Lazy(false)` 误判为延迟，
     * 静态侧过滤掉真正的环边 → 漏报真环。
     *
     * 注解缺省 value 时（Kotlin/Java 编译器不写出默认值）回退到 defaultValue=true，
     * 与注解声明默认值一致。
     */
    private fun List<CapturedAnnotation>.lazyFlag(defaultValue: Boolean = false): Boolean {
        val annotation = findAnnotation("Lazy") ?: return defaultValue
        return annotation.values["value"] as? Boolean ?: true
    }

    private fun List<CapturedAnnotation>.hasAnnotation(simpleName: String): Boolean {
        return any { it.simpleName == simpleName }
    }

    /**
     * C-P2-21：按 FQCN 匹配注解，避免跨包同名注解（如第三方 `com.foo.Inject`）被简单名误命中。
     * 注解全限定名以 `.` 结尾（包名后接简单名）。
     */
    private fun List<CapturedAnnotation>.hasAnnotationFqcn(fqcn: String): Boolean {
        return any { it.className == fqcn }
    }

    private fun List<CapturedAnnotation>.findAnnotation(simpleName: String): CapturedAnnotation? {
        return firstOrNull { it.simpleName == simpleName }
    }

    private fun List<CapturedAnnotation>.annotationValue(simpleName: String, key: String): Any? {
        return findAnnotation(simpleName)?.values?.get(key)
    }

    private fun resolveBeanName(annotations: List<CapturedAnnotation>, defaultName: String): String {
        val beanAnnotation = sequenceOf("Bean", "Component", "Service", "Repository", "Controller")
            .mapNotNull { annotationName -> annotations.findAnnotation(annotationName) }
            .firstOrNull()
            ?: return defaultName
        val explicitName = sequenceOf("beanName", "name", "value")
            .mapNotNull { beanAnnotation.values[it] as? String }
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
        return explicitName ?: defaultName
    }

    private fun componentScanPackages(annotation: CapturedAnnotation): List<String> {
        return sequenceOf("basePackages", "value")
            .mapNotNull { key -> annotation.values[key] }
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

    private fun defaultBeanName(simpleName: String): String {
        if (simpleName.isEmpty()) {
            return simpleName
        }
        return simpleName.replaceFirstChar { it.lowercase(Locale.getDefault()) }
    }
}
