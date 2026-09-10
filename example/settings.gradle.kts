
pluginManagement {
    repositories {
        // 顺序敏感（本机代理环境实测结论）：
        // - wcpe maven-releases 前置：TabooLib 2.0.38-wcpe.1 只在此仓库，maven-public 不聚合（实测 404）；
        // - wcpe 镜像是部分镜像（kotlin-compiler-embeddable 等 只有 POM 没有 jar），
        //   Gradle 命中其元数据后即固定从该仓库取产物、不再回退，导致解析失败；
        // - Plugin Portal 产物经 303 重定向，在本机代理环境下不可用；
        // - 阿里云 public 是 Central 全量镜像且经代理可达，优先命中避开上述两条失败链路。
        maven("https://maven.wcpe.top/repository/maven-releases/")
        maven("https://maven.aliyun.com/repository/public")
        mavenLocal()
        maven("https://maven.wcpe.top/repository/maven-public/")
        mavenCentral()
        gradlePluginPortal()
    }

    plugins {
        id("top.wcpe.taboolib.ioc") version "0.0.6"
    }
}

include("groovy-consumer", "kotlin-consumer")
