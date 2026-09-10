package top.wcpe.taboolib.ioc.gradle.companionfixture

/**
 * B-P1-13：**真实 Kotlin 字节码** companion 跨类归并夹具。
 *
 * 现有 Java 夹具无法触发采集层「`$annotations` 载体放行 + companion 跨类归并」分支
 * （`BytecodeBeanIndexBuilder.kt` 中的 `carrierAnnotations` / `mergeCarrierAnnotations`），
 * 因为：
 * - Java 源码不会生成 `xxx$annotations` 载体方法；
 * - 即使 Kotlin 编译，若注入注解 `@Target(FIELD)`（如运行时 `@Inject`），
 *   注解会直接落在外部类静态 backing field 上，**不会**生成 Companion 侧载体。
 *
 * 本夹具用 **PROPERTY 目标**的 `Inject` / `Named` 注解（简单名匹配采集层白名单），
 * 在 Kotlin 1.9.25 下编译产出：
 * - 外部类 `CompanionInjectionHolder`：静态 backing field `dep`（注解不落在 field 上）；
 * - `CompanionInjectionHolder$Companion`：`getDep$annotations()` 载体方法携带 `@Inject`。
 *
 * 由此才能真实触发「第二阶段读取 Companion 的 `$annotations` 载体并跨类归并回外部类静态字段」
 * 这一分支（H3）。
 */

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Inject(val required: Boolean = true)

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Named(val value: String)

/** @JvmField：backing field 为外部类的 public static 字段。 */
class CompanionInjectionHolder {
    companion object {
        @Inject
        @JvmField
        var dep: String? = null
    }
}

/** 非 @JvmField：backing field 为外部类的 private static 字段。 */
class CompanionNamedHolder {
    companion object {
        @Inject
        @Named("namedDep")
        @JvmField
        var dep: String? = null
    }
}
