
pluginManagement {
    repositories {
        // 顺序敏感（本机代理环境实测结论）：
        // - mavenLocal **必须**前置：插件解析按仓库顺序首个命中即止，而 example 固定声明
        //   version "0.0.11" 且该版本已发布到 maven-releases。若 releases 排在前面，CI 的
        //   publishToMavenLocal 与冒烟测试的版本对齐全部空转，验的是**远端已发布插件**而不是
        //   本次构建产物 —— 改坏源码照样全绿。mavenLocal 未命中时会自然回退，不影响首次拉取。
        // - wcpe maven-releases 紧随其后：TabooLib 2.0.38-wcpe.1 只在此仓库，maven-public 不聚合（实测 404）；
        // - wcpe 镜像是部分镜像（kotlin-compiler-embeddable 等 只有 POM 没有 jar），
        //   Gradle 命中其元数据后即固定从该仓库取产物、不再回退，导致解析失败；
        // - Plugin Portal 产物经 303 重定向，在本机代理环境下不可用；
        // - 阿里云 public 是 Central 全量镜像且经代理可达，优先命中避开上述两条失败链路。
        mavenLocal()
        maven("https://maven.wcpe.top/repository/maven-releases/")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.wcpe.top/repository/maven-public/")
        mavenCentral()
        gradlePluginPortal()
    }

    plugins {
        id("top.wcpe.taboolib.ioc") version "0.1.0"
    }
}

include("groovy-consumer", "kotlin-consumer")
