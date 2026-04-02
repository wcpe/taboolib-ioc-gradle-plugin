package top.wcpe.taboolib.ioc.gradle.backend

enum class PackagingBackendId {
    TABOOLIB,
    STANDALONE,
    ;

    companion object {
        fun parse(value: String?): PackagingBackendId? {
            return values().firstOrNull { it.name.equals(value, ignoreCase = true) }
        }
    }
}