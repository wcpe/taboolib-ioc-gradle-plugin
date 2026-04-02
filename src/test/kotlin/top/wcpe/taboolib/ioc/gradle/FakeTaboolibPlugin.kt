package top.wcpe.taboolib.ioc.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

class FakeTaboolibPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extras = project.extensions.extraProperties
        val createTaboo = (if (extras.has(CREATE_TABOO_CONFIGURATION_PROPERTY)) {
            extras.get(CREATE_TABOO_CONFIGURATION_PROPERTY)
        } else {
            null
        } as? Boolean) ?: true
        val createExtension = (if (extras.has(CREATE_TABOOLIB_EXTENSION_PROPERTY)) {
            extras.get(CREATE_TABOOLIB_EXTENSION_PROPERTY)
        } else {
            null
        } as? Boolean) ?: true

        if (createTaboo) {
            project.configurations.maybeCreate(TaboolibIocResolver.TABOO_CONFIGURATION_NAME)
        }
        if (createExtension) {
            project.extensions.add(TaboolibIocResolver.TABOOLIB_EXTENSION_NAME, FakeTaboolibExtension())
        }
    }

    companion object {
        const val CREATE_TABOO_CONFIGURATION_PROPERTY = "fakeTaboolib.createTabooConfiguration"
        const val CREATE_TABOOLIB_EXTENSION_PROPERTY = "fakeTaboolib.createTaboolibExtension"
    }
}

open class FakeTaboolibExtension {
    var subproject: Boolean = false
    val relocation: MutableMap<String, String> = linkedMapOf()
    val env: FakeTaboolibEnv = FakeTaboolibEnv()

    fun relocate(source: String, target: String) {
        relocation[source] = target
    }
}

open class FakeTaboolibEnv {
    var group: String? = null
}