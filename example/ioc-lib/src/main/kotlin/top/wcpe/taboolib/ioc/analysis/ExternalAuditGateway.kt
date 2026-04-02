package top.wcpe.taboolib.ioc.analysis

interface ExternalAuditGateway {
    fun record(message: String)
}

@Bean
class ExampleExternalAuditGateway : ExternalAuditGateway {
    override fun record(message: String) {
        message.length
    }
}