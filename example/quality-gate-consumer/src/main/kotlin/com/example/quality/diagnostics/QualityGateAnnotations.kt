package com.example.quality.diagnostics

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class Bean(
    val value: String = "",
    val name: String = "",
    val beanName: String = "",
)

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Configuration

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class ComponentScan(
    val value: Array<String> = [],
    val basePackages: Array<String> = [],
)

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class ConditionalOnProperty(
    val name: Array<String> = [],
    val value: Array<String> = [],
    val havingValue: String = "",
    val matchIfMissing: Boolean = false,
)

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class ConditionalOnBean(
    val name: Array<String> = [],
    val beanName: Array<String> = [],
    val value: Array<String> = [],
    val type: Array<String> = [],
)