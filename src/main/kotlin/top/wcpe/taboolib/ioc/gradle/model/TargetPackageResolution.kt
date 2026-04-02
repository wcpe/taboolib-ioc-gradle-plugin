package top.wcpe.taboolib.ioc.gradle.model

internal data class TargetPackageResolution(
    val rootPackage: String,
    val source: String,
) {

    val relocationTarget: String = if (rootPackage.endsWith(".ioc")) {
        rootPackage
    } else {
        "$rootPackage.ioc"
    }
}