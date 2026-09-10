package top.wcpe.taboolib.ioc.gradle.model

internal data class TargetPackageResolution(
    val rootPackage: String,
    val source: String,
) {

    /**
     * 计算 relocate 的目标包。
     *
     * 规则：
     * - 仅当 [rootPackage] 以 `.ioc` 结尾、**且**它不是 [SOURCE_PACKAGE] 本身、
     *   也不是 [SOURCE_PACKAGE] 的前缀时，才视为用户已显式给出最终目标包，直接采用。
     * - 否则追加 `.ioc` 后缀。
     *
     * 之所以收紧判据：`top.wcpe.ioc` 这类以 `.ioc` 结尾但与源包（`top.wcpe.taboolib.ioc`）
     * 存在前缀关系的包名，若直接采纳会得到 `top.wcpe.taboolib.ioc -> top.wcpe.ioc`
     * 这种「父包收窄」的非法/自 relocate；而 `project.group` 恰好等于源包时更会退化为
     * 源 -> 源 的空 relocate。因此只有「源包与目标互不为前缀」时才允许直接采用。
     */
    val relocationTarget: String = resolveRelocationTarget(rootPackage)

    private fun resolveRelocationTarget(pkg: String): String {
        if (!pkg.endsWith(".ioc")) {
            return "$pkg.ioc"
        }
        // 目标与源包存在前缀包含关系（互为前缀）时不应直接采用，避免自 relocate / 父包收窄。
        if (pkg == SOURCE_PACKAGE || pkg.startsWith("$SOURCE_PACKAGE.") || SOURCE_PACKAGE.startsWith("$pkg.")) {
            return "$pkg.ioc"
        }
        return pkg
    }

    internal companion object {
        /** IoC 运行时的源包根，与 [top.wcpe.taboolib.ioc.gradle.TaboolibIocResolver.SOURCE_PACKAGE] 对齐。 */
        const val SOURCE_PACKAGE = "top.wcpe.taboolib.ioc"
    }
}
