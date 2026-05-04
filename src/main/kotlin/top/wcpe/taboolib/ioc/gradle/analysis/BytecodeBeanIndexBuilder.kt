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

    fun build(
        classpathEntries: Iterable<Path>,
        sourceDirectories: Iterable<Path> = emptyList(),
    ): BytecodeAnalysisIndex {
        val classIndex = mutableListOf<ClassIndexEntry>()
        val beanIndex = mutableListOf<BeanDefinition>()
        val injectionPointIndex = mutableListOf<InjectionPointDefinition>()
        val missingInjectCandidateIndex = mutableListOf<InjectionPointDefinition>()
        val componentBeanTypes = mutableListOf<String>()
        val componentScans = mutableListOf<ComponentScanDefinition>()
        val existingEntries = classpathEntries.filter { Files.exists(it) }.distinct()

        existingEntries.forEach { entry ->
            when {
                Files.isDirectory(entry) -> scanDirectory(
                    entry,
                    classIndex,
                    beanIndex,
                    injectionPointIndex,
                    missingInjectCandidateIndex,
                    componentBeanTypes,
                    componentScans,
                )

                Files.isRegularFile(entry) && entry.toString().endsWith(".jar") -> {
                    scanJar(
                        entry,
                        classIndex,
                        beanIndex,
                        injectionPointIndex,
                        missingInjectCandidateIndex,
                        componentBeanTypes,
                        componentScans,
                    )
                }
            }
        }

        val rawIndex = BytecodeAnalysisIndex(
            classIndex = classIndex.distinct().sortedBy { it.className },
            beanIndex = beanIndex.distinct().sortedWith(compareBy({ it.ownerClassName }, { it.declarationName }, { it.beanName })),
            injectionPointIndex = injectionPointIndex.distinct().sortedWith(compareBy({ it.ownerClassName }, { it.declarationName }, { it.kind.name })),
            missingInjectCandidateIndex = missingInjectCandidateIndex.distinct().sortedWith(compareBy({ it.ownerClassName }, { it.declarationName }, { it.kind.name })),
            componentBeanTypes = componentBeanTypes.distinct().sorted(),
            componentScans = componentScans.distinct().sortedBy { it.ownerClassName },
        )
        val genericIndex = enrichGenericMetadata(rawIndex, existingEntries)
        return enrichSourceLocations(genericIndex, sourceDirectories)
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
        classIndex: MutableList<ClassIndexEntry>,
        beanIndex: MutableList<BeanDefinition>,
        injectionPointIndex: MutableList<InjectionPointDefinition>,
        missingInjectCandidateIndex: MutableList<InjectionPointDefinition>,
        componentBeanTypes: MutableList<String>,
        componentScans: MutableList<ComponentScanDefinition>,
    ) {
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }
                .forEach { classFile ->
                    scanClassBytes(
                        Files.readAllBytes(classFile),
                        classIndex,
                        beanIndex,
                        injectionPointIndex,
                        missingInjectCandidateIndex,
                        componentBeanTypes,
                        componentScans,
                    )
                }
        }
    }

    private fun scanJar(
        jarPath: Path,
        classIndex: MutableList<ClassIndexEntry>,
        beanIndex: MutableList<BeanDefinition>,
        injectionPointIndex: MutableList<InjectionPointDefinition>,
        missingInjectCandidateIndex: MutableList<InjectionPointDefinition>,
        componentBeanTypes: MutableList<String>,
        componentScans: MutableList<ComponentScanDefinition>,
    ) {
        JarFile(jarPath.toFile()).use { jarFile ->
            jarFile.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".class") }
                .forEach { entry ->
                    jarFile.getInputStream(entry).use { input ->
                        scanClassBytes(
                            input.readBytes(),
                            classIndex,
                            beanIndex,
                            injectionPointIndex,
                            missingInjectCandidateIndex,
                            componentBeanTypes,
                            componentScans,
                        )
                    }
                }
        }
    }

    private fun scanClassBytes(
        classBytes: ByteArray,
        classIndex: MutableList<ClassIndexEntry>,
        beanIndex: MutableList<BeanDefinition>,
        injectionPointIndex: MutableList<InjectionPointDefinition>,
        missingInjectCandidateIndex: MutableList<InjectionPointDefinition>,
        componentBeanTypes: MutableList<String>,
        componentScans: MutableList<ComponentScanDefinition>,
    ) {
        val collector = ClassCollector()
        ClassReader(classBytes).accept(collector, ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES)
        val scannedClass = collector.toScannedClass() ?: return
        classIndex += scannedClass.classIndexEntry
        beanIndex += scannedClass.beanDefinitions
        injectionPointIndex += scannedClass.injectionPoints
        missingInjectCandidateIndex += scannedClass.missingInjectCandidates
        componentBeanTypes += scannedClass.componentBeanTypes
        componentScans += scannedClass.componentScans
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
    }

    private class ClassCollector : ClassVisitor(Opcodes.ASM9) {

        private var className: String = ""
        private var packageName: String = ""
        private var sourceFile: String? = null
        private var superClassName: String? = null
        private var interfaceNames: List<String> = emptyList()
        private var skipped: Boolean = false

        private val classAnnotations = mutableListOf<CapturedAnnotation>()
        private val fields = mutableListOf<PendingField>()
        private val methods = mutableListOf<PendingMethod>()
        private var scopeAnnotation: String? = null

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
            skipped = className.contains('$') || access and Opcodes.ACC_SYNTHETIC != 0
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
            if (skipped || access and Opcodes.ACC_SYNTHETIC != 0 || access and Opcodes.ACC_BRIDGE != 0) {
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

        fun toScannedClass(): ScannedClass? {
            if (skipped || className.isEmpty()) {
                return null
            }

            val classIndexEntry = ClassIndexEntry(
                className = className,
                packageName = packageName,
                sourceFile = sourceFile,
                superClassName = superClassName,
                interfaceNames = interfaceNames,
                fields = extractFieldsInfo(),
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
                )
            }

            val injections = mutableListOf<InjectionPointDefinition>()
            fields.filter { field ->
                field.annotations.containsInjectionMetadata() &&
                    (!field.isStatic || isKotlinObjectSingleton) &&
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
                    (!field.isStatic || isKotlinObjectSingleton) &&
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

    private fun List<CapturedAnnotation>.containsInjectionMetadata(): Boolean {
        return hasAnnotation("Inject") || hasAnnotation("Named") || hasAnnotation("Resource")
    }

    private fun List<CapturedAnnotation>.beanClassStereotype(): String? {
        return when {
            hasAnnotation("Component") -> "Component"
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

    private fun List<CapturedAnnotation>.hasAnnotation(simpleName: String): Boolean {
        return any { it.simpleName == simpleName }
    }

    private fun List<CapturedAnnotation>.findAnnotation(simpleName: String): CapturedAnnotation? {
        return firstOrNull { it.simpleName == simpleName }
    }

    private fun List<CapturedAnnotation>.annotationValue(simpleName: String, key: String): Any? {
        return findAnnotation(simpleName)?.values?.get(key)
    }

    private fun resolveBeanName(annotations: List<CapturedAnnotation>, defaultName: String): String {
        val beanAnnotation = sequenceOf("Bean", "Component")
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
