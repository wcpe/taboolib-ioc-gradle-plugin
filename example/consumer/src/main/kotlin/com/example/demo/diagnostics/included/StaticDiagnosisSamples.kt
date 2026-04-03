package com.example.demo.diagnostics.included

import com.example.demo.diagnostics.annotations.Bean
import com.example.demo.diagnostics.annotations.ComponentScan
import com.example.demo.diagnostics.annotations.ConditionalOnExpression
import com.example.demo.diagnostics.annotations.ConditionalOnProperty
import com.example.demo.diagnostics.annotations.Configuration
import com.example.demo.diagnostics.annotations.Inject
import com.example.demo.diagnostics.annotations.Named
import com.example.demo.diagnostics.annotations.Primary
import com.example.demo.diagnostics.annotations.Resource

interface MissingService

interface PaymentProcessor

interface AuditService

interface GreetingService

interface ConditionalService

interface UnknownConditionalService

interface RuntimeOnlyService

interface SingleMethodService

interface ScannedGateway

@Bean(name = "wrongType")
class WrongTypeAuditService : AuditService

@Bean
@Primary
class GreetingPrimaryOne : GreetingService

@Bean
@Primary
class GreetingPrimaryTwo : GreetingService

@Bean(name = "primaryProcessor")
class NamedPaymentProcessor : PaymentProcessor

@Bean
class AuditServiceOne : AuditService

@Bean
class AuditServiceTwo : AuditService

@Bean
class SingleMethodServiceImpl : SingleMethodService

@Configuration
@ComponentScan(basePackages = ["com.example.demo.diagnostics.included"])
class StaticDiagnosisConfiguration {

    @Bean(name = "namedProcessor")
    fun namedProcessor(): PaymentProcessor {
        return NamedPaymentProcessor()
    }

    @Bean
    @ConditionalOnProperty(name = "feature.conditional")
    fun conditionalService(): ConditionalService {
        return ConditionalServiceImpl()
    }

    @Bean
    @ConditionalOnExpression("${'$'}{feature.dynamic:true}")
    fun unknownConditionalService(): UnknownConditionalService {
        return UnknownConditionalServiceImpl()
    }

    @Bean
    fun methodInjectedBean(auditService: AuditService): MethodInjectedBean {
        return MethodInjectedBean(auditService)
    }
}

class ConditionalServiceImpl : ConditionalService

class UnknownConditionalServiceImpl : UnknownConditionalService

class MethodInjectedBean(private val auditService: AuditService)

@Bean
class MissingBeanConsumer(private val missingService: MissingService)

@Bean
class MissingNamedConsumer(
    @param:Named("ghostProcessor")
    private val paymentProcessor: PaymentProcessor,
)

@Bean
class NamedTypeMismatchConsumer {

    @field:Resource(name = "wrongType")
    lateinit var paymentProcessor: PaymentProcessor
}

@Bean
class MultiplePrimaryConsumer(private val greetingService: GreetingService)

@Bean
class MultipleCandidatesConsumer(private val auditService: AuditService)

@Bean
class ConditionalOnlyConsumer(private val conditionalService: ConditionalService)

@Bean
class UnknownConditionalConsumer(private val unknownConditionalService: UnknownConditionalService)

@Bean
class RuntimeManualConsumer {

    @field:Inject(required = false)
    var runtimeOnlyService: RuntimeOnlyService? = null
}

@Bean
class ComponentScanExcludedConsumer(private val scannedGateway: ScannedGateway)

@Bean
class InjectMethodConsumer {

    @Inject
    fun setSingleMethodService(singleMethodService: SingleMethodService) {
        singleMethodService.toString()
    }
}