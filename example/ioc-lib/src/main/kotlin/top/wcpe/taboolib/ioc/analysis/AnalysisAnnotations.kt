package top.wcpe.taboolib.ioc.analysis

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class Bean(
    val value: String = "",
    val name: String = "",
    val beanName: String = "",
)