package top.wcpe.mc.plugin.taboolib.ioc.example

import top.wcpe.taboolib.ioc.annotation.Inject

object GroovyDslExampleConsumer {
    @Inject
    lateinit var exampleIocService: ExampleIocService

    fun message() {
        exampleIocService.print()
    }
}