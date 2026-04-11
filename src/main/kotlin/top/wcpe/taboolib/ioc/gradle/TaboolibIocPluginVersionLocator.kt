package top.wcpe.taboolib.ioc.gradle

import java.util.Properties

internal object TaboolibIocPluginVersionLocator {

    private const val VERSION_RESOURCE_PATH = "META-INF/taboolib-ioc-gradle-plugin.properties"
    private const val VERSION_KEY = "pluginVersion"

    fun resolveBundledVersion(): String? {
        val classLoader = TaboolibIocPluginVersionLocator::class.java.classLoader
        val resourceVersion = classLoader.getResourceAsStream(VERSION_RESOURCE_PATH)?.use { input ->
            Properties().apply { load(input) }
                .getProperty(VERSION_KEY)
                ?.trim()
                ?.takeUnless { it.isEmpty() || it.contains("\${") }
        }
        if (resourceVersion != null) {
            return resourceVersion
        }
        return TaboolibIocPluginVersionLocator::class.java.`package`
            ?.implementationVersion
            ?.trim()
            ?.takeUnless { it.isEmpty() }
    }
}