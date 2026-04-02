package com.example.demo

import top.wcpe.taboolib.ioc.ExampleIocService

class ExampleConsumer {

    fun message(): String {
        return ExampleIocService().value()
    }
}