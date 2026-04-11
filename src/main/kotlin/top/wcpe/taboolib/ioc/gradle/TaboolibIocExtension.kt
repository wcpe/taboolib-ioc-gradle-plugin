package top.wcpe.taboolib.ioc.gradle

import javax.inject.Inject
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import top.wcpe.taboolib.ioc.gradle.backend.PackagingBackendId

abstract class TaboolibIocExtension @Inject constructor(objects: ObjectFactory) {

    val autoTakeover: Property<Boolean> = objects.property(Boolean::class.java)

    val backend: Property<PackagingBackendId> = objects.property(PackagingBackendId::class.java)

    val iocVersion: Property<String> = objects.property(String::class.java)

    val targetPackage: Property<String> = objects.property(String::class.java)

    val dependencyNotation: Property<String> = objects.property(String::class.java)

    val localProjectPath: Property<String> = objects.property(String::class.java)

    val analysisFailOnError: Property<Boolean> = objects.property(Boolean::class.java)

    val analysisFailOnWarning: Property<Boolean> = objects.property(Boolean::class.java)

    fun autoTakeover(enabled: Boolean) {
        autoTakeover.set(enabled)
    }

    fun backend(backendId: PackagingBackendId) {
        backend.set(backendId)
    }

    fun backend(backendId: String) {
        val parsed = PackagingBackendId.parse(backendId)
            ?: throw TaboolibIocConfigurationException("未知后端 '$backendId'，可选值：TABOOLIB、STANDALONE。")
        backend.set(parsed)
    }

    fun iocVersion(version: String) {
        iocVersion.set(version)
    }

    fun targetPackage(packageName: String) {
        targetPackage.set(packageName)
    }

    fun analysisFailOnError(enabled: Boolean) {
        analysisFailOnError.set(enabled)
    }

    fun analysisFailOnWarning(enabled: Boolean) {
        analysisFailOnWarning.set(enabled)
    }

    fun dependency(notation: String) {
        dependencyNotation.set(notation)
    }

    fun useLocalProject(path: String) {
        localProjectPath.set(path)
    }

    fun useModule(path: String) {
        useLocalProject(path)
    }
}