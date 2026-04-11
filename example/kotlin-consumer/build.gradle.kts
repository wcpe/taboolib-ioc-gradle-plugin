import io.izzel.taboolib.gradle.Basic
import io.izzel.taboolib.gradle.Bukkit
import io.izzel.taboolib.gradle.BukkitHook
import io.izzel.taboolib.gradle.BukkitUI
import io.izzel.taboolib.gradle.BukkitUtil
import io.izzel.taboolib.gradle.CommandHelper
import io.izzel.taboolib.gradle.I18n
import io.izzel.taboolib.gradle.Kether

plugins {
    kotlin("jvm")
    id("io.izzel.taboolib") version "2.0.36"
    id("top.wcpe.taboolib.ioc") version "0.0.2"
}


repositories {
    mavenLocal()
    mavenCentral()
    maven("https://maven.wcpe.top/repository/maven-public/")
    maven("https://repo.tabooproject.org/repository/releases/")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}


taboolib {
    description {
        name("Taboolib-Ioc-Example")
        desc("Taboolib-IOC示例插件")
        contributors {
            name("WCPE")
        }
        dependencies {
        }
    }
    env {
        install(Basic)
        install(Bukkit)
        install(BukkitUtil)
        install(BukkitUI)
        install(BukkitHook)
        install(Kether)
        install(I18n)
        install(CommandHelper)
    }
    version { taboolib = "6.3.0-88720d8" }
}



taboolibIoc {
    // 是否启用自动接管：自动注入 IoC 依赖并自动追加 relocate 规则。
    autoTakeover(true)

    // IoC 依赖版本：当未显式指定 dependency(...) 时，会用于推导默认坐标版本。
    iocVersion("1.2.0-SNAPSHOT")

    // relocate 目标包根：最终会把 top.wcpe.taboolib.ioc 重定位到 com.example.custom.ioc。
    targetPackage("top.wcpe.mc.plugin.taboolib.ioc.example")

    // 静态诊断发现 error 时直接拦截构建。
    analysisFailOnError(true)

    // 静态诊断发现 warning 时不拦截构建，仅输出报告。
    analysisFailOnWarning(false)

    // 使用外部 Maven 坐标作为 IoC 依赖来源。
//    dependency("top.wcpe.taboolib.ioc:taboolib-ioc:1.0.0-SNAPSHOT")

}
