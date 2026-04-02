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

    fun build(classpathEntries: Iterable<Path>): BytecodeAnalysisIndex {
        val classIndex = mutableListOf<ClassIndexEntry>()
        val beanIndex = mutableListOf<BeanDefinition>()
        val injectionPointIndex = mutableListOf<InjectionPointDefinition>()
        val componentScans = mutableListOf<ComponentScanDefinition>()
        val existingEntries = classpathEntries.filter { Files.exists(it) }.distinct()

        existingEntries.forEach { entry ->
            when {
                Files.isDirectory(entry) -> scanDirectory(entry, classIndex, beanIndex, injectionPointIndex, componentScans)
                Files.isRegularFile(entry) && entry.toString().endsWith(".jar") -> {
                    scanJar(entry, classIndex, beanIndex, injectionPointIndex, componentScans)
                }
            }
        }

        val rawIndex = BytecodeAnalysisIndex(
            classIndex = classIndex.distinct().sortedBy { it.className },
            beanIndex = beanIndex.distinct().sortedWith(compareBy({ it.ownerClassName }, { it.declarationName }, { it.beanName })),
            injectionPointIndex = injectionPointIndex.distinct().sortedWith(compareBy({ it.ownerClassName }, { it.declarationName }, { it.kind.name })),
            componentScans = componentScans.distinct().sortedBy { it.ownerClassName },
        )
        return enrichGenericMetadata(rawIndex, existingEntries)
    }

    private fun scanDirectory(
        root: Path,
        classIndex: MutableList<ClassIndexEntry>,
        beanIndex: MutableList<BeanDefinition>,
        injectionPointIndex: MutableList<InjectionPointDefinition>,
        componentScans: MutableList<ComponentScanDefinition>,
    ) {
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }
                .forEach { classFile ->
                    scanClassBytes(Files.readAllBytes(classFile), classIndex, beanIndex, injectionPointIndex, componentScans)
                }
        }
    }

    private fun scanJar(
        jarPath: Path,
        classIndex: MutableList<ClassIndexEntry>,
        beanIndex: MutableList<BeanDefinition>,
        injectionPointIndex: MutableList<InjectionPointDefinition>,
        componentScans: MutableList<ComponentScanDefinition>,
    ) {
        JarFile(jarPath.toFile()).use { jarFile ->
            jarFile.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".class") }
                .forEach { entry ->
                    jarFile.getInputStream(entry).use { input ->
                        scanClassBytes(input.readBytes(), classIndex, beanIndex, injectionPointIndex, componentScans)
                    }
                }
        }
    }

    private fun scanClassBytes(
        classBytes: ByteArray,
        classIndex: MutableList<ClassIndexEntry>,
        beanIndex: MutableList<BeanDefinition>,
        injectionPointIndex: MutableList<InjectionPointDefinition>,
        componentScans: MutableList<ComponentScanDefinition>,
    ) {
        val collector = ClassCollector()
        ClassReader(classBytes).accept(collector, ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES)
        val scannedClass = collector.toScannedClass() ?: return
        classIndex += scannedClass.classIndexEntry
        beanIndex += scannedClass.beanDefinitions
        injectionPointIndex += scannedClass.injectionPoints
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
                    entry.copy(genericSuperTypes = collectGenericSuperTypes(clazz))
                } ?: entry
            }
            val updatedBeans = index.beanIndex.map { bean -> enrichBeanDefinition(bean, classLoader) }
            val updatedInjections = index.injectionPointIndex.map { injection -> enrichInjectionPoint(injection, classLoader) }
            return index.copy(
                classIndex = updatedClassIndex,
                beanIndex = updatedBeans,
                injectionPointIndex = updatedInjections,
            )
        }
    }

    private fun loadClass(className: String, classLoader: ClassLoader): Class<*>? {
        return runCatching { Class.forName(className, false, classLoader) }.getOrNull()
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
        val method = ownerClass.declaredMethods.firstOrNull {
            it.name == bean.declarationName && it.returnType.name == bean.exposedType
        } ?: return bean
        return bean.copy(exposedGenericType = normalizeTypeName(method.genericReturnType.typeName))
    }

    private fun enrichInjectionPoint(injection: InjectionPointDefinition, classLoader: ClassLoader): InjectionPointDefinition {
        val ownerClass = loadClass(injection.ownerClassName, classLoader) ?: return injection
        val genericType = when (injection.kind) {
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
        return injection.copy(dependencyGenericType = genericType?.let(::normalizeTypeName))
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
        val componentScans: List<ComponentScanDefinition>,
    )

    private data class CapturedAnnotation(
        val className: String,
        val simpleName: String,
        val values: Map<String, Any>,
    )

    private data class PendingField(
        val name: String,
        val type: String,
        val annotations: List<CapturedAnnotation>,
    )

    private data class PendingMethod(
        val name: String,
        val returnType: String,
        val parameterTypes: List<String>,
        val annotations: List<CapturedAnnotation>,
        val parameterAnnotations: List<List<CapturedAnnotation>>,
    )

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
            if (skipped || access and Opcodes.ACC_SYNTHETIC != 0 || access and Opcodes.ACC_STATIC != 0) {
                return null
            }
            val annotations = mutableListOf<CapturedAnnotation>()
            return object : FieldVisitor(Opcodes.ASM9) {
                override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor {
                    return captureAnnotation(descriptor) { annotations += it }
                }

                override fun visitEnd() {
                    fields += PendingField(
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
            )
            val isBeanClass = classAnnotations.hasAnnotation("Bean") || classAnnotations.hasAnnotation("Configuration")
            val classConditions = classAnnotations.conditionDescriptors()
            val beans = mutableListOf<BeanDefinition>()
            if (isBeanClass) {
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
                )
            }

            val injections = mutableListOf<InjectionPointDefinition>()
            fields.filter { it.annotations.containsInjectionMetadata() }.forEach { field ->
                injections += InjectionPointDefinition(
                    ownerClassName = className,
                    declarationName = field.name,
                    dependencyType = field.type,
                    dependencyGenericType = null,
                    ownerPackage = packageName,
                    sourceFile = sourceFile,
                    kind = InjectionPointKind.FIELD,
                    parameterIndex = null,
                    qualifierName = field.annotations.qualifierName(),
                    required = field.annotations.requiredFlag(defaultValue = true),
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
                componentScans = scans,
            )
        }

        private fun selectConstructorMethods(isBeanClass: Boolean): List<PendingMethod> {
            val constructors = methods.filter { it.name == "<init>" && it.parameterTypes.isNotEmpty() }
            if (constructors.isEmpty()) {
                return emptyList()
            }
            val annotatedConstructors = constructors.filter { it.annotations.hasAnnotation("Inject") }
            if (annotatedConstructors.isNotEmpty()) {
                return annotatedConstructors
            }
            return if (isBeanClass && constructors.size == 1) constructors else emptyList()
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
        val beanAnnotation = annotations.findAnnotation("Bean")
        if (beanAnnotation == null) {
            return defaultName
        }
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