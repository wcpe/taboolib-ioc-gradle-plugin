package top.wcpe.taboolib.ioc.gradle

import org.gradle.api.GradleException

internal class TaboolibIocConfigurationException(message: String, cause: Throwable? = null) :
    GradleException(message, cause)