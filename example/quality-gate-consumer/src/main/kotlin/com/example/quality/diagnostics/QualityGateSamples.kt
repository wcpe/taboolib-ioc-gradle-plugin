package com.example.quality.diagnostics

import top.wcpe.taboolib.ioc.analysis.ExternalAuditGateway

interface MessageBox<T> {
    fun value(): T
}

typealias GreetingBox = MessageBox<String>

interface GreetingFormatter {
    fun format(input: String): String
}

interface AuditReporter {
    fun report(message: String)
}

@Configuration
@ComponentScan(basePackages = ["com.example.quality", "top.wcpe.taboolib.ioc.analysis"])
class QualityGateConfiguration {

    @Bean
    fun greetingBox(): MessageBox<String> {
        return GreetingMessageBox()
    }

    @Bean
    @ConditionalOnProperty(name = ["feature.greeting"], havingValue = "on")
    fun greetingFormatter(): GreetingFormatter {
        return DefaultGreetingFormatter()
    }

    @Bean
    @ConditionalOnBean(type = ["top.wcpe.taboolib.ioc.analysis.ExternalAuditGateway"])
    fun auditReporter(externalAuditGateway: ExternalAuditGateway): AuditReporter {
        return DefaultAuditReporter(externalAuditGateway)
    }
}

class GreetingMessageBox : MessageBox<String> {
    override fun value(): String {
        return "quality-gate"
    }
}

class DefaultGreetingFormatter : GreetingFormatter {
    override fun format(input: String): String {
        return "[quality] $input"
    }
}

class DefaultAuditReporter(
    private val externalAuditGateway: ExternalAuditGateway,
) : AuditReporter {
    override fun report(message: String) {
        externalAuditGateway.record(message)
    }
}

@Bean
class QualityGateConsumer(
    private val greetingBox: GreetingBox,
    private val greetingFormatter: GreetingFormatter,
    private val auditReporter: AuditReporter,
) {
    fun message(): String {
        val payload = greetingFormatter.format(greetingBox.value())
        auditReporter.report(payload)
        return payload
    }
}