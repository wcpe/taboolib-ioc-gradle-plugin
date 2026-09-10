pluginManagement {
    repositories {
        // 顺序敏感（本机代理环境实测结论）：
        // - 经本机 Gradle 代理时：repo.maven.apache.org 的 TLS 握手被远端重置；
        //   Plugin Portal 产物经 303 重定向后返回 0 字节 —— 两条链路均不可用。
        // - maven.aliyun.com 与 maven.wcpe.top 经代理可达；阿里云 public 是 Central 全量镜像。
        // 因此阿里云镜像优先解析，mavenCentral 供 CI/无代理环境兜底，wcpe 只兜底私有产物。
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.wcpe.top/repository/maven-public/")
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "taboolib-ioc-gradle-plugin"

include("plugin-integration-tests")
