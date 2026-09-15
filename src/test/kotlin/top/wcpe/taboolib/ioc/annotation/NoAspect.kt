package top.wcpe.taboolib.ioc.annotation

/**
 * 测试专用桩：与真实注解**同包同名**，因为织入器是按描述符
 * `Ltop/wcpe/taboolib/ioc/annotation/NoAspect;` 比对的。
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class NoAspect
