package top.wcpe.mc.plugin.taboolib.ioc.example

import top.wcpe.taboolib.ioc.annotation.Component

@Component
class ExampleIocService {

    fun print(){
        println("IocService")
    }

}